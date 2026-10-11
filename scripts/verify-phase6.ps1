param([int]$ModelPort=11437,[int]$ProviderPort=18085,[int]$EvaluationPort=11435,[string]$NodeModules='C:/Users/Satvik/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules',[string]$BrowserChannel='chrome',[switch]$SkipModelEvaluation)
$ErrorActionPreference='Stop'
$tripPilotRoot=Split-Path $PSScriptRoot -Parent
Push-Location $tripPilotRoot
try {
    & ./scripts/verify-phase5.ps1 -ModelPort $ModelPort -ProviderPort $ProviderPort -NodeModules $NodeModules -BrowserChannel $BrowserChannel
    if(-not $SkipModelEvaluation){
        & ./scripts/evaluate-local-model.ps1 -Port $EvaluationPort -Suite Acceptance -Repeats 2 -RepairLimit 1
        & ./scripts/evaluate-local-model.ps1 -Port $EvaluationPort -Suite Acceptance -Repeats 1 -RepairLimit 1 -ContextStress
    }
    . ./scripts/use-local-environment.ps1
    $tripPilotDelivery=& ./scripts/prepare-delivery.ps1
    & ./scripts/verify-clean-setup.ps1 -DeliveryDirectory $tripPilotDelivery
    if($SkipModelEvaluation){Write-Output 'Regression and clean delivery passed; complete Phase 6 acceptance still requires model evaluation.'}
    else {Write-Output 'Full Phase 6 acceptance passed: regression, booking, live guide/model, Inspector desktop/mobile, evaluations, clean exported build and credential exclusion.'}
    Write-Output "Delivery evidence: $tripPilotDelivery"
} finally {Pop-Location}
