param([int]$Port = 11436, [switch]$Phase2, [string]$DatabaseConfiguration)
$ErrorActionPreference = 'Stop'
$tripPilotRoot = Split-Path $PSScriptRoot -Parent
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Adapter verification port $Port is occupied; choose another port."
}
$tripPilotOutput = Join-Path $tripPilotRoot ('runtime-data/model-adapter/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path $tripPilotOutput | Out-Null
$tripPilotVars = @{
    OLLAMA_HOST = "127.0.0.1:$Port"; OLLAMA_NUM_PARALLEL = '1'; OLLAMA_MAX_LOADED_MODELS = '1'
    OLLAMA_MAX_QUEUE = '3'; OLLAMA_KEEP_ALIVE = '5m'; PHASE2_MODEL_TESTS = 'true'; MODEL_BASE_URL = "http://127.0.0.1:$Port"
    MAVEN_OPTS = "$env:MAVEN_OPTS -Xmx256m"
}
$tripPilotSaved = @{}
if ($Phase2) {
    $tripPilotVars.PHASE1_DB_TESTS = 'true'
    $tripPilotVars.PHASE2_ONLINE_TESTS = 'true'
    $tripPilotVars.PHASE2_LIVE_TESTS = 'true'
}
$tripPilotRuntime = $null
Push-Location $tripPilotRoot
try {
    . (Join-Path $PSScriptRoot 'use-local-environment.ps1')
    if ($DatabaseConfiguration) {
        $tripPilotVerificationConnection = Get-Content -LiteralPath $DatabaseConfiguration -Raw | ConvertFrom-Json
        foreach ($tripPilotName in @('DB_URL','DB_USERNAME','DB_PASSWORD')) {
            [Environment]::SetEnvironmentVariable($tripPilotName, $tripPilotVerificationConnection.$tripPilotName, 'Process')
        }
    }
    foreach ($tripPilotKey in $tripPilotVars.Keys) {
        $tripPilotSaved[$tripPilotKey] = [Environment]::GetEnvironmentVariable($tripPilotKey, 'Process')
        [Environment]::SetEnvironmentVariable($tripPilotKey, $tripPilotVars[$tripPilotKey], 'Process')
    }
    $tripPilotRuntime = Start-Process -FilePath (Get-Command ollama.exe -ErrorAction Stop).Source -ArgumentList 'serve' `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $tripPilotOutput 'runtime.out.log') `
        -RedirectStandardError (Join-Path $tripPilotOutput 'runtime.err.log')
    $tripPilotReady = $false
    for ($tripPilotAttempt = 0; $tripPilotAttempt -lt 30; $tripPilotAttempt++) {
        if ($tripPilotRuntime.HasExited) { throw 'Dedicated Ollama runtime exited before verification.' }
        try {
            Invoke-RestMethod "$env:MODEL_BASE_URL/api/version" -TimeoutSec 1 | Out-Null
            $tripPilotReady = $true; break
        } catch { Start-Sleep -Milliseconds 300 }
    }
    if (-not $tripPilotReady) { throw 'Dedicated runtime did not become ready.' }
    # Load before the verification JVMs reserve heap; runtime failures still fail acceptance.
    Invoke-RestMethod "$env:MODEL_BASE_URL/api/chat" -Method Post -ContentType application/json `
        -Body '{"model":"gemma4:e4b","messages":[],"stream":false,"keep_alive":"5m","options":{"num_ctx":4096}}' -TimeoutSec 90 | Out-Null
    if ($Phase2) {
        & (Join-Path $tripPilotRoot 'mvnw.cmd') -B -ntp '-DargLine=-Xmx384m' verify
    } else {
        & (Join-Path $tripPilotRoot 'mvnw.cmd') -B -ntp -pl backend '-DargLine=-Xmx384m' '-Dtest=OllamaModelAdapterTest,LocalModelAdapterTest' test
    }
    if ($LASTEXITCODE -ne 0) { throw 'Model adapter verification failed.' }
    Write-Output 'Model adapter verification passed: transport/failure tests and real pinned local inference.'
    if ($Phase2) { Write-Output 'Phase 2 verification passed: real MySQL lifecycle/access/SSE, worker limits and live online research.' }
} finally {
    if ($tripPilotRuntime -and -not $tripPilotRuntime.HasExited) {
        # Ollama's model runner may survive server exit. Capture only this owned server's children.
        $tripPilotOwnedChildren = @()
        foreach ($tripPilotChild in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($tripPilotRuntime.Id)" -ErrorAction SilentlyContinue)) {
            $tripPilotChildProcess = Get-Process -Id $tripPilotChild.ProcessId -ErrorAction SilentlyContinue
            if ($tripPilotChildProcess -and $tripPilotChildProcess.Path -eq $tripPilotRuntime.Path `
                    -and $tripPilotChildProcess.StartTime -ge $tripPilotRuntime.StartTime) {
                $tripPilotOwnedChildren += @{Id=$tripPilotChildProcess.Id; Path=$tripPilotChildProcess.Path; Ticks=$tripPilotChildProcess.StartTime.ToUniversalTime().Ticks}
            }
        }
        try {
            Invoke-RestMethod "$env:MODEL_BASE_URL/api/generate" -Method Post -ContentType application/json `
                -Body '{"model":"gemma4:e4b","keep_alive":0}' -TimeoutSec 10 | Out-Null
        } catch { Write-Warning 'Unload failed; stopping the owned verification runtime.' }
        Stop-Process -Id $tripPilotRuntime.Id -ErrorAction SilentlyContinue
        foreach ($tripPilotChild in $tripPilotOwnedChildren) {
            $tripPilotChildProcess = Get-Process -Id $tripPilotChild.Id -ErrorAction SilentlyContinue
            if ($tripPilotChildProcess -and $tripPilotChildProcess.Path -eq $tripPilotChild.Path `
                    -and $tripPilotChildProcess.StartTime.ToUniversalTime().Ticks -eq $tripPilotChild.Ticks) {
                Stop-Process -Id $tripPilotChild.Id -ErrorAction SilentlyContinue
            }
        }
    }
    foreach ($tripPilotKey in $tripPilotSaved.Keys) {
        if ($null -eq $tripPilotSaved[$tripPilotKey]) { Remove-Item -LiteralPath "Env:$tripPilotKey" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($tripPilotKey, $tripPilotSaved[$tripPilotKey], 'Process') }
    }
    Pop-Location
}
