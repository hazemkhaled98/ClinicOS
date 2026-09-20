# ClinicOS dev: stop app java -> drop postgres+minio containers -> quit Docker Desktop.
Set-Location $PSScriptRoot

Write-Host "==> Stopping ClinicOS java process" -ForegroundColor Cyan
$java = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match "clinicos" }
foreach ($p in $java) {
    Write-Host "   killing $($p.ProcessId)" -ForegroundColor Yellow
    Stop-Process -Id $p.ProcessId -Force
}

Write-Host "==> Dropping postgres + minio containers" -ForegroundColor Cyan
docker compose down 2>$null

Write-Host "==> Quitting Docker Desktop" -ForegroundColor Cyan
Stop-Process -Name 'Docker Desktop' -Force -ErrorAction SilentlyContinue
Stop-Process -Name 'Docker Desktop Backend' -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

Write-Host "==> Done. Everything stopped and Docker closed." -ForegroundColor Green