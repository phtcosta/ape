# Análise de Seleção — Fable (Claude Fable 5)

**Fase:** Brainstorming / seleção da arquitetura de rearquitetura do APE-RV
**Data:** 2026-08-02
**Commit analisado:** `5dcf225` (HEAD, branch `master`) — mesmo commit do dossiê original
**Inputs:** prompt original (`docs/20260801_prompt_rearquitetura_aperv.md`), 8 relatórios brutos (`analise_deepseek-v4.md`, `analise_gemini-3.6-flash.md`, `analise_glm-5-2.md`, `analise_gpt-5.md`, `analise_kimi-k3.md`, `analise_ling-3-0-flash-free.md`, `analise_mimo-v2-5-free.md`, `analise_opencode_laguna_s_2_1_free.md`) e 4 meta-seleções (`analise_deepseek-selecao.md`, `analise_gemini-selecao.md`, `analise_gpt-5-selecao.md`, `analise_mimo-selecao.md`)
**Método:** 7 subagentes em paralelo — 5 de verificação de claims diretamente no código (fluxo de decisão; configuração/factory/scoring; memória/persistência; telemetria/resiliência; camada Python em `rv-android`) e 2 de sumarização fiel dos 8 relatórios brutos — mais verificações pontuais próprias (callers de `clone()`, `ThreadLocalRandom`, retenção em `ModelAction`) e análise estruturada da decisão. **Nenhum claim foi usado como fundamento sem verificação em `5dcf225`.** Nenhum código foi alterado.
**Rev. 2 (2026-08-02):** incorporadas duas decisões do dono após discussão — (i) o eco de configuração fica no **nível 0** (proveniência write-only no trace, zero mudança no Python); (ii) o `EventSink` passa a emitir **um registro NDJSON agrupado por step** (Sec. 6.5), com serializer de escaping real e reduções de volume.
**Rev. 3 (2026-08-02):** todas as questões abertas foram decididas pelo dono (Sec. 12, D1–D6): descope total do `ape` stock; heartbeat no logcat desde o início (flag, default on); **rejeitado** o “contrato de saída” validado pelo Python (detecção de truncamento continua pós-hoc por timestamps); remoção dos leitores de `/sdcard/ape.*`; eco em nível 0 definitivo. Não restam questões abertas.

---

## 1. Sumário executivo

**Arquitetura selecionada: “Kernel de Run Descartável” — `RunSpec` + `RunContext` + `DecisionPipeline`, com presets residentes no jar, eco de configuração efetiva write-only (nível 0), telemetria NDJSON agrupada por step e subtração agressiva da persistência legada.**

É a síntese das partes verificadas e convergentes das 8 análises e das 4 meta-seleções, com as divergências entre elas resolvidas assim:

1. **A espinha dorsal é o consenso das 4 meta-seleções** (todas recomendam variantes da mesma coisa): plano imutável validado fail-fast (`RunSpec`), estado mutável inteiramente por run (`RunContext`), e a escada de precedência do `selectNewActionNonnull` expressa como pipeline nomeado de estágios com preempção dura (`DecisionPipeline`).
2. **O split-brain Python↔Java é resolvido por “preset no jar + override explícito + eco write-only”**, não por manifest compartilhado entre repositórios. O jar passa a ser a única autoridade sobre *o que cada modo significa*; o Python continua a única autoridade sobre *a matriz experimental*. **Não há canal de comunicação novo**: o “eco” é apenas a primeira linha do `.trace` que o Python já captura (`RUN_START` com plano efetivo, digest e versão do jar). **Nível 0 por decisão do dono (2026-08-02): nenhuma validação automática, nenhuma mudança no Python** — a linha é registro de proveniência. A validação eco-vs-intenção (“nível 1”, ~20 linhas no `tool.py` ao fechar a task) fica documentada como opção futura.
3. **Nada de linhagem dupla / `StockApeAgent` / build duplo.** O dono declarou que o APE default **não é usado e não será usado**; fidelidade byte-a-byte ao upstream sai do escopo da seleção. A propriedade que o `apePureMode` comprava (feature RV não vaza silenciosamente para um arm de controle) é preservada de forma **estrutural**: uma feature só existe na run se o plano a habilita, e o plano efetivo é ecoado.
4. **Runs limpas como lei (mandato do dono):** nenhum estado operacional sobrevive entre sessões. O protocolo `saveGraph`/`readGraph`/`--ape-model` — verificado como quebrado por construção (grava `Model`, lê `Graph`, engole o `ClassCastException` e devolve grafo vazio) — é **removido**, não consertado. Resiliência = retry integral no supervisor Python, nunca checkpoint/resume. Identificar runs truncadas permanece tarefa **pós-hoc da análise, por timestamps do trace/logcat, como hoje** (decisão do dono, 2026-08-02 — a ideia de um “contrato de saída” validado pelo Python foi descartada: os dados já existem).
5. **Telemetria é instrumento, não feature:** **um registro NDJSON por step** — os eventos do step (decisão, chamadas LLM, outcome) agrupados num único objeto, fechado quando o outcome resolve — write-only, igual para todos os arms (dissolve deliberadamente a cegueira do baseline, INV-ARCH-01), com serializer JSON de escaping real (a classe de erro do newline-na-origem morre por construção) e custo por step controlado — porque o custo por step é uma variável experimental medida (0,037–0,052 pp de `cov_mop` por step perdido). Agrupamento + omissão de defaults + tabela de IDs para strings repetidas atacam diretamente o crescimento do trace (~3,5 GB/880 tasks hoje).
6. **Memória:** correção imediata apenas dos defeitos inequívocos (cache por nó nunca limpo; retentores diagnósticos que guardam árvores inteiras onde IDs bastam); qualquer bound sobre estruturas semânticas (naming/refinement) fica **condicionado a profiling**, porque eviction ali muda comportamento científico.

Rejeitados explicitamente (Sec. 7): event sourcing/log-como-verdade, checkpoint/resume/epochs, política externa/IPC/segundo processo, LLM assíncrono como fundação, EventBus, amostragem ponderada no lugar da preempção dura, manifest compartilhado cross-repo, plugin frameworks/classloaders, e bounds LRU especulativos.

---

## 2. Regras invioláveis

Contrato que qualquer candidato tinha de cumprir — derivado do mandato do dono nesta fase e das restrições do prompt original:

- **R1 — runs limpas:** nenhum estado operacional persiste entre sessões. Grafo, modelo, RNG, caches, breaker e históricos pertencem ao contexto daquela run. A única “persistência” existente é a do APE default (`sataModel.obj` etc.), que **não é usada e não será usada** — e some do protocolo.
- **R2 — processo descartável:** uma run por processo `app_process`; a morte do processo é a barreira final contra `static`s e caches globais.
- **R3 — resultado ≠ estado:** artefatos observacionais (trace, telemetria, logcat) são permitidos e desejados; nenhum deles é relido pelo explorador. Sem `readGraph`, sem resume, sem read-back.
- **R4 — simplicidade:** sem IPC, sockets, segundo processo, plugin framework, classloaders dinâmicos, event sourcing completo, EventBus, async no caminho de decisão. Um mantenedor; Dalvik/`app_process`; zero dependências de runtime embarcadas.
- **R5 — configuração total e fail-fast:** chave desconhecida, tipo inválido, dependência ausente ou combinação proibida abortam **antes do primeiro step**. Nenhuma decisão comportamental depende de default implícito ou arquivo global descoberto.
- **R6 — determinismo observável:** seed, plano efetivo, versão, ordem dos estágios e digest ecoados no início de toda run.
- **R7 — telemetria não decide:** o sink observa; não altera política. Com sink ligado ou desligado, a mesma seed produz as mesmas decisões.
- **R8 — semântica de preempção preservada:** a precedência dura verificada no código (LLM preempta launcher; trigger de componente é side-effect; cadeia SATA é fallback) é semântica de pesquisa, não acidente. A migração a torna explícita sem alterá-la.
- **R9 — proveniência de métrica intocada:** desfechos primários continuam vindo do logcat/APK instrumentado, independentes do jar; definições de métrica da fase 2 permanecem congeladas.

