package com.airplay.dlna.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airplay.dlna.model.DLNADevice
import com.airplay.dlna.model.StreamingState
import com.airplay.dlna.model.VideoFile
import com.airplay.dlna.service.DLNAService
import com.airplay.dlna.service.DLNAService.GenericDevice
import com.airplay.dlna.service.MediaServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for MainActivity, managing UI state and service interactions.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // DLNA Service
    private var dlnaService: DLNAService? = null
    private var dlnaServiceBound = false

    // Media Server Service
    private var mediaServerService: MediaServerService? = null
    private var mediaServerServiceBound = false

    // Selected video file
    private val _selectedVideo = MutableStateFlow<VideoFile?>(null)
    val selectedVideo: StateFlow<VideoFile?> = _selectedVideo.asStateFlow()

    // UI state
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Expose DLNA service flows
    val devices: StateFlow<List<DLNADevice>>?
        get() = dlnaService?.devices

    val genericDevices: StateFlow<List<GenericDevice>>?
        get() = dlnaService?.genericDevices

    val isDiscovering: StateFlow<Boolean>?
        get() = dlnaService?.isDiscovering

    val streamingState: StateFlow<StreamingState>?
        get() = dlnaService?.streamingState

    /**
     * Service connection for DLNAService.
     */
    private val dlnaServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DLNAService.LocalBinder
            dlnaService = binder.getService()
            dlnaServiceBound = true
            Log.d(TAG, "DLNAService connected")

            // Observe service state
            observeDlnaServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dlnaService = null
            dlnaServiceBound = false
            Log.d(TAG, "DLNAService disconnected")
        }
    }

    /**
     * Service connection for MediaServerService.
     */
    private val mediaServerServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaServerService.LocalBinder
            mediaServerService = binder.getService()
            mediaServerServiceBound = true
            Log.d(TAG, "MediaServerService connected")

            // Start the HTTP server
            mediaServerService?.startServer()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaServerService = null
            mediaServerServiceBound = false
            Log.d(TAG, "MediaServerService disconnected")
        }
    }

    init {
        startServices()
    }

    /**
     * Starts and binds to the required services.
     */
    private fun startServices() {
        val context = getApplication<Application>()

        // Start DLNA Service
        val dlnaIntent = Intent(context, DLNAService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(dlnaIntent)
        } else {
            context.startService(dlnaIntent)
        }
        context.bindService(dlnaIntent, dlnaServiceConnection, Context.BIND_AUTO_CREATE)

        // Start Media Server Service
        val mediaIntent = Intent(context, MediaServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(mediaIntent)
        } else {
            context.startService(mediaIntent)
        }
        context.bindService(mediaIntent, mediaServerServiceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Observes DLNA service state and updates UI state accordingly.
     */
    private fun observeDlnaServiceState() {
        viewModelScope.launch {
            dlnaService?.devices?.collect { devices ->
                _uiState.value = _uiState.value.copy(devices = devices)
            }
        }

        viewModelScope.launch {
            dlnaService?.genericDevices?.collect { genericDevices ->
                _uiState.value = _uiState.value.copy(genericDevices = genericDevices)
            }
        }

        viewModelScope.launch {
            dlnaService?.isDiscovering?.collect { isDiscovering ->
                _uiState.value = _uiState.value.copy(isDiscovering = isDiscovering)
            }
        }

        viewModelScope.launch {
            dlnaService?.streamingState?.collect { state ->
                _uiState.value = _uiState.value.copy(streamingState = state)
            }
        }
    }

    /**
     * Starts device discovery.
     */
    fun startDiscovery() {
        dlnaService?.startDiscovery()
    }

    /**
     * Stops device discovery.
     */
    fun stopDiscovery() {
        dlnaService?.stopDiscovery()
    }

    /**
     * Refreshes the device list.
     */
    fun refreshDevices() {
        dlnaService?.refreshDevices()
    }

    /**
     * Selects a DLNA device for streaming.
     */
    fun selectDevice(device: DLNADevice) {
        dlnaService?.selectDevice(device)
        _uiState.value = _uiState.value.copy(
            selectedDevice = device,
            selectedGenericDevice = null
        )
    }

    /**
     * Selects a generic device for streaming.
     */
    fun selectGenericDevice(device: GenericDevice) {
        dlnaService?.selectGenericDevice(device)
        _uiState.value = _uiState.value.copy(
            selectedDevice = null,
            selectedGenericDevice = device
        )
    }

    /**
     * Sets the selected video file from a content URI.
     *
     * @param uri Content URI of the selected video
     */
    fun setVideoFile(uri: Uri) {
        val context = getApplication<Application>()
        val contentResolver = context.contentResolver

        try {
            // Get file info
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)

                    val name = if (nameIndex >= 0) it.getString(nameIndex) else "Unknown"
                    val size = if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L

                    // Get MIME type
                    val mimeType = contentResolver.getType(uri) ?: "video/mp4"

                    val videoFile = VideoFile(
                        uri = uri,
                        name = name,
                        mimeType = mimeType,
                        size = size
                    )

                    _selectedVideo.value = videoFile
                    _uiState.value = _uiState.value.copy(selectedVideo = videoFile)

                    // Set file on media server
                    mediaServerService?.setVideoFile(uri, mimeType)

                    Log.d(TAG, "Video file selected: $name ($mimeType, ${videoFile.getFormattedSize()})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video file info", e)
            _uiState.value = _uiState.value.copy(
                errorMessage = "Error selecting video: ${e.message}"
            )
        }
    }

    /**
     * Starts streaming the selected video to the selected device.
     */
    fun startStreaming() {
        val video = _selectedVideo.value
        val device = _uiState.value.selectedDevice
        val genericDevice = _uiState.value.selectedGenericDevice

        if (video == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please select a video file")
            return
        }

        if (device == null && genericDevice == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please select a device")
            return
        }

        // Get video URL from media server
        val videoUrl = mediaServerService?.getVideoUrl()
        if (videoUrl == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "Media server not ready")
            return
        }

        val deviceName = device?.name ?: genericDevice?.name ?: "Unknown"
        Log.d(TAG, "Starting streaming: $videoUrl -> $deviceName")
        dlnaService?.startStreaming(videoUrl, video.name, video.mimeType)
    }

    /**
     * Stops the current streaming session.
     */
    fun stopStreaming() {
        dlnaService?.stopStreaming()
    }

    /**
     * Pauses the current streaming session.
     */
    fun pauseStreaming() {
        dlnaService?.pauseStreaming()
    }

    /**
     * Resumes the paused streaming session.
     */
    fun resumeStreaming() {
        dlnaService?.resumeStreaming()
    }

    /**
     * Clears the error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Checks if ready to stream (both video and device selected).
     */
    fun isReadyToStream(): Boolean {
        val hasVideo = _selectedVideo.value != null
        val hasDevice = _uiState.value.selectedDevice != null || _uiState.value.selectedGenericDevice != null
        return hasVideo && hasDevice
    }

    override fun onCleared() {
        super.onCleared()

        val context = getApplication<Application>()

        // Unbind services
        if (dlnaServiceBound) {
            context.unbindService(dlnaServiceConnection)
            dlnaServiceBound = false
        }

        if (mediaServerServiceBound) {
            context.unbindService(mediaServerServiceConnection)
            mediaServerServiceBound = false
        }
    }
}

/**
 * UI state data class for MainActivity.
 */
data class MainUiState(
    val devices: List<DLNADevice> = emptyList(),
    val genericDevices: List<GenericDevice> = emptyList(),
    val selectedDevice: DLNADevice? = null,
    val selectedGenericDevice: GenericDevice? = null,
    val selectedVideo: VideoFile? = null,
    val isDiscovering: Boolean = false,
    val streamingState: StreamingState = StreamingState.Idle,
    val errorMessage: String? = null
)
