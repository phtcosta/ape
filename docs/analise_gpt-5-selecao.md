# Seleção arquitetural para a refatoração do APE-RV

**Data:** 2026-08-01  
**Status:** brainstorming arquitetural; nenhuma implementação é proposta como já decidida  
**Escopo:** APE-RV; o APE default/puro não é usado e não orienta esta seleção

## 1. Conclusão executiva

A melhor direção é uma arquitetura pequena, síncrona e orientada a uma execução descartável:

1. um **`RunSpec` obrigatório, imutável e validado** descreve completamente a execução;
2. um **processo `app_process` novo por run** cria um `RunContext` e todo o estado em memória do zero;
3. um **pipeline explícito de decisão**, com precedência forte, substitui a ordem incidental de `if`s;
4. o `ScoringPipeline` existente continua como subpipeline de pontuação, mas passa a receber configuração real em vez de ler `Config` estático;
5. telemetria estruturada é somente saída, identificada por `run_id` e `step_id`; nunca é relida pelo explorador;
6. falha invalida a tentativa inteira; o supervisor inicia outra run limpa. Não há checkpoint, resume, WAL ou modelo carregado da tentativa anterior.

Esta é a **Arquitetura A — RunSpec + Fresh Run + Decision Pipeline**, recomendada. Ela combina as partes comprovadamente úteis das propostas `Compiled Run Plan`, `StepPipeline`, `Phase-Machine`, `Feature Manifest` e `Base × Guidance`, sem incorporar seus frameworks, manifests duplicados, event sourcing ou mecanismos de retomada.

Quatro alternativas são mantidas para o brainstorming:

| Opção | Ideia central | Avaliação |
|---|---|---|
| **A. RunSpec + Fresh Run + Decision Pipeline** | plano imutável, processo descartável, pipeline com precedência explícita | **recomendada** |
| **B. Session Object minimalista** | contexto da run e uma lista fixa de políticas, sem catálogo genérico de features | melhor alternativa se o conjunto de mecanismos estabilizar agora |
| **C. Base Explorer × Guidance Stack** | base e orientações são eixos ortogonais do `RunSpec` | útil se a matriz de ablação crescer; mais abstrações |
| **D. Bounded In-Memory Run Kernel** | estado operacional separado de observações por IDs e working sets limitados | possível evolução de A; só após medir memória |

As quatro respeitam a regra central: **uma run nunca herda estado operacional de outra**.

## 2. Regra de isolamento: o que “run limpa” significa

Há uma distinção indispensável:

- **resultado persistente é permitido:** trace, logcat, métricas, configuração efetiva e artefatos necessários à análise científica;
- **estado operacional persistente é proibido:** grafo, modelo, caches, histórico de decisão, checkpoint, replay automático ou qualquer arquivo que influencie uma run posterior.

O contrato recomendado é:

1. cada tentativa recebe `run_id`, seed, APK, `RunSpec` resolvido e diretório de saída exclusivos;
2. o launcher remove ou rejeita entradas globais residuais antes de iniciar;
3. nasce um processo JVM/Dalvik novo; não se tenta “resetar” todos os `static`s;
4. o explorador não descobre configuração em paths globais e não aceita `modelFile` ou checkpoint;
5. todos os artefatos são abertos com semântica create-new/truncate dentro do namespace da run;
6. o explorador somente escreve resultados; nenhuma run lê o diretório de outra;
7. sucesso exige marcador terminal íntegro; crash/truncamento torna a tentativa inválida;
8. retry cria outra tentativa desde o passo zero, com novo processo e novo diretório;
9. a tentativa anterior pode ser preservada para diagnóstico, mas nunca combinada com a nova como se fosse uma única amostra.

Esse contrato é mais simples e cientificamente mais claro que checkpoint/resume. Ele também resolve os caches estáticos por fronteira de processo, sem uma coleção frágil de métodos `reset()`.

## 3. Verificação dos claims dos relatórios

### 3.1 Claims confirmados

