# Verificação rigorosa de consistência — os 7 changes `rearch-0*` (Kernel de Run Descartável)

**Data**: 2026-08-02
**Alvo**: `openspec/changes/rearch-01-parity-oracle/` … `rearch-07-compact-static-artifact/` (28 artefatos, 6.203 linhas), contra `docs/analise_fable-selecao.md` (rev. 3), `docs/plans/20260802_rearchitecture_roadmap.md`, `openspec/specs/` (6.634 linhas) e o código em `src/main/java/`.
**Método**: verificação em 8 dimensões — fidelidade ao relatório; fidelidade ao código; coerência interna por change; consistência cross-change nos seams; disciplina de invariantes; aplicabilidade dos deltas; cobertura dos testes arquiteturais da Sec. 9; honestidade de escopo. Checagens mecânicas (aplicabilidade dos deltas, colisões de requisito, varredura de mecanismo deletado) por script; o resto por leitura direta.
**Status dos changes**: 7/7 a 4/4 artefatos, `openspec validate --strict` limpo em todos, 299 tarefas, **nenhuma implementação iniciada**. Todos os defeitos abaixo são corrigíveis nos artefatos, antes de qualquer código.

> **Nota de leitura (2026-08-02, mesma sessão)**: as §§3–9 registram o estado **encontrado**. Por determinação do dono, os achados foram corrigidos ainda nesta sessão — a §12 lista cada correção, onde ela caiu, e a re-verificação mecânica. As seções de achado foram mantidas como estão de propósito: são o registro do que existia e o motivo de cada edição. Apenas o S3 permanece em aberto, por depender de decisão do dono.

---

## 1. Veredito executivo

| Dimensão | Resultado |
|---|---|
| Estrutura OpenSpec (`validate --strict`) | **PASSA** — 7/7 limpos |
| Deriva do baseline `5dcf225` → HEAD `bd750d2` | **PASSA** — zero mudança em `src/main`; toda citação `file:line` vale em HEAD |
| Fidelidade ao relatório (D1–D6, R1–R9, Sec. 6/8/9/10) | **PASSA** — nenhuma decisão do dono deturpada |
| Fidelidade ao código (amostragem em V1/V9/V19/V22 + os 2 defeitos registrados) | **PASSA** — e V9 é mais forte que o relatado (§6.1) |
| Cobertura dos testes arquiteturais Sec. 9 | **PASSA em substância** — 4 itens sem citação, todos cobertos por requisito (§7, X4) |
| Coerência interna por change | **5 contradições** (§3) — todas corrigidas (§12) |
| Consistência cross-change nos seams | **BLOQUEADOR** — C1 destruía trabalho de dois estágios (§3.1) — corrigido (§12) |
| Disciplina de invariantes / aplicabilidade dos deltas | **5 lacunas** (§4) — todas corrigidas (§12) |

Os sete changes são, no plano formal e no diagnóstico, de qualidade alta: o relatório foi lido com precisão, as decisões do dono não foram reabertas, e as partes mais difíceis (a divisão do INV-ARCH-01 entre os estágios 2 e 4; o `event-sink`; o nível de captura do oráculo) estão corretas. O problema é concentrado e estrutural: **quando dois ou três estágios tocam o mesmo requisito, o estágio posterior foi escrito sobre o texto pré-mudança e apaga o anterior.** O roadmap alega ter feito essa reconciliação; ela foi feita em 2 das 6 colisões.

**Recomendação original**: C1 e C2 devem ser resolvidos antes da aprovação; C3–C5 e L1–L5 são corrigíveis nos artefatos sem rediscutir arquitetura.

**Estado após as correções da §12**: aplicabilidade dos deltas 0 achados; requisitos órfãos citando mecanismo deletado 0 (eram 11); as 6 colisões de requisito permanecem — elas são legítimas, vários estágios tocam mesmo o mesmo requisito — mas cada estágio posterior agora carrega o texto do anterior, verificado por marcadores. `validate --strict` segue 7/7. Resta uma única questão aberta para o dono: **S3** (o conversor temporário vs. o P3 do rv-android).

---

## 2. Método das checagens mecânicas

Três scripts sobre os artefatos (descartáveis, no scratchpad da sessão):

1. **Aplicabilidade dos deltas** — simula a aplicação dos 7 estágios em ordem sobre o conjunto de requisitos de `openspec/specs/`, verificando que todo `MODIFIED`/`REMOVED` incide sobre requisito existente naquele ponto. 1 achado (L3).
2. **Colisões de requisito** — requisitos tocados por mais de um estágio. 6 achados, analisados um a um em §3.1/§4.2.
3. **Varredura de mecanismo deletado** — requisitos de `openspec/specs/` que citam `apePureMode`, `ape_pure`, `rvForcedOff*`, `rvExemptReasons`, `stepTelemetryEnabled`, `saveGraph`/`readGraph`/`sataModel.obj`, `ape.xpath`/`ape.strings` ou `ThreadLocalRandom` **e** que nenhum change `rearch-0*` toca. 11 achados (L1).

---

## 3. Contradições

