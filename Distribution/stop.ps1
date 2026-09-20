. "$PSScriptRoot\common.ps1"

if (-not (Assert-Docker)) {
    Read-Host "Press Enter to exit"
    exit 1
}

Set-Location $PSScriptRoot

Write-Host "==> Stopping ClinicOS (data is preserved)" -ForegroundColor Cyan
docker compose stop
if ($LASTEXITCODE -ne 0) {
    Write-Host "docker compose stop failed." -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "ClinicOS stopped. Your data is safe -- run start.ps1 to resume." -ForegroundColor Green
Write-Host "Press Enter to close this window" -ForegroundColor Gray
Read-Host | Out-Null
