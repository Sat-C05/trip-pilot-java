param([Parameter(Mandatory)][string]$DeliveryDirectory)
$ErrorActionPreference='Stop'
$tripPilotRoot=Split-Path $PSScriptRoot -Parent
$tripPilotDelivery=[IO.Path]::GetFullPath($DeliveryDirectory)
if(-not $tripPilotDelivery.StartsWith([IO.Path]::GetFullPath((Join-Path $tripPilotRoot 'runtime-data'))+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Expected an ignored local delivery directory.'}
$tripPilotClean=Join-Path $tripPilotDelivery 'clean-setup'
if(Test-Path $tripPilotClean){throw 'Clean verification directory already exists; preserve it and prepare a new export.'}
Expand-Archive -LiteralPath (Join-Path $tripPilotDelivery 'TripPilot-source.zip') -DestinationPath $tripPilotClean
Push-Location (Join-Path $tripPilotClean 'TripPilot')
try {
    & ./mvnw.cmd -B -ntp -DskipTests package
    if($LASTEXITCODE -ne 0){throw 'Exported Java source does not build.'}
    & npm.cmd ci --prefix frontend
    if($LASTEXITCODE -ne 0){throw 'Clean frontend dependency installation failed.'}
    & npm.cmd run build --prefix frontend
    if($LASTEXITCODE -ne 0){throw 'Exported production frontend does not build.'}
    @{passed=$true;checks=@('source ZIP extraction','Maven wrapper Java packages','npm ci from lockfile','TypeScript and production frontend');runtimeAcceptance='Run verify-phase6.ps1 from configured workspace; clean build alone is not live acceptance.'} | ConvertTo-Json | Set-Content (Join-Path $tripPilotDelivery 'clean-setup-report.json')
} finally {Pop-Location}
