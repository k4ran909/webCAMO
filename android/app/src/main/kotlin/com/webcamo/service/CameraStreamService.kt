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
import androidx.core.app.NotificationCompat
import com.webcamo.R
import com.webcamo.ui.AutoConnectActivity
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stable Foreground Service for background camera streaming
 * Optimized for High FPS & Quality
 */
class CameraStreamService : Service() {
    companion object {
        private const val TAG = "CameraStreamService"
        private const val CHANNEL_ID = "webcamo_streaming"
        private const val NOTIFICATION_ID = 1001
        private const val DISCOVERY_PORT = 9001
        private const val STREAM_PORT = 9000
        private const val JPEG_QUALITY = 80 // Optimized for speed/quality balance
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        
        const val ACTION_START = "com.webcamo.START_STREAMING"
        const val ACTION_STOP = "com.webcamo.STOP_STREAMING"
        const val ACTION_SWITCH_CAMERA = "com.webcamo.SWITCH_CAMERA"
        
        const val BROADCAST_STATUS = "com.webcamo.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"  
        const val EXTRA_FPS = "fps"
        const val EXTRA_CONNECTED = "connected"
        
        @Volatile var isRunning = false
            private set
            
        // Singleton access for binding
        var instance: CameraStreamService? = null
            private set
    }
    
    // Binder
    inner class LocalBinder : Binder() {
        fun getService(): CameraStreamService = this@CameraStreamService
    }
    private val binder = LocalBinder()
    
    // Camera
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private lateinit var cameraManager: CameraManager
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var useFrontCamera = true
    private val cameraOpenCloseLock = Semaphore(1)
    
    // Networking
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    @Volatile private var isStreaming = false
    @Volatile private var isDiscovering = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val frameProcessingScope = CoroutineScope(Dispatchers.Default) // Dedicated for heavy image work
    
    // Wake locks
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    
    // FPS tracking
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0
    private val isProcessingFrame = AtomicBoolean(false) // Drop frames if busy
    
