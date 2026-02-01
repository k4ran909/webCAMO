@echo off
echo ========================================
echo  WebCAMO Python Receiver - Quick Start
echo ========================================
echo.

:: Check Python
python --version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Python not found. Please install Python 3.8+
    echo Download: https://www.python.org/downloads/
    pause
    exit /b 1
)

:: Install dependencies
echo Installing dependencies...
pip install opencv-python pyvirtualcam numpy websockets --quiet

echo.
echo Starting WebCAMO TCP Receiver...
echo.
python tcp_receiver.py %*

pause
