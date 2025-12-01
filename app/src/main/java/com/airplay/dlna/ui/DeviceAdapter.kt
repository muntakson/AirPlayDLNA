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

/**
 * RecyclerView adapter for displaying discovered DLNA devices.
 *
 * @property onDeviceClick Callback invoked when a device is clicked
 */
class DeviceAdapter(
    private val onDeviceClick: (DLNADevice) -> Unit
) : ListAdapter<DLNADevice, DeviceAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    /**
     * ViewHolder for device list items.
     */
    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val deviceIcon: ImageView = itemView.findViewById(R.id.deviceIcon)
        private val deviceName: TextView = itemView.findViewById(R.id.deviceName)
        private val deviceInfo: TextView = itemView.findViewById(R.id.deviceInfo)
        private val selectedIndicator: View = itemView.findViewById(R.id.selectedIndicator)

        fun bind(device: DLNADevice) {
            deviceName.text = device.name
            deviceInfo.text = "${device.manufacturer} - ${device.modelName}"

            // Show/hide selection indicator
            selectedIndicator.visibility = if (device.isSelected) View.VISIBLE else View.GONE

            // Update icon based on selection
            deviceIcon.setImageResource(
                if (device.isSelected) R.drawable.ic_cast_connected
                else R.drawable.ic_cast
            )

            // Set click listener
            itemView.setOnClickListener {
                onDeviceClick(device)
            }

            // Update background based on selection
            itemView.isSelected = device.isSelected
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

    /**
     * DiffUtil callback for efficient list updates.
     */
    class DeviceDiffCallback : DiffUtil.ItemCallback<DLNADevice>() {
        override fun areItemsTheSame(oldItem: DLNADevice, newItem: DLNADevice): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DLNADevice, newItem: DLNADevice): Boolean {
            return oldItem == newItem && oldItem.isSelected == newItem.isSelected
        }
    }
}
