$ErrorActionPreference = "Stop"

function Write-Log {
    param([string]$Message)
    Write-Host ("[Installer] " + $Message)
}

function Refresh-Path {
    $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = $machinePath + ";" + $userPath
}

function Resolve-ScriptDir {
    if ($PSScriptRoot) {
        return $PSScriptRoot
    }

    if ($PSCommandPath) {
        return (Split-Path -Parent $PSCommandPath)
    }

    if ($MyInvocation -and $MyInvocation.MyCommand -and $MyInvocation.MyCommand.Path) {
        return (Split-Path -Parent $MyInvocation.MyCommand.Path)
    }

    return (Get-Location).Path
}

function Install-NodeFromMsi {
    param([string]$ScriptDir)

    $toolsDir = Join-Path $ScriptDir "tools"
    New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null

    $msiPath = Join-Path $toolsDir "node-lts-x64.msi"
    $nodeMsiUrl = "https://nodejs.org/dist/latest-v20.x/node-v20.19.5-x64.msi"

    Write-Log "Downloading Node.js LTS installer (MSI)..."
    Invoke-WebRequest -Uri $nodeMsiUrl -OutFile $msiPath

    Write-Log "Installing Node.js silently via MSI..."
    $install = Start-Process msiexec.exe -ArgumentList @("/i", $msiPath, "/qn", "/norestart") -Wait -PassThru
    if ($install.ExitCode -ne 0) {
        throw "Node.js MSI installation failed with exit code $($install.ExitCode)."
    }
}

function Ensure-Node {
    $node = Get-Command node -ErrorAction SilentlyContinue
    if ($node) {
        Write-Log "Node.js is already installed."
        return $node.Source
    }

    Write-Log "Node.js not found. Attempting automatic install..."
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if ($winget) {
        Write-Log "Using winget to install Node.js LTS..."
        winget install --id OpenJS.NodeJS.LTS --exact --silent --accept-package-agreements --accept-source-agreements
    } else {
        Write-Log "winget not found. Falling back to direct Node.js MSI install."
        Install-NodeFromMsi -ScriptDir $scriptDir
    }

    Refresh-Path
    $node = Get-Command node -ErrorAction SilentlyContinue
    if (-not $node) {
        $knownNode = "C:\Program Files\nodejs\node.exe"
        if (Test-Path $knownNode) {
            return $knownNode
        }
        throw "Node.js installation did not complete successfully."
    }

    Write-Log "Node.js installed successfully."
    return $node.Source
}

function Ensure-Adb {
    $existing = Get-Command adb -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Log "ADB already available in PATH."
        return $existing.Source
    }

    $toolsDir = Join-Path $scriptDir "tools"
    $platformToolsDir = Join-Path $toolsDir "platform-tools"
    $adbExe = Join-Path $platformToolsDir "adb.exe"

    if (Test-Path $adbExe) {
        Write-Log "Using bundled ADB from local tools folder."
        return $adbExe
    }

    Write-Log "ADB not found. Downloading Android platform-tools..."
    New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null

    $zipPath = Join-Path $toolsDir "platform-tools-latest-windows.zip"
    $url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

    Invoke-WebRequest -Uri $url -OutFile $zipPath

    if (Test-Path $platformToolsDir) {
        Remove-Item -Recurse -Force $platformToolsDir
    }

    Expand-Archive -Path $zipPath -DestinationPath $toolsDir -Force

    if (-not (Test-Path $adbExe)) {
        throw "ADB installation failed."
    }

    Write-Log "ADB downloaded and ready."
    return $adbExe
}

$scriptDir = Resolve-ScriptDir
Set-Location $scriptDir

$nodeExe = Ensure-Node
$adbExe = Ensure-Adb

$env:APP_INSTALLER_ADB_PATH = $adbExe

Write-Log "Starting web installer UI..."
try {
    $existing = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:4789/api/status" -TimeoutSec 3
    if ($existing.StatusCode -eq 200) {
        Write-Log "Installer server is already running on port 4789. Opening UI only."
        Start-Process "http://localhost:4789"
        exit 0
    }
} catch {
    # Server not running yet, continue with normal startup.
}

Start-Process "http://localhost:4789"
& $nodeExe (Join-Path $scriptDir "server.js")
