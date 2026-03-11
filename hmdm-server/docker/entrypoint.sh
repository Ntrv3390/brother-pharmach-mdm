#!/bin/bash
# =============================================================================
# hmdm-server Docker Entrypoint
# Generates Tomcat context.xml from environment variables, then starts Tomcat.
# =============================================================================
set -e

# ---------------------------------------------------------------------------
# Configuration with defaults  (override via .env / docker-compose.yml)
# ---------------------------------------------------------------------------
DB_HOST="${DB_HOST:-postgres}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-hmdm}"
DB_USER="${DB_USER:-hmdm}"
DB_PASSWORD="${DB_PASSWORD:-hmdm}"

BASE_DIRECTORY="${BASE_DIRECTORY:-/opt/hmdm}"
FILES_DIRECTORY="${FILES_DIRECTORY:-${BASE_DIRECTORY}/files}"
PLUGINS_DIRECTORY="${PLUGINS_DIRECTORY:-${BASE_DIRECTORY}/plugins}"

BASE_URL="${BASE_URL:-http://localhost:8080}"
USAGE_SCENARIO="${USAGE_SCENARIO:-private}"
SECURE_ENROLLMENT="${SECURE_ENROLLMENT:-0}"
HASH_SECRET="${HASH_SECRET:-changeme-C3z9vi54}"

# MQTT – the embedded broker binds to this address:port
# For Docker, use the container's hostname or 0.0.0.0 to accepting connections
# Mobile devices reach MQTT via BASE_URL's host + port 31000
MQTT_SERVER_URI="${MQTT_SERVER_URI:-0.0.0.0:31000}"
MQTT_EXTERNAL="${MQTT_EXTERNAL:-0}"
MQTT_CLIENT_TAG="${MQTT_CLIENT_TAG:-}"
MQTT_AUTH="${MQTT_AUTH:-1}"
MQTT_MESSAGE_DELAY="${MQTT_MESSAGE_DELAY:-0}"

DEVICE_FAST_SEARCH_CHARS="${DEVICE_FAST_SEARCH_CHARS:-5}"
ROLE_ORGADMIN_ID="${ROLE_ORGADMIN_ID:-2}"

PLUGIN_DEVICELOG_CLASS="com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration"
PLUGIN_WORKTIME_CLASS="com.hmdm.plugins.worktime.persistence.postgres.WorkTimePostgresPersistenceConfiguration"
PLUGIN_CALLLOG_CLASS="com.hmdm.plugins.calllog.persistence.postgres.CallLogPostgresPersistenceConfiguration"

AAPT_COMMAND="${AAPT_COMMAND:-aapt}"

SMTP_HOST="${SMTP_HOST:-}"
SMTP_PORT="${SMTP_PORT:-25}"
SMTP_SSL="${SMTP_SSL:-0}"
SMTP_STARTTLS="${SMTP_STARTTLS:-0}"
SMTP_USERNAME="${SMTP_USERNAME:-}"
SMTP_PASSWORD="${SMTP_PASSWORD:-}"
SMTP_FROM="${SMTP_FROM:-}"

JWT_SECRET="${JWT_SECRET:-20c68f0d9185b1d18cf6add1e8b491fd89529a44}"
JWT_VALIDITY="${JWT_VALIDITY:-86400}"
JWT_VALIDITY_REMEMBER="${JWT_VALIDITY_REMEMBER:-2592000}"

INSTALL_FLAG="${BASE_DIRECTORY}/hmdm_install_flag"
LOG4J_CONFIG="file://${BASE_DIRECTORY}/log4j-hmdm.xml"

echo "==> [hmdm] Starting Brother Pharmach MDM Server"

# ---------------------------------------------------------------------------
# Ensure directories exist
# ---------------------------------------------------------------------------
mkdir -p "${BASE_DIRECTORY}/files" \
         "${BASE_DIRECTORY}/plugins" \
         "${BASE_DIRECTORY}/logs"

# ---------------------------------------------------------------------------
# Write log4j config (points to stdout + log files)
# ---------------------------------------------------------------------------
cp /opt/hmdm-setup/log4j-hmdm.xml "${BASE_DIRECTORY}/log4j-hmdm.xml"
sed -i "s|_BASE_DIRECTORY_|${BASE_DIRECTORY}|g" "${BASE_DIRECTORY}/log4j-hmdm.xml"

# ---------------------------------------------------------------------------
# Generate Tomcat context.xml from environment variables
# ---------------------------------------------------------------------------
CONTEXT_DIR="/usr/local/tomcat/conf/Catalina/localhost"
mkdir -p "${CONTEXT_DIR}"

