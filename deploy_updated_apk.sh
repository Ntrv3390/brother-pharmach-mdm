#!/bin/bash
# =============================================================================
# deploy_updated_apk.sh
# Builds the Brother Pharmamach MDM Android Enterprise APK/AAB and deploys it.
#
# Usage:
#   ./deploy_updated_apk.sh              # Docker mode (default)
#   ./deploy_updated_apk.sh --local      # Bare-metal mode (copy to local /opt/hmdm/files)
#   SKIP_BUILD=1 ./deploy_updated_apk.sh # Skip Android build, deploy existing APK
#
# Environment variables (overridable):
#   ANDROID_DIR, SERVER_FILES_DIR, DB_USER, DB_NAME, DB_HOST, DB_PORT,
#   DB_PASSWORD, APK_NAME, PKG_NAME, APK_BASE_URL, SKIP_DB_UPDATE,
#   DOCKER_CONTAINER, APP_INSTALLER_DIR, SKIP_SERVER_DEPLOY
# =============================================================================
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HMDM_DIR="${HMDM_DIR:-$ROOT_DIR}"
ANDROID_DIR="${ANDROID_DIR:-$HMDM_DIR/hmdm-android}"
ANDROID_ENV_FILE="${ANDROID_ENV_FILE:-$HMDM_DIR/.env.android}"

# Load optional env files
if [ -f "$ANDROID_ENV_FILE" ]; then
    set -a; . <(tr -d '\r' < "$ANDROID_ENV_FILE"); set +a
fi
if [ -f "$HMDM_DIR/.env" ]; then
    set -a; . <(tr -d '\r' < "$HMDM_DIR/.env"); set +a
fi
if [ -f "$HMDM_DIR/hmdm-server/.env" ]; then
    set -a; . <(tr -d '\r' < "$HMDM_DIR/hmdm-server/.env"); set +a
fi

# Defaults
PKG_NAME="${PKG_NAME:-com.brother.pharmach.mdm.launcher}"
ANDROID_BUILD_TYPE_RAW="${ANDROID_BUILD_TYPE:-${BUILD_TYPE:-production}}"
ANDROID_BUILD_TYPE="$(printf '%s' "$ANDROID_BUILD_TYPE_RAW" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
if [ "$ANDROID_BUILD_TYPE" = "prod" ]; then
    ANDROID_BUILD_TYPE="production"
fi

ANDROID_STAGE_BASE_URL="${ANDROID_STAGE_BASE_URL:-https://brother-pharmach-mdm-stage.space}"
ANDROID_PROD_BASE_URL="${ANDROID_PROD_BASE_URL:-https://brothers-mdm.com}"

case "$ANDROID_BUILD_TYPE" in
    stage)
        ANDROID_BASE_URL="${ANDROID_BASE_URL:-$ANDROID_STAGE_BASE_URL}"
        ;;
    production)
        ANDROID_BASE_URL="${ANDROID_BASE_URL:-$ANDROID_PROD_BASE_URL}"
        ;;
    *)
        echo "Error: Invalid BUILD_TYPE/ANDROID_BUILD_TYPE '$ANDROID_BUILD_TYPE_RAW'. Use stage or production."
        exit 1
        ;;
esac

ANDROID_SECONDARY_BASE_URL="${ANDROID_SECONDARY_BASE_URL:-$ANDROID_BASE_URL}"
APK_BASE_URL="${APK_BASE_URL:-${ANDROID_BASE_URL}/files}"
SKIP_DB_UPDATE="${SKIP_DB_UPDATE:-0}"
SKIP_SERVER_DEPLOY="${SKIP_SERVER_DEPLOY:-0}"
DOCKER_CONTAINER="${DOCKER_CONTAINER:-hmdm-server}"
DOCKER_DB_CONTAINER="${DOCKER_DB_CONTAINER:-hmdm-postgres}"
DB_USER="${DB_USER:-hmdm}"
DB_NAME="${DB_NAME:-hmdm}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
SERVER_FILES_DIR="${SERVER_FILES_DIR:-/opt/hmdm/files}"
APP_ARTIFACT_DIR="${APP_ARTIFACT_DIR:-$HMDM_DIR/app}"
APP_INSTALLER_DIR="${APP_INSTALLER_DIR:-$HMDM_DIR/app-installer}"

# APK_NAME is set after the build so it can embed the version code.
# It must NOT be set as a default here — it is derived from version.properties.

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
# Bump versionCode + versionName in version.properties before each build
# ---------------------------------------------------------------------------
VERSION_PROPS_FILE="$ANDROID_DIR/version.properties"

