package com.airplay.dlna.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.airplay.dlna.DLNAApplication
import com.airplay.dlna.R
import com.airplay.dlna.ui.MainActivity
import com.airplay.dlna.util.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

/**
 * Service that serves local video files over HTTP.
 * DLNA devices need to access the video via HTTP, so this service
 * creates a local HTTP server to serve the selected video file.
 */
class MediaServerService : Service() {

    companion object {
        private const val TAG = "MediaServerService"
        private const val NOTIFICATION_ID = 1002
        private const val DEFAULT_PORT = 8080
    }

    // Binder for local service binding
    private val binder = LocalBinder()

    // HTTP server instance
    private var httpServer: MediaHttpServer? = null

    // Current video being served
    private var currentVideoUri: Uri? = null
    private var currentVideoMimeType: String = "video/mp4"

    // Server state
    private var isServerRunning = false
    private var serverPort = DEFAULT_PORT

    /**
     * Binder class for local service binding
     */
    inner class LocalBinder : Binder() {
        fun getService(): MediaServerService = this@MediaServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MediaServerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        Log.d(TAG, "MediaServerService destroyed")
    }

    /**
     * Starts the HTTP server to serve video files.
     *
     * @param port Port to run the server on (default: 8080)
     * @return true if server started successfully
     */
    fun startServer(port: Int = DEFAULT_PORT): Boolean {
        if (isServerRunning) {
            Log.d(TAG, "Server already running on port $serverPort")
            return true
        }

        return try {
            serverPort = port
            httpServer = MediaHttpServer(port)
            httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            isServerRunning = true
            Log.d(TAG, "HTTP server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            // Try alternate port
            try {
                serverPort = port + 1
                httpServer = MediaHttpServer(serverPort)
                httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                isServerRunning = true
                Log.d(TAG, "HTTP server started on alternate port $serverPort")
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start HTTP server on alternate port", e2)
                false
            }
        }
    }

    /**
     * Stops the HTTP server.
     */
    fun stopServer() {
        httpServer?.stop()
        httpServer = null
        isServerRunning = false
        currentVideoUri = null
        Log.d(TAG, "HTTP server stopped")
    }

    /**
     * Sets the video file to be served.
     *
     * @param uri Content URI of the video file
     * @param mimeType MIME type of the video
     */
    fun setVideoFile(uri: Uri, mimeType: String) {
        currentVideoUri = uri
        currentVideoMimeType = mimeType
        Log.d(TAG, "Video file set: $uri ($mimeType)")
    }

    /**
     * Gets the URL where the video can be accessed.
     *
     * @return HTTP URL for the video, or null if not available
     */
    fun getVideoUrl(): String? {
        if (!isServerRunning || currentVideoUri == null) {
            return null
        }

        val ipAddress = NetworkUtils.getLocalIpAddress(this)
        if (ipAddress == null) {
            Log.e(TAG, "Could not get local IP address")
            return null
        }

        return "http://$ipAddress:$serverPort/video"
    }

    /**
     * Returns the server port.
     */
    fun getServerPort(): Int = serverPort

    /**
     * Returns whether the server is running.
     */
    fun isRunning(): Boolean = isServerRunning

    /**
     * Internal HTTP server implementation using NanoHTTPD.
     */
    private inner class MediaHttpServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            Log.d(TAG, "HTTP request: ${session.method} $uri")

            return when {
                uri == "/video" || uri.startsWith("/video") -> serveVideo(session)
                uri == "/status" -> serveStatus()
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not Found"
                )
            }
        }

        /**
         * Serves the video file with support for range requests.
         */
        private fun serveVideo(session: IHTTPSession): Response {
            val videoUri = currentVideoUri
            if (videoUri == null) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "No video file set"
                )
            }

            try {
                val contentResolver = applicationContext.contentResolver

                // Get file size
                val fileDescriptor = contentResolver.openFileDescriptor(videoUri, "r")
                val fileSize = fileDescriptor?.statSize ?: 0
                fileDescriptor?.close()

                // Check for range request
                val rangeHeader = session.headers["range"]

                return if (rangeHeader != null) {
                    // Handle range request for seeking support
                    servePartialContent(videoUri, rangeHeader, fileSize)
                } else {
                    // Serve full file
                    serveFullContent(videoUri, fileSize)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error serving video", e)
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Error: ${e.message}"
                )
            }
        }

        /**
         * Serves the full video file.
         */
        private fun serveFullContent(videoUri: Uri, fileSize: Long): Response {
            val inputStream = applicationContext.contentResolver.openInputStream(videoUri)

            return if (inputStream != null) {
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    currentVideoMimeType,
                    inputStream,
                    fileSize
                )
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", fileSize.toString())
                response
            } else {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Could not open video file"
                )
            }
        }

        /**
         * Serves partial content for range requests (seeking support).
         */
        private fun servePartialContent(videoUri: Uri, rangeHeader: String, fileSize: Long): Response {
            // Parse range header (e.g., "bytes=0-1000" or "bytes=500-")
            val range = rangeHeader.replace("bytes=", "")
            val parts = range.split("-")

            val start = parts[0].toLongOrNull() ?: 0
            val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                parts[1].toLongOrNull() ?: (fileSize - 1)
            } else {
                fileSize - 1
            }

            val contentLength = end - start + 1

            try {
                val inputStream = applicationContext.contentResolver.openInputStream(videoUri)
                if (inputStream != null) {
                    // Skip to start position
                    inputStream.skip(start)

                    // Create limited input stream
                    val limitedStream = LimitedInputStream(inputStream, contentLength)

                    val response = newFixedLengthResponse(
                        Response.Status.PARTIAL_CONTENT,
                        currentVideoMimeType,
                        limitedStream,
                        contentLength
                    )
                    response.addHeader("Accept-Ranges", "bytes")
                    response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
                    response.addHeader("Content-Length", contentLength.toString())
                    return response
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error serving partial content", e)
            }

            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Error serving partial content"
            )
        }

        /**
         * Serves a simple status response.
         */
        private fun serveStatus(): Response {
            val status = """
                {
                    "status": "running",
                    "port": $serverPort,
                    "hasVideo": ${currentVideoUri != null}
                }
            """.trimIndent()

            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                status
            )
        }
    }

    /**
     * Input stream wrapper that limits the number of bytes read.
     */
    private class LimitedInputStream(
        private val inputStream: InputStream,
        private var remaining: Long
    ) : InputStream() {

        override fun read(): Int {
            if (remaining <= 0) return -1
            val result = inputStream.read()
            if (result != -1) remaining--
            return result
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val result = inputStream.read(b, off, toRead)
            if (result > 0) remaining -= result
            return result
        }

        override fun close() {
            inputStream.close()
        }

        override fun available(): Int {
            return minOf(inputStream.available().toLong(), remaining).toInt()
        }
    }

    /**
     * Creates a notification for the foreground service.
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DLNAApplication.CHANNEL_ID)
            .setContentTitle("Media Server")
            .setContentText("Serving video content")
            .setSmallIcon(R.drawable.ic_video)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