    // Main thread handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        instance = this
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startForegroundImmediately()
                    acquireLocks()
                    startCameraThread()
                    openCamera()
                    startDiscoveryLoop()
                    isRunning = true
                }
            }
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
            }
            ACTION_SWITCH_CAMERA -> {
                useFrontCamera = !useFrontCamera
                restartCamera()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        instance = null
        stopEverything()
        super.onDestroy()
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed, service continuing")
        super.onTaskRemoved(rootIntent)
    }
    
    // ===== Public API for Activity =====
    
    fun setPreviewSurface(surface: Surface?) {
        if (this.previewSurface != surface) {
            Log.d(TAG, "Updating preview surface")
            this.previewSurface = surface
            // Restart session to apply new surface
            if (cameraDevice != null) {
                createCaptureSession()
            }
        }
    }
    
    // ===== Notification =====
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "WebCAMO Streaming", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camera streaming active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundImmediately() {
        val notification = buildNotification("🔍 Searching for PC...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, AutoConnectActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, CameraStreamService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WebCAMO")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification(text: String) {
        mainHandler.post {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
            } catch (e: Exception) {}
        }
    }
    
    // ===== Locks =====
    
    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebCAMO::WakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WebCAMO::WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {}
    }
    
    // ===== Discovery =====
    
    private fun startDiscoveryLoop() {
        if (isDiscovering) return
        isDiscovering = true
        scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                if (!isStreaming) {
                    try { discoverAndConnect() } catch (e: Exception) {}
                }
                delay(500)
            }
            isDiscovering = false
        }
    }
    
    private suspend fun discoverAndConnect() {
        var udpSocket: DatagramSocket? = null
        try {
            udpSocket = DatagramSocket()
            udpSocket.soTimeout = 400
            udpSocket.broadcast = true
            
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val dhcp = wm.dhcpInfo
            if (dhcp.ipAddress == 0) return
            
            val broadcast = dhcp.ipAddress and dhcp.netmask or dhcp.netmask.inv()
            val quads = ByteArray(4) { k -> (broadcast shr k * 8 and 0xFF).toByte() }
            val address = InetAddress.getByAddress(quads)
            
            val message = "WEBCAMO_DISCOVER".toByteArray()
            udpSocket.send(DatagramPacket(message, message.size, address, DISCOVERY_PORT))
            
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            udpSocket.receive(packet)
            
            val response = String(packet.data, 0, packet.length)
            if (response.startsWith("WEBCAMO_PC|")) {
                val parts = response.split("|")
                if (parts.size >= 3) {
                    val port = parts[2].toIntOrNull() ?: STREAM_PORT
                    val ip = packet.address.hostAddress ?: return
                    connectToPC(ip, port)
                }
            }
        } catch (e: SocketTimeoutException) {
            // No PC found
        } finally {
            udpSocket?.close()
        }
    }
    
    private suspend fun connectToPC(ip: String, port: Int) {
        if (isStreaming) return
        try {
            Log.d(TAG, "Connecting to $ip:$port")
            socket = Socket()
            socket?.connect(InetSocketAddress(ip, port), 3000)
            socket?.tcpNoDelay = true
            outputStream = socket?.getOutputStream()
            isStreaming = true
            updateNotification("📡 Streaming to $ip")
            broadcastStatus("streaming", 0, true)
        } catch (e: Exception) {
            closeConnection()
        }
    }
    
    private fun closeConnection() {
        isStreaming = false
        try { outputStream?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        outputStream = null
        socket = null
        updateNotification("🔍 Searching for PC...")
        broadcastStatus("searching", 0, false)
    }
    
    // ===== Camera =====
    
    private fun startCameraThread() {
        cameraThread = HandlerThread("WebCAMOCamera").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }
    
    private fun openCamera() {
        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) return
        try {
            val id = selectCamera()
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    cameraOpenCloseLock.release()
                    createCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    mainHandler.postDelayed({ openCamera() }, 1000)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    mainHandler.postDelayed({ openCamera() }, 2000)
                }
            }, cameraHandler)
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
        }
    }
    
    private fun selectCamera(): String {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (useFrontCamera) facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: "0"
        } catch (e: Exception) { "0" }
    }
    
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        try {
            // Close existing session
            captureSession?.close()
            captureSession = null
            
            imageReader?.close()
            imageReader = ImageReader.newInstance(VIDEO_WIDTH, VIDEO_HEIGHT, ImageFormat.YUV_420_888, 2)
            
            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isStreaming) {
                    // Drain queue if not streaming to avoid stalls
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                
                // Only process if previous frame is done
                if (isProcessingFrame.compareAndSet(false, true)) {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        processFrameAsync(image)
                    } else {
                        isProcessingFrame.set(false)
                    }
                } else {
                    // Drop frame
                    reader.acquireLatestImage()?.close()
                }
            }, cameraHandler)
            
            val surfaces = ArrayList<Surface>()
            surfaces.add(imageReader!!.surface)
            previewSurface?.let { surfaces.add(it) } // Add preview surface if available
            
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startRepeatingCapture()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Create session error: ${e.message}")
        }
    }
    
    private fun startRepeatingCapture() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(imageReader!!.surface)
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // Optimize FPS range
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30, 30))
            }
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {}
    }
    
    private fun processFrameAsync(image: android.media.Image) {
        // Run conversion on dedicated background thread
        frameProcessingScope.launch {
            try {
                val jpeg = convertToJpeg(image)
                image.close() // Close image as soon as data is copied/used
                sendJpegFrame(jpeg)
                updateFps()
            } catch (e: Exception) {
                try { image.close() } catch (e2: Exception) {}
            } finally {
                isProcessingFrame.set(false)
            }
        }
    }
    
    private fun convertToJpeg(image: android.media.Image): ByteArray {
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
    private fun sendJpegFrame(jpeg: ByteArray) {
        val stream = outputStream ?: return
        try {
            val sizeBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            sizeBuffer.putInt(jpeg.size)
            stream.write(sizeBuffer.array())
            stream.write(jpeg)
            stream.flush()
        } catch (e: Exception) {
            closeConnection()
        }
    }
    
    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTime = now
            broadcastStatus("streaming", currentFps, true)
        }
    }
    
    private fun stopEverything() {
        isRunning = false; isStreaming = false; isDiscovering = false
        scope.cancel()
        frameProcessingScope.cancel()
        closeConnection()
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        try { wakeLock?.release() } catch (e: Exception) {}
        try { wifiLock?.release() } catch (e: Exception) {}
        broadcastStatus("stopped", 0, false)
    }
    
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            imageReader?.close(); imageReader = null
        } finally { cameraOpenCloseLock.release() }
    }
    
    private fun restartCamera() {
        closeCamera()
        openCamera()
    }
    
    private fun broadcastStatus(status: String, fps: Int, connected: Boolean) {
        try {
            sendBroadcast(Intent(BROADCAST_STATUS).apply {
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_CONNECTED, connected)
            })
        } catch (e: Exception) {}
    }
}

