# Validacao Rigorosa: gh6-aperv-llm-integration

**Autor**: Claude Opus 4.6 (1M context) | **Data**: 2026-03-16 | **Tipo**: Analise de change sem implementacao

---

## 1. Resumo Executivo

O change `gh6-aperv-llm-integration` propoe a integracao de um modelo de linguagem visual (Qwen3-VL via SGLang) no loop de exploracao do APE-RV em dois pontos de decisao: (1) primeira visita a um novo estado, e (2) deteccao precoce de estagnacao. A analise conclui que **o plano e fundamentalmente solido e bem alinhado com o estado da arte**, mas identifica **7 lacunas tecnicas, 3 riscos subestimados e 5 oportunidades de melhoria** que devem ser considerados antes da implementacao.

**Veredicto geral**: APROVADO COM RESSALVAS — o design e superior ao status quo e competitivo com o estado da arte, mas ha gaps que podem limitar a eficacia em cenarios reais.

---

## 2. Estado da Arte: Comparacao com Ferramentas LLM-Android

### 2.1 Ferramentas Pesquisadas

| Ferramenta | Venue | Modelo LLM | Invocacao | Contexto | Cobertura | Custo/App | Inovacao Principal |
|-----------|-------|-----------|-----------|----------|-----------|-----------|-------------------|
| GPTDroid | ICSE'24 | ChatGPT | Cada passo | Texto (XML) | +32% atividades | ~$1.07 | Memoria funcional |
| DroidAgent | ICST'24 | GPT-4/3.5 | Multi-agente | Texto (JSON) | 61% atividades | ~$16.31 | Tarefas baseadas em persona |
| VisionDroid | 2024 | GPT-4V | Cada passo | Screenshot + Texto | 57-65% atividades | Alto | Bugs funcionais nao-crash |
| AutoDroid | MobiCom'24 | GPT-3.5/Vicuna | Cada passo + memoria | Texto (HTML) | N/A | Baixo | Injecao de memoria |
| **LLMDroid** | **FSE'25** | GPT-4o-mini | **Plato de cobertura** | Texto (HTML) | **+29.31%** | **$0.03-0.49/hr** | **Guia por cobertura** |
| **LLM-Explorer** | **MobiCom'25** | Diversos | **So manutencao de conhecimento (~160 queries)** | Texto | **64.58%** | **$0.11** | **LLM para conhecimento, nao acoes** |
| VLM-Fuzz | EMSE'25 | GPT-4o Vision | Sob demanda (inputs) | Screenshot + XML | 68.5% classes | $0.05-0.25 | DFS + VLM seletivo |
| FastBot2 | ASE'22 | Nenhum (RL) | N/A | N/A | >APE/Stoat | Gratis | Modelo RL reutilizavel |

### 2.2 Posicionamento do APE-RV gh6

O design do gh6 posiciona-se entre **LLMDroid** (guia por evidencia de estagnacao) e **VLM-Fuzz** (VLM sob demanda com entrada visual). A abordagem de 2 modos (new-state + stagnation) e diretamente validada pela descoberta central do LLMDroid (FSE 2025): **guia por evidencia de cobertura e significativamente mais eficiente que trigger probabilistico**.

**Diferenciais do APE-RV vs estado da arte:**

| Aspecto | APE-RV gh6 | Melhor Pratica SOTA | Avaliacao |
|---------|-----------|-------------------|-----------|
| Trigger LLM | new-state + stagnation (2 modos) | Plato de cobertura (LLMDroid) | Bom — stagnation mode equivale a plato, new-state e adicional |
| Contexto | Screenshot + widget list + MOP | Screenshot + texto (VisionDroid, VLM-Fuzz) | Excelente — MOP e unico, combina visual + estrutural |
| Historico de acoes | Ultimas 3-5 com resultado | Sim (GPTDroid, VisionDroid, rvsmart) | Adequado |
| Tipo de resposta | Selecao direta de acao (tool call) | Tool call (DroidAgent, rvsmart) | Adequado |
| Entrada de texto | type_text com semantica LLM | QTypist: +42% atividades com texto semantico | Critico — corretamente incluido |
| Custo estimado | ~60-130 chamadas, ~$0.05-0.20/sessao (modelo local) | $0.03-0.49/hr (LLMDroid) | Excelente — modelo local elimina custo API |
| Degradacao graciosa | Circuit breaker + budget + SATA fallback | Fallback em LLMDroid, VLM-Fuzz | Excelente |
| Cliques em elementos dinamicos | Raw click para WebView/canvas | VLM-Fuzz: similar | Diferencial positivo |

---

## 3. Analise de Consistencia e Coerencia

### 3.1 Consistencia Interna entre Artefatos

**Metodologia**: Cruzamento sistematico entre proposal.md, design.md, tasks.md e todas as 5 specs.

| Artefato Origem | Artefato Destino | Consistente? | Observacoes |
|-----------------|-----------------|-------------|-------------|
| proposal.md (capabilities) | specs/ (requirements) | SIM | Cada capability nova mapeada para spec com requisitos formais |
| proposal.md (impact) | tasks.md (grupos) | SIM | Cada arquivo impactado tem task correspondente |
| design.md (D1-D10 decisions) | specs/ (invariants) | SIM | Decisoes refletidas nas invariantes (INV-LLM-*, INV-RTR-*, INV-PRM-*) |
| design.md (API Design) | specs/llm-routing | SIM | Assinaturas e fluxo 1:1 |
| design.md (Error Handling) | specs/llm-infrastructure | SIM | Cada cenario de erro especificado |
| design.md (Testing Strategy) | tasks.md (Group 6) | SIM | Cada caso de teste mapeado para task |
| specs/llm-prompt | design.md (ApePromptBuilder) | SIM | Formato de prompt identico |
| specs/exploration | design.md (Architecture) | SIM | Hook points identicos |
| specs/mop-guidance | proposal.md (revert) | SIM | Valores v1 (500/300/100) consistentes |

**Resultado**: Nenhuma inconsistencia interna identificada. Os artefatos sao notavelmente coerentes entre si.

### 3.2 Consistencia com rvsmart (Fonte de Infraestrutura)

**Metodologia**: Comparacao do design gh6 com a implementacao real do rvsmart no diretorio `rvsec-android/rvsmart/`.

