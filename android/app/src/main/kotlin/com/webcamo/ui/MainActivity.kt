package com.webcamo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.webcamo.R
import com.webcamo.databinding.ActivityMainBinding
import com.webcamo.signaling.SignalingClient
import com.webcamo.webrtc.WebRTCClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Main activity for WebCAMO - displays camera preview and controls streaming.
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_SERVER_URL = "ws://192.168.1.100:8080"
        private const val DEFAULT_ROOM = "webcamo"
    }
    
    private lateinit var binding: ActivityMainBinding
    private var webRTCClient: WebRTCClient? = null
    private var signalingClient: SignalingClient? = null
    private var isStreaming = false
    private var useFrontCamera = true
    
    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initializeWebRTC()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        checkCameraPermission()
    }
    
    private fun setupUI() {
        // Connect button
        binding.btnConnect.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }
        
        // Switch camera button
        binding.btnSwitchCamera.setOnClickListener {
            useFrontCamera = !useFrontCamera
            webRTCClient?.switchCamera()
        }
        
        // Settings button
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }
    
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                initializeWebRTC()
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    
    private fun initializeWebRTC() {
        Log.d(TAG, "Initializing WebRTC")
        
        // Create WebRTC client
        webRTCClient = WebRTCClient(
            context = applicationContext,
            signalingListener = object : WebRTCClient.SignalingListener {
                override fun onIceCandidate(candidate: IceCandidate) {
                    signalingClient?.sendIceCandidate(candidate)
                }
            }
        )
        
        webRTCClient?.initialize()
        webRTCClient?.startCamera(binding.localVideoView, useFrontCamera)
        
        // Observe connection state
        lifecycleScope.launch {
            webRTCClient?.connectionState?.collectLatest { state ->
                updateConnectionUI(state)
            }
        }
    }
    
    private fun initializeSignaling() {
        signalingClient = SignalingClient(object : SignalingClient.SignalingListener {
            override fun onConnected() {
                runOnUiThread {
                    updateStatus("Connected to server")
                }
            }
            
            override fun onDisconnected() {
                runOnUiThread {
                    updateStatus("Disconnected")
                    updateStreamingUI(false)
                }
            }
            
            override fun onPeerJoined() {
                runOnUiThread {
                    updateStatus("Receiver connected, starting stream...")
                }
                // Create and send offer when receiver joins
                webRTCClient?.createPeerConnection()
                webRTCClient?.createOffer { offer ->
                    signalingClient?.sendOffer(offer)
                }
            }
            
            override fun onPeerLeft() {
                runOnUiThread {
                    updateStatus("Receiver disconnected")
                }
            }
            
            override fun onAnswerReceived(sdp: SessionDescription) {
                webRTCClient?.setRemoteDescription(sdp)
            }
            
            override fun onIceCandidateReceived(candidate: IceCandidate) {
                webRTCClient?.addIceCandidate(candidate)
            }
            
            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: $message", Toast.LENGTH_SHORT).show()
                    updateStatus("Error: $message")
                }
            }
        })
        
        // Observe signaling state
        lifecycleScope.launch {
            signalingClient?.connectionState?.collectLatest { state ->
                runOnUiThread {
                    binding.txtSignalingStatus.text = when (state) {
                        SignalingClient.ConnectionState.CONNECTED -> "● Server Connected"
                        SignalingClient.ConnectionState.CONNECTING -> "○ Connecting..."
                        SignalingClient.ConnectionState.DISCONNECTED -> "○ Disconnected"
                        SignalingClient.ConnectionState.ERROR -> "● Error"
                    }
                }
            }
        }
    }
    
    private fun startStreaming() {
        val serverUrl = binding.editServerUrl.text.toString().ifEmpty { DEFAULT_SERVER_URL }
        val roomId = DEFAULT_ROOM
        
        updateStatus("Connecting to server...")
        initializeSignaling()
        signalingClient?.connect(serverUrl, roomId)
        
        isStreaming = true
        updateStreamingUI(true)
    }
    
    private fun stopStreaming() {
        signalingClient?.disconnect()
        isStreaming = false
        updateStreamingUI(false)
        updateStatus("Stopped")
    }
    
    private fun updateStreamingUI(streaming: Boolean) {
        binding.btnConnect.text = if (streaming) "Stop" else "Start Streaming"
        binding.btnConnect.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (streaming) R.color.stop_button else R.color.start_button
            )
        )
    }
    
    private fun updateConnectionUI(state: WebRTCClient.ConnectionState) {
        runOnUiThread {
            binding.txtConnectionStatus.text = when (state) {
                WebRTCClient.ConnectionState.CONNECTED -> "● Streaming"
                WebRTCClient.ConnectionState.CONNECTING -> "○ Connecting..."
                WebRTCClient.ConnectionState.DISCONNECTED -> "○ Not connected"
            }
            
            binding.txtConnectionStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    when (state) {
                        WebRTCClient.ConnectionState.CONNECTED -> R.color.status_connected
                        WebRTCClient.ConnectionState.CONNECTING -> R.color.status_connecting
                        WebRTCClient.ConnectionState.DISCONNECTED -> R.color.status_disconnected
                    }
                )
            )
        }
    }
    
    private fun updateStatus(message: String) {
        binding.txtStatus.text = message
    }
    
    private fun showSettingsDialog() {
        // TODO: Implement settings dialog for resolution, quality, etc.
        Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        signalingClient?.release()
        webRTCClient?.release()
    }
}
