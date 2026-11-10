param(
    [Parameter(Mandatory = $true)]
    [string] $StableImage,

    [Parameter(Mandatory = $true)]
    [string] $NightlyImage
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$stable = (Resolve-Path -LiteralPath $StableImage).Path
$nightly = (Resolve-Path -LiteralPath $NightlyImage).Path
$files = @(
    @{
        Source = Join-Path $nightly "Frostguard Nightly.exe"
        Destination = Join-Path $stable "Frostguard.exe"
        Sha256 = "5c728d3662d64c428d003874f6d62b798bbbe329f595b2b15a2ab5ab1fd1faa9"
    },
    @{
        Source = Join-Path $nightly "FrostguardNightlyWatcher.exe"
        Destination = Join-Path $stable "FrostguardWatcher.exe"
        Sha256 = "9c7452d890f39c7f4fdb2e5519993514c84f071deef222fe49784acfd459c209"
    }
)

# Every launcher is hashed before failing, so one run reports all the bytes the build produced.
$mismatches = @()
foreach ($file in $files) {
    $sourceHash = (Get-FileHash -LiteralPath $file.Source -Algorithm SHA256).Hash
    if ($sourceHash -ine $file.Sha256) {
        $mismatches += "$($file.Source): expected $($file.Sha256), got $($sourceHash.ToLowerInvariant())"
    }
}
if ($mismatches.Count -gt 0) {
    throw "Nightly compatibility bootstrap has unexpected SHA-256:`n$($mismatches -join "`n")"
}

foreach ($file in $files) {
    Copy-Item -LiteralPath $file.Source -Destination $file.Destination -Force
    $destinationHash = (Get-FileHash -LiteralPath $file.Destination -Algorithm SHA256).Hash
    if ($destinationHash -ine $file.Sha256) {
        throw "Stable compatibility bootstrap copy failed: $($file.Destination)"
    }
}
