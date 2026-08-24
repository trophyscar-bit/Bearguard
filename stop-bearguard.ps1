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
    [switch]$ListOnly,
    [string]$Owner
)

$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path -LiteralPath $Root).Path

# Warn, never block. Stopping prod is sometimes exactly what has to happen in a hurry, and a lock
# left behind by a session that has since ended must not stand between anyone and a stuck app.
#
# Three cases, and the distinction matters. The first version of this warned whenever ANY lock was
# held, because it had no -Owner to compare against -- so it told the one session doing it
# correctly that it was "another session", which is how a warning gets trained into background
# noise. It also said nothing at all when NO lock was held, which is the case worth catching:
# stopping prod without claiming it is what leaves a window for someone else to stop or build on
# top of you.
if (-not $Owner) { $Owner = $env:BEARGUARD_OWNER }
$lockFile = Join-Path $Root '.prod-lock'
$holder = $null
if (Test-Path $lockFile) {
    try { $holder = (Get-Content $lockFile -Raw | ConvertFrom-Json) } catch { $holder = $null }
}
if (-not $holder) {
    # Claim it rather than merely complaining. A warning only works on someone who is reading, and
    # at 16:59 today a stop went out unclaimed with the warning right there in the output; a minute
    # later a second javaw was launched against the same emulator and the same SQLite file, and the
    # only thing that prevented two bots running at once was one of them losing the frostguard.lock
    # race. The unclaimed window is the hazard, so close it instead of narrating it.
    #
    # Still never blocks: if the claim fails, say so and stop anyway.
    if ($ListOnly) {
        Write-Host "prod is unclaimed (-ListOnly, so not claiming it)."
    }
    else {
        $script:autoOwner = if ($Owner) { $Owner } else { "$env:USERNAME-stop-$PID" }
        $lockScript = Join-Path $Root 'prod-lock.ps1'
        if (Test-Path $lockScript) {
            & $lockScript acquire -Owner $script:autoOwner -Reason 'auto-claimed by stop-bearguard' -Auto -Root $Root | Out-Null
            if ($LASTEXITCODE -eq 0) {
                $script:autoClaimed = $true
                Write-Host "prod was unclaimed -- claimed it as '$script:autoOwner' for the duration of this stop."
                Write-Host "  it will be released automatically when this stop finishes."
            }
            else {
                Write-Warning "prod is unclaimed and the claim failed (someone raced us) -- stopping anyway."
            }
        }
        else {
            Write-Warning "stopping prod WITHOUT claiming it (prod-lock.ps1 not found)."
        }
    }
}
elseif ($Owner -and $holder.owner -eq $Owner) {
    Write-Host "prod is claimed by you ($Owner) -- proceeding."
}
else {
    Write-Warning "prod is locked by '$($holder.owner)' -- stopping anyway, but check with them:"
    Write-Host   "  reason : $($holder.reason)"
    Write-Host   "  since  : $($holder.acquiredUtc) UTC"
    if (-not $Owner) {
        Write-Host "  (pass -Owner <you>, or set BEARGUARD_OWNER, so this can tell your own lock from someone else's)"
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

# Give back what this script took. An auto-claim covers the stop and nothing more: it exists to
# close the window where prod is being shut down with nobody's name on it, not to reserve prod for
# whatever the caller does next. Claiming without releasing left a lock behind after every stop --
# one sat for 50 minutes on 2026-08-23 and another session had to force past it -- and a lock that
# is routinely forced past has stopped being a lock. A claim somebody made deliberately is left
# exactly where it is; only the automatic one is handed back.
function Release-AutoClaim {
    if (-not $script:autoClaimed) { return }
    $lockScript = Join-Path $Root 'prod-lock.ps1'
    if (-not (Test-Path $lockScript)) { return }
    & $lockScript release -Owner $script:autoOwner -Root $Root | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "released the automatic claim '$script:autoOwner'."
    } else {
        Write-Warning "could not release the automatic claim '$script:autoOwner' -- release it by hand."
    }
}

$left = @(Get-BearguardProcesses $Root).Count
if ($left -gt 0) {
    Write-Warning "$left Bearguard process(es) still running at $Root."
    Release-AutoClaim
    exit 1
}
Release-AutoClaim
Write-Host "Bearguard stopped cleanly at $Root."
exit 0
