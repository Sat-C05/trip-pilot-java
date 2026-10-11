param([int]$ModelPort = 11436)
$ErrorActionPreference = 'Stop'
# Use the already-running isolated database; never replace an occupied database service.
& (Join-Path $PSScriptRoot 'verify-model-adapter.ps1') -Port $ModelPort -Phase2
