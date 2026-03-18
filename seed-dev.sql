-- =============================================================================
-- GEM EXPORTADOR - Seed de desenvolvimento
-- Cobre todos os cenários de status e badge para debug dos bugs:
--   Bug 1: Badges azuis em todos os formatos (deve ficar só no 1º pendente)
--   Bug 2/3: Múltiplos desenhos com status "processando" simultaneamente
-- =============================================================================
-- Execução: ./seed-dev.sh  (ou psql direto com as credenciais do .env.dev)
-- =============================================================================

-- Limpa tabela para iniciar fresh em dev
TRUNCATE TABLE desenho;

-- =============================================================================
-- GRUPO 1: FILA ATIVA (pendente + processando)
-- Simula cenário com item na frente sendo processado + fila esperando
-- =============================================================================

-- [1] Em processamento - PDF sendo processado, DWF e DWG aguardando
--     Bug fix esperado: só PDF deve ficar AZUL, DWF e DWG ficam AMARELOS
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, criado_em, atualizado_em
) VALUES (
    'aaaaaaaa-0001-0001-0001-000000000001',
    '241004940_01.idw',
    'ANDRE',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\241',
    'processando',
    1,
    now() - interval '5 minutes',
    now(),
    '["pdf","dwf","dwg"]',
    '/tmp/gem-dev/idw/241004940_01.idw',
    '[]',
    42,
    now() - interval '5 minutes',
    now()
);

-- [2] Pendente - aguardando o #1 terminar
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, criado_em, atualizado_em
) VALUES (
    'aaaaaaaa-0002-0002-0002-000000000002',
    '140000166_00.idw',
    'ANDRE',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\140',
    'pendente',
    2,
    now() - interval '4 minutes',
    now() - interval '4 minutes',
    '["pdf","dwf","dwg"]',
    '/tmp/gem-dev/idw/140000166_00.idw',
    '[]',
    0,
    now() - interval '4 minutes',
    now() - interval '4 minutes'
);

-- [3] Pendente - PDF + DWF apenas (sem DWG)
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, criado_em, atualizado_em
) VALUES (
    'aaaaaaaa-0003-0003-0003-000000000003',
    '750013114_00.idw',
    'DANIEL BIO',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\750',
    'pendente',
    3,
    now() - interval '3 minutes',
    now() - interval '3 minutes',
    '["pdf","dwf"]',
    '/tmp/gem-dev/idw/750013114_00.idw',
    '[]',
    0,
    now() - interval '3 minutes',
    now() - interval '3 minutes'
);

-- [4] Pendente - só PDF
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, criado_em, atualizado_em
) VALUES (
    'aaaaaaaa-0004-0004-0004-000000000004',
    '750013115_00.idw',
    'DANIEL BIO',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\750',
    'pendente',
    4,
    now() - interval '2 minutes',
    now() - interval '2 minutes',
    '["pdf"]',
    '/tmp/gem-dev/idw/750013115_00.idw',
    '[]',
    0,
    now() - interval '2 minutes',
    now() - interval '2 minutes'
);

-- [5] Processando com PDF já concluído, DWF em andamento, DWG aguardando
--     BUG FIX: DWF deve ser AZUL (ativo), DWG deve ser AMARELO (aguardando)
--     PDF deve ser VERDE (concluído)
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, criado_em, atualizado_em
) VALUES (
    'aaaaaaaa-0005-0005-0005-000000000005',
    '181003346_00.idw',
    'DANIEL BIO',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\181',
    'processando',
    5,
    now() - interval '8 minutes',
    now(),
    '["pdf","dwf","dwg"]',
    '/tmp/gem-dev/idw/181003346_00.idw',
    '[{"nome":"181003346_00.pdf","tipo":"pdf","caminho":"/tmp/gem-dev/output/181003346_00.pdf","tamanho":102400}]',
    66,
    now() - interval '8 minutes',
    now()
);

