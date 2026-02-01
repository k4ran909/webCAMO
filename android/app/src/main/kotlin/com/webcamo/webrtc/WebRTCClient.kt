package com.webcamo.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * WebRTC client that handles peer connections and video streaming.
 * This is the core of WebCAMO - it captures camera and streams via WebRTC.
 */
class WebRTCClient(
    private val context: Context,
    private val signalingListener: SignalingListener
) {
    companion object {
        private const val TAG = "WebRTCClient"
        
        // Video constraints
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
    }
    
    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    
    // EGL context for video rendering
    private val eglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext
    
    // ICE servers for NAT traversal
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )
    
    /**
     * Initialize WebRTC components
     */
    fun initialize() {
        Log.d(TAG, "Initializing WebRTC client")
        
        // Create peer connection factory with video encoding support
        val options = PeerConnectionFactory.Options()
        
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBaseContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile
        )
        
        val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        
        Log.d(TAG, "PeerConnectionFactory created successfully")
    }
    
    /**
     * Start camera capture and create video track
     */
    fun startCamera(localView: SurfaceViewRenderer?, useFrontCamera: Boolean = true) {
        Log.d(TAG, "Starting camera (front: $useFrontCamera)")
        
        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory not initialized")
            return
        }
        
        // Create camera capturer
        videoCapturer = createCameraCapturer(useFrontCamera)
        
        if (videoCapturer == null) {
            Log.e(TAG, "Failed to create camera capturer")
            return
        }
        
        // Create video source
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
        localVideoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
        
        videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
        videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        
        // Create video track
        localVideoTrack = factory.createVideoTrack("WebCAMO_video", localVideoSource)
        
        // Add local view renderer
        localView?.let { view ->
            view.init(eglBaseContext, null)
            view.setMirror(useFrontCamera)
            localVideoTrack?.addSink(view)
        }
        
        Log.d(TAG, "Camera started successfully")
    }
    
    /**
     * Switch between front and back camera
     */
    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                Log.d(TAG, "Camera switched to ${if (isFrontCamera) "front" else "back"}")
            }
            
            override fun onCameraSwitchError(error: String?) {
                Log.e(TAG, "Camera switch error: $error")
            }
        })
    }
    
    /**
     * Create peer connection and prepare for streaming
     */
    fun createPeerConnection() {
        Log.d(TAG, "Creating peer connection")
        
        val factory = peerConnectionFactory ?: return
        
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        
        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }
            
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                    PeerConnection.IceConnectionState.CHECKING -> {
                        _connectionState.value = ConnectionState.CONNECTING
                    }
                    else -> {}
                }
            }
            
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $state")
            }
            
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "New ICE candidate: ${it.sdp}")
                    signalingListener.onIceCandidate(it)
                }
            }
            
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            
            override fun onAddStream(stream: MediaStream?) {}
            
            override fun onRemoveStream(stream: MediaStream?) {}
            
            override fun onDataChannel(channel: DataChannel?) {}
            
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }
            
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
        
        // Add video track to peer connection
        localVideoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("WebCAMO_stream"))
            Log.d(TAG, "Video track added to peer connection")
        }
    }
    
    /**
     * Create SDP offer (called when initiating connection)
     */
    fun createOffer(callback: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { offer ->
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set")
                            callback(offer)
                        }
                        override fun onCreateFailure(error: String?) {}
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Failed to set local description: $error")
                        }
                    }, offer)
                }
            }
            
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    /**
     * Set remote SDP answer
     */
    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully")
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
            }
        }, sdp)
    }
    
    /**
     * Add ICE candidate from remote peer
     */
    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }
    
    /**
     * Create camera capturer using Camera2 API
     */
    private fun createCameraCapturer(useFrontCamera: Boolean): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        
        // Find appropriate camera
        for (deviceName in deviceNames) {
            val isFront = enumerator.isFrontFacing(deviceName)
            if (useFrontCamera == isFront) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    return capturer
                }
            }
        }
        
        // Fallback to any available camera
        for (deviceName in deviceNames) {
            val capturer = enumerator.createCapturer(deviceName, null)
            if (capturer != null) {
                return capturer
            }
        }
        
        return null
    }
    
    /**
     * Stop streaming and release resources
     */
    fun release() {
        Log.d(TAG, "Releasing WebRTC client")
        
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        
        localVideoTrack?.dispose()
        localVideoTrack = null
        
        localVideoSource?.dispose()
        localVideoSource = null
        
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        
        peerConnection?.close()
        peerConnection = null
        
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        
        eglBase.release()
        
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }
    
    interface SignalingListener {
        fun onIceCandidate(candidate: IceCandidate)
    }
}
