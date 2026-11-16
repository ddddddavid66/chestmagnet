param([string[]]$Name = @('backpacks','core','rs','beyond','curios','ftbquests','lootr'))
$ErrorActionPreference = 'Stop'
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'docs/neoforge-upstream-lock.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$dir = Join-Path $PSScriptRoot 'libs/neoforge'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
if ($Name -contains 'all') { $Name = @($lock.dependencies.name) }
foreach ($id in $Name) {
    $pin = $lock.dependencies | Where-Object { $_.name -eq $id -and $_.sha256 }
    if (-not $pin) { throw "No verified release for $id" }
    $dest = Join-Path $dir "$id.jar"
    if (Test-Path -LiteralPath $dest) {
        if ((Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash -ieq $pin.sha256) { continue }
        throw "Unexpected checksum for $dest; move it aside before fetching."
    }
    $tmp = "$dest.download"
    $parts = $pin.maven -split ':'
    $url = 'https://cursemaven.com/curse/maven/{0}/{1}/{0}-{1}.jar' -f $parts[1], $parts[2]
    try {
        Invoke-WebRequest -Uri $url -OutFile $tmp
        if ((Get-FileHash -LiteralPath $tmp -Algorithm SHA256).Hash -ine $pin.sha256) { throw "SHA-256 mismatch: $id" }
        Move-Item -LiteralPath $tmp -Destination $dest
        Write-Output "$id verified"
    } finally {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp }
    }
}
