param([int]$ModelPort = 11437, [string]$NodeModules = 'C:/Users/Satvik/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules', [string]$BrowserChannel = 'chrome', [switch]$BrowserOnly)
$ErrorActionPreference='Stop'
$tripPilotSaved=@{}
foreach($tripPilotName in @('NODE_PATH','PHASE4_BROWSER_TESTS','PHASE4_BROWSER_CHANNEL')) { $tripPilotSaved[$tripPilotName]=[Environment]::GetEnvironmentVariable($tripPilotName,'Process') }
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    if(-not (Test-Path (Join-Path $NodeModules 'playwright'))) { throw 'Provide -NodeModules pointing to installed Playwright packages; browser acceptance is mandatory.' }
    $env:NODE_PATH=$NodeModules
    $env:PHASE4_BROWSER_TESTS='true'
    $env:PHASE4_BROWSER_CHANNEL=$BrowserChannel
    Push-Location frontend
    try { & npm.cmd run build; if($LASTEXITCODE -ne 0){throw 'Frontend build failed.'} } finally {Pop-Location}
    if($BrowserOnly) { & ./scripts/verify-phase3.ps1 -DatabaseOnly -TestSelection PlanningExperienceDatabaseTest }
    else { & ./scripts/verify-phase3.ps1 -ModelPort $ModelPort -Smoke }
    Write-Output 'Phase 4 planning verification passed. Existing services/data preserved.'
} finally {
    foreach($tripPilotName in $tripPilotSaved.Keys) {
        if($null -eq $tripPilotSaved[$tripPilotName]) {Remove-Item -LiteralPath "Env:$tripPilotName" -ErrorAction SilentlyContinue}
        else {[Environment]::SetEnvironmentVariable($tripPilotName,$tripPilotSaved[$tripPilotName],'Process')}
    }
    Pop-Location
}
