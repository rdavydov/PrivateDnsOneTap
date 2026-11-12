@echo off
setlocal
cd /d "%~dp0"
if exist "gradle\wrapper\gradle-wrapper.jar" (
  echo Official Gradle wrapper jar is already present.
  exit /b 0
)
echo Downloading the official Gradle v8.13 wrapper JAR from Gradle's GitHub repository...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'"
if errorlevel 1 (
  echo Download failed. Check your internet and try again.
  exit /b 1
)
echo Wrapper JAR downloaded. Open this folder in Android Studio.
exit /b 0
