@echo off
rem Minimal Maven wrapper for Windows (needs PowerShell and tar, both ship with Windows 10+).
setlocal
set "BASE=%~dp0"
for /f "tokens=1,* delims==" %%a in ('findstr /b "distributionUrl=" "%BASE%.mvn\wrapper\maven-wrapper.properties"') do set "URL=%%b"
for %%f in ("%URL%") do set "FILE=%%~nxf"
set "NAME=%FILE:-bin.tar.gz=%"
set "DIST=%USERPROFILE%\.m2\wrapper\dists\%NAME%"
if not exist "%DIST%\bin\mvn.cmd" (
  echo Downloading Maven %NAME% ...
  mkdir "%DIST%" 2>nul
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $t=Join-Path $env:TEMP 'maven.tgz'; Invoke-WebRequest '%URL%' -OutFile $t; tar -xzf $t -C '%DIST%' --strip-components=1"
  if errorlevel 1 exit /b 1
)
"%DIST%\bin\mvn.cmd" %*
