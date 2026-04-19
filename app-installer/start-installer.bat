@echo off
setlocal
set SCRIPT_DIR=%~dp0
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%bootstrap-and-run.ps1"
if errorlevel 1 (
  echo.
  echo Installer failed. Press any key to exit.
  pause >nul
)
