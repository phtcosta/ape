# Análise de Seleção — DeepSeek (deepseek-v4-flash-free)

**Fase:** Brainstorming / seleção de arquitetura de rearquitetura do APE-RV
**Data:** 2026-08-02
**Commit analisado:** `5dcf225` (HEAD, branch `master`)
**Inputs:** prompt original `docs/20260801_prompt_rearquitetura_aperv.md` + 8 relatórios LLM (`analise_deepseek-v4.md`, `analise_gemini-3.6-flash.md`, `analise_glm-5-2.md`, `analise_gpt-5.md`, `analise_kimi-k3.md`, `analise_ling-3-0-flash-free.md`, `analise_mimo-v2-5-free.md`, `analise_opencode_laguna_s_2_1_free.md`)
**Formato de referência:** `docs/analise_gpt-5-selecao.md`, `docs/analise_gemini-selecao.md`, `docs/analise_mimo-selecao.md`
**Deliverable:** este relatório (`docs/analise_deepseek-selecao.md`). Nenhum código foi alterado.

---

## 1. Resumo executivo

**Recomendação:** arquitetura **A + B**, em fases — *RunSpec + DecisionPipeline* como espinha dorsal do processo, com o **Feature Manifest compartilhado jar↔Python** como fonte única de verdade da matriz de modos/arms. As melhorias de memória/telemetria do candidato D são adotadas cirurgicamente como parte da implementação de A; o candidato C (linhagem de baseline por construção) é condicional; o candidato E (política ponderada / contrato fino) é **descartado** porque muda a semântica de preempção verificada no código.

A decisão foi tomada com base em **verificação direta no código em `5dcf225`** de cada claim estrutural usado (Sec. 3). Cinco organizadores alternativos foram sintetizados (Sec. 5). Todos respeitam a restrição central do usuário: **nada persistido entre sessões; cada run é limpa; o fim do processo é a última barreira contra vazamento de estado**. Nenhum candidato usa checkpoint, resume, WAL, journal recarregável ou `readGraph`.

---

## 2. Regras invioláveis

Herdadas do prompt e da verificação de código. São o contrato que qualquer candidato deve cumprir:

- **R1 — runs limpas:** nenhum estado sobrevive entre sessões. `Graph`, `Model`, RNG, caches, breakers e históricos pertencem ao contexto daquela run. O teardown fecha sinks; a próxima run começa do zero. Não há leitura de saída anterior.
- **R2 — processo descartável:** uma run por processo; a morte do processo é a barreira final contra `static` e caches globais (AndroidDevice tem 8 handles `public static` — `AndroidDevice.java:63-72`).
- **R3 — simplicidade:** nada de IPC/sockets, plugin frameworks, classloaders dinâmicos, event sourcing completo ou camadas de abstração que não paguem seu custo em teste/legibilidade.
- **R4 — sem read-back:** resultado nunca é input automático. Sem `readGraph`, sem resume.
- **R5 — configuração total e fail-fast:** nenhuma decisão comportamental depende de default implícito; chave desconhecida ou combinação inválida aborta antes do primeiro step.
- **R6 — determinismo observável:** seed, plano efetivo, versão, ordem dos estágios e digest são ecoados no início da run.
- **R7 — telemetria não decide:** o sink observa `Decision`/`Outcome`, não altera políticas.
- **R8 — bounded onde seguro:** estruturas de diagnóstico são limitadas; estruturas semanticamente necessárias só recebem limite após prova.
- **R9 — baselines fiéis:** `ape` e `ape_pure` devem reproduzir a exploração do APE stock (validade experimental).

---

## 3. Auditoria de claims das LLMs contra o código (commit `5dcf225`)

Cada claim estrutural que sustenta um candidato foi verificado diretamente. A tabela distingue **verificado**, **refutado** e **stale**.

### 3.1 Claims verificados e usados como evidência

