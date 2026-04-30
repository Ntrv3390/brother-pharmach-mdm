def update_app_version_in_db(values: dict) -> bool:
    """
    Insert or update the latest APK version in the database for the main launcher app.
    """
    from pathlib import Path

    version_props = ROOT_DIR / "hmdm-android" / "version.properties"
    env = load_env(version_props)
    version_name = env.get("VERSION_NAME")
    version_code = env.get("VERSION_CODE")
    pkg_name = "com.brother.pharmach.mdm.launcher"
    apk_url = "https://brothers-mdm.com/files/brother-pharmach-mdm.apk"

    if not version_name or not version_code:
        append_log("ERROR: VERSION_NAME or VERSION_CODE missing in version.properties")
        return False

    # 1. Fetch application ID (SQL injection safe)
    sql_get_app_id = f"SELECT id FROM applications WHERE pkg = {sql_lit(pkg_name)} LIMIT 1;"
    rc, out = db_admin_exec(values, sql_get_app_id, database=values["DB_NAME"])
    if rc != 0:
        append_log(f"ERROR: Failed to fetch application ID: {out}")
        return False
    app_id = None
    for line in out.splitlines():
        line = line.strip()
        # Robust parsing: skip headers, look for numeric id in first column
        if line and "|" in line:
            parts = [x.strip() for x in line.split("|")]
            if parts and parts[0].isdigit():
                app_id = int(parts[0])
                break
        elif line.isdigit():
            app_id = int(line)
            break
    if not app_id:
        append_log(f"ERROR: Application with pkg '{pkg_name}' not found in DB.")
        return False

    # 2. Check if this versioncode already exists (uniqueness by versioncode)
    sql_check_version = (
        f"SELECT id FROM applicationversions WHERE applicationid = {app_id} AND versioncode = {int(version_code)};"
    )
    rc, out = db_admin_exec(values, sql_check_version, database=values["DB_NAME"])
    if rc != 0:
        append_log(f"ERROR: Failed to check version existence: {out}")
        return False
    version_id = None
    for line in out.splitlines():
        line = line.strip()
        if line and "|" in line:
            parts = [x.strip() for x in line.split("|")]
            if parts and parts[0].isdigit():
                version_id = int(parts[0])
                break
        elif line.isdigit():
            version_id = int(line)
            break

    if version_id:
        append_log(f"Versioncode {version_code} already exists, skipping insert.")
    else:
        # 3. Insert new version (SQL injection safe, version_code as int)
        sql_insert = (
            "INSERT INTO applicationversions (applicationid, version, url, versioncode) "
            f"VALUES ({app_id}, {sql_lit(version_name)}, {sql_lit(apk_url)}, {int(version_code)});"
        )
        rc, out = db_admin_exec(values, sql_insert, database=values["DB_NAME"])
        if rc != 0:
            append_log(f"ERROR: Failed to insert new version {version_name}: {out}")
            return False
        append_log(f"Inserted new version {version_name} for app {pkg_name}.")

    # 4. Update latestversion pointer (order by versioncode)
    sql_update_latest = (
        f"UPDATE applications SET latestversion = ("
        f"SELECT id FROM applicationversions WHERE applicationid = {app_id} ORDER BY versioncode DESC LIMIT 1"
        f") WHERE id = {app_id};"
    )
    rc, out = db_admin_exec(values, sql_update_latest, database=values["DB_NAME"])
    if rc != 0:
        append_log(f"ERROR: Failed to update latestversion pointer: {out}")
        return False

    append_log(f"Application version updated to {version_name}")
    return True
#!/usr/bin/env python3
"""
Brother Pharmamach MDM - One-command web installer.

Usage:
  python3 setup.py

What it does:
  1) Starts a local setup web page and opens it in your browser.
  2) Accepts environment values once and writes hmdm-server/.env.
  3) Runs docker compose build + up -d (with tunnel profile when token exists).
  4) Streams progress/logs in the web page.
  5) Shows "Continue to Admin Panel" when healthy.

Note:
  A plain `docker build` command cannot launch interactive UI by itself because
  image build runs in a non-interactive build context. This script is the
  one-command installer entrypoint for the requested flow.
"""

import html
import json
import os
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT_DIR = Path(__file__).parent.resolve()
SERVER_DIR = ROOT_DIR / "hmdm-server"
ENV_FILE = SERVER_DIR / ".env"
SETUP_STATE_FILE = SERVER_DIR / ".setup_completed.json"

HOST = "127.0.0.1"
PORT = 8765
APP_VERSION = "v1.0.4"

STATE_LOCK = threading.Lock()
STATE = {
    "running": False,
    "done": False,
    "error": "",
    "phase": "idle",
    "logs": [],
    "base_url": "",
    "http_port": "8080",
}


def load_env(path: Path) -> dict:
    env = {}
    if path.exists():
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if line and not line.startswith("#") and "=" in line:
                k, _, v = line.partition("=")
                env[k.strip()] = v.strip()
    return env


