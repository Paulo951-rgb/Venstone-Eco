@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle is not installed or not in PATH. Install Gradle 9.x, then retry.
  pause
  exit /b 1
)
gradle clean build
if errorlevel 1 (
  echo Build failed. Copy the full error output and send it for diagnosis.
  pause
  exit /b 1
)
echo.
echo Build successful. JAR: build\libs\venstone-economy-assistant-1.2.0.jar
pause
