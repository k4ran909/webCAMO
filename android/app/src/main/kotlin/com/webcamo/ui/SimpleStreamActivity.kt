package com.webcamo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.webcamo.R
import com.webcamo.databinding.ActivitySimpleBinding
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Simple TCP streaming activity - easier to set up than WebRTC
 * Sends MJPEG frames directly over TCP connection
 */
class SimpleStreamActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SimpleStream"
        private const val DEFAULT_PORT = 9000
        private const val JPEG_QUALITY = 80
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
    }

    private lateinit var binding: ActivitySimpleBinding
    
    // Camera
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var cameraManager: CameraManager
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var useFrontCamera = true
    
    // Streaming
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isStreaming = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCameraPreview()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        
        setupUI()
        checkPermission()
    }
    
    private fun setupUI() {
        // Host input - default to local IP hint
        binding.hostInput.hint = "Windows PC IP (e.g., 192.168.1.100)"
        binding.portInput.setText(DEFAULT_PORT.toString())
        
        // Connect button
        binding.connectButton.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                val host = binding.hostInput.text.toString().trim()
                val port = binding.portInput.text.toString().toIntOrNull() ?: DEFAULT_PORT
                
                if (host.isNotEmpty()) {
                    startStreaming(host, port)
                } else {
                    Toast.makeText(this, "Enter Windows PC IP address", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Switch camera
        binding.switchCameraButton.setOnClickListener {
            useFrontCamera = !useFrontCamera
            restartCamera()
        }
        
        updateUI()
    }
    
    private fun updateUI() {
        runOnUiThread {
            if (isStreaming) {
                binding.connectButton.text = "Stop"
                binding.statusText.text = "Streaming..."
                binding.statusText.setTextColor(getColor(android.R.color.holo_green_light))
            } else {
                binding.connectButton.text = "Start"
                binding.statusText.text = "Ready"
                binding.statusText.setTextColor(getColor(android.R.color.white))
            }
        }
    }
    
    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
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
                        Log.e(TAG, "Camera error: $error")
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
        
        // Create ImageReader for capturing frames
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
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session configuration failed")
                    }
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
    
    private fun processImage(image: Image) {
        try {
            val jpeg = imageToJpeg(image)
            sendFrame(jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image: ${e.message}")
        }
    }
    
    private fun imageToJpeg(image: Image): ByteArray {
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
            // Write frame size (4 bytes, little-endian)
            val sizeBuffer = ByteBuffer.allocate(4)
            sizeBuffer.order(ByteOrder.LITTLE_ENDIAN)
            sizeBuffer.putInt(jpeg.size)
            stream.write(sizeBuffer.array())
            
            // Write frame data
            stream.write(jpeg)
            stream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            scope.launch(Dispatchers.Main) {
                stopStreaming()
            }
        }
    }
    
    private fun startStreaming(host: String, port: Int) {
        scope.launch {
            try {
                Log.d(TAG, "Connecting to $host:$port...")
                socket = Socket(host, port)
                outputStream = socket?.getOutputStream()
                isStreaming = true
                
                withContext(Dispatchers.Main) {
                    updateUI()
                    Toast.makeText(this@SimpleStreamActivity, "Connected!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SimpleStreamActivity, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun stopStreaming() {
        isStreaming = false
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection: ${e.message}")
        }
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
