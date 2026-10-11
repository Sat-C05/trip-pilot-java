param([Parameter(Mandatory)][string]$ModelId)
$ErrorActionPreference='Stop'
if($ModelId -notmatch '^gemini-[A-Za-z0-9.-]+$'){throw 'Supply an exact selectable Gemini model version.'}
if([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)){throw 'Set GEMINI_API_KEY privately in this process; never paste it into chat or source.'}
$tripPilotSaved=@{}
foreach($tripPilotName in @('GEMINI_MODEL_ID','GEMINI_EVALUATION')){$tripPilotSaved[$tripPilotName]=[Environment]::GetEnvironmentVariable($tripPilotName,'Process')}
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
  $env:GEMINI_MODEL_ID=$ModelId
  $env:GEMINI_EVALUATION='true'
  & ./mvnw.cmd -B -ntp -pl backend '-Dtest=LiveGeminiEvaluationTest' test
  if($LASTEXITCODE -ne 0){throw 'Gemini evaluation failed; do not accept this model for deployment.'}
  Write-Output 'Synthetic Gemini extraction/validation passed at 4K/8K. Live guides, durable worker/SSE and hosted acceptance remain required.'
} finally {
  foreach($tripPilotName in $tripPilotSaved.Keys){
    if($null -eq $tripPilotSaved[$tripPilotName]){Remove-Item -LiteralPath "Env:$tripPilotName" -ErrorAction SilentlyContinue}
    else{[Environment]::SetEnvironmentVariable($tripPilotName,$tripPilotSaved[$tripPilotName],'Process')}
  }
  Pop-Location
}