| Aspecto | rvsmart (real) | gh6 (planejado) | Consistente? | Notas |
|---------|--------------|----------------|-------------|-------|
| Classes LLM a copiar | 7 classes em `llm/` | 7 classes listadas | SIM | Todas 7 identificadas corretamente |
| SglangClient (Gson) | Usa Gson | Converte para org.json | SIM | Decisao D7 justificada |
| ToolCallParser (Gson) | Usa Gson | Converte para org.json | SIM | Decisao D7 justificada |
| ToolCallParser fallbacks | 3 niveis (native/XML/JSON) | 3 niveis identicos | SIM | |
| Malformed JSON fixes | 4 padroes Qwen3-VL | 4 padroes identicos | SIM | |
| CoordinateNormalizer | [0,1000) → pixels | [0,1000) → pixels | SIM | |
| LlmCircuitBreaker | 3 estados, threshold=3 | 3 estados, threshold=3 | SIM | |
| ImageProcessor | max 1000px, JPEG 80 | max 1000px, JPEG 80 | SIM | |
| ScreenshotCapture | SurfaceControl reflection | SurfaceControl reflection | SIM | |
| Prompt V13 vs V17 | Ambos implementados | Usa V13 (compacto) | SIM | Justificativa: V13 efetivo com menos tokens |
| Modos de roteamento | 4 estrategias (PROB, NEW_SCREEN, STUCK, ARRIVAL) | 2 modos (new-state, stagnation) | SIMPLIFICADO | Deliberado — menos modos, mais focado |
| MOP markers | [DM]/[M] em V17 | [DM]/[M] | SIM | |
| type_text | Sim, com inputValueGenerator | Sim, com LLM | SIM | |
| long_click | Nao no rvsmart | Adicionado no gh6 | EXTENSAO | Correto — preenche lacuna |

**Lacuna identificada L1**: O rvsmart tem um `InputValueGenerator` sofisticado que gera texto contextual usando regex patterns por tipo de campo (email, telefone, URL, etc.). O gh6 depende inteiramente do LLM para gerar texto. **Quando o LLM nao e chamado (fallback SATA), o APE-RV nao tem gerador de texto semantico** — apenas o fuzzing aleatorio existente. O rvsmart resolve isso com fallback algoritmico para texto.

### 3.3 Consistencia com APE-RV (Codebase Destino)

**Metodologia**: Verificacao do design contra o codigo-fonte real do APE-RV.

| Aspecto | Codigo Real | gh6 Design | Consistente? | Notas |
|---------|-----------|-----------|-------------|-------|
| StatefulAgent.updateState() | Captura GUITree, resolve State, chama markVisited | Insere isNewState antes de markVisited | SIM | Bug fix correto — visitedCount incrementa em markVisited |
| SataAgent.selectNewActionNonnull() | Pipeline: buffer → ABA → trivial → backward → epsilon-greedy | LLM hook no topo (antes buffer) | SIM | Posicao correta — LLM tem prioridade |
| StatefulAgent._mopData | Campo existente em StatefulAgent | Usado por ApePromptBuilder | SIM | |
| Config.java | Pattern: `public static final` + `getProperty()` | 9 novos campos seguindo mesmo pattern | SIM | |
| ActionType enum | MODEL_CLICK, MODEL_LONG_CLICK, MODEL_SCROLL_*, MODEL_BACK | click→MODEL_CLICK, long_click→MODEL_LONG_CLICK, back→MODEL_BACK | SIM | |
| GUITreeNode.getBoundsInScreen() | Retorna Rect com bounds | Usado em bounds containment | SIM | |
| GUITreeNode.setInputText() | Metodo existe no GUITreeNode | Usado para type_text injection | SIM | Verificado no codigo |
| MonkeySourceApe.generateEventsForActionInternal() | Verifica node.getInputText() | Pipeline existente reutilizado | SIM | |
| graphStableCounter | Campo em StatefulAgent, comparado com threshold | Stagnation mode usa threshold/2 | SIM | |
| Model.StateTransition tracking | Actions executadas sao rastreadas | Raw clicks NAO sao rastreadas (analogo a fuzzing) | SIM | Decisao explicita e justificada |

**Lacuna identificada L2**: O design especifica `StatefulAgent._stateBeforeLast` para determinar resultado "previous screen", mas o StatefulAgent real ja tem campos `lastState`/`newState`/`currentState`. O design precisa especificar exatamente **onde** na sequencia de updates o `_stateBeforeLast` e atualizado para evitar race conditions com o mecanismo existente de refinement/rebuild do modelo (que pode alterar referências de estado).

---

## 4. Auditoria de Rastreabilidade

### 4.1 PRD → Specs → Design → Tasks

| PRD Req (implicito) | Spec | Design Decision | Task | Rastreavel? |
|--------------------|------|-----------------|------|------------|
| Quebrar exploracao deterministica | llm-routing: 2 modes | D3: Two modes | 4.2, 5.2, 5.3 | SIM |
| Preservar SATA+MOP | llm-routing: INV-RTR-04 | D2: LLM selects, nao boosta | 5.2 (non-null → return) | SIM |
| Reverter pesos MOP | mop-guidance: MODIFIED | Design context | 1.1, 1.2 | SIM |
| Degradacao graciosa | llm-infrastructure: INV-LLM-01..07 | Error Handling table | 2.7 (circuit breaker) | SIM |
| type_text semantico | llm-prompt: System Message | D8: type_text | 3.2, 4.4 | SIM |
| long_click | llm-prompt: System Message | D9: long_click | 3.2, 4.4 | SIM |
| Coordenadas Qwen | llm-infrastructure: CoordinateNormalizer | D5: bounds+Euclidean | 2.6, 4.4 | SIM |
| Orcamento de chamadas | llm-routing: Budget | API: llmMaxCalls | 1.3, 4.2 | SIM |
| Telemetria | llm-routing: Telemetry | Telemetry section | 4.5 | SIM |
| Cliques em elementos dinamicos | llm-routing: LlmActionResult.isRawClick() | API: isRawClick | 4.3 | SIM |
| Zero dependencia Maven | llm-infrastructure: org.json | D7: org.json | 2.2, 2.5 | SIM |
| Testes unitarios | design: Testing Strategy | All test tables | 6.1-6.9 | SIM |

**Resultado**: Rastreabilidade completa de ponta a ponta. Nenhum requisito orfao ou task sem spec.

### 4.2 Invariantes → Cenarios de Teste

