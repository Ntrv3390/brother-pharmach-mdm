@echo off
setlocal EnableExtensions
title Final Android Rebuild (WSL)

pushd "%~dp0" >nul 2>nul
if errorlevel 1 (
  echo ERROR: Unable to open script directory: %~dp0
  goto :end
)

if not exist "final-rebuild-android-only.sh" (
  echo ERROR: Missing final-rebuild-android-only.sh next to this file.
  goto :end
)

where wsl >nul 2>nul
if errorlevel 1 (
  echo ERROR: WSL is not installed or not in PATH.
  echo Install WSL, then run again.
  goto :end
)

echo Running Android rebuild inside WSL...
echo.

set "WSL_DIR="
for /f "delims=" %%I in ('wsl wslpath -a "%CD%"') do set "WSL_DIR=%%I"

if "%WSL_DIR%"=="" (
  echo ERROR: Could not resolve WSL path for %CD%
  goto :end
)

wsl bash -lc "cd \"%WSL_DIR%\" && chmod +x ./final-rebuild-android-only.sh && ./final-rebuild-android-only.sh"
set "RC=%ERRORLEVEL%"

echo.
if not "%RC%"=="0" (
  echo Android WSL rebuild failed with exit code %RC%.
) else (
  echo Android WSL rebuild completed successfully.
)

:end
popd >nul 2>nul
echo.
echo Press any key to exit...
pause >nul
endlocal
