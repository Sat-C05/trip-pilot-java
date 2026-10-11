param([int]$ModelPort = 11437, [switch]$DatabaseOnly, [string]$TestSelection, [switch]$Smoke)
$ErrorActionPreference = 'Stop'
$tripPilotRoot = Split-Path $PSScriptRoot -Parent
$tripPilotSuffix = [Guid]::NewGuid().ToString('N').Substring(0,12)
$tripPilotDatabase = "trippilot_verify_$tripPilotSuffix"
$tripPilotUser = "tpverify_$tripPilotSuffix"
$tripPilotOutput = Join-Path $tripPilotRoot "runtime-data/phase3/$tripPilotSuffix"
New-Item -ItemType Directory -Force -Path $tripPilotOutput | Out-Null
$tripPilotRootConfig = Get-Content (Join-Path $tripPilotRoot 'runtime-data/local-mysql/root-recovery.json') -Raw | ConvertFrom-Json
if ($tripPilotRootConfig.port -ne 13306) { throw 'Verification requires the isolated project MySQL on 13306.' }
$tripPilotClient = Join-Path $tripPilotRoot 'runtime-data/tools/mysql-8.4.11-winx64/bin/mysql.exe'
$tripPilotClientConfig = Join-Path $tripPilotOutput 'root-client.ini'
$tripPilotAppConfig = Join-Path $tripPilotOutput 'connection.json'
$tripPilotPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$tripPilotSaved = @{}
foreach ($tripPilotKey in @('DB_URL','DB_USERNAME','DB_PASSWORD','PHASE1_DB_TESTS','MODEL_BASE_URL')) {
    $tripPilotSaved[$tripPilotKey] = [Environment]::GetEnvironmentVariable($tripPilotKey,'Process')
}
$tripPilotCreated = $false
Push-Location $tripPilotRoot
try {
    "[client]`nuser=root`npassword=$($tripPilotRootConfig.password)`nhost=127.0.0.1`nport=13306`nprotocol=TCP`n" | Set-Content -LiteralPath $tripPilotClientConfig -Encoding UTF8
    # Names and password contain only generated alphanumeric/hex characters. Never target the application schema.
    "CREATE DATABASE $tripPilotDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER '$tripPilotUser'@'127.0.0.1' IDENTIFIED BY '$tripPilotPassword'; GRANT ALL PRIVILEGES ON $tripPilotDatabase.* TO '$tripPilotUser'@'127.0.0.1';" |
        & $tripPilotClient "--defaults-file=$tripPilotClientConfig"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated verification schema/account.' }
    $tripPilotCreated = $true
    @{DB_URL="jdbc:mysql://127.0.0.1:13306/$tripPilotDatabase"; DB_USERNAME=$tripPilotUser; DB_PASSWORD=$tripPilotPassword} |
        ConvertTo-Json | Set-Content -LiteralPath $tripPilotAppConfig -Encoding UTF8
    if ($DatabaseOnly) {
        . (Join-Path $PSScriptRoot 'use-local-environment.ps1')
        $env:DB_URL="jdbc:mysql://127.0.0.1:13306/$tripPilotDatabase"
        $env:DB_USERNAME=$tripPilotUser
        $env:DB_PASSWORD=$tripPilotPassword
        $env:PHASE1_DB_TESTS='true'
        $tripPilotArguments=@('-B','-ntp','-pl','backend')
        if ($TestSelection) { $tripPilotArguments += "-Dtest=$TestSelection" }
        $tripPilotArguments += $(if ($Smoke) { 'package' } else { 'test' })
        & (Join-Path $tripPilotRoot 'mvnw.cmd') @tripPilotArguments
        if ($LASTEXITCODE -ne 0) { throw 'Phase 3 database verification failed.' }
    } else {
        & (Join-Path $PSScriptRoot 'verify-model-adapter.ps1') -Port $ModelPort -Phase2 -DatabaseConfiguration $tripPilotAppConfig
    }
    if ($Smoke) {
        if (-not (Test-Path (Join-Path $tripPilotRoot 'backend/target/backend-0.1.0-SNAPSHOT.jar'))) { throw 'Build the packaged services before smoke verification.' }
        # An empty inherited environment value suppresses Spring's placeholder default.
        if ([string]::IsNullOrWhiteSpace($env:MODEL_BASE_URL)) { $env:MODEL_BASE_URL='http://127.0.0.1:11436' }
        & (Join-Path $PSScriptRoot 'smoke-scaffold.ps1')
    }
    if ($DatabaseOnly) {
        Write-Output 'Focused Phase 3 verification passed; real-model acceptance is still required. Existing services/data were preserved.'
    } else {
        Write-Output 'Full Phase 3 verification passed against an isolated disposable schema; existing services/data were preserved.'
    }
} finally {
    if ($tripPilotCreated) {
        if ($tripPilotDatabase -notmatch '^trippilot_verify_[a-f0-9]{12}$' -or $tripPilotUser -notmatch '^tpverify_[a-f0-9]{12}$') { throw 'Refusing unsafe verification cleanup names.' }
        "DROP DATABASE $tripPilotDatabase; DROP USER '$tripPilotUser'@'127.0.0.1';" | & $tripPilotClient "--defaults-file=$tripPilotClientConfig"
        if ($LASTEXITCODE -ne 0) { Write-Warning 'Verification schema cleanup failed; inspect this run locally.' }
    }
    Remove-Item -LiteralPath $tripPilotClientConfig,$tripPilotAppConfig -ErrorAction SilentlyContinue
    foreach ($tripPilotKey in $tripPilotSaved.Keys) {
        if ($null -eq $tripPilotSaved[$tripPilotKey]) { Remove-Item -LiteralPath "Env:$tripPilotKey" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($tripPilotKey,$tripPilotSaved[$tripPilotKey],'Process') }
    }
    Pop-Location
}
