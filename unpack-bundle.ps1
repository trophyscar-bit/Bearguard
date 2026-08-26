# Materialises the runnable layout from the packaged desktop bundle.
#
# packaging/desktop used to leave an unpacked target\input\ directory, and the launcher's classpath
# still points there. It now emits frostguard-<version>-desktop-bundle.zip instead, so a clean
# build DELETES the directory the launcher needs and replaces it with a zip nothing unpacks. On
# 2026-08-25 that took prod down: the build reported success, the launcher found no app jar, and
# the only reason it came back was someone extracting the bundle by hand.
#
# So the launcher stops assuming the layout exists and ensures it instead. Extraction happens only
# when the bundle is newer than what is unpacked, which keeps a normal start cheap.
#
#   .\unpack-bundle.ps1              unpack if the bundle is newer than the unpacked jar
#   .\unpack-bundle.ps1 -WhatIf      report what it would do, change nothing
#
# Exit 0 = a runnable layout is present. 1 = it is not, and why.

param(
    [string]$Root,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Continue'
if (-not $Root) { $Root = Split-Path -Parent $MyInvocation.MyCommand.Path }

$targetDir = Join-Path $Root 'packaging\desktop\target'
$inputDir  = Join-Path $targetDir 'input'

$bundle = Get-ChildItem (Join-Path $targetDir 'frostguard-*-desktop-bundle.zip') -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1
$appJar = Get-ChildItem (Join-Path $inputDir 'frostguard-desktop-*.jar') -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1

# Which bundle the unpacked layout came from. Timestamps cannot answer this: Expand-Archive keeps
# each entry's ORIGINAL time, so the extracted jar always reads older than the zip that carries it,
# and comparing the two re-extracts on every launch forever. Recording the source identity is the
# only reading that stays true.
$stampFile = Join-Path $inputDir '.unpacked-from'
function Get-BundleId($b) { "{0}|{1}" -f $b.Name, $b.LastWriteTimeUtc.Ticks }

if (-not $bundle) {
    # No bundle is fine as long as something already runnable is unpacked -- an installed copy, or
    # a build from before the packaging changed.
    if ($appJar) { Write-Host "No bundle found; using the unpacked build ($($appJar.Name))."; exit 0 }
    Write-Host "[ERROR] No desktop bundle and nothing unpacked under $inputDir."
    Write-Host "        Build first:  fg-build.bat"
    exit 1
}

$wantId = Get-BundleId $bundle
$haveId = if (Test-Path $stampFile) { (Get-Content $stampFile -Raw).Trim() } else { $null }
if ($appJar -and $haveId -eq $wantId) {
    Write-Host "Unpacked build is current ($($appJar.Name)); leaving it alone."
    exit 0
}

$why = if (-not $appJar) { "nothing is unpacked yet" }
       elseif (-not $haveId) { "the unpacked build has no record of which bundle it came from" }
       else { "a different bundle is unpacked" }
Write-Host "Unpacking $($bundle.Name) -- $why."
if ($WhatIf) { Write-Host "  -WhatIf: not extracting."; exit 0 }

try {
    New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
    Expand-Archive -Path $bundle.FullName -DestinationPath $inputDir -Force
} catch {
    Write-Host "[ERROR] Could not unpack the bundle: $($_.Exception.Message)"
    exit 1
}

$appJar = Get-ChildItem (Join-Path $inputDir 'frostguard-desktop-*.jar') -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $appJar) {
    Write-Host "[ERROR] Unpacked the bundle but found no frostguard-desktop-*.jar in $inputDir."
    exit 1
}
Set-Content -Path $stampFile -Value (Get-BundleId $bundle) -Encoding utf8
Write-Host "Unpacked: $($appJar.Name)"
exit 0
