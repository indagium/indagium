@echo off
setlocal
if "%~4"=="" (
  echo Usage: configure-windows-whisper.cmd JDK_HOME SOURCE_DIR BUILD_DIR OUTPUT_DIR 1>&2
  exit /b 2
)

set "JDK_HOME=%~1"
set "SOURCE_DIR=%~2"
set "BUILD_DIR=%~3"
set "OUTPUT_DIR=%~4"
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
  echo Visual Studio vswhere.exe was not found 1>&2
  exit /b 3
)

for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS_ROOT=%%i"
if not defined VS_ROOT (
  echo Visual Studio C++ build tools were not found 1>&2
  exit /b 4
)

call "%VS_ROOT%\Common7\Tools\VsDevCmd.bat" -no_logo -arch=amd64 -host_arch=amd64
if errorlevel 1 exit /b %errorlevel%
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

cmake -S "%SOURCE_DIR%" -B "%BUILD_DIR%" -G "Visual Studio 17 2022" -A x64 ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=%OUTPUT_DIR%" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=%OUTPUT_DIR%" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY_RELEASE=%OUTPUT_DIR%" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY_RELEASE=%OUTPUT_DIR%" ^
  "-DJAVA_HOME=%JDK_HOME%"
exit /b %errorlevel%
