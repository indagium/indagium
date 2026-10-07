@echo off
setlocal
if "%~2"=="" (
  echo Usage: compile-windows-command-supervisor.cmd SOURCE_FILE OUTPUT_EXE 1>&2
  exit /b 2
)
set "SOURCE_FILE=%~1"
set "OUTPUT_EXE=%~2"
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
for %%i in ("%OUTPUT_EXE%") do if not exist "%%~dpi" mkdir "%%~dpi"
cl.exe /nologo /std:c++17 /EHsc /MT "%SOURCE_FILE%" /link /OUT:"%OUTPUT_EXE%"
exit /b %errorlevel%
