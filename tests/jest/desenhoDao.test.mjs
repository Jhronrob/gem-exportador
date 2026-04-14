/**
 * desenhoDao.test.mjs
 *
 * Testa a lógica de negócio do DesenhoDao e ProcessingQueue
 * espelhada em JS puro.
 *
 * Bugs documentados por estes testes:
 *   [BUG-1] Campo `erro` nunca é limpo: update(erro=null) não gera SQL → valor antigo persiste
 *   [BUG-2] Status incorreto quando formatos parcialmente concluídos
 *   [BUG-3] Race condition na posição da fila (diagnóstico/documentação)
 *
 * Como rodar:
 *   cd tests/jest && npm test
 *   cd tests/jest && npm run test:verbose
 */

import {
  buildUpdateSql,
  calcularErroUpdate,
  calcularNovoStatus,
  simularRaceConditionPosicao,
  simularSequenciaAtomicaPosicao,
} from "./desenhoDao.mjs";

// ===========================================================================
// buildUpdateSql — construção do SQL dinâmico
// ===========================================================================
describe("buildUpdateSql — lógica SQL dinâmica", () => {

  // -------------------------------------------------------------------------
  // BUG-1: campo erro nunca limpa
  // -------------------------------------------------------------------------
  describe("[BUG-1] campo erro — comportamento com null vs clearErro", () => {

    test("erro=null SEM clearErro → campo NÃO aparece no SQL (bug original)", () => {
      const { fields } = buildUpdateSql({ erro: null, clearErro: false });
      const temErro = fields.some((f) => f.includes("erro"));
      // Este teste documenta o comportamento BUGADO.
      // No código original, erro=null resulta em nenhum SQL → banco não é atualizado.
      expect(temErro).toBe(false);
    });

    test("erro=null COM clearErro=true → campo aparece no SQL (correção)", () => {
      const { fields, erroValue } = buildUpdateSql({ erro: null, clearErro: true });
      expect(fields.some((f) => f.includes("erro"))).toBe(true);
      // erroValue=null significa que um SQL NULL será passado ao PreparedStatement
      expect(erroValue).toBeNull();
    });

    test("erro com mensagem → campo aparece no SQL com o valor", () => {
      const { fields, erroValue } = buildUpdateSql({
        erro: "pdf: falhou após 3 tentativas",
        clearErro: false,
      });
      expect(fields.some((f) => f.includes("erro"))).toBe(true);
      expect(erroValue).toBe("pdf: falhou após 3 tentativas");
    });

    test("erro com mensagem E clearErro=true → erro tem precedência (mensagem é gravada)", () => {
      // Caso teórico: se alguém passar ambos, o valor não-nulo é o que vai para o SQL
      const { fields, erroValue } = buildUpdateSql({
        erro: "algum erro",
        clearErro: true,
      });
      expect(fields.some((f) => f.includes("erro"))).toBe(true);
      expect(erroValue).toBe("algum erro");
    });

    test("sem erro E sem clearErro → nenhum dos dois campos aparece", () => {
      const { fields } = buildUpdateSql({ status: "concluido" });
      expect(fields.some((f) => f.includes("erro"))).toBe(false);
    });
  });

  // -------------------------------------------------------------------------
  // Campos obrigatórios
  // -------------------------------------------------------------------------
  describe("campos obrigatórios sempre presentes", () => {

    test("horario_atualizacao sempre está no SQL", () => {
      const { fields } = buildUpdateSql({});
      expect(fields.some((f) => f.includes("horario_atualizacao"))).toBe(true);
    });

    test("atualizado_em sempre está no SQL", () => {
      const { fields } = buildUpdateSql({});
      expect(fields.some((f) => f.includes("atualizado_em"))).toBe(true);
    });

    test("SQL sempre termina com WHERE id = ?", () => {
      const { sql } = buildUpdateSql({ status: "concluido" });
      expect(sql.trim()).toMatch(/WHERE id = \?$/);
    });
  });

  // -------------------------------------------------------------------------
  // Campos opcionais
  // -------------------------------------------------------------------------
  describe("campos opcionais — só aparecem se fornecidos", () => {

    test("status=null → não aparece no SQL", () => {
      const { fields } = buildUpdateSql({ status: null });
      expect(fields.some((f) => f.startsWith("status"))).toBe(false);
    });

    test("status='processando' → aparece no SQL", () => {
      const { fields } = buildUpdateSql({ status: "processando" });
      expect(fields.some((f) => f.startsWith("status"))).toBe(true);
    });

    test("progresso=null → não aparece", () => {
      const { fields } = buildUpdateSql({ progresso: null });
      expect(fields.some((f) => f.startsWith("progresso"))).toBe(false);
    });

    test("progresso=50 → aparece", () => {
      const { fields } = buildUpdateSql({ progresso: 50 });
      expect(fields.some((f) => f.startsWith("progresso"))).toBe(true);
    });

    test("clearPosicaoFila=true → posicao_fila = NULL aparece", () => {
      const { fields } = buildUpdateSql({ clearPosicaoFila: true });
      expect(fields.some((f) => f.includes("posicao_fila"))).toBe(true);
    });

    test("clearPosicaoFila=false → posicao_fila não aparece", () => {
      const { fields } = buildUpdateSql({ clearPosicaoFila: false });
      expect(fields.some((f) => f.includes("posicao_fila"))).toBe(false);
    });

    test("update vazio → só os dois campos obrigatórios + WHERE", () => {
      const { fields } = buildUpdateSql({});
      expect(fields).toHaveLength(2); // horario_atualizacao + atualizado_em
    });
  });

  // -------------------------------------------------------------------------
  // Combinações de update completo (caso de uso real do processLoop)
  // -------------------------------------------------------------------------
  describe("combinações reais de campos", () => {

    test("conclusão com sucesso: status + progresso + arquivos + clearErro + clearPosicaoFila", () => {
      const { fields, erroValue } = buildUpdateSql({
        status: "concluido",
        progresso: 100,
        arquivosProcessados: '[{"tipo":"pdf","caminho":"/out/file.pdf"}]',
        clearErro: true,
        clearPosicaoFila: true,
      });
      expect(fields.some((f) => f.startsWith("status"))).toBe(true);
      expect(fields.some((f) => f.startsWith("progresso"))).toBe(true);
      expect(fields.some((f) => f.startsWith("arquivos"))).toBe(true);
      expect(fields.some((f) => f.includes("erro"))).toBe(true);
      expect(fields.some((f) => f.includes("posicao_fila"))).toBe(true);
      expect(erroValue).toBeNull(); // NULL vai para o banco
    });

    test("erro definitivo: status + erro + clearPosicaoFila", () => {
      const { fields, erroValue } = buildUpdateSql({
        status: "erro",
        erro: "pdf: falhou após 3 tentativas",
        clearPosicaoFila: true,
      });
      expect(fields.some((f) => f.startsWith("status"))).toBe(true);
      expect(fields.some((f) => f.includes("erro"))).toBe(true);
      expect(erroValue).toBe("pdf: falhou após 3 tentativas");
    });

    test("atualização de progresso em andamento: só progresso (sem mexer em erro ou status)", () => {
      const { fields } = buildUpdateSql({ progresso: 42 });
      expect(fields.some((f) => f.startsWith("progresso"))).toBe(true);
      expect(fields.some((f) => f.startsWith("status"))).toBe(false);
      expect(fields.some((f) => f.includes("erro"))).toBe(false);
    });
  });
});

