const http = require("http");
const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");

const PORT = 4789;
const APP_DIR = __dirname;
const UI_FILE = path.join(APP_DIR, "ui", "index.html");
const APK_FILE = path.join(APP_DIR, "app-enterprise-release.apk");
const ADMIN_FILE = path.join(APP_DIR, "AdminReceiverClass.txt");
const DEFAULT_ADMIN_COMPONENT =
  "com.brother.pharmach.mdm.launcher/.AdminReceiver";
let logcatProcess = null;
function resolveAdbPath() {
  const envPath = (process.env.APP_INSTALLER_ADB_PATH || "").trim();
  if (envPath && fs.existsSync(envPath)) {
    return envPath;
  }

  const bundledAdb = path.join(APP_DIR, "tools", "platform-tools", "adb.exe");
  if (fs.existsSync(bundledAdb)) {
    return bundledAdb;
  }

  return "adb";
}

const ADB_EXE = resolveAdbPath();

const sseClients = new Set();
let installInProgress = false;
let removeInProgress = false;

function writeSse(res, chunk) {
  try {
    res.write(chunk);
    return true;
  } catch (error) {
    return false;
  }
}

function now() {
  return new Date().toISOString().replace("T", " ").replace("Z", "");
}

function startLogcat(serial) {
  if (logcatProcess) {
    sendLog("Logcat already running", "warn");
    return;
  }

  const args = serial ? ["-s", serial, "logcat"] : ["logcat"];

  logcatProcess = spawn(ADB_EXE, args, {
    windowsHide: true,
  });

  sendLog(
    `Started ADB logcat${serial ? ` for device ${serial}` : ""}...`,
    "info",
  );

  logcatProcess.stdout.on("data", (data) => {
    const lines = data.toString().split("\n");
    lines.forEach((line) => {
      if (line.trim()) sendLog(line, "info");
    });
  });

  logcatProcess.stderr.on("data", (data) => {
    sendLog(data.toString(), "error");
  });

  logcatProcess.on("close", () => {
    sendLog("Logcat stopped", "warn");
    logcatProcess = null;
  });
}

function stopLogcat() {
  if (logcatProcess) {
    logcatProcess.kill();
    logcatProcess = null;
    sendLog("Stopped ADB logcat", "warn");
  }
}

function sendLog(message, level = "info") {
  const payload = {
    timestamp: now(),
    level,
    message,
  };

  const encoded = `data: ${JSON.stringify(payload)}\n\n`;
  for (const res of [...sseClients]) {
    const ok = writeSse(res, encoded);
    if (!ok) {
      sseClients.delete(res);
    }
  }

  const line = `[${payload.timestamp}] [${level.toUpperCase()}] ${message}`;
  console.log(line);
}

// Keep event-stream connections alive and clean up stale sockets.
setInterval(() => {
  for (const res of [...sseClients]) {
    const ok = writeSse(res, ": ping\n\n");
    if (!ok) {
      sseClients.delete(res);
    }
  }
}, 15000);

function ensureAdminFile() {
  if (!fs.existsSync(ADMIN_FILE)) {
    fs.writeFileSync(ADMIN_FILE, `${DEFAULT_ADMIN_COMPONENT}\n`, "utf8");
  }
}

function getAdditionalApkFiles() {
  const primaryName = path.basename(APK_FILE).toLowerCase();
  const entries = fs.readdirSync(APP_DIR, { withFileTypes: true });

  return entries
    .filter((entry) => entry.isFile() && /\.apk$/i.test(entry.name))
    .map((entry) => path.join(APP_DIR, entry.name))
    .filter((apkPath) => path.basename(apkPath).toLowerCase() !== primaryName)
    .sort((a, b) => path.basename(a).localeCompare(path.basename(b)));
}

function readAdminComponent() {
  ensureAdminFile();
  const text = fs.readFileSync(ADMIN_FILE, "utf8").trim();
  if (!text) {
    throw new Error("AdminReceiverClass.txt is empty.");
  }
  return text;
}