### 3.1 C1 — BLOQUEADOR: `execute_tool_specific_logic() Flow` é reescrito três vezes, destrutivamente

O requisito `aperv-tool :: execute_tool_specific_logic() Flow` recebe um bloco `MODIFIED` completo em **rearch-04**, **rearch-05** e **rearch-07**. Como `MODIFIED` substitui o requisito inteiro, aplicando na ordem dos estágios:

| Estágio | Texto | Efeito líquido |
|---|---|---|
| main | 8 passos | — |
| **04** | 11 passos: +`RVCommandTimeoutError`, +empty-trace, **+passo 10 gzip**, **+passo 11 conversor NDJSON→legado**, +3 cenários (conversão, falha do conversor, "No exit contract") | correto |
| **05** | 9 passos, renumerados: +`system-broadcast.json`, +compactação/enriquecimento, +`ape.preset`/overrides, +provenance LLM, +graça de 45 s | **apaga os passos 10–11 e os 3 cenários do 04** |
| **07** | 8 passos, com a numeração e o texto **originais** do main (`On RVToolTimeoutError`, "No health check step is required", cenários "JAR push fails"/"Timeout during exploration" verbatim) | **apaga também tudo do 05** |

O estado final sincronizado descreveria uma ferramenta **sem** gzip na coleta, **sem** o conversor temporário, **sem** geração `preset + overrides`, **sem** a graça de +45 s, **sem** push do `system-broadcast.json` e **sem** captura de provenance LLM — isto é, revertendo integralmente a metade Python do estágio 4 e o estágio 5 inteiro.

Evidência de que 07 foi escrito sobre o texto pré-mudança, não pós-05: `openspec/changes/rearch-07-compact-static-artifact/specs/aperv-tool/spec.md` passos 1–4, 7, 8 e a frase "No health check step is required (APE has no `--health-check` flag)" são idênticos a `openspec/specs/aperv-tool/spec.md:93-...`, incluindo a numeração que 05 já havia alterado.

O agravante é que o conversor apagado é justamente o que a proposta do 04 declara como o mecanismo que "keeps current rv-platform parsers working during migration" (`rearch-04-step-ndjson-telemetry/proposal.md`), e que o roadmap lista como entregável do estágio 4 (`docs/plans/20260802_rearchitecture_roadmap.md:31-33`).

**Opções**: (a) reescrever os blocos de 05 e 07 sobre o texto pós-estágio anterior — custo baixo, é edição de artefato; (b) fatiar o requisito em três (fluxo base / coleta / artefato MOP) para que cada estágio toque um requisito distinto — mais limpo a longo prazo, mais caro agora e mexe na main spec. A opção (a) resolve o bloqueio; a (b) é a que impede a recorrência.

### 3.2 C2 — a aposentadoria de `ape_pure`/`bfs` não chegou à delta spec

Decisão cruzada #1 do roadmap (`:73-75`) e `rearch-05-thin-python-arms/design.md:112,189,243,258` aposentam as duas variantes: "no structural-purity preset exists". As tasks acompanham (`tasks.md` 1.2, 3.2, 7.1, 9.1). A delta spec **não**:

- `rearch-05-thin-python-arms/specs/aperv-tool/spec.md:46` — "SHALL return exactly the 29 frozen variant names", listando `bfs` e `ape_pure`;
- `:50` e `:54` — atribuem `ape_pure` → "the structural-purity plan", **o preset que D2 diz não existir**;
- `:38` INV-APV-42 — "The 29 variant names are frozen";
- `:40` INV-APV-44 — diff vazio sobre os 29.

A proposta (`proposal.md`, "the 29 arm definitions") e o gate do roadmap (`:39`, "regeneration diff of the 29 arms") carregam o mesmo resíduo; `tasks.md:63` (8.3) pede asserir "29 frozen names present". O número correto pós-retirada é **27 migrados + 2 retiradas documentadas** — que é exatamente como a task 9.1 já o escreve.

A delta spec é o artefato que sobrevive no `openspec/specs/`. Aprovada como está, a main spec passaria a exigir 29 arms incluindo dois que a implementação deleta, ancorados num preset inexistente.

### 3.3 C3 — a whitelist de estratégias Python: o 02 delega ao 05, o 05 recusa

`rearch-02-runspec/design.md:168` e `:275` dizem, duas vezes, que o estágio 5 remove `bfs`/`dfs`. O estágio 5 explicitamente não o faz:

- `rearch-05-thin-python-arms/specs/aperv-tool/spec.md:94` — "SHALL validate that `config["strategy"]` is one of `["sata", "random", "bfs", "dfs"]`";
- `tasks.md:19` (2.2) — "keep strategy validation as-is";
- `design.md:79` e `specs/aperv-tool/spec.md:7` — validação de estratégia "intentionally untouched".

Consequência pós-estágio 2: uma arm com `strategy: "dfs"` passa na validação Python e **aborta no device**, que é precisamente a degradação silenciosa que o estágio 2 existe para matar. Deletar a variante `bfs` (C2) não resolve — `dfs` nunca teve variante; o defeito está na whitelist.

### 3.4 C4 — rearch-06 contradiz o próprio design sobre ordenação