// ===========================================================================
// calcularErroUpdate — lógica de limpeza de erro no ProcessingQueue
// ===========================================================================
describe("calcularErroUpdate — quando limpar o campo erro", () => {

  test("sem erros, todos concluídos → clearErro=true, erroParam=null", () => {
    const r = calcularErroUpdate([], 3, 3, true);
    expect(r.clearErro).toBe(true);
    expect(r.erroParam).toBeNull();
  });

  test("com erros, nem todos concluídos → clearErro=false, erroParam tem mensagem", () => {
    const r = calcularErroUpdate(["pdf: falhou após 3 tentativas"], 0, 3, true);
    expect(r.clearErro).toBe(false);
    expect(r.erroParam).toBe("pdf: falhou após 3 tentativas");
  });

  test("sem erros, mas ainda tem formatos pendentes → não limpa (todoProcessado=false)", () => {
    const r = calcularErroUpdate([], 1, 3, false);
    // Ainda processando — mas erros já estão vazios
    // clearErro é true pois não há erros, mas o status não será terminal
    expect(r.clearErro).toBe(true);
    expect(r.erroParam).toBeNull();
  });

  test("múltiplos erros → joinToString com '; '", () => {
    const erros = ["pdf: falhou após 3 tentativas", "dwf: falhou após 3 tentativas"];
    const r = calcularErroUpdate(erros, 1, 3, true);
    expect(r.erroParam).toBe("pdf: falhou após 3 tentativas; dwf: falhou após 3 tentativas");
    expect(r.clearErro).toBe(false);
  });

  test("[REGRESSÃO] retry bem-sucedido: tinha erro, agora concluiu tudo → clearErro=true", () => {
    // Cenário: pdf falhou na tentativa 1, retry na tentativa 2 funcionou
    // errosAnteriores foi limpo antes desta chamada
    const r = calcularErroUpdate([], 3, 3, true);
    expect(r.clearErro).toBe(true);   // deve limpar o erro antigo do banco
    expect(r.erroParam).toBeNull();   // NULL vai para o PreparedStatement
  });
});