| Invariante | Tem Cenario WHEN/THEN? | Tem Task de Teste? | Rastreavel? |
|-----------|----------------------|-------------------|------------|
| INV-LLM-01 (SglangClient sem excecao) | Sim (SGLang unreachable) | 6.8 SglangClientTest | SIM |
| INV-LLM-02 (ScreenshotCapture retorna null) | Sim (SurfaceControl fails) | Manual test | SIM |
| INV-LLM-03 (ImageProcessor max 1000px) | Sim (Large/Small) | 6.7 ImageProcessorTest | SIM |
| INV-LLM-04 (ToolCallParser 3 fallbacks) | Sim (Native/XML/JSON/AllFail) | 6.2 ToolCallParserTest | SIM |
| INV-LLM-05 (CoordinateNormalizer clamping) | Sim (Center/Edge/Zero) | 6.3 CoordinateNormalizerTest | SIM |
| INV-LLM-06 (CircuitBreaker transitions) | Sim (CLOSED→OPEN→HALF_OPEN) | 6.4 LlmCircuitBreakerTest | SIM |
| INV-LLM-07 (CircuitBreaker synchronized) | Nao explicitamente | Nao | **PARCIAL** |
| INV-RTR-01 (LlmRouter so com URL) | Sim (URL/no URL) | 5.1 | SIM |
| INV-RTR-02 (selectAction sem excecao) | Sim (screenshot fail, timeout) | 6.6 LlmRouterTest | SIM |
| INV-RTR-03 (ModelAction valida) | Sim (bounds, Euclidean, raw click) | 6.6 LlmRouterTest | SIM |
| INV-RTR-04 (nao modifica priorities) | Nao explicitamente | Nao | **PARCIAL** |
| INV-RTR-05 (modos independentes) | Sim (disabled scenarios) | 6.6 LlmRouterTest | SIM |
| INV-RTR-06 (memory cleanup) | Sim (Pipeline) | Nao explicitamente | **PARCIAL** |
| INV-PRM-01 (2 messages sempre) | Sim (System message) | 6.5 ApePromptBuilderTest | SIM |
| INV-PRM-02 (2 content parts) | Sim (Image + Text) | 6.5 ApePromptBuilderTest | SIM |
| INV-PRM-03 (todas acoes presentes) | Sim (Mixed action list) | 6.5 ApePromptBuilderTest | SIM |
| INV-PRM-04 (MOP so com mopData) | Sim (null MopData) | 6.5 ApePromptBuilderTest | SIM |

**Resultado**: 3 invariantes com cobertura parcial de teste. Recomendar adicionar cenarios explicitos para INV-LLM-07 (concorrencia), INV-RTR-04 (nao-modificacao de prioridade) e INV-RTR-06 (memory cleanup).

---

## 5. Validacao Tecnica: O Design Faz Sentido?

### 5.1 A Exploracao Sera Sistematica e Completa?

**Pergunta central**: O LLM realmente ajudara o APE-RV a explorar o app de forma mais completa que APE puro e FastBot2?

**Analise por mecanismo**:

#### Modo 1: New-State (primeira visita)

**Tese**: Na primeira visita a um novo estado, o LLM pode fazer uma escolha mais inteligente que SATA porque:
- Ve o screenshot e entende o contexto visual (SATA so ve a arvore de acessibilidade)
- Pode gerar texto semantico para campos de input (SATA so faz fuzzing aleatorio)
- Pode identificar elementos dinamicos invisiveis ao UIAutomator
- Pode reconhecer dialogos e dismissar corretamente

**Validacao contra evidencias**:
- VLM-Fuzz (EMSE 2025) mostrou que 13 de 59 apps nao precisaram de VLM — ou seja, 78% dos apps se beneficiam de assistencia visual. Isso valida a utilidade do modo new-state.
- QTypist (ICSE 2023) mostrou +42% atividades com texto semantico. O type_text do LLM e um substituto funcional para o QTypist.
- LLM-Explorer (MobiCom 2025) mostrou que a primeira impressao de uma tela e o melhor momento para usar LLM. O modo new-state captura exatamente esse momento.

**Preocupacao P1**: O modo new-state dispara em TODOS os novos estados, incluindo estados triviais (dialogo de permissao, tela de loading, etc.). Em apps complexos com muitos estados, isso pode consumir o budget de 200 chamadas em estados de baixo valor, deixando o LLM indisponivel para estados importantes que aparecem mais tarde na exploracao.

**Sugestao S1**: Considerar heuristica de filtragem: nao disparar LLM em estados com poucas acoes (<= 2, provavelmente dialogo), ou em atividades triviais ja identificadas pelo SataAgent. Alternativa: priorizar estados com MOP markers.

#### Modo 2: Stagnation (exploracao travada)

**Tese**: Quando o graphStableCounter ultrapassa threshold/2, o SATA esta girando em circulos. O LLM pode sugerir uma acao que SATA nao consideraria.

**Validacao contra evidencias**:
- LLMDroid (FSE 2025) provou que guia por evidencia de estagnacao (coverage plateau) e 94% tao eficaz quanto guia continuo, a 1/16 do custo. O stagnation mode implementa este principio.
- A analise dos 47% de APKs com exploracao deterministica confirma que o SATA precisa de quebra de padrao exatamente nestes momentos.

**Preocupacao P2**: O stagnation mode so dispara quando `graphStableCounter > threshold/2` (tipicamente 50 passos sem crescimento do grafo). Mas o graphStableCounter e resetado a cada novo estado. Se o SATA esta ciclando entre estados **conhecidos** sem descobrir **novos** estados, o graphStableCounter cresce. Porem, se o SATA descobre "novos" estados espurios (por refinamento de naming), o contador reseta e o LLM nunca e invocado. Isso pode criar um ponto cego onde o sistema parece explorar (estados novos por refinamento) mas nao avanca (nenhuma atividade nova).

**Sugestao S2**: Complementar graphStableCounter com metricas de diversidade de atividade (activityStableCounter) ou de cobertura (se disponivel via logcat, como no rvsmart). O LLMDroid usa cobertura de codigo real como trigger — se viavel, isso seria mais preciso.

#### Combinacao dos 2 Modos

**Pergunta**: Os 2 modos cobrem os cenarios problematicos identificados nos 47% de APKs deterministicos?

**Analise**:
- **Cenario A**: App com login (requer texto) — o modo new-state com type_text resolve. **COBERTO**.
- **Cenario B**: App com menus de contexto (requer long_click) — o modo new-state com long_click resolve. **COBERTO**.
- **Cenario C**: App com WebView dinamico — o modo new-state com raw click resolve. **COBERTO**.
- **Cenario D**: App com exploracao profunda (>10 telas de profundidade) — o modo stagnation pode ajudar, mas depende do LLM sugerir acoes que levem a estados desconhecidos. **PARCIALMENTE COBERTO** — o LLM nao tem memoria de longo prazo nem mapa da app.
- **Cenario E**: App com navegacao via tabs/bottom nav — SATA ja cobre bem (trivialActivity targeting). **COBERTO pelo SATA existente**.
- **Cenario F**: App com conteudo atras de scroll longo — scroll excluido do schema LLM; depende do SATA. **COBERTO pelo SATA existente**.

**Lacuna identificada L3**: O LLM nao tem **memoria entre chamadas**. Cada invocacao recebe apenas as ultimas 3-5 acoes e o estado atual. Em apps complexos, o LLM pode repetir sugestoes que ja levaram a becos sem saida em chamadas anteriores. GPTDroid e DroidAgent ambos usam memoria funcional/tarefa para evitar isso. O design gh6 explicitamente declara "multi-turn conversations" como non-goal, o que e aceitavel para a v1, mas deve ser considerado para v2.

