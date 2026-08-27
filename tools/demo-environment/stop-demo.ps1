$ErrorActionPreference = 'Stop'
$stateFile = Join-Path $PSScriptRoot '.demo-server.json'

if (-not (Test-Path -LiteralPath $stateFile)) {
    Write-Host 'Demo environment is not running.'
    exit 0
}

$state = Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json
$process = Get-Process -Id $state.pid -ErrorAction SilentlyContinue
if ($process) {
    Stop-Process -Id $process.Id
    $process.WaitForExit(5000) | Out-Null
    Write-Host "Stopped demo environment (PID $($state.pid))."
}
Remove-Item -LiteralPath $stateFile -Force