| Claim | Verificação no código | Uso |
|---|---|---|
| Escada de precedência é declaração de `if` encadeada | `SataAgent.selectNewActionNonnull()` — `SataAgent.java:449-589`, ~141 LOC, com blocos guardados: budget (468), LLM new-state (480-481), LLM stagnation (493-494, 508-509), LLM random (533), launcher (523-525, `shouldFireLauncher` com 6 args), component trigger (546-551, *side-effect sem return*), cadeia SATA x7 copy-paste (553-587) | Motiva o `DecisionPipeline` (A) |
| Precondição do LLM está triplicada | Verificação da "estagnação" igual em 3 pontos (480-481, 493-494, 508-509) e contração do hook em 3 classes (`stagnationHookFired` em `StatefulAgent`, `SataAgent`, `State`) | Fonte de drift; pipeline unifica |
| Modo é sistema implícito no Python | `tool.py` — `APERV_PROPERTY_MAPPING` (75-162, 52 chaves), `ARM_DEFINING_KEYS` (171-192, 18 chaves), `get_variants` (427-659, 26 arms / 128 entradas) sem contrato com o jar | Motiva o Feature Manifest (B) |
| Factory de agente é if-chain com fallback silencioso | `ApeAgent.createAgent()` — `ApeAgent.java:68-96`; tipo desconhecido cai em `new SataAgent` (95); `System.exit(1)` só em replay sem log (91) | Fail-fast (R5) |
| Config é estática e global | `Config.java` — 502 LOC, 112 `public static` fields, init no static block (32-44), kill-switch `apePureMode` (36-43), 27 chaves em `rvForcedOffValues` (343-364), 3 `catch {}` vazios (453-454, 465-466, 477-478); sem detecção de chave desconhecida | `RunContext` (A), manifest (B) |
| Pipeline de scoring é fixo e config decorativa | `ScoringPipeline` — 7 passes (51-61); o param `Config` é decorativo (48-49); `[APE-ARCH] passes=[...]` logado na construção | Configuração explícita (A/B) |
| Retentores de memória sem bound | `Model.actionHistory` — `Model.java:136-137` (TODO OOM, sem eviction); `ModelAction` retém `resolvedGUITreeAction`/`resolvedTree` (80-90); `State.treeHistory` (54-58); `GUITreeBuilder.namingToGUITreeNodeCache` (693) não limpa em `release()` (707-715) | Bounds (R8) |
| Persistência legada está quebrada e é um *mismatch* de tipo | `StatefulAgent.saveGraph()` faz `oos.writeObject(model)` (`StatefulAgent.java:1866`) escrevendo um `Model`; `Graph.readGraph()` (`Graph.java:1166-1173`) tenta converter o objeto em `Graph` | **R1/R4: remover `saveGraph`/`readGraph` do protocolo e proibir carga** |
| Telemetria é stdout key=value ad-hoc | `[APE-STEP]` como linha única formatada (`StatefulAgent.java:1491+`), gated por `Config.stepTelemetryEnabled`; `Logger` 67 LOC, `System.out` apenas, `debug=false` em tempo de compilação (22) | Envelope tipado NDJSON (D) |
| Tempo é o limite primário e não-preemptivo | `Monkey.java:1291-1302` — checagem de deadline por `elapsedRealtime` sem preempção de step | Preservar; não substituir por checkpoints |
| Decisão LLM é um bloco gigante | `LlmRouter.selectAction()` — `LlmRouter.java:327-612` (~286 LOC), 5 params; predicados em 232, 249, 276 | Contrato tipado (A) |
| TOCTOU na geração de eventos | `MonkeySourceApe.generateEvents()` (822-914) — loop `refetchInfoCount`, guard `foreignActivityGuard` (849) | Preservar na migração (A) |
| Driver de agente é muito grande | `StatefulAgent.java` = 1904 LOC; `SataAgent.java` = 1762 LOC; `MonkeySourceApe.java` = 1467 LOC | Fatiamento (A/D) |

### 3.2 Claims refutados, parciais ou stale (não usados como fundamento)

