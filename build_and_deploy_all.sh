#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "Root directory: $ROOT_DIR"
ANDROID_DEPLOY_SCRIPT="$ROOT_DIR/deploy_updated_apk.sh"
SERVER_DIR="$ROOT_DIR/hmdm-server"
SERVER_WAR="$SERVER_DIR/server/target/launcher.war"
TOMCAT_HOME="${TOMCAT_HOME:-/opt/tomcat9}"
START_CLOUDFLARE_TUNNEL="${START_CLOUDFLARE_TUNNEL:-0}"
CLOUDFLARE_TUNNEL_ID="${CLOUDFLARE_TUNNEL_ID:-}"
CLOUDFLARE_CONFIG_FILE="${CLOUDFLARE_CONFIG_FILE:-$HOME/.cloudflared/config.yml}"

if [ ! -f "$ANDROID_DEPLOY_SCRIPT" ]; then
  echo "Error: missing script $ANDROID_DEPLOY_SCRIPT"
  exit 1
fi

if [ ! -d "$SERVER_DIR" ]; then
  echo "Error: missing server directory $SERVER_DIR"
  exit 1
fi

echo "=== Step 1/5: Build Android APK + deploy APK metadata/files ==="
bash "$ANDROID_DEPLOY_SCRIPT"

echo "=== Step 2/5: Build HMDM server ==="
cd "$SERVER_DIR"
mvn -DskipTests clean package

if [ ! -f "$SERVER_WAR" ]; then
  echo "Error: expected WAR not found at $SERVER_WAR"
  exit 1
fi

if [ ! -d "$TOMCAT_HOME" ]; then
  echo "Error: configured Tomcat home not found: $TOMCAT_HOME"
  exit 1
fi

TOMCAT_WEBAPPS="$TOMCAT_HOME/webapps"
DEST_WAR="$TOMCAT_WEBAPPS/ROOT.war"
TOMCAT_STARTUP="$TOMCAT_HOME/bin/startup.sh"
TOMCAT_SHUTDOWN="$TOMCAT_HOME/bin/shutdown.sh"

if [ ! -x "$TOMCAT_STARTUP" ]; then
  echo "Error: startup script not executable or missing: $TOMCAT_STARTUP"
  exit 1
fi

if [ ! -x "$TOMCAT_SHUTDOWN" ]; then
  echo "Error: shutdown script not executable or missing: $TOMCAT_SHUTDOWN"
  exit 1
fi

echo "=== Step 3/5: Deploy WAR to Tomcat ($DEST_WAR) ==="
if [ -w "$TOMCAT_WEBAPPS" ]; then
  cp "$SERVER_WAR" "$DEST_WAR"
  chmod 644 "$DEST_WAR"
else
  sudo cp "$SERVER_WAR" "$DEST_WAR"
  sudo chmod 644 "$DEST_WAR"
fi

echo "=== Step 4/5: Restart Tomcat using shutdown.sh/startup.sh ==="
if ! "$TOMCAT_SHUTDOWN"; then
  echo "Tomcat shutdown without sudo failed, retrying with sudo..."
  sudo "$TOMCAT_SHUTDOWN"
fi

sleep 3

if ! "$TOMCAT_STARTUP"; then
  echo "Tomcat startup without sudo failed, retrying with sudo..."
  sudo "$TOMCAT_STARTUP"
fi

echo "=== Step 5/5: Optional Cloudflare tunnel ==="

if [ "$START_CLOUDFLARE_TUNNEL" = "1" ]; then
  if [ -z "$CLOUDFLARE_TUNNEL_ID" ]; then
    echo "Error: CLOUDFLARE_TUNNEL_ID is required when START_CLOUDFLARE_TUNNEL=1"
    exit 1
  fi

  if [ ! -f "$CLOUDFLARE_CONFIG_FILE" ]; then
    echo "Error: Cloudflare config not found at $CLOUDFLARE_CONFIG_FILE"
    exit 1
  fi

  LOG_FILE="$ROOT_DIR/cloudflared.log"
  CLOUDFLARED_BIN="$(which cloudflared)"
  TMUX_BIN="$(which tmux)"

  echo "Using cloudflared at: $CLOUDFLARED_BIN"
  echo "Using tmux at: $TMUX_BIN"

  # Kill old tunnel session if it exists
  $TMUX_BIN kill-session -t cloudflared 2>/dev/null || true

  # Start tunnel with explicit config path
  $TMUX_BIN new-session -d -s cloudflared \
    "$CLOUDFLARED_BIN tunnel --config $CLOUDFLARE_CONFIG_FILE run $CLOUDFLARE_TUNNEL_ID > $LOG_FILE 2>&1"

  sleep 3

  # Verify session
  if $TMUX_BIN has-session -t cloudflared 2>/dev/null; then
    echo "Cloudflare tunnel started successfully."
    echo "Check status: tmux ls"
    echo "View logs: tail -f $LOG_FILE"
  else
    echo "Failed to start Cloudflare tunnel."
    echo "Check logs: $LOG_FILE"
  fi
else
  echo "Skipping Cloudflare tunnel startup (START_CLOUDFLARE_TUNNEL=0)."
fi



echo "=== Done: Android + DB update + Server build + Tomcat restart complete ==="
