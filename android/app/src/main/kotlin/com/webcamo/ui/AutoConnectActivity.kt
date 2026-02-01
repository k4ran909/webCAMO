package com.webcamo.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.webcamo.databinding.ActivityAutoConnectBinding
import com.webcamo.service.CameraStreamService

/**
 * AutoConnectActivity - Simple UI for controlling the background camera service
 * 
 * This Activity ONLY:
 * - Requests camera permission
 * - Starts the foreground service
 * - Displays status from the service
 * - Provides switch camera / stop buttons
 * 
 * ALL camera and streaming logic is in CameraStreamService for stable background operation.
 */
class AutoConnectActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "AutoConnectActivity"
    }
    
    private lateinit var binding: ActivityAutoConnectBinding
    
    // Broadcast receiver for service status updates
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(CameraStreamService.EXTRA_STATUS) ?: return
            val fps = intent.getIntExtra(CameraStreamService.EXTRA_FPS, 0)
            val connected = intent.getBooleanExtra(CameraStreamService.EXTRA_CONNECTED, false)
            
            updateUI(status, fps, connected)
        }
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startStreamingService()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        checkPermissionAndStart()
    }
    
    override fun onResume() {
        super.onResume()
        // Register for status updates
        val filter = IntentFilter(CameraStreamService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        
        // Update UI based on service state
        if (CameraStreamService.isRunning) {
            binding.statusText.text = "📡 Streaming"
            binding.statusText.setTextColor(getColor(android.R.color.holo_green_light))
        } else {
            binding.statusText.text = "Ready"
            binding.statusText.setTextColor(getColor(android.R.color.darker_gray))
        }
    }
    
    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {}
    }
    
    private fun setupUI() {
        // Switch camera button
        binding.switchCameraButton.setOnClickListener {
            val intent = Intent(this, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_SWITCH_CAMERA
            }
            startService(intent)
            Toast.makeText(this, "Camera switched", Toast.LENGTH_SHORT).show()
        }
        
        // Initial state
        binding.statusText.text = "🔍 Starting..."
        binding.statusText.setTextColor(getColor(android.R.color.white))
    }
    
    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED) {
            startStreamingService()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    private fun startStreamingService() {
        Log.d(TAG, "Starting streaming service")
        
        val intent = Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_START
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}")
            Toast.makeText(this, "Failed to start camera service", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun updateUI(status: String, fps: Int, connected: Boolean) {
        runOnUiThread {
            when (status) {
                "streaming" -> {
                    if (fps > 0) {
                        binding.statusText.text = "📡 Streaming (${fps} FPS)"
                    } else {
                        binding.statusText.text = "📡 Streaming"
                    }
                    binding.statusText.setTextColor(getColor(android.R.color.holo_green_light))
                    binding.searchingIndicator?.visibility = View.GONE
                }
                "searching" -> {
                    binding.statusText.text = "🔍 Searching for PC..."
                    binding.statusText.setTextColor(getColor(android.R.color.white))
                    binding.searchingIndicator?.visibility = View.VISIBLE
                }
                "stopped" -> {
                    binding.statusText.text = "Stopped"
                    binding.statusText.setTextColor(getColor(android.R.color.darker_gray))
                    binding.searchingIndicator?.visibility = View.GONE
                }
                else -> {
                    binding.statusText.text = status
                    binding.statusText.setTextColor(getColor(android.R.color.white))
                }
            }
        }
    }
    
    override fun onBackPressed() {
        // Move app to background instead of stopping service
        moveTaskToBack(true)
    }
}
