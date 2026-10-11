param([switch]$LocalRuntime, [switch]$EvaluateModel)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
$previousTestFlag = $env:PHASE1_DB_TESTS
try {
    if ($LocalRuntime) { . (Join-Path $PSScriptRoot 'use-local-environment.ps1') }
    if (-not $env:DB_PASSWORD) { throw 'Configure MySQL variables or use -LocalRuntime with the isolated portable database.' }
    $env:PHASE1_DB_TESTS = 'true'
    & (Join-Path $projectRoot 'mvnw.cmd') -B -ntp verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven/database foundation verification failed.' }
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        npm.cmd ci --no-fund --no-audit
        if ($LASTEXITCODE -ne 0) { throw 'Frontend locked install failed.' }
        npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    & (Join-Path $PSScriptRoot 'smoke-scaffold.ps1')
    if ($EvaluateModel) {
        & (Join-Path $PSScriptRoot 'evaluate-local-model.ps1') -Suite Acceptance -Repeats 2 -RepairLimit 1
        & (Join-Path $PSScriptRoot 'evaluate-local-model.ps1') -Suite Acceptance -RepairLimit 1 -ContextStress
    }
    Write-Output 'Foundation verification passed: real database tests, frontend build and service startup.'
} finally {
    $env:PHASE1_DB_TESTS = $previousTestFlag
    Pop-Location
}
