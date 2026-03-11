#!/bin/bash
set -e

# Portable configuration (override via environment variables)
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HMDM_DIR="${HMDM_DIR:-$ROOT_DIR}"
ANDROID_DIR="${ANDROID_DIR:-$HMDM_DIR/hmdm-android}"

# Load optional env files for DB/deploy values (useful when script is run with sudo)
if [ -f "$HMDM_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$HMDM_DIR/.env"
    set +a
fi
if [ -f "$HMDM_DIR/hmdm-server/.env" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$HMDM_DIR/hmdm-server/.env"
    set +a
fi

SERVER_FILES_DIR="${SERVER_FILES_DIR:-/opt/hmdm/files}"
DB_USER="${DB_USER:-hmdm}"
DB_NAME="${DB_NAME:-hmdm}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
APK_NAME="${APK_NAME:-brother-pharmach-mdm.apk}"
PKG_NAME="${PKG_NAME:-com.brother.pharmach.mdm.launcher}"
APK_BASE_URL="${APK_BASE_URL:-https://brothers-mdm.com/files}"
SKIP_DB_UPDATE="${SKIP_DB_UPDATE:-0}"

if [ ! -d "$ANDROID_DIR" ]; then
    echo "Error: Android project directory not found: $ANDROID_DIR"
    exit 1
fi

echo "Building Android APK (Release)..."
cd "$ANDROID_DIR"
./gradlew assembleRelease

APK_PATH="$ANDROID_DIR/app/build/outputs/apk/opensource/release/app-opensource-release.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH"
    exit 1
fi

echo "Calculating SHA-256 hash (URL-safe Base64)..."
APK_HASH=$(python3 -c "import hashlib, base64, sys; print(base64.urlsafe_b64encode(hashlib.sha256(open(sys.argv[1], 'rb').read()).digest()).decode('utf-8'))" "$APK_PATH")
echo "Hash: $APK_HASH"

echo "Deploying APK to server..."
# Check if we have write access to server files directory
if [ -w "$SERVER_FILES_DIR" ]; then
    cp "$APK_PATH" "$SERVER_FILES_DIR/$APK_NAME"
else
    echo "Requesting sudo permissions to copy file..."
    sudo cp "$APK_PATH" "$SERVER_FILES_DIR/$APK_NAME"
    sudo chown tomcat:tomcat "$SERVER_FILES_DIR/$APK_NAME"
fi

if [ "$SKIP_DB_UPDATE" = "1" ]; then
    echo "Skipping database update (SKIP_DB_UPDATE=1)."
else
    if [ -z "${DB_PASSWORD:-}" ]; then
        echo "Error: DB_PASSWORD is required for database update."
        echo "Set DB_PASSWORD in environment or in $HMDM_DIR/hmdm-server/.env"
        echo "Or run with SKIP_DB_UPDATE=1 to skip DB metadata update"
        exit 1
    fi

    if ! command -v psql >/dev/null 2>&1; then
        echo "Error: psql is not installed but DB update is enabled."
        exit 1
    fi

    echo "Updating database..."
    APP_VERSION="6.31-release-$(date +%s)"
    APK_URL="$APK_BASE_URL/$APK_NAME?v=$(date +%s)"

    export PGPASSWORD="$DB_PASSWORD"
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "
UPDATE applicationversions
SET url = '$APK_URL',
    apkhash = '$APK_HASH',
    version = '$APP_VERSION'
WHERE applicationid = (SELECT id FROM applications WHERE pkg = '$PKG_NAME' LIMIT 1);
"
    echo "Database updated."
fi

echo "Done! New APK deployed."
