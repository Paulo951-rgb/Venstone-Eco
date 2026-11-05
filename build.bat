@echo off
setlocal
where java >nul 2>nul
if errorlevel 1 (
  echo Java 21 is not installed or not in PATH. Install JDK 21, then retry.
  pause
  exit /b 1
)
call gradlew.bat clean build
if errorlevel 1 (
  echo Build failed. Copy the full error output and send it for diagnosis.
  pause
  exit /b 1
)
echo.
echo Build successful. JAR: build\libs\venstone-economy-assistant-1.3.0.jar
pause
