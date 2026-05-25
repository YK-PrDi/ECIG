# Bump electron/package.json patch version (1.2.0 -> 1.2.1).
# Outputs the new version string on stdout. Exits 1 on failure.
# Critical: read/write with explicit UTF-8 (no BOM) to preserve CJK chars in
# fields like "description". Get-Content's default encoding on PowerShell 5
# is the system codepage (GBK on zh-CN), which corrupts UTF-8 input.
$ErrorActionPreference = 'Stop'
$file = Join-Path $PSScriptRoot 'electron\package.json'
if (-not (Test-Path $file)) {
    Write-Error "electron/package.json not found"
    exit 1
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$txt = [System.IO.File]::ReadAllText((Resolve-Path $file), $utf8NoBom)
$m = [regex]::Match($txt, '"version"\s*:\s*"(\d+)\.(\d+)\.(\d+)"')
if (-not $m.Success) {
    Write-Error "version field not found"
    exit 1
}
$new = '{0}.{1}.{2}' -f $m.Groups[1].Value, $m.Groups[2].Value, ([int]$m.Groups[3].Value + 1)
$out = $txt -replace '("version"\s*:\s*")(\d+\.\d+\.\d+)(")', ('${1}' + $new + '${3}')
[System.IO.File]::WriteAllText((Resolve-Path $file), $out, $utf8NoBom)
Write-Output $new