Os pontos abaixo foram conferidos no código atual e podem fundamentar decisões.

#### Configuração e modos

- A seleção do agente é uma factory baseada em string; um valor desconhecido cai silenciosamente em `SataAgent`. `System.exit(1)` ocorre apenas em `replay` sem replay log, não para todo tipo inválido (`ApeAgent.java:68-95`). Portanto, os relatórios acertam o problema de validação, mas alguns descrevem incorretamente a falha.
- `Config` é global, estático e efetivamente congelado durante a inicialização. Também lê arquivos persistentes em `/data/local/tmp/ape.properties` e `/sdcard/ape.properties` (`Config.java:30-43,289-298`). Valores numéricos malformados caem silenciosamente no default (`Config.java:448-481`). Um plano obrigatório e fail-fast é justificado.
- O kill-switch atual força 27 valores e remove dois paths (`Config.java:343-369`). Isso não torna relevante a construção de uma linhagem “APE puro”: por decisão de escopo, o APE default não será usado.

#### Seleção e precedência

- `SataAgent.selectNewActionNonnull` concentra aproximadamente 141 linhas (`SataAgent.java:449-589`). A ordem real é budget → três hooks LLM → launcher → trigger de componente → sete tentativas SATA.
- A precedência é semântica, não mero detalhe: quando elegível e bem-sucedido, LLM preempta launcher; o trigger de componente produz side effect e continua; budget é apenas aconselhamento. Logo, um sampler único com pesos não reproduz tudo corretamente.
- O `ScoringPipeline` já demonstra que passes ordenados funcionam (`ScoringPipeline.java:21-94`), mas o parâmetro `Config cfg` de `fromConfig` não é usado e o único caller passa `null` (`StatefulAgent.java:208`). Seus sete passes ainda consultam configuração global. A abstração é boa; a injeção é hoje apenas nominal.

#### Memória

- `Model.actionHistory` cresce por append e cada registro pode reter `GUITreeAction` (`Model.java:62-95,136-173`).
- `State.treeHistory` também cresce sem uma política geral de limite (`State.java:56,400-415`).
- `Graph` mantém múltiplos mapas, sets e históricos durante toda a run (`Graph.java:98-130`), inclusive `treeTransitionHistory`.
- `GUITreeBuilder.namingToGUITreeNodeCache` não é limpo pelo `release` que limpa os outros dois caches (`GUITreeBuilder.java:670-715`).

Isso comprova risco de retenção e ownership difuso. Não comprova que OOM ocorrerá em toda run, nem identifica qual raiz domina. LRU indiscriminado pode mudar refinamento, backtracking e reprodutibilidade; limites devem ser semânticos e precedidos de medição.

#### Persistência atual

- `saveObjModel`, `saveVisGraph` e `saveStates` são default-on (`Config.java:63-69`).
- O teardown grava `sataModel.obj`, visualização, estados e action history (`StatefulAgent.java:1802-1813,1850-1900`); `produce.log` e `consume.log` também são criados (`MonkeySourceApe.java:269-285`).
- `--ape-model` habilita carga (`Monkey.java:925-927`; `ApeAgent.java:68-77`). Há ainda inputs globais como `/sdcard/ape.xpath`, `/sdcard/ape.xpath.actions`, `/sdcard/ape.strings`, catálogo de broadcasts e o JSON MOP.
- O saver serializa um `Model` (`StatefulAgent.java:1863-1867`), enquanto `Graph.readGraph` tenta converter o objeto em `Graph` (`Graph.java:1166-1173`). O mismatch é real.

Sob o requisito atual, a conclusão não é consertar o resume: é **retirar o modelo serializado do protocolo de execução e proibir sua carga**.

#### Observabilidade e teardown

- A telemetria usa `System.out` via `Logger` e os eventos de step/outcome são espalhados. Há informação útil, mas não um envelope tipado único com identidade da run.
- O teardown isola suas etapas com captura de `Throwable` (`StatefulAgent.java:1794-1813`). Essa qualidade deve ser preservada para fechar resultados; ela não justifica persistir estado resumível.

