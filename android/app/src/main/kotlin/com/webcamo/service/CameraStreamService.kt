package com.webcamo.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.app.NotificationCompat
import com.webcamo.R
import com.webcamo.ui.AutoConnectActivity
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Foreground Service for background camera streaming
 * Keeps streaming even when app is in background with no FPS loss
 */
class CameraStreamService : Service() {
    companion object {
        private const val TAG = "CameraStreamService"
        private const val CHANNEL_ID = "webcamo_streaming"
        private const val NOTIFICATION_ID = 1001
        private const val DISCOVERY_PORT = 9001
        private const val STREAM_PORT = 9000
        private const val JPEG_QUALITY = 85
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        
        // Actions
        const val ACTION_START = "com.webcamo.START_STREAMING"
        const val ACTION_STOP = "com.webcamo.STOP_STREAMING"
        const val ACTION_SWITCH_CAMERA = "com.webcamo.SWITCH_CAMERA"
        
        // Broadcast for UI updates
        const val BROADCAST_STATUS = "com.webcamo.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_FPS = "fps"
        
        @Volatile
        var isRunning = false
            private set
    }
    
    // Camera
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var cameraManager: CameraManager
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var useFrontCamera = true
    private var previewSurface: Surface? = null
    
    // Networking
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isStreaming = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Wake lock
    private var wakeLock: PowerManager.WakeLock? = null
    
    // FPS tracking
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
        acquireWakeLock()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                startCamera()
                startDiscovery()
                isRunning = true
            }
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
            ACTION_SWITCH_CAMERA -> {
                switchCamera()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        isRunning = false
        stopStreaming()
        closeCamera()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WebCAMO Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camera streaming to PC"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundService() {
        val notificationIntent = Intent(this, AutoConnectActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, CameraStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WebCAMO")
            .setContentText("📡 Searching for PC...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun updateNotification(text: String) {
        val notificationIntent = Intent(this, AutoConnectActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, CameraStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WebCAMO")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WebCAMO::StreamingWakeLock"
        ).apply {
            acquire(10*60*60*1000L) // 10 hours max
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
    
    // ===== Discovery =====
    
    private fun startDiscovery() {
        scope.launch {
            while (isActive && !isStreaming) {
                try {
                    discoverPC()
                } catch (e: Exception) {
                    Log.e(TAG, "Discovery error: ${e.message}")
                }
                delay(500) // Fast discovery
            }
        }
    }
    
    private suspend fun discoverPC() {
        withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                socket.soTimeout = 400
                socket.broadcast = true
                
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                val dhcp = wifiManager.dhcpInfo
                val broadcast = getBroadcastAddress(dhcp)
                
                val message = "WEBCAMO_DISCOVER".toByteArray()
                val packet = DatagramPacket(message, message.size, broadcast, DISCOVERY_PORT)
                socket.send(packet)
                
                val buffer = ByteArray(1024)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                
                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length)
                    
                    if (response.startsWith("WEBCAMO_PC|")) {
                        val parts = response.split("|")
                        if (parts.size >= 3) {
                            val port = parts[2].toIntOrNull() ?: STREAM_PORT
                            val pcIp = responsePacket.address.hostAddress ?: return@withContext
                            
                            Log.d(TAG, "Discovered PC at $pcIp:$port")
                            if (!isStreaming) {
                                connectToPC(pcIp, port)
                            }
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Continue searching
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
                
                updateNotification("📡 Streaming to ${ip}")
                broadcastStatus("streaming")
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
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
        
        updateNotification("Stopped")
        broadcastStatus("stopped")
    }
    
    // ===== Camera =====
    
    private fun startCamera() {
        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        openCamera()
    }
    
    private fun openCamera() {
        try {
            val cameraId = getCameraId()
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
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied")
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
        
        imageReader = ImageReader.newInstance(VIDEO_WIDTH, VIDEO_HEIGHT, ImageFormat.YUV_420_888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null && isStreaming) {
                processImage(image)
            }
            image?.close()
        }, cameraHandler)
        
        val surfaces = mutableListOf<Surface>()
        surfaces.add(imageReader!!.surface)
        
        // Add preview surface if available
        previewSurface?.let { surfaces.add(it) }
        
        try {
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startCapture(surfaces)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session config failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
        }
    }
    
    private fun startCapture(surfaces: List<Surface>) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            surfaces.forEach { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture: ${e.message}")
        }
    }
    
    private fun processImage(image: android.media.Image) {
        try {
            val jpeg = imageToJpeg(image)
            sendFrame(jpeg)
            trackFps()
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
    
    private fun trackFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTime = now
            broadcastStatus("streaming", currentFps)
        }
    }
    
    private fun switchCamera() {
        useFrontCamera = !useFrontCamera
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
    
    // Set preview surface from Activity
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        if (cameraDevice != null) {
            createCaptureSession()
        }
    }
    
    private fun broadcastStatus(status: String, fps: Int = 0) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_FPS, fps)
        }
        sendBroadcast(intent)
    }
}
