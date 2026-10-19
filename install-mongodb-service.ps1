$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
New-Item -ItemType Directory -Path 'data\mongodb' -Force | Out-Null
$gameMongod = Join-Path $PSScriptRoot 'mongodb\bin\mongod.exe'
$gameConfig = Join-Path $PSScriptRoot 'mongodb\mongod.cfg'
$gameExisting = Get-Service -Name 'NetworkGameMongoDB' -ErrorAction SilentlyContinue
if (-not $gameExisting) {
    & $gameMongod --config $gameConfig --install --serviceName NetworkGameMongoDB --serviceDisplayName 'Network Game MongoDB'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot register MongoDB service' }
}
Set-Service -Name NetworkGameMongoDB -StartupType Automatic
Start-Service -Name NetworkGameMongoDB
Get-Service -Name NetworkGameMongoDB | Select-Object Name,Status
