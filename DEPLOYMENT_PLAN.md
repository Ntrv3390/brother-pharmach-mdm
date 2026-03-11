# Brother Pharmach MDM Deployment Plan

This plan covers both `hmdm-server` and `hmdm-android` for deployment on a fresh machine.

## 1. Pre-Deployment Checklist

- Install Java 17 (Android build) and Java 8+ with Maven for server build.
- Install Android SDK + build tools for APK builds.
- Install PostgreSQL client (`psql`) on the deployment/build host.
- Install Tomcat 9 if deploying WAR directly.
- Install Docker + Docker Compose if deploying containerized server.
- Prepare production secrets (DB password, JWT secret, hash secret, keystore passwords).

## 2. Required Environment Variables

For Android deploy script (`deploy_updated_apk.sh`):

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
- `SERVER_FILES_DIR`
- `APK_BASE_URL`
- Optional: `PKG_NAME` (default `com.brother.pharmach.mdm.launcher`)

For full deploy script (`build_and_deploy_all.sh`):

- `TOMCAT_HOME`
- Optional tunnel controls:
  - `START_CLOUDFLARE_TUNNEL=1`
  - `CLOUDFLARE_TUNNEL_ID`
  - `CLOUDFLARE_CONFIG_FILE`

For keystore generation (`generate_keystore.sh`):

- `STORE_PASSWORD`
- `KEY_PASSWORD`
- `KEY_DNAME`
- Optional: `KEYSTORE_PATH`, `KEY_ALIAS`, `KEY_VALIDITY_DAYS`

## 3. Android Release Signing

- `hmdm-android/gradle.properties` now uses portable release properties:
  - `RELEASE_STORE_FILE=brother-pharmach-release.jks`
  - `RELEASE_KEY_ALIAS=brotherpharmach`
- Set `RELEASE_STORE_PASSWORD` and `RELEASE_KEY_PASSWORD` locally (do not commit real secrets).

## 4. Package Name Alignment

- Android app package is `com.brother.pharmach.mdm.launcher`.
- SQL seed data updated to use this package and admin receiver:
  - `com.brother.pharmach.mdm.launcher`
  - `com.brother.pharmach.mdm.launcher.AdminReceiver`

## 5. Deploy Paths

### Option A: Docker deployment

1. Configure `hmdm-server/.env` from `hmdm-server/.env.example`.
2. Start services:
   - `cd hmdm-server`
   - `docker compose up -d --build`

### Option B: WAR + Tomcat deployment

1. Build APK and update server files/DB metadata:
   - `./deploy_updated_apk.sh`
2. Build and deploy WAR:
   - `./build_and_deploy_all.sh`

## 6. Post-Deployment Validation

- Open admin panel and confirm login page.
- Verify application entry exists for `com.brother.pharmach.mdm.launcher`.
- Enroll a test device and confirm push/config update works.
- Verify APK URL and hash in `applicationversions` are updated.

## 7. Known Remaining Hardening Tasks

- Rotate all secrets currently using defaults in runtime environments.
- Add TLS reverse proxy (Nginx/Caddy) for production HTTPS.
- Resolve file ownership in `hmdm-server/target` paths before Maven builds when needed.
- If full backend namespace rebrand is required (`com.hmdm` -> `com.brother...`), do it as a dedicated refactor project (not as a hot deploy change).
