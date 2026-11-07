param([Parameter(Mandatory=$true)][string]$PackDir)
$ErrorActionPreference = 'Stop'
$mods = Join-Path $PackDir 'mods'
if (!(Test-Path -LiteralPath $mods -PathType Container)) { throw 'PackDir must contain a mods directory.' }
$dest = Join-Path $PSScriptRoot 'libs'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$map = @{
 '*sophisticatedbackpacks-1.20.1-3.24.35.1675.jar'='backpacks-3.24.35.1675.jar'
 '*sophisticatedcore-1.20.1-1.3.21.1676.jar'='core-1.3.21.1676.jar'
 '*refinedstorage-1.12.4.jar'='rs-1.12.4.jar'
 '*refinedstorageaddons-0.10.0.jar'='rsaddons-0.10.0.jar'
 '*lootr-forge-1.20-0.7.35.94.jar'='lootr-0.7.35.94.jar'
 '*curios-forge-5.14.1+1.20.1.jar'='curios-5.14.1.jar'
}
foreach ($pattern in $map.Keys) {
 $found = @(Get-ChildItem -LiteralPath $mods -Filter $pattern -File)
 if ($found.Count -ne 1) { throw "Expected exactly one dependency matching $pattern" }
 Copy-Item -LiteralPath $found[0].FullName -Destination (Join-Path $dest $map[$pattern])
}
Write-Output 'Dependencies copied into libs. Original pack is unchanged.'
