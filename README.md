# WebCAMO 📱➡️💻

> **Transform your Android phone into a high-quality virtual webcam for Windows**

WebCAMO is an open-source virtual webcam solution that streams your Android phone's camera to Windows, making it available as a standard webcam device in any application (Zoom, Teams, OBS, etc.).

---

## ✨ Features

- 📸 **High Quality Video** - 720p @ 30fps (expandable to 1080p)
- 🌐 **WiFi Streaming** - Works over local network via WebRTC
- 🔒 **Secure** - Encrypted peer-to-peer connection
- 🖥️ **Universal Compatibility** - DirectShow virtual camera works with any Windows app
- ⚡ **Low Latency** - Optimized for real-time video conferencing
- 🎯 **Simple UI** - Clean Android app with one-tap streaming

---

## 🏗️ Architecture

```
┌─────────────────┐     WebSocket      ┌─────────────────┐
│   Android App   │◄──────────────────►│ Signaling Server│
│  (Kotlin/WebRTC)│                    │    (Node.js)    │
└────────┬────────┘                    └────────┬────────┘
         │                                      │
         │ WebRTC (P2P Video Stream)            │
         │                                      │
         ▼                                      ▼
┌─────────────────┐                    ┌─────────────────┐
│ Windows Desktop │◄───────────────────│   WebSocket     │
│     (C++)       │                    │   Signaling     │
└────────┬────────┘                    └─────────────────┘
         │
         │ Shared Memory
         ▼
┌─────────────────┐
│  DirectShow     │ ◄── Applications (Zoom, Teams, OBS)
│ Virtual Camera  │
└─────────────────┘
```

---

## 🚀 Quick Start

### Prerequisites

- Android 7.0+ device
- Windows 10/11 PC
- Node.js 18+ (for signaling server)
- Same WiFi network

### 1. Start Signaling Server

```bash
cd signaling
npm install
npm start
```

Server runs on `ws://your-ip:8080`

### 2. Install Windows App

```bash
cd windows
mkdir build && cd build
cmake ..
cmake --build . --config Release

# Register virtual camera (run as Admin)
regsvr32 bin\WebCAMOFilter.dll
```

### 3. Install Android App

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Connect!

1. Open WebCAMO app on Android
2. Enter signaling server URL: `ws://192.168.1.x:8080`
3. Tap **Start Streaming**
4. On Windows, right-click tray icon → **Connect**
5. Open Zoom/Teams → Select **WebCAMO Camera**

---

## 📁 Project Structure

```
WebCAMO/
├── android/                 # Android app (Kotlin)
│   ├── app/src/main/
│   │   ├── kotlin/com/webcamo/
│   │   │   ├── webrtc/          # WebRTC client
│   │   │   ├── signaling/       # WebSocket signaling
│   │   │   └── ui/              # Activities
│   │   └── res/                 # Layouts, drawables
│   └── build.gradle.kts
│
├── windows/                 # Windows desktop (C++)
│   ├── src/
│   │   ├── Application.cpp      # Main app logic
│   │   ├── SignalingClient.cpp  # WebSocket client
│   │   ├── WebRTCReceiver.cpp   # Video receiver
│   │   ├── VirtualCamera.cpp    # Shared memory bridge
│   │   └── filter/              # DirectShow filter DLL
│   ├── include/
│   └── CMakeLists.txt
│
├── signaling/               # Signaling server (Node.js)
│   ├── server.js
│   └── package.json
│
└── docs/
```

---

## 🔧 Building from Source

### Android

**Requirements:**
- Android Studio 2024+
- JDK 17
- Android SDK 34

```bash
cd android
./gradlew assembleRelease
```

### Windows

**Requirements:**
- Visual Studio 2022 (C++ workload)
- CMake 3.20+
- Windows SDK

```bash
cd windows
mkdir build && cd build
cmake .. -G "Visual Studio 17 2022"
cmake --build . --config Release
```

### Signaling Server

```bash
cd signaling
npm install
npm start
```

---

## 🔌 Technology Stack

| Component | Technology | Purpose |
|-----------|------------|---------|
| Android | Kotlin + WebRTC SDK | Camera capture & streaming |
| Windows | C++ + DirectShow | Virtual camera & video receive |
| Signaling | Node.js + WebSocket | Connection negotiation |
| Transport | WebRTC | Secure P2P video delivery |

---

## 🛠️ Configuration

### Android App

Edit `MainActivity.kt`:
```kotlin
private const val DEFAULT_SERVER_URL = "ws://192.168.1.100:8080"
private const val DEFAULT_ROOM = "webcamo"
```

### Video Quality

Edit `WebRTCClient.kt`:
```kotlin
private const val VIDEO_WIDTH = 1920   // 1080p
private const val VIDEO_HEIGHT = 1080
private const val VIDEO_FPS = 30
```

---

## 🐛 Troubleshooting

### Virtual camera not showing in apps

1. Register the filter as Administrator: `regsvr32 WebCAMOFilter.dll`
2. Restart the application (Zoom, Teams, etc.)
3. Some apps need a full restart to detect new cameras

### Connection fails

1. Ensure both devices are on the same WiFi network
2. Check if signaling server is running
3. Verify the IP address is correct
4. Check firewall settings (allow port 8080)

### High latency

1. Use 5GHz WiFi instead of 2.4GHz
2. Reduce video resolution in settings
3. Close other bandwidth-heavy applications

---

## 📄 License

MIT License - see [LICENSE](LICENSE)

---

## 🙏 Acknowledgments

- [WebRTC Project](https://webrtc.org/)
- [Stream WebRTC Android SDK](https://github.com/nicely/stream-webrtc-android)
- [DirectShow Base Classes](https://github.com/microsoft/Windows-classic-samples)

---

**Made with ❤️ for better video calls**
