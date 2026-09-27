@echo off
setlocal
if "%~3"=="" (
  echo Usage: compile-windows-mirror.cmd JDK_HOME SOURCE_FILE OUTPUT_DLL 1>&2
  exit /b 2
)
set "JDK_HOME=%~1"
set "SOURCE_FILE=%~2"
set "OUTPUT_DLL=%~3"
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
for %%i in ("%OUTPUT_DLL%") do if not exist "%%~dpi" mkdir "%%~dpi"
cl.exe /nologo /std:c++17 /EHsc /MD /LD ^
  /I"%JDK_HOME%\include" /I"%JDK_HOME%\include\win32" ^
  "%SOURCE_FILE%" /link /LIBPATH:"%JDK_HOME%\lib" jawt.lib d3d11.lib dxgi.lib ^
  /OUT:"%OUTPUT_DLL%"
exit /b %errorlevel%
