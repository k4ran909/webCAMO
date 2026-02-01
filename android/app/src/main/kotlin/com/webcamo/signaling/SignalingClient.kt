package com.webcamo.signaling

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.URI

/**
 * WebSocket-based signaling client for WebRTC connection establishment.
 * Handles SDP offer/answer and ICE candidate exchange.
 */
class SignalingClient(
    private val listener: SignalingListener
) {
    companion object {
        private const val TAG = "SignalingClient"
        private const val RECONNECT_DELAY_MS = 3000L
    }
    
    private val gson = Gson()
    private var webSocket: WebSocketClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private var serverUrl: String = ""
    private var roomId: String = ""
    private var shouldReconnect = false
    
    /**
     * Connect to signaling server
     */
    fun connect(url: String, room: String) {
        serverUrl = url
        roomId = room
        shouldReconnect = true
        
        doConnect()
    }
    
    private fun doConnect() {
        Log.d(TAG, "Connecting to signaling server: $serverUrl")
        _connectionState.value = ConnectionState.CONNECTING
        
        try {
            val uri = URI("$serverUrl?room=$roomId&role=sender")
            
            webSocket = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d(TAG, "WebSocket connected")
                    _connectionState.value = ConnectionState.CONNECTED
                    listener.onConnected()
                }
                
                override fun onMessage(message: String?) {
                    message?.let { handleMessage(it) }
                }
                
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "WebSocket closed: $reason (code: $code)")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    listener.onDisconnected()
                    
                    if (shouldReconnect) {
                        scheduleReconnect()
                    }
                }
                
                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket error: ${ex?.message}")
                    _connectionState.value = ConnectionState.ERROR
                    listener.onError(ex?.message ?: "Unknown error")
                }
            }
            
            webSocket?.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
            listener.onError(e.message ?: "Connection failed")
            
            if (shouldReconnect) {
                scheduleReconnect()
            }
        }
    }
    
    private fun scheduleReconnect() {
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (shouldReconnect) {
                doConnect()
            }
        }
    }
    
    /**
     * Handle incoming signaling message
     */
    private fun handleMessage(message: String) {
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return
            
            Log.d(TAG, "Received message type: $type")
            
            when (type) {
                "answer" -> {
                    val sdp = json.get("sdp")?.asString ?: return
                    val sessionDescription = SessionDescription(
                        SessionDescription.Type.ANSWER,
                        sdp
                    )
                    listener.onAnswerReceived(sessionDescription)
                }
                
                "ice-candidate" -> {
                    val candidateJson = json.getAsJsonObject("candidate") ?: return
                    val sdpMid = candidateJson.get("sdpMid")?.asString ?: return
                    val sdpMLineIndex = candidateJson.get("sdpMLineIndex")?.asInt ?: return
                    val sdp = candidateJson.get("candidate")?.asString ?: return
                    
                    val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                    listener.onIceCandidateReceived(iceCandidate)
                }
                
                "peer-joined" -> {
                    Log.d(TAG, "Receiver joined the room")
                    listener.onPeerJoined()
                }
                
                "peer-left" -> {
                    Log.d(TAG, "Receiver left the room")
                    listener.onPeerLeft()
                }
                
                "error" -> {
                    val errorMsg = json.get("message")?.asString ?: "Unknown error"
                    listener.onError(errorMsg)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}")
        }
    }
    
    /**
     * Send SDP offer to signaling server
     */
    fun sendOffer(sdp: SessionDescription) {
        val message = JsonObject().apply {
            addProperty("type", "offer")
            addProperty("sdp", sdp.description)
        }
        send(message.toString())
    }
    
    /**
     * Send ICE candidate to signaling server
     */
    fun sendIceCandidate(candidate: IceCandidate) {
        val candidateJson = JsonObject().apply {
            addProperty("sdpMid", candidate.sdpMid)
            addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
            addProperty("candidate", candidate.sdp)
        }
        
        val message = JsonObject().apply {
            addProperty("type", "ice-candidate")
            add("candidate", candidateJson)
        }
        send(message.toString())
    }
    
    private fun send(message: String) {
        if (webSocket?.isOpen == true) {
            Log.d(TAG, "Sending: $message")
            webSocket?.send(message)
        } else {
            Log.w(TAG, "WebSocket not connected, cannot send message")
        }
    }
    
    /**
     * Disconnect from signaling server
     */
    fun disconnect() {
        shouldReconnect = false
        webSocket?.close()
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    /**
     * Release resources
     */
    fun release() {
        disconnect()
        scope.cancel()
    }
    
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    interface SignalingListener {
        fun onConnected()
        fun onDisconnected()
        fun onPeerJoined()
        fun onPeerLeft()
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onError(message: String)
    }
}
