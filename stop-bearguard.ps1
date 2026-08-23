# Graceful stop for a Bearguard instance.
#
# Settings have been lost twice to a hard kill leaving writes stranded in the SQLite WAL, so
# nothing here terminates the process. It checkpoints and backs the settings up first, then asks
# the window to close and confirms it actually went. A process that refuses to close is reported
# as a failure rather than forced -- the caller decides, and building against a running instance
# is caught instead of silently producing half-updated jars.
#
#   .\stop-bearguard.ps1                    stop the instance rooted at this script's directory
#   .\stop-bearguard.ps1 -Root C:\Bearguard-dev
#   .\stop-bearguard.ps1 -ListOnly          report what matches, close nothing
#
# Exit code 0 = nothing left running for that root. 1 = something is still up.

param(
    [string]$Root = $PSScriptRoot,
    [int]$TimeoutSeconds = 20,
    [switch]$ListOnly
)

$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path -LiteralPath $Root).Path

# Warn, never block. Stopping prod is sometimes exactly what has to happen in a hurry, and a lock
# left behind by a session that has since ended must not stand between anyone and a stuck app.
# But two sessions stopping and rebuilding this checkout minutes apart has already happened once,
# so say who else is in here before closing it.
$lockScript = Join-Path $Root 'prod-lock.ps1'
if (Test-Path $lockScript) {
    $held = & $lockScript status -Root $Root 2>&1
    if ($LASTEXITCODE -eq 1) {
        Write-Warning "prod is locked by another session -- stopping anyway, but check with them:"
        $held | ForEach-Object { Write-Host "  $_" }
    }
}

function Get-BearguardProcesses([string]$root) {
    # Match on the directory, not the bare string. 'C:\Bearguard' is a prefix of
    # 'C:\Bearguard-dev', so a substring test reports dev as prod and closes the wrong instance.
    # The trailing separator is what keeps the two roots apart.
    $needle = $root.TrimEnd('\') + '\'
    @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($needle) })
}

$procs = Get-BearguardProcesses $Root
if ($procs.Count -eq 0) {
    Write-Host "Bearguard is not running at $Root."
    exit 0
}

if ($ListOnly) {
    $procs | ForEach-Object { Write-Host "  would close PID $($_.ProcessId)" }
    exit 1
}

# Back up before closing, never after: the point is to capture what the running instance holds.
if (Test-Path (Join-Path $Root 'backup-settings.js')) {
    Push-Location $Root
    try { & node backup-settings.js | Out-Host }
    catch { Write-Warning "settings backup failed: $($_.Exception.Message)" }
    Pop-Location
}

foreach ($p in $procs) {
    $proc = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
    if (-not $proc) { continue }
    Write-Host "Closing PID $($proc.Id) with WM_CLOSE (never a hard kill -- WAL)."
    $null = $proc.CloseMainWindow()
    $waited = 0
    while ($waited -lt $TimeoutSeconds) {
        Start-Sleep -Seconds 1
        $waited++
        $proc.Refresh()
        if ($proc.HasExited) { break }
    }
    if ($proc.HasExited) { Write-Host "  PID $($proc.Id) closed after ${waited}s." }
    else { Write-Warning "  PID $($proc.Id) ignored WM_CLOSE after ${TimeoutSeconds}s; not forcing it (WAL)." }
}

$left = @(Get-BearguardProcesses $Root).Count
if ($left -gt 0) {
    Write-Warning "$left Bearguard process(es) still running at $Root."
    exit 1
}
Write-Host "Bearguard stopped cleanly at $Root."
exit 0
