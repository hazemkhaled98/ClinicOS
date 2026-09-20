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

# Run cloudflared detached (own process group) so Ctrl+C in this window doesn't
# kill it directly -- that would bypass the confirmation prompt below.
$logPath = Join-Path $env:TEMP 'clinicos-cloudflared.log'
$errLogPath = Join-Path $env:TEMP 'clinicos-cloudflared.err.log'
Remove-Item $logPath, $errLogPath -ErrorAction SilentlyContinue
$proc = Start-Process -FilePath $cloudflared -ArgumentList @('tunnel', '--url', 'http://localhost:8080') `
    -RedirectStandardOutput $logPath -RedirectStandardError $errLogPath -NoNewWindow -PassThru

[Console]::TreatControlCAsInput = $true
$urlShown = $false
$lastLine = 0
try {
    while (-not $proc.HasExited) {
        if (-not $urlShown -and (Test-Path $errLogPath)) {
            $lines = Get-Content $errLogPath -ErrorAction SilentlyContinue
            for ($i = $lastLine; $i -lt $lines.Count; $i++) {
                if ($lines[$i] -match 'https://[a-z0-9-]+\.trycloudflare\.com') {
                    Write-Host "Public URL: $($Matches[0])" -ForegroundColor Green
                    Write-Host ""
                    Write-Host "WARNING: closing this window stops the app and the tunnel. Use Ctrl+C to stop safely." -ForegroundColor Yellow
                    $urlShown = $true
                    break
                }
            }
            $lastLine = $lines.Count
        }

        if ([Console]::KeyAvailable) {
            $key = [Console]::ReadKey($true)
            if ($key.Key -eq 'C' -and $key.Modifiers -eq 'Control') {
                Write-Host ""
                $resp = Read-Host "Stop ClinicOS and the tunnel? (Y/N)"
                if ($resp -eq 'Y' -or $resp -eq 'y') {
                    Write-Host "==> Stopping tunnel" -ForegroundColor Yellow
                    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
                    & "$PSScriptRoot\dev-down.ps1"
                    Write-Host ""
                    Write-Host "App is down. Press any key to exit." -ForegroundColor Green
                    [Console]::ReadKey($true) | Out-Null
                    exit
                }
                Write-Host "==> Continuing" -ForegroundColor Cyan
                [Console]::TreatControlCAsInput = $true
            }
        }

        Start-Sleep -Milliseconds 200
    }
} finally {
    [Console]::TreatControlCAsInput = $false
}
