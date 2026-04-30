@echo off
setlocal

cd /d "%~dp0"

echo Starting Brother Pharmamach MDM setup web installer...
echo.

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    py setup.py
    goto pause_here
)

where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    python setup.py
    goto pause_here
)

echo ERROR: Python is not installed or not in PATH.
echo Install Python 3.10+ and try again.

:pause_here
echo.
echo Press any key to exit...
pause >nul

endlocal