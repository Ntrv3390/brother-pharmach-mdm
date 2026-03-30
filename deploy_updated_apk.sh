#!/bin/bash
# =============================================================================
# deploy_updated_apk.sh
# Builds the Brother Pharmach MDM Android APK and deploys it.
#
# Usage:
#   ./deploy_updated_apk.sh              # Docker mode (default)
#   ./deploy_updated_apk.sh --local      # Bare-metal mode (copy to local /opt/hmdm/files)
#   SKIP_BUILD=1 ./deploy_updated_apk.sh # Skip Android build, deploy existing APK
#
# Environment variables (overridable):
#   ANDROID_DIR, SERVER_FILES_DIR, DB_USER, DB_NAME, DB_HOST, DB_PORT,
#   DB_PASSWORD, APK_NAME, PKG_NAME, APK_BASE_URL, SKIP_DB_UPDATE,
#   DOCKER_CONTAINER
# =============================================================================
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HMDM_DIR="${HMDM_DIR:-$ROOT_DIR}"
ANDROID_DIR="${ANDROID_DIR:-$HMDM_DIR/hmdm-android}"

# Load optional env files
if [ -f "$HMDM_DIR/.env" ]; then
    set -a; . <(tr -d '\r' < "$HMDM_DIR/.env"); set +a
fi
if [ -f "$HMDM_DIR/hmdm-server/.env" ]; then
    set -a; . <(tr -d '\r' < "$HMDM_DIR/hmdm-server/.env"); set +a
fi

# Defaults
APK_NAME="${APK_NAME:-brother-pharmach-mdm.apk}"
PKG_NAME="${PKG_NAME:-com.brother.pharmach.mdm.launcher}"
APK_BASE_URL="${APK_BASE_URL:-${BASE_URL:-https://brothers-mdm.com}/files}"
SKIP_DB_UPDATE="${SKIP_DB_UPDATE:-0}"
DOCKER_CONTAINER="${DOCKER_CONTAINER:-hmdm-server}"
DOCKER_DB_CONTAINER="${DOCKER_DB_CONTAINER:-hmdm-postgres}"
DB_USER="${DB_USER:-hmdm}"
DB_NAME="${DB_NAME:-hmdm}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
SERVER_FILES_DIR="${SERVER_FILES_DIR:-/opt/hmdm/files}"
APP_ARTIFACT_DIR="${APP_ARTIFACT_DIR:-$HMDM_DIR/app}"

