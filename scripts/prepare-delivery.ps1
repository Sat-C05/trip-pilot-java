param([string]$OutputDirectory)
$ErrorActionPreference='Stop'
$tripPilotRoot=Split-Path $PSScriptRoot -Parent
if(-not $OutputDirectory){$OutputDirectory=Join-Path $tripPilotRoot ('runtime-data/delivery/'+[Guid]::NewGuid().ToString('N'))}
$tripPilotOutput=[IO.Path]::GetFullPath($OutputDirectory)
if(-not $tripPilotOutput.StartsWith([IO.Path]::GetFullPath((Join-Path $tripPilotRoot 'runtime-data'))+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Delivery output must be inside ignored runtime-data.'}
if(Test-Path $tripPilotOutput){throw 'Use a new output directory; existing output is preserved.'}
$tripPilotStage=Join-Path $tripPilotOutput 'TripPilot'
New-Item -ItemType Directory -Path $tripPilotStage -Force | Out-Null
# Export source/public documentation only. Never include local databases, ledgers, traces or binaries.
$tripPilotFiles=@('README.md','project-document.md','pom.xml','mvnw','mvnw.cmd','.gitignore','backend/pom.xml','backend/README.md','backend/.env.example','booking-demo/pom.xml','booking-demo/README.md','frontend/package.json','frontend/package-lock.json','frontend/index.html','frontend/tsconfig.json','frontend/vite.config.ts','frontend/README.md','infra/README.md','infra/.env.example','infra/compose.yml')
$tripPilotFiles+=Get-ChildItem -LiteralPath (Join-Path $tripPilotRoot '.mvn'),(Join-Path $tripPilotRoot 'backend/src'),(Join-Path $tripPilotRoot 'booking-demo/src'),(Join-Path $tripPilotRoot 'frontend/src'),(Join-Path $tripPilotRoot 'scripts') -Recurse -File | ForEach-Object {[IO.Path]::GetRelativePath($tripPilotRoot,$_.FullName)}
$tripPilotFiles+=Get-ChildItem -LiteralPath (Join-Path $tripPilotRoot 'docs') -File -Filter '*.md' | Where-Object Name -ne 'agent-work-map.md' | ForEach-Object {[IO.Path]::GetRelativePath($tripPilotRoot,$_.FullName)}
$tripPilotFiles+=@('.dockerignore','render.yaml')
if(Test-Path (Join-Path $tripPilotRoot 'frontend/vercel.json')){$tripPilotFiles+='frontend/vercel.json'}
$tripPilotFiles+=Get-ChildItem -LiteralPath (Join-Path $tripPilotRoot 'infra/deployment') -File | ForEach-Object {[IO.Path]::GetRelativePath($tripPilotRoot,$_.FullName)}
if(Test-Path (Join-Path $tripPilotRoot 'frontend/vercel.json')){$tripPilotFiles+='frontend/vercel.json'}
$tripPilotSecrets=@()
foreach($tripPilotLocal in @('runtime-data/local-mysql/connection.json','runtime-data/local-mysql/root-recovery.json')){
    $tripPilotLocalPath=Join-Path $tripPilotRoot $tripPilotLocal
    if(Test-Path $tripPilotLocalPath){$tripPilotLocalConfig=Get-Content $tripPilotLocalPath -Raw | ConvertFrom-Json; foreach($tripPilotKey in @('password','DB_PASSWORD')){if($tripPilotLocalConfig.$tripPilotKey){$tripPilotSecrets+=[string]$tripPilotLocalConfig.$tripPilotKey}}}
}
foreach($tripPilotRelative in $tripPilotFiles | Sort-Object -Unique){
    if($tripPilotRelative -match '(?i)(^|[\\/])(AGENTS\.md|agent\.md|decisions\.md|process\.md|phases\.md|logs\.md|project-spec\.md|runtime-data|target|node_modules|dist|\.git|\.codex|\.agents)([\\/]|$)'){throw 'Internal/generated path in export.'}
    $tripPilotSource=Join-Path $tripPilotRoot $tripPilotRelative
    $tripPilotText=[IO.File]::ReadAllText($tripPilotSource)
    foreach($tripPilotSecret in $tripPilotSecrets){if($tripPilotText.Contains($tripPilotSecret)){throw "Local credential detected in public source: $tripPilotRelative"}}
    if($tripPilotText -match '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|\bsk-(proj-)?[A-Za-z0-9_-]{30,}|\bAIza[A-Za-z0-9_-]{35}'){throw "Potential private credential in source: $tripPilotRelative"}
    $tripPilotDestination=Join-Path $tripPilotStage $tripPilotRelative
    New-Item -ItemType Directory -Force -Path (Split-Path $tripPilotDestination -Parent) | Out-Null
    Copy-Item -LiteralPath $tripPilotSource -Destination $tripPilotDestination
}
Compress-Archive -Path $tripPilotStage -DestinationPath (Join-Path $tripPilotOutput 'TripPilot-source.zip')
# ZIP implementations can omit hidden files. Verify wrapper/config examples survived packaging.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$tripPilotArchive=[IO.Compression.ZipFile]::OpenRead((Join-Path $tripPilotOutput 'TripPilot-source.zip'))
try { $tripPilotEntries=@($tripPilotArchive.Entries | ForEach-Object {$_.FullName.Replace('\','/')}); foreach($tripPilotRequired in @('TripPilot/.mvn/wrapper/maven-wrapper.properties','TripPilot/backend/.env.example','TripPilot/.gitignore')){if($tripPilotRequired -notin $tripPilotEntries){throw "Required package entry absent: $tripPilotRequired"}} } finally {$tripPilotArchive.Dispose()}
$tripPilotManifest=Get-ChildItem $tripPilotStage -Recurse -File -Force | ForEach-Object {[pscustomobject]@{path=[IO.Path]::GetRelativePath($tripPilotStage,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}}
@{files=$tripPilotManifest;localCredentialScan='passed';privateKeyScan='passed';internalAndRuntimeFiles='excluded'} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $tripPilotOutput 'manifest.json')
Write-Output $tripPilotOutput
