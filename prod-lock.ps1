# Advisory lock for the shared prod checkout at C:\Bearguard.
#
# Several agent sessions and a human share ONE working copy. Nothing in git serialises them, and
# on 2026-08-23 that produced two failures in one afternoon: a session checked prod out onto a
# feature branch for PR work, which silently emptied the working directory of the ops tooling and
# stopped settings backups with no error anywhere; and then two sessions independently noticed and
# switched it back, minutes apart, neither aware of the other. Builds have the same exposure --
# Maven compiles the working tree, so a build started by one session ships whatever another
# session happens to have sitting there.
#
# This is ADVISORY. It cannot stop a bare `mvn` or a stray `git checkout`; it only works because
# the scripts call it and people honour it. That is the point -- it makes "someone else is in
# here" visible at the moment you would otherwise collide, and it records WHO so the next person
# can ask rather than guess.
#
# Acquire before ANY prod operation: stopping the app, building, cherry-picking, switching branch.
#
#   .\prod-lock.ps1 acquire -Owner lol-bb -Reason "deploy df71bf3"
#   .\prod-lock.ps1 release -Owner lol-bb
#   .\prod-lock.ps1 status
#   .\prod-lock.ps1 acquire -Owner lol-bb -Reason "..." -Force    # take a stale lock deliberately
#
# Exit codes: 0 acquired/released/free, 1 held by someone else, 2 usage error.

param(
    [Parameter(Position = 0)][ValidateSet('acquire', 'release', 'status')][string]$Action = 'status',
    [string]$Owner,
    [string]$Reason = '',
    [switch]$Force,
    [switch]$Auto,
    [int]$StaleMinutes = 20,
    [int]$AutoStaleMinutes = 10,
    [string]$Root
)

$ErrorActionPreference = 'Continue'

# Windows PowerShell 5.1 leaves $PSScriptRoot empty inside a param() default when the script is
# invoked with -File, which silently produced a lock path of '.prod-lock' relative to nothing and
# made every operation report "unlocked". Resolve it after the param block instead.
if (-not $Root) { $Root = Split-Path -Parent $MyInvocation.MyCommand.Path }
$LockFile = Join-Path $Root '.prod-lock'

function Read-Lock {
    if (-not (Test-Path $LockFile)) { return $null }
    try { Get-Content $LockFile -Raw | ConvertFrom-Json } catch { $null }
}

function Show-Lock($lock) {
    # Both sides in UTC. Comparing a UTC stamp against local Get-Date offset every age by the
    # timezone, which read as -240 min on a fresh lock and pushed the stale threshold out to
    # roughly four hours -- a forgotten lock would have blocked everyone for most of a day.
    $age = [math]::Round(((Get-Date).ToUniversalTime() - [datetime]$lock.acquiredUtc).TotalMinutes, 1)
    Write-Host ("  held by : {0}" -f $lock.owner)
    Write-Host ("  since   : {0} UTC ({1} min ago)" -f $lock.acquiredUtc, $age)
    Write-Host ("  reason  : {0}" -f $lock.reason)
    Write-Host ("  pid     : {0}" -f $lock.pid)
    if ($lock.auto) { Write-Host "  kind    : auto-claim (expires sooner than a deliberate lock)" }
    return $age
}

switch ($Action) {

    'status' {
        $lock = Read-Lock
        if (-not $lock) { Write-Host "prod is UNLOCKED ($Root)"; exit 0 }
        Write-Host "prod is LOCKED ($Root)"
        $null = Show-Lock $lock
        exit 1
    }

    'release' {
        if (-not $Owner) { Write-Host "[ERROR] release needs -Owner"; exit 2 }
        $lock = Read-Lock
        if (-not $lock) { Write-Host "prod was already unlocked."; exit 0 }
        if ($lock.owner -ne $Owner -and -not $Force) {
            # Releasing someone else's lock is how you end up building under their stopped app.
            Write-Host "[ERROR] the lock belongs to '$($lock.owner)', not '$Owner'. Use -Force only if you know they are gone."
            $null = Show-Lock $lock
            exit 1
        }
        Remove-Item $LockFile -Force -ErrorAction SilentlyContinue
        Write-Host "prod unlocked by $Owner."
        exit 0
    }

    'acquire' {
        if (-not $Owner) { Write-Host "[ERROR] acquire needs -Owner (use your session name)"; exit 2 }

        $lock = Read-Lock
        if ($lock) {
            $age = Show-Lock $lock
            if ($lock.owner -eq $Owner) {
                Write-Host "prod already locked by you ($Owner); continuing."
                exit 0
            }
            # An auto-claim is a safety net taken on someone's behalf by stop-bearguard, not a
            # reservation they chose to make -- three were left behind in one evening because
            # nothing in the caller's flow releases them. Expire those sooner than a deliberate
            # claim so a forgotten one blocks people for minutes rather than a third of an hour.
            $limit = if ($lock.auto) { $AutoStaleMinutes } else { $StaleMinutes }
            if (-not $Force -and $age -lt $limit) {
                Write-Host "[BLOCKED] prod is in use by '$($lock.owner)'. Ask them before proceeding."
                Write-Host "          If they are definitely gone: -Force"
                exit 1
            }
            # A lock older than StaleMinutes usually means a session ended mid-operation. Say whose
            # it was rather than removing it quietly -- a forgotten lock and an abandoned deploy
            # look identical from here, and the difference matters.
            Write-Host ("[WARN] taking a lock that is {0} min old from '{1}'{2}." -f `
                $age, $lock.owner, $(if ($Force) { ' (-Force)' } else { ' (stale)' }))
        }

        $payload = [ordered]@{
            owner       = $Owner
            reason      = $Reason
            acquiredUtc = (Get-Date).ToUniversalTime().ToString('s')
            pid         = $PID
            host        = $env:COMPUTERNAME
            auto        = [bool]$Auto
        } | ConvertTo-Json -Compress

        # CreateNew is atomic, so two sessions racing here cannot both win. Only reached when the
        # file is absent or we just decided to replace it.
        try {
            if (Test-Path $LockFile) { Remove-Item $LockFile -Force }
            $fs = [System.IO.File]::Open($LockFile, [System.IO.FileMode]::CreateNew,
                                         [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
            $fs.Write($bytes, 0, $bytes.Length)
            $fs.Close()
        } catch {
            Write-Host "[BLOCKED] another session won the race for the lock: $($_.Exception.Message)"
            exit 1
        }

        Write-Host "prod locked by $Owner$(if ($Reason) { " -- $Reason" })."
        exit 0
    }
}