function runAdb(args, timeoutMs = 120000) {
  return new Promise((resolve) => {
    const child = spawn(ADB_EXE, args, {
      windowsHide: true,
    });

    let stdout = "";
    let stderr = "";
    let timedOut = false;

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill();
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });

    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });

    child.on("error", (error) => {
      clearTimeout(timer);
      const hint =
        error && error.code === "ENOENT"
          ? `ADB executable not found: ${ADB_EXE}. Run start-installer.bat so platform-tools can be installed automatically.`
          : "";
      resolve({
        code: 1,
        stdout,
        stderr: `${stderr}\n${error.message}${hint ? `\n${hint}` : ""}`,
        timedOut: false,
      });
    });

    child.on("close", (code) => {
      clearTimeout(timer);
      resolve({
        code: timedOut ? 1 : code,
        stdout,
        stderr,
        timedOut,
      });
    });
  });
}

async function listDevices() {
  const result = await runAdb(["devices", "-l"], 30000);
  if (result.code !== 0) {
    const errorText = (result.stderr || result.stdout || "").trim();
    throw new Error(errorText || "Unable to run adb devices.");
  }

  const lines = result.stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(
      (line) => line.length > 0 && !line.startsWith("List of devices attached"),
    );

  const devices = lines.map((line) => {
    const parts = line.split(/\s+/);
    const serial = parts[0];
    const state = parts[1] || "unknown";
    return {
      serial,
      state,
      raw: line,
      authorized: state === "device",
    };
  });

  return devices;
}

function parseRequestBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk.toString();
      if (body.length > 1024 * 1024) {
        reject(new Error("Payload too large."));
        req.destroy();
      }
    });
    req.on("end", () => {
      if (!body) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(body));
      } catch (error) {
        reject(new Error("Invalid JSON payload."));
      }
    });
    req.on("error", reject);
  });
}

function replyJson(res, statusCode, payload) {
  const text = JSON.stringify(payload);
  res.writeHead(statusCode, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(text),
  });
  res.end(text);
}

function extractPackageFromComponent(adminComponent) {
  const slash = adminComponent.indexOf("/");
  if (slash <= 0) {
    throw new Error(
      `Invalid admin component in AdminReceiverClass.txt: ${adminComponent}`,
    );
  }
  return adminComponent.substring(0, slash);
}

async function isPackageInstalled(serial, packageName) {
  const result = await runAdb(
    ["-s", serial, "shell", "pm", "path", packageName],
    30000,
  );
  const out = `${result.stdout}\n${result.stderr}`.trim();
  return {
    installed: result.code === 0 && /package:/i.test(out),
    details: out,
  };
}

async function ensurePackageInstalled(serial, packageName) {
  const packageCheck = await isPackageInstalled(serial, packageName);
  if (!packageCheck.installed) {
    throw new Error(
      `Installed package check failed for ${packageName}. Ensure APK installed correctly before setting device owner. Details: ${packageCheck.details}`,
    );
  }
}

