param([switch]$WithoutDatabase)
$ErrorActionPreference = 'Stop'
if ($WithoutDatabase) { throw 'The identity foundation requires MySQL. Configure DB_URL/DB_USERNAME/DB_PASSWORD and run without -WithoutDatabase.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$outputDir = Join-Path $projectRoot 'runtime-data/scaffold-smoke'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$processes = @()
$savedPassword = $env:DB_PASSWORD
function Wait-Healthy([string]$url, $process) {
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        if ($process.HasExited) { throw "Process exited before $url became healthy; inspect $outputDir." }
        try {
            $health = Invoke-RestMethod $url -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "Health timeout: $url"
}
try {
    foreach ($port in @(18080, 18081, 15173)) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is occupied." }
    }
    # Oracle's javapath launcher can spawn a child JVM; use the actual JVM so cleanup owns it.
    $javaSettings = & (Get-Command java.exe).Source -XshowSettings:properties -version 2>&1
    $javaHomeLine = $javaSettings | Where-Object { "$_" -match '^\s*java.home\s*=' } | Select-Object -First 1
    if (-not $javaHomeLine) { throw 'Could not resolve java.home.' }
    $javaRuntimeHome = ("$javaHomeLine" -split '=', 2)[1].Trim()
    $javaExe = Join-Path $javaRuntimeHome 'bin/java.exe'
    $backendJar = Join-Path $projectRoot 'backend/target/backend-0.1.0-SNAPSHOT.jar'
    $demoJar = Join-Path $projectRoot 'booking-demo/target/booking-demo-0.1.0-SNAPSHOT.jar'
    $backendArguments = @('-jar', "`"$backendJar`"", '--server.port=18080')
    if ($WithoutDatabase) {
        $env:DB_PASSWORD = 'unused-in-database-free-smoke'
        $backendArguments += '--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration'
        $backendArguments += '--spring.flyway.enabled=false'
    } elseif (-not $env:DB_PASSWORD) { throw 'Set DB_PASSWORD for a real MySQL smoke check, or use -WithoutDatabase for security/bootstrap only.' }
    $backend = Start-Process $javaExe -ArgumentList $backendArguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $outputDir 'backend.stdout.log') -RedirectStandardError (Join-Path $outputDir 'backend.stderr.log')
    $processes += $backend
    $smokeProviderLedger=Join-Path $outputDir ('provider-'+[Guid]::NewGuid().ToString('N')+'.json')
    $demo = Start-Process $javaExe -ArgumentList @('-jar', "`"$demoJar`"", '--server.port=18081',"--provider.state-file=$smokeProviderLedger") -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $outputDir 'demo.stdout.log') -RedirectStandardError (Join-Path $outputDir 'demo.stderr.log')
    $processes += $demo
    Wait-Healthy 'http://127.0.0.1:18080/actuator/health' $backend
    Wait-Healthy 'http://127.0.0.1:18081/actuator/health' $demo
    foreach ($path in @('/api/planning/sessions', '/actuator/env', '/actuator/healthcare')) {
        $status = $null
        try { $status = (Invoke-WebRequest "http://127.0.0.1:18080$path" -UseBasicParsing -TimeoutSec 5).StatusCode }
        catch { if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode } else { throw } }
        if ($status -notin @(401, 403)) { throw "Protected path $path returned $status, expected denial." }
        Write-Output "$path denied ($status)"
    }
    $vite = Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'
    $frontend = Start-Process (Get-Command node.exe).Source -ArgumentList @("`"$vite`"", 'preview', '--host', '127.0.0.1', '--port', '15173', '--strictPort') `
        -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $outputDir 'frontend.stdout.log') -RedirectStandardError (Join-Path $outputDir 'frontend.stderr.log')
    $processes += $frontend
    $html = $null
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        try { $html = Invoke-WebRequest 'http://127.0.0.1:15173' -UseBasicParsing -TimeoutSec 2; break } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $html -or $html.StatusCode -ne 200 -or $html.Content -notmatch 'id="root"') { throw 'Frontend preview did not serve the built entry page.' }
    Write-Output 'Backend health, protected routes, demo health and frontend HTTP preview passed.'
    if ($WithoutDatabase) { Write-Output 'Database/Flyway intentionally excluded: migrations and database-backed startup are NOT verified.' }
} finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
            $process.WaitForExit(5000) | Out-Null
        }
    }
    $env:DB_PASSWORD = $savedPassword
}
