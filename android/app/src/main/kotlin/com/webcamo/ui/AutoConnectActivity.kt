package com.webcamo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.webcamo.databinding.ActivityAutoConnectBinding
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Auto-discovery streaming activity - Iriun-like experience
 * No IP entry required - discovers PC automatically via UDP broadcast
 */
class AutoConnectActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "AutoConnect"
        private const val DISCOVERY_PORT = 9001
        private const val STREAM_PORT = 9000
        private const val DISCOVERY_INTERVAL = 2000L
        private const val JPEG_QUALITY = 80
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
    }

    private lateinit var binding: ActivityAutoConnectBinding
    
    // Camera
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var cameraManager: CameraManager
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var useFrontCamera = true
    
    // Networking
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isStreaming = false
    private var isSearching = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Discovered PCs
    private val discoveredPCs = mutableMapOf<String, String>() // IP -> Name
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCameraPreview()
            startDiscovery()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        
        setupUI()
        checkPermission()
    }
    
    private fun setupUI() {
        // Switch camera button
        binding.switchCameraButton.setOnClickListener {
            useFrontCamera = !useFrontCamera
            restartCamera()
        }
        
        updateUI()
    }
    
    private fun updateUI() {
        mainHandler.post {
            when {
                isStreaming -> {
                    binding.statusText.text = "📡 Streaming"
                    binding.statusText.setTextColor(getColor(android.R.color.holo_green_light))
                    binding.searchingIndicator.visibility = android.view.View.GONE
                }
                isSearching -> {
                    binding.statusText.text = "🔍 Searching for PC..."
                    binding.statusText.setTextColor(getColor(android.R.color.white))
                    binding.searchingIndicator.visibility = android.view.View.VISIBLE
                }
                else -> {
                    binding.statusText.text = "Ready"
                    binding.statusText.setTextColor(getColor(android.R.color.darker_gray))
                    binding.searchingIndicator.visibility = android.view.View.GONE
                }
            }
        }
    }
    
    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview()
            startDiscovery()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    private fun startDiscovery() {
        isSearching = true
        updateUI()
        
        scope.launch {
            while (isActive && !isStreaming) {
                try {
                    discoverPCs()
                } catch (e: Exception) {
                    Log.e(TAG, "Discovery error: ${e.message}")
                }
                delay(DISCOVERY_INTERVAL)
            }
        }
    }
    
    private suspend fun discoverPCs() {
        withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                socket.soTimeout = 1500
                socket.broadcast = true
                
                // Get broadcast address
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val dhcp = wifiManager.dhcpInfo
                val broadcast = getBroadcastAddress(dhcp)
                
                // Send discovery packet
                val message = "WEBCAMO_DISCOVER".toByteArray()
                val packet = DatagramPacket(message, message.size, broadcast, DISCOVERY_PORT)
                socket.send(packet)
                
                // Wait for responses
                val buffer = ByteArray(1024)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                
                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length)
                    
                    if (response.startsWith("WEBCAMO_PC|")) {
                        val parts = response.split("|")
                        if (parts.size >= 3) {
                            val pcName = parts[1]
                            val port = parts[2].toIntOrNull() ?: STREAM_PORT
                            val pcIp = responsePacket.address.hostAddress ?: return@withContext
                            
                            Log.d(TAG, "Discovered PC: $pcName at $pcIp:$port")
                            discoveredPCs[pcIp] = pcName
                            
                            // Auto-connect to first discovered PC
                            if (!isStreaming) {
                                connectToPC(pcIp, port)
                            }
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // No response, continue searching
                }
                
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Discovery failed: ${e.message}")
            }
        }
    }
    
    private fun getBroadcastAddress(dhcp: android.net.DhcpInfo): InetAddress {
        val broadcast = dhcp.ipAddress and dhcp.netmask or dhcp.netmask.inv()
        val quads = ByteArray(4)
        for (k in 0..3) {
            quads[k] = (broadcast shr k * 8 and 0xFF).toByte()
        }
        return InetAddress.getByAddress(quads)
    }
    
    private fun connectToPC(ip: String, port: Int) {
        scope.launch {
            try {
                Log.d(TAG, "Connecting to $ip:$port...")
                socket = Socket(ip, port)
                outputStream = socket?.getOutputStream()
                isStreaming = true
                isSearching = false
                
                withContext(Dispatchers.Main) {
                    updateUI()
                    Toast.makeText(this@AutoConnectActivity, "Connected!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AutoConnectActivity, "Connection failed, retrying...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // ===== Camera Methods =====
    
    private fun startCameraPreview() {
        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        
        binding.previewView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                openCamera()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                closeCamera()
            }
        })
    }
    
    private fun openCamera() {
        try {
            val cameraId = getCameraId()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                    }
                }, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
        }
    }
    
    private fun getCameraId(): String {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (useFrontCamera && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
            if (!useFrontCamera && facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cameraManager.cameraIdList[0]
    }
    
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        
        imageReader = ImageReader.newInstance(VIDEO_WIDTH, VIDEO_HEIGHT, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null && isStreaming) {
                processImage(image)
            }
            image?.close()
        }, cameraHandler)
        
        val previewSurface = binding.previewView.holder.surface
        val captureSurface = imageReader!!.surface
        
        try {
            camera.createCaptureSession(
                listOf(previewSurface, captureSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview(previewSurface, captureSurface)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
        }
    }
    
    private fun startPreview(previewSurface: Surface, captureSurface: Surface) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewSurface)
            builder.addTarget(captureSurface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview: ${e.message}")
        }
    }
    
    private fun processImage(image: android.media.Image) {
        try {
            val jpeg = imageToJpeg(image)
            sendFrame(jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image: ${e.message}")
        }
    }
    
    private fun imageToJpeg(image: android.media.Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
        
        return out.toByteArray()
    }
    
    @Synchronized
    private fun sendFrame(jpeg: ByteArray) {
        val stream = outputStream ?: return
        
        try {
            val sizeBuffer = ByteBuffer.allocate(4)
            sizeBuffer.order(ByteOrder.LITTLE_ENDIAN)
            sizeBuffer.putInt(jpeg.size)
            stream.write(sizeBuffer.array())
            stream.write(jpeg)
            stream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            scope.launch(Dispatchers.Main) {
                stopStreaming()
                startDiscovery()
            }
        }
    }
    
    private fun stopStreaming() {
        isStreaming = false
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {}
        outputStream = null
        socket = null
        updateUI()
    }
    
    private fun restartCamera() {
        closeCamera()
        openCamera()
    }
    
    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }
    
    override fun onDestroy() {
        stopStreaming()
        closeCamera()
        cameraThread?.quitSafely()
        scope.cancel()
        super.onDestroy()
    }
}