-- [6] Pendente - JUNIOR VENSON
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, criado_em, atualizado_em
) VALUES (
    'aaaaaaaa-0006-0006-0006-000000000006',
    '180004470_01.idw',
    'JUNIOR VENSON',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\180',
    'pendente',
    6,
    now() - interval '1 minute',
    now() - interval '1 minute',
    '["pdf","dwf","dwg"]',
    '/tmp/gem-dev/idw/180004470_01.idw',
    '[]',
    0,
    now() - interval '1 minute',
    now() - interval '1 minute'
);

-- =============================================================================
-- GRUPO 2: CONCLUÍDOS (diferentes combinações de formatos)
-- =============================================================================

-- [7] Concluído com PDF + DWF + DWG
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, criado_em, atualizado_em
) VALUES (
    'bbbbbbbb-0001-0001-0001-000000000001',
    '750013106_00.idw',
    'ANDRE',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\750',
    'concluido',
    NULL,
    now() - interval '2 hours',
    now() - interval '1 hour 50 minutes',
    '["pdf","dwf","dwg"]',
    '/tmp/gem-dev/idw/750013106_00.idw',
    '[{"nome":"750013106_00.pdf","tipo":"pdf","caminho":"/tmp/gem-dev/output/750013106_00.pdf","tamanho":204800},{"nome":"750013106_00.dwf","tipo":"dwf","caminho":"/tmp/gem-dev/output/750013106_00.dwf","tamanho":153600},{"nome":"750013106_00.dwg","tipo":"dwg","caminho":"/tmp/gem-dev/output/750013106_00.dwg","tamanho":307200}]',
    100,
    now() - interval '2 hours',
    now() - interval '1 hour 50 minutes'
);

-- [8] Concluído com PDF + DWF (sem DWG)
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, criado_em, atualizado_em
) VALUES (
    'bbbbbbbb-0002-0002-0002-000000000002',
    '180003322_04.idw',
    'JUNIOR VENSON',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\180',
    'concluido',
    NULL,
    now() - interval '3 hours',
    now() - interval '2 hours 50 minutes',
    '["pdf","dwf"]',
    '/tmp/gem-dev/idw/180003322_04.idw',
    '[{"nome":"180003322_04.pdf","tipo":"pdf","caminho":"/tmp/gem-dev/output/180003322_04.pdf","tamanho":102400},{"nome":"180003322_04.dwf","tipo":"dwf","caminho":"/tmp/gem-dev/output/180003322_04.dwf","tamanho":76800}]',
    100,
    now() - interval '3 hours',
    now() - interval '2 hours 50 minutes'
);

-- [9] Concluído com PDF + DWG (sem DWF)
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, criado_em, atualizado_em
) VALUES (
    'bbbbbbbb-0003-0003-0003-000000000003',
    '180003315_04.idw',
    'JUNIOR VENSON',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\180',
    'concluido',
    NULL,
    now() - interval '4 hours',
    now() - interval '3 hours 45 minutes',
    '["pdf","dwg"]',
    '/tmp/gem-dev/idw/180003315_04.idw',
    '[{"nome":"180003315_04.pdf","tipo":"pdf","caminho":"/tmp/gem-dev/output/180003315_04.pdf","tamanho":98304},{"nome":"180003315_04.dwg","tipo":"dwg","caminho":"/tmp/gem-dev/output/180003315_04.dwg","tamanho":294912}]',
    100,
    now() - interval '4 hours',
    now() - interval '3 hours 45 minutes'
);

-- [10] Concluído apenas PDF
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, criado_em, atualizado_em
) VALUES (
    'bbbbbbbb-0004-0004-0004-000000000004',
    '180003314_05.idw',
    'JUNIOR VENSON',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\180',
    'concluido',
    NULL,
    now() - interval '5 hours',
    now() - interval '4 hours 58 minutes',
    '["pdf"]',
    '/tmp/gem-dev/idw/180003314_05.idw',
    '[{"nome":"180003314_05.pdf","tipo":"pdf","caminho":"/tmp/gem-dev/output/180003314_05.pdf","tamanho":86016}]',
    100,
    now() - interval '5 hours',
    now() - interval '4 hours 58 minutes'
);

