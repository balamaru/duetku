$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$installedGo = Get-Command go -ErrorAction SilentlyContinue
$goCompiler = if ($installedGo) { $installedGo.Source } else { Join-Path $projectRoot '.tools/compiler/go/bin/go.exe' }
if (-not (Test-Path -LiteralPath $goCompiler)) { throw 'Go belum tersedia. Pasang Go 1.22 atau lebih baru, lalu ulangi.' }
$previousCache = $env:GOCACHE
$previousProxy = $env:GOPROXY
$previousToolchain = $env:GOTOOLCHAIN
try {
    $env:GOCACHE = Join-Path $projectRoot '.cache/go-build'
    $env:GOPROXY = 'off'
    $env:GOTOOLCHAIN = 'local'
    Push-Location (Join-Path $projectRoot 'core-go')
    try {
        & $goCompiler test '-coverprofile=coverage.out' ./...
        if ($LASTEXITCODE -ne 0) { throw 'Unit test gagal.' }
        & $goCompiler vet ./...
        if ($LASTEXITCODE -ne 0) { throw 'Go vet gagal.' }
        & $goCompiler build ./...
        if ($LASTEXITCODE -ne 0) { throw 'Build core gagal.' }
    } finally { Pop-Location }
} finally {
    $env:GOCACHE = $previousCache
    $env:GOPROXY = $previousProxy
    $env:GOTOOLCHAIN = $previousToolchain
}
