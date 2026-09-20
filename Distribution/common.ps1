function Assert-Docker {
    $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $dockerCmd) {
        Write-Host "Docker is not installed." -ForegroundColor Red
        Write-Host "Install Docker Desktop from https://www.docker.com/products/docker-desktop/ then run this script again." -ForegroundColor Red
        return $false
    }

    docker info *> $null
    if ($LASTEXITCODE -eq 0) {
        return $true
    }

    $candidatePaths = @(
        "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe",
        "${env:ProgramFiles(x86)}\Docker\Docker\Docker Desktop.exe",
        "$env:LOCALAPPDATA\Docker\Docker Desktop.exe"
    )
    $dockerDesktop = $candidatePaths | Where-Object { Test-Path $_ } | Select-Object -First 1

    if (-not $dockerDesktop) {
        Write-Host "Docker engine is not running and Docker Desktop could not be located automatically." -ForegroundColor Red
        Write-Host "Please start Docker Desktop manually, wait for it to say running, then run this script again." -ForegroundColor Red
        return $false
    }

    Write-Host "Docker is installed but not running -- starting Docker Desktop..." -ForegroundColor Cyan
    Start-Process -FilePath $dockerDesktop

    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        docker info *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }

    if (-not $ready) {
        Write-Host "Docker Desktop did not finish starting within 2 minutes." -ForegroundColor Red
        Write-Host "Please open Docker Desktop manually and wait for the whale icon in the system tray to stop animating, then run this script again." -ForegroundColor Red
        return $false
    }

    return $true
}