async function getUserCount(serial) {
  const result = await runAdb(
    ["-s", serial, "shell", "pm", "list", "users"],
    30000,
  );
  const out = `${result.stdout}\n${result.stderr}`;
  const matches = [...out.matchAll(/UserInfo\{\d+:/g)];
  return {
    known: result.code === 0,
    count: matches.length,
    raw: out.trim(),
  };
}

async function getAccountCount(serial) {
  const result = await runAdb(
    ["-s", serial, "shell", "dumpsys", "account"],
    40000,
  );
  const out = `${result.stdout}\n${result.stderr}`;
  if (result.code !== 0) {
    return {
      known: false,
      count: null,
      raw: out.trim(),
    };
  }

  if (/no accounts/i.test(out)) {
    return {
      known: true,
      count: 0,
      raw: out.trim(),
    };
  }

  const explicit = out.match(/Accounts:\s*(\d+)/i);
  if (explicit) {
    return {
      known: true,
      count: Number(explicit[1]),
      raw: out.trim(),
    };
  }

  const accountMatches = [...out.matchAll(/Account\s*\{/g)];
  return {
    known: true,
    count: accountMatches.length,
    raw: out.trim(),
  };
}

async function listInstalledPackageNames(serial) {
  const result = await runAdb(
    ["-s", serial, "shell", "pm", "list", "packages"],
    30000,
  );
  const out = `${result.stdout}\n${result.stderr}`.trim();
  if (result.code !== 0) {
    throw new Error(`Unable to list installed packages: ${out}`);
  }

  return out
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.startsWith("package:"))
    .map((line) => line.substring("package:".length).trim())
    .filter((line) => line.length > 0);
}

function apkPackageHint(apkPath) {
  const base = path.basename(apkPath, ".apk").toLowerCase();
  const withoutCopyIndex = base.replace(/\s*\(\d+\)\s*$/, "");
  const withoutVersion = withoutCopyIndex.replace(
    /[-_ ]v?\d+(\.\d+)+(.*)?$/,
    "",
  );
  const hint = withoutVersion.replace(/[^a-z0-9]/g, "");
  return hint;
}

async function resolveAdditionalPackagesOnDevice(serial, extraApks) {
  const installed = await listInstalledPackageNames(serial);
  const installedLower = installed.map((p) => ({
    original: p,
    lower: p.toLowerCase(),
  }));

  const resolved = [];
  const unresolved = [];

  for (const extraApk of extraApks) {
    const apkName = path.basename(extraApk);
    const hint = apkPackageHint(extraApk);
    if (!hint || hint.length < 4) {
      unresolved.push({ apkName, reason: "weak_hint" });
      continue;
    }

    const matches = installedLower.filter((pkg) => pkg.lower.includes(hint));
    if (matches.length === 1) {
      resolved.push({ apkName, packageName: matches[0].original });
      continue;
    }

    if (matches.length > 1) {
      unresolved.push({
        apkName,
        reason: "ambiguous",
        candidates: matches.map((m) => m.original),
      });
      continue;
    }

    unresolved.push({ apkName, reason: "not_found" });
  }

  return { resolved, unresolved };
}

function remediationStepsForBlockers(blockers) {
  const hasAccounts = blockers.includes("accounts_present");
  const hasUsers = blockers.includes("multiple_users_present");

  const steps = [];

  if (hasAccounts || hasUsers) {
    steps.push("Factory reset the phone.");
    steps.push(
      "On first boot, skip Google/Samsung/Xiaomi/Huawei account sign-in.",
    );
    steps.push("Enable Developer options and USB debugging.");
    steps.push("Connect USB, tap Allow USB debugging on phone.");
    steps.push("Run Install + Make Owner before adding any account.");
  }

  if (!steps.length) {
    steps.push(
      "No blocker detected. You can proceed with Install + Make Owner.",
    );
  }

  return steps;
}

async function precheckDeviceOwner(serial, adminComponent) {
  const packageName = extractPackageFromComponent(adminComponent);
  const state = await runAdb(["-s", serial, "get-state"], 20000);
  const stateText = state.stdout.trim();

  if (state.code !== 0 || stateText !== "device") {
    return {
      serial,
      ready: false,
      blockers: ["device_not_ready"],
      observed: {
        state: stateText || "unknown",
      },
      steps: [
        "Unlock phone screen.",
        "Enable USB debugging.",
        "Tap Allow USB debugging when prompted.",
        "Click Detect Devices again.",
      ],
    };
  }

  const [pkg, users, accounts] = await Promise.all([
    isPackageInstalled(serial, packageName),
    getUserCount(serial),
    getAccountCount(serial),
  ]);

  const blockers = [];
  if (users.known && users.count > 1) blockers.push("multiple_users_present");
  if (accounts.known && accounts.count > 0) blockers.push("accounts_present");

  return {
    serial,
    ready: blockers.length === 0,
    blockers,
    observed: {
      packageInstalled: pkg.installed,
      users: users.known ? users.count : "unknown",
      accounts: accounts.known ? accounts.count : "unknown",
    },
    steps: remediationStepsForBlockers(blockers),
  };
}

async function getExistingDeviceOwner(serial) {
  const result = await runAdb(
    ["-s", serial, "shell", "cmd", "device_policy", "get-device-owner"],
    30000,
  );
  const output = `${result.stdout}\n${result.stderr}`.trim();
  
  // Device owner might be shown as "Device owner: com.package/.admin"
  // or in other formats depending on Android version
  if (
    result.code === 0 && 
    output &&
    output !== "No device owner set" &&
    !output.toLowerCase().includes("error") &&
    output.length > 5
  ) {
    return output;
  }
  
  return null;
}

async function setDeviceOwnerWithFallbacks(serial, adminComponent) {
  const attempts = [
    ["-s", serial, "shell", "dpm", "set-device-owner", adminComponent],
    [
      "-s",
      serial,
      "shell",
      "dpm",
      "set-device-owner",
      "--user",
      "current",
      adminComponent,
    ],
    [
      "-s",
      serial,
      "shell",
      "dpm",
      "set-device-owner",
      "--user",
      "0",
      adminComponent,
    ],
  ];

  let lastCombined = "";
  for (let i = 0; i < attempts.length; i += 1) {
    const args = attempts[i];
    const label = args.slice(4).join(" ");
    sendLog(`[${serial}] Trying: dpm ${label}`);

    const result = await runAdb(args, 60000);
    const combined = `${result.stdout}\n${result.stderr}`.trim();
    lastCombined = combined;

    if (result.code === 0) {
      return { ok: true, output: combined };
    }

    // Check for various "already owner" patterns
    if (
      /already has a device owner|already set.*device owner|@ProvisioningPreCondition 99/i.test(combined)
    ) {
      return { ok: true, alreadyOwner: true, output: combined };
    }
  }

  return { ok: false, output: lastCombined };
}

async function installOnDevice(serial, adminComponent) {
  sendLog(`[${serial}] Preparing device for installation...`);
  sendLog(
    `[${serial}] If your phone asks for USB debugging authorization, tap Allow now.`,
    "warn",
  );

  const state = await runAdb(["-s", serial, "get-state"], 20000);
  const stateText = state.stdout.trim();
  if (state.code !== 0 || stateText !== "device") {
    throw new Error(
      `Device is not ready (${stateText || state.stderr.trim() || "unknown state"}).`,
    );
  }

  sendLog(`[${serial}] Installing APK...`);
  const installResult = await runAdb(
    ["-s", serial, "install", "-r", APK_FILE],
    180000,
  );
  if (installResult.code !== 0) {
    throw new Error(
      `APK install failed: ${(installResult.stderr || installResult.stdout).trim()}`,
    );
  }

  const extraApks = getAdditionalApkFiles();
  if (extraApks.length > 0) {
    sendLog(`[${serial}] Installing ${extraApks.length} additional app(s)...`);
    for (const extraApk of extraApks) {
      const apkName = path.basename(extraApk);
      sendLog(`[${serial}] Installing extra app: ${apkName}`);

      const extraResult = await runAdb(
        ["-s", serial, "install", "-r", extraApk],
        180000,
      );
      if (extraResult.code !== 0) {
        throw new Error(
          `Additional app install failed (${apkName}): ${(extraResult.stderr || extraResult.stdout).trim()}`,
        );
      }
    }
    sendLog(`[${serial}] Additional app installation complete.`, "success");
  }

  sendLog(`[${serial}] APK installed. Setting Device Owner: ${adminComponent}`);

  const packageName = extractPackageFromComponent(adminComponent);
  await ensurePackageInstalled(serial, packageName);

  // Ignore failure here; this simply clears old active admin state if present.
  await runAdb(
    ["-s", serial, "shell", "dpm", "remove-active-admin", adminComponent],
    30000,
  );

  const ownerResult = await setDeviceOwnerWithFallbacks(serial, adminComponent);
  const combined = ownerResult.output || "";

  if (!ownerResult.ok) {
    if (
      /already some accounts on the device|not allowed to set the device owner/i.test(
        combined,
      )
    ) {
      throw new Error(
        `Device owner blocked by Android policy (accounts/users already present). ${combined}`,
      );
    }

    if (/unknown admin/i.test(combined)) {
      throw new Error(
        `Device owner failed: admin component not recognized on device. Check AdminReceiverClass.txt and APK package/class. ${combined}`,
      );
    }

    // Error code 99 or other provisioning conflicts — suggest factory reset
    if (/@ProvisioningPreCondition 99|PRECONDITION_NOT_DEVICE_OWNER|provisioning|blocker/i.test(combined)) {
      const existing = await getExistingDeviceOwner(serial);
      if (existing) {
        throw new Error(
          `Device already has a device owner (${existing}). Factory reset required to change device owner.`,
        );
      }
      throw new Error(
        `Device provisioning conflict (error 99). This typically means: another device owner is set, accounts are configured, or a managed profile exists. Factory reset the device and skip all account sign-in during setup. ${combined}`,
      );
    }

    throw new Error(`Device owner command failed: ${combined}`);
  }

  if (ownerResult.alreadyOwner) {
    sendLog(`[${serial}] Device owner already configured.`, "success");
    return;
  }

  if (!/Active admin component set|Success/i.test(combined)) {
    sendLog(
      `[${serial}] dpm command completed, but response was unusual: ${combined}`,
      "warn",
    );
  } else {
    sendLog(`[${serial}] Device owner set successfully.`, "success");
  }
}

async function handleInstallRequest(res, requestedSerials) {
  if (installInProgress) {
    replyJson(res, 409, {
      ok: false,
      error: "An install is already in progress.",
    });
    return;
  }

  if (!fs.existsSync(APK_FILE)) {
    replyJson(res, 400, {
      ok: false,
      error:
        "APK not found. Expected app-enterprise-release.apk in app-installer folder.",
    });
    return;
  }

  let adminComponent;
  try {
    adminComponent = readAdminComponent();
  } catch (error) {
    replyJson(res, 400, { ok: false, error: error.message });
    return;
  }

  let devices;
  try {
    devices = await listDevices();
  } catch (error) {
    replyJson(res, 500, { ok: false, error: error.message });
    return;
  }

  const eligible = devices.filter((d) => d.authorized);
  if (eligible.length === 0) {
    replyJson(res, 400, {
      ok: false,
      error:
        "No authorized devices detected. Connect phone, enable USB debugging, and tap Allow.",
    });
    return;
  }

  const selected =
    Array.isArray(requestedSerials) && requestedSerials.length > 0
      ? eligible.filter((d) => requestedSerials.includes(d.serial))
      : eligible;

  if (selected.length === 0) {
    replyJson(res, 400, {
      ok: false,
      error: "No valid authorized devices selected.",
    });
    return;
  }

  installInProgress = true;
  sendLog(`Starting install flow for ${selected.length} device(s).`, "info");

  const results = [];
  for (const device of selected) {
    try {
      await installOnDevice(device.serial, adminComponent);
      results.push({ serial: device.serial, ok: true });
    } catch (error) {
      const reason = error && error.message ? error.message : String(error);
      sendLog(`[${device.serial}] ${reason}`, "error");

      const lowered = reason.toLowerCase();
      const steps = [];
      if (
        lowered.includes("accounts/users already present") ||
        lowered.includes("already some accounts") ||
        lowered.includes("provisioning conflict") ||
        lowered.includes("error 99") ||
        lowered.includes("@provisioningprecondition 99")
      ) {
        steps.push("Factory reset the device (Settings > System > Reset options > Erase all data).");
        steps.push("On first boot, do NOT sign in to Google/Samsung/Xiaomi/Huawei account.");
        steps.push("Skip all email and account setup.");
        steps.push("Enable Developer options: tap Build Number 7 times in About Phone.");
        steps.push("Enable USB Debugging in Developer options.");
        steps.push("Connect USB cable and tap Allow when prompted on phone.");
        steps.push("Run Install + Make Owner immediately before adding any account.");
      } else if (
        lowered.includes("admin component not recognized") ||
        lowered.includes("unknown admin")
      ) {
        steps.push(
          "Confirm AdminReceiverClass.txt matches manifest receiver class.",
        );
        steps.push("Reinstall the APK and retry.");
      }

      if (steps.length > 0) {
        sendLog(`[${device.serial}] Required steps:`, "warn");
        steps.forEach((step, index) => {
          sendLog(`[${device.serial}] ${index + 1}. ${step}`, "warn");
        });
      }

      results.push({ serial: device.serial, ok: false, error: reason, steps });
    }
  }

  installInProgress = false;

  const successCount = results.filter((r) => r.ok).length;
  sendLog(
    `Install flow finished. Success: ${successCount}/${results.length}.`,
    "info",
  );

  replyJson(res, 200, {
    ok: true,
    results,
    adminComponent,
  });
}

async function removeAppsOnDevice(serial, adminComponent) {
  sendLog(`[${serial}] Starting app removal flow...`);

  const state = await runAdb(["-s", serial, "get-state"], 20000);
  const stateText = state.stdout.trim();
  if (state.code !== 0 || stateText !== "device") {
    throw new Error(
      `Device is not ready (${stateText || state.stderr.trim() || "unknown state"}).`,
    );
  }

  const adminPackage = extractPackageFromComponent(adminComponent);
  let adminRemovalRestricted = false;

  // Try removing active admin in both default and explicit user modes.
  const removeAdminAttempts = [
    ["-s", serial, "shell", "dpm", "remove-active-admin", adminComponent],
    [
      "-s",
      serial,
      "shell",
      "dpm",
      "remove-active-admin",
      "--user",
      "0",
      adminComponent,
    ],
  ];

  let adminRemovalMessage = "";
  for (const args of removeAdminAttempts) {
    const result = await runAdb(args, 30000);
    const combined = `${result.stdout}\n${result.stderr}`.trim();
    if (combined) {
      adminRemovalMessage = combined;
    }

    if (result.code === 0 || /success|removed/i.test(combined)) {
      sendLog(`[${serial}] Admin status removed (or not active).`, "success");
      adminRemovalMessage = combined;
      break;
    }
  }

  if (adminRemovalMessage) {
    if (
      /not test.?only admin|securityexception|unknown admin/i.test(
        adminRemovalMessage,
      )
    ) {
      adminRemovalRestricted = true;
      sendLog(
        `[${serial}] Admin removal may be restricted by Android policy: ${adminRemovalMessage}`,
        "warn",
      );
    }
  }

  const uninstallTargets = new Set([adminPackage]);
  const extraApks = getAdditionalApkFiles();

  try {
    const extraPackages = await resolveAdditionalPackagesOnDevice(
      serial,
      extraApks,
    );
    for (const item of extraPackages.resolved) {
      uninstallTargets.add(item.packageName);
    }

    for (const unresolved of extraPackages.unresolved) {
      const detail = unresolved.candidates
        ? ` Candidates: ${unresolved.candidates.join(", ")}`
        : "";
      sendLog(
        `[${serial}] Could not auto-resolve package for ${unresolved.apkName} (${unresolved.reason}).${detail}`,
        "warn",
      );
    }
  } catch (error) {
    sendLog(
      `[${serial}] Could not resolve additional packages automatically: ${error.message}`,
      "warn",
    );
  }

  const removed = [];
  const failed = [];

  for (const packageName of uninstallTargets) {
    sendLog(`[${serial}] Uninstalling package: ${packageName}`);
    const uninstall = await runAdb(
      ["-s", serial, "uninstall", packageName],
      60000,
    );
    const combined = `${uninstall.stdout}\n${uninstall.stderr}`.trim();
    if (uninstall.code === 0 && /success/i.test(combined)) {
      removed.push(packageName);
      continue;
    }

    if (/unknown package|not installed/i.test(combined)) {
      removed.push(packageName);
      continue;
    }

    let failureReason = combined || "Unknown uninstall error";
    if (
      packageName === adminPackage &&
      adminRemovalRestricted &&
      /delete_failed_internal_error/i.test(failureReason)
    ) {
      failureReason = `Admin app cannot be removed via adb because it is an active non-test device admin/device owner on this device. ${failureReason}`;
    }

    failed.push({ packageName, error: failureReason });
    sendLog(
      `[${serial}] Uninstall failed for ${packageName}: ${failureReason}`,
      "error",
    );
  }

  if (failed.length > 0) {
    const firstError = failed[0].error || "Uninstall failed";
    throw new Error(
      `Some apps could not be removed. Removed ${removed.length}, failed ${failed.length}. First error: ${firstError}`,
    );
  }

  sendLog(
    `[${serial}] App removal flow complete. Removed ${removed.length} package(s).`,
    "success",
  );
}

async function handleRemoveRequest(res, requestedSerials) {
  if (installInProgress) {
    replyJson(res, 409, {
      ok: false,
      error: "Cannot remove while install is in progress.",
    });
    return;
  }

  if (removeInProgress) {
    replyJson(res, 409, {
      ok: false,
      error: "A remove operation is already in progress.",
    });
    return;
  }

  let adminComponent;
  try {
    adminComponent = readAdminComponent();
  } catch (error) {
    replyJson(res, 400, { ok: false, error: error.message });
    return;
  }

  let devices;
  try {
    devices = await listDevices();
  } catch (error) {
    replyJson(res, 500, { ok: false, error: error.message });
    return;
  }

  const eligible = devices.filter((d) => d.authorized);
  if (eligible.length === 0) {
    replyJson(res, 400, {
      ok: false,
      error:
        "No authorized devices detected. Connect phone, enable USB debugging, and tap Allow.",
    });
    return;
  }

  const selected =
    Array.isArray(requestedSerials) && requestedSerials.length > 0
      ? eligible.filter((d) => requestedSerials.includes(d.serial))
      : eligible;

  if (selected.length === 0) {
    replyJson(res, 400, {
      ok: false,
      error: "No valid authorized devices selected.",
    });
    return;
  }

  removeInProgress = true;
  sendLog(`Starting remove flow for ${selected.length} device(s).`, "info");

  const results = [];
  for (const device of selected) {
    try {
      await removeAppsOnDevice(device.serial, adminComponent);
      results.push({ serial: device.serial, ok: true });
    } catch (error) {
      const reason = error && error.message ? error.message : String(error);
      sendLog(`[${device.serial}] ${reason}`, "error");

      const steps = [];
      const lowered = reason.toLowerCase();
      if (
        /not test.?only admin|securityexception|device owner|delete_failed_internal_error|cannot be removed via adb/.test(
          lowered,
        )
      ) {
        steps.push(
          "Open the admin app and use any built-in deactivation/remove-owner option if available.",
        );
        steps.push(
          "If removal is still blocked, factory reset the device (Android policy restriction).",
        );
        steps.push(
          "After reset, do not add accounts before install/remove operations.",
        );
      }

      if (steps.length === 0) {
        steps.push("Review the exact error in Live Logs for this device.");
        steps.push(
          "Ensure the device is unlocked and USB debugging is still authorized.",
        );
        steps.push(
          "Retry Remove Apps. If it fails again, factory reset may be required.",
        );
      }

      results.push({ serial: device.serial, ok: false, error: reason, steps });
    }
  }

  removeInProgress = false;
  const successCount = results.filter((r) => r.ok).length;
  sendLog(
    `Remove flow finished. Success: ${successCount}/${results.length}.`,
    "info",
  );

  replyJson(res, 200, {
    ok: true,
    results,
    adminComponent,
  });
}

function serveIndex(res) {
  fs.readFile(UI_FILE, (error, data) => {
    if (error) {
      res.writeHead(500, { "Content-Type": "text/plain" });
      res.end("UI file missing: ui/index.html");
      return;
    }
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(data);
  });
}

const server = http.createServer(async (req, res) => {
  const requestUrl = new URL(req.url, `http://${req.headers.host}`);

  if (req.method === "GET" && requestUrl.pathname === "/") {
    serveIndex(res);
    return;
  }

  if (req.method === "GET" && requestUrl.pathname === "/events") {
    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
      "Access-Control-Allow-Origin": "*",
    });
    writeSse(res, "\n");
    sseClients.add(res);
    req.on("close", () => {
      sseClients.delete(res);
    });
    return;
  }

  if (req.method === "GET" && requestUrl.pathname === "/api/status") {
    let adminComponent = "";
    try {
      adminComponent = readAdminComponent();
    } catch (error) {
      adminComponent = "";
    }

    replyJson(res, 200, {
      ok: true,
      apkExists: fs.existsSync(APK_FILE),
      apkFile: path.basename(APK_FILE),
      adminFile: path.basename(ADMIN_FILE),
      adminComponent,
      adbPath: ADB_EXE,
      installInProgress,
    });
    return;
  }

  if (req.method === "GET" && requestUrl.pathname === "/api/devices") {
    try {
      const devices = await listDevices();
      replyJson(res, 200, { ok: true, devices });
    } catch (error) {
      replyJson(res, 500, { ok: false, error: error.message });
    }
    return;
  }

  if (req.method === "POST" && requestUrl.pathname === "/api/install") {
    try {
      const body = await parseRequestBody(req);
      await handleInstallRequest(res, body.serials || []);
    } catch (error) {
      installInProgress = false;
      replyJson(res, 500, { ok: false, error: error.message });
    }
    return;
  }

  if (req.method === "POST" && requestUrl.pathname === "/api/precheck") {
    try {
      const body = await parseRequestBody(req);
      const serials = Array.isArray(body.serials) ? body.serials : [];

      if (!serials.length) {
        replyJson(res, 400, {
          ok: false,
          error: "No device serials provided.",
        });
        return;
      }

      const adminComponent = readAdminComponent();
      const checks = [];
      for (const serial of serials) {
        checks.push(await precheckDeviceOwner(serial, adminComponent));
      }

      replyJson(res, 200, {
        ok: true,
        adminComponent,
        checks,
      });
    } catch (error) {
      replyJson(res, 500, { ok: false, error: error.message });
    }
    return;
  }

  if (req.method === "POST" && requestUrl.pathname === "/api/logcat/start") {
    try {
      const body = await parseRequestBody(req);
      startLogcat(body.serial || null);
      replyJson(res, 200, { ok: true });
    } catch (error) {
      replyJson(res, 500, { ok: false, error: error.message });
    }
    return;
  }

  if (req.method === "POST" && requestUrl.pathname === "/api/logcat/stop") {
    try {
      stopLogcat();
      replyJson(res, 200, { ok: true });
    } catch (error) {
      replyJson(res, 500, { ok: false, error: error.message });
    }
    return;
  }

  if (req.method === "POST" && requestUrl.pathname === "/api/remove") {
    try {
      const body = await parseRequestBody(req);
      await handleRemoveRequest(res, body.serials || []);
    } catch (error) {
      removeInProgress = false;
      replyJson(res, 500, { ok: false, error: error.message });
    }
    return;
  }

  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    });
    res.end();
    return;
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("Not found");
});

ensureAdminFile();
sendLog("App Installer server starting...");
sendLog(`ADB path: ${ADB_EXE}`);
sendLog(
  `Using admin receiver from ${path.basename(ADMIN_FILE)}: ${readAdminComponent()}`,
);

process.on("uncaughtException", (error) => {
  console.error("[FATAL] Uncaught exception:", error);
  try {
    sendLog(`Unhandled server error: ${error.message}`, "error");
  } catch (_) {
    // no-op
  }
  process.exit(1);
});

process.on("unhandledRejection", (reason) => {
  const message = reason && reason.message ? reason.message : String(reason);
  console.error("[FATAL] Unhandled rejection:", reason);
  try {
    sendLog(`Unhandled promise rejection: ${message}`, "error");
  } catch (_) {
    // no-op
  }
  process.exit(1);
});

server.listen(PORT, () => {
  sendLog(`Web UI ready at http://localhost:${PORT}`);
  sendLog(
    "Connect Android phone via USB, enable USB debugging, then click Detect Devices.",
  );
});
