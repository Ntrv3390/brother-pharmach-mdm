param(
    [string]$EnvFile = ".env",
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"

Write-Host "== Brother Pharmach MDM Docker dry run (Windows Server mode) =="

function Assert-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

Assert-Command docker

docker version | Out-Host
docker compose version | Out-Host

if (-not (Test-Path $EnvFile)) {
    throw "Environment file not found: $EnvFile"
}

Write-Host "Step 1: Validate compose config"
docker compose --env-file $EnvFile config | Out-Host

Write-Host "Step 2: Build images"
docker compose --env-file $EnvFile build --no-cache | Out-Host

Write-Host "Step 3: Start stack"
docker compose --env-file $EnvFile up -d | Out-Host

Write-Host "Step 4: Wait for services"
Start-Sleep -Seconds 20

Write-Host "Step 5: Show service status"
docker compose ps | Out-Host

Write-Host "Step 6: Tail recent logs"
docker compose logs --tail=120 | Out-Host

Write-Host "Step 7: Probe HTTP endpoint"
try {
    $resp = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080" -TimeoutSec 30
    Write-Host "HTTP probe status: $($resp.StatusCode)"
} catch {
    Write-Warning "HTTP probe failed: $($_.Exception.Message)"
}

if ($KeepRunning) {
    Write-Host "Stack left running because -KeepRunning was specified."
    exit 0
}

Write-Host "Step 8: Tear down stack"
docker compose down | Out-Host

Write-Host "Dry run completed."