### 3.2 Claims parciais, incorretos ou não verificados

| Claim encontrado | Veredito | Consequência |
|---|---|---|
| “tipo de agente desconhecido causa `System.exit(1)`” | **falso**; cai em SATA, e o exit é específico de replay sem log | exigir erro explícito para tipo desconhecido |
| “o pipeline tem seis passes” | **stale**; há sete em `ScoringPipeline.java:53-59` | não basear design em contagens documentais antigas |
| “há 117 flags” | **inconsistente**; um mesmo relatório também afirma 112 | número exato não é fundamento arquitetural |
| “Graph tem 13 coleções” | **stale/depende do critério**; há pelo menos 17 containers top-level | o claim relevante é ausência de bounds, não a contagem |
| “três caches per-node” | **impreciso**; dois são por `GUITree`, um por `GUITreeNode` | corrigir o alvo antes de qualquer política de eviction |
| “nenhuma `GUITree` é liberada” | **forte demais**; há remoção pontual de árvore instável | correto: não existe política geral de retenção limitada |
| “checkpoint/resume atende resiliência” | **incompatível** com runs limpas | retry integral no supervisor |
| números de arms, testes Python e taxas do repositório irmão | **não verificados neste workspace** | não usados para escolher arquitetura |
| “APE pure é fiel ao upstream” | **não provado** e fora do escopo atual | não criar linhagem/build extra para isso |

### 3.3 O que foi aproveitado de cada LLM

| Relatório | Aproveitado | Rejeitado ou reduzido |
|---|---|---|
| DeepSeek | `StepPipeline`, plano explícito, validação de capabilities | `TwoLineage`; matriz genérica como segundo sistema de configuração |
| Gemini | preset imutável, contexto injetado, separação de guidance | híbrido decorators + manifest + async; checkpoint; baseline stock |
| GLM | fases com hard preemption; contrato tipado in-process | serviço externo, serialização por step e resume |
| GPT-5 | `Compiled Run Plan + Decision Kernel`; eventos tipados como slice | event sourcing completo; lane de APE fossilizado |
| Kimi | `RunSpec`, pipeline e distinção entre evento e projeção | epochs, journal recarregável e retomada |
| Ling | policy pipeline e modelo base × guidance | manifest compartilhado obrigatório, LRU genérico, process-per-mode |
| MiMo | `FeatureContext`, cadeia de componentes, specs declarativas | EventBus geral, append-only history, checkpoint/resume; claims numéricos frágeis |
| Laguna | separação executor/policy e supervisão | Policy-as-external-program e EventSourced Explorer completos; checkpointed worker |

## 4. Princípios comuns às quatro opções

Antes das diferenças, todas as arquiteturas candidatas devem obedecer a estas invariantes:

- **R1 — configuração total:** nenhuma decisão comportamental depende de default implícito do APE ou de arquivo global descoberto;
- **R2 — fail-fast:** chave desconhecida, tipo inválido, dependência ausente, combinação proibida ou path faltante abortam antes do primeiro step;
- **R3 — estado por run:** `Graph`, `Model`, RNG, histories, caches e breakers pertencem ao `RunContext` daquela run;
- **R4 — processo descartável:** uma run por processo; o fim do processo é a última barreira contra vazamento de `static`;
- **R5 — sem read-back:** resultado nunca é input automático;
- **R6 — ordem explícita:** preempção e fallback aparecem em uma estrutura inspecionável e testável;
- **R7 — determinismo observável:** seed, plano efetivo, versão, ordem dos estágios e digest são ecoados no início;
- **R8 — telemetria não decide:** o sink observa `Decision` e `Outcome`, mas não altera políticas;
- **R9 — bounded onde seguro:** dados diagnósticos usam IDs/records compactos; estruturas semanticamente necessárias só recebem limite após prova de segurança;
- **R10 — retry integral:** crash, OOM, ausência de `RUN_END` ou timeout externo inválido geram tentativa nova, nunca continuação.