def write_env(path: Path, v: dict):
    lines = [
        "# Brother Pharmamach MDM - Environment Configuration",
        "# Generated by setup.py",
        "",
        "# Server",
        f"BASE_URL={v['BASE_URL']}",
        f"ADMIN_EMAIL={v['ADMIN_EMAIL']}",
        f"HTTP_PORT={v['HTTP_PORT']}",
        f"MQTT_PORT={v['MQTT_PORT']}",
        "",
        "# Database",
        f"DB_NAME={v['DB_NAME']}",
        f"DB_USER={v['DB_USER']}",
        f"DB_PASSWORD={v['DB_PASSWORD']}",
        f"DB_PORT={v['DB_PORT']}",
        "",
        "# Security",
        f"HASH_SECRET={v['HASH_SECRET']}",
        f"JWT_SECRET={v['JWT_SECRET']}",
        f"SECURE_ENROLLMENT={v['SECURE_ENROLLMENT']}",
        "",
        "# MQTT",
        f"MQTT_AUTH={v['MQTT_AUTH']}",
        f"MQTT_MESSAGE_DELAY={v['MQTT_MESSAGE_DELAY']}",
        "",
        "# SMTP (optional)",
        f"SMTP_HOST={v['SMTP_HOST']}",
        f"SMTP_PORT={v['SMTP_PORT']}",
        f"SMTP_SSL={v['SMTP_SSL']}",
        f"SMTP_STARTTLS={v['SMTP_STARTTLS']}",
        f"SMTP_USERNAME={v['SMTP_USERNAME']}",
        f"SMTP_PASSWORD={v['SMTP_PASSWORD']}",
        f"SMTP_FROM={v['SMTP_FROM']}",
        "",
        "# Cloudflare Tunnel (optional)",
        f"CLOUDFLARE_TUNNEL_TOKEN={v['CLOUDFLARE_TUNNEL_TOKEN']}",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def read_setup_done() -> dict:
    if not SETUP_STATE_FILE.exists():
        return {}
    try:
        return json.loads(SETUP_STATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def write_setup_done(base_url: str):
    payload = {
        "completed": True,
        "base_url": base_url,
        "timestamp": int(time.time()),
    }
    SETUP_STATE_FILE.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def append_log(line: str):
    with STATE_LOCK:
        STATE["logs"].append(line)
        # Keep memory bounded
        if len(STATE["logs"]) > 1500:
            STATE["logs"] = STATE["logs"][-1000:]


def set_phase(phase: str):
    with STATE_LOCK:
        STATE["phase"] = phase


def set_error(message: str):
    with STATE_LOCK:
        STATE["error"] = message
        STATE["running"] = False


def set_done(base_url: str):
    with STATE_LOCK:
        STATE["done"] = True
        STATE["running"] = False
        STATE["base_url"] = base_url


def docker_available() -> bool:
    try:
        return subprocess.run(["docker", "info"], capture_output=True).returncode == 0
    except FileNotFoundError:
        return False


def run_compose_stream(args, cwd: Path) -> int:
    cmd = ["docker", "compose"] + args
    append_log("$ " + " ".join(cmd))
    try:
        proc = subprocess.Popen(
            cmd,
            cwd=str(cwd),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
    except FileNotFoundError:
        append_log("ERROR: docker command not found")
        return 127

    assert proc.stdout is not None
    for line in proc.stdout:
        append_log(line.rstrip())
    return proc.wait()


def run_compose_capture(args, cwd: Path, log_command: bool = True) -> tuple:
    cmd = ["docker", "compose"] + args
    if log_command:
        append_log("$ " + " ".join(cmd))
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(cwd),
            capture_output=True,
            text=True,
        )
    except FileNotFoundError:
        append_log("ERROR: docker command not found")
        return 127, ""

    output = (proc.stdout or "") + (proc.stderr or "")
    out = output.strip()
    if out:
        for line in out.splitlines():
            append_log(line)
    return proc.returncode, out


def to_bool(value: str) -> bool:
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def remove_project_images() -> bool:
    append_log("Removing project-related Docker images...")

    rc, out = run_compose_capture(["--profile", "tunnel", "images", "-q"], SERVER_DIR)
    refs = set()
    if rc == 0 and out:
        for line in out.splitlines():
            line = line.strip()
            if line:
                refs.add(line)

    refs.update({
        "hmdm-server:latest",
        "postgres:15-alpine",
        "cloudflare/cloudflared:latest",
    })

    all_ok = True
    for ref in sorted(refs):
        cmd = ["docker", "image", "rm", "-f", ref]
        append_log("$ " + " ".join(cmd))
        try:
            proc = subprocess.run(
                cmd,
                cwd=str(SERVER_DIR),
                capture_output=True,
                text=True,
            )
        except FileNotFoundError:
            append_log("ERROR: docker command not found")
            return False

        output = ((proc.stdout or "") + (proc.stderr or "")).strip()
        if output:
            for line in output.splitlines():
                append_log(line)

        if proc.returncode != 0 and "No such image" not in output and "reference does not exist" not in output:
            all_ok = False

    return all_ok


def prune_docker_builder_cache() -> bool:
    append_log("Pruning Docker builder cache...")
    cmd = ["docker", "builder", "prune", "-af"]
    append_log("$ " + " ".join(cmd))
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(SERVER_DIR),
            capture_output=True,
            text=True,
        )
    except FileNotFoundError:
        append_log("ERROR: docker command not found")
        return False

    output = ((proc.stdout or "") + (proc.stderr or "")).strip()
    if output:
        for line in output.splitlines():
            append_log(line)

    return proc.returncode == 0


def sql_ident(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def sql_lit(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def db_admin_exec(
    values: dict,
    sql: str,
    database: str = "postgres",
    admin_user: str = None,
    admin_password: str = None,
) -> tuple:
    admin_user = admin_user if admin_user is not None else values["DB_ADMIN_USER"]
    admin_password = admin_password if admin_password is not None else values["DB_ADMIN_PASSWORD"]
    cmd = [
        "docker", "compose", "exec", "-T",
        "-e", f"PGPASSWORD={admin_password}",
        "postgres",
        "psql",
        "-h", "127.0.0.1",
        "-p", values.get("DB_PORT", "5432"),
        "-v", "ON_ERROR_STOP=1",
        "-U", admin_user,
        "-d", database,
        "-c", sql,
    ]
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(SERVER_DIR),
            capture_output=True,
            text=True,
        )
    except FileNotFoundError:
        append_log("ERROR: docker command not found")
        return 127, ""

    output = (proc.stdout or "") + (proc.stderr or "")
    out = output.strip()
    if out:
        for line in out.splitlines():
            append_log(line)
    return proc.returncode, out


def db_local_postgres_exec(sql: str, database: str = "postgres") -> tuple:
    cmd = [
        "docker", "compose", "exec", "-T",
        "postgres",
        "psql",
        "-v", "ON_ERROR_STOP=1",
        "-U", "postgres",
        "-d", database,
        "-c", sql,
    ]
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(SERVER_DIR),
            capture_output=True,
            text=True,
        )
    except FileNotFoundError:
        append_log("ERROR: docker command not found")
        return 127, ""

    output = (proc.stdout or "") + (proc.stderr or "")
    out = output.strip()
    if out:
        for line in out.splitlines():
            append_log(line)
    return proc.returncode, out


def resolve_admin_credentials(values: dict) -> tuple:
    existing = load_env(ENV_FILE)
    candidates = [
        (values.get("DB_ADMIN_USER", ""), values.get("DB_ADMIN_PASSWORD", ""), "form admin"),
        (values.get("DB_USER", ""), values.get("DB_PASSWORD", ""), "form app user"),
        (existing.get("DB_USER", ""), existing.get("DB_PASSWORD", ""), "existing .env app user"),
        ("hmdm", "hmdm", "legacy default"),
        ("postgres", "postgres", "postgres default"),
    ]

    seen = set()
    for user, pwd, label in candidates:
        key = (user, pwd)
        if not user or not pwd or key in seen:
            continue
        seen.add(key)

        rc, out = db_admin_exec(
            values,
            "SELECT rolcreaterole::int, rolcreatedb::int, rolsuper::int FROM pg_roles WHERE rolname = current_user;",
            admin_user=user,
            admin_password=pwd,
        )
        if rc != 0:
            continue

        lines = [ln.strip() for ln in out.splitlines() if ln.strip() and "|" in ln]
        if not lines:
            continue

        parts = [p.strip() for p in lines[-1].split("|")]
        if len(parts) != 3:
            continue

        try:
            can_createrole = int(parts[0]) == 1
            can_createdb = int(parts[1]) == 1
            is_super = int(parts[2]) == 1
        except ValueError:
            continue

        if is_super or (can_createrole and can_createdb):
            append_log(f"Using PostgreSQL admin credentials from {label}: '{user}'")
            return user, pwd

    return None, None


def wait_postgres_ready(values: dict, timeout_sec: int = 90) -> bool:
    append_log("Waiting for PostgreSQL to accept connections...")
    deadline = time.time() + timeout_sec
    user = values.get("DB_USER", "postgres")
    db_name = values.get("DB_NAME", "postgres")
    port = values.get("DB_PORT", "5432")

    while time.time() < deadline:
        cmd = [
            "docker", "compose", "exec", "-T",
            "postgres",
            "pg_isready",
            "-h", "127.0.0.1",
            "-p", port,
            "-U", user,
            "-d", db_name,
        ]
        try:
            proc = subprocess.run(
                cmd,
                cwd=str(SERVER_DIR),
                capture_output=True,
                text=True,
            )
        except FileNotFoundError:
            append_log("ERROR: docker command not found")
            return False

        if proc.returncode == 0:
            append_log("PostgreSQL is ready.")
            return True

        time.sleep(2)

    append_log("ERROR: PostgreSQL was not ready before timeout.")
    return False


def ensure_db_prerequisites(values: dict) -> bool:
    append_log("Preparing PostgreSQL prerequisites (database/user)...")

    if not wait_postgres_ready(values):
        set_error("PostgreSQL container started, but the database is not ready yet. Please retry in a few seconds.")
        return False

    db_name = values["DB_NAME"]
    db_user = values["DB_USER"]
    db_password = values["DB_PASSWORD"]
    requested_admin_user = values["DB_ADMIN_USER"]
    requested_admin_password = values["DB_ADMIN_PASSWORD"]

    append_log("Checking PostgreSQL admin credentials...")
    admin_user, admin_password = resolve_admin_credentials(values)
    if not admin_user:
        append_log("Admin login failed. Trying local postgres bootstrap inside the container...")
        bootstrap_sql = (
            "DO $$ "
            "BEGIN "
            f"IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = {sql_lit(requested_admin_user)}) THEN "
            f"CREATE ROLE {sql_ident(requested_admin_user)} LOGIN CREATEDB CREATEROLE PASSWORD {sql_lit(requested_admin_password)}; "
            "ELSE "
            f"ALTER ROLE {sql_ident(requested_admin_user)} WITH LOGIN CREATEDB CREATEROLE PASSWORD {sql_lit(requested_admin_password)}; "
            "END IF; "
            "END $$;"
        )
        rc, _ = db_local_postgres_exec(bootstrap_sql)
        if rc == 0:
            append_log(f"Bootstrap succeeded. Using requested admin user '{requested_admin_user}'.")
            admin_user = requested_admin_user
            admin_password = requested_admin_password
        else:
            set_error(
                "PostgreSQL admin login failed and bootstrap could not create the requested admin role. "
                "Provide an existing admin account with CREATEROLE and CREATEDB privileges."
            )
            return False

    if admin_user != requested_admin_user:
        append_log(f"Requested admin user '{requested_admin_user}' was not available. Creating/updating it...")
        admin_role_sql = (
            "DO $$ "
            "BEGIN "
            f"IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = {sql_lit(requested_admin_user)}) THEN "
            f"CREATE ROLE {sql_ident(requested_admin_user)} LOGIN CREATEDB CREATEROLE PASSWORD {sql_lit(requested_admin_password)}; "
            "ELSE "
            f"ALTER ROLE {sql_ident(requested_admin_user)} WITH LOGIN CREATEDB CREATEROLE PASSWORD {sql_lit(requested_admin_password)}; "
            "END IF; "
            "END $$;"
        )
        rc, _ = db_admin_exec(values, admin_role_sql, admin_user=admin_user, admin_password=admin_password)
        if rc == 0:
            append_log(f"Admin role '{requested_admin_user}' is ready and will be used.")
            admin_user = requested_admin_user
            admin_password = requested_admin_password
        else:
            append_log(
                f"WARNING: Could not create requested admin role '{requested_admin_user}'. "
                f"Continuing with '{admin_user}'."
            )

    append_log(f"Ensuring DB role '{db_user}' exists...")
    role_sql = (
        "DO $$ "
        "BEGIN "
        f"IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = {sql_lit(db_user)}) THEN "
        f"CREATE ROLE {sql_ident(db_user)} LOGIN PASSWORD {sql_lit(db_password)}; "
        "ELSE "
        f"ALTER ROLE {sql_ident(db_user)} WITH LOGIN PASSWORD {sql_lit(db_password)}; "
        "END IF; "
        "END $$;"
    )
    rc, _ = db_admin_exec(values, role_sql, admin_user=admin_user, admin_password=admin_password)
    if rc != 0:
        set_error("Failed to create/update DB role. Ensure admin user has CREATEROLE privilege.")
        return False

    append_log(f"Ensuring database '{db_name}' exists...")
    exists_sql = f"SELECT 1 FROM pg_database WHERE datname = {sql_lit(db_name)};"
    rc, out = db_admin_exec(values, exists_sql, admin_user=admin_user, admin_password=admin_password)
    if rc != 0:
        set_error("Failed to check database existence.")
        return False

    if "1" not in out:
        create_db_sql = f"CREATE DATABASE {sql_ident(db_name)} OWNER {sql_ident(db_user)};"
        rc, _ = db_admin_exec(values, create_db_sql, admin_user=admin_user, admin_password=admin_password)
        if rc != 0:
            set_error("Failed to create database. Ensure admin user has CREATEDB privilege.")
            return False
        append_log(f"Database '{db_name}' created.")
    else:
        append_log(f"Database '{db_name}' already exists.")

    grant_db_sql = f"GRANT ALL PRIVILEGES ON DATABASE {sql_ident(db_name)} TO {sql_ident(db_user)};"
    rc, _ = db_admin_exec(values, grant_db_sql, admin_user=admin_user, admin_password=admin_password)
    if rc != 0:
        set_error("Failed to grant database privileges to application user.")
        return False

    schema_sql = (
        f"ALTER SCHEMA public OWNER TO {sql_ident(db_user)}; "
        f"GRANT ALL ON SCHEMA public TO {sql_ident(db_user)};"
    )
    rc, _ = db_admin_exec(values, schema_sql, database=db_name, admin_user=admin_user, admin_password=admin_password)
    if rc != 0:
        append_log("WARNING: Could not update public schema owner/permissions; continuing.")

    append_log("PostgreSQL prerequisites are ready.")
    return True


def wait_http_local(http_port: str, timeout_sec: int = 600) -> bool:
    url = f"http://localhost:{http_port}/"
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=5) as r:
                if 200 <= r.status < 400:
                    return True
        except Exception:
            pass
        time.sleep(3)
    return False


