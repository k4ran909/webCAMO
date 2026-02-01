@echo off
echo ========================================
echo  WebCAMO - Start All Services
echo ========================================
echo.

:: Start signaling server
echo [1/2] Starting Signaling Server...
start "WebCAMO Signaling" cmd /k "cd /d %~dp0signaling && node server.js"

:: Wait a moment for server to start
timeout /t 2 /nobreak >nul

:: Start Windows desktop app
echo [2/2] Starting WebCAMO Desktop...
if exist "%~dp0windows\build\bin\Release\WebCAMO.exe" (
    start "" "%~dp0windows\build\bin\Release\WebCAMO.exe"
) else if exist "%~dp0windows\build\bin\Debug\WebCAMO.exe" (
    start "" "%~dp0windows\build\bin\Debug\WebCAMO.exe"
) else (
    echo [WARNING] WebCAMO.exe not found. Please build the Windows app first.
)

echo.
echo WebCAMO services started!
echo.
echo Next steps:
echo 1. Install the Android app on your phone
echo 2. Enter the signaling server URL (ws://YOUR_IP:8080)
echo 3. Tap "Start Streaming"
echo 4. Right-click WebCAMO tray icon ^> Connect
echo.
pause
