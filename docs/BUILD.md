# Build Instructions

## Prerequisites

### All Platforms
- Git
- CMake 3.20+

### Android
- Android Studio 2024.x or later
- JDK 17
- Android SDK 34 (API level 34)
- Android NDK (optional, for native debugging)

### Windows
- Visual Studio 2022 with C++ Desktop Development workload
- Windows SDK 10.0.22621.0 or later
- CMake 3.20+

### Signaling Server
- Node.js 18+ (LTS recommended)
- npm 9+

---

## Building the Android App

### Using Android Studio (Recommended)

1. Open Android Studio
2. Select **File → Open**
3. Navigate to `WebCAMO/android` folder
4. Wait for Gradle sync to complete
5. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**
6. APK will be in `app/build/outputs/apk/debug/`

### Using Command Line

```bash
cd android

# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
# or
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Building the Windows Desktop App

### Using Visual Studio

1. Open Command Prompt or PowerShell
2. Navigate to the windows directory:
   ```bash
   cd windows
   mkdir build
   cd build
   ```
3. Generate Visual Studio solution:
   ```bash
   cmake .. -G "Visual Studio 17 2022" -A x64
   ```
4. Open `WebCAMO.sln` in Visual Studio
5. Build → Build Solution (or press F7)

### Using Command Line

```bash
cd windows
mkdir build
cd build

# Configure
cmake .. -G "Visual Studio 17 2022" -A x64

# Build Debug
cmake --build . --config Debug

# Build Release
cmake --build . --config Release
```

### Output Files

After building:
- `build/bin/Debug/WebCAMO.exe` - Main application
- `build/bin/Debug/WebCAMOFilter.dll` - DirectShow filter

---

## Registering the Virtual Camera

The DirectShow filter must be registered with Windows to appear as a camera device.

### Register (Run as Administrator)

```bash
regsvr32 "path\to\WebCAMOFilter.dll"
```

Or from the build directory:
```bash
regsvr32 bin\Release\WebCAMOFilter.dll
```

### Unregister

```bash
regsvr32 /u "path\to\WebCAMOFilter.dll"
```

### Verify Registration

1. Open Device Manager
2. Look under "Imaging devices" or "Cameras"
3. "WebCAMO Camera" should appear when the main app is running

---

## Building the Signaling Server

```bash
cd signaling

# Install dependencies
npm install

# Run server
npm start

# Or for development with auto-restart
npx nodemon server.js
```

The server will start on port 8080 by default.

---

## Complete Build Script

### Windows PowerShell

```powershell
# Clone and build everything
git clone https://github.com/yourusername/WebCAMO.git
cd WebCAMO

# Build signaling server
cd signaling
npm install
cd ..

# Build Windows app
cd windows
mkdir build -ErrorAction SilentlyContinue
cd build
cmake .. -G "Visual Studio 17 2022" -A x64
cmake --build . --config Release
cd ../..

# Build Android app (if gradlew is available)
cd android
.\gradlew.bat assembleDebug
cd ..

Write-Host "Build complete!"
```

---

## Troubleshooting Build Issues

### CMake can't find Visual Studio
```
Error: Could not find CMAKE_CXX_COMPILER
```
**Solution:** Install Visual Studio 2022 with "Desktop development with C++" workload.

### DirectShow headers not found
```
Error: Cannot open include file: 'streams.h'
```
**Solution:** The DirectShow base classes are part of the Windows SDK samples. You may need to:
1. Download Windows-classic-samples from GitHub
2. Copy the DirectShow base classes to `third_party/directshow`

### Gradle sync failed
```
Error: SDK location not found
```
**Solution:** Create `local.properties` in the android folder:
```properties
sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

### Node.js version mismatch
```
Error: The engine "node" is incompatible with this module
```
**Solution:** Use Node.js 18.x LTS:
```bash
nvm install 18
nvm use 18
```

---

## Development Tips

### Hot Reload (Android)
Android Studio supports hot reload for UI changes. Use **Apply Changes** (Ctrl+F10).

### Debugging Signaling
Add `DEBUG=*` environment variable to see all WebSocket messages:
```bash
set DEBUG=* && node server.js
```

### Debugging DirectShow Filter
1. Attach Visual Studio debugger to the process using the virtual camera
2. Set breakpoints in `VirtualCameraFilter.cpp`
3. Use GraphEdit to test the filter in isolation