`rearch-06-memory-surgical/proposal.md:30`: "**independent of stages 4–5**, ordered after the pipeline work to avoid double-churn".

Contra:
- `design.md:17-19` — o estágio 4 deleta `Model.saveActionHistory`, "the **only** consumer that re-resolves every deep record through the rich `GUITreeAction`"; "If either stage has not landed when this change is applied, group 3 (V11) is blocked";
- `tasks.md:9` (1.1) — "**Ordering precondition (gate for group 3)**: verify `rearch-02-runspec` and `rearch-04-step-ndjson-telemetry` are applied … If either survives, group 3 (V11) is **blocked**";
- decisão cruzada #6 do roadmap (`:88-90`) — "hard-blocked on stages 2+4".

O design e as tasks estão certos; a frase da proposta está errada e é a que um leitor consulta primeiro. Correto seria: "V12 e V24 independentes dos estágios 4–5; V11 (grupo 3) hard-blocked nos estágios 2+4".

### 3.5 C5 — `action-selection` fica autocontraditória depois do estágio 4

`openspec/specs/action-selection/spec.md` tem, além do requisito `:48` **Per-action decision-source telemetry** (reescrito por rearch-03 e rearch-04; pós-04, gravação sempre-on, sem gate, `StepRecord`), duas peças que nenhum change tocava:

- `:280` **Per-step counterfactual attribution** — requisito inteiro condicionado a "When `stepTelemetryEnabled` is `true`" e emitindo `cf_action`/`cf_changed` na linha `[APE-STEP]`;
- `:316` em diante, uma seção **`## Invariants` de topo** (não dentro de requisito algum — por isso nenhuma operação de requisito a alcança) contendo:
  - `:321` INV-SEL-04 — "When `Config.stepTelemetryEnabled` is `true` (default), exactly one `[APE-STEP]` line SHALL be emitted … When the flag is `false` (the `ape_pure` arm), zero `[APE-STEP]` lines are emitted";
  - `:322` INV-SEL-05 — idem, gateado por `stepTelemetryEnabled`;
  - `:318` INV-SEL-01 — "the `ape_pure` arm".

O estágio 4 deleta `stepTelemetryEnabled` (a própria delta do 04 exige que a chave passe a abortar como desconhecida: `rearch-04/specs/scoring-pipeline/spec.md:88-91`) e o `ape_pure` é retirado por C2. A mesma capability afirmaria as duas coisas. A correção do bloco `## Invariants` não é um `MODIFIED` de requisito: a convenção já usada no repo (p. ex. `rearch-02/specs/exploration/spec.md:3-6`) é carregar uma seção `## Invariants` na própria delta registrando a disposição.

---

## 4. Lacunas

### 4.1 L1 — a varredura de subtração parou nos mecanismos, não alcançou as specs

Onze requisitos, em sete capabilities, citam mecanismo que os changes deletam, e **nenhum change `rearch-0*` os toca**:

| Capability :: Requisito | Linhas | Mecanismo morto citado |
|---|---|---|
| `ui-coverage` :: Coverage Dump Emitted Before Model Serialization | 319, 321, 325, 343 | `saveGraph` (02) e `saveActionHistory` (04) |
| `exploration` :: Exploration Loop Termination | 101 | `sataModel.obj`, `sataGraph.vis.js` |
| `exploration` :: Seeded Agent Decision Reproducibility | 782 | `RandomHelper.seed` em `MonkeySourceApe` / `ThreadLocalRandom` |
| `exploration` :: OptionsMenu Systematic Exploration | 184 | arm `ape_pure` |
| `scoring-pipeline` :: MopFrontierPass — Frontier Boost… | 178 | registry `apePureMode` (INV-ARCH-06 dissolvido) |
| `action-selection` :: Per-step counterfactual attribution | 282, 318, 321, 322 | `ape_pure`, `stepTelemetryEnabled` (→ C5) |
| `action-selection` :: State.greedyPickLeastVisited() — Priority Tiebreaker | 20 | arm `ape_pure` |
| `form-completion` :: Form-completion boost pass placement and provenance | 142, 146, 158 | `ape_pure`, `stepTelemetryEnabled` |
| `activity-budget` :: Budget Check in SATA Action Selection | 78 | arm `ape_pure` |
| `ui-tree` :: ViewPager Scroll Direction | 86 | arm `ape_pure` |
| `ui-tree` :: WebView Pruning Correctness | 194, 200 | arm `ape_pure` |

Dois merecem destaque:

**`ui-coverage` :: Coverage Dump Emitted Before Model Serialization.** A proposta do 02 afirma "The teardown coverage-dump ordering (INV-COV-10) is preserved". Mas o requisito que codifica INV-COV-10 está *definido sobre a fronteira do `saveGraph`* — `openspec/specs/ui-coverage/spec.md:319` ("SHALL be emitted **before the model serialization step (`saveGraph`)**") e `:321` ("The boundary is the model serialization, not chain position … `llmSummary → superTearDown → saveGraph → saveActionHistory → …`"). O estágio 2 deleta `saveGraph`; o estágio 4 deleta `saveActionHistory`. O requisito é invalidado por dois estágios e tocado por nenhum. A propriedade que ele protege (recuperar 333 dos 338 dumps perdidos) é real e deve sobreviver — só precisa ser reancorada numa fronteira que ainda exista.