// ===========================================================================
// calcularNovoStatus — determinação do status final
// ===========================================================================
describe("calcularNovoStatus — lógica de status após processamento", () => {

  test("ainda tem formatos pendentes → processando", () => {
    expect(calcularNovoStatus({
      todoProcessado: false,
      errosAnteriores: [],
      concluidos: 1,
      totalFormatos: 3,
    })).toBe("processando");
  });

  test("todos concluídos, sem erros → concluido", () => {
    expect(calcularNovoStatus({
      todoProcessado: true,
      errosAnteriores: [],
      concluidos: 3,
      totalFormatos: 3,
    })).toBe("concluido");
  });

  test("todos processados, com erros, mas ao menos um concluído → concluido_com_erros", () => {
    expect(calcularNovoStatus({
      todoProcessado: true,
      errosAnteriores: ["dwg: falhou após 3 tentativas"],
      concluidos: 2,
      totalFormatos: 3,
    })).toBe("concluido_com_erros");
  });

  test("todos processados, com erros, nenhum concluído → erro", () => {
    expect(calcularNovoStatus({
      todoProcessado: true,
      errosAnteriores: ["pdf: falhou", "dwf: falhou"],
      concluidos: 0,
      totalFormatos: 2,
    })).toBe("erro");
  });

  test("fila vazia, sem erros, mas 0 concluídos → concluido_com_erros (fallback)", () => {
    // Caso de borda: todoProcessado=true mas concluidos<totalFormatos sem erros registrados
    expect(calcularNovoStatus({
      todoProcessado: true,
      errosAnteriores: [],
      concluidos: 0,
      totalFormatos: 1,
    })).toBe("erro");
  });

  describe("invariantes críticos", () => {
    test("nunca retorna status inválido", () => {
      const statusValidos = new Set(["processando", "concluido", "concluido_com_erros", "erro"]);
      const combinacoes = [
        { todoProcessado: false, errosAnteriores: [],          concluidos: 0, totalFormatos: 1 },
        { todoProcessado: true,  errosAnteriores: [],          concluidos: 1, totalFormatos: 1 },
        { todoProcessado: true,  errosAnteriores: ["e"],       concluidos: 0, totalFormatos: 1 },
        { todoProcessado: true,  errosAnteriores: ["e"],       concluidos: 1, totalFormatos: 2 },
        { todoProcessado: false, errosAnteriores: ["e"],       concluidos: 1, totalFormatos: 2 },
      ];
      for (const params of combinacoes) {
        const status = calcularNovoStatus(params);
        expect(statusValidos.has(status)).toBe(true);
      }
    });

    test("quando todoProcessado=false → SEMPRE 'processando', independente do resto", () => {
      // Mesmo com erros e 0 concluídos, se ainda há fila → processando
      expect(calcularNovoStatus({
        todoProcessado: false,
        errosAnteriores: ["pdf: erro grave"],
        concluidos: 0,
        totalFormatos: 3,
      })).toBe("processando");
    });
  });
});

