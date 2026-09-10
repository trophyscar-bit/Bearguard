# Bearguard watchdog.
#
# The app can stay alive and responsive while its task queue has quietly stopped working. That
# happened on 2026-08-22: the log ended at 00:47:31 on "Crystal Lab UI not detected. Retrying in
# 5min", the retry never fired, and 38 minutes later the process was still running at ~2% CPU with
# the game parked on a screen the navigation did not expect. Nothing crashed, so nothing restarted
# it. A liveness check on the PROCESS would have reported everything fine.
#
# So this watches the thing that actually indicates work: whether the account log is still being
# written. A long silence means the queue has stopped regardless of how healthy the process looks.
#
# Recovery follows the standing rule about settings -- checkpoint the WAL and take a backup first,
# then close with WM_CLOSE rather than killing javaw, because a hard kill strands writes in
# database.db-wal and has wiped settings before.
#
# Create watchdog.off beside this script to stop it intervening without stopping the loop.

$ErrorActionPreference = 'Continue'
$Root      = 'C:\Bearguard'
$LogFile   = Join-Path $Root 'logs\account_Default_1.log'
$WatchLog  = Join-Path $Root 'logs\watchdog.log'
$OffSwitch = Join-Path $Root 'watchdog.off'
$LockFile  = Join-Path $Root '.prod-lock'
$Launcher  = Join-Path $Root 'Start Bearguard.bat'

# Long enough that a legitimately idle queue is never mistaken for a stall. Real passes write
# frequently; the observed stall was already 38 minutes silent when it was found by hand.
$StallMinutes = 15
$PollSeconds  = 120

function Write-WatchLog([string]$Message) {
    $line = "{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -Path $WatchLog -Value $line -Encoding utf8
}

function Restart-Bearguard {
    Write-WatchLog "Restarting: backing settings up first."
    try {
        Push-Location $Root
        & node backup-settings.js 2>&1 | ForEach-Object { Write-WatchLog "  backup: $_" }
        Pop-Location
    } catch {
        Write-WatchLog "  backup FAILED: $($_.Exception.Message) -- continuing, a stalled bot is worse."
    }

    Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" |
        Where-Object { $_.CommandLine -like '*frostguard*' } |
        ForEach-Object {
            $p = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
            if ($p) {
                Write-WatchLog "  closing PID $($p.Id) with WM_CLOSE (never a hard kill -- WAL)."
                $null = $p.CloseMainWindow()
                Start-Sleep -Seconds 12
                $p.Refresh()
                if (-not $p.HasExited) {
                    Write-WatchLog "  PID $($p.Id) ignored WM_CLOSE; leaving it rather than risking the WAL."
                    return
                }
            }
        }

    Start-Process -FilePath $Launcher -WorkingDirectory $Root -WindowStyle Hidden
    Write-WatchLog "  relaunched; auto-start brings the queue back on its own."
}

Write-WatchLog "Watchdog started (stall threshold ${StallMinutes}m, poll ${PollSeconds}s)."

while ($true) {
    Start-Sleep -Seconds $PollSeconds

    if (Test-Path $OffSwitch) { continue }
    if (-not (Test-Path $LogFile)) { continue }

    # Stand down while a session holds the prod checkout. Without this the watchdog relaunches
    # Bearguard in the middle of somebody's stop -> build -> start, which is how two javaw briefly
    # ran against one emulator and one SQLite file on 2026-08-23. A build legitimately leaves the
    # queue silent for minutes, so every such window looks exactly like the stall this watches for.
    if (Test-Path $LockFile) {
        $held = $null
        try { $held = (Get-Content $LockFile -Raw | ConvertFrom-Json) } catch { }
        $who = if ($held -and $held.owner) { $held.owner } else { 'unknown' }
        $why = if ($held -and $held.reason) { $held.reason } else { 'no reason given' }
        Write-WatchLog "Prod is locked by $who ($why) -- standing down this round."
        continue
    }

    $quietFor = (New-TimeSpan -Start (Get-Item $LogFile).LastWriteTime -End (Get-Date)).TotalMinutes
    if ($quietFor -lt $StallMinutes) { continue }

    $running = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" |
        Where-Object { $_.CommandLine -like '*frostguard*' }).Count

    if ($running -eq 0) {
        # No process and no lock: nobody claimed this stop. That is the unclaimed-shutdown
        # signature that went unattributed for weeks because nothing was watching for it.
        Write-WatchLog "Bearguard is not running and no session holds the lock -- UNCLAIMED shutdown. Starting it."
    } else {
        Write-WatchLog ("Queue silent for {0:N0} minutes while the process is still up -- stalled." -f $quietFor)
    }
    Restart-Bearguard
}
