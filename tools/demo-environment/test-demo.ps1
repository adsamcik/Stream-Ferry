[CmdletBinding()]
param([string] $Python)

$ErrorActionPreference = 'Stop'
$pythonPrefix = @()
if (-not $Python) {
    $Python = $env:STREAM_FERRY_DEMO_PYTHON
}
if (-not $Python) {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCommand) {
        $Python = $pythonCommand.Source
    }
    else {
        $launcher = Get-Command py -ErrorAction SilentlyContinue
        if ($launcher) {
            $Python = $launcher.Source
            $pythonPrefix = @('-3')
        }
        else {
            $codexPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
            if (Test-Path -LiteralPath $codexPython) {
                $Python = $codexPython
            }
            else {
                throw 'Python 3 was not found. Pass -Python or set STREAM_FERRY_DEMO_PYTHON.'
            }
        }
    }
}

$tests = Join-Path $PSScriptRoot 'tests'
& $Python @pythonPrefix -m unittest discover $tests -v
exit $LASTEXITCODE
