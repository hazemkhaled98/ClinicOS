try {
    Set-Location $PSScriptRoot

    Write-Host "==> Ensuring Docker is running" -ForegroundColor Cyan
    docker info > $null 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "==> Docker engine down; starting Docker Desktop" -ForegroundColor Cyan
        $dockerExePath = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
        if (-not (Test-Path $dockerExePath)) {
            throw "Docker Desktop not found at $dockerExePath"
        }
        Start-Process $dockerExePath
        $ready = $false
        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Seconds 2
            docker info > $null 2>&1
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        }
        if (-not $ready) { throw "Docker engine did not start within 120s" }
    }

    Write-Host "==> Building jar" -ForegroundColor Cyan
    Set-Location "$PSScriptRoot\apps\api"
    mvn clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed."
    }
    Set-Location $PSScriptRoot

    Write-Host "==> Determining version" -ForegroundColor Cyan
    $pomContent = Get-Content "apps\api\pom.xml" -Raw
    if ($pomContent -match '<artifactId>clinicos-api</artifactId>\s*<version>(.*?)</version>') {
        $appVersion = $matches[1]
    } else {
        throw "Could not extract version from pom.xml"
    }
    Write-Host "    Version: $appVersion" -ForegroundColor Gray

    Write-Host "==> Locating jar" -ForegroundColor Cyan
    $jar = Get-ChildItem "apps\api\target" -Filter "clinicos-api-*.jar" |
        Where-Object { $_.Name -notmatch "sources|javadoc" } |
        Select-Object -First 1
    if (-not $jar) {
        throw "No JAR file found in apps\api\target"
    }
    Write-Host "    JAR: $($jar.Name)" -ForegroundColor Gray

    Write-Host "==> Building Docker image" -ForegroundColor Cyan
    docker build -f Distribution\Dockerfile -t "clinicos-app:$appVersion" apps\api
    if ($LASTEXITCODE -ne 0) {
        throw "Docker image build failed."
    }

    Write-Host "==> Cleaning up dangling images" -ForegroundColor Cyan
    docker image prune -f | Out-Null

    Write-Host "==> Recreating dist folder" -ForegroundColor Cyan
    $distPath = Join-Path $PSScriptRoot "dist"
    if (Test-Path $distPath) {
        Remove-Item $distPath -Recurse -Force
    }
    New-Item -ItemType Directory -Path $distPath | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $distPath "initdb") | Out-Null

    Write-Host "==> Exporting Docker image" -ForegroundColor Cyan
    $tarPath = Join-Path $distPath "app-image.tar"
    docker save "clinicos-app:$appVersion" -o $tarPath
    if ($LASTEXITCODE -ne 0) {
        throw "docker save failed."
    }

    Write-Host "==> Copying distribution files" -ForegroundColor Cyan
    Copy-Item "Distribution\docker-compose.dist.yml" (Join-Path $distPath "docker-compose.yml")
    Copy-Item "Distribution\common.ps1" $distPath
    Copy-Item "Distribution\start.ps1" $distPath
    Copy-Item "Distribution\stop.ps1" $distPath
    Copy-Item "Distribution\reset.ps1" $distPath
    Copy-Item "Distribution\README.md" $distPath
    Copy-Item "Distribution\.env.example" $distPath
    Copy-Item "Distribution\initdb\*" (Join-Path $distPath "initdb")

    Write-Host "==> Patching dist/.env.example with actual version" -ForegroundColor Cyan
    $envPath = Join-Path $distPath ".env.example"
    $envContent = Get-Content $envPath
    $envContent = $envContent | ForEach-Object {
        if ($_ -match '^APP_VERSION=') {
            "APP_VERSION=$appVersion"
        } else {
            $_
        }
    }
    Set-Content $envPath $envContent

    Write-Host "==> Zipping dist folder" -ForegroundColor Cyan
    $zipName = "clinicos-app-$appVersion.zip"
    $zipPath = Join-Path $PSScriptRoot $zipName
    if (Test-Path $zipPath) {
        Remove-Item $zipPath -Force
    }
    Compress-Archive -Path "$distPath\*" -DestinationPath $zipPath
    Remove-Item $distPath -Recurse -Force

    Write-Host ""
    $zipSize = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
    Write-Host "Distribution build succeeded." -ForegroundColor Green
    Write-Host "  Image: clinicos-app:$appVersion" -ForegroundColor Green
    Write-Host "  Zip:   $zipPath ($zipSize MB)" -ForegroundColor Green
    Write-Host "Send $zipName to the recipient." -ForegroundColor Green
    Write-Host "Press Enter to close this window" -ForegroundColor Gray
    Read-Host | Out-Null
} catch {
    Write-Host ""
    Write-Host "Distribution build FAILED: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Press Enter to close this window" -ForegroundColor Gray
    Read-Host | Out-Null
    exit 1
}
