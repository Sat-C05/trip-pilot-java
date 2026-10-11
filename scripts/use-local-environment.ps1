$tripPilotRoot = Split-Path $PSScriptRoot -Parent
$tripPilotConfigPath = Join-Path $tripPilotRoot 'runtime-data/local-mysql/connection.json'
if (-not (Test-Path $tripPilotConfigPath)) { throw 'Run start-local-mysql.ps1 first, or configure database environment variables manually.' }
$tripPilotConnection = Get-Content $tripPilotConfigPath -Raw | ConvertFrom-Json
foreach ($tripPilotName in @('DB_URL','DB_USERNAME','DB_PASSWORD')) {
    [Environment]::SetEnvironmentVariable($tripPilotName, $tripPilotConnection.$tripPilotName, 'Process')
}
$tripPilotJdk = Get-ChildItem (Join-Path $tripPilotRoot 'runtime-data/tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($tripPilotJdk) {
    $env:JAVA_HOME = $tripPilotJdk.FullName
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}
Write-Output 'Local database configuration loaded into this process; credential values are not displayed.'
