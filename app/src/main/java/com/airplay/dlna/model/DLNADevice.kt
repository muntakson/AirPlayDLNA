package com.airplay.dlna.model

import org.jupnp.model.meta.Device

/**
 * Data class representing a discovered DLNA device.
 * Wraps the JUpnP Device object with additional UI-friendly properties.
 *
 * @property device The underlying JUpnP UPnP device
 * @property name Display name of the device
 * @property type Type of the device (e.g., MediaRenderer)
 * @property isSelected Whether this device is currently selected
 */
data class DLNADevice(
    val device: Device<*, *, *>,
    val name: String,
    val type: String,
    var isSelected: Boolean = false
) {
    /**
     * Unique device identifier (UDN)
     */
    val id: String
        get() = device.identity.udn.identifierString

    /**
     * Device manufacturer name
     */
    val manufacturer: String
        get() = device.details?.manufacturerDetails?.manufacturer ?: "Unknown"

    /**
     * Device model name
     */
    val modelName: String
        get() = device.details?.modelDetails?.modelName ?: "Unknown Model"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DLNADevice
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
