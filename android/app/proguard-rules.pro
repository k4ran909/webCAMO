# WebCAMO ProGuard Rules

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Java WebSocket
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Keep our model classes
-keep class com.webcamo.** { *; }
