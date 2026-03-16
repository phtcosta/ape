# Análise Codex — gh6-aperv-llm-integration

**Escopo**
Análise rigorosa da change em `openspec/changes/gh6-aperv-llm-integration` e inspeção de código-fonte em APE-RV e rvsmart. Não há implementação nesta etapa, apenas validação de plano, coerência, rastreabilidade e aderência técnica. Inclui revisão de estado da arte em LLM para testes Android com fontes externas.

**Resumo Executivo**
A change propõe integrar LLM (Qwen3‑VL via SGLang) ao loop de exploração do APE‑RV em dois pontos (primeira visita e estagnação), além de reverter pesos MOP. O plano é ambicioso e alinhado com tendência de LLM‑guidance, mas há incoerências técnicas importantes: (1) o caminho de integração do “raw click” não existe no pipeline de eventos do APE; (2) a estratégia de estagnação foi descrita em `SataAgent`, mas o gatilho real de estabilidade é em `StatefulAgent`; (3) requisitos de robustez do `SglangClient` entram em conflito com a cópia direta do código do rvsmart (que lança exceções). Há também lacunas de rastreabilidade com o PRD vigente, que não contempla LLM no escopo do produto. Esses pontos precisam ser resolvidos para a change ser consistente, verificável e superior a ferramentas como FastBot e APE baseline.

**Rastreabilidade (PRD → Specs → Design → Tasks)**
- O PRD atual (`docs/PRD.md`) não inclui LLM como objetivo ou requisito de produto. A change não referencia o PRD nem atualiza o “problema/solução”. Resultado: rastreabilidade fraca do ponto de vista de requisitos de produto. Isso deve ser sanado para evitar “feature drift”.
- A change é bem estruturada internamente (proposal → design → specs → tasks), porém há divergências entre specs e o código real do APE.
- Especificações detalham classes, invariantes e fluxos, mas a integração com o pipeline do MonkeySourceApe (responsável por gerar eventos) não está plenamente rastreada.

**Coerência do Plano e Ambiguidades**
Pontos positivos de coerência:
- A motivação é sustentada por experimento (determinismo em 47% dos APKs) e pelo ganho demonstrado dos pesos MOP v1.
- Reuso de infraestrutura do rvsmart reduz risco e tempo de desenvolvimento.
- A proposta foca em LLM “pontual” (new‑state + estagnação), alinhada a custos reais e evidências empíricas (minimizar chamadas contínuas).

Ambiguidades e inconsistências relevantes:
- **Stagnation hook em local errado**: specs e tasks dizem que o hook é em `SataAgent`, mas o gatilho real de estabilidade é `StatefulAgent.onGraphStable()` e `checkStable()` em `StatefulAgent`. Não há override em `SataAgent`. Isso gera divergência clara entre o “onde” e o “como”.
- **Raw click não é suportado**: o design define `LlmActionResult` com `raw click` e afirma execução via `MonkeyTouchEvent`, mas o pipeline do APE só aceita `Action`/`ModelAction` (ver `MonkeySourceApe.generateEventsForActionInternal()`), sem suporte a clique coordenado fora de `ModelAction`. Isso precisa de um novo tipo de `Action` ou um caminho explícito para injetar eventos fora do modelo.
- **Contrato de robustez vs. cópia do rvsmart**: o spec `llm-infrastructure` exige que `SglangClient.chat()` não lance exceções, mas o `SglangClient` do rvsmart lança `LlmException`. A tarefa 2.2 só pede “copiar e converter Gson → org.json”, sem ajustar essa semântica. Há conflito direto entre spec e execução real.
- **Action history duplicada**: o APE já mantém `Model.actionHistory` e registra ações em `MonkeySourceApe.generateEventsForAction()`. A proposta cria um buffer paralelo com resultados, mas não define claramente como integrar com o histórico já existente, nem como lidar com ações “raw”.
- **Nomes de campos**: o plano usa `_lastState` e `_stateBeforeLast` mesmo já existindo `lastState` em `StatefulAgent`. Isso tende a criar confusão e bug sutil se não for projetado com muito cuidado.
- **Uso de MOP markers no prompt**: o spec menciona usar `MopData.getWidget()` com shortId; isso está correto, mas não está explicitado como lidar com widgets sem resourceId (muito comum em apps modernos). Isso pode degradar o sinal MOP no prompt sem aviso.

**Coerência com o Código do APE‑RV**
Principais observações a partir de `StatefulAgent` e `SataAgent`:
- `StatefulAgent.updateStateInternal()` chama `getGraph().markVisited(newState, ts)` antes de qualquer seleção de ação. Isso explica o bug de “new state” identificado; a correção é necessária, mas também existe um segundo `markVisited()` quando `newState.isUnvisited()` — essa lógica precisa ser revisitada para evitar incrementos redundantes.
- `StatefulAgent.checkStable()` e `onGraphStable()` são os únicos pontos onde a estabilidade do grafo é avaliada. Qualquer “LLM‑stagnation hook” precisa estar aqui, não em `SataAgent`.
- O pipeline de eventos em `MonkeySourceApe.generateEventsForActionInternal()` só entende ações conhecidas (MODEL_CLICK/LONG_CLICK/SCROLL/BACK etc.). Não há caminho para clique coordenado sem `ModelAction`.
- `GUITreeNode.setInputText()` já é suportado por `MonkeySourceApe` na fase de `MODEL_CLICK`, portanto a proposta de `type_text` é tecnicamente viável.

