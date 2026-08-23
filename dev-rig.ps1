# Bearguard dev rig.
#
# The problem this solves: testing a routine meant waiting for its schedule. Intel runs every
# 15-60 minutes, so one attempt at a fix cost an hour of waiting, and the only place to try it was
# the live profile -- where a bad guess corrupts real settings.
#
# PROD is C:\Bearguard. DEV is C:\Bearguard-dev, a git worktree, which makes it a genuinely
# separate application: its own source, its own build, its own frostguard.db, its own logs. Nothing
# it does can reach prod's settings.
#
# What is NOT separable, and it is worth being clear about it: there is one emulator and one game
# account. Two instances driving the same screen would fight each other, and the same Whiteout
# login cannot be in two places. So this swaps between them rather than running both -- prod is
# stopped (gracefully, with a settings backup) for the length of a dev run and restarted after.
#
#   .\dev-rig.ps1 build           rebuild dev (releases the jars first, restarts after)
#   .\dev-rig.ps1 test INTEL      rebuild dev, then run only that task, immediately
#   .\dev-rig.ps1 stop            stop dev and bring prod back
#   .\dev-rig.ps1 status          what is running right now
#
# Add -Keep to leave dev running after 'test' returns.

param(
    [Parameter(Position = 0)][ValidateSet('test', 'stop', 'status', 'build')][string]$Action = 'status',
    [Parameter(Position = 1)][string]$Task,
    [switch]$Keep,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$Prod = 'C:\Bearguard'
$Dev  = 'C:\Bearguard-dev'
$TaskEnumSource = Join-Path $Dev 'modules\api\src\main\java\dev\frostguard\api\configs\TpDailyTaskEnum.java'

function Get-BearguardProcesses([string]$root) {
    # The match has to be on the directory, not the bare string. 'C:\Bearguard' is a prefix of
    # 'C:\Bearguard-dev', so a substring test reports dev as prod -- 'stop prod' then closes the
    # dev instance while announcing it closed prod, which is exactly how a deploy ends up building
    # against a running process. Requiring the trailing separator keeps the two roots apart.
    $needle = $root.TrimEnd('\') + '\'
    @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($needle) })
}


function Stop-Bearguard([string]$root, [string]$label) {
    $procs = Get-BearguardProcesses $root
    if ($procs.Count -eq 0) { Write-Host "$label is not running."; return }

    # Settings have been lost twice to a hard kill leaving writes stranded in the SQLite WAL, so
    # this checkpoints first and then asks the window to close rather than terminating it.
    Push-Location $root
    # Only prod holds settings worth protecting; dev's database is disposable and the backup
    # script looks for a file dev does not have.
    if ($root -eq $Prod) {
        try { & node backup-settings.js | Out-Host } catch { Write-Warning "backup failed: $($_.Exception.Message)" }
    }
    Pop-Location

    foreach ($p in $procs) {
        $proc = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
        if ($proc) {
            $null = $proc.CloseMainWindow()
            Start-Sleep -Seconds 12
            $proc.Refresh()
            if ($proc.HasExited) { Write-Host "$label PID $($proc.Id) closed." }
            else { Write-Warning "$label PID $($proc.Id) ignored the close request; not forcing it (WAL)." }
        }
    }
}

function Start-Bearguard([string]$root, [string]$label) {
    Start-Process -FilePath (Join-Path $root 'Start Bearguard.bat') -WorkingDirectory $root -WindowStyle Hidden
    Start-Sleep -Seconds 8
    Write-Host "$label started."
}

function Build-Dev {
    # A running instance holds every jar under packaging\desktop\target\input\lib, so a clean
    # partway and leaves a half-updated build that looks fine and runs the old code. Releasing the
    # jars is part of building, not something to remember to do first.
    $running = @(Get-BearguardProcesses $Dev).Count -gt 0
    if ($running) {
        Write-Host "Dev holds the build output; closing it first."
        Stop-Bearguard $Dev 'DEV'
    }

    Push-Location $Dev
    try {
        # Absolute paths, no PATH manipulation: the wrapper resolves a different local
        # repository that has not cached the install plugin for offline use, and relying on
        # PATH order is how the previous attempt ended up finding no mvn at all.
        $env:JAVA_HOME = 'C:\Frostguard-tools\jdk-21.0.12+8'
        $mvn = 'C:\Frostguard-tools\apache-maven-3.9.9\bin\mvn.cmd'
        & $mvn -q -o clean install -DskipTests 2>&1 |
            Where-Object { $_ -match 'ERROR|BUILD FAILURE' } | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Dev build failed; not starting it on stale jars." }
        Write-Host "Dev rebuilt."
    } finally {
        Pop-Location
    }
    return $running
}

