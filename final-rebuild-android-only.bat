@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "ROOT_DIR=%CD%"
set "ANDROID_DIR=%ROOT_DIR%\hmdm-android"
set "VERSION_FILE=%ANDROID_DIR%\version.properties"
set "ENV_FILE=%ROOT_DIR%\.env.android"
set "APP_ARTIFACT_DIR=%ROOT_DIR%\app"
set "APP_INSTALLER_DIR=%ROOT_DIR%\app-installer"

set "BUILD_TYPE=production"
set "ANDROID_STAGE_BASE_URL=https://brother-pharmach-mdm-stage.space"
set "ANDROID_PROD_BASE_URL=https://brothers-mdm.com"
set "ANDROID_BASE_URL="
set "ANDROID_SECONDARY_BASE_URL="

echo Building Android enterprise APK/AAB and refreshing local artifacts only...
echo This will NOT update database and will NOT touch hmdm-postgres.
echo.

if not exist "%ANDROID_DIR%\gradlew.bat" (
    echo ERROR: Missing %ANDROID_DIR%\gradlew.bat
    goto :fail
)

if not exist "%VERSION_FILE%" (
    echo ERROR: Missing %VERSION_FILE%
    goto :fail
)

call :loadEnvIfPresent
call :resolveBuildUrls
call :ensureAndroidSdk
if errorlevel 1 goto :fail

echo Android BUILD_TYPE: !BUILD_TYPE!
echo Android BASE_URL: !ANDROID_BASE_URL!
echo Android SECONDARY_BASE_URL: !ANDROID_SECONDARY_BASE_URL!
echo.

call :bumpAndroidVersion
if errorlevel 1 goto :fail

pushd "%ANDROID_DIR%"
call gradlew.bat -PmdmBaseUrl="!ANDROID_BASE_URL!" -PmdmSecondaryBaseUrl="!ANDROID_SECONDARY_BASE_URL!" bundleEnterpriseRelease assembleEnterpriseRelease --no-daemon
if errorlevel 1 (
    popd
    echo ERROR: Android build failed.
    goto :fail
)
popd

set "APK_PATH=%ANDROID_DIR%\app\build\outputs\apk\enterprise\release\app-enterprise-release.apk"
set "AAB_PATH=%ANDROID_DIR%\app\build\outputs\bundle\enterpriseRelease\app-enterprise-release.aab"

if not exist "%APK_PATH%" (
    echo ERROR: APK not found at %APK_PATH%
    goto :fail
)

if not exist "%AAB_PATH%" (
    echo ERROR: AAB not found at %AAB_PATH%
    goto :fail
)

for /f "tokens=2 delims==" %%A in ('findstr /B "VERSION_CODE=" "%VERSION_FILE%"') do set "VERSION_CODE=%%A"
for /f "tokens=* delims= " %%A in ("!VERSION_CODE!") do set "VERSION_CODE=%%A"
set "APK_NAME=app-enterprise-release-!VERSION_CODE!.apk"

if not exist "%APP_ARTIFACT_DIR%" mkdir "%APP_ARTIFACT_DIR%"
if not exist "%APP_INSTALLER_DIR%" mkdir "%APP_INSTALLER_DIR%"

copy /Y "%APK_PATH%" "%APP_ARTIFACT_DIR%\app-enterprise-release.apk" >nul
copy /Y "%AAB_PATH%" "%APP_ARTIFACT_DIR%\app-enterprise-release.aab" >nul
copy /Y "%APK_PATH%" "%APP_ARTIFACT_DIR%\!APK_NAME!" >nul
copy /Y "%APK_PATH%" "%APP_INSTALLER_DIR%\app-enterprise-release.apk" >nul

echo.
echo Success: Android enterprise APK/AAB built.
echo Refreshed:
echo   - app/app-enterprise-release.apk
echo   - app/app-enterprise-release.aab
echo   - app/!APK_NAME!
echo   - app-installer/app-enterprise-release.apk
echo.
goto :end

:loadEnvIfPresent
if not exist "%ENV_FILE%" goto :eof
call :readEnvKey BUILD_TYPE BUILD_TYPE
call :readEnvKey ANDROID_STAGE_BASE_URL ANDROID_STAGE_BASE_URL
call :readEnvKey ANDROID_PROD_BASE_URL ANDROID_PROD_BASE_URL
call :readEnvKey ANDROID_BASE_URL ANDROID_BASE_URL
call :readEnvKey ANDROID_SECONDARY_BASE_URL ANDROID_SECONDARY_BASE_URL
goto :eof