## 5. Arquitetura A — RunSpec + Fresh Run + Decision Pipeline (recomendada)

### 5.1 Princípio organizador

Uma run é a execução de um plano imutável dentro de um processo descartável. O plano escolhe mecanismos; o pipeline torna explícita a ordem; o contexto contém todo o estado mutável.

```text
launcher/supervisor
  -> resolve + valida RunSpec
  -> cria run_id/output exclusivo
  -> inicia app_process novo
       -> RunBootstrap
          -> RunContext novo
          -> DecisionPipeline fixo para o plano
          -> loop observe/decide/execute/record
       -> RUN_END + fechamento dos sinks
  -> valida resultado ou agenda tentativa nova desde zero
```

### 5.2 Abstrações mínimas

`RunSpec` deve ser value object, não service locator. Campos conceituais:

- identidade: schema version, preset informativo, run ID, APK/package e seed;
- base de exploração APE-RV;
- policies habilitadas e parâmetros;
- MOP: input explícito, widget/direct/transitive, menu, WTG, frontier genérico, frontier∩MOP, launcher e component trigger;
- LLM: ocasiões, dose, timeout, breaker e fallback explícito;
- melhorias independentes: coverage, budget, forms, typed input, menu, guards;
- limites de memória/telemetria;
- outputs permitidos, todos observacionais.

`RunContext` contém device, clock, RNG, model, graph, MOP data, LLM client, counters, histories e event sink. Nada consulta `Config` estático depois do bootstrap.

`DecisionPipeline` tem duas categorias claras:

1. **stages preemptivos**, em ordem forte: budget advisory, LLM new-state/stagnation/random, launcher e component trigger;
2. **fallback SATA**, preservando a ordem e as regras atuais; antes da escolha, o `ScoringPipeline` aplica os boosts habilitados.

Cada stage retorna uma soma pequena: `Skip`, `Select(action, source)` ou `SideEffectAndContinue`. Isso captura a diferença hoje escondida entre LLM, launcher, trigger de componente e boost de score.

### 5.3 Presets e features

Os nomes `aperv`, `mop`, `llm` e `llm_mop` devem ser **aliases de planos**, não subclasses nem modes espalhados. O plano efetivo é a autoridade e sempre é ecoado.

Modelo recomendado:

- `aperv`: base APE-RV + melhorias independentes, sem input MOP e sem LLM;
- `mop`: `aperv` + guidance MOP;
- `llm`: `aperv` + LLM; fallback explícito para o pipeline APE-RV;
- `llm_mop`: `aperv` + MOP + LLM; se LLM declina/falha, continua no pipeline MOP/APERV configurado.

Widget MOP, frontier genérico, frontier∩MOP, launcher e component trigger são **features ortogonais**, não novos modes. Presets podem agrupá-los; ablações os sobrescrevem explicitamente.

O plano deve declarar dependências, por exemplo: frontier exige WTG/MOP input; menu-gateway exige model-menu; LLM occasion exige endpoint/model. Não é necessário um solver de capabilities: validações diretas e tipadas são suficientes.

### 5.4 Telemetria

Um `EventSink` recebe DTOs tipados e emite NDJSON/stdout ou um arquivo exclusivo da run. Envelope mínimo:

```text
schema, run_id, step_id, monotonic_time, event_type,
config_digest, state_id, action_id, decision_source, stage,
candidate_scores, outcome, failure
```

`RUN_START` contém o plano efetivo; `DECISION` explica candidatos e fonte; `OUTCOME` correlaciona o resultado; `RUN_END` contém motivo e contagens. O host junta isso ao logcat por `run_id`/`step_id` ou heartbeat. O journal não é fonte de verdade operacional e nunca é replayado.

### 5.5 Pontos fortes e riscos

Pontos fortes:

- resolve diretamente spaghetti, configuração, testabilidade e isolamento;
- preserva hard precedence sem transformar tudo em pesos;
- reaproveita o `ScoringPipeline` já validado conceitualmente;
- não requer plugin framework, EventBus, processos auxiliares ou schema de checkpoint;
- presets e ablações usam a mesma representação.

