param([switch]$Offline)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repo 'gradlew.bat'
$tasks = @(
    ':app:testDebugUnitTest', ':stimulus-app:testDebugUnitTest',
    ':app:lintDebug', ':stimulus-app:lintDebug',
    ':app:assembleDebug', ':stimulus-app:assembleDebug', '--no-daemon'
)
if ($Offline) { $tasks += '--offline' }

Push-Location $repo
try {
    & $gradle @tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle validation failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