increment_app_version() {
    local current="$1"
    local major minor

    if [[ ! "$current" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
        echo "1.0"
        return
    fi

    major="${current%%.*}"
    if [[ "$current" == "$major" ]]; then
        minor=0
    else
        minor="${current#*.}"
    fi

    minor=$((10#$minor + 1))
    if [ "$minor" -ge 100 ]; then
        major=$((major + 1))
        minor=0
    fi

    echo "${major}.${minor}"
}

# Detect mode
MODE="docker"
if [ "$1" = "--local" ]; then
    MODE="local"
fi

if [ ! -d "$ANDROID_DIR" ]; then
    echo "Error: Android project directory not found: $ANDROID_DIR"
    exit 1
fi

# ---------------------------------------------------------------------------
# Build APK
# ---------------------------------------------------------------------------
APK_PATH="$ANDROID_DIR/app/build/outputs/apk/opensource/release/app-opensource-release.apk"
AAB_PATH="$ANDROID_DIR/app/build/outputs/bundle/opensourceRelease/app-opensource-release.aab"

if [ "${SKIP_BUILD}" != "1" ]; then
    echo "Building Android APK + AAB (Release)..."
    cd "$ANDROID_DIR"
    ./gradlew bundleOpensourceRelease assembleOpensourceRelease --no-daemon
    cd "$ROOT_DIR"
else
    echo "Skipping build (SKIP_BUILD=1)"
fi

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH"
    exit 1
fi

if [ ! -f "$AAB_PATH" ]; then
    echo "Error: AAB not found at $AAB_PATH"
    exit 1
fi

mkdir -p "$APP_ARTIFACT_DIR"
cp -f "$APK_PATH" "$APP_ARTIFACT_DIR/app-opensource-release.apk"
cp -f "$AAB_PATH" "$APP_ARTIFACT_DIR/app-opensource-release.aab"
echo "Updated app artifacts in: $APP_ARTIFACT_DIR"

# ---------------------------------------------------------------------------
# Calculate hash
# ---------------------------------------------------------------------------
echo "Calculating SHA-256 hash (URL-safe Base64)..."
APK_HASH=$(python3 -c "import hashlib, base64, sys; print(base64.urlsafe_b64encode(hashlib.sha256(open(sys.argv[1], 'rb').read()).digest()).decode('utf-8'))" "$APK_PATH")
echo "Hash: $APK_HASH"

# ---------------------------------------------------------------------------
# Deploy APK to server
# ---------------------------------------------------------------------------
if [ "$MODE" = "docker" ]; then
    echo "Deploying APK to Docker container '$DOCKER_CONTAINER'..."

    # Check if container is running
    if ! docker inspect -f '{{.State.Running}}' "$DOCKER_CONTAINER" > /dev/null 2>&1; then
        echo "Error: Docker container '$DOCKER_CONTAINER' is not running."
        echo "Start with: cd hmdm-server && docker compose up -d"
        exit 1
    fi

    docker cp "$APK_PATH" "${DOCKER_CONTAINER}:/opt/hmdm/files/${APK_NAME}"
    echo "APK copied into container."

    # Update DB through the postgres container
    if [ "$SKIP_DB_UPDATE" != "1" ]; then
        echo "Updating database via Docker..."
        CURRENT_VERSION="$(docker exec "$DOCKER_DB_CONTAINER" psql \
            -U "$DB_USER" \
            -d "$DB_NAME" \
            -tA \
            -c "SELECT av.version
                FROM applicationversions av
                JOIN applications a ON a.id = av.applicationid
                WHERE a.pkg = '${PKG_NAME}'
                ORDER BY av.id DESC
                LIMIT 1;" | tr -d '[:space:]')"
        APP_VERSION="$(increment_app_version "${CURRENT_VERSION:-1.0}")"
        APK_URL="${APK_BASE_URL}/${APK_NAME}"

        docker exec "$DOCKER_DB_CONTAINER" psql \
            -U "$DB_USER" \
            -d "$DB_NAME" \
            -c "UPDATE applicationversions
                SET url = '${APK_URL}',
                    apkhash = '${APK_HASH}',
                    version = '${APP_VERSION}'
                WHERE id = (
                    SELECT av.id
                    FROM applicationversions av
                    JOIN applications a ON a.id = av.applicationid
                    WHERE a.pkg = '${PKG_NAME}'
                    ORDER BY av.id DESC
                    LIMIT 1
                );"
        echo "Database updated. New app version: ${APP_VERSION}"
    fi

else
    echo "Deploying APK to local server ($SERVER_FILES_DIR)..."
    if [ -w "$SERVER_FILES_DIR" ]; then
        cp "$APK_PATH" "$SERVER_FILES_DIR/$APK_NAME"
    else
        echo "Requesting sudo permissions to copy file..."
        sudo cp "$APK_PATH" "$SERVER_FILES_DIR/$APK_NAME"
        sudo chown tomcat:tomcat "$SERVER_FILES_DIR/$APK_NAME" 2>/dev/null || true
    fi

    if [ "$SKIP_DB_UPDATE" != "1" ]; then
        if [ -z "${DB_PASSWORD:-}" ]; then
            echo "Error: DB_PASSWORD is required for database update."
            echo "Set DB_PASSWORD in environment or in $HMDM_DIR/hmdm-server/.env"
            exit 1
        fi

        echo "Updating database..."
        CURRENT_VERSION="$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -tA -c "SELECT av.version
            FROM applicationversions av
            JOIN applications a ON a.id = av.applicationid
            WHERE a.pkg = '$PKG_NAME'
            ORDER BY av.id DESC
            LIMIT 1;" | tr -d '[:space:]')"
        APP_VERSION="$(increment_app_version "${CURRENT_VERSION:-1.0}")"
        APK_URL="$APK_BASE_URL/$APK_NAME?v=$(date +%s)"

        export PGPASSWORD="$DB_PASSWORD"
        psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "
            UPDATE applicationversions
            SET url = '$APK_URL',
                apkhash = '$APK_HASH',
                version = '$APP_VERSION'
            WHERE id = (
                SELECT av.id
                FROM applicationversions av
                JOIN applications a ON a.id = av.applicationid
                WHERE a.pkg = '$PKG_NAME'
                ORDER BY av.id DESC
                LIMIT 1
            );"
        echo "Database updated. New app version: ${APP_VERSION}"
    fi
fi

echo "Done! New APK deployed ($MODE mode)."
echo ""
echo "APK:  $APK_NAME"
echo "Hash: $APK_HASH"
echo "URL:  $APK_BASE_URL/$APK_NAME"