function Resolve-TaskId([string]$name) {
    $line = Select-String -Path $TaskEnumSource -Pattern "^\s{4}$name\s*\(\s*(\d+)," | Select-Object -First 1
    if (-not $line) { throw "No task named '$name' in TpDailyTaskEnum." }
    $id = [int]$line.Matches[0].Groups[1].Value
    $key = $null
    if ($line.Line -match 'ConfigurationKeyEnum\.([A-Z0-9_]+)') { $key = $Matches[1] }
    [pscustomobject]@{ Id = $id; EnableKey = $key }
}

function Initialize-DevDatabase([string]$enableKey, [int]$taskId) {
    $devDb  = Join-Path $Dev 'frostguard.db'
    $prodDb = Join-Path $Prod 'frostguard.db'

    # Seed once from prod so the dev profile knows the emulator, resolution and account layout.
    # After that dev keeps its own copy -- re-seeding every run would throw away whatever the last
    # test set up.
    if (-not (Test-Path $devDb)) {
        Write-Host "Seeding the dev database from prod (first run)."
        Copy-Item $prodDb $devDb
    }

    $py = @"
import sqlite3, time
db = sqlite3.connect(r'$devDb')
cur = db.cursor()
# Gate by SCHEDULE, not by config. Flipping enable flags means editing dozens of keys and getting
# one wrong leaves a task quietly disabled on the next run; pushing every other task's next_schedule
# far out achieves the same isolation and touches nothing that outlives this test.
far = int((time.time() + 3650*86400) * 1000)
cur.execute("update daily_task set next_schedule=?", (far,))
cur.execute("update daily_task set next_schedule=? where task_id=?", (int(time.time()*1000), $taskId))
# The task still has to be switched on to be queued at all.
if '$enableKey':
    cur.execute("update config set value='true' where config_key=?", ('$enableKey',))
cur.execute("update config set value='true' where config_key='AUTO_START_ENABLED_BOOL'")
cur.execute("update config set value='0' where config_key='AUTO_START_DELAY_MINUTES_INT'")
cur.execute("update config set value='5' where config_key='AUTO_START_DELAY_SECONDS_INT'")
db.commit()
print('dev db: task $taskId due now, every other task parked 10 years out')
"@
    $tmp = Join-Path $env:TEMP 'devrig_seed.py'
    Set-Content -Path $tmp -Value $py -Encoding utf8
    & python $tmp | Out-Host
}

switch ($Action) {
    'status' {
        Write-Host ("PROD ({0}): {1} process(es)" -f $Prod, @(Get-BearguardProcesses $Prod).Count)
        Write-Host ("DEV  ({0}): {1} process(es)" -f $Dev,  @(Get-BearguardProcesses $Dev).Count)
    }

    'build' {
        $wasRunning = Build-Dev
        if ($wasRunning) { Start-Bearguard $Dev 'DEV' }
    }

    'stop' {
        Stop-Bearguard $Dev 'DEV'
        Start-Bearguard $Prod 'PROD'
        Remove-Item (Join-Path $Prod 'watchdog.off') -ErrorAction SilentlyContinue
        Write-Host "Prod watchdog re-armed."
    }

    'test' {
        if (-not $Task) { throw "Name the task, e.g. .\dev-rig.ps1 test INTEL" }
        $resolved = Resolve-TaskId $Task
        Write-Host ("Task {0} -> id {1}, enable key {2}" -f $Task, $resolved.Id, $resolved.EnableKey)

        # Build before anything else, so a failed compile never costs a prod stop.
        if (-not $NoBuild) { $null = Build-Dev }

        # One emulator, one account: prod has to be out of the way before dev touches the screen.
        # The prod watchdog restarts anything that goes quiet, which would drag prod back on top
        # of the dev run. Muzzle it for the duration.
        New-Item -ItemType File -Path (Join-Path $Prod 'watchdog.off') -Force | Out-Null
        Stop-Bearguard $Prod 'PROD'
        Initialize-DevDatabase $resolved.EnableKey $resolved.Id
        Start-Bearguard $Dev 'DEV'

        Write-Host ""
        Write-Host "Dev is running $Task on demand. Watch it with:"
        Write-Host "  Get-Content '$Dev\logs\account_Default_1.log' -Tail 40 -Wait"
        if (-not $Keep) {
            Write-Host ""
            Write-Host "When you are done:  .\dev-rig.ps1 stop   (stops dev, brings prod back)"
        }
    }
}
