param(
    [string]$BinaryDirectory,
    [int]$Port = 13306
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $BinaryDirectory) {
    $BinaryDirectory = Join-Path $projectRoot 'runtime-data/tools/mysql-8.4.11-winx64'
}
$serverExe = Join-Path $BinaryDirectory 'bin/mysqld.exe'
$clientExe = Join-Path $BinaryDirectory 'bin/mysql.exe'
if (-not (Test-Path $serverExe)) { throw 'Provide BinaryDirectory for a portable MySQL 8.4 archive.' }
$dbRoot = Join-Path $projectRoot 'runtime-data/local-mysql'
$dataDirectory = Join-Path $dbRoot 'data'
$configPath = Join-Path $dbRoot 'connection.json'
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is occupied. This script never replaces an existing service." }
New-Item -ItemType Directory -Force -Path $dbRoot | Out-Null
$initial = -not (Test-Path $dataDirectory)
if ($initial) {
    & $serverExe --no-defaults --initialize-insecure "--basedir=$BinaryDirectory" "--datadir=$dataDirectory" "--log-error=$dbRoot/initialize.log"
    if ($LASTEXITCODE -ne 0) { throw 'MySQL data initialization failed.' }
} elseif (-not (Test-Path $configPath)) { throw 'Existing data lacks the connection file. Stop and recover configuration; do not reinitialize data.' }
if (-not $initial) {
    $storedRoot = Get-Content (Join-Path $dbRoot 'root-recovery.json') -Raw | ConvertFrom-Json
    if ($storedRoot.port -ne $Port) { throw "Existing database was configured on port $($storedRoot.port). Restart with that port; do not silently change the connection." }
}
$arguments = @('--no-defaults', "`"--basedir=$BinaryDirectory`"", "`"--datadir=$dataDirectory`"", "--port=$Port", '--bind-address=127.0.0.1', '--mysqlx=0', "`"--log-error=$dbRoot/server.log`"", "`"--pid-file=$dbRoot/server.pid`"")
$server = Start-Process -FilePath $serverExe -ArgumentList $arguments -WindowStyle Hidden -PassThru
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        if ($server.HasExited) { throw 'Portable MySQL exited; inspect runtime-data/local-mysql/server.log.' }
        if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Portable MySQL did not become ready.' }
    if ($initial) {
        $rootPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        @{username='root'; password=$rootPassword; port=$Port} | ConvertTo-Json | Set-Content (Join-Path $dbRoot 'root-recovery.json') -Encoding UTF8
        $sql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword'; CREATE DATABASE trippilot CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER 'trippilot'@'127.0.0.1' IDENTIFIED BY '$appPassword'; GRANT ALL PRIVILEGES ON trippilot.* TO 'trippilot'@'127.0.0.1';"
        $sql | & $clientExe --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root
        if ($LASTEXITCODE -ne 0) { throw 'Local database/user setup failed; do not recreate or delete its data.' }
        @{DB_URL="jdbc:mysql://127.0.0.1:$Port/trippilot"; DB_USERNAME='trippilot'; DB_PASSWORD=$appPassword} | ConvertTo-Json | Set-Content $configPath -Encoding UTF8
    }
    # On Windows mysqld uses a supervisor plus a child that owns the listener.
    $listenerPid = [int](Get-Content (Join-Path $dbRoot 'server.pid'))
    $listenerProcess = Get-Process -Id $listenerPid
    if ($listenerProcess.Path -ne (Resolve-Path $serverExe).Path) { throw 'Database listener executable mismatch.' }
    $server.Id | Set-Content (Join-Path $dbRoot 'owned-process-id.txt')
    @{pid=$listenerPid; supervisorPid=$server.Id; executable=(Resolve-Path $serverExe).Path; dataDirectory=$dataDirectory; port=$Port; startedUtcTicks=$listenerProcess.StartTime.ToUniversalTime().Ticks} | ConvertTo-Json | Set-Content (Join-Path $dbRoot 'owned-process.json') -Encoding UTF8
    Write-Output "Portable MySQL started on 127.0.0.1:$Port. Connection configuration: $configPath"
    Write-Output 'Credentials stay in ignored runtime-data; never commit or paste their contents.'
} catch {
    if (-not $server.HasExited) { Stop-Process -Id $server.Id }
    throw
}