-- =============================================================================
-- GRUPO 3: CONCLUÍDO COM ERROS (alguns formatos falharam)
-- =============================================================================

-- [11] Concluído com erros: PDF ok, DWF falhou, DWG ok
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    erro, progresso, criado_em, atualizado_em
) VALUES (
    'cccccccc-0001-0001-0001-000000000001',
    '180003326_04.idw',
    'JUNIOR VENSON',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\180',
    'concluido_com_erros',
    NULL,
    now() - interval '6 hours',
    now() - interval '5 hours 30 minutes',
    '["pdf","dwf","dwg"]',
    '/tmp/gem-dev/idw/180003326_04.idw',
    '[{"nome":"180003326_04.pdf","tipo":"pdf","caminho":"/tmp/gem-dev/output/180003326_04.pdf","tamanho":102400},{"nome":"180003326_04.dwg","tipo":"dwg","caminho":"/tmp/gem-dev/output/180003326_04.dwg","tamanho":286720}]',
    'dwf: falhou após 3 tentativas',
    66,
    now() - interval '6 hours',
    now() - interval '5 hours 30 minutes'
);

-- =============================================================================
-- GRUPO 4: ERRO e CANCELADO
-- =============================================================================

-- [12] Erro: arquivo não encontrado
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    erro, progresso, criado_em, atualizado_em
) VALUES (
    'dddddddd-0001-0001-0001-000000000001',
    '180004482_01.idw',
    'JUNIOR VENSON',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\180',
    'erro',
    NULL,
    now() - interval '7 hours',
    now() - interval '6 hours 55 minutes',
    '["pdf","dwf","dwg"]',
    '/tmp/gem-dev/idw/180004482_01.idw',
    '[]',
    'pdf: falhou após 3 tentativas; dwf: falhou após 3 tentativas; dwg: falhou após 3 tentativas',
    0,
    now() - interval '7 hours',
    now() - interval '6 hours 55 minutes'
);

-- [13] Cancelado
INSERT INTO desenho (
    id, nome_arquivo, computador, caminho_destino, status,
    posicao_fila, horario_envio, horario_atualizacao,
    formatos_solicitados, arquivo_original, arquivos_processados,
    progresso, cancelado_em, criado_em, atualizado_em
) VALUES (
    'eeeeeeee-0001-0001-0001-000000000001',
    '181003343_00.idw',
    'DANIEL BIO',
    '\\192.168.1.152\Arquivos$\DESENHOS GERENCIADOR\181',
    'cancelado',
    NULL,
    now() - interval '8 hours',
    now() - interval '7 hours 30 minutes',
    '["pdf","dwf","dwg"]',
    '/tmp/gem-dev/idw/181003343_00.idw',
    '[]',
    0,
    now() - interval '7 hours 30 minutes',
    now() - interval '8 hours',
    now() - interval '7 hours 30 minutes'
);

-- =============================================================================
-- Resumo do seed:
--   Em fila (pendente+processando): 6 itens
--     #1 aaaaaaaa-0001: 241004940_01.idw - processando (0% -> PDF azul, DWF amarelo, DWG amarelo)
--     #2 aaaaaaaa-0002: 140000166_00.idw - pendente (amarelo todos)
--     #3 aaaaaaaa-0003: 750013114_00.idw - pendente (PDF+DWF amarelo)
--     #4 aaaaaaaa-0004: 750013115_00.idw - pendente (só PDF amarelo)
--     #5 aaaaaaaa-0005: 181003346_00.idw - processando (PDF verde, DWF azul, DWG amarelo)
--     #6 aaaaaaaa-0006: 180004470_01.idw - pendente
--   Concluídos: 4
--   Concluído com erros: 1
--   Erro: 1
--   Cancelado: 1
-- =============================================================================
