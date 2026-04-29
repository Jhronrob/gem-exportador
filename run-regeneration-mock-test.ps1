$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $repoRoot ".env.dev"

if (-not (Test-Path $envFile)) {
    throw "Arquivo .env.dev nao encontrado em $envFile"
}

$envContent = Get-Content -Raw $envFile
if ($envContent -match "DB_HOST\s*=\s*192\.168\.1\.152") {
    throw "Seguranca: .env.dev aponta para 192.168.1.152. Ajuste para um banco local antes de rodar o mock."
}
if ($envContent -notmatch "GEM_MODE\s*=\s*dev") {
    throw "Seguranca: .env.dev precisa estar com GEM_MODE=dev para usar o mock do Inventor."
}
if ($envContent -notmatch "SUPABASE_BACKUP_ENABLED\s*=\s*false") {
    throw "Seguranca: .env.dev precisa estar com SUPABASE_BACKUP_ENABLED=false."
}

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
Write-Host "[mock-test] Modo: DEV com mock do Inventor"
Write-Host "[mock-test] Env:  $envFile"
Write-Host "[mock-test] API:  http://localhost:8080"
Write-Host "[mock-test] DB:   localhost:5432/gem_dev"
Write-Host ""

$server = Start-Process `
    -FilePath (Join-Path $repoRoot "gradlew.bat") `
    -ArgumentList ":server:run", "-PgemEnvFile=.env.dev" `
    -WorkingDirectory $repoRoot `
    -PassThru `
    -WindowStyle Hidden

try {
    Write-Host "[mock-test] Aguardando servidor responder..."
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 2
        try {
            $health = Invoke-RestMethod -Uri "http://localhost:8080/api/health" -TimeoutSec 3
            if ($health.status -eq "ok") {
                Write-Host "[mock-test] Servidor ativo."
                break
            }
        } catch {
            if ((Get-Date) -ge $deadline) {
                throw "Servidor nao respondeu em http://localhost:8080/api/health dentro de 60s."
            }
        }
    } while ($true)

    Push-Location (Join-Path $repoRoot "tests\jest")
    try {
        npm test -- api.integration
    } finally {
        Pop-Location
    }
} finally {
    if ($server -and -not $server.HasExited) {
        Write-Host "[mock-test] Encerrando servidor mock..."
        Stop-Process -Id $server.Id -Force
    }
}
