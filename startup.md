# Brother Pharmach MDM - Windows Startup Guide

This guide is for a **Windows server** where the full project folder is already copied.

Goal:
- Build Android APK + server in Docker
- Start database + server + tunnel
- Use your own APK URL in QR code (not h-mdm.com)

## 1. What to install on Windows

Install these first:
- Docker Desktop (latest)
- Python 3.10 or newer
- Git (optional, only if you pull updates)

After installing:
- Open Docker Desktop and wait until it says Docker is running.
- Open Command Prompt and check:
  - `docker --version`
  - `python --version` or `py --version`

## 2. Put project in one folder

Example folder:
- `C:\hmdm`

Inside this folder you should see files like:
- `setup.py`
- `start-setup.bat`
- `hmdm-server\docker-compose.yml`

## 3. Run setup (one command)

Open Command Prompt in project root and run:
- `start-setup.bat`

This will:
- Open setup page in browser
- Ask for env values (BASE_URL, DB user/password, ports, etc.)
- Run Docker build
- Run Docker up
- Show live status logs in browser
- Show button **Continue to Admin Panel** when healthy

## 4. Fill setup form correctly

Important values:
- `BASE_URL`: your public URL (example: `https://brothers-mdm.com`)
- `DB_PASSWORD`: strong password
- `CLOUDFLARE_TUNNEL_TOKEN`: optional (needed if using cloudflared tunnel)

Then click **Start Setup**.

Wait until status becomes completed.

## 5. Open admin panel

When setup is done, click:
- **Continue to Admin Panel**

This opens your MDM panel URL.

## 6. How QR enrollment now works

After setup:
- APK is built from your code inside Docker
- APK is copied to server files directory
- DB is updated with local APK URL and APK hash
- QR code uses your URL:
  - `https://your-domain/files/brother-pharmach-mdm.apk`

So device will not download from h-mdm.com.

## 7. One-time setup rule

Setup is designed to run once.

If setup page says already completed:
- delete file:
  - `hmdm-server\.setup_completed.json`
- run again:
  - `start-setup.bat`

## 8. Daily operations

Start containers:
- `cd hmdm-server`
- `docker compose up -d`

Stop containers:
- `cd hmdm-server`
- `docker compose down`

See logs:
- `docker logs -f hmdm-server`

## 9. Update project later

When code changes:
1. Pull/copy latest project files
2. Run from project root:
   - `start-setup.bat`
3. In setup page, click Start Setup again (only if one-time marker removed)

## 10. Troubleshooting

If browser page does not open:
- Manually open: `http://127.0.0.1:8765`

If Docker build fails:
- Make sure Docker Desktop is running
- Check internet access for Gradle/Maven downloads
- Check logs shown in setup page

If public URL is not healthy:
- Check DNS points to server
- Check Cloudflare tunnel token/config (if used)
- Check firewall rules for required ports
