# Runs dev-up (background) then opens a Cloudflare quick tunnel to it, printing the public URL.
Write-Host "==> Launching ClinicOS (docker up, app_rw check, mvn spring-boot:run in background)" -ForegroundColor Cyan
Write-Host "    Waiting for http://localhost:8080/login to respond (up to 120s)..." -ForegroundColor Cyan
& "$PSScriptRoot\dev-up.ps1" -Background
Write-Host "==> ClinicOS is up" -ForegroundColor Green

$cloudflared = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
if (-not $cloudflared) {
    $cloudflared = "C:\Program Files (x86)\cloudflared\cloudflared.exe"
}
if (-not (Test-Path $cloudflared)) {
    throw "cloudflared not found; install with: winget install --id Cloudflare.cloudflared -e"
}

Write-Host "==> Starting Cloudflare quick tunnel" -ForegroundColor Cyan

$cancelHandler = {
    $_.Cancel = $true
    Write-Host ""
    $resp = Read-Host "Stop ClinicOS and the tunnel? (Y/N)"
    if ($resp -eq 'Y' -or $resp -eq 'y') {
        Write-Host "==> Stopping" -ForegroundColor Yellow
        exit
    }
    Write-Host "==> Continuing" -ForegroundColor Cyan
}
[Console]::add_CancelKeyPress($cancelHandler)

& $cloudflared tunnel --url http://localhost:8080 2>&1 | ForEach-Object {
    if ($_ -match 'https://[a-z0-9-]+\.trycloudflare\.com') {
        Write-Host "Public URL: $($Matches[0])" -ForegroundColor Green
        Write-Host ""
        Write-Host "WARNING: closing this window stops the app and the tunnel. Use Ctrl+C to stop safely." -ForegroundColor Yellow
    }
}