### 5.2 Sera Melhor que APE Original?

| Dimensao | APE Original | APE-RV gh6 | Vantagem |
|----------|-------------|-----------|----------|
| Entrada de texto | Fuzzing aleatorio | LLM gera texto semantico (type_text) | **APE-RV** — apps com login/busca explorados mais profundamente |
| Desempate em exploracao | Determinisico apos saturacao | LLM quebra padrao em new-state + stagnation | **APE-RV** — 47% de APKs determinisicos beneficiados |
| Elementos dinamicos | Invisivel (so UIAutomator) | Raw click via screenshot LLM | **APE-RV** — WebView, canvas, custom views |
| Priorizacao MOP | Pesos 100/60/20 | Pesos 500/300/100 + [DM]/[M] no prompt LLM | **APE-RV** — mais agressivo em operacoes monitoradas |
| Sobrecarga | Zero | +3-11 min em sessao de 10 min | **APE** — sem overhead |
| Reprodutibilidade | Determinisico com seed | Nao-deterministico (LLM) | **APE** — mais facil de reproduzir |

**Veredicto**: APE-RV gh6 sera melhor que APE em cobertura para a maioria dos apps, especialmente os que exigem texto semantico ou tem conteudo dinamico. A sobrecarga de tempo e aceitavel dado que APE-RV ja roda em ambiente de pesquisa.

### 5.3 Sera Melhor que FastBot2?

| Dimensao | FastBot2 | APE-RV gh6 | Vantagem |
|----------|---------|-----------|----------|
| Velocidade | 12 acoes/seg | ~1-2 acoes/seg (com LLM: ~0.2-0.3) | **FastBot2** — 40x mais rapido |
| Modelo reutilizavel | Sim (persiste em /sdcard/) | Nao (cada sessao do zero) | **FastBot2** |
| Entrada de texto | Nao | Sim (LLM semantico) | **APE-RV** |
| Guia MOP | Nao | Sim (500/300/100 + LLM) | **APE-RV** |
| Elementos dinamicos | Nao | Sim (raw click) | **APE-RV** |
| Long click | Nao (so click/back/scroll) | Sim | **APE-RV** |
| Abstacao/Refinamento | RL simples | CEGAR com NamingFactory | **APE-RV** — modelo mais sofisticado |
| CI/CD industrial | Sim (ByteDance) | Nao | **FastBot2** |

**Veredicto**: FastBot2 e imbativel em velocidade bruta e reutilizacao de modelos. APE-RV gh6 sera superior em **profundidade de exploracao** (especialmente em apps com forms, WebViews, operacoes monitoradas). A combinacao ideal seria LLMDroid-style: FastBot2 como base + APE-RV LLM como guia em platôs — mas isso esta fora do escopo.

---

## 6. Pontos Positivos

### P1. Arquitetura Hibrida LLM+Heuristico Alinhada com SOTA
O principio de usar LLM como override pontual sobre SATA+MOP e validado por LLMDroid (FSE 2025), LLM-Explorer (MobiCom 2025) e VLM-Fuzz (EMSE 2025). Todas as ferramentas top-tier em 2024-2025 concluiram que LLM continuo e desperdicador — o gh6 evita esse erro.

### P2. Degradacao Graciosa Exemplar
Tres camadas de protecao (circuit breaker, budget, SATA fallback) garantem que falha do LLM nunca degrade a exploracao. Isso e melhor que a maioria das ferramentas SOTA que falham silenciosamente.

### P3. Reutilizacao Inteligente de Infraestrutura
Copiar 7 classes maduras do rvsmart (~1000 LOC testado) em vez de reimplementar e uma decisao de engenharia pragmatica. A conversao Gson→org.json e de baixo risco (mapeamento 1:1).

### P4. MOP Markers no Prompt LLM — Inovacao Exclusiva
Nenhuma ferramenta SOTA combina analise estatica de operacoes monitoradas com LLM visual. Os marcadores [DM]/[M] dão ao LLM informacao que nenhum outro sistema tem — quais widgets levam a codigo sob monitoramento RV. Isso e um diferencial competitivo real.

### P5. Suporte a Raw Click para Elementos Dinamicos
O `LlmActionResult.isRawClick()` permite que o LLM interaja com elementos invisiveis ao UIAutomator (WebView, canvas, custom views). Apenas VLM-Fuzz tem capacidade similar. Isso resolve uma limitacao fundamental do APE original.

### P6. type_text com Semantica LLM
QTypist (ICSE 2023) provou que texto semantico aumenta cobertura em 42%. O design gh6 reutiliza o pipeline existente do APE (`setInputText()` + `generateEventsForActionInternal()`) para integrar texto LLM sem codigo novo de geracao de eventos.

### P7. Coordenadas Normalizadas Consistentes
O design explicitamente evita o mismatch do rvsmart (pixels no prompt, [0,1000) na resposta) usando [0,1000) em ambos os lados. Isso segue a melhor pratica do rvagent.

### P8. Telemetria Estruturada e Parseable
O formato de log `[APE-RV] LLM iter=N mode=X ...` e compativel com o ecossistema RVSEC e permite analise automatizada. O resumo agregado no tearDown facilita comparacao entre experimentos.

### P9. Rastreabilidade Completa dos Artefatos
Cada decisao tem justificativa, cada spec tem cenarios WHEN/THEN, cada requisito tem task de implementacao e caso de teste. O nivel de documentacao e superior ao de qualquer ferramenta SOTA analisada.

### P10. Correcao do Bug isNewState
A captura de `isNewState = (visitedCount == 0)` ANTES de `markVisited()` corrige um bug real que impediria o modo new-state de funcionar. Isso demonstra entendimento profundo do fluxo de execucao do APE.

---

## 7. Pontos Negativos e Lacunas

### N1. Ausencia de Memoria entre Chamadas LLM (ALTA IMPORTANCIA)

**Problema**: Cada chamada LLM recebe apenas as ultimas 3-5 acoes. O LLM nao sabe o que sugeriu em chamadas anteriores nem quais sugestoes falharam. Em uma sessao de 200 chamadas, pode sugerir a mesma acao repetidamente.

**Evidencia SOTA**: GPTDroid usa "functionality-aware memory" que registra funcionalidades ja testadas. DroidAgent mantém Task Memory e Spatial Memory. LLM-Explorer mantém Abstract Interaction Graph. Todos reportam que memoria e critica para evitar exploracao redundante.

**Impacto**: O LLM pode desperdicar chamadas repetindo sugestoes que ja foram executadas ou que levaram a becos sem saida. Mitigacao parcial: o `(v:N)` no prompt mostra visited count, mas nao diz se o LLM especificamente ja sugeriu aquela acao.

