package com.webcamo

import android.app.Application
import org.webrtc.PeerConnectionFactory

class WebCAMOApplication : Application() {
    
    companion object {
        lateinit var instance: WebCAMOApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize WebRTC
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
    }
}
