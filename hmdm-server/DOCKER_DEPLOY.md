# Brother Pharmamach MDM – Docker Deployment Guide

## Overview

This guide explains how to deploy Brother Pharmamach MDM Server using Docker Desktop on Windows. After completing setup, clients and Android devices access the server at `http://YOUR_SERVER_IP:8080`.

**What you get:**

- `hmdm-server` – Tomcat 9 running the MDM web panel + REST API on port **8080**
- `postgres` – PostgreSQL 15 database with persistent storage
- Push notifications via embedded MQTT broker on port **31000**

---

## Prerequisites

| Requirement                                                                   | Notes                               |
| ----------------------------------------------------------------------------- | ----------------------------------- |
| [Docker Desktop for Windows](https://www.docker.com/products/docker-desktop/) | v4.x or newer                       |
| Windows 10/11 (64-bit)                                                        | WSL2 backend recommended            |
| 4 GB RAM minimum                                                              | 8 GB recommended for production     |
| Ports 8080 and 31000 available                                                | Or configure alternatives in `.env` |

---

## Step-by-Step Deployment

### 1. Install Docker Desktop

1. Download from: https://www.docker.com/products/docker-desktop/
2. Run the installer and restart your machine if prompted.
3. Open Docker Desktop and wait until it shows **"Docker Desktop is running"**.

### 2. Get the Project Files

Copy the `hmdm-server` folder to your machine (USB, network share, or `git clone`).

Open **PowerShell** and navigate to the `hmdm-server` directory:

```powershell
cd C:\path\to\hmdm-server
```

### 3. Configure Environment Variables

```powershell
Copy-Item .env.example .env
notepad .env
```

**Mandatory settings to update in `.env`:**

| Variable      | What to set                                                     |
| ------------- | --------------------------------------------------------------- |
| `BASE_URL`    | Public URL clients/devices use: e.g. `http://192.168.1.50:8080` |
| `DB_PASSWORD` | Strong password for the database                                |
| `HASH_SECRET` | Random secret (16+ chars)                                       |
| `JWT_SECRET`  | Random hex string (40 chars)                                    |

> **IMPORTANT:** `BASE_URL` must be the address Android devices will use to reach the server. If devices are on the same LAN, use the server's LAN IP.

Example minimal `.env`:

```env
DB_PASSWORD=MyStr0ngP@ssword
BASE_URL=http://192.168.1.50:8080
HASH_SECRET=mySecretKey-abc123xyz
JWT_SECRET=a4f2c1e8b3d7e0fa1234567890abcdef12345678
```

### 4. Build and Start

If you use Cloudflare Tunnel, run the helper script so the panel and tunnel start together:

```powershell
bash ./build_and_start_with_tunnel.sh
```

This script validates that `CLOUDFLARE_TUNNEL_TOKEN` is set and then runs Docker Compose with build.

You can still run Docker Compose directly:

```powershell
docker compose up -d --build
```

> **First build takes 5–15 minutes** as Maven downloads dependencies and compiles the project.

Monitor startup logs:

```powershell
docker compose logs -f hmdm
```

Wait until you see: `Server startup in [...] milliseconds`

### 5. Access the Web Panel

Open your browser and navigate to:

```
http://localhost:8080
```

Default credentials:

- **Username:** `admin`
- **Password:** `admin`

> ⚠️ Change the admin password immediately after first login!

---

## Firewall / Port Requirements

For Android devices to connect, the following ports must be reachable from the device network:

| Port    | Protocol | Purpose                |
| ------- | -------- | ---------------------- |
| `8080`  | TCP/HTTP | Web panel + device API |
| `31000` | TCP/MQTT | Push notifications     |

**Windows Firewall (PowerShell as Administrator):**

```powershell
New-NetFirewallRule -DisplayName "HMDM Web" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
New-NetFirewallRule -DisplayName "HMDM MQTT" -Direction Inbound -Protocol TCP -LocalPort 31000 -Action Allow
```

---

## Managing the Containers

| Action            | Command                       |
| ----------------- | ----------------------------- |
| Start             | `docker compose up -d`        |
| Stop              | `docker compose down`         |
| View logs         | `docker compose logs -f hmdm` |
| Restart hmdm only | `docker compose restart hmdm` |
| Check status      | `docker compose ps`           |

---

## Upgrading the Server

When a new version is available:

```powershell
# Pull latest code
git pull

# Rebuild image and restart (database is preserved in named volume)
docker compose up -d --build hmdm
```

Database and uploaded files are stored in Docker named volumes and **survive upgrades**.

---

## Backup

### Backup the Database

```powershell
docker exec hmdm-postgres pg_dump -U hmdm hmdm | Out-File -Encoding utf8 hmdm_backup.sql
```

### Backup Uploaded Files

```powershell
docker run --rm -v hmdm-server_hmdm_files:/data -v ${PWD}:/backup alpine `
    tar czf /backup/hmdm_files_backup.tar.gz -C /data .
```

### Restore the Database

```powershell
Get-Content hmdm_backup.sql | docker exec -i hmdm-postgres psql -U hmdm hmdm
```

---

## Troubleshooting

### Container won't start

```powershell
docker compose logs hmdm
docker compose logs postgres
```

### Port already in use

Edit `.env` and change `HTTP_PORT` or `MQTT_PORT`, then restart.

### Android devices can't connect

- Ensure `BASE_URL` in `.env` is set to the **server's LAN/public IP**, not `localhost`.
- Confirm ports 8080 and 31000 are open in Windows Firewall.
- MQTT URI on the Android app should be: `tcp://YOUR_SERVER_IP:31000`

### Database connection error on startup

The container waits for PostgreSQL automatically. If it keeps failing:

```powershell
docker compose restart
```

### Reset everything (clean install)

> ⚠️ This deletes all data!

```powershell
docker compose down -v
docker compose up -d --build
```

---

## Production Hardening Checklist

- [ ] Change `DB_PASSWORD`, `HASH_SECRET`, and `JWT_SECRET` to long random values
- [ ] Set `SECURE_ENROLLMENT=1` once all devices are enrolled
- [ ] Put a reverse proxy (nginx or Traefik) in front on port 443 with HTTPS/TLS
- [ ] Configure `SMTP_*` variables for password recovery emails
- [ ] Enable automatic daily database backups (scheduled `pg_dump`)
- [ ] Set `BASE_URL` to `https://` after configuring TLS
- [ ] Restrict Docker network exposure (don't expose 5432 externally)
- [ ] Regularly pull updated base images: `docker compose pull && docker compose up -d --build`

---

## Architecture

```
Windows Host
│
├── Docker Desktop
│   ├── [hmdm-server]  (:8080, :31000)
│   │    Tomcat 9 → launcher.war
│   │    Embedded MQTT broker
│   │    aapt for APK analysis
│   │
│   └── [postgres]  (internal only)
│        PostgreSQL 15
│
└── Named Volumes
     ├── postgres_data   (database files)
     ├── hmdm_files      (uploaded APKs, icons)
     ├── hmdm_plugins    (plugin data)
     └── hmdm_logs       (application logs)
```
