# Plano de correção — APE-RV e módulos relacionados

**Data:** 2026-06-21 (rev. 06-21 — re-verificação independente na fonte + decisões fechadas)
**Autor:** Pedro Costa (+ análise assistida)
**Status:** plano **reformulado pela medição de 2026-06-21 (rev 2)** — ver §0-bis, que **supersede a §1.1**.
Ajustes da revisão anterior marcados **[rev 06-21]**; os da medição, **[rev 06-21 #2 — medição]**.
**Progresso de execução:** **A-1 DESCARTADA** antes da implementação (redundante com B-1) — issue
phtcosta/ape#14 fechada como not-planned, change `gh14-build-provenance-stamp` arquivada com
`--skip-specs` (commit `6b7b96f`). A medição reordenou o restante (§0-bis): **B-1 + A-3** viraram o núcleo
(o parser já está pronto no fonte — gh13 não é trabalho); **G-1 adiada/provável drop**; **A-2 reduzida a B3**.
**B-1 (rvsec#71, change `gh71-build-ship-integrity`): ✅ IMPLEMENTADA, VERIFICADA E ARQUIVADA** [rev 06-21 #4]
— Fases 1–6 do SDD concluídas; commit atômico `64740c8f` na branch `modules` (`closes #71`). Escopo
fechado entregue: single-stage, clone do branch default (sem `ARG`/pin), sem mudança de tag,
**só build-chain** (Dockerfile + deletar jar commitado + `.gitignore` + docs); **não** tocou
`docker-compose.*.yml`/`calibration_orchestrator.py` (histórico/experimento). **Smoke build verificou**:
`mvn package` BUILD SUCCESS, jar embarcado 245640 B ≠ legado 236967 B (source-compilado, não o binário
legado). Delta spec sincronizado (`tools`: +2 requirements; INV-TOOL-21..24). **Gate de fechamento real
do #71 = validação 169 APKs do usuário** (`maxBoost>0`), fora do escopo da change (RISK-008).
**A-2/A-3/A-4/A-5/A-6 (todas as changes vivas do `ape`): ✅ IMPLEMENTADAS, VERIFICADAS E ARQUIVADAS**
[rev 06-22] — empacotadas numa única change `gh15-aperv-experiment-fidelity` (FF SDD, schema `sdd-full`).
Fases 1–6 concluídas: `mvn package` BUILD SUCCESS + suíte JUnit completa (356 testes, 0 falhas, 15
skipped); corrida em device (`aperv:sata_mop`, cryptoapp, 120s, `results/gh15_e2e`) confirmou **0
`[APE-RV] Triggering`** (A-3), **um `[APE-STEP]` por ação 136/136** com atribuição por mecanismo (A-5) e
**cobertura limitada sem OOM** (A-4); gate opcional (c) FLAG_SECURE→breaker LLM (A-6) não exercitado
(cryptoapp não é secure-window e `sata_mop` não tem LLM — coberto por code review + INV-RTR-08). Deltas
das 5 capabilities sincronizados nas base specs (merge mínimo in-place; o cruft pré-existente de syncs
antigos — headers de delta nas base specs + duplicação em `component-triggering` — foi deixado intacto por
decisão). Change arquivada `2026-06-22-gh15-aperv-experiment-fidelity`; commit local `a8f2fa6`
(gh15-scoped, **ainda não pushado** — `master` 2 à frente de `origin/master`); issue phtcosta/ape#15
**fechada** (via `gh`); card do board "ape" em **Done**. **Restam do plano: validação final dos 169 (M-1,
pelo usuário) e a decisão de G-1 (provável drop).**
**Escopo:** correções e melhorias do `ape` (APE-RV), do producer de análise estática (`rvsec-gator`) e da cadeia de build/empacotamento (`rv-android` / Docker / `aperv-tool`).

---

## 0-bis. Resultado da medição (2026-06-21 rev 2) — SUPERSEDE §1.1

Antes de escrever G-1/A-2, foram medidos os dois números que destravam a decisão, **sobre dados já
existentes** (corrida de comparação de junho, 169 APKs, `data/results/cmp_*` + JSONs `data/apks/*.apk.json`).
Resultado reformula o plano.

### Evidência (reproduzível)

| Achado | Número | Como |
|---|---|---|
| `[APE-RV] MOP boost` com `maxBoost>0` em todos os 169 APKs / 1014 traces `sata_mop` | **0 / 147.153** | grep `maxBoost=` nos traces |
| Confound A-3: traces `sata_mop` com triggering vs `sata` | **548 vs 45** | grep assinatura de component-trigger |
| APKs do corpus com **alguma** target method (chave legada `reachesMop`) | **163 / 169** | scan dos JSONs |
| APKs cujo handler **bate direto** numa target → **dariam boost com jar correto** | **98 / 169** | `handler ∈ reachesTargetSet` |
| APKs **predominantemente ofuscados** | **5 / 169** | heurística de nome de classe, corroborada pelos 98 hits in-package |
| APKs com target mas sem hit direto (miss) | 65 / 169 | dos quais ~27% granularidade (A-2/B3), ~73% ausentes (lib/não-alcançável, **não** ofuscação) |

### Causa-raiz (corrigida)

O `boost=0` **não** é ofuscação nem falta de substrato — é **jar stale**. A corrida empacotou o binário
commitado (`c5d76943`, 20/abr, **anterior à gh13**), que lê a chave legada `reachesMop`, contra JSONs gh60
(`reachesTarget`) → índice `bySignature` vazio → boost 0 em 169/169. **O parser correto JÁ ESTÁ no fonte e
está PRONTO** (HEAD `138a161`, gh13 — lê `reachesTarget`/`directlyReachesTarget`/`targetMethods`). Não há
nada a implementar nem validar no parser. Logo o desbloqueio é **puramente B-1** (buildar o fonte atual na
imagem); nenhuma linha a escrever no consumer.

O `mop_total` 13,4-vs-9,0 que a análise de junho creditou ao MOP é o **confound A-3** (component-triggering:
`componentPercentage=0,05` quando `mopDataPath != null`), não steering — os dois braços diferiram na variável errada.

### Consequências para as changes

- **A "regressão/empate do MOP" do experimento de junho é inválida**, não evidência: o MOP nunca disparou. Q2
  ("MOP-guidance move cobertura?") **continua não testada** — só é mensurável após B-1+A-3 + a comparação
  final dos 169 (com 98 boostáveis dentro), executada pelo usuário.
- **G-1 perde a premissa.** Ofuscação é 5/169, não ~88%. 98/169 já têm hit in-package na reachability filtrada
  por pacote. O teto da G-1 é ≤65 apps, e desses só ~73% têm handler ausente — em maioria lib/não-alcançável,
  onde o lookup por assinatura só ajuda se a assinatura estiver de fato no set transitivo (precisaria do spike no
  gator p/ saber). **Adiar/provável drop.**
- **A-2 quase toda já está na gh13** (parser, eventType, package — em `138a161`). Resíduo real = **só B3**
  (granularidade pai/filho), que cobre ~27% dos 65 miss-apps. Manter como escopo analítico do plano
  (sem gate de experimento; a validação dos 169 é única, no fim).
- **A-1 descartada** (redundante com B-1).
- **gh13 — pronta, não é trabalho.** O parser já está implementado no fonte (`138a161`); aparece aqui só
  para explicar por que B-1 basta (não há o que validar nem reimplementar).

### Espinha reformulada

> **Status [rev 06-22]:** passos **1 (B-1)**, **2 (A-3)** e **3 (A-2/B3 · A-5 · A-4 · A-6)** ✅ **CONCLUÍDOS** — B-1 via rvsec#71/gh71; A-2..A-6 no bundle `gh15` (arq. `2026-06-22`, commit `a8f2fa6`, #15 fechada). Resta o passo **4** (comparação final 169, pelo usuário) e a decisão de **G-1** (provável drop).

```
1. B-1  — buildar o fonte (branch default; parser já pronto) na imagem → 98/169 dariam boost   ← O FIX
2. A-3  — desacoplar component-triggering (confound provado 548×45)
3. Demais changes conforme o escopo analítico do plano: A-2/B3 (granularidade), A-5, A-4 (podar),
   A-6; G-1 = provável drop (decisão por análise + eventual spike no gator). NÃO gated por experimento.
4. Comparação final dos 169 (sata × sata_mop) — executada pelo usuário UMA vez, no fim do plano
   inteiro, só para VALIDAR resultado e fechar as changes. Nada depende dela; não é gate. ("98" é só
   o subconjunto analítico onde o boost pode disparar, não um teste à parte.)
```

Caminho crítico curto: **B-1 → A-3 → medir**. O parser já está pronto no fonte — não há nada a
implementar nem validar nele. G-1 (provável drop) e o escopo de A-2 (reduzido a B3) são decididos
por análise, **não** por um experimento intermediário — a comparação dos 169 roda **uma vez no fim**,
só para validar e fechar as changes.

---

## 0. Como este plano foi construído (rastreabilidade)

Origem: 8 documentos de análise gerados por múltiplas LLMs em
`rvsec/rv-android/docs/analise_*.md` e `ape/docs/analise_*.md`. **Nenhuma claim foi aceita
sem verificação na fonte.** Cada item abaixo foi confirmado lendo o código real e está
anotado com `arquivo:linha`. Veredictos usados: ✅ CONFIRMADO · ⚠️ PARCIAL · 🟡 PLAUSÍVEL ·
❌ REFUTADO.

> **Re-verificação independente (2026-06-21).** Antes de detalhar a execução, as claims de maior
> carga foram re-conferidas por leitura direta do código nos **dois** repos (gator + ape + cadeia
> Docker), em paralelo. Resultado: **todas confirmadas** — números de linha batem e os veredictos
> se sustentam. A revisão acrescentou: a tese de ofuscação tem o **formato de assinatura garantido**
> (mesmo `SootMethod.getSignature()` nos dois lados) mas o **recall do SPARK** é o FN real; o índice
> por assinatura **já existe** (G-1 é fiação); A-2 toca **API pública** de `score()`; o auth do clone
> do `ape` foi resolvido. Detalhes marcados **[rev 06-21]**.

### Mapa de repositórios (confirmado via `git remote`/`rev-parse`)

| Componente | Repo Git | Branch | OpenSpec home |
|---|---|---|---|
| `ape` (APE-RV) | `git@github.com:phtcosta/ape.git` | `master` | `ape/openspec/` |
| `rv-android` (Docker, `aperv-tool`) | `git@github.com:PAMunb/rvsec.git` | `modules` | `rv-android/openspec/` |
| `rvsec-gator` (producer JSON) | `git@github.com:PAMunb/rvsec.git` | `modules` | `rv-android/openspec/` |

> **Consequência prática:** B-1 (build/Docker) e G-1 (gator) vivem no **mesmo** repositório
> (`PAMunb/rvsec` @ `modules`). Só há **2 repos** no total. As changes do `ape` (A-1…A-6)
> ficam em `phtcosta/ape` @ `master`.
>
> **Regra de OpenSpec (decisão do usuário):** **todas** as changes do `PAMunb/rvsec` são
> criadas **dentro de `rv-android/openspec/`** — inclusive as que tratam de outros módulos
> (ex.: G-1, que altera o `rvsec-gator`). Não criar `openspec/` separado para o gator.
>
> **Modelo de processo por repo (difere) [rev 06-21]:** `phtcosta/ape` usa OpenSpec com schemas
> **`sdd-full`/`sdd-quick-path`** e **sem** camada de skills `rv-*` (verificação manual: JUnit
> parcial onde existe — `MopScorer`/`MopData` — + validação em device). `PAMunb/rvsec` usa
> **`rv-sdd`/`quick-path`** com skills `rv-*` + CI. **Há dois namespaces `gh<N>` independentes**
> (ape no gh13; rvsec no gh70+) — todo cross-ref SHALL nomear o repo (`phtcosta/ape#N` vs
> `rvsec#N`). As capabilities-alvo das changes A-* **já existem** em `ape/openspec/specs/`
> (`build`, `mop-guidance`, `component-triggering`, `ui-coverage`, `action-selection`,
> `llm-routing`) — os nomes de spec delta do plano estão corretos.

### Princípios de design (OBRIGATÓRIOS para toda change deste plano)

O sistema deve ser **o mais simples e elegante possível**, sem complexidade desnecessária e
seguindo as boas práticas. Toda change abaixo SHALL respeitar:

- **P1 — Simplicidade.** Complexidade mínima para a tarefa atual. Três linhas parecidas > abstração
  prematura. Chamada direta > indireção com um único assinante. **Sem features especulativas, sem
  validação para cenários impossíveis, sem helpers para operação única.** Validar só nas fronteiras
  do sistema (input de usuário, APIs externas). Composição > herança; plano > aninhado.
- **P3 — Sem retrocompatibilidade.** Código morto/superado é **deletado por inteiro** — sem
  adapters, shims, wrappers, comentários `# removed` ou renomeações `_unused`. Backup para
  `backup/` (gitignored) antes de deletar. Toda mudança é completa: atualizar todos os callers,
  `grep` por referências penduradas, um commit = um estado consistente.
- **P4 — Comentários no presente.** Comentários descrevem o que o código faz **agora**. Sem
  histórico de migração ("migrated from X", "replaces old Y"). Sem linguagem promocional
  ("modern", "elegant", "advanced"). Nomes descrevem função, não linhagem (`process_tasks`, não
  `process_tasks_v2`). Contexto histórico vai pra tese/paper, não pro comentário.

> **Consequência direta neste plano (auto-aplicada):** removido o "oráculo de obfuscação" do M-1
> (não existe, e o design do G-1 não precisa); `llmMaxCalls` **não existe e NUNCA volta** (A-6);
> nenhuma métrica/maquinaria especulativa (ex.: medir "97% incidentais"). G-1 fork B **remove**
> acoplamento (não adiciona) — é simplificação, não feature.

---

## 1. Sumário executivo

### 1.1 Achado central (estratégico)

> ⚠️ **SUPERADA pela medição (§0-bis).** Os pontos 1–4 abaixo foram a hipótese pré-medição. O que se
> confirmou: **ponto 1** (jar legado) é a causa real e única do `boost=0` — mas via **chave de schema**
> (`reachesMop` vs `reachesTarget`), não "pacote limpo vs ofuscado". O que **caiu**: o ponto 2 (a tese de
> que ofuscação é o bloqueio remanescente e exige G-1/A-2) — ofuscação é **5/169**, e o parser correto já
> está no fonte (gh13/`138a161`). Ler §0-bis como autoritativo; o texto abaixo fica por rastreabilidade.

1. **A imagem Docker assa um jar legado e obsoleto** — causa proximal de um experimento
   inteiro invalidado (MOP nunca testado: 0/224990 linhas com boost). **A intuição do
   usuário está correta: nenhum Dockerfile compila o ape.** ✅
2. **Rebuildar o jar conserta o MOP para apps NÃO ofuscados; não para os ofuscados.**
   ✅ **Verificado na fixture real** `ape/test-apks/cryptoapp.apk.json`: com o jar correto
   (`138a161`) e o código atual, o scorer **já emite `+300`** — 2 de 7 listeners casam um
   método `reachesTarget=true` pelo join exato de assinatura
   (`buttonGenerateHash→MessageDigestActivity.generateHash`,
   `btn_cipher_encrypt→CipherActivity$1.onClick`; ambos `directly=false` ⇒ transitive ⇒
   `mopWeightTransitive`). Logo **B-1 (jar correto) é o desbloqueio do MOP para apps de
   pacote limpo**; o scorer NÃO é estruturalmente inerte.
   - O bloqueio remanescente é o **R8/ofuscação** (~88% dos 169 APKs): handlers renomeados
     fora do pacote somem do `reachability[]` (filtro `isAppClass`) e o join exato falha. É
     aí — e só aí — que **G-1** (emitir `handlerReachesTarget` + incluir handlers ofuscados)
     **e A-2** (recall/precisão) são necessários. Não são o desbloqueio universal do MOP, e
     sim a **extensão para o caso ofuscado**.
3. **A "regressão do MOP" é empate/ruído** (consistente com a memória do projeto), não uma
   regressão causal. Não perseguir um fantasma; o problema é que o MOP é **inerte**, não
   "pior".
4. **A regressão do braço LLM é por latência** (menos ações no orçamento de 300s), não por
   qualidade de decisão (match 84%, 0 timeouts).

### 1.2 Catálogo de changes

> **Tabela reformulada pela medição [rev 06-21 #2] — ver §0-bis.**

> **Nota:** o parser do consumer (`reachesTarget`) **já está implementado e pronto no fonte** (`138a161`,
> gh13) — **não é item de trabalho** e não entra na tabela. É só o motivo de B-1 bastar.

| ID | Nome | Repo | Tema | Prioridade | Track (schema) | Status pós-medição |
|---|---|---|---|---|---|---|
| **B-1** | `build-ship-integrity` | PAMunb/rvsec (rvsec#71, `gh71`) | build/Docker | P0 | Full SDD + ADR (`rv-sdd`) | **✅ ENTREGUE** (commit `64740c8f`, `closes #71`, branch `modules`; arquivada `2026-06-21-gh71`) — single-stage, build do fonte (branch default); só build-chain; smoke build OK (jar 245640 B source-compilado). Falta só validação 169-APK do usuário (`maxBoost>0`, RISK-008) |
| **A-3** | `decouple-component-triggering` | phtcosta/ape | validade experimental | P0 | FF SDD (`sdd-full`) | **✅ ENTREGUE** (bundle `gh15`, arq. `2026-06-22`, commit `a8f2fa6`) — default `0.0`, INV-CT-01; device: 0 `Triggering` em `sata_mop` |
| **A-1** | `build-provenance-stamp` | phtcosta/ape | observabilidade/build | — | — | **DESCARTADA** (redundante c/ B-1; #14 not-planned, arquivada) |
| **G-1** | `emit-handler-reachability` | PAMunb/rvsec (gator) | MOP producer | P3 | FF SDD (`rv-sdd`) | **ADIADA / provável drop** — ofuscação 5/169, não 88% |
| **A-2** | `mop-scorer-correctness` | phtcosta/ape | MOP consumer | P2 | FF SDD (`sdd-full`) | **✅ ENTREGUE** (bundle `gh15`) — B3 containment caller-side (depth ≤2) + B4 fallback (INV-MOP-07) + B6 eventType norm (INV-MOP-08); B9 descopado (dormente, intocado) |
| **A-5** | `step-decision-logging` | phtcosta/ape | observabilidade | P2 | FF SDD (`sdd-full`) | **✅ ENTREGUE** (bundle `gh15`) — `ModelAction.decisionSource` + boosts por mecanismo + `[APE-STEP]` por ação (INV-SEL-04); device 136/136 |
| **A-4** | `faithful-ui-coverage` | phtcosta/ape | métrica de cobertura | P2 | FF SDD (`sdd-full`) | **✅ ENTREGUE** (bundle `gh15`) — key `xpath\|TYPE` (INV-COV-06), `stateData` limitado (LRU) + rollup por Activity (INV-COV-05); device sem OOM |
| **A-6** | `llm-throughput-and-secure` | phtcosta/ape | LLM | P2 | FF SDD (`sdd-full`) | **✅ ENTREGUE** (bundle `gh15`) — breaker no screenshot null + clamp `[0,1]` (INV-RTR-08); linha `llmMaxCalls` removida do doc. Gate device (c) opcional não exercitado |
| **M-1** | re-run metodológico | PAMunb/rvsec (experimentos) | metodologia | P3 | nota metodológica (não-OpenSpec) | depende de B-1, A-3 |

> **Justificativa dos tracks [rev 06-21].** B-1 é **Full SDD + ADR**: tem decisão de design
> (build-from-source / single-stage / auth), nova capability `aperv-image-build` e toca
> Dockerfile + deleção do binário commitado + `.gitignore` (só build-chain; **não** compose/orchestrator).
> As demais são **FF SDD** (módulo
> único, requisitos claros): G-1 é fiação sobre índice existente; A-2 encolheu porque a
> infraestrutura de parser/eventType/precedência já está no código (resta B3 — mudança de API —,
> B4, B9, B6). M-1 não é OpenSpec (nota metodológica + config de experimento).

### 1.3 Sequência recomendada e dependências

**Reformulada pela medição — ver a espinha em §0-bis.** Resumo:

```
P0:  B-1   buildar o fonte (branch default; parser JÁ pronto) na imagem → 98/169 dariam boost  ← O FIX
     A-3   desacopla component-triggering (confound 548×45)
P2+: A-2/B3 (granularidade) · A-5 · A-4(podar) · A-6   (escopo analítico; G-1 provável drop)
FIM: comparação final 169 (sata × sata_mop) — UMA vez, pelo usuário, só VALIDA p/ fechar changes;
     nada depende dela, não é gate ("98" = subconjunto analítico boostável)
```

Caminho crítico curto: **B-1 → A-3 → demais changes → validação final única (169)**. A §1.1 antiga ("B-1+A-1 fazem o MOP disparar em apps
limpos; G-1+A-2 estendem aos ofuscados") está **superada por §0-bis**: o corpus tem 5/169 ofuscados,
98/169 dão boost só com o jar correto, A-1 foi descartada e o parser já está pronto no fonte.

---

## 2. Changes no repo `PAMunb/rvsec` (@ `modules`)

### B-1 — `build-ship-integrity`  (componente: rv-android / Docker / aperv-tool)

**Problema (✅ confirmado).** A cadeia de proveniência do `ape-rv.jar` tem dois handoffs
manuais sem gate, e nenhum Dockerfile compila o ape:

- `ape/pom.xml:111-125` (`maven-jar-plugin`, fase `prepare-package`) + `pom.xml:133-154`
  (`exec-maven-plugin` rodando `d8`, fase `package`) produzem `target/ape-rv.jar`.
- `ape/pom.xml:163-185` (`maven-resources-plugin`, **fase `install`**) copia o jar para
  `${rvsec_home}/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv` (`pom.xml:173`).
  **Só roda em `mvn install`** — `mvn package` não dispara; `-Drvsec_home` só muda o destino.
- O jar em `rv-android/modules/aperv-tool/.../aperv/ape-rv.jar` **é binário commitado**
  (git-tracked; último bump `c5d76943`, 20/abr). A working tree tem um jar **diferente e
  mais novo** (29/mai, 236967 B vs 237019 B) **não commitado** → drift real.
- `docker/rvandroid/Dockerfile:13` faz `git clone --branch modules ... rvsec.git` e compila
  só os módulos rvsec — **nunca o ape**. O jar entra na imagem **apenas** por já estar
  commitado no clone. Não há `COPY`/`RUN`/`d8` do ape.
- **Há gate de frescor para o dexlib2** (`Dockerfile:25-26`: `test -f ... || exit 1`), mas
  **nenhum equivalente para o `ape-rv.jar`**.
- Os `docker-compose.*.yml` de experimentos fazem **bind-mount `:ro`** de um jar do host por
  cima do jar assado — **~10 arquivos compose + `calibration_orchestrator.py:245`** (não só 2;
  ex.: `docker-compose.exp5-prompt-600s.yml:82+`, `docker-compose.final-1a.yml:44+`) — prova de
  campo de que a obsolescência já é contornada manualmente.

**Causa raiz.** Build do ape desacoplado do build da imagem + binário commitado + ausência
de gate de frescor ⇒ jar obsoleto persiste silenciosamente.

> **Issue/change [rev 06-21 #4 — ENTREGUE]:** rvsec#71, change `gh71-build-ship-integrity` (Full SDD + ADR).
> ✅ **Fases 1–6 concluídas e arquivada** (`openspec/changes/archive/2026-06-21-gh71-build-ship-integrity/`).
> Commit atômico `64740c8f` (`closes #71`, branch `modules`); ADR 0005 registrado; delta spec `tools`
> sincronizado (+2 requirements). Escopo abaixo **entregue como fechado pelo usuário**. Card no kanban:
> mover p/ Done **após** a validação 169-APK do usuário.
>
> **Resultado da execução [rev 06-21 #4].** As três edições do build-chain entraram **num único commit**
> (`git show --stat`: `Dockerfile` M · `.gitignore` M · `ape-rv.jar` D — RISK-002 satisfeito). O novo `RUN`
> standalone foi colocado **após** o `RUN` composto do rvsec (que termina em `uv sync`, linha 17), **antes**
> do gate dexlib2 — não dividiu o `RUN` composto (P1). Adicionado `test -s` ao jar copiado (espelha o gate
> dexlib2; reforça INV-TOOL-21). **Smoke build** (`rvandroid:gh71-smoke`): clone do `ape` + `mvn package`
> **BUILD SUCCESS**; jar embarcado **245640 B** (não-vazio), **distinto** do legado commitado **236967 B** →
> é o artefato source-compilado, não o binário legado. Testes `aperv-tool` 41/41 verdes. Docs do módulo
> corrigidas (claim "gitignored build artifact" → fluxo clone→`mvn package`→copy). **Não fecha o #71 por si
> só** (RISK-008): o gate real é `[APE-RV] MOP boost maxBoost>0` na corrida de 169 APKs do usuário, numa
> imagem **reconstruída** (RISK-005), no mesmo dataset que deu 0/147.153.

**Solução (DECISÃO FECHADA — build-from-source, single-stage) [rev 06-21 #3].** Alterar o Dockerfile
para **clonar o `ape` e gerar o jar dentro da imagem**, copiando para o local correto. Sem gate sobre
binário e **sem** depender do jar commitado. Escopo de B-1 = **somente a cadeia de build** (três
edições); **não** toca compose nem orchestrator.

- Adicionar ao `docker/rvandroid/Dockerfile` (após o clone do rvsec na linha 13, antes do `uv sync`)
  um passo **single-stage** (o base image já tem o toolchain — ver Pré-requisitos) que:
  1. clona `https://github.com/phtcosta/ape.git` — **branch default (sempre o mais atual no build);
     sem `ARG`, sem pin de SHA** (decisão do usuário: simplicidade, isso nunca será sobrescrito);
  2. roda `mvn package` (produz `target/ape-rv.jar` via `d8`);
  3. copia o `ape-rv.jar` recém-gerado para o local que o `aperv-tool` espera:
     `modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar`.
- **Deletar** o binário commitado e adicionar o `.gitignore`. O `maven-resources-plugin` (fase
  `install`, `ape/pom.xml:163-185`) continua útil para uso local fora do Docker.
- **Sem mudança de tag da imagem** (decisão do usuário): a imagem é re-buildada no mesmo tag.

**Fora do escopo de B-1 (decisão do usuário) [rev 06-21 #3].** B-1 **NÃO** mexe em
`docker-compose.*.yml` nem em `scripts/calibration_orchestrator.py` — são **config/execução de
experimento** (domínio do usuário) e **arquivos históricos** (incl. os A/B `final-*`/`revalidate-*`
que montam jars distintos `ape-rv-baseline/island0/island1.jar`). Uma vez que a imagem builda o jar
correto, o workaround de bind-mount fica desnecessário; o que montar na comparação final é decisão do
usuário, não desta change.

**Acesso ao `ape` no build (DECISÃO FECHADA — `phtcosta/ape` público; clone HTTPS) [rev 06-21].**
HTTPS anônimo (`https://github.com/phtcosta/ape.git`), igual ao clone do rvsec — sem secret/token/SSH.
Mantém válida a refutação do "risco de chave SSH" (§5). Pré-condição: repo público antes do build.

**Drift de três vias (confirmado) [rev 06-21].** Commitado (237019 B), working-tree (236967 B) e
host A/B (~237 KB cada). Build-from-source elege o build da imagem como única fonte e deleta o
commitado. **Confirmado no Explore:** o `.gitignore` em `aperv-tool/.../aperv/` está **vazio
(0 B)** e o jar está **git-tracked** (force-added) — a doc do módulo que diz "gitignored" está errada;
B-1 a torna verdadeira (deleta o jar + preenche o `.gitignore`).

**Pré-requisitos de imagem — CONFIRMADOS no Explore [rev 06-21 #3].** O base
`phtcosta/rvandroid_tools:0.9.1` **já contém** o toolchain completo: `d8`
(`/opt/android/build-tools/35.0.1/d8`), `mvn` 3.9.16, `git`, **JDK 25**, `ANDROID_HOME=/opt/android`.
Logo **single-stage é suficiente** — multi-stage não é necessário (premissa antiga superada). O
pom do ape usa `maven.compiler.release=11`; `javac 25 --release 11` é padrão e o próprio base já
builda todo o rvsec sob JDK 25 (`Dockerfile:14`) → **JDK 25 não é risco**.

**Atomicidade (confirmado no Explore — impact analyzer).** Hoje o jar entra na imagem **via o clone
do rvsec** (não pelo host). Logo **deletar o commitado + adicionar o build no Dockerfile devem ser
UM commit atômico** (P3): isolado, qualquer um quebra `_resolve_jar_path()` em todo `aperv:*`.

**Spec deltas (semente para `specs/`).** Capability sugerida: `aperv-image-build`.
- O build da imagem SHALL compilar o `ape` a partir do fonte (clone + `mvn package`) e produzir
  o `ape-rv.jar` **dentro** da imagem.
- O `ape-rv.jar` empacotado SHALL ser o artefato recém-compilado, copiado para
  `modules/aperv-tool/.../tools/aperv/ape-rv.jar`; o build NÃO SHALL depender de jar
  pré-commitado nem de bind-mount em runtime.

**Tasks (semente `tasks.md`) — 3 edições de build-chain [rev 06-21 #3].**
1. `docker/rvandroid/Dockerfile`: passo single-stage `git clone` (branch default, HTTPS) +
   `mvn package` + `cp target/ape-rv.jar` para o module dir do `aperv-tool`. Após o clone do rvsec
   (linha 13), antes do `uv sync`.
2. **Deletar** o binário commitado (`modules/aperv-tool/.../ape-rv.jar`) — backup para `backup/`
   antes — e **adicionar** a linha `ape-rv.jar` ao `.gitignore` (hoje vazio) do mesmo diretório.
   Mesmo commit atômico que (1).
3. Documentar o fluxo (clone→build→copy) em `rv-android/docs` e no `CLAUDE.md` do ape/aperv-tool
   (corrigir a afirmação "gitignored").

**Critérios de aceitação [rev 06-21 #3].** A imagem buildada do zero contém um `ape-rv.jar`
compilado do fonte (branch default do `ape` no momento do build), não o binário legado; verificável
no build (o `cp` do `target/ape-rv.jar` existe e é não-vazio). O binário commitado foi deletado e o
`.gitignore` o ignora. **Sem** mudança de tag de imagem. **Sem** edição de compose/orchestrator.

**Riscos / rollback.** Aumenta tempo de build (clone + `mvn package` do ape). Rede/clone do `ape`
no build (cache de camadas). Rollback (emergencial, via git revert): reintroduzir o binário — não é
caminho suportado, já que será deletado.

**Dependência.** Nenhuma. (A-1 descartada.)

**Elevada a O FIX pela medição [rev 06-21 #2].** Não é só integridade: com o jar buildado do fonte
(parser gh13 já pronto), **98/169 APKs passam a dar boost** (§0-bis) — desbloqueio do MOP. **Nota
[rev 06-21 #3]:** "98" é um **número analítico** da medição (quantos APKs dariam boost com o jar
correto), **não** um teste a rodar; a comparação final é nos **169** e é executada pelo usuário (M-1),
fora desta change.

---

### G-1 — `emit-handler-reachability`  (componente: rvsec-gator)  — ⏸ ADIADA / provável drop (2026-06-21)

> **ADIADA pela medição (§0-bis); provável drop.** A premissa "handlers ofuscados são o bloqueio
> remanescente (~88%)" **caiu**: o corpus tem **5/169 ofuscados**, e **98/169** já dão boost só com o jar
> correto (B-1), via reachability filtrada por pacote. O teto da G-1 é ≤65 miss-apps, e desses ~73% têm
> handler ausente da reachability — em maioria **lib/não-alcançável**, não ofuscação. Só faz sentido **se**
> (a) a análise indicar que MOP-guidance move cobertura, **e** (b) um spike no gator medir que o set transitivo
> recupera uma fração relevante desses ausentes. Até lá, **não construir.** Texto abaixo por rastreabilidade.

**Problema (✅ confirmado; decisivo).** O producer **não emite** reachability por-handler, e
filtra handlers ofuscados:

- `rvsec-gator/.../reach/ReachabilityEnricher.java:71` — `enrichWidget()` é **stub**:
  `return EMPTY;`. Idem `enrichTransition`/`enrichComponent`. **Zero call-sites** no
  `client/`. Só `enrichMethod` (`:56-64`) tem conteúdo, e emite reachability **por-método**,
  não por-handler. Javadoc da classe (`:18-22`) confirma: overloads são placeholders até "C3".
- `RvsecAnalysisClient.java:277-286` — `isAppClass(className, filterPackage)` rejeita
  qualquer classe que não comece com `filterPackage`. O array `reachability[]` (único lugar
  com `reachesTarget`/`directlyReachesTarget`) é montado **só** dessas `appClasses`
  (`:255-269`, `:110`). Handlers R8-renomeados fora do pacote (ex.: `F5.a`, `n5.e`) **somem**
  do `reachability[]`. As *assinaturas* de handler aparecem em `windows[].widgets[].listeners[]`
  (`:935-947`, `:1410-1417`), mas **sem** o flag de reachability.
- Vocabulário `eventType`: producer emite **snake_case minúsculo** (`EventType.java`:
  `long_click:15`, `item_long_click:42`, `item_selected:43`; serializado com `.toLowerCase()`
  em `:939`/`:1712`). Consumer espera camelCase (ver A-2/B6). Mismatch latente.
- Wire keys já corretas: `JsonSchema.REACHES_TARGET="reachesTarget":48`,
  `DIRECTLY_REACHES_TARGET:49`, `TARGET_METHODS:88` (sem legado `reachesMop`).

**Causa raiz.** O canal por-handler nunca foi implementado (stub) + escopo de pacote exclui
handlers ofuscados ⇒ o consumer não tem como casar handler↔reachability.

**Solução proposta.**
1. Implementar `enrichWidget()` para, por listener `handler`, consultar o índice de
   reachability e emitir `handlerReachesTarget` / `handlerDirectlyReachesTarget`. **O flag que
   guia o widget SHALL ser o TRANSITIVO (`reachesTargetSignatures()`), não o direto [rev 06-21]:**
   handlers ofuscados multi-camada quase nunca chamam a API JCA diretamente — usar `directly...`
   anularia a resiliência a ofuscação na prática. O `direct` fica informativo.
2. Resolver handlers **ofuscados/fora do pacote** consultando o índice **por assinatura**
   (`index.reachesTargetSignatures().contains(handler)` / `directlyReachesTargetSignatures()`) —
   **fork B recomendado (decisão fechada).** ✅ **Verificado:** `reachesTargetSet` é BFS reversa
   a partir dos alvos JCA sobre o **call-graph completo** (`ReachabilityEngine.java:62,73`),
   **não** limitado a `appClasses`; só a serialização do array `reachability[]` é filtrada por
   pacote (`RvsecAnalysisClient.java:90,255-259`). Consultar o índice por assinatura **ignora o
   filtro de pacote** ⇒ o sinal é **resiliente a obfuscação por construção** — o aperv **não**
   tenta deobfuscar nem casar nomes; usa a **invariância da reachability sob renaming
   consistente do R8** (a assinatura renomeada continua nó do call-graph; a BFS reversa dos
   alvos se preserva). O fork A (relaxar `isAppClass` na saída) é **descartado**: ainda dependeria
   de acertar o pacote.
3. Fiar (`wire`) `enrichWidget` em `collectWidgets`/`writeWidget`.
4. ~~Alinhar `eventType`~~ — **fora do escopo do gator** (decisão fechada): a normalização
   `eventType` é feita no consumer (A-2). O producer mantém o snake_case atual.

**Limitações conhecidas (documentar, não bloquear).** Apps Jetpack Compose podem retornar 0
listeners na árvore de acessibilidade (handler vive em `transitions[].events`); precisão de
call-graph em dispatch por interface pode marcar `reachesTarget=false` em impls reais. São
limitações de análise estática separadas — registrar como gaps, não como falha desta change.

**Recall do SPARK = principal modo de falha (FN), não o formato [rev 06-21].** O match por
assinatura é **string-idêntico por construção** — ambos os lados (o `handler` em `listeners[]` e as
chaves do índice) derivam do mesmo `SootMethod.getSignature()`, logo **não** há risco de mismatch de
formato (o ponto make-or-break, confirmado na re-verificação). O risco real é de **recall**:
`reachesTargetSet` é a BFS reversa sobre o call-graph do SPARK e **não tem** a rede de segurança do
bytecode-scan — `findDirectTargetCallersByBytecodeScan` alimenta só o set **direto**
(`ReachabilityEngine.java:79`), não o transitivo. Um handler ofuscado cujo caminho ao alvo passa por
uma aresta app→lib cega ao SPARK fica **fora** do set e o lookup retorna `false` — exatamente o caso
ofuscado que G-1 quer cobrir. Documentar como FN conhecido (consistente com a investigação de FN do
gator, ~0.26% real), não como falha desta change. Pré-condição implícita a afirmar como invariante:
o handler do GUI/WTG e o método do call-graph são a **mesma resolução de Scene** (garante o
`getSignature()` canônico).

**Dependência upstream — `package_detector` (importante).** Hoje `filterPackage` = `codePackage`
do **`package_detector`** (`RvsecAnalysisClient.java:90`), que **não tem detecção de obfuscação** e
tem mis-picks conhecidos para pacotes de **biblioteca** (≥8 casos; sob R8 colapsa para prefixo
curto/lixo). A acurácia dele está sendo medida no plano de validação
`rvsec-testes-jca/docs/20260620_validar_package_detector.md` (M1/M2). Isso contamina o array
`reachability[]` (montado de `appClasses`) **e** o join por string do consumer. **O fork B
desacopla o aperv disso:** a pergunta de *steering* do aperv ("este handler alcança um alvo
JCA/MOP?") é uma propriedade de **reachability de grafo** que **não precisa** da partição
app-vs-lib — essa partição serve à *atribuição* do artigo (`\appCodePct`, RQ3), **não** à
exploração. Consultar a reachability por assinatura torna o sinal MOP do aperv robusto **mesmo
quando o `package_detector` erra o pacote**. (Ressalva direct/transitive: handlers ofuscados
fora de `appClasses` podem ter só `transitive=true` — o `directBcSet` é por `appClasses`,
`ReachabilityEngine.java:79`; o componente CG `findDirectTargetCallers(graph,…)` é full-graph;
de todo modo `transitive` já rende +300 p/ steering.)

**Spec deltas.** Capability sugerida: `reachability-enrichment`.
- O producer SHALL emitir `handlerReachesTarget` e `handlerDirectlyReachesTarget` por listener
  em `windows[].widgets[].listeners[]`.
- A reachability de handler SHALL ser computada mesmo quando a classe do handler é
  R8-ofuscada / fora do pacote do app.
- A consulta que **guia** o widget SHALL usar o índice **transitivo** (`reachesTargetSignatures()`);
  o flag direto é informativo, não o gate de steering. [rev 06-21]
- A limitação de recall do SPARK (FN no set transitivo, sem rede de bytecode-scan) SHALL ser
  documentada como gap conhecido, não tratada como falha desta change. [rev 06-21]
- (`eventType` resolvido no consumer — ver A-2; sem requisito de mudança no producer.)

**Tasks.**
1. **Reusar** o índice consultável por assinatura — **já existe** [rev 06-21]:
   `ReachabilityIndex.reachesTargetSignatures()` / `directlyReachesTargetSignatures()` (pré-computados
   no construtor), expostos via `ReachabilityEnricher.targetSignatures()` / `directTargetSignatures()`.
   G-1 é **fiação**, não construção de índice — reduz o esforço estimado.
2. Implementar `enrichWidget()` (resolver handler → flags).
3. Tratar classes de handler ofuscadas/fora do pacote (decidir fork A vs B acima).
4. Fiar em `collectWidgets`/`writeWidget`.
5. Regenerar JSONs de amostra (apps com handler de cripto **ofuscado**) e validar flags não-nulos.

**Critérios de aceitação.** JSON regenerado tem `handlerReachesTarget` não-nulo para apps com
widgets de cripto; o `ape` deixa de depender do join por string.

**Riscos / rollback.** Precisão de análise estática. Rollback: manter o caminho por string no
consumer (A-2 já mantém como fallback). Mudança é aditiva (novos campos), não quebra schema.

---

## 3. Changes no repo `phtcosta/ape` (@ `master`)

### A-1 — `build-provenance-stamp`  — ❌ DESCARTADA (2026-06-21)

> **DESCARTADA antes da implementação — redundante com B-1.** Issue phtcosta/ape#14 fechada como
> not-planned; change `gh14-build-provenance-stamp` arquivada com `--skip-specs` (commit `6b7b96f`).
> Motivo (§0-bis): o `boost=0` é puramente o jar stale; o parser já está pronto no fonte (`138a161`).
> Com B-1 buildando o fonte na imagem, a proveniência é um fato de **build-time** (`APE_REF` + label),
> e um banner `[APE-BUILD]` em runtime não agrega valor único (P1). Texto abaixo mantido por rastreabilidade.

**Problema (✅ confirmado).** O jar **não tem build-stamp** — não há `BuildConfig`,
`git.properties` nem constante de versão do APE-RV (só versões do runtime Android). O skew do
§B-1 ficou invisível até inspeção manual do dex.

**Solução.**
1. ⚠️ **NÃO** usar `build-info.properties` como *resource*: o `d8` (`pom.xml:111-154`) dexa só
   `.class` — um resource não entra no `ape-rv.jar` e `getResourceAsStream` retornaria null. Em
   vez disso, **gerar uma constante Java via template Maven** (`templating-maven-plugin` ou
   fonte gerado filtrado) com git sha, timestamp e schema version — sobrevive a `javac`→`d8`.
2. Util de carga + banner `[APE-BUILD]`. ⚠️ **Decoplar da carga de MOP:** emitir no init do
   `MonkeySourceApe`/`Monkey`, **antes** de `MopData.load` (`StatefulAgent.java:162`, que já
   loga). Não pode ser "linha 1" se ficar no fim do construtor (`:166`). Reusar a contagem de
   widgets de `MopData.java:240-244` quando o MOP estiver carregado.
3. Banner emite: `git_sha`, `jar_built`, `schema`, `mopDataPath`, `mopLoaded` (bool),
   `mopWidgetCount`.

**Spec deltas.** Capability: `build` (já existe; **não** criar `observability`/
`build-provenance` — não existem).
- O build SHALL embutir git sha, timestamp e schema version no jar.
- No início da sessão o agente SHALL emitir uma linha `[APE-BUILD]` com sha, data de build,
  schema, `mopDataPath`, `mopLoaded` e contagem de widgets MOP.

**Tasks.** (1) plugins Maven no `pom.xml` (captura de git sha/timestamp + templating);
(2) gerar **`BuildInfo.java`** (constante Java, **não** `.properties` — ver Explore abaixo);
(3) util/banner; (4) emitir banner no construtor de `StatefulAgent` logo após `:162`;
(5) testes JUnit do banner/constante.

**Critérios de aceitação.** `[APE-BUILD]` aparece no início da sessão (antes de qualquer log de
MOP); um jar legado mostraria `schema=v1`/sha antigo, capturando o skew automaticamente.
Fornece o marker para B-1.

**Riscos.** Baixo. Não muda comportamento de exploração.

**Explore (Phase 1) — verificação na fonte [2026-06-21, SHA `138a161`].** Todas as âncoras
confirmadas por leitura direta; nada criado ainda (sem issue, sem change dir).
- **Âncoras ✅:** construtor `StatefulAgent` `:157-167`, `MopData.load(Config.mopDataPath)` em
  `:162`, fecho do construtor `:167`. `MopData.java:240-244` é o log `MopData: loaded N widgets…`
  com a contagem vinda do helper `countWidgets(widgetData)` (def `:715`) — reusável para
  `mopWidgetCount`. `pom.xml`: `maven-jar-plugin` (prepare-package, `:111-125`) → `ape-rv-classes.jar`;
  `exec-maven-plugin`/`d8` (package, `:133-154`) → `target/ape-rv.jar`; `maven-resources-plugin`
  (install, `:163-185`) → cópia para `aperv-tool`.
- **Claim do d8 CONFIRMADA (decide a abordagem):** corroborada pelos invariantes da própria spec
  `build` — **INV-BUILD-01** (saída contém `classes.dex`) e **INV-BUILD-06** (sem `.java` no jar):
  o `ape-rv.jar` é só o `classes.dex`. Um `build-info.properties` empacotado no jar intermediário
  seria **descartado pelo `d8`** ⇒ `getResourceAsStream` retorna `null` no device. **Logo: constante
  Java gerada (`BuildInfo.java`) via `templating-maven-plugin`**, alimentada por git sha + timestamp
  (candidato: `git-commit-id-maven-plugin` ou `buildnumber-maven-plugin`; escolha do plugin é
  detalhe de Design).
- **Greenfield ✅:** sem `templating`/`git-commit-id`/`buildnumber`/`BuildConfig`/`BuildInfo` e
  **sem constante de schema** no fonte hoje. A spec `build` existe
  (`openspec/specs/build/spec.md`, INV-BUILD-01..08) — A-1 a **estende** (novo Requirement +
  provável `INV-BUILD-09` para o stamp embutido). Schema OpenSpec do repo: `sdd-full`, **sem skills
  `rv-*`** (validação manual: JUnit + device).
- **Decisão de design 1 (recomendada — colocação do banner):** emitir **um** `[APE-BUILD]` no
  construtor logo **após** `:162`, com `git_sha`/`jar_built`/`schema` (constante gerada),
  `mopDataPath` (de `Config`), `mopLoaded = _mopData != null`, `mopWidgetCount` (de `_mopData`).
  "Antes de qualquer log de MOP" lê-se como **antes dos logs de scoring `[APE-RV] MOP boost`**
  (`StatefulAgent.java:1372`) — que ele satisfaz. Descartada a alternativa de fundir o log interno
  `MopData:240` no banner (acopla sem ganho — P1). `mopLoaded`/`mopWidgetCount` só são conhecidos
  pós-`load`, então o banner é necessariamente pós-`:162`.
- **Decisão de design 2 (recomendada — origem do campo `schema`):** introduzir **uma** constante
  Java declarando a versão do schema do JSON-MOP que o jar entende. **Independente** da change aberta
  `gh13-mopdata-schema-v2` (que este plano trata como esquecida e **não** dependência).
- **Ambas as decisões pendem da revisão no checkpoint de Propose** (não decididas unilateralmente).

---

### A-2 — `mop-scorer-correctness`  — ✅ ENTREGUE via gh15 (2026-06-22)

> **✅ ENTREGUE (bundle `gh15`, 2026-06-22).** Resíduo real implementado: **B3** (containment pai/filho resolvido caller-side em `StatefulAgent.adjustActionsByGUITree`, depth ≤2, com `containment=N` na telemetria — design D2, sem mudar a assinatura de `MopScorer.score`); **B4** (removido o `return 0` precoce; fallback de atividade alcançável p/ widget resolvido-mas-sem-flag, INV-MOP-07); **B6** (`MopData.normalizeEventType` snake⇄camel nos dois lados, INV-MOP-08). **B9** permanece descopado (código dormente gh13 intocado). Commit `a8f2fa6`. Banner histórico abaixo por rastreabilidade.

> **Reduzida e adiada pela medição (§0-bis).** O parser, `eventType` e a precedência de
> `handlerReachesTarget` **já estão prontos no fonte** (`138a161`, gh13) — não são trabalho. O único resíduo
> com valor próprio é **B3** (granularidade pai/filho), que cobre ~27% dos 65 miss-apps. B4 (fallback +100
> uniforme), B9 (strict OFF) e B6 (eventType mascarado) têm valor marginal — revisitar conforme o
> escopo analítico. A comparação dos 169 valida no fim (não gateia).

**Escopo real (menor do que parece) [rev 06-21].** O parser tipado, a infraestrutura de `eventType`
e a precedência de `handlerReachesTarget` (`MopData.java:388-390`) **já existem no código**. O peso
de A-2 concentra-se em: **B3** (reconciliação pai/filho — **única mudança de API pública**:
`MopScorer.score()` hoje recebe só `String shortId`, sem `GUITreeNode`, então o containment ripa para
o caller `StatefulAgent.java:1365`, o overload de 3-arg e os testes); **B4** (reordenar o `return 0`);
**B9** (passar package/mainActivity, default OFF); e **fechar B6** (normalizar snake⇄camel). ⚠️ B4 e
B6 partilham o mesmo fallback OR-agregado — **a ordem de correção importa** (corrigir B6 sem B4 pode
mudar comportamento de forma não-óbvia).

**Problemas (todos ✅ confirmados salvo onde indicado).**
- **B4 — curto-circuito do fallback `+100`.** `MopScorer.java:40-51`: quando `getWidget()`
  retorna widget não-nulo com flags todas falsas, `score()` faz `return 0` (`:48`) **antes**
  de `if (data.activityHasMop(activity)) return Config.mopWeightActivity` (`:50-51`). O
  fallback de atividade só é alcançável quando `widget==null`.
- **B3 — granularidade de widget** (🟡 consumer confirmado). Runtime usa
  `MopData.extractShortId(node.getResourceID())` (`StatefulAgent.java:1364`); lookup é get
  exato em dois níveis `widgetData.get(activity).get(shortId)` (`MopData.java:620-623`), sem
  reconciliação pai/filho. Static pode flagar id do pai (CardView) e runtime clicar id do
  filho (LinearLayout) ⇒ miss.
- **`handlerReachesTarget` é o bypass pretendido** (✅). Parser já lê (`MopData.java:374-375`)
  e `deriveWidgetMopFlags` já o prefere ao join por string (`:388-390`); hoje sempre cai no
  `else` (join por igualdade exata `bySignature.get(l.handler)`, `:392`, índice montado em
  `:281-282`) porque o producer não emite o flag. Após G-1, o caminho preferencial passa a
  funcionar.
- **B9 — `mopStrictPackageMatch` é código morto** (✅). `StatefulAgent.java:162` chama
  `MopData.load(path)` 1-arg → `load(path, null, null)` (`MopData.java:148-150`); a checagem
  estrita (`:225,:230,:235-238`) nunca dispara.
- **B6 — `eventType` camelCase vs snake_case** (⚠️ latente, mascarado). `MopScorer.java:138-143`
  emite `longClick`/`itemSelected`; producer emite snake_case (ver G-1). Hoje mascarado pelo
  fallback OR-agregado (`Widget.isDirectMop`, `MopData.java:755-759`). Severidade baixa, mas
  reaparece quando o MOP voltar a disparar.

**Solução.**
1. Reordenar `MopScorer.score()` para cair no fallback `activityHasMop()→+100` quando o widget
   é resolvido mas não-flagado (mover o `return 0` para depois do check de atividade).
2. Reconciliar granularidade: quando `getWidget(activity,shortId)` falha, tentar ids de
   ancestrais/descendentes (containment na árvore do `node`) antes de declarar no-match.
   ⚠️ **Requer acesso ao nó:** `MopScorer.score(...)` hoje recebe só `String shortId` (sem
   `GUITreeNode`); decidir entre **mudar a assinatura** de `score()` ou fazer o lookup de
   ancestrais no chamador (`StatefulAgent.java:1364`). Limitar profundidade (≤2–3) e logar
   hit-rate para evitar over-boost (raiz `LinearLayout` marcada por um único filho).
3. Consumir `handlerReachesTarget` como caminho primário (já fiado); manter join por string
   como fallback.
4. B9 (decisão revisada — **passar `package`/`mainActivity`, default OFF**): chamar `load`
   3-arg com os valores de runtime, mas manter `mopStrictPackageMatch=false` por padrão até o
   M-1 validar. ⚠️ **Risco:** APK instrumentado pode divergir legitimamente em
   package/mainActivity ⇒ checagem estrita rejeitaria JSON válido e deixaria o MOP **mais**
   inerte. Ativar (default ON) só após confirmar que a instrumentação não altera esses campos.
5. B6 (decisão fechada — **normalizar no consumer**): canonicalizar `eventType` ao ler o JSON e
   em `eventTypeOf` (mapear snake_case ⇄ camelCase pro mesmo token), sem tocar o gator nem a
   spec gh13.

**Spec deltas.** Capability: `mop-guidance` (já existe no `ape/openspec`).
- O scorer SHALL cair no boost de atividade quando um widget é resolvido mas sem flag MOP de widget.
- A resolução de widget SHALL reconciliar granularidade pai/filho (containment) antes de no-match.
- Quando `handlerReachesTarget` está presente ele SHALL ter precedência sobre o join por assinatura.
- `load` SHALL receber `package`/`mainActivity` de runtime; `mopStrictPackageMatch` default OFF até validação (M-1).
- O `eventType` SHALL ser normalizado no consumer (snake_case ⇄ camelCase) antes da comparação.

**Tasks.** Edits em `MopScorer.java:40-51` (reorder), `MopData.java:620-623`+
`StatefulAgent.java:1364` (containment), `StatefulAgent.java:162`/`MopData.java:148-150`
(B9), `MopScorer.java:138-143` (B6); testes unitários cobrindo cada caso.

**Critérios de aceitação.** `maxBoost>0` **não** é critério desta change — já ocorre **hoje**
em app de pacote limpo (cryptoapp) só com B-1 (ver §1.1.2). O critério de A-2 é: em app
**ofuscado** (R8) com JSON do G-1, observa-se `maxBoost>0` em `[APE-RV] MOP boost`; e os testes
de fallback (+100), granularidade pai/filho, B9 e normalização de `eventType` passam.

**Riscos / rollback.** Mudança de comportamento de scoring — proteger com testes; manter join
por string como fallback. ⚠️ **Correção:** o fallback `+100` (B4) **não** tem valor
independente em apps ofuscados — `mopActivities` só é populado quando um widget já tem
`directMop||transitiveMop` (`MopData.java:317-318`), que depende do mesmo join que o R8 quebra;
e o `+100` é **uniforme** para todo widget da activity MOP (não discrimina widget). B3/B9 têm
valor próprio; B4 só agrega quando o join (ou G-1) já produziu sinal — considerar escalar o
`+100` pela densidade de MOP da activity.

---

### A-3 — `decouple-component-triggering`

> **✅ ENTREGUE (bundle `gh15`, 2026-06-22).** `componentPercentage` default `0.0` (sem ternário em `mopDataPath`), INV-CT-01. Device (`aperv:sata_mop`, cryptoapp): **0 `[APE-RV] Triggering`** sem `ape.componentPercentage` explícito. Commit `a8f2fa6`; #15 fechada.

**Problema (✅ confirmado).** `Config.java:169-170`:
`componentPercentage = getDouble("ape.componentPercentage", mopDataPath != null ? 0.05 : 0.0)`.
O default de triggering muda ao ativar MOP ⇒ braços `sata` (0%) e `sata_mop` (5%) diferem em
**duas** variáveis, confundindo a comparação.

**Solução.** Default fixo `0.0` independentemente de `mopDataPath`; triggering só por
`ape.componentPercentage` explícito. Ajustar configs de experimento (`tool.py`/properties) para
setar explicitamente onde se quiser triggering. Documentar.

**Spec deltas.** `Config` SHALL NOT derivar `componentPercentage` de `mopDataPath`; default
`0.0`; triggering habilitado apenas por config explícita.

**Tasks.** (1) editar `Config.java:169-170`; (2) atualizar configs de experimento;
(3) atualizar `CLAUDE.md`/docs.

**Critérios de aceitação.** `sata` e `sata_mop` diferem em exatamente uma variável (o scorer).

**Riscos.** Baixo; muda defaults de experimento (intencional).

---

### A-5 — `step-decision-logging`

> **✅ ENTREGUE (bundle `gh15`, 2026-06-22).** `ModelAction` ganhou enum `DecisionSource` (9 fontes) + boosts por mecanismo (`mop/wtg/coverage/menu`) + `resetBoosts()`; `StatefulAgent.resolveNewAction()` emite um `[APE-STEP]` por ação finalizada; `SataAgent` seta a fonte em todos os caminhos de retorno (SATA via `logActionSelected`, Budget, 3 hooks LLM). INV-SEL-04. Device: **136/136** `[APE-STEP]`, 0 dups.

**Problema (✅ confirmado).** As linhas de boost existentes (`[APE-RV] MOP boost`
`StatefulAgent.java:1372-1373`; `menu` `:1380-1381`; `WTG` `:1407-1408`; `Coverage`
`:1430-1431`) logam **agregados durante o scoring**, mas nenhuma atribui a **ação final
escolhida** a uma fonte de decisão. Impossível atribuir impacto de MOP/LLM/Coverage por ação.

**Solução.** Emitir `[APE-STEP]` no ponto de finalização da seleção —
`StatefulAgent.resolveNewAction()` logo após `selectNewActionNonnull()`
(`StatefulAgent.java:1259`; impl em `SataAgent.java:294`). ⚠️ **Não é só uma linha de log:** a
fonte SATA já é logada via `logActionSelected`/`SataEventType`, mas os early-returns de
LLM/componente/budget (`SataAgent.java:~313-350`) **a contornam**, e `ModelAction` **não tem
campo de fonte de boost**. Orçar A-5 como **mudança de modelo**: adicionar campo de
proveniência/fonte em `ModelAction` e cobrir todos os caminhos de retorno (incl. early-returns).
Campos do log: `step#`, `elapsed_ms`, `state`, `action`, `decision_source`
(SATA|MOP|Coverage|LLM|Fuzz|Menu|WTG|Component|Budget), boost por mecanismo, resultado. Definir
o formato/enum explicitamente (evitar parse heurístico). Opcional: `[APE-END]` por run.

**Spec deltas.** Capability: `action-selection` (já existe; **não** há `observability`). O
agente SHALL emitir uma linha `[APE-STEP]` por ação selecionada, com a fonte da decisão e os
boosts por mecanismo, cobrindo todos os caminhos de seleção (incl. early-returns).

**Tasks.** (1) instrumentar `StatefulAgent.java:1259`; (2) propagar a fonte de decisão do
scoring até a seleção; (3) (opcional) `[APE-END]`; (4) flag de toggle p/ volume de log.

**Critérios de aceitação.** Cada passo é atribuível a uma fonte; análise de experimento deixa
de depender de heurística sobre logs agregados.

**Riscos.** Volume de log — tornar estruturado e toggleável.

---

### A-4 — `faithful-ui-coverage`

> **✅ ENTREGUE (bundle `gh15`, 2026-06-22).** `widgetId` de ação-alvo agora `toXPath()+"|"+TYPE` (INV-COV-06); `stateData` é `LinkedHashMap` access-ordered limitado a `Config.coverageMaxStates` (default 2000), com `foldIntoRollup` preservando contagens por Activity na evicção (INV-COV-05); `getActivityCoverageGap()` agrega fragmentos de naming. Device: cobertura limitada, **sem OOM**, não zerada.

**Problemas (✅/⚠️ confirmados).**
- **B5** — chave de cobertura por `action.getTarget().toXPath()` (`UICoverageTracker.java:191-199`,
  `:196`), path abstrato/relativo; tipos de ação distintos no mesmo target colapsam numa chave
  (tipo só entra na chave para ações não-alvo, `:198`). `Name.toXPath()` em `Name.java:22`.
- **Fragmentação de estado** — `stateData` é keyado por `State`/`StateKey`, e `StateKey`
  inclui `naming` no identity/`hashCode` (`StateKey.java:45-62`, `:47`). Uma Activity fragmenta
  em N mapas de cobertura (média ~22, máx ~84) ⇒ "gap" inflado, descorrelacionado de cobertura
  de método.
- **B-Cov** (⚠️ mais estreito que o alegado) — `registerScreenElements` reconstrói `newData`
  iterando só `actions` (`:65-103`, put em `:102`), mas **carrega** counts antigos de widgets
  ainda presentes (`:88-93`). Widget dinâmico (`recordInteraction` `:130-133`) é esquecido
  **só** quando some de `actions`.
- **Sem poda** (✅) — `stateData` nunca tem `remove`/`clear`/`evict`; cresce monotonicamente
  (instância única por agente, `StatefulAgent.java:163`). Risco latente de OOM (já citado no
  `CLAUDE.md`).

**Solução.** (1) Rekey `widgetId` para `(toXPath, actionType)` — incluir `actionType` **também
para ações de alvo** (hoje o tipo só entra na chave para ações não-alvo,
`UICoverageTracker.java:198`); (2) agregar cobertura no nível de Activity (colapsar fragmentos
de `naming`) **para reporte**; (3) preservar widgets dinâmicos na re-registração; (4)
limitar/podar `stateData` — **preservar o rollup antes de evict** (não zerar cobertura no meio
do run).

**Spec deltas.** Capability: `ui-coverage`. A chave de cobertura SHALL incluir o tipo de ação;
o reporte SHALL agregar por Activity independente da abstração de naming; `stateData` SHALL ser
limitado.

**Tasks.** Edits em `UICoverageTracker.java:191-199` (rekey), agregação por Activity,
`:65-103` (dinâmicos), poda/limite; testes.

**Critérios de aceitação.** "gap" correlaciona melhor com cobertura de método; memória limitada.

**Riscos.** Redefine semântica da métrica (decisão fechada: **substituir**, sem manter a antiga
em paralelo). Comparabilidade histórica do "gap" é abandonada — aceitável, pois o gap é sinal
interno de steering, não o resultado headline (cobertura de método vem do rv-android,
independente). Documentar a quebra no `CLAUDE.md`/notas de experimento.

---

### A-6 — `llm-throughput-and-secure`

> **✅ ENTREGUE (bundle `gh15`, 2026-06-22).** `LlmRouter` chama `breaker.recordFailure()` + short-circuit no screenshot null (paridade com IOException/parse); `Config.llmPercentage` clampado a `[0,1]` no load (INV-RTR-08); linha stale `llmMaxCalls` removida do `CLAUDE.md` (não reintroduzida). Gate device **(c)** FLAG_SECURE→breaker **não exercitado** (cryptoapp não é secure-window, `sata_mop` sem LLM) — opcional, coberto por code review + INV-RTR-08.

**Problemas.**
- **Latência domina** (✅ análise) — `llmPercentage` agressivo (até 0.9) gasta ~46–49% do
  orçamento de 300s em chamadas LLM; ~1.9–2.1× menos ações. Median 1.0s/call, 0 timeouts,
  match 84%.
- **FLAG_SECURE** (✅ análise) — `ScreenshotCapture` retorna null em janelas FLAG_SECURE;
  `LlmRouter.java:245-249` não chama `breaker.recordFailure()` ⇒ retenta ~todo passo. 3 apps
  100% null (`securecamera_30`, `passvault_36`, `notesr_59`) viram "SATA disfarçado".
- **`llmMaxCalls` foi REMOVIDO** (✅ confirmado) — `fix(gh12)` `e2d9f49` ("remove llmMaxCalls
  limit"); **0 ocorrências** em `src/main/java/`. Só o `CLAUDE.md:133` ficou desatualizado. Não
  é "bug a implementar": é **doc obsoleta a remover**.

**Solução.** (1) Reduzir/segmentar `llmPercentage` (restringir a stagnation+new-state) e
clampar a `[0,1]`; (2) em screenshot null: `breaker.recordFailure()` + short-circuit + fallback
SATA gracioso; (3) **remover a linha `llmMaxCalls` do `CLAUDE.md:133`** (não reimplementar).

**Spec deltas.** Capability: `llm-routing` (já existe; **não** há `llm-integration`). Em
screenshot null o router SHALL registrar falha no breaker e short-circuit; a taxa default SHALL
ser calibrada ao orçamento e clampada a `[0,1]`. (Sem requisito de teto rígido — `llmMaxCalls`
foi removido em gh12; ver §5.)

**Tasks.** (1) calibrar/segmentar routing (clamp `[0,1]`); (2) tratar FLAG_SECURE em
`LlmRouter.java:245-249`; (3) **remover a linha `llmMaxCalls` do `CLAUDE.md:133`** — **NÃO
existe no código** (removido em gh12 `e2d9f49`) e **NUNCA** deve ser reintroduzido; **sem** teto
rígido de chamadas no aperv; (4) testes.

**Critérios de aceitação.** Throughput recupera; apps FLAG_SECURE não desperdiçam orçamento.

**Riscos.** Tuning; medir com A-5.

---

## 4. Metodologia (não-código)

### M-1 — re-run experimental controlado

Após B-1 + A-1 + G-1 + A-2: re-rodar com **jar verificado** e proveniência no trace.
- **Braço de controle B-1-isolado (NOVO):** rodar com só o jar correto (sem G-1/A-2). Como
  `maxBoost>0` já ocorre só com B-1 (ver §1.1.2), o **delta** entre este braço e o braço com
  G-1/A-2 **é** a contribuição de G-1/A-2 — medida direto, **sem precisar rotular apps**. Sem
  este braço, credita-se a G-1/A-2 um efeito que pode ser de B-1.
- **Pré-registrar** critérios de exclusão e hipóteses **antes** de olhar resultados
  (anti-HARKing). Lista de exclusão (a fixar antes): apps FLAG_SECURE (`securecamera_30`,
  `passvault_36`, `notesr_59`) e zero-coverage que são falha de app, não da ferramenta
  (`de.readeckapp_900`, `io.github.snd_r.komelia_18`).
- **≥5 reps por APK** (std cross-APK ~18 pp; ruído inter-rep 10–15 pp tornam n=3
  subdimensionado); declarar effect-size / power-alvo.
- Desenho isolando uma variável por braço (graças a A-3).
- Confirmar `maxBoost>0` antes de qualquer calibração de pesos MOP. ⚠️ **Caveat de eficácia:**
  `maxBoost>0` mede **mecânica**, não ganho. O resultado headline continua sendo cobertura de
  método / violações (vem do rv-android), não o boost — a memória registra `sata_mop≈sata`.

**Onde mora:** scripts/experimentos em `PAMunb/rvsec` (não necessariamente OpenSpec; pode ser
nota metodológica + config).

---

## 5. Claims refutadas / rebaixadas (não gastar esforço)

- ❌ **"Regressão causal do MOP"** — é empate/ruído (consistente com a memória do projeto). O
  problema é MOP **inerte**, não "pior". Não perseguir.
- ❌ **"Component triggering causa a regressão"** — service launches falham ("Start Service
  error: null"), 0 crashes/ANR, correlação ≈0. A-3 resolve a **validade**, não uma regressão.
- ❌ **"Vitórias iniciais (H-F) eram evidência de MOP"** — recomputado, não significativas.
- ⚠️ **B6 (eventType)** — real mas baixa severidade, mascarado pelo OR-agregado; dobrar em
  G-1/A-2, não como change própria.
- ⚠️ **B-Cov (widget dinâmico)** — mais estreito que alegado; dobrar em A-4.
- ✅ **`llmMaxCalls` foi removido (gh12 `e2d9f49`)** — não é bug a implementar; só doc obsoleta
  (`CLAUDE.md:133`) a remover (A-6).
- ❌ **"O parser ignora o canal `transitions`"** (uma das análises) — refutado: `transitions[]`
  é parseado e usado no WTG; na fixture real os handlers que casam aparecem **nos dois** canais.
- ❌ **"Risco de chave SSH no clone (P0)"** — o clone do Dockerfile já é **HTTPS** (e nunca
  builda o ape, de qualquer forma). **Mantém-se válido após B-1 [rev 06-21]:** o build-from-source
  clona o `ape` também por **HTTPS** (repo tornado público), sem SSH.
- ⚠️ **"Colisão garantida de `idName` vazio"** — condicional a o producer emitir `""` literal; o
  mapa estático pula `idName` null. Verificar a saída do producer antes de tratar como bug.
- ⚠️ **"FLAG_SECURE" como mecanismo de código** — **não existe** referência a FLAG_SECURE no
  fonte; o null do screenshot é inferência de log de experimento. O fix do `recordFailure()`
  continua válido; rotular a causa como inferência, não fato de código (A-6).
- ⚠️ **"~97% dos widgets MOP-alcançáveis são incidentais"** — não verificável (sem artefato);
  **não acionar** (não é um achado; não embutir maquinaria para medir isso).

---

## 6. Mapa change → arquivos-âncora (para criação direta)

| Change | Arquivos-âncora (arquivo:linha) |
|---|---|
| B-1 | `rv-android/docker/rvandroid/Dockerfile:13` (inserir build do ape após o clone do rvsec, antes do `uv sync`); `rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar` (deletar, git-tracked); `rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/.gitignore` (vazio → adicionar `ape-rv.jar`); `ape/pom.xml:111-125,133-154,163-185` (referência do build). **Fora de escopo:** `docker-compose.*.yml`, `scripts/calibration_orchestrator.py` (histórico/experimento). |
| A-1 | `ape/pom.xml`; `StatefulAgent.java:157-167,166`; `MopData.java:240-244` |
| G-1 | `rvsec-gator/.../reach/ReachabilityEnricher.java:56-64,71`; `ReachabilityIndex.java` (índice por assinatura `reachesTargetSignatures()`); `RvsecAnalysisClient.java:90,110,255-269,277-286,935-947,1410-1417,939,1712`; `JsonSchema.java:48,49,88` (eventType resolvido no consumer — ver A-2; sem mudança no gator) |
| A-2 | `MopScorer.java:40-51,138-143`; `MopData.java:148-150,225,230,235-238,281-282,374-375,388-392,620-623,755-759`; `StatefulAgent.java:162,1364` |
| A-3 | `Config.java:169-170` |
| A-5 | `StatefulAgent.java:1256-1269,1259,1372-1373,1380-1381,1407-1408,1430-1431`; `SataAgent.java:294`; `LlmRouter.java:573,582` |
| A-4 | `UICoverageTracker.java:65-103,88-93,102,130-133,191-199`; `StateKey.java:45-62`; `Name.java:22`; `StatefulAgent.java:163,661,1195` |
| A-6 | `LlmRouter.java:245-249`; `ScreenshotCapture.java`; `Config.java`; `CLAUDE.md` (llmMaxCalls) |

> Caminhos do gator relativos a
> `rvsec/rvsec-android/rvsec-gator/client/src/main/java/presto/android/gui/clients/`
> (e `.../sootandroid/...` para `EventType`).

---

## 7. Decisões

### Fechadas
- ✅ **B-1 = build-from-source.** O Dockerfile clona o `ape` e gera o jar dentro da imagem
  (não gate sobre binário). Ver B-1.
- ✅ **OpenSpec do `PAMunb/rvsec` sempre em `rv-android/openspec/`** — inclusive G-1 (gator) e
  qualquer change de outro módulo. Não criar `openspec/` separado.
- ✅ **`eventType` (G-1/A-2): normalizar no consumer (ape).** Canonicaliza no `MopData`/
  `MopScorer` (snake_case ⇄ camelCase mapeiam pro mesmo token). Não toca o gator, não regenera
  JSONs, não muda a spec gh13. → A-2 absorve isso; G-1 **não** precisa alterar `eventType`.
- ✅ **A-4: redefinir a métrica (substituir).** O "gap" é sinal interno de steering, não o
  resultado headline (cobertura de método vem do rv-android, independente). Substitui pela
  métrica fiel; **não** manter a antiga em paralelo.
- ✅ **B9 (revisado): passar `package`/`mainActivity` ao `load` 3-arg, mas
  `mopStrictPackageMatch` default OFF.** Ganha o caminho de detecção de skew JSON↔app, sem
  arriscar rejeitar JSON de APK instrumentado que divirja legitimamente nesses campos. Ativar
  (default ON) só após o M-1 confirmar que a instrumentação não altera package/mainActivity. (A
  decisão anterior "ativar" era arriscada.)
- ✅ **B-1: deletar o binário commitado** de `modules/aperv-tool/.../ape-rv.jar` no branch
  `modules`. Single source of truth; sem fallback ao binário stale (foi a causa raiz original).
- ✅ **G-1 = resiliência a obfuscação por reachability de grafo (fork B), desacoplado do
  `package_detector`.** `enrichWidget` consulta o índice de reachability **por assinatura** sobre
  o call-graph completo, ignorando o filtro `isAppClass`/`codePackage`. O aperv **não** deobfusca
  nem casa nomes; usa a invariância da reachability sob renaming consistente do R8. A partição
  app-vs-lib do `package_detector` (em validação, plano `20260620_validar_package_detector.md`) é
  para a *atribuição* do artigo, **não** para o steering do aperv.
- ✅ **`llmMaxCalls` NÃO existe e NUNCA deve existir.** Removido em gh12 (`e2d9f49`); só sobra doc
  obsoleta a apagar (`CLAUDE.md:133`). Sem teto rígido de chamadas LLM no aperv (A-6).
- ✅ **B-1: `phtcosta/ape` torna-se público; clone HTTPS no build [rev 06-21].** Resolve o acesso
  sem secret/SSH e preserva a refutação do risco de chave. Pré-condição: tornar o repo público
  antes do primeiro build da imagem. Build novo é a única fonte do jar (deletar o commitado).
- ✅ **G-1: `enrichWidget` consulta o índice TRANSITIVO [rev 06-21].** `reachesTargetSignatures()`,
  não `directlyReachesTargetSignatures()` — o direto anularia a resiliência a ofuscação. O recall do
  SPARK é FN conhecido (sem rede de bytecode-scan no set transitivo): documentar, não bloquear. O
  índice já existe — G-1 é fiação.
- ✅ **Processo OpenSpec difere por repo [rev 06-21].** `phtcosta/ape`: schemas
  `sdd-full`/`sdd-quick-path`, **sem** skills `rv-*` (verificação manual). `PAMunb/rvsec`:
  `rv-sdd`/`quick-path` + skills `rv-*` + CI. **Dois namespaces `gh<N>` independentes** — cross-ref
  SHALL nomear o repo (`phtcosta/ape#N` vs `rvsec#N`). Tracks por change na tabela §1.2.

#### Fechadas pela medição [rev 06-21 #2 — ver §0-bis]
- ✅ **`boost=0` = jar stale, não ofuscação.** 0/147.153 avaliações com `maxBoost>0` na corrida de junho;
  causa = chave `reachesMop` (jar `c5d76943`) vs `reachesTarget` (JSON gh60). **Desbloqueio é puramente B-1.**
- ✅ **Parser pronto no fonte — gh13 não é trabalho.** `138a161` já lê `reachesTarget`/`directlyReachesTarget`/
  `targetMethods`. Nada a implementar nem validar no consumer.
- ✅ **A-1 descartada** (redundante com B-1; #14 not-planned, change arquivada `--skip-specs`, commit `6b7b96f`).
- ✅ **G-1 adiada/provável drop.** Ofuscação 5/169 (não 88%); 98/169 dão boost só com o jar correto. Construir
  decisão por análise + eventual spike no gator (recall nos miss-apps). NÃO gated por experimento.
- ✅ **A-2 reduzida a B3** (granularidade); o resto já está na gh13. Escopo analítico.
- ✅ **A-3 é P0** (não P1): confound de component-triggering provado em dados (548 vs 45 traces); sem ela a
  comparação difere em 2 variáveis.
- ✅ **Sem gate de experimento intermediário.** A comparação dos 169 roda UMA vez, no fim do plano
  inteiro, só para validar resultado e fechar as changes — **nada depende dela**. O escopo das changes
  (A-2/B3, G-1 provável drop, etc.) é decidido por análise, não por um re-run intermediário.

#### Fechadas no Explore de B-1 [rev 06-21 #3 — rvsec#71/gh71]
- ✅ **Sem `ARG`/sem pin de SHA.** O Dockerfile clona o branch default do `ape` (sempre o mais atual
  no build); overridável só editando o Dockerfile, se algum dia precisar. (Simplicidade — nunca será
  sobrescrito.) Supersede o "pin 138a161" do texto antigo.
- ✅ **Single-stage.** O base `phtcosta/rvandroid_tools:0.9.1` já tem `d8` (build-tools 35.0.1),
  `mvn` 3.9.16, `git`, JDK 25 — multi-stage desnecessário. JDK 25 não é risco (`--release 11` padrão;
  o base já builda todo o rvsec sob JDK 25).
- ✅ **Sem mudança de tag de imagem.** Re-build no mesmo tag.
- ✅ **B-1 não toca compose/orchestrator.** `docker-compose.*.yml` e `calibration_orchestrator.py`
  são config/execução de experimento (domínio do usuário) e arquivos históricos (incl. A/B
  `final-*`/`revalidate-*`). Escopo de B-1 = 3 edições de build-chain (Dockerfile + deletar jar +
  `.gitignore`).
- ✅ **"98" é número analítico, não um teste.** A comparação final é nos 169 APKs e é executada pelo
  usuário (M-1), fora desta change. O usuário monta/roda; Claude só confere para fechar as changes.
- ✅ **Commit atômico.** Deletar o jar commitado + adicionar o build no Dockerfile no mesmo commit
  (hoje o jar entra na imagem via o clone do rvsec; isolar qualquer metade quebra o runtime).

### Em aberto
- **Q2 (validação final, única):** a comparação dos 169 (`sata × sata_mop`), no fim do plano, mede se
  MOP-guidance move cobertura — para validar/fechar as changes, **não** para gatear G-1/A-2.
- **Direção do mismatch (confirmação barata):** checar nos JSONs instrumentados do run de junho se eram gh60
  (`reachesTarget`) — crava que o jar stale (lendo `reachesMop`) foi a causa. Conclusão não muda; só pinta o fix.

---

## 8. Ordem de execução sugerida (resumo) — reformulada [rev 06-21 #2]

> O parser já está pronto no fonte (`138a161`) — não é etapa. A-1 descartada.

1. **B-1** (buildar o fonte na imagem, branch default) — **O FIX**: 98/169 passam a dar boost. **✅ ENTREGUE** (rvsec#71/gh71, `64740c8f`).
2. **A-3** (desacoplar component-triggering) — confound provado (548×45); comparação limpa. **✅ ENTREGUE** (bundle gh15).
3. **Demais changes (escopo analítico — NÃO gated por experimento):** **A-2/B3** (granularidade);
   **A-5** (telemetria por ação); **A-4** (podar `stateData`/OOM); **A-6** (clamp `[0,1]` + breaker no
   screenshot null). **✅ ENTREGUE** (todas no bundle gh15, arq. `2026-06-22`, commit `a8f2fa6`, #15 fechada).
   **G-1** = provável drop (decisão por análise + eventual spike no gator) — **pendente**.
4. **Comparação final / validação (M-1) — UMA vez, no fim do plano inteiro, executada pelo usuário.**
   `sata × sata_mop` nos **169 APKs**, só para validar o resultado e fechar as changes. **Nada depende
   dela; não é gate.** ("98" = subconjunto analítico boostável, não um teste separado. Confirmar
   `maxBoost>0` no trace para sanidade. ≥5 reps + braço B-1-isolado + exclusões pré-registradas.)
