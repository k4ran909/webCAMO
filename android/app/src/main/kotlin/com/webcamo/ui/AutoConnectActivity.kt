package com.webcamo.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.webcamo.databinding.ActivityAutoConnectBinding
import com.webcamo.service.CameraStreamService

/**
 * AutoConnectActivity - Simple UI for controlling the background camera service
 * Now includes local preview binding
 */
class AutoConnectActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "AutoConnectActivity"
    }
    
    private lateinit var binding: ActivityAutoConnectBinding
    private var cameraService: CameraStreamService? = null
    private var isBound = false
    
    // Service Connection for Preview
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as CameraStreamService.LocalBinder
            cameraService = binder.getService()
            isBound = true
            tryAttemptPreview()
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            cameraService = null
        }
    }
    
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
    
    override fun onStart() {
        super.onStart()
        // Bind to service for preview control
        Intent(this, CameraStreamService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }
    
    override fun onStop() {
        super.onStop()
        if (isBound) {
            // Detach preview before unbinding to avoid leaks
            cameraService?.setPreviewSurface(null)
            unbindService(connection)
            isBound = false
        }
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
        tryAttemptPreview()
    }
    
    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }
    
    private fun setupUI() {
        binding.switchCameraButton.setOnClickListener {
            val intent = Intent(this, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_SWITCH_CAMERA
            }
            startService(intent)
        }
        
        binding.statusText.text = "🔍 Starting..."
        
        // Setup SurfaceView
        binding.previewView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                tryAttemptPreview()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraService?.setPreviewSurface(null)
            }
        })
    }
    
    private fun tryAttemptPreview() {
        if (isBound && cameraService != null && binding.previewView.holder.surface.isValid) {
            cameraService?.setPreviewSurface(binding.previewView.holder.surface)
        }
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
        moveTaskToBack(true)
    }
}