bump_android_version() {
    local props_file="$1"
    local cur_code cur_name
    cur_code=$(grep '^VERSION_CODE=' "$props_file" | head -1 | cut -d= -f2 | tr -d '[:space:]')
    cur_name=$(grep '^VERSION_NAME=' "$props_file" | head -1 | cut -d= -f2 | tr -d '[:space:]')

    local new_code=$(( ${cur_code:-15310} + 1 ))

    # Increment the minor part of the version name (e.g. 6.91 -> 6.92)
    local major minor
    major="${cur_name%%.*}"
    minor="${cur_name#*.}"
    minor=$((10#$minor + 1))
    if [ "$minor" -ge 100 ]; then
        major=$((major + 1))
        minor=0
    fi
    local new_name="${major}.${minor}"

    {
        echo "# Auto-managed by deploy_updated_apk.sh — do not edit manually"
        echo "# VERSION_CODE must increase monotonically for each Play Console upload"
        echo "VERSION_CODE=${new_code}"
        echo "VERSION_NAME=${new_name}"
    } > "${props_file}.tmp" && mv "${props_file}.tmp" "$props_file"

    echo "Android version bumped: code=${new_code}, name=${new_name}"
}

# ---------------------------------------------------------------------------
# Build APK
# ---------------------------------------------------------------------------
APK_PATH="$ANDROID_DIR/app/build/outputs/apk/enterprise/release/app-enterprise-release.apk"
AAB_PATH="$ANDROID_DIR/app/build/outputs/bundle/enterpriseRelease/app-enterprise-release.aab"

if [ "${SKIP_BUILD}" != "1" ]; then
    if [ ! -f "$VERSION_PROPS_FILE" ]; then
        echo "Warning: version.properties not found at $VERSION_PROPS_FILE, skipping version bump"
    else
        bump_android_version "$VERSION_PROPS_FILE"
    fi
    echo "Building Android Enterprise APK + AAB (Release)..."
    echo "Android BUILD_TYPE: $ANDROID_BUILD_TYPE"
    echo "Android BASE_URL: $ANDROID_BASE_URL"
    echo "Android SECONDARY_BASE_URL: $ANDROID_SECONDARY_BASE_URL"
    cd "$ANDROID_DIR"
    ./gradlew -PmdmBaseUrl="$ANDROID_BASE_URL" -PmdmSecondaryBaseUrl="$ANDROID_SECONDARY_BASE_URL" bundleEnterpriseRelease assembleEnterpriseRelease --no-daemon
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

# ---------------------------------------------------------------------------
# Derive versioned APK filename from version.properties
# Each version gets a unique filename (e.g. app-enterprise-release-15371.apk)
# so every upload has a distinct URL — no Cloudflare cache collisions.
# ---------------------------------------------------------------------------
ANDROID_VERSION_CODE=$(grep '^VERSION_CODE=' "$VERSION_PROPS_FILE" | head -1 | cut -d= -f2 | tr -d '[:space:]')
ANDROID_VERSION_NAME=$(grep '^VERSION_NAME=' "$VERSION_PROPS_FILE" | head -1 | cut -d= -f2 | tr -d '[:space:]')

# Allow override via env, otherwise use versioned name
APK_NAME="${APK_NAME:-app-enterprise-release-${ANDROID_VERSION_CODE}.apk}"
echo "APK filename: $APK_NAME  (version ${ANDROID_VERSION_NAME} / ${ANDROID_VERSION_CODE})"

mkdir -p "$APP_ARTIFACT_DIR"
cp -f "$APK_PATH" "$APP_ARTIFACT_DIR/app-enterprise-release.apk"
cp -f "$AAB_PATH" "$APP_ARTIFACT_DIR/app-enterprise-release.aab"
cp -f "$APK_PATH" "$APP_ARTIFACT_DIR/$APK_NAME"
echo "Updated app artifacts in: $APP_ARTIFACT_DIR"

mkdir -p "$APP_INSTALLER_DIR"
cp -f "$APK_PATH" "$APP_INSTALLER_DIR/app-enterprise-release.apk"
echo "Updated installer APK in: $APP_INSTALLER_DIR/app-enterprise-release.apk"

# ---------------------------------------------------------------------------
# Calculate hash
# ---------------------------------------------------------------------------
echo "Calculating SHA-256 hash (URL-safe Base64)..."
APK_HASH=$(python3 -c "import hashlib, base64, sys; print(base64.urlsafe_b64encode(hashlib.sha256(open(sys.argv[1], 'rb').read()).digest()).decode('utf-8'))" "$APK_PATH")
echo "Hash: $APK_HASH"

if [ "$SKIP_SERVER_DEPLOY" = "1" ]; then
    echo "SKIP_SERVER_DEPLOY=1 -> skipping server file copy and database update."
    echo "Done! New APK/AAB built and artifacts refreshed only."
    echo ""
    echo "APK:     $APK_NAME"
    echo "Version: ${ANDROID_VERSION_NAME} (${ANDROID_VERSION_CODE})"
    echo "Hash:    $APK_HASH"
    echo "URL:     $APK_BASE_URL/$APK_NAME"
    exit 0
fi

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
        APK_URL="${APK_BASE_URL}/${APK_NAME}"

        # Get the application ID
        APP_ID="$(docker exec "$DOCKER_DB_CONTAINER" psql \
            -U "$DB_USER" \
            -d "$DB_NAME" \
            -tA \
            -c "SELECT id FROM applications WHERE pkg = '${PKG_NAME}' LIMIT 1;" | tr -d '[:space:]')"

        if [ -z "$APP_ID" ]; then
            echo "Error: application with pkg '${PKG_NAME}' not found in database."
            exit 1
        fi

        # Check if this version code already exists — if so, update URL/hash; otherwise insert
        EXISTING_ID="$(docker exec "$DOCKER_DB_CONTAINER" psql \
            -U "$DB_USER" \
            -d "$DB_NAME" \
            -tA \
            -c "SELECT id FROM applicationversions WHERE applicationid = ${APP_ID} AND versioncode = ${ANDROID_VERSION_CODE} LIMIT 1;" | tr -d '[:space:]')"

        if [ -n "$EXISTING_ID" ]; then
            docker exec "$DOCKER_DB_CONTAINER" psql \
                -U "$DB_USER" \
                -d "$DB_NAME" \
                -c "UPDATE applicationversions
                    SET url = '${APK_URL}',
                        apkhash = '${APK_HASH}',
                        version = '${ANDROID_VERSION_NAME}'
                    WHERE id = ${EXISTING_ID};"
            echo "Database updated (existing record id=${EXISTING_ID})."
        else
            docker exec "$DOCKER_DB_CONTAINER" psql \
                -U "$DB_USER" \
                -d "$DB_NAME" \
                -c "INSERT INTO applicationversions (applicationid, version, versioncode, url, apkhash)
                    VALUES (${APP_ID}, '${ANDROID_VERSION_NAME}', ${ANDROID_VERSION_CODE}, '${APK_URL}', '${APK_HASH}');"
            echo "Database updated (new record inserted: version=${ANDROID_VERSION_NAME}, code=${ANDROID_VERSION_CODE})."
        fi
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
        APK_URL="$APK_BASE_URL/$APK_NAME"

        export PGPASSWORD="$DB_PASSWORD"

        APP_ID="$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -tA \
            -c "SELECT id FROM applications WHERE pkg = '${PKG_NAME}' LIMIT 1;" | tr -d '[:space:]')"

        if [ -z "$APP_ID" ]; then
            echo "Error: application with pkg '${PKG_NAME}' not found in database."
            exit 1
        fi

        EXISTING_ID="$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -tA \
            -c "SELECT id FROM applicationversions WHERE applicationid = ${APP_ID} AND versioncode = ${ANDROID_VERSION_CODE} LIMIT 1;" | tr -d '[:space:]')"

        if [ -n "$EXISTING_ID" ]; then
            psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "
                UPDATE applicationversions
                SET url = '$APK_URL',
                    apkhash = '$APK_HASH',
                    version = '${ANDROID_VERSION_NAME}'
                WHERE id = ${EXISTING_ID};"
            echo "Database updated (existing record id=${EXISTING_ID})."
        else
            psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "
                INSERT INTO applicationversions (applicationid, version, versioncode, url, apkhash)
                VALUES (${APP_ID}, '${ANDROID_VERSION_NAME}', ${ANDROID_VERSION_CODE}, '$APK_URL', '$APK_HASH');"
            echo "Database updated (new record inserted: version=${ANDROID_VERSION_NAME}, code=${ANDROID_VERSION_CODE})."
        fi
    fi
fi

echo "Done! New APK deployed ($MODE mode)."
echo ""
echo "APK:     $APK_NAME"
echo "Version: ${ANDROID_VERSION_NAME} (${ANDROID_VERSION_CODE})"
echo "Hash:    $APK_HASH"
echo "URL:     $APK_BASE_URL/$APK_NAME"