Riscos:

- migrar todos os reads de `Config` exige disciplina;
- pipeline excessivamente granular pode virar cerimônia;
- bounds de memória continuam sendo um trabalho separado e orientado por medidas.

### 5.6 Quando escolher

Escolher A se se deseja o melhor equilíbrio entre extensibilidade, transparência científica e simplicidade. É a recomendação atual.

## 6. Arquitetura B — Session Object minimalista

### 6.1 Princípio organizador

Esta é a alternativa mais conservadora. Não há framework de features nem catálogo declarativo genérico. O bootstrap valida uma configuração tipada, cria `Session`, e `SataAgent` recebe uma lista fixa de policies conhecidas.

```text
Session
  config + random + clock + graph + model + telemetry
  policies = [budget, llm, launcher, component, sata]
```

### 6.2 Diferença para A

- presets são métodos/factories compilados, não entidades de uma registry;
- dependências são validadas diretamente no construtor;
- a lista de policies é fixa; enablement fica em cada instância;
- não se modela “feature” como conceito universal.

### 6.3 Vantagens

- menor quantidade de tipos e metadados;
- caminho curto para remover `Config` estático e tornar precedência testável;
- excelente se o conjunto de mecanismos estiver próximo de fechado;
- dificulta configuração dinâmica acidental.

### 6.4 Desvantagens

- cada novo mecanismo requer editar bootstrap/factory;
- geração de matrizes de ablação é menos automática;
- configuração efetiva precisa de serialização explícita para não voltar a ficar opaca;
- pode recriar condicionais no assembler se crescer sem disciplina.

### 6.5 Quando escolher

Escolher B se a prioridade máxima for minimizar abstrações e houver expectativa de poucas features novas. Ela é a alternativa real à recomendação, não uma versão inferior: sacrifica extensibilidade para reduzir superfície conceitual.

## 7. Arquitetura C — Base Explorer × Guidance Stack

### 7.1 Princípio organizador

O sistema é descrito em dois eixos no `RunSpec`:

```text
BaseExplorer = aperv
GuidanceStack = [mop_widget, mop_frontier, llm]
```

O eixo base contém exploração independente; a stack contém mecanismos que orientam ou preemptam. Os modes conhecidos são presets sobre o produto desses eixos.

### 7.2 Estrutura

- `BaseExplorer` é responsável por observar, manter o modelo e fornecer o fallback SATA;
- `GuidancePolicy` pode ajustar scores, propor ação ou preemptar;
- um `GuidanceOrchestrator` ordena policies e explicita fallback;
- o processo e o `RunContext` continuam descartáveis.

Não se recomenda implementar isso com uma cadeia profunda de decorators. Composição deve ser dados + uma lista plana, pois decorators ocultariam precedência e dificultariam trace.

### 7.3 Vantagens

- representa naturalmente `llm_on_aperv`, `mop_on_aperv` e combinações;
- widget/frontier/launcher tornam-se eixos de ablação sem criar classes por mode;
- separa bem melhorias gerais de mecanismos estudados;
- favorece matriz experimental gerada do mesmo `RunSpec`.

### 7.4 Desvantagens

- “base” versus “guidance” nem sempre é inequívoco: coverage e budget podem ser classificados dos dois lados;
- mais interfaces que A/B;
- interações entre policies ainda precisam de ordem forte e validação; uma stack não resolve conflitos sozinha;
- não agrega valor se apenas quatro presets forem usados.

### 7.5 Quando escolher

Escolher C se o objetivo experimental priorizar muitas combinações e interações de features. Mesmo em A, recomenda-se usar base × guidance como **modelo mental do `RunSpec`**, sem necessariamente criar toda essa hierarquia.

## 8. Arquitetura D — Bounded In-Memory Run Kernel

### 8.1 Princípio organizador

