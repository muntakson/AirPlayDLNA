package com.airplay.dlna.model

/**
 * Sealed class representing the current streaming state.
 */
sealed class StreamingState {
    /** No streaming activity */
    object Idle : StreamingState()

    /** Preparing to stream (setting up connection) */
    object Preparing : StreamingState()

    /** Currently streaming video */
    data class Streaming(
        val videoName: String,
        val deviceName: String
    ) : StreamingState()

    /** Streaming paused */
    data class Paused(
        val videoName: String,
        val deviceName: String
    ) : StreamingState()

    /** Streaming stopped */
    object Stopped : StreamingState()

    /** Error occurred during streaming */
    data class Error(val message: String) : StreamingState()
}

/**
 * Enum representing playback transport state from DLNA device.
 */
enum class TransportState {
    STOPPED,
    PLAYING,
    TRANSITIONING,
    PAUSED_PLAYBACK,
    PAUSED_RECORDING,
    RECORDING,
    NO_MEDIA_PRESENT,
    UNKNOWN
}
