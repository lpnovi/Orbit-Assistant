@echo off
setlocal
cd /d "%~dp0"

echo ============================================================
echo                  ORBIT ASSISTANT BUILDER
echo ============================================================
echo.
echo This will prepare the Android build tools and compile Orbit.
echo The first run downloads several hundred MB of official tools.
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build_orbit.ps1"
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo ============================================================
  echo BUILD COMPLETE
  echo Look for the newly created Orbit-Assistant-vX.Y.Z-debug.apk in this folder.
  echo ============================================================
) else (
  echo ============================================================
  echo BUILD FAILED - error code %EXITCODE%
  echo Take a screenshot of the error above and send it to ChatGPT.
  echo ============================================================
)
echo.
pause
exit /b %EXITCODE%
