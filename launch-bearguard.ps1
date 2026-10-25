# Start Bearguard OUTSIDE the Claude desktop app's job object.
#
# Anything a Claude session starts runs inside the Claude app's Windows job, so a
# Bearguard launched straight from a session dies with the app: the nightly
# ClaudeDesktop-DailyFullExit task force-closes it at 04:00 for updates, and a
# Bearguard started that way stopped dead overnight and stayed down for hours.
# Task Scheduler is no way out either: its tasks run in a job of their own, which it
# kills when the task ends. Asking Explorer to open launch-bearguard.vbs is: Explorer
# starts it the way a double-click would, in no job.
# Every launcher (sessions, dev-rig, watchdog) should come through here.
#
#   .\launch-bearguard.ps1        start it (no-op if Bearguard is already up)
$ErrorActionPreference = 'Stop'
$running = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" |
    Where-Object { $_.CommandLine -match 'frostguard\.workspace="?C:\\Bearguard' })
if ($running) { Write-Host "Bearguard is already running (pid $($running[0].ProcessId))."; exit 0 }
Start-Process explorer.exe -ArgumentList '"C:\Bearguard\launch-bearguard.vbs"'
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    $p = Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" |
        Where-Object { $_.CommandLine -match 'frostguard\.workspace="?C:\\Bearguard' } | Select-Object -First 1
    if ($p) { Write-Host "Bearguard started (pid $($p.ProcessId)) via Explorer, outside any job."; exit 0 }
}
Write-Host 'Bearguard did not start within 60 s.'; exit 1
