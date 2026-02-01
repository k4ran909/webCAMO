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
import android.util.Size
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

/**
 * Stable Foreground Service for background camera streaming
 * Works reliably on ALL Android versions (API 21+)
 * 
 * Key features:
 * - Proper foreground service with notification
 * - Full WakeLock to prevent CPU sleep  
 * - WiFi lock to maintain network
 * - Camera runs entirely in service (no activity conflict)
 * - Auto-reconnect on disconnect
 * - Stable across app minimize/restore
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
        
        const val ACTION_START = "com.webcamo.START_STREAMING"
        const val ACTION_STOP = "com.webcamo.STOP_STREAMING"
        const val ACTION_SWITCH_CAMERA = "com.webcamo.SWITCH_CAMERA"
        
        const val BROADCAST_STATUS = "com.webcamo.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"  
        const val EXTRA_FPS = "fps"
        const val EXTRA_CONNECTED = "connected"
        
        @Volatile var isRunning = false
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
    private val cameraOpenCloseLock = Semaphore(1)
    
    // Networking
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    @Volatile private var isStreaming = false
    @Volatile private var isDiscovering = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Wake locks - CRITICAL for background operation
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    
    // FPS tracking
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0
    
    // Main thread handler for UI updates
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    // MUST call startForeground IMMEDIATELY (within 5 seconds on Android 8+)
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
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        stopEverything()
        super.onDestroy()
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        // App was swiped away - keep service running!
        Log.d(TAG, "Task removed, service continuing")
        super.onTaskRemoved(rootIntent)
    }
    
    // ===== Notification =====
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WebCAMO Camera Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camera streaming to your PC"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundImmediately() {
        val notification = buildNotification("🔍 Searching for PC...")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10-13
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                // Android 9 and below
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            // Fallback
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun buildNotification(text: String): Notification {
        val notificationIntent = Intent(this, AutoConnectActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
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
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WebCAMO")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
    
    private fun updateNotification(text: String) {
        mainHandler.post {
            try {
                val notification = buildNotification(text)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.e(TAG, "updateNotification failed: ${e.message}")
            }
        }
    }
    
    // ===== Locks =====
    
    private fun acquireLocks() {
        // CPU wake lock - keeps processing running
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WebCAMO::CameraStream"
            ).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours max
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquire failed: ${e.message}")
        }
        
        // WiFi lock - keeps WiFi active for streaming
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wifiManager.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "WebCAMO::WifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "WifiLock acquire failed: ${e.message}")
        }
    }
    
    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {}
        
        try {
            wifiLock?.let { if (it.isHeld) it.release() }  
            wifiLock = null
        } catch (e: Exception) {}
        
        Log.d(TAG, "Locks released")
    }
    
    // ===== Discovery =====
    
    private fun startDiscoveryLoop() {
        if (isDiscovering) return
        isDiscovering = true
        
        scope.launch {
            Log.d(TAG, "Discovery loop started")
            while (isActive && isRunning) {
                if (!isStreaming) {
                    try {
                        discoverAndConnect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Discovery error: ${e.message}")
                    }
                }
                delay(500)
            }
            isDiscovering = false
        }
    }
    
    private suspend fun discoverAndConnect() {
        withContext(Dispatchers.IO) {
            var udpSocket: DatagramSocket? = null
            try {
                udpSocket = DatagramSocket()
                udpSocket.soTimeout = 400
                udpSocket.broadcast = true
                
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                val dhcp = wifiManager.dhcpInfo
                if (dhcp.ipAddress == 0) {
                    Log.w(TAG, "No WiFi connection")
                    return@withContext
                }
                
                val broadcast = getBroadcastAddress(dhcp)
                val message = "WEBCAMO_DISCOVER".toByteArray()
                val packet = DatagramPacket(message, message.size, broadcast, DISCOVERY_PORT)
                udpSocket.send(packet)
                
                val buffer = ByteArray(1024)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                
                udpSocket.receive(responsePacket)
                val response = String(responsePacket.data, 0, responsePacket.length)
                
                if (response.startsWith("WEBCAMO_PC|")) {
                    val parts = response.split("|")
                    if (parts.size >= 3) {
                        val port = parts[2].toIntOrNull() ?: STREAM_PORT
                        val pcIp = responsePacket.address.hostAddress ?: return@withContext
                        
                        Log.d(TAG, "Found PC: $pcIp:$port")
                        connectToPC(pcIp, port)
                    }
                }
            } catch (e: SocketTimeoutException) {
                // Normal - no PC found yet
            } catch (e: Exception) {
                Log.w(TAG, "Discovery: ${e.message}")
            } finally {
                udpSocket?.close()
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
    
    private suspend fun connectToPC(ip: String, port: Int) {
        if (isStreaming) return
        
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to $ip:$port")
                socket = Socket()
                socket?.connect(InetSocketAddress(ip, port), 3000)
                socket?.tcpNoDelay = true
                outputStream = socket?.getOutputStream()
                isStreaming = true
                
                updateNotification("📡 Streaming to $ip")
                broadcastStatus("streaming", 0, true)
                Log.d(TAG, "Connected!")
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                closeConnection()
            }
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
        cameraThread = HandlerThread("WebCAMOCamera").apply { 
            start()
        }
        cameraHandler = Handler(cameraThread!!.looper)
    }
    
    private fun openCamera() {
        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Camera lock timeout")
            return
        }
        
        try {
            val cameraId = selectCamera()
            Log.d(TAG, "Opening camera: $cameraId")
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened")
                    cameraDevice = camera
                    cameraOpenCloseLock.release()
                    createCaptureSession()
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    // Try to reopen
                    mainHandler.postDelayed({ openCamera() }, 1000)
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    // Try to reopen
                    mainHandler.postDelayed({ openCamera() }, 2000)
                }
            }, cameraHandler)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied")
            cameraOpenCloseLock.release()
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access: ${e.message}")
            cameraOpenCloseLock.release()
        } catch (e: Exception) {
            Log.e(TAG, "Camera open error: ${e.message}")
            cameraOpenCloseLock.release()
        }
    }
    
    private fun selectCamera(): String {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (useFrontCamera && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
            if (!useFrontCamera && facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cameraManager.cameraIdList.firstOrNull() ?: "0"
    }
    
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        
        try {
            imageReader = ImageReader.newInstance(
                VIDEO_WIDTH, VIDEO_HEIGHT, 
                ImageFormat.YUV_420_888, 3
            )
            
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    if (isStreaming) {
                        processAndSendFrame(image)
                    }
                    image.close()
                }
            }, cameraHandler)
            
            val surfaces = listOf(imageReader!!.surface)
            
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Capture session configured")
                        captureSession = session
                        startRepeatingCapture()
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session config failed")
                    }
                },
                cameraHandler
            )
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
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
            Log.d(TAG, "Repeating capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Start capture error: ${e.message}")
        }
    }
    
    private fun processAndSendFrame(image: android.media.Image) {
        try {
            val jpegData = convertToJpeg(image)
            sendJpegFrame(jpegData)
            updateFps()
        } catch (e: Exception) {
            Log.e(TAG, "Frame error: ${e.message}")
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
            Log.w(TAG, "Send error: ${e.message}")
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
    
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Close camera error: ${e.message}")
        } finally {
            cameraOpenCloseLock.release()
        }
    }
    
    private fun restartCamera() {
        closeCamera()
        openCamera()
    }
    
    private fun stopEverything() {
        Log.d(TAG, "Stopping everything")
        isRunning = false
        isStreaming = false
        isDiscovering = false
        
        scope.cancel()
        closeConnection()
        closeCamera()
        
        cameraThread?.quitSafely()
        try { cameraThread?.join(1000) } catch (e: Exception) {}
        cameraThread = null
        cameraHandler = null
        
        releaseLocks()
        broadcastStatus("stopped", 0, false)
    }
    
    private fun broadcastStatus(status: String, fps: Int, connected: Boolean) {
        try {
            val intent = Intent(BROADCAST_STATUS).apply {
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_CONNECTED, connected)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {}
    }
}
