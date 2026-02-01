@echo off
echo ========================================
echo  WebCAMO Virtual Camera Registration
echo ========================================
echo.

:: Check for admin rights
net session >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] This script requires Administrator privileges.
    echo Right-click and select "Run as administrator"
    pause
    exit /b 1
)

set FILTER_DLL=%~dp0build\bin\Release\WebCAMOFilter.dll

if not exist "%FILTER_DLL%" (
    echo [ERROR] WebCAMOFilter.dll not found at:
    echo %FILTER_DLL%
    echo.
    echo Please build the project first using build.bat
    pause
    exit /b 1
)

echo Registering WebCAMO Virtual Camera...
regsvr32 "%FILTER_DLL%"

if %ERRORLEVEL% equ 0 (
    echo.
    echo [SUCCESS] Virtual camera registered!
    echo.
    echo The "WebCAMO Camera" should now appear in video applications.
    echo You may need to restart Zoom/Teams/etc to see it.
) else (
    echo.
    echo [ERROR] Registration failed. Error code: %ERRORLEVEL%
)

pause