def wait_https_public(base_url: str, timeout_sec: int = 240) -> bool:
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(base_url, timeout=8) as r:
                if 200 <= r.status < 400:
                    return True
        except Exception:
            pass
        time.sleep(5)
    return False


def validate_form(v: dict) -> str:
    required = [
        "BASE_URL",
        "ADMIN_EMAIL",
        "DB_NAME",
        "DB_USER",
        "DB_PASSWORD",
        "DB_ADMIN_USER",
        "DB_ADMIN_PASSWORD",
    ]
    for key in required:
        if not v.get(key, "").strip():
            return f"{key} is required"

    if not (v["BASE_URL"].startswith("http://") or v["BASE_URL"].startswith("https://")):
        return "BASE_URL must start with http:// or https://"

    for p in ("HTTP_PORT", "MQTT_PORT", "DB_PORT"):
        try:
            n = int(v[p])
            if n < 1 or n > 65535:
                return f"{p} must be between 1 and 65535"
        except ValueError:
            return f"{p} must be a valid integer"

    if not v.get("CLOUDFLARE_TUNNEL_TOKEN", "").strip():
        return "CLOUDFLARE_TUNNEL_TOKEN is required so cloudflared can start automatically"

    return ""


def compose_worker(values: dict):
    with STATE_LOCK:
        STATE["running"] = True
        STATE["done"] = False
        STATE["error"] = ""
        STATE["phase"] = "initializing"
        STATE["logs"] = []
        STATE["base_url"] = values["BASE_URL"]
        STATE["http_port"] = values["HTTP_PORT"]

    append_log("Writing hmdm-server/.env...")
    write_env(ENV_FILE, values)
    append_log(f"Saved: {ENV_FILE}")

    if not docker_available():
        set_error("Docker daemon is not running or docker CLI is not installed.")
        append_log("ERROR: Docker is unavailable")
        return

    force_rebuild = to_bool(values.get("RESET_FROM_SCRATCH", "0"))
    profile_args = ["--profile", "tunnel"]

    if force_rebuild:
        set_phase("cleanup")
        append_log("Force rebuild is ON: removing containers, volumes (including DB), images, and build cache...")
        rc = run_compose_stream(profile_args + ["down", "--volumes", "--remove-orphans"], SERVER_DIR)
        if rc != 0:
            set_error(f"docker compose down --volumes failed (exit {rc})")
            return
        if not remove_project_images():
            set_error("Failed to remove one or more Docker images during force rebuild.")
            return
        if not prune_docker_builder_cache():
            set_error("Failed to prune Docker builder cache during force rebuild.")
            return
        append_log("Cleanup completed. Starting from scratch.")

    set_phase("db-prerequisites")
    append_log("Starting PostgreSQL container for DB pre-checks...")
    rc = run_compose_stream(profile_args + ["up", "-d", "postgres"], SERVER_DIR)
    if rc != 0:
        set_error(f"docker compose up -d postgres failed (exit {rc})")
        return
    if not ensure_db_prerequisites(values):
        return

    set_phase("building")
    append_log("Starting Docker build (Android APK + server)...")
    build_args = profile_args + ["build"]
    if force_rebuild:
        build_args.extend(["--no-cache", "--pull"])
    rc = run_compose_stream(build_args, SERVER_DIR)
    if rc != 0:
        set_error(f"docker compose build failed (exit {rc})")
        return
    
    apk_dir = ROOT_DIR / "hmdm-android" / "app" / "build" / "outputs" / "apk"
    apk_files = list(apk_dir.rglob("*.apk"))

    if not apk_files:
        append_log("APK not found on host, trying to copy from Docker...")
        subprocess.run([
            "docker", "cp",
            "hmdm:/app/build/outputs/apk/enterprise/release/app-enterprise-release.apk",
            str(apk_dir / "app-enterprise-release.apk")
        ])

        apk_files = list(apk_dir.rglob("*.apk"))

        if not apk_files:
            set_error("APK not found after build (even after docker copy).")
            return

    apk_path = apk_files[0]
    append_log(f"APK found: {apk_path}")
    apk_download_url = f"http://{HOST}:{PORT}/download-apk"
    append_log(f"APK ready. Downloading from: {apk_download_url}")

    set_phase("starting")
    append_log("Starting required containers (postgres, hmdm-server, cloudflared)...")
    rc = run_compose_stream(profile_args + ["up", "-d", "postgres", "hmdm", "cloudflared"], SERVER_DIR)
    if rc != 0:
        set_error(f"docker compose up -d failed (exit {rc})")
        return

    set_phase("health-check-local")
    append_log("Waiting for local server health check...")
    if not wait_http_local(values["HTTP_PORT"]):
        set_error("Local server health check failed (localhost not ready in time).")
        append_log("Tip: check logs with `docker logs -f hmdm-server`")
        return

    set_phase("health-check-public")
    append_log(f"Waiting for public URL: {values['BASE_URL']}")
    if not wait_https_public(values["BASE_URL"]):
        set_error(
            "Local server is up, but public URL is not reachable yet. "
            "Check DNS/Cloudflare tunnel/TLS and retry."
        )
        return

    set_phase("completed")
    append_log("Setup complete. Opening admin panel is now safe.")
    write_setup_done(values["BASE_URL"])
    set_done(values["BASE_URL"])


