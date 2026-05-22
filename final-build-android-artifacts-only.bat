@echo off
setlocal EnableExtensions

cd /d "%~dp0"

echo Building Android enterprise APK/AAB and refreshing local artifacts only...
echo This will NOT update database and will NOT touch hmdm-postgres.
echo.

if not exist "deploy_updated_apk.sh" (
    echo ERROR: Missing deploy_updated_apk.sh in %CD%
    goto :fail
)

where bash >nul 2>nul
if errorlevel 1 (
    echo ERROR: 'bash' is not available in PATH.
    echo Install Git Bash or run this from WSL, then try again.
    goto :fail
)

set "SKIP_DB_UPDATE=1"
set "SKIP_SERVER_DEPLOY=1"
bash ./deploy_updated_apk.sh
if errorlevel 1 (
    echo ERROR: Android artifacts build failed.
    goto :fail
)

echo.
echo Success: Android enterprise APK/AAB built.
echo Refreshed:
echo   - app/app-enterprise-release.apk
echo   - app/app-enterprise-release.aab
echo   - app/app-enterprise-release-<versionCode>.apk
echo   - app-installer/app-enterprise-release.apk
echo.
goto :end

:fail
echo.
echo Operation did not complete.
echo.

:end
echo Press any key to exit...
pause >nul
endlocal
