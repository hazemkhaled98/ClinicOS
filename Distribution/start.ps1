. "$PSScriptRoot\common.ps1"

if (-not (Assert-Docker)) {
    Read-Host "Press Enter to exit"
    exit 1
}

Set-Location $PSScriptRoot

if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
    Write-Host "Created .env from .env.example (edit it to change ports/credentials)." -ForegroundColor Yellow
}

Write-Host "==> Loading application image" -ForegroundColor Cyan
docker load -i app-image.tar
if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to load app-image.tar. Is the file present and not corrupted?" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "==> Starting ClinicOS" -ForegroundColor Cyan
docker compose up -d
if ($LASTEXITCODE -ne 0) {
    Write-Host "docker compose up failed. Run docker compose logs for details." -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "==> Cleaning up dangling images" -ForegroundColor Cyan
docker image prune -f | Out-Null

$appPort = "8080"
if (Test-Path ".env") {
    $line = Get-Content ".env" | Where-Object { $_ -match "^APP_PORT=" }
    if ($line) {
        $appPort = (($line -split "=", 2)[1]).Trim().Trim('"').Trim("'")
    }
}

Write-Host "==> Waiting for ClinicOS to become ready" -ForegroundColor Cyan
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        Invoke-WebRequest -Uri "http://localhost:$appPort/login" -UseBasicParsing -TimeoutSec 2 | Out-Null
        $ready = $true
        break
    } catch {
        Start-Sleep -Seconds 2
    }
}

if (-not $ready) {
    Write-Host "ClinicOS did not respond within 2 minutes." -ForegroundColor Red
    Write-Host "Recent logs:" -ForegroundColor Yellow
    docker compose logs --tail 50 app
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""
Write-Host "ClinicOS is running at http://localhost:$appPort" -ForegroundColor Green
Write-Host "First time here? Create your clinic at http://localhost:$appPort/signup" -ForegroundColor Green
Write-Host ""
Read-Host "Press Enter to close this window"