def parse_form_data(body: bytes) -> dict:
    raw = urllib.parse.parse_qs(body.decode("utf-8"), keep_blank_values=True)
    def g(k, d=""):
        return raw.get(k, [d])[0].strip()

    existing = load_env(ENV_FILE)

    values = {
        "BASE_URL": g("BASE_URL", existing.get("BASE_URL", "https://brothers-mdm.com")),
        "ADMIN_EMAIL": g("ADMIN_EMAIL", existing.get("ADMIN_EMAIL", "admin@example.com")),
        "HTTP_PORT": g("HTTP_PORT", existing.get("HTTP_PORT", "8080")),
        "MQTT_PORT": g("MQTT_PORT", existing.get("MQTT_PORT", "31000")),
        "DB_NAME": g("DB_NAME", existing.get("DB_NAME", "hmdm")),
        "DB_USER": g("DB_USER", existing.get("DB_USER", "hmdm")),
        "DB_PASSWORD": g("DB_PASSWORD", existing.get("DB_PASSWORD", "")),
        "DB_PORT": g("DB_PORT", existing.get("DB_PORT", "5432")),
        "DB_ADMIN_USER": g("DB_ADMIN_USER", existing.get("DB_ADMIN_USER", "")),
        "DB_ADMIN_PASSWORD": g("DB_ADMIN_PASSWORD", existing.get("DB_ADMIN_PASSWORD", "")),
        "HASH_SECRET": g("HASH_SECRET", existing.get("HASH_SECRET", secrets.token_urlsafe(24))),
        "JWT_SECRET": g("JWT_SECRET", existing.get("JWT_SECRET", secrets.token_hex(20))),
        "SECURE_ENROLLMENT": g("SECURE_ENROLLMENT", existing.get("SECURE_ENROLLMENT", "0")),
        "MQTT_AUTH": g("MQTT_AUTH", existing.get("MQTT_AUTH", "1")),
        "MQTT_MESSAGE_DELAY": g("MQTT_MESSAGE_DELAY", existing.get("MQTT_MESSAGE_DELAY", "0")),
        "SMTP_HOST": g("SMTP_HOST", existing.get("SMTP_HOST", "")),
        "SMTP_PORT": g("SMTP_PORT", existing.get("SMTP_PORT", "25")),
        "SMTP_SSL": g("SMTP_SSL", existing.get("SMTP_SSL", "0")),
        "SMTP_STARTTLS": g("SMTP_STARTTLS", existing.get("SMTP_STARTTLS", "0")),
        "SMTP_USERNAME": g("SMTP_USERNAME", existing.get("SMTP_USERNAME", "")),
        "SMTP_PASSWORD": g("SMTP_PASSWORD", existing.get("SMTP_PASSWORD", "")),
        "SMTP_FROM": g("SMTP_FROM", existing.get("SMTP_FROM", "")),
        "CLOUDFLARE_TUNNEL_TOKEN": g("CLOUDFLARE_TUNNEL_TOKEN", existing.get("CLOUDFLARE_TUNNEL_TOKEN", "")),
        "RESET_FROM_SCRATCH": "1" if g("RESET_FROM_SCRATCH", "") else "0",
    }
    return values


