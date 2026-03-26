@echo off
setlocal

cd /d "%~dp0"

echo Starting Brother Pharmach MDM setup web installer...
echo.

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    py setup.py
    goto :end
)

where python >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    python setup.py
    goto :end
)

echo ERROR: Python is not installed or not in PATH.
echo Install Python 3.10+ and try again.
echo.
pause

:end
endlocal