**Coerência com o Código do rvsmart**
- O rvsmart possui infraestrutura madura de LLM, mas é acoplada ao seu próprio loop (`AgentLoop.tryLlmAction`) e usa exceções para sinalizar falhas. Copiar classes sem ajuste semântico viola os invariantes definidos nas specs do APE‑RV.
- O rvsmart aplica “boundary reject” e faz fallback seguro; isso é consistente com o design da change.

**Avaliação da Capacidade de Exploração Sistemática**
Pontos fortes:
- **LLM em pontos de alto valor** (primeira visita + estagnação) tende a reduzir repetição determinística e melhorar a diversificação do caminho.
- **MOP markers no prompt** fornecem informação útil sobre relevância semântico‑funcional, alinhada com o objetivo RVSEC.
- **Fallback total para SATA** evita regressão de cobertura quando LLM falha.

Limitações para “exploração sistemática e completa”:
- **LLM só na primeira visita**: se a primeira sugestão for ruim, não há mecanismo claro para “revisitar” com LLM depois (ex.: listas longas, tela dinâmica). Isso reduz a chance de sistematicidade.
- **Sem scroll no LLM**: SATA fará scroll, mas a LLM não pode priorizar scrolls semânticos em listas relevantes. Isso pode limitar exploração de conteúdo denso.
- **Raw click sem tracking**: mesmo que implementado, a ação não é registrada no modelo, quebrando rastreabilidade de transições — isso mina a sistematicidade do grafo.

**Riscos e Mitigações**
Riscos técnicos:
- **Risco: inconsistência de integração do raw click**. O pipeline atual não suporta injeção de coordenadas fora do `ModelAction`. Mitigação: introduzir um `ActionType` explícito (ex.: `MODEL_RAW_CLICK`) ou um `Action` com coordenadas que o `MonkeySourceApe` consiga traduzir em `MonkeyTouchEvent`.
- **Risco: hook de estagnação no lugar errado**. Implementar em `SataAgent` não dispara porque o gatilho real é `StatefulAgent.onGraphStable()`. Mitigação: mover o hook para `StatefulAgent.onGraphStable()` ou `checkStable()` e padronizar nos specs.
- **Risco: exceções no `SglangClient`**. A cópia direta do rvsmart viola o invariant de “não lançar exceções”. Mitigação: ajustar `SglangClient` para retornar null e registrar falhas sem lançar, alinhado ao spec.
- **Risco: custos de latência**. `llmTimeoutMs=15000` pode bloquear exploração em dispositivos lentos. Mitigação: permitir timeouts menores por perfil e logging de tempo; manter circuit breaker agressivo.
- **Risco: conflito com histórico existente**. O novo ring buffer pode divergir do `Model.actionHistory`. Mitigação: reutilizar `Model.actionHistory` com camada de pós‑processamento, ou documentar explicitamente que o ring buffer é separado e por quê.

Riscos de produto/experimento:
- **Risco: falta de alinhamento com PRD**. LLM não está descrito como requisito do produto. Mitigação: atualizar PRD (ou criar “delta PRD” específico) para cobrir LLM guidance como objetivo formal.

**Sugestões de Melhoria (para superar APE/FastBot)**
1. **Cobertura‑aware LLM**: permitir re‑chamadas em revisitas com “baixo ganho de cobertura”, não só no primeiro estado (inspirado em LLMDroid). citeturn2search1
2. **LLM para listas longas**: habilitar scroll semântico em telas com listas ou WebView; pelo menos oferecer uma heurística “se lista grande, scroll”.
3. **Integração explícita de raw click**: adicionar um ActionType coordenado e uma trilha mínima no modelo (mesmo que sem `StateTransition`) para manter rastreabilidade.
4. **Telemetria comparativa**: logar taxa de “LLM‑override vs SATA” por estado e por atividade, para comparar com FastBot e identificar regiões que a LLM realmente ajuda.
5. **PRD update**: incorporar objetivos mensuráveis de LLM (ex.: redução de determinismo, aumento de cobertura, custo por hora). Sem isso, a change não é formalmente rastreável.

**Estado da Arte (LLM para testes/automação Android)**
- **GPTDroid (ICSE 2024)** propõe formular testes como conversa LLM‑app, com memória funcional para guiar exploração e ganhos de cobertura/bugs em apps reais. citeturn1search0
- **LLMDroid (FSE 2025)** usa LLM de forma econômica, alternando exploração autônoma e guidance quando o ganho de cobertura desacelera. Esse padrão é diretamente aplicável ao APE‑RV. citeturn2search1
- **AutoDroid (MobiCom 2024)** mostra que UI representation + memory injection + otimização de consultas melhoram precisão e custo em automação móvel; a ideia de “exploration‑based memory injection” é útil para os prompts do APE‑RV. citeturn2search0
- **DroidBot‑GPT (arXiv 2023/2024)** traduz estado GUI e ações em prompt e usa LLM para decidir ações, com avaliação em tarefas multi‑passo. É referência de baseline LLM‑GUI simples. citeturn3search0
- **VisionDroid (TSE 2025)** aplica MLLM para detecção de bugs funcionais não‑crash com agentes especializados, mostrando que visão + histórico pode capturar defeitos que testes tradicionais não veem. citeturn2search3

**Conclusão**
A change é promissora e alinhada com o estado da arte, mas precisa corrigir inconsistências cruciais para ser coerente e rastreável. O ganho real de “exploração sistemática e completa” depende de resolver o caminho de execução de “raw click”, realinhar o hook de estagnação e formalizar a integração no PRD. Sem esses ajustes, a proposta corre risco de não entregar a superioridade frente a APE/FastBot.