**Mitigacao sugerida**: Adicionar um campo `llmSuggestedCount` ao prompt (quantas vezes o LLM ja sugeriu cada acao). Ou manter uma lista dos ultimos N widgets sugeridos pelo LLM e incluir no prompt como "LLM already suggested: ...".

### N2. Consumo Potencial de Budget em Estados Triviais (MEDIA IMPORTANCIA)

**Problema**: O modo new-state dispara em TODOS os novos estados, incluindo dialogos de permissao, telas de loading, popups de erro, teclados virtuais, etc. Apps complexos podem ter 50-100+ estados novos, consumindo o budget de 200 chamadas rapidamente.

**Evidencia SOTA**: VLM-Fuzz filtra chamadas VLM para apenas quando ha campos de texto. LLMDroid so chama em platôs de cobertura. LLM-Explorer usa apenas ~160 queries para toda a sessao.

**Impacto**: Apps complexos podem esgotar o budget antes de atingir estados profundos de alto valor.

**Mitigacao sugerida**: (a) Nao disparar LLM em estados com <= 2 acoes (provavelmente dialogo simples); (b) Nao disparar em atividades triviais ja identificadas pelo SataAgent; (c) Reservar uma fracao do budget (ex: 30%) exclusivamente para stagnation mode.

### N3. Stagnation Mode Pode Ter Ponto Cego com Refinamento (MEDIA IMPORTANCIA)

**Problema**: O `graphStableCounter` reseta quando novos estados sao descobertos. Mas o NamingFactory pode criar "novos" estados por refinamento de abstracao (split de estado existente em dois), resetando o contador sem progresso real de exploracao. O LLM nunca seria invocado nestes casos.

**Impacto**: Em apps com alta taxa de refinamento, o stagnation mode pode nunca disparar.

**Mitigacao sugerida**: Usar `activityStableCounter` como trigger complementar: se nenhuma NOVA atividade e descoberta por N passos, considerar estagnacao mesmo se estados sao criados por refinamento.

### N4. Scroll Excluido do Schema LLM — Risco em Apps com Listas (BAIXA-MEDIA IMPORTANCIA)

**Problema**: A decisao D9 exclui scroll do schema LLM porque "nao beneficia de entendimento semantico e desperdicaria 3-5s de chamada LLM". Porem, em apps com listas longas (contatos, emails, configuracoes), o conteudo abaixo da dobra (fold) so e acessivel via scroll. Se o SATA nao faz scroll suficiente, o LLM nao pode compensar.

**Evidencia SOTA**: rvsmart inclui SCROLL como acao do agente. VisionDroid inclui scroll. O argumento do gh6 e valido (scroll e mecanico), mas o LLM poderia IDENTIFICAR que scroll e necessario mesmo sem executa-lo.

**Mitigacao**: Aceitavel para v1. Para v2, considerar `scroll(direction)` no schema se experimentacao mostrar deficit em apps com listas.

### N5. Sem Verificacao de Efeito do Raw Click (BAIXA IMPORTANCIA)

**Problema**: Quando `isRawClick()=true`, um MonkeyTouchEvent e injetado, mas o resultado nao e rastreado pelo Model. Se o raw click leva a um novo estado, ele e capturado no proximo ciclo. Mas se o raw click abre um componente que NAO muda o AccessibilityTree (ex: animacao CSS, popup JS), o sistema nao sabe se o click teve efeito.

**Impacto**: Baixo — o mesmo problema existe no fuzzing aleatorio do APE, e o sistema e resiliente a cliques ineficazes.

### N6. Prompt System Message Poderia Ser Mais Diretivo (BAIXA IMPORTANCIA)

**Problema**: O system message diz "PRIORITY: [DM]/[M] elements > unvisited (v:0) > visited" mas nao explica o PORQUE. LLMs de menor porte (Qwen3-VL-4B) podem nao seguir instrucoes de priorizacao sem contexto.

**Evidencia SOTA**: DroidAgent usa chain-of-thought com reasoning steps. VisionDroid usa 5 aspectos estruturados. O argumento do gh6 (V13 compacto e eficaz) e razoavel, mas nao foi testado especificamente com Qwen3-VL-4B.

**Mitigacao**: Testar empiricamente; se o LLM ignorar prioridades MOP, adicionar uma linha de contexto ("these elements reach security-sensitive code paths — testing them is the primary research goal").

### N7. Ausencia de Tratamento para Apps Multi-Processo/WebView Hibrido (BAIXA IMPORTANCIA)

**Problema**: Apps hibridos (React Native, Flutter, WebView) podem ter a arvore de acessibilidade drasticamente diferente do que o screenshot mostra. O LLM ve o screenshot correto, mas o widget list pode estar desatualizado ou incompleto.

**Impacto**: Baixo para v1 — o raw click cobre parcialmente. Mas a discrepância entre widget list e screenshot pode confundir o LLM.

---

## 8. Riscos e Mitigacoes

### R1. Risco: Latencia LLM Impacta Throughput de Exploracao (ALTO)

**Descricao**: Cada chamada LLM adiciona 3-5 segundos. Com 60-130 chamadas, o overhead e de 3-11 minutos em uma sessao de 10 minutos. Em cenarios com muitos novos estados (app complexo), o overhead pode exceder o tempo util de exploracao.

**Probabilidade**: Alta (especialmente para apps com >50 estados unicos).

**Impacto**: O APE-RV pode executar **menos acoes totais** que o APE puro na mesma janela de tempo. Se o ganho de qualidade do LLM nao compensar a perda de quantidade, a cobertura pode ate diminuir.

**Mitigacao planejada** (design.md): Circuit breaker + call budget + mode toggles.

**Mitigacao adicional sugerida**: (a) Limitar new-state mode a primeiros N% do tempo (ex: 70%), reservando tempo para exploracao SATA pura; (b) Implementar timeout adaptativo: se o LLM demora >5s, reduzir budget; (c) Paralelizar captura de screenshot com processamento SATA (capturar screenshot em background enquanto SATA seleciona acao; se LLM retornar antes, substituir).

### R2. Risco: Qwen3-VL-4B Insuficiente para Raciocinio Complexo (MEDIO)

**Descricao**: O modelo planejado (Qwen3-VL-4B) tem 4 bilhoes de parametros — muito menor que GPT-4V (~1.7T parametros estimados) usado pela maioria das ferramentas SOTA. O raciocinio visual e seguimento de instrucoes de modelos 4B sao significativamente inferiores.

**Evidencia**: VisionDroid e LLMDroid usam GPT-4o. LLM-Explorer testou modelos menores mas com performance inferior. A precisao de coordenadas de ~84% citada no design e para Qwen3-VL, nao especificamente para 4B.

