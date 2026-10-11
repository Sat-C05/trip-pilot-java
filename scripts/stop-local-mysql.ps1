$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$dbRoot = Join-Path $projectRoot 'runtime-data/local-mysql'
$ownershipPath = Join-Path $dbRoot 'owned-process.json'
if (-not (Test-Path $ownershipPath)) { throw 'No owned-process manifest. Do not stop an unidentified database process.' }
$ownership = Get-Content $ownershipPath -Raw | ConvertFrom-Json
$ownedProcess = Get-Process -Id $ownership.pid -ErrorAction SilentlyContinue
if (-not $ownedProcess) { Write-Output 'The owned local MySQL process is already stopped.'; return }
if ($ownedProcess.Path -ne $ownership.executable -or $ownedProcess.StartTime.ToUniversalTime().Ticks -ne $ownership.startedUtcTicks) { throw 'Process identity changed. Refusing to stop it.' }
$rootConfig = Get-Content (Join-Path $dbRoot 'root-recovery.json') -Raw | ConvertFrom-Json
if ($rootConfig.port -ne $ownership.port) { throw 'Recovery connection port differs from the owned database. Refusing shutdown.' }
$listener = Get-NetTCPConnection -LocalPort $ownership.port -State Listen -ErrorAction Stop
if (@($listener | Where-Object OwningProcess -eq $ownership.pid).Count -eq 0) { throw 'The expected port does not belong to the owned process.' }
$clientFile = Join-Path $dbRoot 'shutdown-client.ini'
$clientExe = Join-Path (Split-Path $ownership.executable -Parent) 'mysql.exe'
try {
    "[client]`nuser=root`npassword=$($rootConfig.password)`nhost=127.0.0.1`nport=$($rootConfig.port)`nprotocol=TCP`n" | Set-Content $clientFile -Encoding UTF8
    'SHUTDOWN;' | & $clientExe "--defaults-file=$clientFile"
    if ($LASTEXITCODE -ne 0) { throw 'Graceful shutdown failed; the process has not been forcibly killed.' }
    if (-not $ownedProcess.WaitForExit(15000)) { throw 'MySQL did not finish shutdown within 15 seconds.' }
    Write-Output 'Owned portable MySQL stopped gracefully; its data is preserved.'
} finally { Remove-Item -LiteralPath $clientFile -ErrorAction SilentlyContinue }
