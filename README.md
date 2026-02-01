<p align="center">
  <img src="logo.jpg" alt="WebCAMO Logo" width="200" style="border-radius: 50%" />
</p>

# WebCAMO 📱➡️💻

> **Transform your Android phone into a high-quality webcam for Windows**

WebCAMO turns your Android phone's camera into a virtual webcam on Windows PC. Works with Zoom, Teams, Discord, OBS, and any other video application.

## ✨ Features

| Feature | Description |
|---------|-------------|
| 📸 **1280x720 @ 30fps** | High quality video streaming |
| 🔍 **Auto-Discovery** | No manual IP entry - finds PC automatically |
| 📡 **Background Streaming** | Keep streaming with app minimized |
| 🖥️ **DirectShow Virtual Camera** | Works with any Windows app |
| ⚡ **Low Latency** | Optimized TCP/JPEG streaming |
| 🎯 **One-Tap Start** | Simple, clean UI |

---

## 🏗️ How It Works

```
┌──────────────────┐                    ┌──────────────────┐
│   Android Phone  │    UDP Discovery   │   Windows PC     │
│   (Camera App)   │◄──────────────────►│   (Receiver)     │
└────────┬─────────┘                    └────────┬─────────┘
         │                                       │
         │         TCP Stream (JPEG)             │
         │◄─────────────────────────────────────►│
         │                                       │
         │                              ┌────────┴─────────┐
         │                              │ Shared Memory    │
         │                              └────────┬─────────┘
         │                                       │
         │                              ┌────────▼─────────┐
         │                              │ DirectShow       │
         │                              │ Virtual Camera   │
         └──────────────────────────────│ (WebCAMO)        │
                                        └──────────────────┘
                                                 │
                                        ┌────────▼─────────┐
                                        │ Zoom / Teams /   │
                                        │ Discord / OBS    │
                                        └──────────────────┘
```

---

## 🚀 Quick Start

### Requirements

- **Android 7.0+** phone
- **Windows 10/11** PC
- Same **WiFi network**

### 1. Install Windows App

Download `WebCAMO.exe` from [Releases](../../releases)

Or build from source:
```bash
cd windows-python
pip install -r requirements.txt
python webcamo_gui.py
```

### 2. Install Virtual Camera (Optional)

For apps that need a named camera device:
```bash
cd windows
build_filter.bat
# Then run as Administrator:
regsvr32 bin\WebCAMOFilter.dll
```

### 3. Install Android App

Download `WebCAMO-Android.apk` from [Releases](../../releases)

Or build from source:
```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Stream!

1. Start **WebCAMO** on Windows
2. Open **WebCAMO** on Android
3. They auto-connect over WiFi
4. Select **WebCAMO** camera in your video app

---

## 📁 Project Structure

```
WebCAMO/
├── android/                 # Android app (Kotlin)
│   └── app/src/main/kotlin/com/webcamo/
│       ├── service/         # Background camera service
│       ├── ui/              # Activities
│       └── stream/          # TCP streaming
│
├── windows-python/          # Windows receiver (Python)
│   ├── webcamo_gui.py       # Main GUI app
│   └── README.md
│
├── windows/                 # DirectShow virtual camera (C++)
│   ├── src/filter/          # Virtual camera DLL
│   ├── build_filter.bat     # Build script
│   └── CMakeLists.txt
│
├── signaling/               # WebRTC signaling (unused in TCP mode)
│
└── docs/
    ├── ARCHITECTURE.md
    └── BUILD.md
```

---

## 🔧 Building from Source

### Android

```bash
cd android
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: Android Studio or Gradle, JDK 17

### Windows (Python Receiver)

```bash
cd windows-python
pip install opencv-python numpy pillow pyvirtualcam
python webcamo_gui.py

# Build standalone EXE:
pip install pyinstaller
pyinstaller --onefile --noconsole webcamo_gui.py
```

### Windows (DirectShow Filter)

```bash
cd windows
build_filter.bat
regsvr32 bin\WebCAMOFilter.dll  # Run as Admin
```

Requirements: Visual Studio 2022, Windows SDK

---

## 🐛 Troubleshooting

| Issue | Solution |
|-------|----------|
| **Not discovering PC** | Ensure both devices on same WiFi |
| **Camera stops in background** | Disable battery optimization for WebCAMO |
| **WebCAMO camera not in list** | Restart your video app after registering filter |
| **Low FPS** | Use 5GHz WiFi, close other apps |

---

## 📄 License

MIT License - see [LICENSE](LICENSE)

---

**Made with ❤️ for better video calls**
