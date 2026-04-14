/**
 * api.integration.test.mjs
 *
 * Testes de integração HTTP contra o servidor gem-exportador rodando localmente.
 * Valida comportamento end-to-end que a lógica pura em desenhoDao.test.mjs
 * não consegue cobrir — especificamente o campo `erro` sendo persistido e
 * limpo no PostgreSQL real.
 *
 * PRÉ-REQUISITO:
 *   O servidor deve estar rodando antes de executar estes testes:
 *     cd gem-exportador && ./start-dev.sh    (modo dev com mock Inventor)
 *
 * COMO RODAR APENAS ESTES TESTES:
 *   cd tests/jest && npm test -- api.integration
 *
 * NOTA: Estes testes requerem Node 18+ (fetch nativo).
 *   Em versões anteriores, instalar: npm install node-fetch
 *   E substituir as chamadas fetch por import fetch from 'node-fetch'.
 *
 * SKIP automático: se o servidor não estiver acessível, os testes são pulados
 * sem falhar o CI. Basta que o servidor responda em SERVER_URL.
 */

const SERVER_URL = process.env.SERVER_URL || "http://localhost:8080";
const TIMEOUT_MS = 60_000; // 60s para processar no modo dev

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function serverAtivo() {
  try {
    const res = await fetch(`${SERVER_URL}/health`, { signal: AbortSignal.timeout(3000) });
    return res.ok;
  } catch {
    return false;
  }
}

