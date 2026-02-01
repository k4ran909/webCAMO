@echo off
:: WebCAMO DirectShow Filter Build Script
:: Requires Visual Studio 2022 with C++ Desktop Development

echo =================================
echo  Building WebCAMO Virtual Camera
echo =================================
echo.

:: Find VS2022
set "VCVARS="
if exist "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" (
    set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
)
if exist "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat" (
    set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat"
)
if exist "C:\Program Files\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvars64.bat" (
    set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvars64.bat"
)

if "%VCVARS%"=="" (
    echo ERROR: Visual Studio 2022 not found!
    echo Please install Visual Studio 2022 with C++ Desktop Development
    pause
    exit /b 1
)

echo Found Visual Studio at:
echo %VCVARS%
echo.

:: Initialize VS environment
call "%VCVARS%"

:: Navigate to filter source
cd /d "%~dp0src\filter"

:: Compile
echo Compiling WebCAMOFilter.dll...
cl /nologo /LD /EHsc /O2 /W3 /MD ^
   /D "WIN32" /D "NDEBUG" /D "_WINDOWS" /D "_USRDLL" /D "UNICODE" /D "_UNICODE" ^
   WebCAMOFilter.cpp ^
   /link /NOLOGO /DLL /DEF:WebCAMOFilter.def ^
   strmiids.lib ole32.lib oleaut32.lib uuid.lib advapi32.lib ^
   /OUT:..\..\bin\WebCAMOFilter.dll

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Compilation failed!
    pause
    exit /b 1
)

:: Create bin directory if needed
if not exist "..\..\bin" mkdir "..\..\bin"

echo.
echo ========================================
echo  Build successful!
echo ========================================
echo.
echo DLL Location: %~dp0bin\WebCAMOFilter.dll
echo.
echo To register (run as Administrator):
echo   regsvr32 "%~dp0bin\WebCAMOFilter.dll"
echo.
echo To unregister:
echo   regsvr32 /u "%~dp0bin\WebCAMOFilter.dll"
echo.
pause