| Claim | Veredito | Consequência |
|---|---|---|
| "Tipo de agente desconhecido aborta a run" | **falso** — cai em `new SataAgent` (`ApeAgent.java:95`); o `System.exit(1)` é específico de replay sem log | exigir erro explícito para tipo desconhecido (R5) |
| "Checkpoint/resume confere resiliência" | **incompatível** com runs limpas (R1/R4) | retry integral no supervisor; nunca continuação |
| "6 passes no pipeline" / "117 flags" | **stale/inconsistente** — há 7 passes (`ScoringPipeline.java:51-61`) e a contagem de flags varia entre relatórios | contagem exata não é fundamento arquitetural |
| "Graph tem 13 coleções" | **impreciso** — há 17+ containers top-level; o relevante é ausência de bounds | eviction como política, não contagem |
| "Nenhuma GUITree é liberada" | **forte demais** — há remoção pontual de árvore instável | correto: não existe política geral de retenção |
| "Three caches per-node" | **impreciso** — dois são por `GUITree`, um por `GUITreeNode` | corrigir alvo antes de política de eviction |
| "Event sourcing dá rastreabilidade sem memória" | **parcial** — dá rastreabilidade, mas reintroduz pressão de memória se o log for retido; e vira resiliência se persistido | usar log *por run, em memória, com bound* (D) |
| "Política como processo externo é leve" | **refutado por custo** — segundo app_process no emulador é caro e complica o deploy em rv-platform | manter política in-process (A), contrato tipado basta |

### 3.3 O que foi aproveitado e rejeitado de cada LLM

| Relatório | Aproveitado | Rejeitado/reduzido |
|---|---|---|
| DeepSeek | `StepPipeline`/plano explícito; capabilities validadas; **remover `saveGraph`/`readGraph`** | `TwoLineage` como default; matriz genérica como 2º sistema de config |
| Gemini | preset imutável; contexto injetado; separação guidance/explorador | hybrid decorators+manifest+async; checkpoint; baseline stock |
| GLM | fases com preempção clara; contrato tipado in-process; WPS como hipótese a testar | serviço externo; serialização por step; resume |
| GPT-5 | `RunPlan + Decision Kernel`; eventos tipados como slice | event sourcing completo; lane APE fossilizado obrigatório |
| Kimi | `RunSpec`; pipeline; evento ≠ projeção | epochs; journal recarregável e retomada |
| Ling | policy pipeline; modelo base × guidance | manifest compartilhado obrigatório em toda chave; LRU genérico; process-per-mode |
| MiMo | `FeatureContext`; cadeia de componentes; specs declarativas | EventBus geral; append-only history; checkpoint/resume; claims numéricos frágeis |
| Laguna | separação executor/policy; supervisão externa | Policy-as-external-program; EventSourced Explorer completo; worker com checkpoint |

---

## 4. Espaço de design mapeado

Os relatórios convergem em 5 eixos de organização. Cada candidato da Sec. 5 é um **organizador dominante** distinto — não variações da mesma ideia:

| Eixo | Pergunta que responde | Status quo (verificado) | Candidato |
|---|---|---|---|
| E1 Estrutura de decisão | Como a escada de precedência é expressa? | `if` encadeado, ordem = semântica (SataAgent:449-589) | A — DecisionPipeline |
| E2 Fronteira de configuração | Como modos/arms são definidos e validados? | Dicts Python sem contrato (tool.py:427-659) | B — Feature Manifest |
| E3 Fidelidade do baseline | Como `ape`/`ape_pure` ficam fiéis ao stock? | flags de kill-switch (Config:36-43) | C — Linhagem por build |
| E4 Ciclo de vida do estado | Como memória e rastreabilidade são governadas? | estruturas sem bound (Model:136-137) | D — Run-local bounded + NDJSON |
| E5 Execução da política | Onde mora a política de escolha de ação? | in-process, acoplada ao agente | E — Política ponderada / contrato fino |

---

## 5. Arquiteturas candidatas (3–5, com as sugestões selecionadas das LLMs embutidas)