def render_page() -> str:
    existing = load_env(ENV_FILE)
    done = read_setup_done()

    base_url = html.escape(existing.get("BASE_URL", "https://brothers-mdm.com"))
    admin_email = html.escape(existing.get("ADMIN_EMAIL", "admin@example.com"))
    previous_setup_note = ""
    if done.get("completed"):
        previous_setup_note = (
            '<p class="note">Previous setup detected for URL: '
            + html.escape(done.get("base_url", ""))
            + '. You can run setup again.</p>'
        )

    def val(name: str, default: str) -> str:
        return html.escape(existing.get(name, default))

    return f"""<!doctype html>
<html>
<head>
<meta charset=\"utf-8\" />
<title>Brother Pharmamach MDM - First Setup ({APP_VERSION})</title>
<style>
body {{ font-family: sans-serif; max-width: 900px; margin: 1rem auto; padding: 0 1rem; }}
.grid {{ display: grid; grid-template-columns: 1fr 1fr; gap: 10px 14px; }}
label {{ display:block; font-size: .92rem; color: #333; margin-bottom: 3px; }}
input {{ width: 100%; padding: .5rem; box-sizing: border-box; }}
fieldset {{ border: 1px solid #ddd; border-radius: 8px; margin: 12px 0; }}
legend {{ padding: 0 6px; font-weight: 600; }}
button {{ padding: .7rem 1.2rem; }}
#logs {{ background:#111; color:#ddd; padding:.8rem; border-radius:8px; height:260px; overflow:auto; white-space:pre-wrap; }}
#status {{ margin: .7rem 0; font-weight: 600; }}
#doneBtn {{ display:none; margin-top:10px; }}
.note {{ color:#555; font-size:.9rem; }}
</style>
</head>
<body>
<h2>Brother Pharmamach MDM - Setup</h2>
<p class=\"note\">Version: {APP_VERSION}</p>
<p class=\"note\">Fill values once, submit, and wait for health checks.</p>
{previous_setup_note}

<form id=\"setupForm\">
<fieldset><legend>Server</legend>
<div class=\"grid\">
<div><label>BASE_URL *</label><input name=\"BASE_URL\" value=\"{base_url}\" required /></div>
<div><label>ADMIN_EMAIL *</label><input name=\"ADMIN_EMAIL\" value=\"{admin_email}\" required /></div>
<div><label>HTTP_PORT</label><input name=\"HTTP_PORT\" value=\"{val('HTTP_PORT','8080')}\" /></div>
<div><label>MQTT_PORT</label><input name=\"MQTT_PORT\" value=\"{val('MQTT_PORT','31000')}\" /></div>
</div>
</fieldset>

<fieldset><legend>Database</legend>
<p class="note">Use an existing PostgreSQL admin account (CREATEDB + CREATEROLE) for one-time setup. The installer will create/update the app DB user and DB if missing.</p>
<div class=\"grid\">
<div><label>DB_ADMIN_USER *</label><input name="DB_ADMIN_USER" value="{val('DB_ADMIN_USER','')}" required /></div>
<div><label>DB_ADMIN_PASSWORD *</label><input type="password" name="DB_ADMIN_PASSWORD" value="" required /></div>
<div><label>APP_DB_NAME *</label><input name="DB_NAME" value="{val('DB_NAME','hmdm')}" required /></div>
<div><label>APP_DB_USER *</label><input name="DB_USER" value="{val('DB_USER','hmdm')}" required /></div>
<div><label>APP_DB_PASSWORD *</label><input type="password" name="DB_PASSWORD" value="{val('DB_PASSWORD','')}" required /></div>
<div><label>DB_PORT</label><input name="DB_PORT" value="{val('DB_PORT','5432')}" /></div>
</div>
</fieldset>

<fieldset><legend>Security</legend>
<div class=\"grid\">
<div><label>HASH_SECRET</label><input name=\"HASH_SECRET\" value=\"{val('HASH_SECRET', secrets.token_urlsafe(24))}\" /></div>
<div><label>JWT_SECRET</label><input name=\"JWT_SECRET\" value=\"{val('JWT_SECRET', secrets.token_hex(20))}\" /></div>
<div><label>SECURE_ENROLLMENT (0/1)</label><input name=\"SECURE_ENROLLMENT\" value=\"{val('SECURE_ENROLLMENT','0')}\" /></div>
</div>
</fieldset>

<fieldset><legend>MQTT + SMTP + Tunnel</legend>
<div class=\"grid\">
<div><label>MQTT_AUTH</label><input name=\"MQTT_AUTH\" value=\"{val('MQTT_AUTH','1')}\" /></div>
<div><label>MQTT_MESSAGE_DELAY</label><input name=\"MQTT_MESSAGE_DELAY\" value=\"{val('MQTT_MESSAGE_DELAY','0')}\" /></div>
<div><label>SMTP_HOST</label><input name=\"SMTP_HOST\" value=\"{val('SMTP_HOST','')}\" /></div>
<div><label>SMTP_PORT</label><input name=\"SMTP_PORT\" value=\"{val('SMTP_PORT','25')}\" /></div>
<div><label>SMTP_SSL (0/1)</label><input name=\"SMTP_SSL\" value=\"{val('SMTP_SSL','0')}\" /></div>
<div><label>SMTP_STARTTLS (0/1)</label><input name=\"SMTP_STARTTLS\" value=\"{val('SMTP_STARTTLS','0')}\" /></div>
<div><label>SMTP_USERNAME</label><input name=\"SMTP_USERNAME\" value=\"{val('SMTP_USERNAME','')}\" /></div>
<div><label>SMTP_PASSWORD</label><input type=\"password\" name=\"SMTP_PASSWORD\" value=\"{val('SMTP_PASSWORD','')}\" /></div>
<div><label>SMTP_FROM</label><input name=\"SMTP_FROM\" value=\"{val('SMTP_FROM','')}\" /></div>
<div><label>CLOUDFLARE_TUNNEL_TOKEN *</label><input name=\"CLOUDFLARE_TUNNEL_TOKEN\" value=\"{val('CLOUDFLARE_TUNNEL_TOKEN','')}\" required /></div>
</div>
</fieldset>

<fieldset><legend>Build Mode</legend>
<p class=\"note\">Leave unchecked for normal setup. Enable only when you want a full reset and rebuild.</p>
<div>
<label><input type=\"checkbox\" name=\"RESET_FROM_SCRATCH\" value=\"1\" /> Rebuild from scratch (remove project containers/images, clear DB volume, then rebuild everything)</label>
</div>
</fieldset>

<button type=\"submit\" id=\"submitBtn\">Start Setup</button>
</form>

<div id=\"status\">Status: idle</div>
<div id=\"logs\"></div>
<a id=\"doneBtn\" href=\"#\" target=\"_blank\" rel=\"noopener\"><button>Continue to Admin Panel</button></a>

<script>
const form = document.getElementById('setupForm');
const logsEl = document.getElementById('logs');
const statusEl = document.getElementById('status');
const doneBtn = document.getElementById('doneBtn');
const submitBtn = document.getElementById('submitBtn');

form.addEventListener('submit', async (e) => {{
  e.preventDefault();
  submitBtn.disabled = true;
  doneBtn.style.display = 'none';
  logsEl.textContent = '';

  const fd = new FormData(form);
  const resp = await fetch('/start', {{ method: 'POST', body: new URLSearchParams(fd) }});
  const data = await resp.json();
  if (!data.ok) {{
    alert(data.error || 'Failed to start setup');
    submitBtn.disabled = false;
    return;
  }}

  pollStatus();
}});

async function pollStatus() {{
  const timer = setInterval(async () => {{
    const resp = await fetch('/status');
    const data = await resp.json();
    statusEl.textContent = 'Status: ' + data.phase + (data.error ? ' | ERROR: ' + data.error : '');
    logsEl.textContent = data.logs.join('\\n');
    logsEl.scrollTop = logsEl.scrollHeight;

    if (data.phase === "health-check-public" && !window.apkDownloaded) {{
        window.apkDownloaded = true;
        const link = document.createElement('a');
        link.href = '/download-apk';
        link.download = 'app-enterprise-release.apk';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
    }}

    if (data.done) {{
      clearInterval(timer);
      doneBtn.href = data.base_url;
      doneBtn.style.display = 'inline-block';
      submitBtn.disabled = true;
      statusEl.textContent = 'Status: completed';
    }}

    if (!data.running && data.error) {{
      clearInterval(timer);
      submitBtn.disabled = false;
    }}
  }}, 2000);
}}
</script>
</body>
</html>"""