:readEnvKey
set "_key=%~1"
set "_out=%~2"
set "_val="
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /B /I "%_key%=" "%ENV_FILE%"`) do set "_val=%%B"
if defined _val (
    for /f "tokens=* delims= " %%A in ("!_val!") do set "_val=%%A"
    for %%A in ("!_val!") do set "_val=%%~A"
    set "%_out%=!_val!"
)
goto :eof

:resolveBuildUrls
for /f "tokens=* delims= " %%A in ("!BUILD_TYPE!") do set "BUILD_TYPE=%%A"
for %%A in ("!BUILD_TYPE!") do set "BUILD_TYPE=%%~A"

if /I "!BUILD_TYPE!"=="prod" set "BUILD_TYPE=production"
if /I "!BUILD_TYPE!"=="stage" (
    if "!ANDROID_BASE_URL!"=="" set "ANDROID_BASE_URL=!ANDROID_STAGE_BASE_URL!"
)
if /I "!BUILD_TYPE!"=="production" (
    if "!ANDROID_BASE_URL!"=="" set "ANDROID_BASE_URL=!ANDROID_PROD_BASE_URL!"
)

if not /I "!BUILD_TYPE!"=="stage" if not /I "!BUILD_TYPE!"=="production" (
    echo ERROR: Invalid BUILD_TYPE in %ENV_FILE%. Use stage or production.
    exit /b 1
)

if "!ANDROID_SECONDARY_BASE_URL!"=="" set "ANDROID_SECONDARY_BASE_URL=!ANDROID_BASE_URL!"
exit /b 0

:ensureAndroidSdk
set "SDK_DIR="

if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools" set "SDK_DIR=%ANDROID_HOME%"
if not defined SDK_DIR if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools" set "SDK_DIR=%ANDROID_SDK_ROOT%"
if not defined SDK_DIR if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools" set "SDK_DIR=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK_DIR if exist "%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools" set "SDK_DIR=%USERPROFILE%\AppData\Local\Android\Sdk"
if not defined SDK_DIR if exist "%ANDROID_DIR%\local.properties" (
    for /f "tokens=1,* delims==" %%A in ('findstr /B /I "sdk.dir=" "%ANDROID_DIR%\local.properties"') do (
        set "RAW_SDK=%%B"
    )
    if defined RAW_SDK (
        set "RAW_SDK=!RAW_SDK:\\=\!"
        if exist "!RAW_SDK!\platform-tools" set "SDK_DIR=!RAW_SDK!"
    )
)

if not defined SDK_DIR (
    echo ERROR: Android SDK not found.
    echo Install Android SDK and set ANDROID_HOME, or set sdk.dir in hmdm-android\local.properties
    exit /b 1
)

set "ANDROID_HOME=%SDK_DIR%"
set "ANDROID_SDK_ROOT=%SDK_DIR%"
set "SDK_ESC=%SDK_DIR:\=\\%"
(
    echo # Auto-generated by final-rebuild-android-only.bat
    echo sdk.dir=!SDK_ESC!
) > "%ANDROID_DIR%\local.properties"

echo Using Android SDK: %SDK_DIR%
exit /b 0

:bumpAndroidVersion
set "CUR_CODE="
set "CUR_NAME="
for /f "tokens=2 delims==" %%A in ('findstr /B "VERSION_CODE=" "%VERSION_FILE%"') do set "CUR_CODE=%%A"
for /f "tokens=2 delims==" %%A in ('findstr /B "VERSION_NAME=" "%VERSION_FILE%"') do set "CUR_NAME=%%A"

for /f "tokens=* delims= " %%A in ("!CUR_CODE!") do set "CUR_CODE=%%A"
for /f "tokens=* delims= " %%A in ("!CUR_NAME!") do set "CUR_NAME=%%A"

if "!CUR_CODE!"=="" (
    echo ERROR: VERSION_CODE missing in %VERSION_FILE%
    exit /b 1
)
if "!CUR_NAME!"=="" (
    echo ERROR: VERSION_NAME missing in %VERSION_FILE%
    exit /b 1
)

set /a NEW_CODE=!CUR_CODE! + 1
for /f "tokens=1,2 delims=." %%A in ("!CUR_NAME!") do (
    set "MAJOR=%%A"
    set "MINOR=%%B"
)
if "!MINOR!"=="" set "MINOR=0"
set /a NEW_MINOR=!MINOR! + 1
set "NEW_MAJOR=!MAJOR!"
if !NEW_MINOR! GEQ 100 (
    set /a NEW_MAJOR=!MAJOR! + 1
    set "NEW_MINOR=0"
)
set "NEW_NAME=!NEW_MAJOR!.!NEW_MINOR!"

(
    echo # Auto-managed by final-rebuild-android-only.bat -- do not edit manually
    echo # VERSION_CODE must increase monotonically for each Play Console upload
    echo VERSION_CODE=!NEW_CODE!
    echo VERSION_NAME=!NEW_NAME!
) > "%VERSION_FILE%"

echo Android version bumped: code=!NEW_CODE!, name=!NEW_NAME!
exit /b 0

:fail
echo.
echo Operation did not complete.
echo.

:end
echo Press any key to exit...
pause >nul
endlocal
