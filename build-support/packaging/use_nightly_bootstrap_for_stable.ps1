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
# The launchers embed the app icon, so these are the hashes of the Bearguard-icon build, not the
# upstream Frostguard-icon bytes. The fork never shipped the accepted upstream bootstrap.
$files = @(
    @{
        Source = Join-Path $nightly "Frostguard Nightly.exe"
        Destination = Join-Path $stable "Frostguard.exe"
        Sha256 = "0839eabebe4e41aadaa6b21e5ce5e58cf3a39a90ab1945df9867a5150da53ab5"
    },
    @{
        Source = Join-Path $nightly "FrostguardNightlyWatcher.exe"
        Destination = Join-Path $stable "FrostguardWatcher.exe"
        Sha256 = "7da88867920501293e3d2e15d159db390aadd158f89dd78de5333837749d5268"
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