**`form-completion`** não é tocada por change algum, mas `:146` condiciona comportamento ao "the `ape_pure` arm" e `:158` declara a emissão gateada por `stepTelemetryEnabled`.

### 4.2 L2 — dois estágios apagam o substituto que o 02 acabara de registrar

A restrição permanente do roadmap (`:56-58`) exige que invariante dissolvido seja removido pelo change que o dissolve **"with the substitute recorded"**. Em duas colisões, o registro é apagado pelo estágio seguinte:

| Requisito | 02 registra | Estágio seguinte |
|---|---|---|
| `component-triggering` :: Cadence-Based MOP Activity Launch | troca a cláusula do registry `rvExemptReasons`/INV-ARCH-06 por: chaves declaradas como sub-parâmetros da feature `ACTIVITY_TRIGGER` (requer `MOP`), neutras via INV-RUN-05 | **03** reescreve como estágio `MopLauncher`; zero ocorrências de `ACTIVITY_TRIGGER` ou `INV-RUN-05`; a cláusula de deleção de `Config.triggerMopFirst` também some |
| `mop-guidance` :: MopData — Activity-Level MOP Source (A′) | ancora a chave na feature `MOP_ACTIVITY_SOURCE` (depende de `MOP`) + cenário "explicit activation without MOP data aborts" | **07** reescreve para o artefato derivado; zero ocorrências de `MOP_ACTIVITY_SOURCE`/`INV-RUN-05`; o cenário de abort desaparece |

Não sobra referência pendurada ao mecanismo morto (o texto antigo também é substituído), mas o substituto exigido pelo roadmap deixa de constar. É a mesma causa-raiz de C1, com dano menor.

*Contraste, para calibrar*: a colisão `scoring-pipeline :: Parity Configuration Flags` (02→04) está **correta** — `rearch-04/specs/scoring-pipeline/spec.md:68` ancora cada flag no `Feature` model "per `rearch-02-runspec`", `:78` atribui a remoção do `apePureMode` ao estágio 2, INV-ARCH-07 desce de seis para cinco gates, e a seção `## Notes` (`:95-97`) implementa a decisão cruzada #5 explicitamente. Igualmente correta a colisão `action-selection :: Per-action decision-source telemetry` (03→04). O padrão de defeito não é universal — é exatamente onde a reconciliação não foi feita.

### 4.3 L3 — `REMOVED` cross-repo que não incide sobre nada

`rearch-05-thin-python-arms/specs/aperv-tool/spec.md:199` declara `## REMOVED Requirements / ### Requirement: Arm-Defining Flag Completeness (FR20)`. Esse requisito **não existe** em `openspec/specs/aperv-tool/spec.md` (7 requisitos, nenhum com esse nome). Ele mora no outro repo: `rv-android/openspec/specs/aperv/spec.md:710`.

A intenção está documentada (`spec.md:9` e `tasks.md:65`), mas `openspec validate --strict` passa e o sync no repo `ape` não terá alvo — falha silenciosa, exatamente a classe que a dimensão 6 procura.

### 4.4 L4 — o trabalho cross-repo não tem instrumento OpenSpec do lado rv-android

Os estágios 5 e 7 editam `rv-android` (`modules/aperv-tool/`), e o estágio 5 precisa alterar `rv-android/openspec/specs/aperv/spec.md`. Não existe change OpenSpec correspondente em `rv-android` (`openspec list` mostra 8 changes ativos; nenhum é `rearch-*`). A task 8.5 do 05 manda "Update … rv-android's `openspec/specs/aperv/spec.md` counterpart (in that repo's workflow)" — editar main spec diretamente é o que o `rv-android/CLAUDE.md` proíbe ("MANDATORY: Use OpenSpec Skills, Never Write Artifacts Manually"). O estágio 7 não tem nenhuma task para a spec `aperv` do rv-android, embora mude o contrato de wire que ela descreve.

### 4.5 L5 — o corpus do rearch-07 não é reproduzível a partir da citação

`rearch-07-compact-static-artifact/design.md:5` mede sobre "the 134 real static-analysis JSONs in `data/instrumented_apks/`" e conclui `reachability[]` = 57,7 % dos bytes agregados. `:138` e `:237` definem o gate de equivalência (R9) como teste JVM "over `data/instrumented_apks/` via `-Dmop.corpusDir`".

Verificado: `data/` no repo `ape` contém **apenas** `system-broadcast.json`; `data/instrumented_apks/` não existe (`ls data/`). O corpus mais próximo é `rvsec-dataset/static_analysis/` com **345** `.apk.json`.

A medição pode estar correta — mas não é reproduzível da citação, e o gate que o roadmap lista como condição do estágio 7 aponta para um caminho inexistente. Fecha-se com uma linha: o comando que produziu os 57,7 % e o caminho real do corpus.

---

## 5. Smells