Essa opção trata memória como o eixo principal: o core mantém apenas o working set semanticamente necessário, e records observacionais compactos usam IDs em vez de referências a objetos ricos.

```text
live model/graph working set
        | stable IDs
        v
compact DecisionRecord / OutcomeRecord -> EventSink write-only
```

Não é event sourcing: o log não reconstrói a run, não é autoridade e não é relido.

### 8.2 Estratégias possíveis

- `ActionRecord` deixa de reter `GUITreeAction`/árvore e guarda IDs + snapshot mínimo;
- histories diagnósticos usam ring buffer quando não participam de decisões;
- caches têm owner e lifetime explícitos;
- árvores antigas liberam payload pesado quando a semântica permite;
- estruturas usadas por refinement só recebem bounds acompanhados de invariantes e testes de equivalência.

### 8.3 Vantagens

- enfrenta diretamente retenção acidental;
- reduz custo de teardown e serialização desnecessária;
- combina bem com A ou B;
- mantém estado exclusivamente em memória da run.

### 8.4 Desvantagens

- é a opção de maior risco sem heap profile;
- eviction pode alterar comportamento científico;
- exige entender profundamente naming/refinement e backtracking;
- não resolve sozinha configuração ou spaghetti.

### 8.5 Quando escolher

Não escolher D como primeira refatoração. Adotar seus princípios seletivamente após medir heap por tipo/retention root. Se `actionHistory` dominar, records compactos podem ser um ajuste pequeno; se refinement exigir todas as árvores, o desenho precisará preservar essa semântica.

## 9. Opções eliminadas

### Event-sourced explorer, WAL, checkpoint e resume

Eliminados porque fazem do estado persistido parte do mecanismo operacional. Mesmo um event log elegante cria schema evolution, replay, snapshots, consistência e recuperação parcial — complexidade que contradiz runs limpas e não é necessária para atribuição científica. Eventos tipados são aproveitados apenas como observabilidade write-only.

### Supervised checkpointed worker / epochs

Eliminado. O supervisor deve supervisionar tentativas, não continuar uma amostra parcial. O retry correto descarta a tentativa anterior e inicia uma nova.

### TwoLineage, Stock Lane e Process-per-mode

Eliminados porque resolvem principalmente fidelidade ao APE upstream/default, explicitamente fora do escopo, ao custo de builds, classpaths e caminhos de manutenção adicionais.

### Policy-as-external-program / serviço de decisão

Eliminado como arquitetura principal. Serializar cada observação e decisão ou interpretar uma DSL externa adiciona failure domain, versionamento e overhead. Um contrato tipado in-process obtém a testabilidade necessária.

### EventBus geral e LLM assíncrono como fundação

Eliminados por concorrência, nondeterminismo e precedência menos clara. O cliente LLM pode ter timeout e circuit breaker isolados, mas a decisão deve continuar síncrona para que seed, ordem e causalidade sejam reproduzíveis.

### Manifest compartilhado obrigatório entre Python e Java

Não é necessário. Um arquivo compartilhado parece eliminar drift, mas cria autoridade e deployment cross-repo. Mais simples: o launcher envia um plano versionado; o jar valida, resolve e ecoa configuração efetiva + digest; o coletor rejeita divergência. Se um formato for usado, deve haver um schema simples e uma autoridade clara, não duas interpretações.

## 10. Comparação

Escala 1–5; pesos refletem o escopo atualizado, no qual APE puro não é requisito e fresh-run é obrigatório.

| Critério | Peso | A | B | C | D |
|---|---:|---:|---:|---:|---:|
| isolamento entre runs | 5 | **5** | **5** | **5** | **5** |
| simplicidade | 5 | 4 | **5** | 3 | 3 |
| precedência/clareza da decisão | 5 | **5** | 4 | 4 | 3 |
| configuração fail-fast | 4 | **5** | 4 | **5** | 3 |
| testabilidade | 4 | **5** | 4 | **5** | 4 |
| ablação e extensibilidade | 4 | 4 | 3 | **5** | 3 |
| memória/throughput | 3 | 3 | 3 | 3 | **5** |
| traceabilidade | 4 | **5** | 4 | **5** | 5 |
| risco de adoção | 4 | 4 | **5** | 3 | 2 |
| adequação geral | — | **melhor equilíbrio** | mínima | experimental | evolução especializada |

