@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "SERVER_DIR=%CD%\hmdm-server"
set "COMPOSE_FILE=%SERVER_DIR%\docker-compose.yml"
set "DEFAULT_ENV_FILE=%SERVER_DIR%\.env"
set "STAGE_ENV_FILE=%SERVER_DIR%\.env.stage"
set "PROD_ENV_FILE=%SERVER_DIR%\.env.prod"

if not exist "%COMPOSE_FILE%" (
    echo ERROR: Missing compose file: %COMPOSE_FILE%
    goto :fail
)

where docker >nul 2>nul
if errorlevel 1 (
    echo ERROR: Docker is not installed or not in PATH.
    goto :fail
)

docker compose version >nul 2>nul
if errorlevel 1 (
    echo ERROR: docker compose is not available.
    goto :fail
)

set "BUILD_TYPE="
if exist "%DEFAULT_ENV_FILE%" (
    for /f "usebackq tokens=1,* delims==" %%A in (`findstr /B /I "BUILD_TYPE=" "%DEFAULT_ENV_FILE%"`) do (
        set "BUILD_TYPE=%%B"
    )
)

for /f "tokens=* delims= " %%A in ("!BUILD_TYPE!") do set "BUILD_TYPE=%%A"
for %%A in ("!BUILD_TYPE!") do set "BUILD_TYPE=%%~A"

if /I "!BUILD_TYPE!"=="stage" (
    set "SELECTED_ENV_FILE=%STAGE_ENV_FILE%"
    goto :have_env
)

if /I "!BUILD_TYPE!"=="production" (
    set "SELECTED_ENV_FILE=%PROD_ENV_FILE%"
    goto :have_env
)

if /I "!BUILD_TYPE!"=="prod" (
    set "SELECTED_ENV_FILE=%PROD_ENV_FILE%"
    goto :have_env
)

echo BUILD_TYPE is not set to stage/production in %DEFAULT_ENV_FILE%.
echo Choose target deployment environment:
echo   [S] Stage
echo   [P] Production
choice /C SP /N /M "Select environment (S/P): "
if errorlevel 2 (
    set "SELECTED_ENV_FILE=%PROD_ENV_FILE%"
    set "BUILD_TYPE=production"
) else (
    set "SELECTED_ENV_FILE=%STAGE_ENV_FILE%"
    set "BUILD_TYPE=stage"
)

:have_env
if not exist "%SELECTED_ENV_FILE%" (
    echo ERROR: Selected env file not found: %SELECTED_ENV_FILE%
    goto :fail
)

set "ACTIVE_BASE_URL="
set "ACTIVE_CLOUDFLARE_TOKEN="
set "DEFAULT_STAGE_TOKEN="
set "DEFAULT_PROD_TOKEN="
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /B /I "BASE_URL=" "%SELECTED_ENV_FILE%"`) do (
    if /I "%%A"=="BASE_URL" set "ACTIVE_BASE_URL=%%B"
)
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /B /I "CLOUDFLARE_TUNNEL_TOKEN=" "%SELECTED_ENV_FILE%"`) do (
    if /I "%%A"=="CLOUDFLARE_TUNNEL_TOKEN" set "ACTIVE_CLOUDFLARE_TOKEN=%%B"
)

if exist "%DEFAULT_ENV_FILE%" (
    for /f "usebackq tokens=1,* delims==" %%A in (`findstr /B /I "CLOUDFLARE_STAGE_TUNNEL_TOKEN=" "%DEFAULT_ENV_FILE%"`) do (
        if /I "%%A"=="CLOUDFLARE_STAGE_TUNNEL_TOKEN" set "DEFAULT_STAGE_TOKEN=%%B"
    )
    for /f "usebackq tokens=1,* delims==" %%A in (`findstr /B /I "CLOUDFLARE_TUNNEL_TOKEN=" "%DEFAULT_ENV_FILE%"`) do (
        if /I "%%A"=="CLOUDFLARE_TUNNEL_TOKEN" set "DEFAULT_PROD_TOKEN=%%B"
    )
)

if /I "!BUILD_TYPE!"=="stage" (
    if not "!DEFAULT_STAGE_TOKEN!"=="" set "ACTIVE_CLOUDFLARE_TOKEN=!DEFAULT_STAGE_TOKEN!"
)
if /I "!BUILD_TYPE!"=="production" (
    if not "!DEFAULT_PROD_TOKEN!"=="" set "ACTIVE_CLOUDFLARE_TOKEN=!DEFAULT_PROD_TOKEN!"
)
if /I "!BUILD_TYPE!"=="prod" (
    if not "!DEFAULT_PROD_TOKEN!"=="" set "ACTIVE_CLOUDFLARE_TOKEN=!DEFAULT_PROD_TOKEN!"
)

echo.
echo Environment resolved: !BUILD_TYPE!
echo Using env file: %SELECTED_ENV_FILE%
echo BASE_URL: !ACTIVE_BASE_URL!
if "!ACTIVE_CLOUDFLARE_TOKEN!"=="" (
    echo WARNING: CLOUDFLARE_TUNNEL_TOKEN is empty in selected env file.
)
echo.
echo Rebuilding hmdm-server and starting hmdm-cloudflared...

set "CLOUDFLARE_TUNNEL_TOKEN=!ACTIVE_CLOUDFLARE_TOKEN!"

docker compose --env-file "%SELECTED_ENV_FILE%" -f "%COMPOSE_FILE%" --profile tunnel up -d --build --no-deps hmdm cloudflared
if errorlevel 1 (
    echo ERROR: Failed to rebuild hmdm-server and start hmdm-cloudflared.
    goto :fail
)

echo.
echo Success: hmdm-server rebuilt and hmdm-cloudflared started using %SELECTED_ENV_FILE%.
echo Postgres and other unrelated services were not rebuilt.
echo.
goto :end

:fail
echo.
echo Deployment did not complete.
echo.

:end
echo Press any key to exit...
pause >nul
endlocal
