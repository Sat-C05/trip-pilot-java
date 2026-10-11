param([Parameter(Mandatory)][string]$BackendOrigin)
$ErrorActionPreference='Stop'
$tripPilotOrigin=[Uri]$BackendOrigin
if($tripPilotOrigin.Scheme -ne 'https' -or $tripPilotOrigin.Host -notmatch '^[a-z0-9-]+\.onrender\.com$' -or $tripPilotOrigin.UserInfo -or $tripPilotOrigin.Query -or $tripPilotOrigin.Fragment -or $tripPilotOrigin.AbsolutePath -ne '/' -or $tripPilotOrigin.Port -ne 443){throw 'Supply only the HTTPS Render service origin, without credentials/path/query.'}
$tripPilotConfig=@{
  '$schema'='https://openapi.vercel.sh/vercel.json'; framework='vite'; buildCommand='npm run build'; outputDirectory='dist'
  rewrites=@(@{source='/api/:path*';destination=$tripPilotOrigin.GetLeftPart([UriPartial]::Authority)+'/api/:path*'})
  headers=@(@{source='/api/:path*';headers=@(
    @{key='Cache-Control';value='no-store, private'},
    @{key='CDN-Cache-Control';value='no-store'},
    @{key='Vercel-CDN-Cache-Control';value='no-store'},
    @{key='x-vercel-enable-rewrite-caching';value='0'})})
}
$tripPilotPath=Join-Path (Split-Path $PSScriptRoot -Parent) 'frontend/vercel.json'
if(Test-Path $tripPilotPath){throw 'Existing frontend/vercel.json preserved. Review and update it manually.'}
$tripPilotConfig | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $tripPilotPath -Encoding utf8
Write-Output 'Created frontend/vercel.json for the supplied Render origin.'
