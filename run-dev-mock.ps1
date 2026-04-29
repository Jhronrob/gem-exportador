$ErrorActionPreference = "Stop"

$paths = @(
    "C:\tmp\gem-dev\idw",
    "C:\tmp\gem-dev\output",
    "C:\gem-exportador\logs-dev"
)

foreach ($path in $paths) {
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory -Path $path -Force | Out-Null
    }
}

Write-Host ""
Write-Host "[dev-mock] Modo: DEV"
Write-Host "[dev-mock] API:  http://localhost:8080"
Write-Host "[dev-mock] DB:   localhost:5432/gem_dev"
Write-Host "[dev-mock] Mock: Inventor simulado"
Write-Host ""

& .\gradlew.bat ":server:run" "-PgemEnvFile=.env.dev"