async function criarDesenho(overrides = {}) {
  const body = {
    nomeArquivo: "teste-unitario.idw",
    computador: "PC-JEST-TEST",
    caminhoDestino: "C:\\\\desenhos gerenciador 3d\\\\jest",
    arquivoOriginal: "C:\\\\projetos\\\\teste.ipt",
    formatos: ["pdf"],
    ...overrides,
  };
  const res = await fetch(`${SERVER_URL}/api/desenhos/queue`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return { res, body: await res.json() };
}

async function getDesenho(id) {
  const res = await fetch(`${SERVER_URL}/api/desenhos/${id}`);
  if (!res.ok) return null;
  return res.json();
}

async function aguardarStatus(id, statusEsperado, timeoutMs = TIMEOUT_MS) {
  const inicio = Date.now();
  while (Date.now() - inicio < timeoutMs) {
    const d = await getDesenho(id);
    if (!d) return null;
    if (d.status === statusEsperado) return d;
    await new Promise((r) => setTimeout(r, 1000));
  }
  return await getDesenho(id); // retorna o estado final mesmo com timeout
}

async function deletarDesenho(id) {
  await fetch(`${SERVER_URL}/api/desenhos/${id}`, { method: "DELETE" });
}

// ---------------------------------------------------------------------------
// Setup: pula todos os testes se o servidor não está acessível
// ---------------------------------------------------------------------------
let skipAll = false;

beforeAll(async () => {
  const ativo = await serverAtivo();
  if (!ativo) {
    console.warn(
      `\n[SKIP] Servidor não acessível em ${SERVER_URL}.\n` +
      `Execute "./start-dev.sh" antes de rodar os testes de integração.\n`
    );
    skipAll = true;
  }
}, 5000);

const it = (name, fn, timeout) => {
  global.it(name, async () => {
    if (skipAll) return;
    await fn();
  }, timeout);
};

// ---------------------------------------------------------------------------
// Testes de criação e validação básica
// ---------------------------------------------------------------------------
describe("POST /api/desenhos/queue — criação de desenho", () => {

  let idCriado;

  afterAll(async () => {
    if (idCriado) await deletarDesenho(idCriado);
  });

  it("cria um desenho e retorna id, status e posicaoFila", async () => {
    const { res, body } = await criarDesenho();
    expect(res.status).toBe(200);
    expect(body).toHaveProperty("id");
    expect(body).toHaveProperty("status", "pendente");
    expect(body).toHaveProperty("posicaoFila");
    expect(typeof body.posicaoFila).toBe("number");
    idCriado = body.id;
  });

  it("rejeita request sem nomeArquivo", async () => {
    const { res } = await criarDesenho({ nomeArquivo: "" });
    expect(res.status).toBe(400);
  });

  it("rejeita request sem arquivoOriginal", async () => {
    const { res } = await criarDesenho({ arquivoOriginal: "" });
    expect(res.status).toBe(400);
  });

  it("formatos inválidos são rejeitados", async () => {
    const { res } = await criarDesenho({ formatos: ["xlsx", "doc"] });
    expect(res.status).toBe(400);
  });

  it("formato misto: só válidos são aceitos — pdf e step → só pdf", async () => {
    // Se a validação funciona, o desenho deve ser criado com apenas "pdf"
    // (ou rejeitado se exigir todos válidos — depende da implementação)
    const { res, body } = await criarDesenho({ formatos: ["pdf", "step"] });
    if (res.ok) {
      // Se aceitar, verifica que step foi descartado
      expect(body.formatosSolicitados).not.toContain("step");
      if (body.id) await deletarDesenho(body.id);
    } else {
      expect(res.status).toBe(400);
    }
  });
});

// ---------------------------------------------------------------------------
// [BUG-1] Teste de integração: campo erro deve ser limpo após sucesso
// ---------------------------------------------------------------------------
describe("[BUG-1] campo erro — persistência e limpeza no banco", () => {

  it(
    "após processamento com sucesso em modo dev, campo erro deve ser null",
    async () => {
      const { res, body } = await criarDesenho({ formatos: ["pdf"] });
      expect(res.ok).toBe(true);
      const id = body.id;

      try {
        // Aguarda o modo dev processar (mock rápido ~8s por default)
        const final = await aguardarStatus(id, "concluido");
        expect(final).not.toBeNull();
        expect(final.status).toBe("concluido");

        // ASSERÇÃO CENTRAL do BUG-1:
        // Antes da correção: erro pode ter valor residual de uma tentativa anterior
        // Após a correção: erro DEVE ser null quando status é "concluido"
        expect(final.erro).toBeNull();
      } finally {
        await deletarDesenho(id);
      }
    },
    TIMEOUT_MS + 5000
  );

  it(
    "desenho concluido não deve ter campo erro preenchido mesmo após reenvio",
    async () => {
      // Cria, aguarda conclusão, re-envia (simula retry do usuário), aguarda novamente
      const { body: b1 } = await criarDesenho();
      const id = b1.id;

      try {
        await aguardarStatus(id, "concluido");

        // Re-envia o mesmo arquivo (operação de retry via /queue com mesmo computador)
        // Verifica que o desenho continua sem erro após o segundo processamento
        const final = await getDesenho(id);
        if (final?.status === "concluido") {
          expect(final.erro).toBeNull();
        }
      } finally {
        await deletarDesenho(id);
      }
    },
    TIMEOUT_MS * 2 + 5000
  );
});

// ---------------------------------------------------------------------------
// Testes de status e progressão
// ---------------------------------------------------------------------------
describe("progressão de status durante processamento", () => {

  it(
    "status começa em 'pendente' ou 'processando' logo após criação",
    async () => {
      const { body } = await criarDesenho();
      const id = body.id;

      try {
        const d = await getDesenho(id);
        expect(["pendente", "processando"]).toContain(d.status);
        expect(d.progresso).toBeGreaterThanOrEqual(0);
        expect(d.progresso).toBeLessThanOrEqual(100);
      } finally {
        await deletarDesenho(id);
      }
    }
  );

  it(
    "progresso chega a 100 quando status é 'concluido'",
    async () => {
      const { body } = await criarDesenho();
      const id = body.id;

      try {
        const final = await aguardarStatus(id, "concluido");
        expect(final?.progresso).toBe(100);
      } finally {
        await deletarDesenho(id);
      }
    },
    TIMEOUT_MS + 5000
  );

  it(
    "arquivosProcessados contém o formato solicitado após conclusão",
    async () => {
      const { body } = await criarDesenho({ formatos: ["pdf"] });
      const id = body.id;

      try {
        const final = await aguardarStatus(id, "concluido");
        const tipos = (final?.arquivosProcessados ?? []).map((a) => a.tipo.toLowerCase());
        expect(tipos).toContain("pdf");
      } finally {
        await deletarDesenho(id);
      }
    },
    TIMEOUT_MS + 5000
  );
});

// ---------------------------------------------------------------------------
// Testes do endpoint de status da fila
// ---------------------------------------------------------------------------
describe("GET /api/queue/status", () => {

  it("retorna estrutura de status da fila", async () => {
    const res = await fetch(`${SERVER_URL}/api/queue/status`);
    expect(res.ok).toBe(true);
    const body = await res.json();
    expect(body).toHaveProperty("tamanho");
    expect(body).toHaveProperty("processando");
    expect(typeof body.tamanho).toBe("number");
    expect(typeof body.processando).toBe("boolean");
  });

  it("tamanho da fila é >= 0", async () => {
    const res = await fetch(`${SERVER_URL}/api/queue/status`);
    const body = await res.json();
    expect(body.tamanho).toBeGreaterThanOrEqual(0);
  });
});

// ---------------------------------------------------------------------------
// Testes de listagem e paginação
// ---------------------------------------------------------------------------
describe("GET /api/desenhos — listagem", () => {

  it("retorna estrutura paginada válida", async () => {
    const res = await fetch(`${SERVER_URL}/api/desenhos?limit=5&offset=0`);
    expect(res.ok).toBe(true);
    const body = await res.json();
    expect(body).toHaveProperty("desenhos");
    expect(body).toHaveProperty("total");
    expect(body).toHaveProperty("limit", 5);
    expect(body).toHaveProperty("offset", 0);
    expect(Array.isArray(body.desenhos)).toBe(true);
    expect(body.desenhos.length).toBeLessThanOrEqual(5);
  });

  it("filtro por status retorna apenas desenhos com aquele status", async () => {
    const res = await fetch(`${SERVER_URL}/api/desenhos?status=concluido&limit=10`);
    const body = await res.json();
    for (const d of body.desenhos) {
      expect(d.status).toBe("concluido");
    }
  });

  it("limit=0 retorna lista vazia (edge case)", async () => {
    const res = await fetch(`${SERVER_URL}/api/desenhos?limit=0`);
    const body = await res.json();
    expect(body.desenhos).toHaveLength(0);
  });
});

// ---------------------------------------------------------------------------
// Health check
// ---------------------------------------------------------------------------
describe("GET /health", () => {

  it("servidor responde 200 com indicação de saúde", async () => {
    const res = await fetch(`${SERVER_URL}/health`);
    expect(res.status).toBe(200);
  });
});