---

## 3. Auditoria de claims (verificação em `5dcf225`)

Cada claim estrutural que sustenta a seleção foi verificado por subagente com leitura direta do código. Três categorias: confirmados, refutados/desatualizados, e **descobertas novas** feitas durante esta verificação (que nenhum relatório tinha).

### 3.1 Confirmados (fundamentos válidos)

| # | Claim | Evidência verificada |
|---|---|---|
| V1 | Escada de precedência = ordem textual de blocos `if` guardados | `SataAgent.selectNewActionNonnull`, `SataAgent.java:449-589` (141 LOC): logging `:450-462` → budget `:468-477` → LLM new-state `:480-487` → LLM stagnation `:493-506` → LLM random `:508-515` → launcher MOP `:522-545` (`shouldFireLauncher` com 6 args, 3 lidos de `Config` no call site `:523-525`) → component trigger `:547-551` (**side-effect sem return**) → cadeia SATA com o padrão `resolved = …; if (resolved != null) { log; return; }` **copiado 7×** `:552-587` → `throw new BadStateException` `:588` |
| V2 | Precondição LLM triplicada | `actionBufferSize()==0 && newState.getActions().size()>2 && _llmRouter != null` idêntica em `SataAgent.java:480-481`, `:493-494`, `:508-509` |
| V3 | Decisão “quando usar LLM” dividida entre 2 classes | predicados em `LlmRouter.java:232-237`, `:249-255`, `:276-281` (+ `breakerAllows()` `:292-302`); ordem e precondições em `SataAgent.java:480-515` |
| V4 | Preempção dura LLM→launcher | os 3 blocos LLM antecedem textualmente o launcher e retornam via `acceptLlmResult` (`:485,:504,:513`); launcher nem incrementa contadores nesses steps |
| V5 | Estado de estagnação espalhado em 3 classes (pós-#16) | flag em `StatefulAgent.java:128`, rearmada em `:1436`, queimada em `SataAgent.java:499` (+ reset do contador em `:503`) |
| V6 | `LlmRouter.selectAction` é um monólito | `LlmRouter.java:327-612` (286 LOC), **19 responsabilidades distintas** enumeradas (screenshot, prompt, HTTP, parse, mapeamento, ban, telemetria, cleanup…) |
| V7 | `Config` é global, congelada e silenciosa | 502 LOC; **113 campos `public static`** (108 final + 5 não-final); static block `:31-43` lê `/data/local/tmp/ape.properties` + `/sdcard/ape.properties`; `catch (NumberFormatException) {}` vazios em `:448-482`; **nenhuma** detecção de chave desconhecida |
| V8 | Kill-switch por sobrescrita de Properties + registries de strings | `forceApePureModeInto` `:399-410` antes dos initializers; `rvForcedOffValues()` = **27 chaves** `:341-368`; `rvUnsetKeys()` = 2; `rvExemptReasons()` = 21 — literais de string sem vínculo de compilador com os campos |
| V9 | Factory de agente com fallback silencioso | `ApeAgent.createAgent`, `ApeAgent.java:68-96`; valor desconhecido cai em `new SataAgent` em `:95` sem log; `System.exit(1)` só no branch replay-sem-log `:91` |
| V10 | `ScoringPipeline`: 7 passes, injeção nominal | `ScoringPipeline.java:51-61`; javadoc `:48-49` admite que `cfg` é decorativo; caller único passa `null` (`StatefulAgent.java:208`); 5 passes leem `Config` estática no construtor |
| V11 | Retentor principal: `actionHistory` | `Model.java:136-137` (TODO OOM no próprio código); cadeia de retenção real: `ActionRecord` → `Action` + `GUITreeAction` → `GUITree` → subárvore `GUITreeNode` inteira (`Model.java:62-93`, `GUITreeAction.java:29-31`, `GUITree.java:66-74`); sem `remove`/`clear` |
| V12 | Cache por nó nunca limpo | `GUITreeBuilder.java:693` (`namingToGUITreeNodeCache`, chave `GUITreeNode`); `release()` `:707-715` limpa só os dois caches por `GUITree` (`:670-671`) |
| V13 | `Graph` sem eviction | **17** campos de coleção em `Graph.java:98-130`; remoções só por correção (`remove(State)` `:1232-1282`), nenhuma por política |
| V14 | Persistência legada quebrada por construção | grava `Model` (`StatefulAgent.java:1864-1866`, `oos.writeObject(model)`; `Model` **não** estende `Graph`); lê com cast para `Graph` (`Graph.java:1166-1174`) |
| V15 | Telemetria: stdout key=value sem escape, sem run_id | `Logger.java` 67 LOC, `debug=false` compile-time (`:22`); `[APE-STEP]` 15 campos + condicionais, sem aspas (`StatefulAgent.java:1492-1506`); grep `run_id|runId|correlationId` em `src/main` → **0 hits** |
| V16 | Baseline cego por construção (INV-ARCH-01) | `ape.stepTelemetryEnabled` na lista forçada-off (`Config.java:346`); emissores gated em `StatefulAgent.java:1491,1519,1026` |
| V17 | Teardown isolado (a joia do sistema) | `Monkey.run` finally `:774-797`; `MonkeySourceApe.tearDown` 6 steps `:234-248`; `StatefulAgent.tearDown` **9** steps `:1802-1814`; coverage dump imediatamente antes de `saveGraph` (`:1807-1808`, INV-COV-10) |
| V18 | Deadline não-preemptivo; crash não aborta run cronometrada | `Monkey.java:1292-1302`; supressão de abort `:1369-1372` |
| V19 | Sem shutdown hook; único `catch(OutOfMemoryError)` é o parse do JSON | grep repo-wide → 0 `addShutdownHook`; `MopData.java:328` |
| V20 | Split-brain Python↔Java | `tool.py`: `APERV_PROPERTY_MAPPING` = **52** pares (`:77-165`; só chaves mapeadas são escritas, `:1159-1170`); `ARM_DEFINING_KEYS` = 18; `LLM_ARM_KEYS` = 11; guards pytest validam **constante Python contra constante Python** (INV-APV-14, `test_aperv_tool.py:630-640`); kill-switch duplicado e divergente (18 chaves Python vs 27 Java; Java força `llmOnNewState/llmOnStagnation/llmPercentage`, `mopWeight*`, `coverageBoostWeight` etc. que o Python não seta); sem drift test |
| V21 | Degradação silenciosa composta | JSON de análise estática ausente → warning e arm roda **sem MOP** (`tool.py:1497-1501`); exit ≠ 0 do jar → só `logger.debug` (`:1556-1562`) — um task “COMPLETED” pode não ter tido MOP **nem** ter rodado |
| V22 | Inputs globais lidos pelo jar | `/data/local/tmp/ape.properties`, `/sdcard/ape.properties` (`Config.java:34-35`); `/sdcard/ape.xpath` (`GUITreeBuilder.java:91`); `/sdcard/ape.xpath.actions` (`XPathActionController.java:52`); `/sdcard/ape.strings` (`StringCache.java:74-76`, **static initializer que lança RuntimeException em falha de leitura**); `system-broadcast.json`; JSON MOP; `--ape-model` |
| V23 | Furo de semeabilidade | `StringCache.nextString()` usa `ThreadLocalRandom` (`StringCache.java:118`) — fora do `RandomHelper` semeável (verificação própria; claim único do GLM) |
| V24 | Retentor independente em `ModelAction` | `resolvedGUITreeAction`/`resolvedTree` como campos (`ModelAction.java:85-87`, `:228-233`) — limitar `actionHistory` sozinho é insuficiente (verificação própria; claim único do GLM) |
| V25 | Colateral do kill-switch | `apePureMode` força `ape.activityStableRestartThreshold=Integer.MAX_VALUE` (`Config.java:362`) — o arm “puro” silenciosamente desliga o restart por estagnação de atividade (claim único do GLM, confirmado via V8) |

### 3.2 Refutados, desatualizados ou imprecisos (não usados como fundamento)

| Claim (fonte) | Veredito | Realidade verificada |
|---|---|---|
| “Tipo de agente desconhecido causa `System.exit(1)`” (vários) | **falso** | cai silenciosamente em `SataAgent` (`ApeAgent.java:95`); o exit é só replay-sem-log |
| “Condição de estagnação duplicada entre SataAgent e LlmRouter” (dossiê) | **stale** | consolidada em `LlmRouter.stagnationMidpointReached` (`:267-270`, único local, `>=`); o que resta espalhado é o **estado** (V5), não a lógica |
| “`selectNewActionNonnull` em 392-527” (dossiê) | **stale** | faixa atual: 449-589 |
| “117/118/112 flags” (vários) | **todos errados** | 113 campos `public static` (108 final + 5 não-final) |
| “26 arms no Python” (dossiê/meta-seleções) | **stale** | **29** (o próprio docstring diz 29; faltavam os 3 arms gh90) |
| “Graça de +15 s no timeout” (dossiê) | **stale** | **+45 s** (`tool.py:1395`; o comentário `:1392` explica a mudança) |
| “Graph tem 13 coleções” | **impreciso** | 17 campos de coleção (`Graph.java:98-130`); o claim relevante é ausência de bounds, não a contagem |
| “Nenhuma GUITree é jamais liberada” | **forte demais** | `State.removeLastLastGUITree()` (`State.java:556-561`) remove e libera via cadeia completa — mas é caminho de recheck de instabilidade, não política de retenção |
| “`releaseAll()` não limpa o cache X” (um relatório) | **fabricado** | `releaseAll()` não existe no repositório |
| “`maxGUITreesPerState` nunca é aplicado” (MiMo) vs “gates refinement” (dossiê) | **dossiê certo** | aplicado em `NamingFactory.java:280,1180` como supressor de refinement; não em `appendGUITree`; libera zero memória |
| “Mapa de naming copiado a cada refinement” (dossiê) | **refutado** | a cópia em `AbstractNamingManager.clone()` (`:103`) existe, mas **`clone()` não tem nenhum caller externo no repo** — código morto hoje (DeepSeek certo; Laguna citou apenas a cadeia interna de `super.clone()`) |
| “Eixo CLI `--ape` e eixo de properties são ortogonais” (dossiê) | **invertido** | `--ape` grava na **mesma** `Properties` (`Config.set("ape.agentType")`); o tipo de agente pode ser trocado silenciosamente por `/sdcard/ape.properties`, e `--ape mop` é engolido — achado **pior** que o reportado |
| “`selectAction` é o maior método do repo” | **impreciso** | maior do código APE-RV; no repo, `MonkeySourceScript.handleEvent` (466 LOC, AOSP) é maior — irrelevante para o escopo, mas corrige o registro |
| “O dono decidiu baseline byte-a-byte com upstream” (Gemini E2.1) | **não sustentado** | contradiz as decisões registradas por Kimi/GLM (“APE + bugfixes documentados aceito”; manifest compartilhado rejeitado) e o mandato atual (“o default do ape NÃO usamos e nem vamos usar”). Descartado |
| “Budget gate é só advisory, sem return” (dossiê/meta-seleções) | **parcial** | o gate **retorna** ação trivial não-nula em `:474`; só o caminho nulo faz fall-through — consequência direta para o design do pipeline (Sec. 6.3) |
| Nesting 8 em `generateEvents`; ApeAgent 446 LOC | **imprecisos** | profundidade medida 6; `ApeAgent.java` = 465 LOC |

### 3.3 Descobertas novas desta verificação (nenhum relatório tinha)

1. **LLM bem-sucedido não avança a cadência do launcher** — `_stepsSinceLauncherFiring++` (`SataAgent.java:522`) nunca executa num step preemptado pelo LLM. Interação não documentada entre features; exatamente a classe de acoplamento oculto que o pipeline precisa tornar explícito (cada estágio dono dos próprios contadores).
2. **`getBoolean` não tem caminho de erro nenhum** (`Config.java:439-446`) — um typo em booleano degrada para `false` ainda mais silenciosamente que os getters numéricos com catch vazio.
3. **Javadoc do `ScoringPipeline` diz “six passes”** (`:14,:43`) enquanto constrói 7 — drift de documentação dentro do próprio módulo citado como exemplo de boa arquitetura.
4. **`mopWeightOpenMenu` é não-final sem motivo vivo** (nenhum teste o escreve); `activityTriggerEnabled` é não-final sem comentário justificando.
5. **`llm_snap_tolerance_px` é chave viva fora de todos os guards** — o comentário do `tool.py:216-220` a declara “ignorada pelo binário”, mas `Config.java:223` a lê e o arm `mop_on_llm_70` a seta (`tool.py:774`): uma chave que diferencia arm LLM fora do `LLM_ARM_KEYS`.
6. **Falha de load do modelo é silenciosa, não crash** — `Graph.readGraph` engole o `ClassCastException` e retorna `new Graph()` vazio (`Graph.java:1169`): um run com `--ape-model` partiria do zero logando apenas “Fail to load graph”. Pior do que os relatórios descrevem — e mais um motivo para **remover** em vez de consertar.
7. **`mop_weight_activity` continua mapeado no Python** (`tool.py:97`) para uma chave que não existe mais no jar (0 hits em `src/main`) — arqueologia viva do split-brain.

---

## 4. O que foi aproveitado e rejeitado de cada LLM

| Relatório | Aproveitado (verificado) | Rejeitado (motivo) |
|---|---|---|
| **DeepSeek-v4** | `StepPipeline` como herdeiro natural da decisão de 2026-07-08; a tese causal “o conceito de modo não tem casa no Java, então colonizou o Python”; refutações corretas (estagnação single-sourced; `clone()` sem callers); telemetria-como-estágio igual para todos os arms; join violação↔step **analysis-side** por wall-clock no pipeline Python (sem acoplar APE a logcat) | manifest `modes.yaml` compartilhado como artefato cross-repo (substituído por preset-no-jar + eco); journal em disco com resume (viola R1/R3); `TwoLineage` (fora de escopo) |
| **Gemini-3.6** | preset imutável validado no boot; `FeatureConfig` substituindo `Config` estática; a quantificação do custo do LLM síncrono (~1 s/call ≈ 0,95 steps) | decorator-chain como modelo de modo (esconde precedência; aninhamento rígido); `Mode` enum de primeira classe; ERKE (async/EventBus/arbiter — nondeterminismo); checkpoint periódico; claim E2.1 “byte-a-byte decidido” (não sustentado) |
| **GLM-5.2** | as descobertas V23/V24/V25 (todas confirmadas); sampler ponderado **confinado aos 7 degraus SATA** como hipótese futura de limpeza do copy-paste (não adotado agora); ledger explícito de deleções; experimentos baratos discriminantes | WPS como organizador (“não há escada de precedência” — refutado pelo código e pela decisão Q1 do dono: precedência é dura); EDC out-of-process (IPC) |
| **GPT-5** | o diagnóstico-raiz “o tratamento experimental efetivo é implícito” e o **digest requested-vs-effective como gate de validade da amostra**; `DecisionResult` como sum type (`Selected`/`Declined`/`Effect`) — ataca o `null` sobrecarregado e o trigger efeito-sem-retorno; taxonomia epistêmica da atribuição (fato ≠ atribuição temporal ≠ contrafactual ≠ efeito identificado); desenho estatístico (família confirmatória de arms; intention-to-treat por wall-time fixo); “alguns invariantes devem dissolver, não ser preservados” | event sourcing/journal como verdade (C3); epochs supervisionados com checkpoint e resume (viola R1); lane fossilizada (fora de escopo) |
| **Kimi-K3** | `RunSpec` com `specHash`; `Feature` enum com dependências declaradas e valor-puro **como dado** (mata os 3 registries de string); registro das decisões do dono (manifest compartilhado **rejeitado**; heartbeat aprovado); artefato de análise estática **compacto e derivado** (empurrar 1–5 MB explorer-shaped em vez de 1,5–48 MB de call-graph); correções do Appendix Z (17 coleções; 27 chaves) | journal recarregável/replay/resume (C2 completo); `.fbm`-style persistência (contra R1); **contrato sentinela + exit code validado pelo Python** — descartado pelo dono (2026-08-02): detecção de truncamento pós-hoc por timestamps já é possível hoje e basta |
| **Ling-3.0** | policy pipeline (chain-of-responsibility com fallback estrutural); a observação T7 quantificada (o grosso dos bytes do JSON é call-graph que o explorador não usa — reforça o artefato compacto do Kimi); contabilização honesta do custo de migração dos parsers de análise | manifest JSON compartilhado e **deployado no device** como fonte de verdade (rejeitado pelo dono, registro do Kimi); process-per-mode/plugins/classloaders (C3); checkpoint em OOM |
| **MiMo-v2.5** | `FeatureContext.empty()`/plano vazio como expressão do baseline (a pureza como **ausência estrutural** de features, não kill-switch); decomposição do `LlmRouter` em unidades (caller/screenshot/mapper/ban-tracker); elevação do `MopCounterfactual` como capacidade a preservar | precedência **como dado editável** no spec (validador teria de reimpor as restrições de ordem — cerimônia sem ganho: a ordem é semântica de pesquisa, muda por release, não por arm); EventBus; checkpoint declarado no spec; bounds numéricos day-1 sem profiling |
| **Laguna S-2.1** | a pergunta “o que o jar deve **parar** de possuir” (resposta: persistência, retry, accounting de conclusão — tudo já é do Python); censo de ≥17 mudanças não-gated com o achado `ApeFuzzer.java:197` (fork passou a **despachar** pinch/zoom que o upstream só computava); o bug do DSL não tipado (`@heuristic_input=False` cruza como string `"False"`) | X1 (log-como-verdade com replay), X2 (política como programa externo interpretado — o jar “burro” debugando JSON), X3 (worker checkpointável com resume) — todos contra R1/R3/R4 |

---

## 5. Divergências entre as 4 meta-seleções — e como foram resolvidas

As quatro meta-seleções (DeepSeek, Gemini, GPT-5, MiMo) convergem no núcleo (RunSpec + pipeline + bounded + NDJSON + modos-como-presets). Divergem em três pontos; a resolução de cada um:

### 5.1 Manifest compartilhado jar↔Python (DeepSeek a favor; GPT-5 contra) → **contra, com síntese**

O problema real é verificado (V20): o significado dos modos vive em dicts Python sem contrato com o jar, e o drift é silencioso (V21, achado 3.3-7). Mas um arquivo compartilhado cria uma **terceira autoridade** com versionamento e deploy cross-repo próprios — troca uma costura por duas. E há registro (Kimi) de que o dono já **rejeitou** o manifest compartilhado, optando por autoridade no jar + detecção de drift.

**Resolução:** os *presets* (o que `aperv`, `mop`, `llm`, `llm_mop` significam) passam a residir **no jar**, que é quem os interpreta; o Python passa `preset + overrides explícitos`. Isso mata os 29 arms × 52 chaves hardcoded gradualmente (arms viram deltas finos), sem criar artefato novo. O eco (`RUN_START`) opera em **nível 0**: proveniência write-only na primeira linha do trace — nenhuma validação automática, nenhuma mudança no Python. Já no nível 0 ele fecha as classes de drift que mais doeram: um jar desatualizado no device (bug gh71: boost MOP disparou 0× em 147 mil avaliações) fica visível pela versão/build-hash no `RUN_START`, e “que explorador rodou esta task” passa a ter resposta definitiva dentro do próprio `.trace`. A substituição do INV-APV-14 (constante validada contra si mesma) por verificação automática eco-vs-intenção é o **nível 1** — opção futura do dono, ~20 linhas no `tool.py`, sem mudança de arquitetura.

Vale explicitar o que **não** existe aqui, porque a palavra “eco” sugere diálogo: não há handshake, socket, request/response nem leitura do jar pelo Python em tempo de execução. O fluxo físico é o que já existe hoje — `adb push` do `ape.properties`, launch por linha de comando, stdout capturado no `.trace`. Muda apenas *o que o jar imprime* na primeira linha. É o mesmo mecanismo do `[APE-LLM-CONFIG]` atual, generalizado para o plano inteiro (e fechando o gap “MOP não ecoa pesos efetivos” do prompt §2.3).

### 5.2 `StockApeAgent` / linhagem dupla (Gemini a favor; GPT-5/MiMo contra) → **fora de escopo**

O dono declarou nesta fase: o APE default **não é usado e não será usado**. Construir um segundo artefato/agente para máxima fidelidade upstream resolveria um problema que a tese não tem mais. O que se preserva é a *propriedade* (arm de controle não contaminado por feature RV), agora estrutural: no plano do arm de controle, as features simplesmente não existem — e o eco prova. Se um dia a paridade com upstream voltar ao escopo, o caminho é o **oráculo estatístico** (comparar distribuições de sequências de ação contra build do upstream `8f51b99`), não build duplo.

### 5.3 Amostragem ponderada vs preempção dura (GLM C1) → **preempção dura**

Verificado (V1, V4): a precedência é dura e semântica — LLM preempta launcher; trigger de componente é efeito, não candidato pontuável; budget retorna ação trivial. Converter isso em pesos muda o comportamento observável de todos os arms e invalida a comparabilidade com o grid histórico (21.681 tarefas da fase 2 + calibração). A decisão registrada do dono (Q1: precedência dura) fecha a questão. A ideia sobrevivente do GLM — dissolver o copy-paste 7× **dentro** do estágio SATA — fica como limpeza interna do estágio, sem mudar a semântica de amostragem.

---

## 6. A arquitetura selecionada — Kernel de Run Descartável

### 6.1 Princípio organizador

> **Uma run é a execução de um plano imutável, dentro de um processo descartável, através de um pipeline de decisão explícito, deixando para trás apenas observações.**

Quatro conceitos novos, e só quatro: `RunSpec` (o plano), `RunContext` (o estado), `DecisionPipeline` (a política), `EventSink` (a observação). Tudo o mais é o APE-RV de hoje, realocado para dentro dessas quatro casas — e uma lista longa de coisas **deletadas** (6.6).

```text
rv-android (Python)                      ape-rv.jar (Dalvik, processo novo por run)
─────────────────────                    ──────────────────────────────────────────
arm = preset + overrides    ──adb push─► RunSpec.resolve(properties, CLI)
                                           │  fail-fast: chave desconhecida, tipo
                                           │  inválido, dependência ausente → abort
                                           ▼
1ª linha do .trace          ◄──stdout──  RUN_START {plano efetivo, digest, versão, seed}
(proveniência; nível 0 —                   │
nenhuma validação)                         ▼
                                         RunContext (model, graph, RNG, MopData,
                                           LlmClient+breaker, trackers, episódios,
                                           contadores, sink) — nada estático
                                           ▼
                                         loop: observe → DecisionPipeline → execute
                                           │   → StepRecord acumulado; fechado e
                                           │     escrito (1 linha NDJSON) no step N+1
                                           ▼
                                         RUN_END {reason=timeout, contadores} + exit 0
task fecha como hoje;       ◄──stdout──    │
runs curtas identificáveis                 ▼
pós-hoc por timestamps                   processo morre; nada é relido, nunca
```

### 6.2 `RunSpec` — o plano

Value object imutável, resolvido uma única vez no bootstrap a partir de `ape.properties` + CLI. Substitui a `Config` estática como autoridade comportamental.

```java
final class RunSpec {
    final String presetName;        // "aperv" | "mop" | "llm" | "llm_mop" (informativo)
    final long seed;
    final String runId;             // gerado pelo host, ecoado em toda linha
    final ExplorationParams base;   // epsilon, throttle, restarts, caps — sempre presentes
    final Set<Feature> features;    // ausente = não existe nesta run
    final MopParams mop;            // null ⇔ !features.contains(MOP)
    final LlmParams llm;            // null ⇔ !features.contains(LLM); inclui fallback declarado
    final TelemetryParams telemetry;
    final String digest;            // hash canônico de tudo acima
}
```

- **`Feature` é enum com metadados como dados** (Kimi): chave, tipo, default, dependências declaradas (`MENU_GATEWAY requires MODEL_MENU + MOP`; `FRONTIER requires MOP_INPUT`; `LLM_* requires LLM_URL`). As três registries de string do kill-switch (V8) morrem; a validação vira código que o compilador enxerga.
- **Fail-fast total (R5):** chave desconhecida em `ape.properties` → abort com mensagem (corrige V7, achados 3.3-2); `--ape` desconhecido → erro (corrige V9); combinação inválida → abort antes do step 1. Fecha também o achado da não-ortogonalidade (3.2): o tipo de agente passa a ser parte validada do plano, não uma property injetável por `/sdcard`.
- **Presets no jar:** `Presets.resolve("mop")` devolve o vetor de features/params correspondente; overrides vêm por cima, explícitos. Ablação = override nomeado, nunca modo novo.
- **Eco write-only, nível 0 (GPT-5, recortado por decisão do dono):** `RUN_START` — a primeira linha do trace — carrega o plano efetivo completo, o digest do `ape.properties` lido, e a **versão/build-hash do jar**. Nenhum canal novo, nenhuma validação automática: é registro de proveniência que torna cada `.trace` auto-descritivo (“que explorador rodou esta task” deixa de exigir reconstrução via `tool.py`). **Nível 0 é definitivo** (decisão do dono, 2026-08-02): auditoria de drift é pós-hoc, na análise, quando houver suspeita; reavaliar apenas mediante incidente real.

### 6.3 `DecisionPipeline` — a política

A escada de `selectNewActionNonnull` (V1) vira uma lista ordenada de estágios nomeados, montada uma vez pelo plano. A ordem é dado inspecionável; a semântica atual é preservada exatamente.

```java
interface DecisionStage {
    String name();
    StageResult decide(StepContext ctx);   // StepContext: estado, ações, RunContext
}
// StageResult é sum type (Java 11: classe selada por construtores estáticos):
//   Select(action, decisionSource)  — decide e encerra o step
//   Continue()                      — passa ao próximo estágio
//   SideEffect(desc)                — efeito colateral registrado, segue (component trigger)
```

Estágios, na ordem de hoje (verificada, V1): `Budget` (pode **Select** a ação trivial — correção do 3.2 — ou Continue), `LlmNewState`, `LlmStagnation`, `LlmRandom` (precondição comum num único helper — mata a triplicação V2), `MopLauncher`, `ComponentTrigger` (SideEffect), `SataChain` (fallback; internamente preserva os 7 degraus e o `ScoringPipeline` como sub-pipeline de pontuação, agora com injeção real de parâmetros — conserta o `cfg` decorativo V10).

- **Estado de episódio tem casa:** a flag de estagnação hoje espalhada em 3 classes (V5) vive no estágio `LlmStagnation` (armada/queimada num único objeto, resetada pelo `RunContext` em eventos de transição). Os contadores do launcher vivem no estágio `MopLauncher` — e a interação descoberta em 3.3-1 (LLM preemptando sem avançar cadência) vira decisão explícita e testada, não acidente textual.
- **`LlmRouter` fatiado** (MiMo): o monólito de 286 LOC/19 responsabilidades (V6) se decompõe em `LlmClient` (HTTP+breaker), `ScreenshotStep`, `PromptBuilder` (já existe), `CoordinateMapper` (snap/boundary/ban), `LlmTelemetry` — orquestrados pelo estágio. Cada peça testável em JVM pura.
- **Teste de ouro de preempção:** estados sintéticos que elegem simultaneamente LLM, launcher, component e SATA confirmam a ordem declarada (Sec. 9). A migração é gated por oráculo de paridade por modo.

### 6.4 `RunContext` — o estado

Dono único de todo estado mutável da run: `Model`, `Graph`, `RandomHelper` semeado (e o `nextString` do `StringCache` migrado para ele — fecha V23), `MopData`, cliente LLM + breaker, `UICoverageTracker`, `ActivityBudgetTracker`, contadores, sink. Nada consulta `Config` estática depois do bootstrap; os 5 campos não-finais “para testes” (V7) morrem — testes constroem `RunSpec`s. `AndroidDevice` é acessado por interface estreita membro do contexto (suficiente para testar a lógica de decisão em JVM pura; **sem** camada de simulação completa de device — over-engineering para um mantenedor).

### 6.5 `EventSink` — a observação (registro agrupado por step)

**Decisão do dono (2026-08-02):** em vez de uma linha NDJSON por *evento* (com envelope repetido em cada uma), o sink emite **um registro NDJSON por step** — um objeto `StepRecord` que agrupa tudo que aconteceu naquele step, com os metadados uma única vez e os sub-eventos como lista ordenada interna. O código atual já aponta nessa direção: o join buffer do `StatefulAgent` (`lastDecisionStep`/`lastDecisionAction`, `:117-124`) existe exatamente porque o outcome do step N só resolve no step N+1 — hoje ele serve para emitir duas linhas que o analista re-junta por `step=`; aqui ele vira o acumulador natural do registro.

```json
{"s":42,"t":8123,"act":17,"st":231,
 "dec":{"src":"MOP_WIDGET","ch":"GREEDY","mop":500,"menu":250},
 "llm":[{"call":3,"mode":"new_state","result":"accepted","px":[512,884],"ms":973,"tok":[1841,25]}],
 "out":{"new_state":true,"target":232,"act_changed":false}}
```

**Ciclo de vida do registro:** aberto no início do step; decisão, chamadas LLM e efeitos anexados conforme ocorrem; **fechado e escrito (uma linha, line-buffered) quando o outcome resolve — i.e., no início do step N+1** (mesmo timing do join buffer atual: nada é adiado além do que já é). O teardown ganha `safeStep("flushPendingStep")` que descarrega o step em voo com `out:{"resolved":false}`. Perda máxima em morte súbita (SIGKILL): **1 step** — contra o status quo em que 42,3% das runs perdiam o coverage dump inteiro antes do fix A10.

**Redução de volume** (o trace atual cresce ~3,5 GB/880 tasks; quatro alavancas, todas habilitadas pelo agrupamento):

1. **Envelope 1× por step**, não por evento — some a repetição de `step=`/contexto entre `[APE-STEP]`, `[APE-OUTCOME]` e N× `[APE-LLM-TEL]`.
2. **Defaults omitidos:** hoje toda linha imprime `mop=0 mop_frontier=0 wtg=0 coverage=0 menu=0 form=0`; em JSON, campo ausente = default. A maioria dos steps carrega 2–3 boosts não-zero.
3. **Tabela de IDs para strings repetidas:** `activity` e `state` (as strings mais longas e mais repetidas do trace) são emitidas uma vez como evento próprio — `{"type":"ACT","id":17,"name":"com.foo/.MainActivity"}` — e referenciadas por inteiro nos steps. Run-local, write-only, reconstrução trivial na análise.
4. **`run_id` só em `RUN_START`/`RUN_END`:** o `.trace` já é 1:1 com a task no rv-platform; repetir o id em toda linha é redundância intra-arquivo.

Estimativa: 3–5× de redução bruta; e NDJSON com chaves curtas comprime muito bem — **gzip na coleta (lado Python, custo zero para o jar)** resolve o custo de armazenamento em repouso, ortogonal ao formato.

**Escaping por construção — não repetir o erro do `\n`:** o fix recente do #16 *achatou newlines na origem* (`ModelAction.resolvedInfo`), i.e., consertou o dado para caber num formato frágil. Direção invertida aqui: um serializer JSON próprio (~80 linhas, sem dependência) escapa aspas, barra invertida, caracteres de controle e NUL por construção — qualquer conteúdo cabe no formato. Duas garantias viram teste permanente (Sec. 9.12): round-trip de valores com newline/aspas/NUL/espaços, e o invariante **uma-linha-por-registro** (o serializer nunca emite newline cru).

**Demais propriedades (inalteradas da seleção original):**

- **Eventos fora do step:** `RUN_START` (plano efetivo + digest + versão), eventos de dicionário (`ACT`/`STATE`), `MOP_DATA` (status do load), `RUN_END` (reason + contadores). Tudo no mesmo NDJSON.
- **Transporte:** stdout (contrato de coleta intocado — zero mudança no Python além do parser, com conversor temporário durante a migração). Sink em arquivo no device continua opção futura atrás de flag, decidida por medição de perda.
- **Sempre-on e neutra:** o mesmo registro para todos os arms — dissolve INV-ARCH-01 deliberadamente. Neutralidade testável: sink ligado/desligado, mesma seed ⇒ mesmas decisões (R7). Custo alvo ≤ o da linha atual (continua ~1 escrita/step; o acúmulo é um objeto pequeno reutilizado).
- **Join violação↔step:** no **lado da análise** (DeepSeek): wall-clock (`t`/`clock` no registro) join no pipeline Python mapeia cada violação RVSEC do logcat ao step precedente, emitindo `first_seen_step` por chave de dedup. Sem acoplamento runtime APE↔logcat. **O heartbeat write-only no logcat entra desde o início, atrás de flag (default on)** — decisão do dono, 2026-08-02: uma linha `s=N t=...` por step via `Log.i`, colocando violação e step no mesmo arquivo/relógio e imunizando o join contra clock skew; desligar depois é trivial.
- **`RUN_END` é só o último registro** (decisão do dono, 2026-08-02): motivo + contadores, write-only, simétrico ao `RUN_START`. **Nenhuma validação no Python, nenhuma mudança de status de task** — a ideia de “contrato de saída” (Kimi) foi descartada: os dados para identificar runs curtas pós-hoc já existem (timestamps do trace/logcat — foi assim que o relatório de calibração e o próprio relatório do bug de truncamento foram escritos). V21 permanece na auditoria como fato; o remédio, se algum dia necessário, é análise, não mecanismo.
- **Teste de aceitação preservado:** o relatório de calibração de 2026-07-24 precisa continuar escrevível a partir do novo trace — e fica *mais fácil*: as ~39 mil chamadas LLM já vêm penduradas no step certo (o join deixa de existir); a latência por chamada vive no sub-evento (`ms`).

### 6.6 Subtração — o que é deletado

O prompt exige que cada proposta declare o que **remove**. Lista:

| Deletado | Substituído por |
|---|---|
| `saveGraph`/`readGraph`/`--ape-model`/`sataModel.obj` (V14) | nada — R1/R3; retry integral no supervisor |
| `sataGraph.vis.js`, `sataGraph.dot`, `step-*.txt` por estado, `action-history.log`, `sataTimeline.vis.js`, `produce.log`/`consume.log` | o trace NDJSON (as visualizações, se necessárias, viram pós-processamento no host) |
| `Config` estática como autoridade (113 campos, V7) + os 3 registries de string do kill-switch (V8) + os 5 campos não-finais | `RunSpec` + `Feature` enum com metadados |
| `apePureMode` como mecanismo (sobrescrita de Properties pré-initializer) | pureza estrutural: plano sem a feature ⇒ feature não existe; eco prova |
| Fallback silencioso do `createAgent` (V9) + a via `/sdcard/ape.properties` para trocar agente (3.2) | validação total do plano |
| Precondição LLM ×3 (V2), `shouldFireLauncher` de 6 args, os 141 LOC do `selectNewActionNonnull` | estágios nomeados |
| Inputs globais `/sdcard/ape.xpath`, `/sdcard/ape.xpath.actions`, `/sdcard/ape.strings` (V22 — incluindo o RuntimeException do static initializer do `StringCache`) | **removidos** (decisão do dono, 2026-08-02 — nenhum arm os usa e o `tool.py` nunca os empurra); a geração de strings de input é reescrita sobre o RNG semeável do `RunContext`, matando também o `ThreadLocalRandom` residual (V23) |
| `mop_weight_activity` e demais chaves mortas do `tool.py` (3.3-7); os 29 arms × 52 chaves hardcoded | arms finos: `preset + overrides` verificados por eco |
| Parse on-device de call-graph de até 48 MB (T7 do Ling) | artefato **derivado e compacto** gerado no host (Kimi): só o que o explorador usa — widgets flagados, WTG, componentes (~1–5 MB); mata a classe de degradação `too-large` |

### 6.7 Memória — cirúrgico agora, medido depois

- **Agora (defeitos inequívocos):** limpar `namingToGUITreeNodeCache` no mesmo ciclo do `release()` (V12); `ActionRecord` diagnóstico passa a guardar IDs + snapshot mínimo em vez de `GUITreeAction`→árvore inteira (V11) — *condicionado a* confirmar que nenhum caminho semântico (rebuild/replay) depende dos objetos ricos; idem para `ModelAction.resolvedTree` (V24) além do último resolve.
- **Depois (gated por profiling):** qualquer bound sobre `Graph`/`treeHistory`/naming exige heap profile por retention-root em runs de 600 s + teste de paridade de sequência de ações. Eviction em estrutura de refinement muda comportamento científico — não se especula (rejeita os bounds day-1 do MiMo).
- **OOM:** continua sendo morte do processo + task FAILED no supervisor (o contrato de saída detecta). Sem catch heroico tentando serializar um modelo gigante num heap exausto — esse caminho morre junto com `saveGraph`.

### 6.8 Respostas às questões abertas do prompt (§2.2), nos termos desta arquitetura

- **“Mode” sobrevive?** Não como primitivo. O primitivo de engenharia é o **plano** (features + params); o primitivo científico é o **preset nomeado, versionado e ecoado**. Os nomes `aperv/mop/llm/llm_mop` são aliases de planos — consenso de 7 dos 8 relatórios, agora com mecanismo concreto.
- **`mop` = `aperv` + MOP?** Sim, por construção (consenso unânime dos 8): as melhorias `aperv` são o instrumento; o mecanismo MOP é o objeto de estudo; a cadeia monotônica `aperv → mop → llm_mop` isola um mecanismo por comparação. `mop_minimal` continua expressável por override, se alguma ablação o pedir.
- **Widget vs frontier:** eixos de feature ortogonais e parametrizados (`MOP_WIDGET`, `FRONTIER`, `MOP_FRONTIER`…), nunca modos — arms adicionais são overrides, e o custo em contagem de arms é aditivo, não multiplicativo.
- **Fallback do LLM:** **declarado no plano** (`llm.fallback`) e realizado estruturalmente — o estágio LLM retorna `Continue` em declínio/falha/breaker e o pipeline cai no restante configurado (`aperv` ou `mop`). A “bidimensionalidade” do modo é exatamente isso: base = o resto do pipeline; guidance = os estágios preemptivos habilitados.
- **Modo `ape` (stock):** fora do escopo desta fase por decisão do dono. O arm de controle da campanha é o preset `aperv` mínimo (ou o arm congelado da fase 2, externo a este redesign).

### 6.9 Orçamento de complexidade

4 conceitos novos (`RunSpec`, `RunContext`, `DecisionPipeline`+`StageResult`, `EventSink`) — contra a remoção de: 3 registries de string, kill-switch por Properties, 113 campos estáticos como autoridade, fallback silencioso, precondição triplicada, 7 arquivos de output legado, 1 protocolo de persistência quebrado, e ~50% do conteúdo hardcoded dos 29 arms Python. Saldo de LOC estimado próximo de zero no jar; fortemente negativo no sistema como um todo. Um recém-chegado precisa aprender: “o plano decide o que existe; o pipeline decide em que ordem; o contexto guarda; o sink observa”.

---

## 7. Rejeições explícitas (com o motivo verificado)

| Ideia (fontes) | Motivo da rejeição |
|---|---|
| Event sourcing / log-como-verdade / replay (Kimi C2, GPT-5 C3, Laguna X1, Ling, MiMo, Gemini ERKE) | Sem caso de uso sob R1/R3 (replay/resume proibidos); a projeção duplicaria `Model`/`Graph`; schema-evolution e consistência são custo puro. Sobrevive apenas a fatia write-only: eventos tipados como **observação** |
| Checkpoint / resume / WAL / epochs supervisionados (GPT-5, Kimi B6, Laguna X3, Ling, MiMo) | Viola R1; o Python já possui retry por task; uma run ressuscitada é amostra cientificamente ambígua (decisão Q3 do dono: uma amostra por run, descartar a anterior) |
| Política externa / IPC / segundo processo / jar “burro” (GLM EDC, Laguna X2) | Custo de serialização por step no emulador; segundo failure domain; deploy mais complexo em rv-platform; contrato tipado in-process obtém a testabilidade desejada |
| LLM assíncrono / EventBus como fundação (Gemini ERKE, MiMo C2) | Loop é single-threaded por design; async quebra determinismo semeado e a causalidade passo-a-passo. O problema real (1 s bloqueado/call, medido) se ataca por dose/trigger — e um eventual arm experimental async seria mudança de protocolo declarada, nunca fundação |
| Amostragem ponderada substituindo precedência (GLM WPS, Gemini C4) | Refutada pelo código (V1/V4) e pela decisão Q1 do dono; invalidaria comparabilidade com o grid histórico |
| Linhagem dupla / `StockApeAgent` / build duplo (DeepSeek C3, GPT-5 C2, Kimi C3, Gemini SGDA) | Fora de escopo por decisão do dono (APE default não usado); custo de build/deploy sem pergunta de pesquisa que o pague |
| Manifest declarativo compartilhado entre repos (DeepSeek C2/B, Ling C1, MiMo C3 como spec-file cross-repo) | Terceira autoridade com deploy próprio; rejeitado pelo dono (registro do Kimi); substituído por preset-no-jar + eco+digest |
| Precedência como dado editável por arm (MiMo C3) | A ordem é semântica de pesquisa estável; torná-la editável exige validador que re-imponha as restrições — cerimônia que só reintroduz o risco que o pipeline elimina |
| Plugin frameworks / classloaders / process-per-mode (Ling C3) | Dalvik/`d8`, um mantenedor, zero benefício científico |
| Bounds LRU/day-1 sobre estruturas semânticas (MiMo S3, Gemini) | Eviction em naming/refinement muda comportamento; exige profiling + teste de paridade primeiro (R8 do GPT-5; “memory semantics” na Sec. 9) |

---

## 8. Invariantes: preservados vs dissolvidos

**Preservados (e como):** isolamento de teardown INV-EXPL-16/29 (V17 — intocado; continua fechando os sinks); fail-fast do MOP load INV-MOP-22 (generalizado: *toda* validação de plano é fail-fast); o join `step=`/`clock=` (V15/T3 — promovido a envelope tipado); contrato no-op de pass desabilitado (INV-ARCH-03 — generalizado para estágios: feature ausente = estágio ausente); explicitação de arms (INV-APV-13/17 — reforçada por eco-vs-intenção, que valida contra o binário real).

**Dissolvidos deliberadamente (com o que os substitui):** INV-ARCH-01 (baseline sem telemetria) → telemetria universal e neutra, com teste de neutralidade (R7); INV-EXPL-03 (mismatch `Model`/`Graph` documentado) → o protocolo inteiro é removido; INV-APV-14 (constante vs constante) → verificação de eco; INV-ARCH-06 (exempt keys inertes) → deixa de ter objeto: sub-parâmetro de feature ausente não existe no plano.

---

## 9. Testes arquiteturais (definem “pronto” para qualquer estágio da migração)

1. **Isolamento A→B:** duas runs consecutivas; a segunda produz sequência idêntica à mesma run isolada (mesma seed/inputs).
2. **Proibição de read-back:** instrumentar filesystem; a run B não lê nenhum artefato da run A.
3. **Config total:** chave desconhecida, tipo inválido, path residual, combinação proibida → abort antes do step 1, com mensagem.
4. **Golden de preempção:** estados sintéticos elegendo LLM+launcher+component+SATA simultaneamente confirmam a ordem declarada — incluindo o caso 3.3-1 (cadência do launcher sob preempção LLM) como comportamento **decidido**.
5. **Fallback do LLM:** decline/timeout/breaker produzem exatamente o fallback declarado no plano, com `decision_source` correto.
6. **Eco (nível 0):** todo trace começa com `RUN_START` contendo plano efetivo, digest do properties lido e versão/build-hash do jar; o conteúdo sozinho reconstrói o arm sem consultar o `tool.py`.
7. **`RUN_END` em término normal:** todo término por timeout escreve `RUN_END` como último registro (teste apenas do lado do jar; nenhuma verificação existe no Python, por decisão do dono).
8. **Neutralidade da telemetria:** sink on/off ⇒ mesmas decisões sob mesma seed.
9. **Paridade por modo (gate da migração):** para cada preset, o pipeline novo reproduz as decisões do código atual sob as mesmas seeds (oráculo antes/depois por arm).
10. **Semântica de memória:** qualquer bound novo passa por paridade de sequência de ações + invariantes de refinement.
11. **Aceitação de observabilidade:** o relatório de calibração de 2026-07-24 é re-gerável a partir do novo trace (parser novo, mesmas tabelas).
12. **Escaping por construção:** valores contendo newline, aspas, NUL, espaços e não-ASCII fazem round-trip serialize→parse; o serializer nunca emite newline cru (invariante uma-linha-por-registro); o step em voo é descarregado no teardown (`flushPendingStep`) e a ausência de `out` resolvido é representável (`resolved:false`), nunca linha malformada.

---

## 10. Plano de adoção (estágios independentes, cada um valioso sozinho)

1. **Oráculo de paridade por modo** (captura de goldens do comportamento atual nos presets-alvo). Gate para tudo que segue.
2. **`RunSpec` + fail-fast + eco nível 0** (a `Config` estática vira detalhe de carga; `createAgent` falha alto; `RUN_START` ecoa plano + digest + versão do jar como primeira linha do trace; remoção dos leitores de `/sdcard/ape.xpath*`/`ape.strings` — decisão do dono). **Zero mudança no Python neste estágio.**
3. **`DecisionPipeline`** (fatiar `selectNewActionNonnull` nos estágios nomeados; estado de episódio realocado; `ScoringPipeline` com injeção real). Validado pelo oráculo do estágio 1.
4. **Telemetria NDJSON agrupada por step** (`StepRecord` fechado no N+1; serializer com escaping real; tabela de IDs; defaults omitidos; `RUN_END` como último registro; heartbeat no logcat atrás de flag; conversor temporário para os parsers atuais do rv-platform; gzip na coleta; testes de aceitação = itens 11 e 12 da Sec. 9). Remoção dos outputs legados (6.6).
5. **Arms finos no Python** (presets + overrides; remoção das chaves mortas; kill-switch duplicado morre).
6. **Memória cirúrgica** (V12, V11/V24 com checagem semântica) e, só após profiling, bounds adicionais.
7. **Artefato de análise estática compacto** (gerador no host; `MopData` passa a consumir o formato derivado).

Cada estágio mantém o sistema executável e os resultados comparáveis; nenhum exige parar campanhas.

---

## 11. Riscos e mitigações

| Risco | Mitigação |
|---|---|
| Regressão da ordem de preempção na migração | Oráculo de paridade (estágio 2) como gate; golden de preempção permanente |
| Novo formato de telemetria quebra parsers do rv-platform | Conversor temporário NDJSON→formato atual; teste de aceitação da Sec. 9.11 |
| Mudança nos arms Python quebra o grid calibrado | Regeneração determinística + diff contra os 29 arms atuais; eco-vs-intenção pega qualquer divergência semântica |
| “Eviction cirúrgica” tocar caminho semântico não mapeado | Checagem de callers antes (rebuild/replay); paridade de sequência de ações depois |
| Escopo crescer (a tentação do framework) | R4 como gate de review: qualquer PR com IPC/persistência/async/registry genérico é cortado |
| SIGKILL perde o `StepRecord` em voo | perda máxima = 1 step por construção; `flushPendingStep` no teardown cobre todos os términos normais; aceitável contra o status quo (42,3% das runs perdiam o coverage dump inteiro) |
| As decisões do dono registradas nos relatórios (Q1/Q2/Q3) estarem desatualizadas | Confirmação de uma linha cada (Sec. 12) antes do estágio 3 |

---

## 12. Decisões registradas (todas as questões da seleção estão fechadas)

Decisões do dono, registradas em 2026-08-02 nesta discussão:

| # | Decisão | Conteúdo |
|---|---|---|
| D1 | **Eco = nível 0, definitivo** | O jar imprime `RUN_START` (plano efetivo + digest + versão do jar) como primeira linha do trace, write-only, **sem nenhuma validação automática e zero mudança no Python** — não há canal de comunicação novo. Auditoria de drift é pós-hoc, na análise, quando houver suspeita; nível 1 só seria reavaliado mediante incidente real |
| D2 | **Sink agrupado por step** | Um registro NDJSON por step (`StepRecord` fechado quando o outcome resolve, no step N+1), com envelope 1×, defaults omitidos, tabela de IDs para strings repetidas, `run_id` só nas bordas, serializer com escaping real + testes de round-trip, `flushPendingStep` no teardown e gzip na coleta. Substitui o modelo uma-linha-por-evento e o formato `key=value` |
| D3 | **Descope total do modo `ape` stock** | Nenhum trabalho de fidelidade upstream nesta rearquitetura (sem `StockApeAgent`, build duplo, reverts ou oráculo). Controle da campanha = preset `aperv` mínimo; a comparação com o APE original fica ancorada nos dados congelados da fase 2 |
| D4 | **Heartbeat no logcat desde o início** | Uma linha write-only por step (`s=N t=...`) via `Log.i`, atrás de flag (default on) — violação e step no mesmo arquivo/relógio, join imune a clock skew. Desligar é trivial |
| D5 | **Sem “contrato de saída”** | Rejeitada a validação de sentinela/exit-code no `tool.py` e qualquer mudança de status de task. `RUN_END` existe apenas como último registro natural do NDJSON (write-only, simétrico ao `RUN_START`). Identificar runs truncadas continua sendo tarefa pós-hoc da análise, por timestamps do trace/logcat — os dados já existem hoje |
| D6 | **Remover os leitores de `/sdcard`** | `ape.xpath`, `ape.xpath.actions` e `ape.strings` saem do jar (nenhum arm os usa; o `tool.py` nunca os empurra). A geração de strings de input é reescrita sobre o RNG semeável do `RunContext`, eliminando o `ThreadLocalRandom` residual (V23) |

Não restam questões abertas nesta seleção. Próximo passo natural do workflow: formalizar a mudança via OpenSpec (`/opsx:new` ou `/opsx:ff`) usando este documento como base da proposta.

---

*Relatório de brainstorming/seleção. Nenhum código foi alterado. Todos os claims usados como fundamento foram verificados no commit `5dcf225` por subagentes de leitura direta; claims refutados, desatualizados ou não sustentados estão listados na Sec. 3.2 e não fundamentam a seleção.*
