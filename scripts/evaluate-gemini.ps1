param([Parameter(Mandatory)][string]$ModelId,
  [ValidateSet(4096,8192)][int[]]$ContextTokens=@(4096),
  [ValidateSet('normal','empty','injection','wrong-city','stale','conflict')][string[]]$Cases=@('normal'),
  [ValidateRange(1,20)][int]$RequestLimit=5)
$ErrorActionPreference='Stop'
if($ModelId -notmatch '^gemini-[A-Za-z0-9.-]+$'){throw 'Supply an exact selectable Gemini model version.'}
if([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)){throw 'Set GEMINI_API_KEY privately in this process; never paste it into chat or source.'}
$tripPilotSaved=@{}
foreach($tripPilotName in @('GEMINI_MODEL_ID','GEMINI_EVALUATION','GEMINI_EVALUATION_CONTEXTS','GEMINI_EVALUATION_CASES','GEMINI_EVALUATION_REQUEST_LIMIT')){$tripPilotSaved[$tripPilotName]=[Environment]::GetEnvironmentVariable($tripPilotName,'Process')}
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
  $env:GEMINI_MODEL_ID=$ModelId
  $env:GEMINI_EVALUATION='true'
  $env:GEMINI_EVALUATION_CONTEXTS=$ContextTokens -join ','
  $env:GEMINI_EVALUATION_CASES=$Cases -join ','
  $env:GEMINI_EVALUATION_REQUEST_LIMIT=[string]$RequestLimit
  & ./mvnw.cmd -B -ntp -pl backend '-Dtest=LiveGeminiEvaluationTest' test
  if($LASTEXITCODE -ne 0){throw 'Gemini evaluation failed; do not accept this model for deployment.'}
  Write-Output ('Selected Gemini synthetic cases passed at contexts '+($ContextTokens -join ',')+'. Other cases/contexts, live guides, durable worker/SSE and hosted acceptance remain required.')
} finally {
  foreach($tripPilotName in $tripPilotSaved.Keys){
    if($null -eq $tripPilotSaved[$tripPilotName]){Remove-Item -LiteralPath "Env:$tripPilotName" -ErrorAction SilentlyContinue}
    else{[Environment]::SetEnvironmentVariable($tripPilotName,$tripPilotSaved[$tripPilotName],'Process')}
  }
  Pop-Location
}
