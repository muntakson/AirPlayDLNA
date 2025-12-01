package com.airplay.dlna.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.airplay.dlna.DLNAApplication
import com.airplay.dlna.R
import com.airplay.dlna.model.DLNADevice
import com.airplay.dlna.model.StreamingState
import com.airplay.dlna.ui.MainActivity
import com.airplay.dlna.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jupnp.UpnpServiceImpl
import org.jupnp.android.AndroidUpnpServiceConfiguration
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.meta.Device
import org.jupnp.model.meta.LocalDevice
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.types.UDAServiceType
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import org.jupnp.support.avtransport.callback.GetTransportInfo
import org.jupnp.support.avtransport.callback.Pause
import org.jupnp.support.avtransport.callback.Play
import org.jupnp.support.avtransport.callback.SetAVTransportURI
import org.jupnp.support.avtransport.callback.Stop
import org.jupnp.support.model.TransportInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import org.jupnp.model.meta.Service as UpnpService

/**
 * Service responsible for DLNA device discovery and media control.
 * Uses multiple discovery methods: UPnP/DLNA, mDNS, and network scanning.
 */
class DLNAService : Service() {

    companion object {
        private const val TAG = "DLNAService"
        private const val NOTIFICATION_ID = 1001

        // UPnP Service Types
        private val AV_TRANSPORT_SERVICE = UDAServiceType("AVTransport")

        // mDNS service types to search for
        private val MDNS_SERVICE_TYPES = listOf(
            "_airplay._tcp.local.",
            "_raop._tcp.local.",
            "_eshare._tcp.local.",
            "_googlecast._tcp.local.",
            "_mediarenderer._tcp.local."
        )

        // Common DLNA/streaming ports to scan
        private val COMMON_PORTS = listOf(7000, 7100, 8008, 8009, 8080, 8443, 9000, 49152, 49153, 49154)
    }

    // Binder for local service binding
    private val binder = LocalBinder()

    // Coroutine scope for async operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // WiFi multicast lock for UPnP discovery
    private var multicastLock: WifiManager.MulticastLock? = null

    // JUpnP service
    private var upnpService: UpnpServiceImpl? = null

    // mDNS/JmDNS
    private var jmdns: JmDNS? = null

    // NSD Manager for Android's native service discovery
    private var nsdManager: NsdManager? = null

    // Discovered devices (combined from all methods)
    private val _devices = MutableStateFlow<List<DLNADevice>>(emptyList())
    val devices: StateFlow<List<DLNADevice>> = _devices.asStateFlow()

    // Discovered generic devices (mDNS/scan results)
    private val _genericDevices = MutableStateFlow<List<GenericDevice>>(emptyList())
    val genericDevices: StateFlow<List<GenericDevice>> = _genericDevices.asStateFlow()

    // Discovery state
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    // Streaming state
    private val _streamingState = MutableStateFlow<StreamingState>(StreamingState.Idle)
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    // Currently selected device
    private var currentDevice: DLNADevice? = null
    private var currentGenericDevice: GenericDevice? = null

    /**
     * Data class for devices found via mDNS or network scan
     */
    data class GenericDevice(
        val name: String,
        val host: String,
        val port: Int,
        val type: String,
        var isSelected: Boolean = false
    ) {
        val id: String get() = "$host:$port"
    }

    /**
     * Binder class for local service binding
     */
    inner class LocalBinder : Binder() {
        fun getService(): DLNAService = this@DLNAService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DLNAService created")
        acquireMulticastLock()
        initUpnpService()
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DLNAService destroyed")

        // Clean up
        stopDiscovery()
        releaseMulticastLock()
        shutdownUpnpService()
        stopMdnsDiscovery()
        serviceScope.cancel()
    }