O ranking não deve ser interpretado como precisão matemática. A decisão qualitativa é:

- **A** se flexibilidade e clareza precisam coexistir;
- **B** se o sistema praticamente parou de crescer;
- **C** se o desenho experimental combinatório dominar;
- **D** como evolução baseada em perfil, não como reescrita inicial.

## 11. Testes arquiteturais que qualquer opção deve prever

Mesmo nesta fase de brainstorming, estes testes definem o que significaria uma arquitetura correta:

1. **isolamento A→B:** executar A e depois B; B deve produzir a mesma sequência que B isolada, dadas as mesmas entradas e seed;
2. **proibição de reads:** instrumentar filesystem e provar que a run B não lê diretório/artefatos de A;
3. **process boundary:** duas runs nunca compartilham PID/JVM nem caches estáticos;
4. **config total:** unknown key, default ausente, path residual ou número inválido falham antes do step 1;
5. **precedência golden:** estados sintéticos exercitam simultaneamente LLM, launcher, component e SATA e confirmam a ordem declarada;
6. **fallback:** decline, timeout e breaker do LLM produzem exatamente o fallback configurado;
7. **plan echo:** digest enviado, efetivo e coletado devem coincidir;
8. **truncamento:** ausência de `RUN_END` nunca é classificada como sucesso;
9. **telemetria neutra:** com sink ligado/desligado, mesma seed produz mesmas decisões;
10. **memory semantics:** qualquer bound novo passa por action-sequence parity e invariantes de refinement.

## 12. Questões que ainda merecem experimento antes de decidir

1. Qual raiz domina o heap em runs de 600 s: `actionHistory`, árvores/cache ou Graph histories?
2. Qual o custo p50/p99 de NDJSON por step no device?
3. Quantas features/combinações realmente entrarão na campanha? Se forem poucas, B ganha atratividade; se forem muitas, C.
4. Quais dados do histórico LLM são necessários para prompt e breaker, e quais são apenas diagnósticos?
5. Um plano completo cabe confortavelmente em propriedades/JSON tipado simples, ou exige representação mais rica? A hipótese inicial é que JSON simples basta.
6. O join logcat↔step é robusto apenas com `run_id`/`step_id`, ou precisa também de timestamp monotônico/heartbeat?

## 13. Recomendação final

Adotar como norte de discussão a **Arquitetura A**, com este recorte deliberado:

- `RunSpec` obrigatório e sem defaults comportamentais implícitos;
- `RunContext` novo e processo descartável por tentativa;
- pipeline plano, síncrono, com hard preemption explícita;
- `ScoringPipeline` como subpipeline, recebendo dependências reais;
- presets apenas como aliases de planos completos;
- base × guidance como organização dos campos, não como framework obrigatório;
- telemetria estruturada write-only;
- histories compactos e bounds apenas após profiling;
- retry integral no supervisor;
- nenhum `--ape-model`, replay automático, checkpoint, WAL, event sourcing durável ou read-back;
- nenhuma complexidade dedicada ao APE default/puro.

Se a prioridade mudar para minimalismo absoluto e o conjunto de features estiver fechado, a Arquitetura B é a substituta natural. Se a campanha exigir uma matriz grande de interações, incorporar a modelagem de C ao `RunSpec` de A é preferível a trocar toda a arquitetura. D deve permanecer uma evolução orientada por evidência.

O insight comum mais valioso dos oito relatórios é correto: o problema central não é “criar melhores `if`s”, mas tornar **plano, precedência, ownership e observabilidade** explícitos. O filtro imposto por runs limpas simplifica a resposta: tudo vive dentro de uma tentativa descartável; tudo que sobrevive é resultado, nunca memória operacional.