### 5.1 Candidato A — RunSpec + DecisionPipeline (recomendado)

**Organizador (E1):** a ordem de precedência deixa de ser a ordem de `if`s e vira uma **sequência nomeada e inspecionável de estágios**. O estado mutável sai do `Config` estático para um `RunContext` por run. O plano da run é um `RunSpec` imutável.

```
launcher/supervisor (rv-platform)
  -> resolve + valida RunSpec (fail-fast R5)
  -> cria run_id/output exclusivo
  -> inicia app_process novo (R2)
       -> RunBootstrap
          -> RunContext novo (device, clock, RNG, model, graph, mopData, llm, counters, sinks)
          -> DecisionPipeline fixado pelo plano
          -> loop observe/decide/execute/record
       -> RUN_END + fechamento dos sinks
  -> valida resultado; se ausente/crash/timeout -> tentativa nova DESDE ZERO (R1)
```

- `RunSpec` = value object (não service locator): identidade (schema version, preset, run_id, apk/package, seed), base de exploração, policies habilitadas e params, MOP, LLM (ocasiões/dose/timeout/breaker/fallback), melhorias independentes, limites de memória/telemetria, outputs observacionais.
- `DecisionPipeline` = duas categorias: (1) **estágios preemptivos** em ordem forte — budget advisory, LLM new-state/stagnation/random, launcher, component trigger; (2) **fallback SATA** preservando a ordem e as regras atuais; `ScoringPipeline` aplica boosts antes da escolha. Cada estágio retorna uma soma pequena: `Skip`, `Select(action, source)` ou `SideEffectAndContinue` — isso captura a diferença hoje escondida entre LLM, launcher, trigger de componente (side-effect sem return, SataAgent:546-551) e boost de score.
- Modos = **aliases de planos**, não subclasses: `aperv`, `mop`, `llm`, `llm_mop` viram presets sobre o mesmo `RunSpec`. Widget-MOP, frontier, frontier∩MOP, launcher, component trigger e MODEL_MENU são **features ortogonais**, não modos.
- Telemetria via `EventSink` NDJSON (D).

**O que absorve das LLMs:** `StepPipeline` (DeepSeek C1), `Compiled Run Plan + Decision Kernel` (GPT-5 C1), `RunSpec + Decision Pipeline` (Kimi C1), `DPMA` (Gemini C1), fases com preempção clara (GLM C1), policy pipeline (Ling C1).

**Custo:** médio. Exige refatorar `selectNewActionNonnull` em estágios nomeados e `RunContext`, mas sem mudar semântica de decisão — portanto preservável via testes de oráculo.

**Riscos:** regressão da ordem de preempção durante a migração; mitigado por teste de paridade por modo (Sec. 7.2).

### 5.2 Candidato B — Feature Manifest compartilhado jar↔Python

**Organizador (E2):** o maior defeito arquitetural verificado é o *split-brain*: os modos existem como dicts no Python (`tool.py:427-659`, 26 arms / 128 entradas) e como flags no Java (`Config.java`), sem contrato entre eles. O candidato B torna a **definição da matriz de modos um único artefato declarativo** lido pelos dois lados.

- Um `manifest` (JSON ou YAML) declara: features atômicas, dependências entre elas, presets (nomes de modo → vetor de ativação) e validações (ex.: frontier exige WTG/MOP; menu-gateway exige MODEL_MENU).
- O jar lê o manifest para montar o `RunSpec` e validar (R5). O Python gera os arms a partir do manifest em vez de hard-codar `get_variants` — elimina os 128 nomes e as chaves de ablação espalhadas.
- Ablações = override explícito de features no vetor, nunca modo novo.
- Não é um "solver de capabilities": validações diretas e tipadas bastam (rejeita o `CapabilityEngine` genérico de DeepSeek C2).

**O que absorve:** preset feature manifest (Gemini DPMA C1), feature surface atômica (MiMo C1), manifest compartilhado (Ling C1), capability matrix (DeepSeek C2).