echo "==> [hmdm] Writing Tomcat context (ROOT.xml)"
cat > "${CONTEXT_DIR}/ROOT.xml" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<Context>
    <!-- Database -->
    <Parameter name="JDBC.driver"   value="org.postgresql.Driver"/>
    <Parameter name="JDBC.url"      value="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"/>
    <Parameter name="JDBC.username" value="${DB_USER}"/>
    <Parameter name="JDBC.password" value="${DB_PASSWORD}"/>

    <!-- File storage -->
    <Parameter name="base.directory"         value="${BASE_DIRECTORY}"/>
    <Parameter name="files.directory"        value="${FILES_DIRECTORY}"/>
    <Parameter name="plugins.files.directory" value="${PLUGINS_DIRECTORY}"/>

    <!-- Web URL -->
    <Parameter name="base.url" value="${BASE_URL}"/>

    <!-- App config -->
    <Parameter name="usage.scenario"  value="${USAGE_SCENARIO}"/>
    <Parameter name="secure.enrollment" value="${SECURE_ENROLLMENT}"/>
    <Parameter name="hash.secret"     value="${HASH_SECRET}"/>
    <Parameter name="role.orgadmin.id" value="${ROLE_ORGADMIN_ID}"/>

    <!-- Plugin persistence classes -->
    <Parameter name="plugin.devicelog.persistence.config.class" value="${PLUGIN_DEVICELOG_CLASS}"/>
    <Parameter name="plugin.worktime.persistence.config.class"  value="${PLUGIN_WORKTIME_CLASS}"/>
    <Parameter name="plugin.calllog.persistence.config.class"   value="${PLUGIN_CALLLOG_CLASS}"/>

    <!-- MQTT push notifications -->
    <Parameter name="mqtt.server.uri"    value="${MQTT_SERVER_URI}"/>
    <Parameter name="mqtt.external"      value="${MQTT_EXTERNAL}"/>
    <Parameter name="mqtt.client.tag"    value="${MQTT_CLIENT_TAG}"/>
    <Parameter name="mqtt.auth"          value="${MQTT_AUTH}"/>
    <Parameter name="mqtt.message.delay" value="${MQTT_MESSAGE_DELAY}"/>

    <!-- Misc -->
    <Parameter name="device.fast.search.chars" value="${DEVICE_FAST_SEARCH_CHARS}"/>
    <Parameter name="aapt.command"             value="${AAPT_COMMAND}"/>
    <Parameter name="log4j.config"             value="${LOG4J_CONFIG}"/>
    <Parameter name="initialization.completion.signal.file" value="${INSTALL_FLAG}"/>
    <Parameter name="plugin.photo.enable.places"      value="0"/>
    <Parameter name="plugin.audit.display.forwarded.ip" value="0"/>
    <Parameter name="sql.init.script.path"            value=""/>

    <!-- SMTP (password recovery) -->
    <Parameter name="smtp.host"      value="${SMTP_HOST}"/>
    <Parameter name="smtp.port"      value="${SMTP_PORT}"/>
    <Parameter name="smtp.ssl"       value="${SMTP_SSL}"/>
    <Parameter name="smtp.starttls"  value="${SMTP_STARTTLS}"/>
    <Parameter name="smtp.username"  value="${SMTP_USERNAME}"/>
    <Parameter name="smtp.password"  value="${SMTP_PASSWORD}"/>
    <Parameter name="smtp.from"      value="${SMTP_FROM}"/>

    <!-- JWT -->
    <Parameter name="jwt.secretkey"           value="${JWT_SECRET}"/>
    <Parameter name="jwt.validity"            value="${JWT_VALIDITY}"/>
    <Parameter name="jwt.validityrememberme"  value="${JWT_VALIDITY_REMEMBER}"/>
</Context>
EOF

# ---------------------------------------------------------------------------
# Wait for PostgreSQL to be ready
# ---------------------------------------------------------------------------
echo "==> [hmdm] Waiting for PostgreSQL at ${DB_HOST}:${DB_PORT}..."
until pg_isready -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -q; do
    echo "    PostgreSQL not ready yet, retrying in 3s..."
    sleep 3
done
echo "==> [hmdm] PostgreSQL is ready."

# ---------------------------------------------------------------------------
# First-run database initialisation
# ---------------------------------------------------------------------------
if [ ! -f "${BASE_DIRECTORY}/.db_initialized" ]; then
    echo "==> [hmdm] First run detected – initialising database..."

    # Substitute vars into the SQL init script
    ADMIN_EMAIL="${ADMIN_EMAIL:-admin@example.com}"
    CLIENT_VERSION="5.19"
    CLIENT_APK="hmdm-${CLIENT_VERSION}-os.apk"

    TEMP_SQL="/tmp/hmdm_init_$$.sql"
    sed "s|_HMDM_BASE_|${BASE_DIRECTORY}|g; \
         s|_HMDM_VERSION_|${CLIENT_VERSION}|g; \
         s|_HMDM_APK_|${CLIENT_APK}|g; \
         s|_ADMIN_EMAIL_|${ADMIN_EMAIL}|g" \
        /opt/hmdm-setup/hmdm_init.en.sql > "${TEMP_SQL}"

    PGPASSWORD="${DB_PASSWORD}" psql \
        -h "${DB_HOST}" \
        -p "${DB_PORT}" \
        -U "${DB_USER}" \
        -d "${DB_NAME}" \
        -f "${TEMP_SQL}" > /dev/null 2>&1 && \
        echo "==> [hmdm] Database initialised successfully." || \
        echo "==> [hmdm] WARNING: DB init script returned errors (may be safe if DB was already seeded)."

    rm -f "${TEMP_SQL}"
    touch "${BASE_DIRECTORY}/.db_initialized"
else
    echo "==> [hmdm] Database already initialised, skipping."
fi

# ---------------------------------------------------------------------------
# Start Tomcat
# ---------------------------------------------------------------------------
echo "==> [hmdm] Starting Tomcat..."
exec /usr/local/tomcat/bin/catalina.sh run
