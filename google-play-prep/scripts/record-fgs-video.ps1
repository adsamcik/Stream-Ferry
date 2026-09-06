[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("data-sync", "media-playback")]
    [string] $Scenario,

    [string] $Device = "emulator-5554",

    [ValidateRange(10, 180)]
    [int] $TimeLimitSeconds = 80
)

$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$prepDirectory = Split-Path -Parent $scriptDirectory
$outputDirectory = Join-Path $prepDirectory "fgs-videos"
$outputPath = Join-Path $outputDirectory "$Scenario-demo.mp4"
$remotePath = "/sdcard/stream-ferry-$Scenario.mp4"

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $sdkAdb)) {
        throw "adb was not found on PATH or at $sdkAdb."
    }
    $adbPath = $sdkAdb
}
else {
    $adbPath = $adb.Source
}

New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

& $adbPath -s $Device shell rm -f $remotePath
if ($LASTEXITCODE -ne 0) {
    throw "Could not clear the previous temporary recording on $Device."
}

Write-Host "Recording $Scenario on $Device for up to $TimeLimitSeconds seconds. Complete the scenario, then wait for capture to finish."
& $adbPath -s $Device shell screenrecord --bit-rate 8000000 --time-limit $TimeLimitSeconds $remotePath
if ($LASTEXITCODE -ne 0) {
    throw "Android screen recording failed with exit code $LASTEXITCODE."
}

& $adbPath -s $Device pull $remotePath $outputPath
if ($LASTEXITCODE -ne 0) {
    throw "Could not pull the recording from $Device."
}

& $adbPath -s $Device shell rm $remotePath
Write-Host "Saved local-only declaration video to $outputPath."
