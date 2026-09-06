# ClinicOS dev: docker up (postgres+minio) -> ensure app_rw role -> run app.
# Idempotent; safe on fresh volumes and fresh clusters alike.
Set-Location $PSScriptRoot

docker info > $null 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "==> Docker engine down; starting Docker Desktop" -ForegroundColor Cyan
    Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        docker info > $null 2>&1
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }
    if (-not $ready) { throw "Docker engine did not start within 120s" }
}

Write-Host "==> Starting postgres + minio" -ForegroundColor Cyan
docker compose up -d postgres minio 2>$null
if ($LASTEXITCODE -ne 0) {
    docker compose up -d postgres
    if ((docker inspect --format "{{.State.Health.Status}}" clinicos-minio 2>$null) -ne "healthy") {
        throw "minio failed to start"
    }
}

Write-Host "==> Waiting for postgres to be healthy" -ForegroundColor Cyan
$healthy = $false
for ($i = 0; $i -lt 30; $i++) {
    if ((docker inspect --format "{{.State.Health.Status}}" clinicos-postgres 2>$null) -eq "healthy") {
        $healthy = $true
        break
    }
    Start-Sleep -Seconds 2
}
if (-not $healthy) {
    throw "postgres did not become healthy within 60s"
}

Write-Host "==> Ensuring app_rw role has a password" -ForegroundColor Cyan
docker exec clinicos-postgres psql -U postgres -d clinicos -c "ALTER ROLE app_rw WITH LOGIN PASSWORD 'local-dev-only'" 2>$null
if ($LASTEXITCODE -ne 0) {
    docker exec clinicos-postgres psql -U postgres -d clinicos -c "CREATE ROLE app_rw LOGIN PASSWORD 'local-dev-only'"
    if ($LASTEXITCODE -ne 0) { throw "could not create app_rw role" }
}

$stale = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match "clinicos" }
foreach ($p in $stale) {
    Write-Host "==> Killing stale ClinicOS java process ($($p.ProcessId))" -ForegroundColor Yellow
    Stop-Process -Id $p.ProcessId -Force
}
if ($stale) { Start-Sleep -Seconds 2 }

Write-Host "==> Starting application (http://localhost:8080)" -ForegroundColor Cyan
Set-Location "$PSScriptRoot\apps\api"
mvn clean spring-boot:run