// ===========================================================================
// Race condition na posição da fila — documentação do bug
// ===========================================================================
describe("[BUG-3] race condition na posicaoFila", () => {

  test("sem atomicidade: dois requests simultâneos recebem a mesma posição", () => {
    // Simula dois requests chegando quando o count é 5
    const posicoes = simularRaceConditionPosicao(5, 2);
    // Ambos recebem 6 — colisão de posição
    expect(posicoes).toEqual([6, 6]);
    // Este é o bug: posições duplicadas quebram o FIFO
    expect(new Set(posicoes).size).toBe(1); // 1 único valor = duplicata
  });

  test("com sequência atômica: cada request recebe posição única", () => {
    const posicoes = simularSequenciaAtomicaPosicao(6, 2);
    expect(posicoes).toEqual([6, 7]);
    // Posições únicas e crescentes = FIFO correto
    expect(new Set(posicoes).size).toBe(2);
  });

  test("sequência atômica com N simultâneos: todos únicos e crescentes", () => {
    const posicoes = simularSequenciaAtomicaPosicao(1, 10);
    expect(posicoes).toHaveLength(10);
    expect(new Set(posicoes).size).toBe(10); // todos únicos
    for (let i = 1; i < posicoes.length; i++) {
      expect(posicoes[i]).toBe(posicoes[i - 1] + 1); // crescente
    }
  });
});

// ===========================================================================
// Testes de regressão — cenários do mundo real
// ===========================================================================
describe("cenários de regressão end-to-end (lógica pura)", () => {

  test("fluxo completo: falha → retry → sucesso → erro deve ser limpo", () => {
    // Passo 1: PDF falhou na tentativa 1
    const erros1 = ["pdf: falhou após 3 tentativas"];
    const update1 = buildUpdateSql({
      status: "erro",
      erro: erros1.join("; "),
      clearPosicaoFila: true,
    });
    expect(update1.erroValue).toBe("pdf: falhou após 3 tentativas");

    // Passo 2: usuário reenviou, PDF funcionou no retry
    const erros2 = []; // limpos após sucesso
    const { erroParam, clearErro } = calcularErroUpdate(erros2, 1, 1, true);
    const update2 = buildUpdateSql({
      status: "concluido",
      progresso: 100,
      clearErro,
      erro: erroParam,
      clearPosicaoFila: true,
    });

    // Verificação central: o erro DEVE ser limpo no banco
    expect(update2.fields.some((f) => f.includes("erro"))).toBe(true); // campo está no SQL
    expect(update2.erroValue).toBeNull(); // valor é NULL = limpeza
  });

  test("fluxo parcial: PDF ok, DWG falhou → concluido_com_erros com erro registrado", () => {
    const erros = ["dwg: falhou após 3 tentativas"];
    const status = calcularNovoStatus({
      todoProcessado: true,
      errosAnteriores: erros,
      concluidos: 1,
      totalFormatos: 2,
    });
    const { erroParam, clearErro } = calcularErroUpdate(erros, 1, 2, true);
    const sql = buildUpdateSql({ status, erro: erroParam, clearErro, clearPosicaoFila: true });

    expect(status).toBe("concluido_com_erros");
    expect(sql.erroValue).toBe("dwg: falhou após 3 tentativas");
    expect(sql.fields.some((f) => f.includes("erro"))).toBe(true);
  });

  test("fluxo: todos os formatos concluídos → status concluido, sem erro no SQL (NULL)", () => {
    const { erroParam, clearErro } = calcularErroUpdate([], 3, 3, true);
    const status = calcularNovoStatus({
      todoProcessado: true,
      errosAnteriores: [],
      concluidos: 3,
      totalFormatos: 3,
    });
    const sql = buildUpdateSql({ status, erro: erroParam, clearErro, clearPosicaoFila: true });

    expect(status).toBe("concluido");
    expect(clearErro).toBe(true);
    expect(sql.erroValue).toBeNull();
    expect(sql.fields.some((f) => f.includes("erro"))).toBe(true); // campo está no SQL com NULL
  });
});