class SetupHandler(BaseHTTPRequestHandler):
    def _send_json(self, payload: dict, code: int = 200):
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _send_html(self, body: str, code: int = 200):
        raw = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, fmt, *args):
        # Keep terminal clean; progress is shown in web UI.
        return

    def do_GET(self):
        if self.path == "/":
            self._send_html(render_page())
            return

        if self.path == "/status":
            with STATE_LOCK:
                payload = {
                    "running": STATE["running"],
                    "done": STATE["done"],
                    "error": STATE["error"],
                    "phase": STATE["phase"],
                    "logs": STATE["logs"],
                    "base_url": STATE["base_url"],
                }
            self._send_json(payload)
            return

        if self.path == "/download-apk":
            apk_path = ROOT_DIR / "hmdm-android" / "app" / "build" / "outputs" / "apk" / "enterprise" / "release" / "app-enterprise-release.apk"
            if not apk_path.exists():
                self.send_response(404)
                self.send_header("Content-Type", "text/plain")
                self.end_headers()
                self.wfile.write(b"APK not found.")
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", "attachment; filename=app-enterprise-release.apk")
            self.send_header("Content-Length", str(apk_path.stat().st_size))
            self.end_headers()
            with open(apk_path, "rb") as f:
                while True:
                    chunk = f.read(8192)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            return

        self._send_json({"ok": False, "error": "Not found"}, code=404)

    def do_POST(self):
        if self.path != "/start":
            self._send_json({"ok": False, "error": "Not found"}, code=404)
            return

        with STATE_LOCK:
            if STATE["running"]:
                self._send_json({"ok": False, "error": "Setup is already running."}, code=409)
                return

        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        values = parse_form_data(body)
        err = validate_form(values)
        if err:
            self._send_json({"ok": False, "error": err}, code=400)
            return

        t = threading.Thread(target=compose_worker, args=(values,), daemon=True)
        t.start()
        self._send_json({"ok": True})


def can_bind(host: str, port: int) -> bool:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind((host, port))
    except OSError:
        return False
    finally:
        s.close()
    return True


def main():
    if not SERVER_DIR.exists():
        print(f"ERROR: Missing directory: {SERVER_DIR}")
        sys.exit(1)

    if not can_bind(HOST, PORT):
        print(f"ERROR: Port {PORT} is already in use. Stop the other process and retry.")
        sys.exit(1)

    url = f"http://{HOST}:{PORT}/"
    print(f"Starting setup web UI at: {url}")
    print("Opening browser...")

    # Best-effort browser launch
    try:
        webbrowser.open(url, new=2, autoraise=True)
    except Exception:
        pass

    server = ThreadingHTTPServer((HOST, PORT), SetupHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nSetup server stopped.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
