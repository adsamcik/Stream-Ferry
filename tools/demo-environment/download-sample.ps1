[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$mediaRoot = Join-Path $PSScriptRoot 'media'
$videoPath = Join-Path $mediaRoot 'big-buck-bunny-480p-30sec.mp4'
$posterPath = Join-Path $mediaRoot 'big-buck-bunny-poster.jpg'

$videoUrl = 'https://raw.githubusercontent.com/chthomos/video-media-samples/master/big-buck-bunny-480p-30sec.mp4'
$posterUrl = 'https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Big_buck_bunny_poster_big.jpg/500px-Big_buck_bunny_poster_big.jpg'

New-Item -ItemType Directory -Force -Path $mediaRoot | Out-Null

function Get-DemoAsset {
    param(
        [Parameter(Mandatory)] [string]$Url,
        [Parameter(Mandatory)] [string]$Destination,
        [Parameter(Mandatory)] [long]$MinimumBytes,
        [Parameter(Mandatory)] [string]$ExpectedSha256
    )

    if ((-not $Force) -and (Test-Path -LiteralPath $Destination)) {
        $existing = Get-Item -LiteralPath $Destination
        if ($existing.Length -ge $MinimumBytes) {
            $existingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Destination).Hash.ToLowerInvariant()
            if ($existingHash -eq $ExpectedSha256) {
                Write-Host "Already present: $Destination ($($existing.Length) bytes)"
                return
            }
        }
    }

    $partial = "$Destination.download"
    Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
    try {
        Invoke-WebRequest -Uri $Url -OutFile $partial -UseBasicParsing -UserAgent 'Stream-Ferry-Demo/1.0 (local test fixture)'
        $downloaded = Get-Item -LiteralPath $partial
        if ($downloaded.Length -lt $MinimumBytes) {
            throw "Downloaded asset is unexpectedly small: $($downloaded.Length) bytes"
        }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $partial).Hash.ToLowerInvariant()
        if ($hash -ne $ExpectedSha256) {
            throw "Downloaded asset checksum mismatch. Expected $ExpectedSha256 but received $hash"
        }
        Move-Item -LiteralPath $partial -Destination $Destination -Force
        Write-Host "Downloaded: $Destination"
        Write-Host "SHA-256:   $hash"
    }
    finally {
        Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue
    }
}

Get-DemoAsset -Url $videoUrl -Destination $videoPath -MinimumBytes 1MB -ExpectedSha256 '521a78e22a4065814066d8a60d53b99472a80d6a8fb0908370df0410298b4b12'
Get-DemoAsset -Url $posterUrl -Destination $posterPath -MinimumBytes 50KB -ExpectedSha256 'ef7d56dddeed39353f77190682cbb5973b0a70133a4e64123bde29f8bb6e69de'

Write-Host ''
Write-Host 'License and attribution: tools/demo-environment/ATTRIBUTION.md'