    /**
     * Acquires WiFi multicast lock for UPnP discovery.
     */
    private fun acquireMulticastLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("DLNAService")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()
        Log.d(TAG, "Multicast lock acquired")
    }

    /**
     * Releases the WiFi multicast lock.
     */
    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Multicast lock released")
            }
        }
        multicastLock = null
    }

    /**
     * Initializes the JUpnP service.
     */
    private fun initUpnpService() {
        try {
            upnpService = UpnpServiceImpl(AndroidUpnpServiceConfiguration())
            upnpService?.registry?.addListener(registryListener)
            Log.d(TAG, "UPnP service initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing UPnP service", e)
        }
    }

    /**
     * Shuts down the JUpnP service.
     */
    private fun shutdownUpnpService() {
        try {
            upnpService?.registry?.removeListener(registryListener)
            upnpService?.shutdown()
            upnpService = null
            Log.d(TAG, "UPnP service shut down")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down UPnP service", e)
        }
    }

    /**
     * Registry listener for DLNA device discovery events.
     */
    private val registryListener = object : DefaultRegistryListener() {
        override fun remoteDeviceAdded(registry: Registry?, device: RemoteDevice?) {
            device?.let { addDevice(it) }
        }

        override fun remoteDeviceRemoved(registry: Registry?, device: RemoteDevice?) {
            device?.let { removeDevice(it) }
        }

        override fun localDeviceAdded(registry: Registry?, device: LocalDevice?) {}
        override fun localDeviceRemoved(registry: Registry?, device: LocalDevice?) {}
    }

    /**
     * Adds a discovered UPnP device to the list.
     */
    private fun addDevice(device: Device<*, *, *>) {
        val avTransportService = device.findService(AV_TRANSPORT_SERVICE)
        if (avTransportService != null) {
            val dlnaDevice = DLNADevice(
                device = device,
                name = device.details?.friendlyName ?: "Unknown Device",
                type = device.type?.displayString ?: "Unknown"
            )

            serviceScope.launch {
                val currentList = _devices.value.toMutableList()
                if (!currentList.any { it.id == dlnaDevice.id }) {
                    currentList.add(dlnaDevice)
                    _devices.value = currentList
                    Log.d(TAG, "UPnP Device added: ${dlnaDevice.name}")
                }
            }
        }
    }

    /**
     * Removes a device from the list.
     */
    private fun removeDevice(device: Device<*, *, *>) {
        val deviceId = device.identity.udn.identifierString
        serviceScope.launch {
            val currentList = _devices.value.toMutableList()
            currentList.removeAll { it.id == deviceId }
            _devices.value = currentList
        }
    }

    /**
     * Adds a generic device (from mDNS or scan).
     * Prioritizes mDNS-discovered devices and avoids duplicates on same host.
     */
    private fun addGenericDevice(device: GenericDevice, priority: Boolean = false) {
        serviceScope.launch {
            val currentList = _genericDevices.value.toMutableList()

            // Check if we already have a device with the same host
            val existingIndex = currentList.indexOfFirst { it.host == device.host }

            if (existingIndex >= 0) {
                // If existing device is from network scan and new one is from mDNS (priority), replace it
                val existing = currentList[existingIndex]
                if (priority && existing.type == "Network Device") {
                    currentList[existingIndex] = device
                    _genericDevices.value = currentList
                    Log.d(TAG, "Generic device updated (mDNS priority): ${device.name} at ${device.host}:${device.port}")
                }
                // Otherwise keep existing device
            } else {
                currentList.add(device)
                _genericDevices.value = currentList
                Log.d(TAG, "Generic device added: ${device.name} at ${device.host}:${device.port}")
            }
        }
    }

    /**
     * Starts all discovery methods.
     */
    fun startDiscovery() {
        if (_isDiscovering.value) {
            Log.d(TAG, "Discovery already in progress")
            return
        }

        _isDiscovering.value = true
        _devices.value = emptyList()
        _genericDevices.value = emptyList()

        serviceScope.launch(Dispatchers.IO) {
            // Start UPnP discovery
            try {
                upnpService?.controlPoint?.search()
                Log.d(TAG, "UPnP discovery started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting UPnP discovery", e)
            }

            // Start mDNS discovery
            startMdnsDiscovery()

            // Start network scanning for EShare devices
            startNetworkScan()

            // Stop discovery indicator after 15 seconds
            delay(15000)
            launch(Dispatchers.Main) {
                _isDiscovering.value = false
            }
        }
    }

    /**
     * Starts mDNS/Bonjour discovery using JmDNS
     */
    private fun startMdnsDiscovery() {
        try {
            val localIp = NetworkUtils.getLocalIpAddress(this)
            if (localIp != null) {
                val inetAddress = InetAddress.getByName(localIp)
                jmdns = JmDNS.create(inetAddress, "DLNAStreamer")

                val serviceListener = object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        Log.d(TAG, "mDNS service added: ${event.name} (${event.type})")
                        // Request more info
                        jmdns?.requestServiceInfo(event.type, event.name)
                    }

                    override fun serviceRemoved(event: ServiceEvent) {
                        Log.d(TAG, "mDNS service removed: ${event.name}")
                    }

                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info
                        Log.d(TAG, "mDNS service resolved: ${info.name} (${event.type}) at ${info.hostAddresses.firstOrNull()}:${info.port}")

                        info.hostAddresses.firstOrNull()?.let { host ->
                            // Determine friendly device type name and correct port
                            val isRaop = event.type.contains("raop", ignoreCase = true)

                            val deviceType = when {
                                event.type.contains("eshare", ignoreCase = true) -> "EShare"
                                event.type.contains("airplay", ignoreCase = true) -> "AirPlay"
                                isRaop -> "AirPlay"  // Treat RAOP as AirPlay for video
                                event.type.contains("googlecast", ignoreCase = true) -> "Chromecast"
                                else -> "Media Renderer"
                            }

                            // For RAOP services, the video port is typically 7000
                            // RAOP port is for audio, but video uses a different port
                            val videoPort = if (isRaop) 7000 else info.port

                            // Clean up device name (remove MAC address prefix if present)
                            val cleanName = if (info.name.contains("@")) {
                                info.name.substringAfter("@")
                            } else {
                                info.name
                            }

                            addGenericDevice(GenericDevice(
                                name = cleanName,
                                host = host,
                                port = videoPort,
                                type = deviceType
                            ), priority = true)  // mDNS devices have priority

                            // Also try to probe the actual AirPlay port
                            if (isRaop) {
                                serviceScope.launch(Dispatchers.IO) {
                                    probeAirPlayPort(host, cleanName)
                                }
                            }
                        }
                    }
                }

                MDNS_SERVICE_TYPES.forEach { serviceType ->
                    jmdns?.addServiceListener(serviceType, serviceListener)
                    Log.d(TAG, "Listening for mDNS type: $serviceType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting mDNS discovery", e)
        }
    }

    /**
     * Stops mDNS discovery
     */
    private fun stopMdnsDiscovery() {
        try {
            jmdns?.close()
            jmdns = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mDNS", e)
        }
    }

    /**
     * Probes for the correct AirPlay video port on a device.
     */
    private fun probeAirPlayPort(host: String, deviceName: String) {
        val commonPorts = listOf(7000, 7100, 47000, 5000)

        for (port in commonPorts) {
            try {
                // Try to connect and get server info
                val url = URL("http://$host:$port/server-info")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000

                val responseCode = connection.responseCode
                connection.disconnect()

                if (responseCode in 200..299) {
                    Log.d(TAG, "Found AirPlay video port at $host:$port")
                    // Update the device with correct port
                    addGenericDevice(GenericDevice(
                        name = deviceName,
                        host = host,
                        port = port,
                        type = "AirPlay"
                    ), priority = true)
                    return
                }
            } catch (e: Exception) {
                // Port not available, try next
            }
        }
        Log.d(TAG, "Could not find AirPlay video port for $host, using default 7000")
    }

    /**
     * Scans the local network for EShare and similar devices
     */
    private fun startNetworkScan() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val localIp = NetworkUtils.getLocalIpAddress(this@DLNAService) ?: return@launch
                val subnet = localIp.substringBeforeLast(".")

                Log.d(TAG, "Starting network scan on subnet: $subnet.*")

                // Scan common IP range
                for (i in 1..254) {
                    val host = "$subnet.$i"

                    // Skip our own IP
                    if (host == localIp) continue

                    launch(Dispatchers.IO) {
                        // Try common streaming ports
                        COMMON_PORTS.forEach { port ->
                            if (isPortOpen(host, port, 100)) {
                                val deviceName = identifyDevice(host, port)
                                addGenericDevice(GenericDevice(
                                    name = deviceName,
                                    host = host,
                                    port = port,
                                    type = "Network Device"
                                ))
                            }
                        }
                    }
                }

                // Also try EShare-specific discovery
                sendEShareDiscovery()

            } catch (e: Exception) {
                Log.e(TAG, "Error during network scan", e)
            }
        }
    }

    /**
     * Checks if a port is open on a host
     */
    private fun isPortOpen(host: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeout)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Tries to identify a device by connecting to it
     */
    private fun identifyDevice(host: String, port: Int): String {
        return when (port) {
            7000, 7100 -> "AirPlay Device ($host)"
            8008, 8009 -> "Chromecast ($host)"
            8080 -> "Media Server ($host)"
            else -> "Streaming Device ($host:$port)"
        }
    }

    /**
     * Sends EShare-specific UDP discovery packet
     */
    private fun sendEShareDiscovery() {
        try {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 3000

            // EShare uses a specific discovery protocol
            // Send broadcast on common EShare ports
            val discoveryPorts = listOf(48689, 8121, 2425)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")

            discoveryPorts.forEach { port ->
                try {
                    // Simple discovery message
                    val message = "ESHARE_DISCOVER".toByteArray()
                    val packet = DatagramPacket(message, message.size, broadcastAddr, port)
                    socket.send(packet)

                    // Try to receive response
                    val buffer = ByteArray(1024)
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(responsePacket)
                        val response = String(responsePacket.data, 0, responsePacket.length)
                        val responderHost = responsePacket.address.hostAddress
                        Log.d(TAG, "EShare response from $responderHost: $response")

                        if (responderHost != null) {
                            addGenericDevice(GenericDevice(
                                name = "EShare Device",
                                host = responderHost,
                                port = port,
                                type = "EShare"
                            ))
                        }
                    } catch (e: Exception) {
                        // Timeout, no response
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending EShare discovery on port $port", e)
                }
            }

            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error in EShare discovery", e)
        }
    }

    /**
     * Stops all discovery.
     */
    fun stopDiscovery() {
        _isDiscovering.value = false
        stopMdnsDiscovery()
        Log.d(TAG, "Discovery stopped")
    }

    /**
     * Refreshes the device list.
     */
    fun refreshDevices() {
        _devices.value = emptyList()
        _genericDevices.value = emptyList()
        startDiscovery()
    }

    /**
     * Selects a DLNA device for streaming.
     */
    fun selectDevice(device: DLNADevice) {
        currentDevice = device
        currentGenericDevice = null

        serviceScope.launch {
            val updatedList = _devices.value.map {
                it.copy(isSelected = it.id == device.id)
            }
            _devices.value = updatedList

            // Deselect generic devices
            val updatedGeneric = _genericDevices.value.map {
                it.copy(isSelected = false)
            }
            _genericDevices.value = updatedGeneric
        }

        Log.d(TAG, "Device selected: ${device.name}")
    }

    /**
     * Selects a generic device for streaming.
     */
    fun selectGenericDevice(device: GenericDevice) {
        currentGenericDevice = device
        currentDevice = null

        serviceScope.launch {
            // Deselect DLNA devices
            val updatedDlna = _devices.value.map {
                it.copy(isSelected = false)
            }
            _devices.value = updatedDlna

            val updatedList = _genericDevices.value.map {
                it.copy(isSelected = it.id == device.id)
            }
            _genericDevices.value = updatedList
        }

        Log.d(TAG, "Generic device selected: ${device.name}")
    }

    /**
     * Gets the currently selected device.
     */
    fun getSelectedDevice(): DLNADevice? = currentDevice
    fun getSelectedGenericDevice(): GenericDevice? = currentGenericDevice

    /**
     * Starts streaming a video to the selected device.
     */
    fun startStreaming(videoUrl: String, videoTitle: String, mimeType: String) {
        val device = currentDevice
        val genericDevice = currentGenericDevice

        when {
            device != null -> startDlnaStreaming(device, videoUrl, videoTitle, mimeType)
            genericDevice != null -> startGenericStreaming(genericDevice, videoUrl, videoTitle, mimeType)
            else -> _streamingState.value = StreamingState.Error("No device selected")
        }
    }

    /**
     * Starts DLNA streaming to a UPnP device.
     */
    private fun startDlnaStreaming(device: DLNADevice, videoUrl: String, videoTitle: String, mimeType: String) {
        _streamingState.value = StreamingState.Preparing

        serviceScope.launch(Dispatchers.IO) {
            try {
                @Suppress("UNCHECKED_CAST")
                val avTransportService = device.device.findService(AV_TRANSPORT_SERVICE) as? UpnpService<*, *>
                if (avTransportService == null) {
                    launch(Dispatchers.Main) {
                        _streamingState.value = StreamingState.Error("Device doesn't support AVTransport")
                    }
                    return@launch
                }

                setAVTransportURI(avTransportService, videoUrl, videoTitle, mimeType) { success ->
                    if (success) {
                        play(avTransportService) { playSuccess ->
                            serviceScope.launch(Dispatchers.Main) {
                                if (playSuccess) {
                                    _streamingState.value = StreamingState.Streaming(
                                        videoName = videoTitle,
                                        deviceName = device.name
                                    )
                                } else {
                                    _streamingState.value = StreamingState.Error("Failed to start playback")
                                }
                            }
                        }
                    } else {
                        serviceScope.launch(Dispatchers.Main) {
                            _streamingState.value = StreamingState.Error("Failed to set media URI")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting DLNA streaming", e)
                launch(Dispatchers.Main) {
                    _streamingState.value = StreamingState.Error("Streaming error: ${e.message}")
                }
            }
        }
    }

    /**
     * Starts streaming to a generic device (tries AirPlay and other protocols).
     */
    private fun startGenericStreaming(device: GenericDevice, videoUrl: String, videoTitle: String, mimeType: String) {
        _streamingState.value = StreamingState.Preparing

        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Attempting streaming to ${device.host}:${device.port}")
                Log.d(TAG, "Video URL: $videoUrl")

                // Try AirPlay protocol first (used by EShare and similar devices)
                val airplaySuccess = tryAirPlayStreaming(device, videoUrl, videoTitle)

                if (airplaySuccess) {
                    launch(Dispatchers.Main) {
                        _streamingState.value = StreamingState.Streaming(
                            videoName = videoTitle,
                            deviceName = device.name
                        )
                    }
                } else {
                    // Try alternative methods
                    val altSuccess = tryAlternativeStreaming(device, videoUrl, videoTitle, mimeType)

                    launch(Dispatchers.Main) {
                        if (altSuccess) {
                            _streamingState.value = StreamingState.Streaming(
                                videoName = videoTitle,
                                deviceName = device.name
                            )
                        } else {
                            _streamingState.value = StreamingState.Error("Device doesn't support streaming protocol")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error starting generic streaming", e)
                launch(Dispatchers.Main) {
                    _streamingState.value = StreamingState.Error("Streaming error: ${e.message}")
                }
            }
        }
    }

    /**
     * Tries to stream using AirPlay protocol.
     * AirPlay uses HTTP POST to /play endpoint with specific headers.
     */
    private fun tryAirPlayStreaming(device: GenericDevice, videoUrl: String, videoTitle: String): Boolean {
        // Try the device's discovered port first, then common AirPlay ports
        val portsToTry = listOf(device.port, 7000, 7100)

        for (port in portsToTry) {
            // Try multiple content types as different devices expect different formats
            if (tryAirPlayWithContentType(device.host, port, videoUrl, "text/parameters")) {
                return true
            }
            if (tryAirPlayWithContentType(device.host, port, videoUrl, "application/x-apple-binary-plist")) {
                return true
            }
        }

        return false
    }

    /**
     * Tries AirPlay streaming with a specific content type.
     */
    private fun tryAirPlayWithContentType(host: String, port: Int, videoUrl: String, contentType: String): Boolean {
        try {
            Log.d(TAG, "Trying AirPlay on $host:$port with $contentType")

            val url = URL("http://$host:$port/play")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true

            // AirPlay headers
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("User-Agent", "iTunes/12.2 (Macintosh; OS X 10.10.5)")
            connection.setRequestProperty("X-Apple-Session-ID", java.util.UUID.randomUUID().toString())
            connection.setRequestProperty("X-Apple-Device-ID", "0x0000000000000001")

            // AirPlay body format
            val body = "Content-Location: $videoUrl\nStart-Position: 0.0\n"

            val outputStream = connection.outputStream
            outputStream.write(body.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            Log.d(TAG, "AirPlay /play response code: $responseCode")

            connection.disconnect()

            if (responseCode in 200..299) {
                Log.d(TAG, "AirPlay streaming started successfully on port $port")
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "AirPlay failed on $host:$port: ${e.message}")
        }

        return false
    }

    /**
     * Tries alternative streaming methods for devices that don't support standard AirPlay.
     */
    private fun tryAlternativeStreaming(device: GenericDevice, videoUrl: String, videoTitle: String, mimeType: String): Boolean {
        // Try EShare-specific protocol
        if (device.type.contains("EShare", ignoreCase = true) ||
            device.name.contains("EShare", ignoreCase = true)) {
            return tryEShareStreaming(device, videoUrl, videoTitle)
        }

        // Try Chromecast-like protocol
        if (device.port == 8008 || device.port == 8009) {
            return tryChromecastStreaming(device, videoUrl, videoTitle, mimeType)
        }

        // Try generic HTTP streaming endpoints
        return tryGenericHttpStreaming(device, videoUrl, videoTitle)
    }

    /**
     * Tries EShare-specific streaming protocol.
     */
    private fun tryEShareStreaming(device: GenericDevice, videoUrl: String, videoTitle: String): Boolean {
        try {
            // EShare devices often accept AirPlay-like commands on their discovered port
            val url = URL("http://${device.host}:${device.port}/play")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true

            connection.setRequestProperty("Content-Type", "application/x-apple-binary-plist")
            connection.setRequestProperty("User-Agent", "AirPlay/320.20")

            // Simple body with video URL
            val body = "Content-Location: $videoUrl\nStart-Position: 0\n"

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(body)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            Log.d(TAG, "EShare streaming response: $responseCode")

            if (responseCode in 200..299) {
                return true
            }

            connection.disconnect()
        } catch (e: Exception) {
            Log.d(TAG, "EShare streaming failed: ${e.message}")
        }

        // Try alternate EShare API endpoint
        try {
            val url = URL("http://${device.host}:${device.port}/stream")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true

            connection.setRequestProperty("Content-Type", "application/json")

            val jsonBody = """{"url":"$videoUrl","title":"$videoTitle"}"""

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(jsonBody)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            Log.d(TAG, "EShare /stream response: $responseCode")

            return responseCode in 200..299
        } catch (e: Exception) {
            Log.d(TAG, "EShare /stream failed: ${e.message}")
        }

        return false
    }

    /**
     * Tries Chromecast-like streaming.
     */
    private fun tryChromecastStreaming(device: GenericDevice, videoUrl: String, videoTitle: String, mimeType: String): Boolean {
        try {
            val url = URL("http://${device.host}:${device.port}/apps/YouTube")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true

            connection.setRequestProperty("Content-Type", "application/json")

            val jsonBody = """{"v":"$videoUrl"}"""

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(jsonBody)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            Log.d(TAG, "Chromecast streaming response: $responseCode")

            return responseCode in 200..299
        } catch (e: Exception) {
            Log.d(TAG, "Chromecast streaming failed: ${e.message}")
        }

        return false
    }

    /**
     * Tries generic HTTP streaming endpoints.
     */
    private fun tryGenericHttpStreaming(device: GenericDevice, videoUrl: String, videoTitle: String): Boolean {
        val endpoints = listOf("/play", "/video", "/stream", "/media", "/cast")

        for (endpoint in endpoints) {
            try {
                val url = URL("http://${device.host}:${device.port}$endpoint")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.doOutput = true

                connection.setRequestProperty("Content-Type", "text/parameters")

                val body = "Content-Location: $videoUrl\n"

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(body)
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                Log.d(TAG, "Generic $endpoint response: $responseCode")

                if (responseCode in 200..299) {
                    return true
                }

                connection.disconnect()
            } catch (e: Exception) {
                // Continue to next endpoint
            }
        }

        return false
    }

    /**
     * Stops the current streaming session.
     */
    fun stopStreaming() {
        val dlnaDevice = currentDevice
        val genericDevice = currentGenericDevice

        when {
            dlnaDevice != null -> stopDlnaStreaming(dlnaDevice)
            genericDevice != null -> stopGenericStreaming(genericDevice)
            else -> {
                // No device selected, just reset state
                _streamingState.value = StreamingState.Stopped
            }
        }
    }

    /**
     * Stops DLNA streaming.
     */
    private fun stopDlnaStreaming(device: DLNADevice) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                @Suppress("UNCHECKED_CAST")
                val avTransportService = device.device.findService(AV_TRANSPORT_SERVICE) as? UpnpService<*, *>
                if (avTransportService != null) {
                    stop(avTransportService) { success ->
                        serviceScope.launch(Dispatchers.Main) {
                            _streamingState.value = if (success) {
                                StreamingState.Stopped
                            } else {
                                StreamingState.Error("Failed to stop streaming")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping DLNA streaming", e)
                launch(Dispatchers.Main) {
                    _streamingState.value = StreamingState.Stopped
                }
            }
        }
    }

    /**
     * Stops streaming to a generic/AirPlay device.
     */
    private fun stopGenericStreaming(device: GenericDevice) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Stopping streaming on ${device.host}:${device.port}")

                // Try AirPlay stop endpoint
                val stopped = tryAirPlayStop(device)

                launch(Dispatchers.Main) {
                    _streamingState.value = StreamingState.Stopped
                }

                if (stopped) {
                    Log.d(TAG, "Successfully stopped streaming via AirPlay")
                } else {
                    Log.d(TAG, "Stop command sent (device may not confirm)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error stopping generic streaming", e)
                launch(Dispatchers.Main) {
                    _streamingState.value = StreamingState.Stopped
                }
            }
        }
    }

    /**
     * Sends AirPlay stop command.
     */
    private fun tryAirPlayStop(device: GenericDevice): Boolean {
        val portsToTry = listOf(device.port, 7000, 7100)

        for (port in portsToTry) {
            try {
                // AirPlay /stop endpoint
                val url = URL("http://${device.host}:$port/stop")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                connection.setRequestProperty("User-Agent", "MediaControl/1.0")

                val responseCode = connection.responseCode
                Log.d(TAG, "AirPlay /stop response code: $responseCode")

                connection.disconnect()

                if (responseCode in 200..299) {
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "AirPlay stop failed on port $port: ${e.message}")
            }
        }

        return false
    }

    /**
     * Pauses the current streaming session.
     */
    fun pauseStreaming() {
        val device = currentDevice ?: return
        val currentState = _streamingState.value

        if (currentState !is StreamingState.Streaming) return

        serviceScope.launch(Dispatchers.IO) {
            try {
                @Suppress("UNCHECKED_CAST")
                val avTransportService = device.device.findService(AV_TRANSPORT_SERVICE) as? UpnpService<*, *>
                if (avTransportService != null) {
                    pause(avTransportService) { success ->
                        serviceScope.launch(Dispatchers.Main) {
                            if (success) {
                                _streamingState.value = StreamingState.Paused(
                                    videoName = currentState.videoName,
                                    deviceName = currentState.deviceName
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing streaming", e)
            }
        }
    }

    /**
     * Resumes paused streaming.
     */
    fun resumeStreaming() {
        val device = currentDevice ?: return
        val currentState = _streamingState.value

        if (currentState !is StreamingState.Paused) return

        serviceScope.launch(Dispatchers.IO) {
            try {
                @Suppress("UNCHECKED_CAST")
                val avTransportService = device.device.findService(AV_TRANSPORT_SERVICE) as? UpnpService<*, *>
                if (avTransportService != null) {
                    play(avTransportService) { success ->
                        serviceScope.launch(Dispatchers.Main) {
                            if (success) {
                                _streamingState.value = StreamingState.Streaming(
                                    videoName = currentState.videoName,
                                    deviceName = currentState.deviceName
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming streaming", e)
            }
        }
    }

    // AVTransport control methods
    private fun setAVTransportURI(
        service: UpnpService<*, *>,
        uri: String,
        title: String,
        mimeType: String,
        callback: (Boolean) -> Unit
    ) {
        val metadata = createDIDLMetadata(uri, title, mimeType)

        val action = object : SetAVTransportURI(service, uri, metadata) {
            override fun success(invocation: ActionInvocation<*>?) {
                Log.d(TAG, "SetAVTransportURI success")
                callback(true)
            }

            override fun failure(
                invocation: ActionInvocation<*>?,
                operation: UpnpResponse?,
                defaultMsg: String?
            ) {
                Log.e(TAG, "SetAVTransportURI failed: $defaultMsg")
                callback(false)
            }
        }

        upnpService?.controlPoint?.execute(action)
    }

    private fun play(service: UpnpService<*, *>, callback: (Boolean) -> Unit) {
        val action = object : Play(service) {
            override fun success(invocation: ActionInvocation<*>?) {
                callback(true)
            }

            override fun failure(
                invocation: ActionInvocation<*>?,
                operation: UpnpResponse?,
                defaultMsg: String?
            ) {
                callback(false)
            }
        }
        upnpService?.controlPoint?.execute(action)
    }

    private fun pause(service: UpnpService<*, *>, callback: (Boolean) -> Unit) {
        val action = object : Pause(service) {
            override fun success(invocation: ActionInvocation<*>?) {
                callback(true)
            }

            override fun failure(
                invocation: ActionInvocation<*>?,
                operation: UpnpResponse?,
                defaultMsg: String?
            ) {
                callback(false)
            }
        }
        upnpService?.controlPoint?.execute(action)
    }

    private fun stop(service: UpnpService<*, *>, callback: (Boolean) -> Unit) {
        val action = object : Stop(service) {
            override fun success(invocation: ActionInvocation<*>?) {
                callback(true)
            }

            override fun failure(
                invocation: ActionInvocation<*>?,
                operation: UpnpResponse?,
                defaultMsg: String?
            ) {
                callback(false)
            }
        }
        upnpService?.controlPoint?.execute(action)
    }

    fun getTransportInfo(callback: (TransportInfo?) -> Unit) {
        val device = currentDevice ?: run {
            callback(null)
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                @Suppress("UNCHECKED_CAST")
                val avTransportService = device.device.findService(AV_TRANSPORT_SERVICE) as? UpnpService<*, *>
                if (avTransportService != null) {
                    val action = object : GetTransportInfo(avTransportService) {
                        override fun received(
                            invocation: ActionInvocation<*>?,
                            transportInfo: TransportInfo?
                        ) {
                            callback(transportInfo)
                        }

                        override fun failure(
                            invocation: ActionInvocation<*>?,
                            operation: UpnpResponse?,
                            defaultMsg: String?
                        ) {
                            callback(null)
                        }
                    }
                    upnpService?.controlPoint?.execute(action)
                } else {
                    callback(null)
                }
            } catch (e: Exception) {
                callback(null)
            }
        }
    }

    private fun createDIDLMetadata(uri: String, title: String, mimeType: String): String {
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                       xmlns:dc="http://purl.org/dc/elements/1.1/"
                       xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                <item id="0" parentID="-1" restricted="1">
                    <dc:title>$title</dc:title>
                    <upnp:class>object.item.videoItem</upnp:class>
                    <res protocolInfo="http-get:*:$mimeType:*">$uri</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DLNAApplication.CHANNEL_ID)
            .setContentTitle("DLNA Streaming")
            .setContentText("Streaming service active")
            .setSmallIcon(R.drawable.ic_cast)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
