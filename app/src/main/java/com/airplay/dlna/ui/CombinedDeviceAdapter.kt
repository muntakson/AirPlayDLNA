package com.airplay.dlna.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.airplay.dlna.R
import com.airplay.dlna.model.DLNADevice
import com.airplay.dlna.service.DLNAService.GenericDevice

/**
 * Wrapper for both DLNA and Generic devices to display in a single list.
 */
sealed class DeviceItem {
    abstract val id: String
    abstract val name: String
    abstract val info: String
    abstract val isSelected: Boolean

    data class Dlna(val device: DLNADevice) : DeviceItem() {
        override val id: String get() = device.id
        override val name: String get() = device.name
        override val info: String get() = "${device.manufacturer} - ${device.modelName}"
        override val isSelected: Boolean get() = device.isSelected
    }

    data class Generic(val device: GenericDevice) : DeviceItem() {
        override val id: String get() = device.id
        override val name: String get() = device.name
        override val info: String get() = "${device.type} - ${device.host}:${device.port}"
        override val isSelected: Boolean get() = device.isSelected
    }
}

/**
 * RecyclerView adapter for displaying all discovered devices (DLNA and Generic).
 */
class CombinedDeviceAdapter(
    private val onDlnaDeviceClick: (DLNADevice) -> Unit,
    private val onGenericDeviceClick: (GenericDevice) -> Unit
) : ListAdapter<DeviceItem, CombinedDeviceAdapter.DeviceViewHolder>(DeviceItemDiffCallback()) {

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val deviceIcon: ImageView = itemView.findViewById(R.id.deviceIcon)
        private val deviceName: TextView = itemView.findViewById(R.id.deviceName)
        private val deviceInfo: TextView = itemView.findViewById(R.id.deviceInfo)
        private val selectedIndicator: View = itemView.findViewById(R.id.selectedIndicator)

        fun bind(item: DeviceItem) {
            deviceName.text = item.name
            deviceInfo.text = item.info

            // Show/hide selection indicator
            selectedIndicator.visibility = if (item.isSelected) View.VISIBLE else View.GONE

            // Update icon based on selection
            deviceIcon.setImageResource(
                if (item.isSelected) R.drawable.ic_cast_connected
                else R.drawable.ic_cast
            )

            // Set click listener
            itemView.setOnClickListener {
                when (item) {
                    is DeviceItem.Dlna -> onDlnaDeviceClick(item.device)
                    is DeviceItem.Generic -> onGenericDeviceClick(item.device)
                }
            }

            // Update background based on selection
            itemView.isSelected = item.isSelected
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DeviceItemDiffCallback : DiffUtil.ItemCallback<DeviceItem>() {
        override fun areItemsTheSame(oldItem: DeviceItem, newItem: DeviceItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DeviceItem, newItem: DeviceItem): Boolean {
            return oldItem == newItem
        }
    }
}