- **S1 — política de emulador inconsistente entre changes irmãs.** `rearch-02/tasks.md:79` (8.1) e `rearch-06/tasks.md:39,41` (5.x) prescrevem `scripts/run_emulator.sh` + adb, citando o "standalone path" de `CLAUDE.md` — que de fato existe em `ape/CLAUDE.md:32-37`. Já `rearch-03/tasks.md:77` (8.4) e `rearch-04/tasks.md:83` (9.1) dizem "via rv-platform — **never manual emulator management**". As duas políticas convivem no mesmo conjunto de changes. O `rv-android/CLAUDE.md` é absoluto no ponto; vale decidir uma e escrever igual nos sete.
- **S2 — `rearch-01` subenumera seus dependentes.** Sua proposta cita 02 e 03 como os que usam o oráculo como gate; `rearch-06/proposal.md:30` declara depender dele, e `rearch-06/specs/model/spec.md:88` funda a neutralidade das correções de memória nos goldens do 01.
- **S3 — o conversor legado tensiona P3 do rv-android.** O estágio 4 instala um conversor NDJSON→legado em `rv-android` para manter os parsers atuais. O relatório autoriza (Sec. 6.5, "conversor temporário durante a migração"), e o próprio delta declara que é deletado depois. Mas P3 do `rv-android/CLAUDE.md` proíbe adapters/shims. Vale registrar a exceção explicitamente no artefato, com a condição de morte, em vez de deixá-la implícita.
- **S4 — o defeito do `type_text` é preservado sem ser declarado.** `rearch-03/specs/llm-routing/spec.md:167` diz "**type_text handling**: unchanged", e `:163` descreve a ordem `… → fixTextEdit conversion → back/long-click preference`. Confirmado no código em HEAD: `LlmRouter.java:689` restringe `ActionType` só para `"click"`; `:807` faz `fixTextEdit` retornar imediatamente quando `actionType` não é `click`/`long_click`. Logo o fatiamento **preserva** o defeito (28 de 1.233 respostas). Isso é legítimo — o estágio 3 é explicitamente comportamento-neutro — mas "unchanged" deveria dizer que o que se preserva inclui um defeito conhecido, senão o `CoordinateMapper` nasce com um bug não documentado.

---

## 6. Achados de código (subproduto da dimensão 2)

### 6.1 V9 é mais forte do que o relatório registra: `bfs` nunca foi um agente

`ApeAgent.createAgent` (`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:68-96`) reconhece exatamente três valores — `sata`, `random`, `replay` — e `null` → `SataAgent`; qualquer outro cai em `return new SataAgent(ape, graph)` sem log.

Portanto a arm `bfs` do `tool.py` (`modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py:494`, `{**_BASELINE_ARM_FLAGS, "strategy": "bfs", "throttle_ms": 200}`) sempre executou `SataAgent` — configuração idêntica à arm `sata` (`:493`), que difere apenas na string de estratégia que o jar ignora. `bfs` e `sata` não são dois arms; são o mesmo arm com dois nomes.

