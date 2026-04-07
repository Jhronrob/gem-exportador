-- =====================================================================================
-- GEM EXPORTADOR - Schema PostgreSQL
-- Banco de dados: gem_jhonrob (produção KSI - PostgreSQL 9.5)
-- Tabela: desenho
-- =====================================================================================
--
-- ESTRUTURA DA TABELA "desenho"
-- =====================================================================================
--
--  #  | Coluna                        | Tipo        | Obrigatório | Default              | Constraint                                          | Descrição
-- ----+-------------------------------+-------------+-------------+----------------------+-----------------------------------------------------+-------------------------------------------
--  1  | id                            | TEXT (PK)   | SIM         | (pela aplicação)     |                                                     | Identificador único UUID gerado pela aplicação (java.util.UUID.randomUUID())
--  2  | nome_arquivo                  | TEXT        | SIM         | —                    |                                                     | Nome do arquivo de desenho (ex: 170000112_03.idw)
--  3  | computador                    | TEXT        | SIM         | —                    |                                                     | Nome do computador que processou
--  4  | caminho_destino               | TEXT        | SIM         | —                    |                                                     | Caminho de rede destino dos arquivos
--  5  | status                        | TEXT        | SIM         | 'pendente'           | CHECK (pendente, processando, concluido, concluido_com_erros, erro, cancelado) | Status atual do processamento
--  6  | posicao_fila                  | INTEGER     | NÃO         | NULL                 |                                                     | Posição na fila de processamento
--  7  | horario_envio                 | TIMESTAMPTZ | SIM         | now()                |                                                     | Data/hora do envio para processamento
--  8  | horario_atualizacao           | TIMESTAMPTZ | SIM         | now()                |                                                     | Data/hora da última atualização de status
--  9  | formatos_solicitados          | TEXT        | NÃO         | NULL                 |                                                     | JSON array de formatos (ex: ["pdf","dwf","dwg"])
-- 10  | arquivo_original              | TEXT        | NÃO         | NULL                 |                                                     | Caminho completo do arquivo original
-- 11  | arquivos_processados          | TEXT        | NÃO         | NULL                 |                                                     | JSON array com arquivos gerados
-- 12  | erro                          | TEXT        | NÃO         | NULL                 |                                                     | Mensagem de erro (se houver)
-- 13  | progresso                     | INTEGER     | NÃO         | 0                    | CHECK (progresso >= 0 AND progresso <= 100)         | Percentual de progresso (0 a 100)
-- 14  | tentativas                    | INTEGER     | SIM         | 0                    |                                                     | Número de tentativas de processamento
-- 15  | arquivos_enviados_para_usuario| INTEGER     | NÃO         | 0                    | CHECK (0 ou 1)                                      | Flag se arquivos foram enviados (0=não, 1=sim)
-- 16  | cancelado_em                  | TIMESTAMPTZ | NÃO         | NULL                 |                                                     | Data/hora do cancelamento (se cancelado)
-- 17  | criado_em                     | TIMESTAMPTZ | SIM         | now()                |                                                     | Data/hora de criação do registro
-- 18  | atualizado_em                 | TIMESTAMPTZ | SIM         | now()                |                                                     | Data/hora da última modificação do registro
-- 19  | pasta_processamento           | TEXT        | NÃO         | NULL                 |                                                     | Pasta temporária de processamento
--
-- =====================================================================================
-- INSERT MÍNIMO (campos obrigatórios sem default):
--
--   INSERT INTO desenho (nome_arquivo, computador, formatos_solicitados, arquivo_original, caminho_destino)
--   VALUES ('170000112_03.idw', 'SRVMTGEM1', '["pdf","dwf","dwg"]',
--           '\\srvmtgem1\Arquivos$\desenhos\170000112_03.ipt',
--           '\\srvmtgem1\Arquivos$\DESENHOS GERENCIADOR\170');
--
-- Os demais campos (id, status, horarios, progresso, etc.) são gerados automaticamente.
-- =====================================================================================

-- Cria o banco (execute como superuser se necessário)
-- CREATE DATABASE gem_jhonrob;

-- Tabela principal de desenhos
-- Nota: id é sempre gerado pela aplicação (java.util.UUID.randomUUID()), sem DEFAULT no banco.
CREATE TABLE IF NOT EXISTS desenho (
    id                             TEXT        NOT NULL PRIMARY KEY,
    nome_arquivo                   TEXT        NOT NULL,
    computador                     TEXT        NOT NULL,
    caminho_destino                TEXT        NOT NULL,
    status                         TEXT        NOT NULL DEFAULT 'pendente'
                                               CHECK (status = ANY (ARRAY['pendente'::TEXT, 'processando'::TEXT, 'concluido'::TEXT, 'concluido_com_erros'::TEXT, 'erro'::TEXT, 'cancelado'::TEXT])),
    posicao_fila                   INTEGER,
    horario_envio                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    horario_atualizacao            TIMESTAMPTZ NOT NULL DEFAULT now(),
    formatos_solicitados           TEXT,
    arquivo_original               TEXT,
    arquivos_processados           TEXT,
    erro                           TEXT,
    progresso                      INTEGER     DEFAULT 0
                                               CHECK (progresso >= 0 AND progresso <= 100),
    tentativas                     INTEGER     NOT NULL DEFAULT 0,
    arquivos_enviados_para_usuario INTEGER     DEFAULT 0
                                               CHECK (arquivos_enviados_para_usuario = ANY (ARRAY[0, 1])),
    cancelado_em                   TIMESTAMPTZ,
    criado_em                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    pasta_processamento            TEXT
);

-- Índices para performance
CREATE INDEX IF NOT EXISTS idx_desenho_status ON desenho(status);
CREATE INDEX IF NOT EXISTS idx_desenho_computador ON desenho(computador);
CREATE INDEX IF NOT EXISTS idx_desenho_horario_envio ON desenho(horario_envio);

-- Função para notificações em tempo real (LISTEN/NOTIFY)
CREATE OR REPLACE FUNCTION notify_desenho_changes()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_notify('desenho_changes', json_build_object('op', 'INSERT', 'id', NEW.id)::text);
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        PERFORM pg_notify('desenho_changes', json_build_object('op', 'UPDATE', 'id', NEW.id)::text);
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        PERFORM pg_notify('desenho_changes', json_build_object('op', 'DELETE', 'id', OLD.id)::text);
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Trigger para notificações automáticas
DROP TRIGGER IF EXISTS desenho_changes_trigger ON desenho;
CREATE TRIGGER desenho_changes_trigger
AFTER INSERT OR UPDATE OR DELETE ON desenho
FOR EACH ROW EXECUTE PROCEDURE notify_desenho_changes();
