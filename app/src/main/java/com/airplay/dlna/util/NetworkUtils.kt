package com.airplay.dlna.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Utility class for network-related operations.
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * Gets the local IP address of the device on the WiFi network.
     *
     * @param context Application context
     * @return Local IP address string, or null if not available
     */
    fun getLocalIpAddress(context: Context): String? {
        // First try to get from WifiManager
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        @Suppress("DEPRECATION")
        val wifiInfo = wifiManager.connectionInfo
        if (wifiInfo != null) {
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                val ip = intToIpAddress(ipInt)
                Log.d(TAG, "Got IP from WifiManager: $ip")
                return ip
            }
        }

        // Fallback: iterate through network interfaces
        return getLocalIpFromNetworkInterfaces()
    }

    /**
     * Gets local IP address by iterating through network interfaces.
     */
    private fun getLocalIpFromNetworkInterfaces(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // Only use IPv4, non-loopback addresses
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        Log.d(TAG, "Got IP from network interface: $ip")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP from network interfaces", e)
        }
        return null
    }

    /**
     * Converts an integer IP address to string format.
     */
    private fun intToIpAddress(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    /**
     * Checks if the device is connected to WiFi.
     *
     * @param context Application context
     * @return true if connected to WiFi
     */
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo?.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
        }
    }

    /**
     * Gets the WiFi SSID (network name).
     *
     * @param context Application context
     * @return WiFi SSID, or null if not available
     */
    fun getWifiSSID(context: Context): String? {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        @Suppress("DEPRECATION")
        val wifiInfo = wifiManager.connectionInfo
        return wifiInfo?.ssid?.replace("\"", "")
    }

    /**
     * Gets the broadcast address for the current network.
     *
     * @param context Application context
     * @return Broadcast address, or null if not available
     */
    fun getBroadcastAddress(context: Context): String? {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            @Suppress("DEPRECATION")
            val dhcpInfo = wifiManager.dhcpInfo ?: return null

            val broadcast = (dhcpInfo.ipAddress and dhcpInfo.netmask) or dhcpInfo.netmask.inv()
            return intToIpAddress(broadcast)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting broadcast address", e)
            return null
        }
    }
}
