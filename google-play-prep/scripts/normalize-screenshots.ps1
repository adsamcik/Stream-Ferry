[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$prepDirectory = Split-Path -Parent $scriptDirectory
$screenshotDirectory = Join-Path $prepDirectory "en-US\graphics\phone-screenshots"

Add-Type -AssemblyName System.Drawing

$screenshots = Get-ChildItem -LiteralPath $screenshotDirectory -Filter "*.png" | Sort-Object Name
if ($screenshots.Count -lt 2) {
    throw "Expected at least two Google Play screenshots in $screenshotDirectory."
}

foreach ($screenshot in $screenshots) {
    $source = [System.Drawing.Image]::FromFile($screenshot.FullName)
    try {
        if ($source.Width -ne 1080 -or $source.Height -ne 1920) {
            throw "Screenshot $($screenshot.Name) is $($source.Width)x$($source.Height), expected 1080x1920."
        }

        $normalized = New-Object System.Drawing.Bitmap 1080, 1920, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($normalized)
            try {
                $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
                $graphics.DrawImageUnscaled($source, 0, 0)
            }
            finally {
                $graphics.Dispose()
            }

            $temporaryPath = "$($screenshot.FullName).normalized.png"
            $normalized.Save($temporaryPath, [System.Drawing.Imaging.ImageFormat]::Png)
        }
        finally {
            $normalized.Dispose()
        }
    }
    finally {
        $source.Dispose()
    }

    Move-Item -LiteralPath $temporaryPath -Destination $screenshot.FullName -Force
    Write-Host "Normalized $($screenshot.Name) to 1080x1920 24-bit PNG."
}