Isso **reforça** a decisão de aposentadoria (C2/decisão cruzada #1) e a torna mais barata do que o design supõe: não se está descartando um braço experimental, está-se removendo uma duplicata. Item para o dono: se algum resultado histórico comparou `bfs` contra `sata`, comparou a mesma configuração — não verifiquei se `bfs` chegou a rodar em alguma campanha, e essa checagem não estava no escopo desta sessão.

### 6.2 O resíduo A8 é subsumido por construção — confirmado

O estágio 4 resolve o resíduo A8 (74 de 576.739 linhas `[APE-STEP]` quebradas por `\n` no `text=` do Name), e resolve pela direção certa — o formato absorve o dado, em vez do dado ser achatado para caber no formato:

- `rearch-04/specs/event-sink/spec.md` INV-SNK-01 — nenhum newline cru dentro de um registro; o terminador é o único `\n` escrito pelo sink;
- INV-SNK-02 — escapa `"`, `\`, todo U+0000–U+001F (incl. NUL), U+2028/U+2029; round-trip byte-idêntico;
- `tasks.md` 1.1/1.2 — implementação (`JsonBuf`) e testes permanentes de round-trip.

O `Data Contracts / Input` do `event-sink` inclui "typed text" entre o material dos sub-eventos LLM, então o campo onde o resíduo aparece continua existindo e passa pelo serializer. **O sítio de código nunca localizado deixa de importar**: a garantia é no sink, não na origem. Achado limpo — nada a corrigir.

---

## 7. Cobertura dos testes arquiteturais (Sec. 9)

Citação explícita `Sec. 9.x` por change: 01 → 9.4, 9.9 · 02 → 9.6 · 03 → 9.4, 9.5, 9.9 · 04 → 9.7, 9.8, 9.11, 9.12 · 05, 06, 07 → nenhuma.

Os quatro itens sem citação **estão cobertos em substância**, e por isso isto é cosmético e não lacuna:

- 9.1 (isolamento A→B) e 9.2 (proibição de read-back) → `rearch-02/specs/run-spec/spec.md:46` INV-RUN-07 ("No artifact produced by a previous run SHALL be read by the explorer (R3) … no resume, no read-back");
- 9.3 (config total) → `rearch-02/specs/run-spec/spec.md:128` "Total Fail-Fast Validation";
- 9.10 (semântica de memória) → `rearch-06/specs/model/spec.md:86` "Retention Fixes Are Decision-Neutral" e `:107` INV-MODEL-20, ambos fundados nos goldens do 01.

---

## 8. Classificação dos gates: host/JVM vs. device (entregável para o gh92)

`gh92-emulator-boot-gating` (rv-android, 0/70) conserta um no-op silencioso do portão de boot **no caminho do rv-platform**. Um gate só é afetado se roda por ali. A decisão cruzada #4 do roadmap se confirma: `rearch-01/design.md:139-145` declara os goldens "harness-relative … **No device-trace equivalence**", executados em JVM pura sobre `selectNewActionNonnull()`. Isso desacopla a maior parte da migração de qualquer device.

| Estágio | Gate declarado no roadmap | Natureza | Bloqueado pelo gh92? |
|---|---|---|---|
| 01 | é o gate dos demais | **host/JVM** (`mvn test`) | **não** |
| 02 | oráculo verde por preset | **host/JVM** | **não** |
| 03 | oráculo + golden de preempção permanente | **host/JVM** | **não** |
| 04 | neutralidade sink on/off; relatório de calibração re-gerável; round-trip/uma-linha | **host/JVM** (Sec. 9.8/9.11/9.12) | **não** |
| 05 | diff de regeneração das configs efetivas | **host/Python** | **não** |
| 06 | paridade de sequência de ações | **host/JVM** (goldens do 01) | **não** |
| 07 | conjuntos de métrica preservados por derivação (R9); push cross-repo | **host/JVM** (corpus equivalence) + push | **não** (o gate; ver abaixo) |

**Nenhum dos sete gates de aceitação é device-level.** O que é device-level são smokes de validação, não gates:

| Task | Caminho | Bloqueado pelo gh92? |
|---|---|---|
| `rearch-03` t8.4 — smoke on-device | via rv-platform | **sim** |
| `rearch-04` t9.1 — trace de amostra no novo formato | via rv-platform ("never manual emulator management") | **sim** |
| `rearch-05` t1.1 e t9.2 — smoke por família de preset na AVD RVSec | não especifica o caminho | **sim, se via rv-platform** — vale explicitar na task |
| `rearch-07` t8.1 e t8.2 — smoke e2e + deltas de tamanho/tempo de load | "End-to-end device smoke … RVSec AVD" | **sim, se via rv-platform** |
| `rearch-02` t8.1 · `rearch-06` t5.1 | `scripts/run_emulator.sh` (standalone, fora do rv-platform) | **não** — mas é o caminho que S1 questiona |

**Leitura para o dono**: o gh92 **não** está no caminho crítico da rearquitetura. Os estágios 1–4 podem ser implementados e aceitos inteiramente sem device. O gh92 destrava a *evidência de campo* (os smokes), não os gates — e destrava a corrida experimental, que é outro assunto.

---

## 9. Cosméticos

- **X1 — `Related state` do roadmap está estale.** `docs/plans/20260802_rearchitecture_roadmap.md:67-69` diz que `telemetry-proof-llm-efficacy` "(50/51) remains open pending on-device smoke 17.4". Está **arquivada**: `openspec/changes/archive/2026-08-02-telemetry-proof-llm-efficacy/`, **51/51** tarefas, 0 abertas (`grep -c '^- \[ \]' → 0`). A precondição de `:95-96` ("must archive before rearch-03/04") **já está satisfeita**. No mesmo bloco, `:96-97` lista "`gh88`/`gh90` … must merge before rearch-05": `gh90-e3-decisive-run-setup` está arquivada em `rv-android/openspec/changes/archive/2026-08-02-gh90-e3-decisive-run-setup/`; **só `gh88-cal-llm-control` (47/58, parada desde 2026-07-24) é bloqueio real**. A task 1.3 do 05 nomeia as duas como "open rv-android changes" — mesma correção.
- **X2 — prefixo de skill errado nas tasks que rodam no outro repo.** Os skills `sdd-*` existem e são os corretos **neste** repo (`.claude/skills/sdd-test-run`, `sdd-doc-code`, `sdd-qa-lint-fix`, `sdd-verify`, `sdd-code-reviewer`, `sdd-docs-sync`) — as tasks do `rearch-07` que operam sobre o `ape` (3.6, 5.5, 5.6, 8.4, 8.5, 8.6) estão certas. O erro está nas tasks que operam sobre `rv-android`, onde os skills se chamam `rv-*`: `rearch-05` 1.8 e 8.6 (`/sdd-test-run modules/aperv-tool`), `rearch-07` 2.8 (`/sdd-doc-code modules/aperv-tool/…`), 6.4 (`/sdd-test-run aperv-tool`) e a primeira metade de 8.3 (`/sdd-qa-lint-fix aperv-tool`). (Vale lembrar a decisão permanente de 2026-07-31: não rodar skills `rv-*` sem o dono pedir.)
- **X3 — contagem de arms** em `rearch-05/tasks.md:63` (8.3): "29 frozen names present" deveria ser 27, coerente com a task 9.1.
- **X4 — citações Sec. 9 ausentes** em 05/06/07 e parciais no 02 (§7). Substância coberta; só falta a referência.
- **X5 — estado do repo no handoff.** O prompt da sessão registra "4 commits unpushed" no `ape`; `git log origin/master..HEAD` mostra **3** (`bd750d2`, `e81b382`, `99dded5`) — `ea1e89e` e `25f69a4` já estão em `origin/master`.

---

## 10. O que passou, e por quê importa

Registrado para que a aprovação seja informada dos dois lados:

1. **Deriva do baseline: nenhuma.** `git diff --name-only 5dcf225..HEAD` fora de `openspec/` retorna apenas `.classpath`, `.project` e o roadmap. Zero mudança em `src/main/java`. Toda citação `file:line` do relatório e dos designs vale em HEAD — o lead de deriva se fecha negativo.
2. **Decisões do dono D1–D6 e regras R1–R9**: nenhuma reaberta, nenhuma deturpada. Amostragem: D1 (nível 0) aparece como INV-APV-43 em 05 e como requisito em `run-spec`; D5 (sem contrato de saída) como INV-SNK-09 e como cenário "No exit contract" em 04; D6 (leitores `/sdcard`) como requisitos em `ui-tree`/`heuristic-input`/`model` do 02.
3. **A decisão cruzada #5 (split do INV-ARCH-01) está correta** — a mais delicada das sete, e implementada sem defeito (§4.2, contraste).
4. **Decisão cruzada #2 (stamp gh14)**: `openspec/changes/archive/2026-06-21-gh14-build-provenance-stamp/` existe; `rearch-02/design.md:195-197` reusa o wiring e **declara a divergência** em relação ao motivo do descarte original em vez de escondê-la.
5. **Decisão cruzada #4 (nível do oráculo)**: fiel, e `rearch-01/design.md:139-145` lista as próprias limitações (sem equivalência com trace de device, sem cobertura dos ramos SATA que alcançam `AndroidDevice`) — honestidade de escopo, dimensão 8.
6. **`event-sink`** é o artefato mais forte do conjunto: 12 invariantes, contratos de dados completos, e a garantia de escaping que resolve o resíduo A8 por construção (§6.2).

---

## 11. Ordem sugerida de correção

Tudo abaixo é edição de artefato, via `openspec-update-change` — nada exige rediscussão de arquitetura, e nada bloqueia a implementação do `rearch-01`, que é independente de todos os achados.

1. **C1** — reescrever os blocos `execute_tool_specific_logic()` de 05 e 07 sobre o texto pós-estágio anterior (ou fatiar o requisito). Bloqueador.
2. **C2** — propagar a aposentadoria `ape_pure`/`bfs` para a delta spec do 05 (`Tool Variants`, INV-APV-42, INV-APV-44), a proposta, a task 8.3 e o gate do roadmap: 27 + 2 retiradas.
3. **C3** — decidir onde a whitelist encolhe (`{sata, random, replay}`) e escrever no estágio que a executa; hoje o 02 delega e o 05 recusa.
4. **C5 + L1** — varrer `openspec/specs/` pelos 11 requisitos da tabela §4.1 e alocá-los aos estágios que os invalidam; reancorar INV-COV-10 numa fronteira que sobreviva.
5. **L2** — reintroduzir o substituto (`ACTIVITY_TRIGGER`/INV-RUN-05; `MOP_ACTIVITY_SOURCE`/INV-RUN-05 + cenário de abort) nos textos de 03 e 07.
6. **C4, L3, L4, L5** e os cosméticos — correções pontuais.

---

## 12. Correções aplicadas (2026-08-02, mesma sessão)

O dono determinou a correção dos achados. Tudo abaixo foi aplicado **via `openspec-update-change`**, apenas em artefatos de planejamento; nenhum código foi tocado e nenhuma implementação foi iniciada. `openspec validate --strict` segue limpo em 7/7 depois de todas as edições.

| Achado | Correção | Onde |
|---|---|---|
| **C1** | Os blocos `execute_tool_specific_logic()` de 05 e 07 foram reescritos sobre o texto do estágio anterior. O 05 agora tem 12 passos e preserva o gzip (11), o conversor legado (12), o parágrafo D5 e os três cenários do 04; o 07 é escrito sobre o pós-05 e preserva gzip, conversor, `ape.preset`+overrides, graça de 45 s, `system-broadcast.json` e provenance LLM, mudando só os passos 5–6 (artefato derivado) | `rearch-05/specs/aperv-tool`, `rearch-07/specs/aperv-tool` |
| **C2** | 29 → **27 sobreviventes + 2 retiradas documentadas**, em `Tool Variants`, INV-APV-42, INV-APV-44, o cenário do kill-switch, a proposta, o cabeçalho das tasks, a task 8.3 e o gate do roadmap. O preset "structural-purity" inexistente foi removido da atribuição de presets | `rearch-05` (spec/proposal/tasks), roadmap |
| **C3** | Whitelist reduzida a `["sata", "random"]` no requisito `configure()`, com cenário de rejeição de `bfs`/`dfs` antes de qualquer interação com device; task 2.2 passa a exigir a redução de `APERV_AVAILABLE_STRATEGIES`; design e Purpose alinhados | `rearch-05` (spec/design/tasks) |
| **C4** | A proposta do 06 passa a declarar o bloqueio duro real: V12 e V24 independentes; **V11 hard-blocked nos estágios 2 e 4**, coerente com o design e a task 1.1 | `rearch-06/proposal.md` |
| **C5** | `Per-step counterfactual attribution` reescrito em termos de `StepRecord`/`dec.cf` no 04; nova seção `## Invariants` no delta do 04 dispondo INV-SEL-04/05/06; INV-SEL-01 disposto no 03 | `rearch-04/specs/action-selection`, `rearch-03/specs/action-selection` |
| **L1** | Os **11** requisitos órfãos foram alocados ao estágio que os invalida. Oito por deltas já existentes; três exigiram delta novo: `ui-coverage` e `activity-budget` no 02, `form-completion` no 04. O `Coverage Dump Emitted Before Model Serialization` foi **renomeado** (`RENAMED`) para `Coverage Dump Emitted First Among Teardown Writers` e reancorado em "primeiro entre os escritores", preservando integralmente a evidência medida (338/800, 333 recuperados), o argumento do shutdown hook e os limites honestos | `rearch-02` (exploration ×3, scoring-pipeline, ui-tree ×2, ui-coverage, activity-budget), `rearch-03` (action-selection), `rearch-04` (form-completion) |
| **L2** | Substitutos reinseridos: `ACTIVITY_TRIGGER`+INV-RUN-05 e a deleção de `triggerMopFirst` no 03; `MOP_ACTIVITY_SOURCE`+INV-RUN-05 e o cenário de abort por dependência ausente no 07 | `rearch-03/specs/component-triggering`, `rearch-07/specs/mop-guidance` |
| **L3** | O `REMOVED` de FR20 — que não incidia sobre nada neste repo — virou nota de disposição, seguindo o padrão que o 04 já usa para o `apePureMode` | `rearch-05/specs/aperv-tool` |
| **L4** | Tasks de instrumento cross-repo adicionadas (8.5a no 05, 6.5 no 07): a spec `aperv` do rv-android passa a ser alterada por change própria naquele repo, nunca à mão | `rearch-05/tasks.md`, `rearch-07/tasks.md` |
| **L5** | Seção "Corpus provenance" no design do 07 registrando que `data/instrumented_apks/` não existe aqui, e nova task 1.4 exigindo fixar caminho, contagem e comando antes de rodar o gate de equivalência | `rearch-07/design.md`, `tasks.md` |
| **S1** | Política de emulador unificada: as tasks de device de 02 e 06 passam a ser explicitamente **executadas pelo dono**, com preferência pela rota rv-platform onde a checagem couber | `rearch-02/tasks.md`, `rearch-06/tasks.md` |
| **S2** | A proposta do 01 passa a enumerar os três dependentes (02, 03 e **06**) | `rearch-01/proposal.md` |
| **S4** | O defeito preservado do `type_text` (28/1.233; `LlmRouter.java:689`/`:807`) passa a ser declarado no requisito, com o escopo do fix explicitamente fora deste estágio | `rearch-03/specs/llm-routing` |
| **X1, X2, X3** | Roadmap corrigido (efficacy arquivada 51/51; gh90 arquivada; só gh88 bloqueia; split host/device do gh92 registrado); prefixos `/sdd-*` → `/rv-*` nas tasks que rodam no rv-android; task 1.3 do 05 reescrita | roadmap, `rearch-05`, `rearch-07` |

**S3 não foi corrigido** (tensão do conversor temporário com o P3 do rv-android): é decisão do dono se o conversor entra como exceção declarada a P3 ou se os parsers migram direto para NDJSON. Está registrado, não resolvido.

### Verificação pós-correção

| Checagem | Antes | Depois |
|---|---|---|
| `openspec validate --strict` | 7/7 limpo | 7/7 limpo |
| Aplicabilidade dos deltas (script §2.1) | 1 achado (L3) | **0** |
| Requisitos citando mecanismo deletado sem dono (script §2.3) | 11 | **0** |
| Colisões de requisito (script §2.2) | 6, das quais 4 destrutivas | 6, **todas carregando o estágio anterior** (verificado por marcadores: `ndjson.gz`, `legacy conversion`, `ape.preset`, `ACTIVITY_TRIGGER`, `INV-RUN-05`, `MOP_ACTIVITY_SOURCE`, `flushPendingStep`) |

O verificador de aplicabilidade foi corrigido durante esta rodada para tratar `## RENAMED Requirements` (`- FROM:`/`- TO:`), que ele antes ignorava — sem isso o `RENAMED` do `ui-coverage` aparecia como falso positivo.

---

*Documento de verificação. As correções da §12 tocaram apenas artefatos de planejamento, via `openspec-update-change`; nenhum código foi alterado e nenhuma implementação foi iniciada. Toda afirmação factual acima cita `file:line`; as afirmações quantitativas indicam o comando ou o script que as produziu (§2).*
