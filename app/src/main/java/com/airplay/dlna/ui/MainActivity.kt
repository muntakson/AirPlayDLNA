package com.airplay.dlna.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.airplay.dlna.R
import com.airplay.dlna.databinding.ActivityMainBinding
import com.airplay.dlna.model.DLNADevice
import com.airplay.dlna.model.StreamingState
import com.airplay.dlna.service.DLNAService.GenericDevice
import com.airplay.dlna.util.NetworkUtils
import com.airplay.dlna.util.PermissionUtils
import kotlinx.coroutines.launch

/**
 * Main Activity for the DLNA streaming application.
 * Provides UI for video selection, device discovery, and streaming control.
 */
class MainActivity : AppCompatActivity() {

    // View binding
    private lateinit var binding: ActivityMainBinding

    // ViewModel
    private val viewModel: MainViewModel by viewModels()

    // Device list adapter (combined for DLNA and generic devices)
    private lateinit var deviceAdapter: CombinedDeviceAdapter

    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            openVideoPicker()
        } else {
            showPermissionDeniedDialog()
        }
    }

    // Video picker launcher
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Take persistent permission for the URI
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.setVideoFile(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupObservers()
        checkWifiConnection()
    }

    /**
     * Sets up the UI components and click listeners.
     */
    private fun setupUI() {
        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // Setup device RecyclerView with combined adapter
        deviceAdapter = CombinedDeviceAdapter(
            onDlnaDeviceClick = { device -> onDlnaDeviceSelected(device) },
            onGenericDeviceClick = { device -> onGenericDeviceSelected(device) }
        )
        binding.deviceRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }

        // Select Video button
        binding.selectVideoButton.setOnClickListener {
            onSelectVideoClicked()
        }

        // Find Devices button
        binding.findDevicesButton.setOnClickListener {
            onFindDevicesClicked()
        }

        // Start Streaming button
        binding.startStreamingButton.setOnClickListener {
            onStartStreamingClicked()
        }

        // Stop Streaming button
        binding.stopStreamingButton.setOnClickListener {
            onStopStreamingClicked()
        }

        // Pause/Resume button
        binding.pauseResumeButton.setOnClickListener {
            onPauseResumeClicked()
        }

        // Refresh button
        binding.refreshButton.setOnClickListener {
            viewModel.refreshDevices()
        }
    }

    /**
     * Sets up LiveData/StateFlow observers.
     */
    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    /**
     * Updates the UI based on the current state.
     */
    private fun updateUI(state: MainUiState) {
        // Combine DLNA and generic devices into a single list
        val combinedDevices = mutableListOf<DeviceItem>()
        state.devices.forEach { combinedDevices.add(DeviceItem.Dlna(it)) }
        state.genericDevices.forEach { combinedDevices.add(DeviceItem.Generic(it)) }

        // Update device list
        deviceAdapter.submitList(combinedDevices)

        // Update device section visibility
        binding.noDevicesText.visibility = if (combinedDevices.isEmpty() && !state.isDiscovering) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Update discovery progress
        binding.discoveryProgress.visibility = if (state.isDiscovering) View.VISIBLE else View.GONE
        binding.findDevicesButton.text = if (state.isDiscovering) {
            getString(R.string.stop_search)
        } else {
            getString(R.string.find_devices)
        }

        // Update selected video display
        state.selectedVideo?.let { video ->
            binding.selectedVideoName.text = video.name
            binding.selectedVideoInfo.text = "${video.getFormattedSize()} - ${video.mimeType}"
            binding.selectedVideoCard.visibility = View.VISIBLE
        } ?: run {
            binding.selectedVideoCard.visibility = View.GONE
        }

        // Update selected device display (supports both DLNA and generic devices)
        when {
            state.selectedDevice != null -> {
                val device = state.selectedDevice
                binding.selectedDeviceName.text = device.name
                binding.selectedDeviceInfo.text = "${device.manufacturer} - ${device.modelName}"
                binding.selectedDeviceCard.visibility = View.VISIBLE
            }
            state.selectedGenericDevice != null -> {
                val device = state.selectedGenericDevice
                binding.selectedDeviceName.text = device.name
                binding.selectedDeviceInfo.text = "${device.type} - ${device.host}:${device.port}"
                binding.selectedDeviceCard.visibility = View.VISIBLE
            }
            else -> {
                binding.selectedDeviceCard.visibility = View.GONE
            }
        }

        // Update streaming controls based on state
        updateStreamingControls(state.streamingState)

        // Show error if present
        state.errorMessage?.let { message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    /**
     * Updates streaming control buttons based on current streaming state.
     */
    private fun updateStreamingControls(state: StreamingState) {
        when (state) {
            is StreamingState.Idle, is StreamingState.Stopped -> {
                binding.startStreamingButton.isEnabled = viewModel.isReadyToStream()
                binding.startStreamingButton.visibility = View.VISIBLE
                binding.stopStreamingButton.visibility = View.GONE
                binding.pauseResumeButton.visibility = View.GONE
                binding.streamingStatusText.text = getString(R.string.status_idle)
            }

            is StreamingState.Preparing -> {
                binding.startStreamingButton.isEnabled = false
                binding.startStreamingButton.visibility = View.VISIBLE
                binding.stopStreamingButton.visibility = View.GONE
                binding.pauseResumeButton.visibility = View.GONE
                binding.streamingStatusText.text = getString(R.string.status_preparing)
            }

            is StreamingState.Streaming -> {
                binding.startStreamingButton.visibility = View.GONE
                binding.stopStreamingButton.visibility = View.VISIBLE
                binding.pauseResumeButton.visibility = View.VISIBLE
                binding.pauseResumeButton.text = getString(R.string.pause)
                binding.streamingStatusText.text = getString(
                    R.string.status_streaming,
                    state.videoName,
                    state.deviceName
                )
            }

            is StreamingState.Paused -> {
                binding.startStreamingButton.visibility = View.GONE
                binding.stopStreamingButton.visibility = View.VISIBLE
                binding.pauseResumeButton.visibility = View.VISIBLE
                binding.pauseResumeButton.text = getString(R.string.resume)
                binding.streamingStatusText.text = getString(R.string.status_paused)
            }

            is StreamingState.Error -> {
                binding.startStreamingButton.isEnabled = viewModel.isReadyToStream()
                binding.startStreamingButton.visibility = View.VISIBLE
                binding.stopStreamingButton.visibility = View.GONE
                binding.pauseResumeButton.visibility = View.GONE
                binding.streamingStatusText.text = getString(R.string.status_error, state.message)
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Checks if WiFi is connected and shows warning if not.
     */
    private fun checkWifiConnection() {
        if (!NetworkUtils.isWifiConnected(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.wifi_required_title)
                .setMessage(R.string.wifi_required_message)
                .setPositiveButton(R.string.ok, null)
                .show()
        } else {
            // Show WiFi info
            val ssid = NetworkUtils.getWifiSSID(this)
            val ip = NetworkUtils.getLocalIpAddress(this)
            binding.wifiInfoText.text = getString(R.string.wifi_info, ssid ?: "Unknown", ip ?: "Unknown")
        }
    }

    /**
     * Handles Select Video button click.
     */
    private fun onSelectVideoClicked() {
        if (PermissionUtils.hasStoragePermission(this)) {
            openVideoPicker()
        } else {
            permissionLauncher.launch(PermissionUtils.getRequiredPermissions())
        }
    }

    /**
     * Opens the system video picker.
     */
    private fun openVideoPicker() {
        videoPickerLauncher.launch(arrayOf("video/*"))
    }

    /**
     * Handles Find Devices button click.
     */
    private fun onFindDevicesClicked() {
        if (!NetworkUtils.isWifiConnected(this)) {
            Toast.makeText(this, R.string.wifi_required_message, Toast.LENGTH_SHORT).show()
            return
        }

        val currentState = viewModel.uiState.value
        if (currentState.isDiscovering) {
            viewModel.stopDiscovery()
        } else {
            viewModel.startDiscovery()
        }
    }

    /**
     * Handles DLNA device selection from the list.
     */
    private fun onDlnaDeviceSelected(device: DLNADevice) {
        viewModel.selectDevice(device)
        Toast.makeText(
            this,
            getString(R.string.device_selected, device.name),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Handles generic device selection from the list.
     */
    private fun onGenericDeviceSelected(device: GenericDevice) {
        viewModel.selectGenericDevice(device)
        Toast.makeText(
            this,
            getString(R.string.device_selected, device.name),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Handles Start Streaming button click.
     */
    private fun onStartStreamingClicked() {
        viewModel.startStreaming()
    }

    /**
     * Handles Stop Streaming button click.
     */
    private fun onStopStreamingClicked() {
        viewModel.stopStreaming()
    }

    /**
     * Handles Pause/Resume button click.
     */
    private fun onPauseResumeClicked() {
        val currentState = viewModel.uiState.value.streamingState
        when (currentState) {
            is StreamingState.Streaming -> viewModel.pauseStreaming()
            is StreamingState.Paused -> viewModel.resumeStreaming()
            else -> { /* Do nothing */ }
        }
    }

    /**
     * Shows dialog explaining why permissions were denied.
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required_title)
            .setMessage(R.string.permission_required_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