**Probabilidade**: Media — Qwen3-VL-4B pode nao seguir instrucoes de priorizacao MOP ou gerar texto semantico de baixa qualidade.

**Mitigacao planejada**: `Config.llmModel` permite trocar modelo.

**Mitigacao adicional sugerida**: (a) Testar com Qwen3-VL-8B como alternativa (dobra de capacidade com overhead aceitavel no SGLang); (b) Simplificar ainda mais o prompt se 4B nao seguir instrucoes; (c) Documentar modelo minimo recomendado baseado em experimentacao.

### R3. Risco: Conflito entre isNewState e Model Rebuild (MEDIO)

**Descricao**: Quando o NamingFactory refina a abstracao, o Model e reconstruido (`rebuild()`). Durante rebuild, estados sao removidos e recriados, o que pode invalidar a referencia `_stateBeforeLast`. Se `_stateBeforeLast` aponta para um estado removido, a comparacao para determinar "previous screen" falha silenciosamente.

**Probabilidade**: Media — refinamento ocorre regularmente, especialmente no inicio da exploracao.

**Impacto**: Historico de acoes incorreto ("new screen" quando deveria ser "previous screen"), o que degrada a qualidade do prompt LLM.

**Mitigacao planejada**: Nenhuma explicita no design.

**Mitigacao sugerida**: Usar `State.getStateKey()` para comparacao em vez de referencia de objeto. StateKey sobrevive a rebuilds pois identifica o estado por activity + widget set.

### R4. Risco: Memory Pressure em Sessoes Longas (BAIXO-MEDIO)

**Descricao**: O APE ja tem problema conhecido de `OutOfMemoryError` (mencionado em CLAUDE.md). Cada chamada LLM adiciona ~500KB de dados base64 temporarios. Com 200 chamadas, sao ~100MB adicionais de alocacao (mesmo com cleanup no finally).

**Mitigacao planejada**: `finally` block nula referencias. Adequado para v1.

**Mitigacao adicional**: Considerar `ImageProcessor.processScreenshot()` retornando `InputStream` em vez de `String` para evitar alocacao de string base64 de uma vez.

### R5. Risco: Coordenadas Imprecisas em Dispositivos com Density Diferente (BAIXO)

**Descricao**: O CoordinateNormalizer assume mapeamento linear de [0,1000) para [0,deviceWidth). Em dispositivos com barra de status flutuante, notch, ou display com cantos arredondados, o mapeamento pode ser impreciso nas bordas.

**Mitigacao planejada**: Boundary reject (top 5%, bottom 6%) cobre parcialmente.

**Mitigacao adicional**: O boundary reject e suficiente para v1. Dispositivos de pesquisa (emulador RVSec) tem formato standard.

---

## 9. Ambiguidades Identificadas

### A1. Raw Click vs Model Tracking (design.md linhas 193-210)

**Ambiguidade**: O design diz que raw clicks nao sao rastreados pelo Model, mas que o "efeito e capturado no proximo ciclo GUITree". Nao esta claro: se o raw click leva a um novo estado que ja foi mapeado, a transicao sera registrada como vindo de qual acao? Se nenhuma acao do Model foi executada, a transicao pode ficar "orfã".

**Resolucao sugerida**: Especificar que apos um raw click, o proximo `updateState()` trata o estado resultante como se uma acao de fuzzing tivesse sido executada (analogo ao mecanismo existente de fuzzing do APE que ja gera eventos nao-rastreados).

### A2. Stagnation Hook — Onde Exatamente? (specs/exploration/spec.md)

**Ambiguidade**: A spec diz "when `graphStableCounter > threshold/2`" mas nao especifica se o check ocorre em `checkStable()` (chamado a cada passo) ou em `onGraphStable()` (chamado quando threshold e atingido). O SataAgent tem ambos os metodos. Se ocorre em `checkStable()`, o LLM sera chamado a cada passo durante estagnacao (multiplas vezes). Se ocorre em `onGraphStable()`, so sera chamado uma vez ao atingir o threshold.

**Analise do codigo**: `checkStable()` e chamado a cada passo e incrementa `graphStableCounter`. `onGraphStable()` e chamado quando `counter >= threshold`. A spec implica que o check deve ocorrer em `checkStable()` (antes de atingir threshold), mas deve haver logica para nao chamar o LLM a cada passo — por exemplo, chamar uma vez quando cruza threshold/2 e nao chamar novamente ate que o graphStableCounter resete.

**Resolucao sugerida**: Especificar que o LLM e chamado UMA VEZ quando `graphStableCounter` cruza `threshold/2` (transicao de baixo para cima). Se o LLM retorna acao, o counter reseta. Se retorna null, o LLM nao e chamado novamente ate que o counter resete e cruze novamente.

### A3. LlmActionResult para Stagnation Mode (design.md linhas 339-345)

**Ambiguidade**: O data flow mostra que no modo stagnation, se LLM retorna acao, o `graphStableCounter` reseta para 0. Mas se a acao retornada e um `isRawClick()`, ela nao e um ModelAction — nao esta claro se um raw click deve resetar o graphStableCounter (o efeito pode nao ser detectavel pelo mecanismo de estabilidade).

**Resolucao sugerida**: Resetar o counter independentemente de ser ModelAction ou raw click, pois o LLM fez uma intervencao que pode mudar o estado. Se o raw click nao teve efeito, o counter voltara a crescer naturalmente.

### A4. callCount Incrementa em Falha ou So em Sucesso? (specs/llm-routing/spec.md)

**Ambiguidade**: O pipeline (passo 9) diz `callCount++` apos `breaker.recordSuccess()`, sugerindo que so incrementa em sucesso. Mas o budget check (passo 1) e para evitar chamadas excessivas. Se falhas nao incrementam o counter, um LLM que falha repetidamente poderia gerar trafego de rede ilimitado (limitado apenas pelo circuit breaker).

**Analise**: O circuit breaker ja limita a 3 falhas + 60s de cooling. Portanto, o callCount incrementar apenas em sucesso e aceitavel — o circuit breaker cobre o cenario de falhas repetidas.

**Resolucao**: Aceitavel como esta, mas documentar explicitamente que "callCount tracks successful LLM calls only; the circuit breaker handles failure rate limiting independently".

---

## 10. Sugestoes de Melhoria

### S1. Heuristica de Filtragem para New-State Mode (ALTA PRIORIDADE)

**O que**: Nao disparar LLM em estados triviais (poucos widgets, dialogos de permissao conhecidos, atividades do SystemUI).

**Por que**: Evita desperdicio de budget em estados de baixo valor. VLM-Fuzz mostrou que 22% dos apps nao precisam de assistencia VLM — os estados triviais nesses apps consumiriam budget inutilmente.

