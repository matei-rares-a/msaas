<#
.SYNOPSIS
  One-time bootstrap for Windows: installs what the MSaaS platform needs (Docker Desktop, JDK 21, Node.js 22) and, with -Setup,
  continues with the setup of the repository (scripts/env/setup.mjs).

.DESCRIPTION
  Run it from the repository root, in Windows PowerShell or PowerShell 7. Windows blocks scripts by default on many machines, so
  start it like this (it changes the policy for this one run only):

    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\env\install-prerequisites.ps1 -Setup

  Safe to run again: what is installed is left alone. It installs through winget (part of Windows 10/11 as "App Installer").
  Maven is not installed: the repository's mvnw downloads the Maven it needs.

  Exit codes: 0 done, 1 something failed, 10 Docker Desktop was just installed and needs a restart / a first start before
  anything else can continue (run the script again afterwards).

  Keep this file ASCII only: Windows PowerShell 5.1 reads a UTF-8 file without a BOM as ANSI.

.PARAMETER Setup
  After the tools are in place, run node scripts/env/setup.mjs (build, start the platform, demo data, the web interface).

.PARAMETER Check
  Only report what is installed; install nothing.
#>
[CmdletBinding()]
param(
    [switch]$Setup,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$dockerDesktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'

function Write-Section([string]$Message) {
    Write-Host ""
    Write-Host "=== $Message ===" -ForegroundColor Cyan
}

function Test-Command([string]$Name) {
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

# A tool installed a moment ago is on the PATH of new terminals only; reading it from the registry makes it usable here.
function Update-SessionPath {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = (@($machine, $user) | Where-Object { $_ }) -join ';'
    $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    if (-not $javaHome) { $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine') }
    if ($javaHome) { $env:JAVA_HOME = $javaHome }
}

# The build refuses any Java but 21. The "release" file of a JDK says which one it is; running java -version instead would
# write to stderr, which Windows PowerShell 5.1 turns into an error when $ErrorActionPreference is Stop.
function Find-Jdk21 {
    $homes = @()
    if ($env:JAVA_HOME) { $homes += $env:JAVA_HOME }
    foreach ($root in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft", "$env:LOCALAPPDATA\jdks")) {
        if (Test-Path $root) { $homes += (Get-ChildItem $root -Directory | ForEach-Object { $_.FullName }) }
    }
    foreach ($candidate in $homes) {
        $release = Join-Path $candidate 'release'
        if ((Test-Path (Join-Path $candidate 'bin\java.exe')) -and (Test-Path $release) -and ((Get-Content $release -Raw) -match 'JAVA_VERSION="21\.')) {
            return $candidate
        }
    }
    return $null
}

# Angular 19 runs on Node 20.11+ and 22+; the CI uses 22.
function Test-NodeSupported {
    if (-not (Test-Command 'node')) { return $false }
    $parts = ((node -v) -replace '^v', '').Split('.')
    $major = [int]$parts[0]
    $minor = [int]$parts[1]
    return ($major -ge 22) -or ($major -eq 20 -and $minor -ge 11)
}

function Install-WithWinget([string]$Id, [string]$Name) {
    Write-Host "Installing $Name ($Id) - this can take a few minutes..." -ForegroundColor Yellow
    winget install -e --id $Id --accept-package-agreements --accept-source-agreements --silent
    # -1978335189 (0x8A15002B) nothing to update, -1978335135 (0x8A150061) already installed: both mean the tool is there
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189 -and $LASTEXITCODE -ne -1978335135) {
        throw "winget could not install $Name (exit code $LASTEXITCODE). Install it by hand (see TLDR.md, section 'Install by hand') and run this script again."
    }
}

Write-Host "MSaaS prerequisites for Windows$(if ($Check) { ' (check only)' })"

Write-Section 'Checking the machine'
if (-not (Test-Command 'winget')) {
    Write-Host 'winget is not available. It comes with "App Installer" (Microsoft Store), on Windows 10 1809+ and Windows 11.' -ForegroundColor Red
    Write-Host 'Install App Installer from the Microsoft Store and run this script again, or install by hand:' -ForegroundColor Yellow
    Write-Host '  Docker Desktop   https://www.docker.com/products/docker-desktop/'
    Write-Host '  Temurin JDK 21   https://adoptium.net/temurin/releases/?version=21'
    Write-Host '  Node.js 22       https://nodejs.org/en/download'
    exit 1
}
Write-Host 'winget: available' -ForegroundColor Green

# Docker Desktop cannot start without hardware virtualization (and, for its default backend, WSL 2).
try {
    $computer = Get-CimInstance Win32_ComputerSystem
    $firmware = (Get-CimInstance Win32_Processor | Select-Object -First 1).VirtualizationFirmwareEnabled
    if ($computer.HypervisorPresent -or $firmware) {
        Write-Host 'Virtualization: enabled' -ForegroundColor Green
    }
    else {
        Write-Host 'Virtualization: NOT enabled. Docker Desktop will not start until Intel VT-x / AMD-V (SVM) is turned on in the BIOS/UEFI.' -ForegroundColor Red
        Write-Host '  (In a virtual machine, enable nested virtualization for it.)' -ForegroundColor Yellow
    }
}
catch {
    Write-Host 'Virtualization: could not be checked (continuing).' -ForegroundColor Yellow
}

$dockerInstalled = (Test-Command 'docker') -or (Test-Path $dockerDesktop)
$jdk = Find-Jdk21
$nodeOk = Test-NodeSupported
Write-Host ("Docker Desktop : " + $(if ($dockerInstalled) { 'installed' } else { 'missing' }))
Write-Host ("JDK 21         : " + $(if ($jdk) { $jdk } else { 'missing' }))
Write-Host ("Node.js 20.11+ : " + $(if ($nodeOk) { (node -v) } else { 'missing or too old' }))

if ($Check) {
    if ($dockerInstalled -and $jdk -and $nodeOk) { Write-Host 'All prerequisites are installed.' -ForegroundColor Green; exit 0 }
    Write-Host 'Something is missing: run this script without -Check.' -ForegroundColor Yellow
    exit 1
}

Write-Section 'Installing what is missing'
$dockerJustInstalled = $false
if (-not $jdk) { Install-WithWinget 'EclipseAdoptium.Temurin.21.JDK' 'Temurin JDK 21' }
if (-not $nodeOk) { Install-WithWinget 'OpenJS.NodeJS.22' 'Node.js 22' }
if (-not $dockerInstalled) {
    Write-Host 'Docker Desktop asks for administrator permission (a Windows prompt will appear).' -ForegroundColor Yellow
    Install-WithWinget 'Docker.DockerDesktop' 'Docker Desktop'
    $dockerJustInstalled = $true
}

Update-SessionPath
Write-Section 'Result'
$jdk = Find-Jdk21
Write-Host ("JDK 21  : " + $(if ($jdk) { $jdk } else { 'NOT FOUND' }))
Write-Host ("Node.js : " + $(if (Test-Command 'node') { (node -v) } else { 'NOT FOUND' }))
Write-Host ("Docker  : " + $(if (Test-Command 'docker') { (docker --version) } else { 'installed, not on the PATH of this window yet' }))
if (-not $jdk -or -not (Test-Command 'node')) {
    Write-Host 'A tool is still not found. Open a NEW terminal and run this script again.' -ForegroundColor Red
    exit 1
}

if ($dockerJustInstalled) {
    Write-Host ''
    Write-Host 'Docker Desktop was just installed. Before anything else can continue:' -ForegroundColor Yellow
    Write-Host '  1. Restart Windows if the installer asked for it (it enables WSL 2 / virtualization features).'
    Write-Host '  2. Open "Docker Desktop" once, accept its terms, and wait until it says the engine is running.'
    Write-Host '  3. Open a new terminal and run this script again (same command).'
    exit 10
}

if ($Setup) {
    Write-Section 'Setting up the repository'
    Push-Location $repoRoot
    try {
        node scripts/env/setup.mjs
        exit $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}

Write-Host ''
Write-Host 'Prerequisites are in place. Next (in a new terminal, from the repository root): node scripts/env/setup.mjs' -ForegroundColor Green
