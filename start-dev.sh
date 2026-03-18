#!/usr/bin/env bash
# =============================================================================
# start-dev.sh - Inicia o GEM Exportador em modo DESENVOLVIMENTO
#
# O que este script faz:
#   1. Usa .env.dev (PostgreSQL local, sem KSI)
#   2. Define GEM_MODE=dev -> mock do Inventor (sem Autodesk/Windows)
#   3. Passa -PgemEnvFile=.env.dev ao Gradle para carregar .env.dev
#   4. Cria diretórios e arquivos dummy para a simulação
#
# Uso:
#   ./start-dev.sh          -> inicia servidor dev
#   ./start-dev.sh seed     -> aplica seed via HTTP (servidor precisa estar rodando)
#   ./start-dev.sh clear    -> limpa banco via HTTP
#
# Pré-requisitos:
#   - PostgreSQL local (brew install postgresql@16 && brew services start postgresql@16)
#   - Banco criado: createdb gem_dev
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Adiciona postgresql@16 do Homebrew ao PATH
export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"

ACTION="${1:-start}"

echo ""
echo "======================================================"
echo " GEM Exportador - Modo DESENVOLVIMENTO"
echo "======================================================"
echo " Banco   : PostgreSQL local (localhost/gem_dev)"
echo " GEM_MODE: dev (mock do Inventor ATIVO)"
echo " Env     : .env.dev"
echo " Ação    : $ACTION"
echo "======================================================"

# Cria diretórios temporários para o mock
mkdir -p /tmp/gem-dev/idw /tmp/gem-dev/output

# Cria arquivos IDW dummy para o seed
for nome in 241004940_01 140000166_00 750013114_00 750013115_00 181003346_00 180004470_01 750013106_00 180003322_04 180003326_04 180004482_01 181003343_00; do
  [ -f "/tmp/gem-dev/idw/${nome}.idw" ] || echo "[DEV] dummy" > "/tmp/gem-dev/idw/${nome}.idw"
done

if [ "$ACTION" = "seed" ]; then
  echo ""
  echo "[start-dev] Aplicando seed via HTTP (certifique-se que o servidor está rodando)..."
  curl -s -X POST http://localhost:8080/api/dev/seed | python3 -m json.tool 2>/dev/null || \
    curl -s -X POST http://localhost:8080/api/dev/seed
  exit 0
fi

if [ "$ACTION" = "clear" ]; then
  echo ""
  echo "[start-dev] Limpando banco via HTTP..."
  curl -s -X DELETE http://localhost:8080/api/dev/clear
  exit 0
fi

echo ""
echo "[start-dev] Verificando banco gem_dev..."
if ! psql gem_dev -c "SELECT 1" >/dev/null 2>&1; then
  echo "[start-dev] ERRO: banco 'gem_dev' não encontrado!"
  echo "[start-dev] Execute: createdb gem_dev"
  exit 1
fi
echo "[start-dev] Banco OK"

echo ""
echo "[start-dev] Iniciando servidor..."
echo "[start-dev] API          : http://localhost:8080/api/desenhos/all"
echo "[start-dev] WebSocket    : ws://localhost:8080/ws"
echo "[start-dev] Dev seed     : POST http://localhost:8080/api/dev/seed"
echo "[start-dev] Dev enqueue  : POST http://localhost:8080/api/dev/enqueue?formatos=pdf,dwf,dwg"
echo "[start-dev] Dev status   : GET  http://localhost:8080/api/dev/status"
echo ""

exec ./gradlew :server:run -PgemEnvFile=.env.dev
