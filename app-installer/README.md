# Windows USB App Installer

This folder contains an automatic Windows installer flow for the APK in this folder.

## What it does

- Auto-installs required dependencies on Windows:
  - Node.js LTS (via `winget`, or silent MSI fallback if `winget` is unavailable)
  - Android ADB platform-tools (downloaded into `tools/platform-tools`)
- Starts a local web UI at `http://localhost:4789`
- Detects Android devices connected by USB (`adb devices -l`)
- Installs `app-enterprise-release.apk` (admin app)
- Installs all additional `.apk` files found in this folder (except `app-enterprise-release.apk`) as normal apps
- Sets Device Owner using the admin receiver class from `AdminReceiverClass.txt`

## Run

Double-click:

- `start-installer.bat`

or run in PowerShell:

- `./bootstrap-and-run.ps1`

## Admin Receiver Class

`AdminReceiverClass.txt` is read by the installer at runtime.

## Notes

- If a phone shows `unauthorized`, unlock it and approve the USB debugging prompt.
- Keep USB debugging enabled in developer options.
