package com.webcamo.stream

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Simple TCP video streamer - alternative to WebRTC
 * Sends MJPEG frames over raw TCP connection
 * 
 * Protocol:
 * - Frame header: 4 bytes (frame size as uint32 little-endian)
 * - Frame data: JPEG bytes
 */
class SimpleStreamer {
    companion object {
        private const val TAG = "SimpleStreamer"
        private const val DEFAULT_PORT = 9000
        private const val JPEG_QUALITY = 80
    }
    
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isConnected = false
    
    var onStateChanged: ((Boolean) -> Unit)? = null
    
    /**
     * Connect to the Windows desktop receiver
     */
    suspend fun connect(host: String, port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to $host:$port")
            socket = Socket(host, port)
            outputStream = socket?.getOutputStream()
            isConnected = true
            
            withContext(Dispatchers.Main) {
                onStateChanged?.invoke(true)
            }
            
            Log.d(TAG, "Connected successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            isConnected = false
            false
        }
    }
    
    /**
     * Send a camera frame (YUV format from Camera2)
     */
    fun sendFrame(image: Image) {
        if (!isConnected || outputStream == null) return
        
        scope.launch {
            try {
                // Convert YUV to JPEG
                val jpeg = yuvToJpeg(image)
                if (jpeg != null) {
                    sendJpeg(jpeg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send frame: ${e.message}")
                disconnect()
            }
        }
    }
    
    /**
     * Send pre-encoded JPEG frame
     */
    @Synchronized
    private fun sendJpeg(jpeg: ByteArray) {
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
            throw e
        }
    }
    
    /**
     * Convert YUV Image to JPEG
     */
    private fun yuvToJpeg(image: Image): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "Unexpected image format: ${image.format}")
            return null
        }
        
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        // Convert to NV21 format for YuvImage
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )
        
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, image.width, image.height),
            JPEG_QUALITY,
            out
        )
        
        return out.toByteArray()
    }
    
    /**
     * Disconnect from receiver
     */
    fun disconnect() {
        isConnected = false
        
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect: ${e.message}")
        }
        
        outputStream = null
        socket = null
        
        scope.launch(Dispatchers.Main) {
            onStateChanged?.invoke(false)
        }
    }
    
    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected
    
    /**
     * Release resources
     */
    fun release() {
        disconnect()
        scope.cancel()
    }
}
