param(
    [switch]$InstallProjectDeps
)

$ErrorActionPreference = 'Stop'

function Write-Section {
    param([string]$Message)
    Write-Host "`n=== $Message ===" -ForegroundColor Cyan
}

function Test-CommandExists {
    param([string]$CommandName)
    return [bool](Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Install-WithWinget {
    param(
        [string]$PackageId,
        [string]$DisplayName
    )

    Write-Host "Installing $DisplayName ($PackageId)..." -ForegroundColor Yellow
    winget install -e --id $PackageId --accept-package-agreements --accept-source-agreements
}

function Print-Version {
    param(
        [string]$Label,
        [scriptblock]$VersionCommand
    )

    try {
        $result = & $VersionCommand
        if ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) {
            Write-Host "$Label: $result" -ForegroundColor Green
        }
        else {
            Write-Host "$Label: not available" -ForegroundColor Red
        }
    }
    catch {
        Write-Host "$Label: not available" -ForegroundColor Red
    }
}

Write-Section "Checking package manager"
if (-not (Test-CommandExists "winget")) {
    Write-Host "winget is not available on this machine." -ForegroundColor Red
    Write-Host "Install App Installer from Microsoft Store, then re-run this script." -ForegroundColor Yellow
    exit 1
}
Write-Host "winget is available." -ForegroundColor Green

$tools = @(
    @{ Name = "Docker Desktop"; Id = "Docker.DockerDesktop"; Verify = "docker" },
    @{ Name = "Java 21 JDK"; Id = "EclipseAdoptium.Temurin.21.JDK"; Verify = "java" },
    @{ Name = "Apache Maven"; Id = "Apache.Maven"; Verify = "mvn" },
    @{ Name = "Node.js LTS"; Id = "OpenJS.NodeJS.LTS"; Verify = "node" }
)

Write-Section "Installing missing tools"
foreach ($tool in $tools) {
    if (Test-CommandExists $tool.Verify) {
        Write-Host "$($tool.Name) already installed." -ForegroundColor Green
    }
    else {
        Install-WithWinget -PackageId $tool.Id -DisplayName $tool.Name
    }
}

Write-Section "Version summary"
Print-Version -Label "Docker" -VersionCommand { docker --version }
Print-Version -Label "Docker Compose" -VersionCommand { docker compose version }
Print-Version -Label "Java" -VersionCommand { java -version 2>&1 | Select-Object -First 1 }
Print-Version -Label "Maven" -VersionCommand { mvn -version 2>&1 | Select-Object -First 1 }
Print-Version -Label "Node" -VersionCommand { node -v }
Print-Version -Label "npm" -VersionCommand { npm -v }

if ($InstallProjectDeps) {
    Write-Section "Installing project dependencies"

    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
    Push-Location $repoRoot
    try {
        if (Test-Path "angular_base/package.json") {
            Write-Host "Installing Angular dependencies..." -ForegroundColor Yellow
            Push-Location "angular_base"
            try {
                npm install
            }
            finally {
                Pop-Location
            }
        }

        if (Test-Path "svelte_base/package.json") {
            Write-Host "Installing Svelte dependencies..." -ForegroundColor Yellow
            Push-Location "svelte_base"
            try {
                npm install
            }
            finally {
                Pop-Location
            }
        }
    }
    finally {
        Pop-Location
    }
}

Write-Section "Done"
Write-Host "If Docker was just installed, start Docker Desktop before running the stack." -ForegroundColor Yellow
Write-Host "If Java/Node/Maven were just installed, open a new terminal so PATH updates apply." -ForegroundColor Yellow
