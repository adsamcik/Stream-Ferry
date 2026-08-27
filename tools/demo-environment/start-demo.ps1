[CmdletBinding()]
param(
    [switch]$Foreground,
    [int]$JellyfinPort = 8096,
    [int]$RendererPort = 8097,
    [string]$Python
)

$ErrorActionPreference = 'Stop'
$pythonPrefix = @()
if (-not $Python) {
    $Python = $env:STREAM_FERRY_DEMO_PYTHON
}
if (-not $Python) {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand) {
        $Python = $pythonCommand.Source
    } else {
        $launcher = Get-Command py -ErrorAction SilentlyContinue
        if ($launcher) {
            $Python = $launcher.Source
            $pythonPrefix = @('-3')
        } else {
            $codexPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
            if (Test-Path -LiteralPath $codexPython) {
                $Python = $codexPython
            } else {
                throw 'Python 3 was not found. Pass -Python or set STREAM_FERRY_DEMO_PYTHON.'
            }
        }
    }
}
$server = Join-Path $PSScriptRoot 'demo_server.py'
$video = Join-Path $PSScriptRoot 'media\big-buck-bunny-480p-30sec.mp4'
$poster = Join-Path $PSScriptRoot 'media\big-buck-bunny-poster.jpg'
$stateFile = Join-Path $PSScriptRoot '.demo-server.json'
$logFile = Join-Path $PSScriptRoot 'demo-server.log'
$errorLogFile = Join-Path $PSScriptRoot 'demo-server.error.log'

if (-not (Test-Path -LiteralPath $video) -or -not (Test-Path -LiteralPath $poster)) {
    throw 'Demo media is missing. Run tools/demo-environment/download-sample.ps1 first.'
}

if (Test-Path -LiteralPath $stateFile) {
    $old = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
    $oldProcess = Get-Process -Id $old.pid -ErrorAction SilentlyContinue
    if ($oldProcess) {
        Write-Host "Demo environment is already running (PID $($old.pid))."
        Write-Host "Jellyfin: http://127.0.0.1:$($old.jellyfinPort)"
        Write-Host "Receiver: http://127.0.0.1:$($old.rendererPort)"
        exit 0
    }
    Remove-Item -LiteralPath $stateFile -Force
}

$arguments = $pythonPrefix + @(
    $server,
    '--video', $video,
    '--poster', $poster,
    '--jellyfin-port', $JellyfinPort,
    '--renderer-port', $RendererPort
)

if ($Foreground) {
    & $Python @arguments
    exit $LASTEXITCODE
}

$process = Start-Process -FilePath $Python -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $logFile -RedirectStandardError $errorLogFile
$state = [ordered]@{
    pid = $process.Id
    jellyfinPort = $JellyfinPort
    rendererPort = $RendererPort
    startedAt = (Get-Date).ToString('o')
}
$state | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding utf8

$ready = $false
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    Start-Sleep -Milliseconds 250
    try {
        $info = Invoke-RestMethod -Uri "http://127.0.0.1:$JellyfinPort/System/Info/Public" -TimeoutSec 1
        if ($info.Id -eq 'stream-ferry-demo-server') {
            $ready = $true
            break
        }
    } catch { }
}

if (-not $ready) {
    throw "Demo environment did not become ready. Inspect $logFile"
}

Write-Host 'Stream Ferry demo environment is ready.'
Write-Host "Jellyfin for Android emulator: http://10.0.2.2:$JellyfinPort"
Write-Host 'Username: demo'
Write-Host 'Password: streamferry'
Write-Host "Demo receiver dashboard: http://127.0.0.1:$RendererPort"
Write-Host ''
Write-Host 'Build the opt-in app with: .\gradlew :app:assembleDebug -PdemoEnvironment=true'
