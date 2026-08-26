$ErrorActionPreference = "Stop"
$INSTALL_DIR = "$env:USERPROFILE\.aiope"
$PORT = if ($env:AIOPE_PORT) { $env:AIOPE_PORT } else { "2222" }

Write-Host "================================"
Write-Host "  aiope-remote installer (Windows)"
Write-Host "================================"

$ARCH = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
switch ($ARCH) {
    "X64"  { $BINARY = "aiope-remote-windows-amd64.exe" }
    "Arm64" { $BINARY = "aiope-remote-windows-arm64.exe" }
    default { Write-Host "Unsupported architecture: $ARCH"; exit 1 }
}

Write-Host "Detected: $ARCH -> $BINARY"

# Create install dir
New-Item -ItemType Directory -Path $INSTALL_DIR -Force | Out-Null

# Find archive marker in this script's companion file
$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
$PAYLOAD = Join-Path $SCRIPT_DIR "payload.tar.gz"

if (-not (Test-Path $PAYLOAD)) {
    Write-Host "Error: payload.tar.gz not found at $PAYLOAD"
    exit 1
}

# Extract payload
tar xzf $PAYLOAD -C $INSTALL_DIR
if ($LASTEXITCODE -ne 0) { Write-Host "Error: tar extraction failed"; exit 1 }

# Copy correct binary
$SRC = Join-Path $INSTALL_DIR $BINARY
$DEST = Join-Path $INSTALL_DIR "aiope-remote.exe"
if (-not (Test-Path $SRC)) {
    Write-Host "Error: binary $BINARY not found in archive"
    exit 1
}
Copy-Item $SRC $DEST -Force
Write-Host "Installed binary to $DEST"

# Clean up extracted binaries (keep only the right one)
Remove-Item "$INSTALL_DIR\aiope-remote-*" -Force -ErrorAction SilentlyContinue
Remove-Item "$INSTALL_DIR\authorized_keys" -Force -ErrorAction SilentlyContinue

# Version
$VER = & $DEST --version 2>&1
Write-Host "Version: $VER"

# Stop existing daemon
Stop-Process -Name aiope-remote -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1

# Firewall rule
netsh advfirewall firewall delete rule name="AIOPE Remote" >$null 2>&1
netsh advfirewall firewall add rule name="AIOPE Remote" dir=in action=allow protocol=TCP localport=$PORT profile=any >$null
netsh advfirewall firewall add rule name="AIOPE Remote EXE" dir=in action=allow program="$DEST" enable=yes profile=any >$null

# Register as Windows Service with restart-on-failure
sc.exe stop "AIOPERemote" >$null 2>&1
sc.exe delete "AIOPERemote" >$null 2>&1
sc.exe create "AIOPERemote" binpath="$DEST" start=auto DisplayName="AIOPE Remote" >$null 2>&1
if ($LASTEXITCODE -eq 0) {
    sc.exe failure "AIOPERemote" reset=60 actions=restart/5000/restart/10000/restart/30000
    sc.exe start "AIOPERemote" >$null 2>&1
    Write-Host "Installed as Windows Service with auto-restart"
} else {
    # Fallback: scheduled task + registry run key
    schtasks /Delete /TN "AIOPE Remote" /F 2>$null
    schtasks /Create /TN "AIOPE Remote" /TR "$DEST" /SC ONSTART /RU $env:USERNAME /RL HIGHEST /F >$null
    schtasks /Run /TN "AIOPE Remote"
    Write-Host "Installed as Scheduled Task (service registration failed)"
}

# Registry run key as backup
Set-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "AIOPE Remote" -Value "$DEST"

Start-Sleep -Seconds 3

# Health check
$listening = netstat -an | Select-String ":$PORT.*LISTENING"
if ($listening) {
    Write-Host "================================"
    Write-Host "  OK - daemon listening on port $PORT"
    Write-Host "================================"
    # Output health JSON
    $os = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
    $hostname = $env:COMPUTERNAME
    Write-Host "{`"os`":`"windows`",`"arch`":`"$ARCH`",`"hostname`":`"$hostname`",`"version`":`"$VER`",`"port`":$PORT}"
} else {
    Write-Host "ERROR: daemon not listening on port $PORT"
    exit 1
}
