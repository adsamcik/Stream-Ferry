[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$prepDirectory = Split-Path -Parent $scriptDirectory
$repositoryDirectory = Split-Path -Parent $prepDirectory
$graphicsDirectory = Join-Path $prepDirectory "en-US\graphics"
$iconSource = Join-Path $repositoryDirectory "app\src\main\res\drawable-nodpi\ic_launcher_foreground_art_v2.png"
$featureSource = Join-Path $repositoryDirectory "website\og.png"
$iconOutput = Join-Path $graphicsDirectory "icon.png"
$featureOutput = Join-Path $graphicsDirectory "feature-graphic.png"

Add-Type -AssemblyName System.Drawing

function New-HighQualityGraphics([System.Drawing.Bitmap]$Bitmap) {
    $graphics = [System.Drawing.Graphics]::FromImage($Bitmap)
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    return $graphics
}

New-Item -ItemType Directory -Force $graphicsDirectory | Out-Null

$icon = [System.Drawing.Image]::FromFile($iconSource)
try {
    $iconBitmap = New-Object System.Drawing.Bitmap 512, 512, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $graphics = New-HighQualityGraphics $iconBitmap
        try {
            $graphics.DrawImage($icon, 0, 0, 512, 512)
        }
        finally {
            $graphics.Dispose()
        }
        $iconBitmap.Save($iconOutput, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $iconBitmap.Dispose()
    }
}
finally {
    $icon.Dispose()
}

$feature = [System.Drawing.Image]::FromFile($featureSource)
try {
    $targetRatio = 1024.0 / 500.0
    $sourceRatio = $feature.Width / [double]$feature.Height
    if ($sourceRatio -gt $targetRatio) {
        $cropHeight = $feature.Height
        $cropWidth = [int][Math]::Round($cropHeight * $targetRatio)
        $cropX = [int][Math]::Floor(($feature.Width - $cropWidth) / 2.0)
        $cropY = 0
    }
    else {
        $cropWidth = $feature.Width
        $cropHeight = [int][Math]::Round($cropWidth / $targetRatio)
        $cropX = 0
        $cropY = [int][Math]::Floor(($feature.Height - $cropHeight) / 2.0)
    }

    $featureBitmap = New-Object System.Drawing.Bitmap 1024, 500, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    try {
        $graphics = New-HighQualityGraphics $featureBitmap
        try {
            $sourceRectangle = New-Object System.Drawing.Rectangle $cropX, $cropY, $cropWidth, $cropHeight
            $targetRectangle = New-Object System.Drawing.Rectangle 0, 0, 1024, 500
            $graphics.DrawImage($feature, $targetRectangle, $sourceRectangle, [System.Drawing.GraphicsUnit]::Pixel)
        }
        finally {
            $graphics.Dispose()
        }
        $featureBitmap.Save($featureOutput, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $featureBitmap.Dispose()
    }
}
finally {
    $feature.Dispose()
}

Write-Host "Prepared Google Play icon: $iconOutput"
Write-Host "Prepared Google Play feature graphic: $featureOutput"
