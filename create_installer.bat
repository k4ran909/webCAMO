@echo off
echo ========================================
echo  WebCAMO Installer Builder
echo ========================================
echo.
echo This will create an installer package for WebCAMO.
echo.

:: Check if built
if not exist "windows\build\bin\Release\WebCAMO.exe" (
    echo [ERROR] Build not found. Please run windows\build.bat first.
    pause
    exit /b 1
)

:: Create installer directory
echo Creating installer package...
if exist "installer" rmdir /s /q installer
mkdir installer
mkdir installer\bin

:: Copy files
echo Copying files...
copy "windows\build\bin\Release\WebCAMO.exe" "installer\bin\" >nul
copy "windows\build\bin\Release\WebCAMOFilter.dll" "installer\bin\" >nul
copy "README.md" "installer\" >nul

:: Create install script
echo Creating install scripts...
echo @echo off > installer\install.bat
echo echo Installing WebCAMO... >> installer\install.bat
echo. >> installer\install.bat
echo :: Check admin >> installer\install.bat
echo net session ^>nul 2^>^&1 >> installer\install.bat
echo if %%ERRORLEVEL%% neq 0 ( >> installer\install.bat
echo     echo Please run as Administrator >> installer\install.bat
echo     pause >> installer\install.bat
echo     exit /b 1 >> installer\install.bat
echo ) >> installer\install.bat
echo. >> installer\install.bat
echo :: Copy to Program Files >> installer\install.bat
echo set INSTALL_DIR=%%ProgramFiles%%\WebCAMO >> installer\install.bat
echo mkdir "%%INSTALL_DIR%%" 2^>nul >> installer\install.bat
echo copy bin\*.* "%%INSTALL_DIR%%" ^>nul >> installer\install.bat
echo. >> installer\install.bat
echo :: Register virtual camera >> installer\install.bat
echo regsvr32 "%%INSTALL_DIR%%\WebCAMOFilter.dll" >> installer\install.bat
echo. >> installer\install.bat
echo :: Create Start Menu shortcut >> installer\install.bat
echo echo Installation complete! >> installer\install.bat
echo echo Run WebCAMO from Program Files or Start Menu >> installer\install.bat
echo pause >> installer\install.bat

:: Create uninstall script
echo @echo off > installer\uninstall.bat
echo echo Uninstalling WebCAMO... >> installer\uninstall.bat
echo net session ^>nul 2^>^&1 >> installer\uninstall.bat
echo if %%ERRORLEVEL%% neq 0 ( >> installer\uninstall.bat
echo     echo Please run as Administrator >> installer\uninstall.bat
echo     pause >> installer\uninstall.bat
echo     exit /b 1 >> installer\uninstall.bat
echo ) >> installer\uninstall.bat
echo set INSTALL_DIR=%%ProgramFiles%%\WebCAMO >> installer\uninstall.bat
echo regsvr32 /u "%%INSTALL_DIR%%\WebCAMOFilter.dll" >> installer\uninstall.bat
echo rmdir /s /q "%%INSTALL_DIR%%" >> installer\uninstall.bat
echo echo Uninstall complete! >> installer\uninstall.bat
echo pause >> installer\uninstall.bat

echo.
echo [SUCCESS] Installer package created in 'installer' folder!
echo.
echo Contents:
echo   - bin\WebCAMO.exe
echo   - bin\WebCAMOFilter.dll
echo   - install.bat
echo   - uninstall.bat
echo   - README.md
echo.
pause