**Como**: Antes de chamar `shouldRouteNewState()`, verificar:
- `state.getActions().length > 3` (mais que BACK + MENU + 1 widget)
- Atividade nao e do `com.android.systemui` package
- Atividade nao e trivial (usar `SataAgent.isTrivialActivity()` existente)

### S2. Reserva de Budget para Stagnation Mode (MEDIA PRIORIDADE)

**O que**: Dividir o budget de `llmMaxCalls` em duas faixas: 70% para new-state, 30% para stagnation.

**Por que**: O stagnation mode e o mais critico para quebrar exploracao deterministica (afeta os 47% de APKs problematicos). Se o new-state mode consumir todo o budget, o stagnation mode fica inoperante exatamente quando mais precisa.

**Como**: `shouldRouteNewState()` verifica `newStateCallCount < llmMaxCalls * 0.7`. `shouldRouteStagnation()` verifica `stagnationCallCount < llmMaxCalls * 0.3`.

### S3. Mecanismo Simples de Memoria LLM (MEDIA PRIORIDADE — FUTURO)

**O que**: Manter lista das ultimas N acoes sugeridas pelo LLM e incluir no prompt como contexto negativo.

**Por que**: Previne repeticao de sugestoes. GPTDroid e LLM-Explorer provaram que memoria melhora cobertura.

**Como**: Adicionar ao prompt: `"LLM previously suggested: [2] Button 'OK', [4] EditText 'Search'. Try something different."`. Custo: ~20 tokens adicionais.

### S4. Metricas de Cobertura no Prompt LLM (BAIXA PRIORIDADE — FUTURO)

**O que**: Se `logcat` coverage tags estiverem disponiveis (como no rvsmart), incluir metricas de cobertura no prompt.

**Por que**: LLMDroid mostrou que coverage-aware guidance e mais eficaz. Dar ao LLM info sobre quais areas do codigo ja foram cobertas permite decisoes mais informadas.

**Como**: "Coverage: 45% methods. Untested areas: SettingsActivity, CryptoHelper." Requer integracao com LogcatReader (disponivel no rvsmart mas nao no APE).

### S5. Timeout Adaptativo por Modelo (BAIXA PRIORIDADE)

**O que**: Ajustar `llmTimeoutMs` dinamicamente baseado na latencia media observada.

**Por que**: Se o modelo e rapido (media 2s), um timeout de 15s e desperdicador em caso de falha. Se e lento (media 8s), 15s pode causar falsos timeouts.

**Como**: Apos as primeiras 5 chamadas, calcular `avgMs` e ajustar timeout para `min(15000, max(5000, avgMs * 3))`.

---

## 11. Comparacao: gh6 vs Abordagens Alternativas

### Abordagem A: LLMDroid-style (wrapper sobre APE)

**Descricao**: Em vez de modificar o APE internamente, usar LLM externamente para guiar o APE quando cobertura estagna.

**Pros**: Zero modificacao no APE; reutilizavel em outros tools.
**Contras**: Requer instrumentacao de cobertura (Jacoco); perda de acesso a dados internos (MOP, widget list, visited counts); atraso na comunicacao (IPC vs in-process).
**Veredicto**: O gh6 e superior porque acessa dados internos (MOP, abstraction state) que um wrapper externo nao vê.

### Abordagem B: LLM-Explorer-style (LLM so para conhecimento)

**Descricao**: Usar LLM apenas para agrupar estados similares e gerar texto, nao para selecionar acoes.

**Pros**: Dramaticamente mais barato (~$0.11/app); menos latencia por passo.
**Contras**: Nao quebra determinismo direto; APE ja tem abstraction/refinement (NamingFactory) que e superior a agrupamento LLM.
**Veredicto**: O NamingFactory do APE ja resolve o problema de agrupamento melhor que LLM. O gh6 corretamente foca o LLM em selecao de acoes onde o APE e fraco.

### Abordagem C: Always-on LLM (GPTDroid-style)

**Descricao**: Chamar LLM a cada passo de exploracao.

**Pros**: Maximo uso de inteligencia LLM.
**Contras**: +30-50 min de overhead em sessao de 10 min; custo 10x maior; LLMDroid provou que nao e necessario.
**Veredicto**: O gh6 corretamente rejeita esta abordagem (design D3).

**Conclusao**: O gh6 escolheu a abordagem correta para o contexto do APE-RV.

---

## 12. Validacao da Completude do Task Plan

### Tasks Presentes e Necessarias

| Task | Necessaria? | Completa? | Notas |
|------|-----------|----------|-------|
| 1.1 Revert MOP weights | Sim | Sim | |
| 1.2 Update MopScorer docs | Sim | Sim | |
| 1.3 Add 9 LLM config keys | Sim | Sim | |
| 1.4 Verify compile | Sim | Sim | |
| 2.1-2.9 Copy+convert 7 classes | Sim | Sim | |
| 3.1-3.7 ApePromptBuilder | Sim | Sim | |
| 4.1-4.6 LlmRouter | Sim | Sim | |
| 5.1-5.4 Agent integration | Sim | Sim | |
| 6.1-6.9 Unit tests | Sim | Sim | |
| 7.1-7.5 Build+docs+verify | Sim | Sim | |

### Tasks Ausentes

| Task Ausente | Importancia | Justificativa |
|-------------|------------|---------------|
| **ActionHistoryEntry data class** | ALTA | Mencionada em 5.1 mas nao tem task dedicada para criacao. Deve ser item separado ou parte explicita de 5.1. |
| **LlmActionResult data class** | ALTA | Definida no design API mas nao tem task de criacao. Deve ser criada junto com LlmRouter (4.1) mas nao e explicita. |
| **Teste de integracao end-to-end** | MEDIA | Nao ha task para teste que exercite o pipeline completo (screenshot → LLM → acao → Model update). Os testes unitarios isolam cada componente mas nao testam a integracao. Os testes manuais cobrem parcialmente. |
| **Documentacao de configuracao LLM** | BAIXA | 7.2 menciona update do CLAUDE.md, mas nao ha template de `ape.properties` com os 9 novos campos comentados para referencia do usuario. |

---

## 13. Analise de Dependencias e Riscos de Implementacao

### Ordem de Dependencia (Critical Path)

```
Group 1 (Config)  ─────┐
                        ├──→ Group 4 (LlmRouter) ──→ Group 5 (Agent Integration) ──→ Group 6 (Tests) ──→ Group 7 (Verify)
Group 2 (Infra)  ──→ Group 3 (Prompt) ─┘
```

- Groups 1 e 2 sao independentes (paralelizaveis)
- Group 3 depende de Group 2 (usa SglangClient.Message)
- Group 4 depende de Groups 2 e 3
- Group 5 depende de Group 4
- Group 6 depende de todos os anteriores
- Group 7 e verificacao final

