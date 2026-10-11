param([int]$ModelPort=11437,[int]$ProviderPort=18085,[switch]$Focused,[string]$NodeModules='C:/Users/Satvik/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules',[string]$BrowserChannel='chrome')
$ErrorActionPreference='Stop'
$tripPilotRoot=Split-Path $PSScriptRoot -Parent
$tripPilotOutput=Join-Path $tripPilotRoot ('runtime-data/phase5/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tripPilotOutput | Out-Null
$tripPilotSaved=@{}
foreach($tripPilotName in @('PHASE5_PROVIDER_TESTS','PHASE5_PROVIDER_URL','PHASE5_BROWSER_TESTS','PHASE4_BROWSER_TESTS','PHASE4_BROWSER_CHANNEL','NODE_PATH','SERVER_PORT','PROVIDER_STATE_FILE')) {$tripPilotSaved[$tripPilotName]=[Environment]::GetEnvironmentVariable($tripPilotName,'Process')}
$tripPilotProvider=$null
Push-Location $tripPilotRoot
try {
    if(Get-NetTCPConnection -LocalPort $ProviderPort -State Listen -ErrorAction SilentlyContinue){throw 'Verification provider port is occupied; choose a free port.'}
    . ./scripts/use-local-environment.ps1
    & ./mvnw.cmd -B -ntp -pl booking-demo package
    if($LASTEXITCODE -ne 0){throw 'Provider package/checks failed.'}
    $env:PHASE5_PROVIDER_TESTS='true'
    $env:PHASE5_PROVIDER_URL="http://127.0.0.1:$ProviderPort"
    $env:PHASE4_BROWSER_CHANNEL=$BrowserChannel
    $env:NODE_PATH=$NodeModules
    $env:SERVER_PORT="$ProviderPort"
    $env:PROVIDER_STATE_FILE=Join-Path $tripPilotOutput 'provider-ledger.json'
    $tripPilotProvider=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList @('-Xmx128m','-jar','booking-demo/target/booking-demo-0.1.0-SNAPSHOT.jar') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $tripPilotOutput 'provider.out.log') -RedirectStandardError (Join-Path $tripPilotOutput 'provider.err.log')
    $tripPilotReady=$false
    for($tripPilotAttempt=0;$tripPilotAttempt -lt 60;$tripPilotAttempt++) {
        if($tripPilotProvider.HasExited){throw 'Verification provider exited; inspect provider logs.'}
        try {Invoke-RestMethod "$env:PHASE5_PROVIDER_URL/actuator/health" -TimeoutSec 1 | Out-Null;$tripPilotReady=$true;break}catch{Start-Sleep -Milliseconds 300}
    }
    if(-not $tripPilotReady){throw 'Provider did not become healthy.'}
    # Remove test-server port override before launching the isolated backend and smoke services.
    if($null -eq $tripPilotSaved.SERVER_PORT){Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue}else{$env:SERVER_PORT=$tripPilotSaved.SERVER_PORT}
    if($Focused){& ./scripts/verify-phase3.ps1 -DatabaseOnly -TestSelection BookingDatabaseTest}
    else {
        if(-not(Test-Path (Join-Path $NodeModules 'playwright'))){throw 'Installed Playwright is required for Phase 5 browser acceptance.'}
        $env:PHASE5_BROWSER_TESTS='true'
        $env:PHASE4_BROWSER_TESTS='true'
        & npm.cmd run build --prefix frontend
        if($LASTEXITCODE -ne 0){throw 'Frontend build failed.'}
        & ./scripts/verify-phase3.ps1 -ModelPort $ModelPort -Smoke
    }
    Write-Output $(if($Focused){'Focused Phase 5 provider/backend verification passed; full live/browser acceptance remains required.'}else{'Full Phase 5 acceptance passed: provider/backend, live regression, production browser, packages and smoke.'})
} finally {
    if($tripPilotProvider -and -not $tripPilotProvider.HasExited){Stop-Process -Id $tripPilotProvider.Id -ErrorAction SilentlyContinue}
    foreach($tripPilotName in $tripPilotSaved.Keys){if($null -eq $tripPilotSaved[$tripPilotName]){Remove-Item -LiteralPath "Env:$tripPilotName" -ErrorAction SilentlyContinue}else{[Environment]::SetEnvironmentVariable($tripPilotName,$tripPilotSaved[$tripPilotName],'Process')}}
    Pop-Location
}
