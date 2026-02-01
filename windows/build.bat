@echo off
echo ========================================
echo  WebCAMO - Build Script for Windows
echo ========================================
echo.

:: Check for Visual Studio
where cl >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Visual Studio C++ compiler not found.
    echo Please run this from "Developer Command Prompt for VS 2022"
    echo.
    pause
    exit /b 1
)

:: Check for CMake
where cmake >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] CMake not found. Please install CMake 3.20+
    pause
    exit /b 1
)

echo [1/4] Creating build directory...
if not exist build mkdir build
cd build

echo [2/4] Configuring with CMake...
cmake .. -G "Visual Studio 17 2022" -A x64
if %ERRORLEVEL% neq 0 (
    echo [ERROR] CMake configuration failed
    pause
    exit /b 1
)

echo [3/4] Building Release...
cmake --build . --config Release
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Build failed
    pause
    exit /b 1
)

echo [4/4] Build complete!
echo.
echo Output files:
echo   - bin\Release\WebCAMO.exe
echo   - bin\Release\WebCAMOFilter.dll
echo.
echo To register virtual camera (run as Admin):
echo   regsvr32 bin\Release\WebCAMOFilter.dll
echo.
pause