**Risco de implementacao**: A conversao Gson→org.json (tasks 2.2 e 2.5) e o passo mais propenso a erros. O rvsmart usa Gson patterns (JsonObject, JsonArray, TypeToken) que nao tem equivalente 1:1 em org.json. Recomendacao: implementar os testes unitarios do ToolCallParser e SglangClient (6.2 e 6.8) ANTES da conversao, para validar a fidelidade.

---

## 14. Conclusao

### Avaliacao Final

| Criterio | Nota (1-5) | Justificativa |
|---------|-----------|---------------|
| Consistencia interna | 5/5 | Zero inconsistencias entre artefatos |
| Coerencia com codebase | 4/5 | Excelente, com excecao de stateBeforeLast/rebuild |
| Rastreabilidade | 5/5 | Completa de ponta a ponta (PRD→spec→design→task→test) |
| Alinhamento SOTA | 4/5 | Alinhado com LLMDroid/VLM-Fuzz; falta memoria LLM |
| Viabilidade tecnica | 4/5 | Viavel; conversao Gson→org.json e risco medio |
| Potencial de melhoria vs APE | 5/5 | Forte: type_text, raw click, MOP guidance |
| Potencial de melhoria vs FastBot2 | 3/5 | Profundidade sim, velocidade nao |
| Completude do plano | 4/5 | 2 data classes e teste E2E ausentes |
| Documentacao | 5/5 | Exemplar — superior a qualquer ferramenta SOTA |
| Pragmatismo | 5/5 | Reutiliza rvsmart, zero deps, degradacao graciosa |

**Media**: 4.4/5

### Recomendacoes Prioritarias

1. **ANTES de implementar**: Resolver ambiguidade A2 (stagnation hook — chamar LLM uma vez quando cruza threshold/2, nao a cada passo).
2. **DURANTE implementacao**: Criar tasks explicitas para `ActionHistoryEntry` e `LlmActionResult` data classes; implementar testes de ToolCallParser ANTES da conversao Gson→org.json.
3. **APOS implementacao v1**: Experimentar heuristica de filtragem (S1) e reserva de budget (S2). Medir se o LLM realmente melhora cobertura vs o overhead de tempo.
4. **PARA v2**: Memoria LLM entre chamadas (S3) e metricas de cobertura no prompt (S4).

### Veredicto

O change gh6-aperv-llm-integration e um plano **bem fundamentado, coerente e rastreavel** que integra LLM no ponto certo da arquitetura APE-RV. O design evita os erros das ferramentas SOTA (LLM continuo, sempre-ligado) e adota as melhores praticas (LLM pontual, degradacao graciosa, dual-mode input). As lacunas identificadas (memoria LLM, filtragem de estados triviais, ambiguidade do stagnation hook) sao tratáveis e nao comprometem a viabilidade do plano. **Recomendo prosseguir com a implementacao**, incorporando as resolucoes de ambiguidade antes de comecar e as sugestoes de melhoria ao longo da experimentacao.

---

## Apendice A: Ferramentas SOTA — Detalhes Tecnicos

### GPTDroid (ICSE 2024)

- **Memoria funcional**: Registra funcionalidades ja testadas para evitar redundancia
- **Formato**: Widget list numerada (ex: `a view 'Sort by' that can click (0)`)
- **Resultados**: +32% activity coverage, 53 novos bugs (35 confirmados)
- **Limitacao**: Texto-only (sem screenshot), GitHub desabilitado

### DroidAgent (ICST 2024)

- **Arquitetura multi-agente**: Planner (gera tarefas), Actor (executa), Observer (resume), Reflector (aprende)
- **Memoria**: Working Memory + Task Memory (ChromaDB) + Spatial Memory (por widget)
- **Exploracao**: Intent-driven — gera tarefas realisticas ("Create a new message")
- **Resultados**: 61% activity coverage, ~$16.31/app
- **GitHub**: `github.com/coinse/droidagent`

### LLMDroid (FSE 2025) — MAIS RELEVANTE

- **Wrapper sobre tools existentes**: Funciona com DroidBot, Humanoid, FastBot2
- **Trigger**: Coverage plateau (estagnacao de cobertura de codigo)
- **Page summarization**: LLM resume funcionalidades de cada pagina
- **Custo**: $0.03-0.49/hr com GPT-4o-mini
- **Resultados**: +26.16% cobertura de codigo, +29.31% activity coverage
- **GitHub**: `github.com/LLMDroid-2024/LLMDroid`

### LLM-Explorer (MobiCom 2025)

- **Paradigma**: LLM para manutencao de conhecimento, NAO para selecao de acoes
- **Abstract UI States**: Agrupa telas similares em estados logicos
- **Custo**: $0.11/app (148x mais barato que DroidAgent)
- **Resultados**: 64.58% activity coverage
- **Conclusao chave**: LLMs sao melhores para organizar conhecimento do que para selecionar acoes

### VLM-Fuzz (EMSE 2025)

- **VLM sob demanda**: So chama quando ha campos de texto ou heuristica falha
- **DFS recursivo**: Exploracao sistematica com backtracking
- **Resultados**: 68.5% class coverage (+9.0% vs APE), 53.2% method coverage (+3.7% vs APE)
- **Custo**: $0.05-0.25/app
- **Conclusao**: Comparacao direta favoravel contra APE como baseline

### FastBot2 (ASE 2022) — Baseline Nao-LLM

- **Hibrido Java/C++**: 19.4% Java + 80.5% C++ (RL nativo)
- **12 acoes/segundo**: Ordens de magnitude mais rapido que ferramentas LLM
- **Modelo reutilizavel**: Persiste conhecimento entre sessoes
- **Producao**: 2+ anos no CI/CD da ByteDance
- **Limitacao**: Sem entendimento semantico, sem texto valido

---

## Apendice B: Referencias

1. Liu et al. "Make LLM a Testing Expert" (ICSE 2024) — GPTDroid
2. Yoon et al. "Intent-Driven Mobile GUI Testing" (ICST 2024) — DroidAgent
3. Liu et al. "Vision-driven Automated Mobile GUI Testing" (2024) — VisionDroid
4. Wen et al. "AutoDroid: LLM-powered Task Automation" (MobiCom 2024)
5. He et al. "LLMDroid: Enhancing Automated Mobile App GUI Testing" (FSE 2025)
6. LLM-Explorer: "Towards Efficient and Affordable LLM-based Exploration" (MobiCom 2025)
7. Chen et al. "VLM-Fuzz: Vision Language Model Assisted DFS" (EMSE 2025)
8. Cai et al. "Fastbot2: Reusable Automated Model-based GUI Testing" (ASE 2022)
9. Liu et al. "QTypist: Fill in the Blank for App Testing" (ICSE 2023)
10. AUITestAgent: "Natural Language-Driven GUI Testing" (2024)