**Custo:** médio. Exige um artefato novo + mudança no `tool.py` (o par mais sensível da pesquisa). O benefício é medido em ablações sem drift.

**Riscos:** tocar no Python de produção exige cuidado com o grid de 21.681 tarefas já calibrado; mitigado por regeneração determinística dos arms e diff contra os atuais.

### 5.3 Candidato C — Linhagem de baseline por construção

**Organizador (E3):** fidelidade de `ape`/`ape_pure` hoje é garantida por kill-switch de flags (`Config.java:36-43`, 27 chaves). Isso é frágil: toda nova feature precisa ser registrada no kill-switch ou vaza para o baseline. O candidato C torna a fidelidade uma **propriedade de build**, não de configuração: o artefato stock (APE upstream, intocado) é uma lane; o artefato APE-RV é outra. `ape` executa o artefato stock; os demais modos executam o RV.

**O que absorve:** `TwoLineage` (DeepSeek C3), lane APE fossilizado (GPT-5 C2), baseline byte-identical (Kimi C3), separação de linhagem (Gemini).

**Custo:** baixo-médio. O principal custo é operacional: build duplo e deploy em rv-platform (o `ape-rv.jar` único hoje).

**Riscos:** fere a simplicidade (R3) e adiciona um artefato que o prompt explicitamente diz não querer ("não criar linhagem/build extra para isso"). **É o candidato mais descartável.** Só vale se a auditoria de paridade mostrar drift incurável no kill-switch. Recomendo: avaliar primeiro a paridade de A; adotar C apenas como último recurso.

### 5.4 Candidato D — Run-local bounded + telemetria tipada

**Organizador (E4):** memória e rastreabilidade são governadas por política explícita, mantendo R1. Adota os *wins* de memória observados (Sem 3.1) sem recorrer a persistência:

- Eviction de `GUITree`/`ModelAction` com bound por run (`actionHistory` é hoje `ArrayList` sem eviction, `Model.java:136-137`); caches do `GUITreeBuilder` passam a ser limpos no mesmo ciclo do `release()`.
- Telemetria vira **envelope NDJSON por run** com identidade (`run_id`, `step_id`, `monotonic_time`, `event_type`, `config_digest`, `state_id`, `action_id`, `decision_source`, `stage`, `candidate_scores`, `outcome`, `failure`) em arquivo exclusivo da run — substitui o stdout ad-hoc `[APE-STEP]` (StatefulAgent:1491+) sem mudar o que é coletado.
- Rejeita explicitamente: journal recarregável (Kimi C2), append-only history (MiMo), log-is-truth (Laguna X1) — todos reintroduzem pressão de memória ou resiliência incompatível com R1.

**O que absorve:** bounded working sets + envelope tipado (GPT-5 C3, Kimi C4, MiMo R9, Gemini telemetry).

**Custo:** baixo (eviction local) + médio (migração de telemetria). **Nenhum custo de semântica de exploração** — é o candidato de menor risco.

**Riscos:** mudar o formato de telemetria pode quebrar parsers do rv-platform; mitigado por conversor temporário.

### 5.5 Candidato E — Política ponderada (WPS) / contrato fino

**Organizador (E5):** a escolha de ação vira uma **amostragem ponderada** sobre `ActionCandidate`s tipados, com `WeightSource`s contribuintes (MOP, frontier, coverage, menu, LLM, …) e modos = vetores de pesos (GLM C1 WPS). Alternativa mais extrema: política fora do jar (Laguna X2, GLM EDC), com o jar como executor "burro" — estilo Fastbot2.

**O que absorve:** Weighted-Policy Sampler (GLM C1), policy-external (Laguna X2), contrato fino.

**Custo:** alto. Altera a **semântica de preempção** verificada no código: hoje LLM *preempta* launcher (SataAgent:480-509 antes de 523-525), e component trigger é *side-effect sem retorno* (546-551), não um candidato pontuável. Transformar em pesos muda o comportamento observável das baselines — inaceitável sem reprovação experimental.

