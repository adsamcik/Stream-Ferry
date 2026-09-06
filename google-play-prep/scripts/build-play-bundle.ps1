[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$prepDirectory = Split-Path -Parent $scriptDirectory
$repositoryDirectory = Split-Path -Parent $prepDirectory
$propertiesPath = Join-Path $repositoryDirectory "keystore.properties"
$gradlePropertiesPath = Join-Path $repositoryDirectory "gradle.properties"
$gradleWrapper = Join-Path $repositoryDirectory "gradlew.bat"
$bundlePath = Join-Path $repositoryDirectory "app\build\outputs\bundle\release\app-release.aab"
$artifactDirectory = Join-Path $prepDirectory "artifacts"
$versionLine = Get-Content -LiteralPath $gradlePropertiesPath | Where-Object { $_ -match "^versionName=" } | Select-Object -Last 1
if (-not $versionLine) {
    throw "gradle.properties does not define versionName."
}
$versionName = $versionLine.Split("=", 2)[1].Trim()
$artifactPath = Join-Path $artifactDirectory "stream-ferry-$versionName.aab"
$checksumPath = "$artifactPath.sha256"

if (-not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) {
    throw "Missing ignored keystore.properties. Refusing to build a debug-signed Play release."
}

$properties = @{}
foreach ($line in Get-Content -LiteralPath $propertiesPath) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
        continue
    }
    $parts = $trimmed.Split("=", 2)
    if ($parts.Count -eq 2) {
        $properties[$parts[0].Trim()] = $parts[1].Trim()
    }
}

foreach ($requiredName in @("storeFile", "storePassword", "keyAlias", "keyPassword")) {
    if (-not $properties.ContainsKey($requiredName) -or [string]::IsNullOrWhiteSpace($properties[$requiredName])) {
        throw "keystore.properties is missing '$requiredName'."
    }
}

$keystorePath = $properties["storeFile"]
if (-not [System.IO.Path]::IsPathRooted($keystorePath)) {
    $keystorePath = Join-Path (Join-Path $repositoryDirectory "app") $keystorePath
}
$keystorePath = [System.IO.Path]::GetFullPath($keystorePath)
if (-not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
    throw "The configured keystore does not exist: $keystorePath"
}

$keytool = Get-Command keytool -ErrorAction Stop
$env:STREAM_FERRY_KEYSTORE_PASSWORD = $properties["storePassword"]
try {
    $certificateDetails = & $keytool.Source -list -v -keystore $keystorePath "-storepass:env" STREAM_FERRY_KEYSTORE_PASSWORD -alias $properties["keyAlias"] 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "keytool could not inspect the configured upload key."
    }
}
finally {
    Remove-Item Env:STREAM_FERRY_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
}
if (($certificateDetails | Out-String) -match "CN=Android Debug") {
    throw "The configured certificate is an Android debug key. Refusing to create a Play artifact."
}

Push-Location $repositoryDirectory
try {
    & $gradleWrapper :app:bundleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle release bundle build failed."
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $bundlePath -PathType Leaf)) {
    throw "Gradle completed without producing the expected bundle: $bundlePath"
}

New-Item -ItemType Directory -Force $artifactDirectory | Out-Null
Copy-Item -LiteralPath $bundlePath -Destination $artifactPath -Force
$hash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksumPath -Value "$hash  $([System.IO.Path]::GetFileName($artifactPath))" -Encoding ascii

Write-Host "Prepared signed Play bundle: $artifactPath"
Write-Host "SHA-256: $hash"
