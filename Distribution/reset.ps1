. "$PSScriptRoot\common.ps1"

if (-not (Assert-Docker)) {
    Read-Host "Press Enter to exit"
    exit 1
}

Set-Location $PSScriptRoot

Write-Host "WARNING: this will permanently delete ALL ClinicOS data (clinics, users, attendance, attachments)." -ForegroundColor Red
Write-Host "This cannot be undone." -ForegroundColor Red
$confirm = Read-Host "Type DELETE (all caps) to continue, or anything else to cancel"

if ($confirm -ne "DELETE") {
    Write-Host "Cancelled. No data was deleted." -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 0
}

Write-Host "==> Removing containers and volumes" -ForegroundColor Cyan
docker compose down -v
if ($LASTEXITCODE -ne 0) {
    Write-Host "docker compose down -v failed." -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "==> Cleaning up dangling images" -ForegroundColor Cyan
docker image prune -f | Out-Null

Write-Host "All ClinicOS data has been deleted. Run start.ps1 for a fresh install." -ForegroundColor Green
Write-Host "Press Enter to close this window" -ForegroundColor Gray
Read-Host | Out-Null