**Riscos:** invalida a comparação com o grid histórico (21.681 tarefas). **É o candidato descartado** — mantido aqui apenas como registro da hipótese (GLM a defendeu; a verificação de código a contraria).

---

## 6. Comparação e avaliação

| Critério | A — DecisionPipeline | B — Feature Manifest | C — Linhagem por build | D — Run-local bounded | E — WPS/contrato fino |
|---|---|---|---|---|---|
| Ataca o problema central (escada de `if`s) | **sim** | parcial | não | não | sim (mas muda semântica) |
| Ataca o split-brain Python↔Java | não | **sim** | parcial | não | não |
| Preserva baselines `ape`/`ape_pure` | sim (paridade testável) | sim | sim (por construção) | sim | **não** |
| Simplicidade (R3) | alta | média | baixa | alta | baixa |
| Custo de migração | médio | médio | médio | baixo | alto |
| Risco experimental (grid 21.681) | baixo | médio (toca Python) | baixo | baixo | **alto** |
| Compatível com R1/R4 (runs limpas) | sim | sim | sim | sim | sim |
| Pronto para ablações | sim | **sim** | não | sim | sim |

## 7. Recomendação e plano de adoção

### 7.1 Decisão

- **Adotar A** como espinha dorsal: `RunSpec` + `DecisionPipeline` + `RunContext`, preservando ordem e semântica atuais de decisão. Os 5 modos viram aliases de planos.
- **Adotar B** logo em seguida: manifest compartilhado como fonte de verdade da matriz de modos/arms, com regeneração determinística do Python.
- **Adotar D** cirurgicamente dentro de A: eviction por run + telemetria NDJSON tipada com `run_id`.
- **C condicional:** apenas se a paridade de A (7.2) revelar drift incurável.
- **E rejeitado:** mudaria preempção e invalidaria o grid histórico.

### 7.2 Próximos passos (nesta ordem)

1. **Oráculo de paridade por modo:** rodar A contra o comportamento atual nos 5 modos (métricas de cobertura, sequência de decisão `decision_source`) para provar equivalência. *Gate* para avançar.
2. **Remover `saveGraph`/`readGraph`** do protocolo e proibir carga (`StatefulAgent.java:1855-1866`, `Graph.java:1166-1173`); manter apenas outputs observacionais.
3. **Fatiar `selectNewActionNonnull`** (`SataAgent.java:449-589`) em estágios nomeados do pipeline, começando pelos estágios preemptivos.
4. **Criar o Feature Manifest** (features, dependências, presets) e regenerar os arms do `tool.py` por diff contra os 26 atuais.
5. **Migrar telemetria** para envelope NDJSON por run, com conversor temporário para os parsers do rv-platform.

### 7.3 Arquitetura-alvo (A+B+D)

```text
manifest (features/presets)  ----> Python get_variants (gerado) + validação de arms
                              \--> jar: RunSpec → DecisionPipeline
RunSpec ──► RunBootstrap ──► RunContext ──► [budget | LLM | launcher | component trigger | SATA]
RunContext ──► EventSink (NDJSON, run_id) ──► rv-platform (coleta/resultados)
run termina ─► fechamento; nada persistido; retry integral no supervisor
```

## 8. Riscos e mitigação

| Risco | Mitigação |
|---|---|
| Regressão de ordem de preempção na migração de A | oráculo de paridade (7.2.1) antes de qualquer refactor |
| Mudança em `tool.py` quebra o grid calibrado | regeneração determinística + diff; feature flags por arm |
| Formato de telemetria novo quebra parsers | conversor temporário; envelope tipado com `schema` version |
| Eviction altera cobertura medida | bound conservador + métrica de impacto antes/depois |
| Escopo crescer (over-engineering) | R3 como gate de review: qualquer candidato com IPC/event-sourcing/persistence é cortado |

---

*Relatório de brainstorming. Nenhum código foi alterado neste workspace. Todos os claims estruturais citados foram verificados no commit `5dcf225`.*
