param([int]$Port=11436)
$ErrorActionPreference='Stop'
$tripPilotRoot=Split-Path $PSScriptRoot -Parent
$tripPilotBase="http://127.0.0.1:$Port"
$tripPilotOutput=Join-Path $tripPilotRoot 'runtime-data/local-model'
$tripPilotOwned=$null
$tripPilotSaved=@{}
foreach($tripPilotName in @('OLLAMA_HOST','OLLAMA_NUM_PARALLEL','OLLAMA_MAX_LOADED_MODELS','OLLAMA_MAX_QUEUE')){$tripPilotSaved[$tripPilotName]=[Environment]::GetEnvironmentVariable($tripPilotName,'Process')}
try {
    if(-not(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
        New-Item -ItemType Directory -Force -Path $tripPilotOutput | Out-Null
        $env:OLLAMA_HOST="127.0.0.1:$Port"
        $env:OLLAMA_NUM_PARALLEL='1'
        $env:OLLAMA_MAX_LOADED_MODELS='1'
        $env:OLLAMA_MAX_QUEUE='3'
        $tripPilotStamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
        $tripPilotOwned=Start-Process -FilePath (Get-Command ollama.exe -ErrorAction Stop).Source -ArgumentList 'serve' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $tripPilotOutput "$tripPilotStamp.out.log") -RedirectStandardError (Join-Path $tripPilotOutput "$tripPilotStamp.err.log")
        $tripPilotReady=$false
        for($tripPilotAttempt=0;$tripPilotAttempt -lt 40;$tripPilotAttempt++) {
            if($tripPilotOwned.HasExited){throw 'Local model service exited. Inspect runtime-data/local-model logs.'}
            try {Invoke-RestMethod "$tripPilotBase/api/version" -TimeoutSec 1 | Out-Null;$tripPilotReady=$true;break}catch{Start-Sleep -Milliseconds 300}
        }
        if(-not $tripPilotReady){throw 'Local model service did not become ready. Inspect runtime-data/local-model logs.'}
        @{id=$tripPilotOwned.Id;path=$tripPilotOwned.Path;startedAt=$tripPilotOwned.StartTime.ToUniversalTime().ToString('o');port=$Port} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $tripPilotOutput 'service.json')
    }
    $tripPilotVersion=Invoke-RestMethod "$tripPilotBase/api/version" -TimeoutSec 3
    if($tripPilotVersion.version -ne '0.20.4'){throw 'Existing model runtime differs from the selected 0.20.4 version; it was preserved.'}
    $tripPilotTags=Invoke-RestMethod "$tripPilotBase/api/tags" -TimeoutSec 3
    if(-not($tripPilotTags.models | Where-Object {$_.name -eq 'gemma4:e4b' -and $_.digest -eq 'c6eb396dbd5992bbe3f5cdb947e8bbc0ee413d7c17e2beaae69f5d569cf982eb'})){throw 'Pinned gemma4:e4b artifact is missing or changed; no model download/update was performed.'}
    Invoke-RestMethod "$tripPilotBase/api/chat" -Method Post -ContentType application/json -Body '{"model":"gemma4:e4b","messages":[],"stream":false,"keep_alive":"5m","options":{"num_ctx":4096}}' -TimeoutSec 90 | Out-Null
    Write-Output "Local application model is ready at $tripPilotBase. Keep this service running while using TripPilot; existing services were preserved."
} finally {
    # This is application startup, not a disposable verification runtime: leave it running.
    foreach($tripPilotName in $tripPilotSaved.Keys){if($null -eq $tripPilotSaved[$tripPilotName]){Remove-Item -LiteralPath "Env:$tripPilotName" -ErrorAction SilentlyContinue}else{[Environment]::SetEnvironmentVariable($tripPilotName,$tripPilotSaved[$tripPilotName],'Process')}}
}
