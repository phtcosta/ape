# Análise Rigorosa da Change gh6-aperv-llm-integration

**Data:** 16/03/2026  
**Analista:** opencode  
**Change:** `/openspec/changes/gh6-aperv-llm-integration`  
**Objetivo:** Integração de LLM (Qwen3-VL via SGLang) no APE-RV para quebrar comportamento determinístico na exploração de GUI

---

## Sumário Executivo

A change gh6 propone integrar LLM no APE-RV para quebrar o comportamento determinístico que afeta 47% dos APKs testados nos experimentos exp1+exp2. O plano apresenta uma arquitetura bem pensadas, reusing infrastructure do rvsmart, mas possui **várias inconsistências, ambiguidades e riscos** que precisam ser endereçados antes da implementação.

**Veredicto:** O plano é **parcialmente sólido** mas requer correções significativas antes da implementação. A rastreabilidade PRD→Specs→Design→Tasks tem lacunas, e algumas decisões técnicas carecem de justificativa mais robusta baseada em evidências do estado da arte.

---

## 1. Análise de Consistência e Coerência do Plano

### 1.1 Rastreabilidade (PRD → Specs → Design → Tasks)

| Artefato | Status | Problemas |
|----------|--------|-----------|
| **Proposal.md** | ✅ Completo | Define objetivos claramente |
| **Specs (5 arquivos)** | ⚠️ Parcial | Especificações detalhadas mas com redundâncias |
| **Design.md** | ✅ Completo | Boa arquitetura, mas algumas decisões carecem de fundamentação |
| **Tasks.md** | ✅ Completo | Boas dependências entre grupos |

#### Problemas de Rastreabilidade:

1. **Especificação vs. Implementação Desalinhadas:**
   - A spec `llm-infrastructure/spec.md` diz que `SglangClient` deve usar org.json
   - A spec `llm-routing/spec.md` diz que `LlmRouter` deve criar `SglangClient` com `maxTokens 1024`
   - O **tasks.md item 4.1** NÃO especifica `maxTokens` na construção do cliente
   - **Inconsistência:** A默认值 não está documentada em Tasks

2. **MOP Weight Revert:**
   - O `specs/mop-guidance/spec.md` especifica defaults 500/300/100
   - O `Config.java` atual tem 100/60/20 (v2)
   - O **tasks.md item 1.1** apenas menciona reverter, mas não menciona atualizar a spec original em `openspec/specs/mop-guidance/spec.md`
   - **Gap:** A spec principal não foi atualizada para refletir a mudança

### 1.2 Análise de Completeza

**O que está coberto:**
- ✅ LLM Infrastructure (7 classes copiadas do rvsmart)
- ✅ LLM Routing (dois modos: new-state, stagnation)
- ✅ Prompt Builder (APE-specific)
- ✅ Integração com SataAgent
- ✅ Bug fix: isNewState capture before markVisited()
- ✅ MOP weight revert

**O que está FALTANDO:**
1. **PRD explícito:** Não há PRD formal documento, apenas um "Why" no proposal
2. **Critérios de sucesso mensuráveis:** O proposal menciona "47% deterministic exploration", mas não define:
   - Como medir se o problema foi resolvido?
   - Qual threshold de melhoria esperado?
   - Como comparar com Fastbot?

3. **Análise de custo-benefício quantitativa:**
   - Custo: ~3-5s por chamada LLM, 60-130 chamadas por 10min = +3-11min overhead
   - Benefício: Não há métricas quantitativas de melhoria esperada

---

## 2. Análise de Ambiguidades

### 2.1 Ambiguidades Identificadas

| # | Ambiguidade | Severity | Recomendação |
|---|-------------|----------|--------------|
| 1 | **"Qwen3-VL coordinate accuracy ~84%"** (design.md:368) - não citei fonte | Média | Adicionar referência ao paper/documentation do Qwen3-VL |
| 2 | **coordinate [0,1000)** - se é incluso ou exclusivo? | Alta | Especificar claramente: `[0, 1000)` = 0 a 999 |
| 3 | **"type_text integration"** - quem chama `setInputText()`? Design diz "caller" mas não especifica exatamente onde no código | Alta | Explicitar no flow: SataAgent deve chamar após LlmRouter.selectAction() |
| 4 | **Stagnation threshold** - não há default definido para `graphStableRestartThreshold` em LLM context | Média | Documentar que usa Config.graphStableRestartThreshold existente |
| 5 | **Raw click execution** - não há details de como "MonkeyTouchEvent" é injetado | Alta | Explicitar em design.md: usar `MonkeySourceApe.generateEventsForActionInternal()` existente |
| 6 | **MOP markers em widgets sem MopData** - design.md diz "simply omitted" mas não há verificação de null safety no código | Média | Adicionar verificação defensiva |
| 7 | **Circuit breaker timing** - 60s hardcoded, mas não há como configurar via ape.properties | Baixa | Adicionar `llmCircuitBreakerOpenDurationMs` em Config |

### 2.2 Contradições no Plano

1. **Design.md linha 116 vs. Tasks.md item 3.1:**
   - Design: menciona "epsilon-LLM mode (5% random trigger)" foi REMOVIDO
   - Tasks: não há task para remover código existente relacionado a epsilon-LLM
   - **Contradição:** Se mode foi removido do escopo, não há work necessário

2. **Spec llm-infrastructure vs. Design D7:**
   - Spec diz "org.json conversion is straightforward (~200 lines)"
   - Design D7 repete a mesma estimativa
   - **Nenhuma verificação real** foi feita copiando o código

---

## 3. Análise Comparativa com Estado da Arte

### 3.1 Ferramentas de Referência

| Ferramenta | Tipo | Arquitetura LLM | Posição no Mercado |
|------------|------|-----------------|-------------------|
| **Fastbot** (ByteDance) | Model-based + RL | Não usa LLM | 1165★, produção em CI/CD |
| **APE** (ETH Zurich) | Model-based | Não usa LLM | baseline academic |
| **V-Droid** (MobiCom 2026) | LLM como Verifier | Generative Verifiers | 59.5% task success, 4.3s/step |
| **DroidAgent** | LLM Agent | Direct action generation | - |
| **DroidBot-GPT** | LLM Agent | Vision + text | - |
| **LLMDroid** | LLM Agent | Multimodal | - |
| **rvsmart** (próprio) | LLM hybrid | Vision +SATA | baseline interno |

### 3.2 Análise de Diferenciação

**O que APE-LLM faz diferente:**

| Aspecto | APE-LLM (proposto) | Fastbot2 | V-Droid | rvsmart |
|---------|-------------------|----------|---------|---------|
| **Estratégia** | Hybrid: SATA + LLM punctual | Model + RL | Verifier-driven | Full LLM |
| **LLM Usage** | 2 pontos: new-state, stagnation | N/A | Every step | Every step |
| **MOP Integration** | ✅ Sim (Phase 3) | ❌ | ❌ | ✅ |
| **Model Reuse** | ❌ | ✅ (FBM file) | ❌ | ❌ |
| **Coverage Focus** | Activity/Method | Activity | Task completion | Activity |

### 3.3 O que o Plano FAZ BEM comparado ao Estado da Arte:

1. **MOP Integration:** único entre as ferramentas que usam LLM
2. **Graceful Degradation:** fallback para SATA quando LLM falha
3. **Efficient LLM Usage:** apenas ~60-130 chamadas vs. cada passo
4. **Circuit Breaker:** proteção contra falhas cascata

### 3.4 O que o Plano NÃO Considera do Estado da Arte:

1. **V-Droid (MobiCom 2026):**
   - Usa LLM como **verificador** (evaluates candidate actions) vs. **gerador** (direct action generation)
   - Claim: mais eficiente e preciso que agentes diretos
   - **Não considerado no design** - poderia melhorar accuracy

2. **Fastbot Model Reuse:**
   - Salva modelo em `.fbm` para reuse em runs futuros
   - APE não considera esta feature
   - Poderia acelerar testing em cenários de CI/CD

3. **Multi-step Planning:**
   - V-Droid e DroidAgent fazem planeamento de múltiplos passos
   - APE-LLM faz apenas **um passo** por chamada LLM
   - **Limitação:** não captura intents de longo prazo

4. **Vision-Only Approaches:**
   - Algumas ferramentas (como DroidBot-GPT) usam APIGrounded ou OCR
   - Não mencionado no design

---

## 4. Análise de Riscos e Mitigações

### 4.1 Riscos Técnicos

| Risco | Severidade | Probabilidade | Impacto | Mitigação | Status |
|-------|------------|---------------|---------|-----------|--------|
| **Qwen3-VL coordinate accuracy ~84%** | Alta | Alta | 16% das ações targetam widget errado | Bounds containment + Euclidean fallback | ⚠️ Parcial |
| **Latência LLM (+3-11 min)** | Média | Alta | Tempo de execução | Budget limits, circuit breaker | ✅ Mitigado |
| **Memória (screenshots ~500KB/call)** | Alta | Média | OOM em runs longos | finally block cleanup | ⚠️ Precisa verificar |
| **Screenshot capture API 29+** | Média | Média | Fallback UiAutomation pode falhar | Fallback exists, null → SATA | ✅ Mitigado |
| **Gson→org.json conversion** | Baixa | Baixa | Bugs de parsing | Testes unitários específicos | ⚠️ Sem testes ainda |
| **MOP weight revert breaking experiments** | Média | Alta | Reproducibilidade quebrada | Configurable via properties | ⚠️ Documentar |

### 4.2 Riscos Arquiteturais

| Risco | Descrição | Recomendação |
|-------|-----------|--------------|
| **Acoplamento rvsmart** | Cópia direta cria código duplicado | Considerar Maven module compartilhado no futuro |
| **Configuração dispersa** | 23+ config keys para LLM+MOP | Criar Config section ou classe separada |
| **Test coverage** | Não há testes e2e definidos | Adicionar testes de integração |

### 4.3 Riscos de Pesquisa

| Risco | Descrição | Recomendação |
|-------|-----------|--------------|
| **Effectiveness não provado** | 47% deterministic claim baseado em exp1+exp2 mas não há baseline de melhoria com LLM | Adicionar experiment design com A/B testing |
| **Não supera Fastbot** | Claim "ser melhores que APE e Fastbot" mas não há evidência | Definir métricas de comparação |

---

## 5. Análise de Pontos Positivos e Negativos

### 5.1 Pontos Positivos ✅

1. **Arquitetura bem pensada:**
   - Reutilização de código do rvsmart (7 classes estáveis)
   - Two-mode approach (new-state + stagnation) é inteligente
   - Graceful degradation com fallback SATA

2. **Inovação técnica:**
   - MOP + LLM integration é único no estado da arte
   - Bug fix `isNewState` capture antes do `markVisited()` é necessário
   - Action history ring buffer previne "amnesia" do LLM

3. **Engenharia de Software:**
   - Boas práticas: circuit breaker, budget limits, telemetry
   - Design decisions bem documentadas (D1-D10)
   - Tasks bem organizadas com dependências

4. **Dados Empíricos:**
   - Experiments exp1+exp2 (169 APKs) com resultados estatísticos
   - MOP v1 vs v2 comparison (Wilcoxon p=0.031)

### 5.2 Pontos Negativos ❌

1. **Inconsistência de rastreamento:**
   - Spec original não atualizada para MOP weight revert
   - Tasks não capturam todos os detalhes do design

2. **Falta de PRD formal:**
   - Não há documento PRD estruturado
   - Critérios de sucesso vagos

3. **Sem validação empírica do LLM:**
   - Claim "47% deterministic" mas não mede impacto LLM
   - Não há baseline comparison com Fastbot

4. **Technical debt:**
   - Cópia de código (não library sharing)
   - org.json conversion não testada

5. **Coordenadas:**
   - Qwen [0,1000) não é padrão da indústria
   - Outras VLMs podem usar formatos diferentes

---

## 6. Sugestões de Melhoria

### 6.1 Imediatas (antes da implementação)

| # | Sugestão | Justificativa |
|---|----------|---------------|
| 1 | **Adicionar PRD formal** com KPIs mensuráveis | Definir sucesso antes de implementar |
| 2 | **Atualizar spec original** `openspec/specs/mop-guidance/spec.md` | Consistência de rastreamento |
| 3 | **Detalhar raw click execution** em design.md | Como injetar MonkeyTouchEvent |
| 4 | **Adicionar config key** `llmCircuitBreakerOpenDurationMs` | Flexibilidade |
| 5 | **Adicionar referência** para Qwen3-VL coordinate claim | Credibilidade |
| 6 | **Especificar** onde `setInputText()` é chamado | Completeza |
| 7 | **Adicionar experimento** A/B: SATA-only vs SATA+LLM | Evidência de eficácia |

### 6.2 Medium-term (futuro)

| # | Sugestão | Impacto |
|---|----------|---------|
| 1 | **Avaliar V-Droid approach** (LLM as verifier) | Potencial melhoria de accuracy |
| 2 | **Adicionar model reuse** como Fastbot | CI/CD scenarios |
| 3 | **Criar shared library** para LLM infrastructure | Eliminar duplicação |
| 4 | **Suporte a outras VLMs** (GPT-4V, Claude, Gemini) | Flexibilidade |

### 6.3 Comparação com Claim "Ser Melhores que APE e Fastbot"

**O plano NÃO prova isso.** Recomendações:

1. Definir métricas:
   - Activity coverage (vs. Fastbot)
   - Method coverage (vs. APE baseline)
   - Crash detection rate
   - Tempo médio por action

2. Experiment design:
   - APE baseline (SATA only)
   - APE + MOP (Phase 3)
   - APE + LLM (gh6)
   - Fastbot baseline

3. Publicação de resultados em paper/metadata

---

## 7. Análise de Código Existente

### 7.1 Verificação do Bug isNewState

**Arquivo:** `StatefulAgent.java:614-637`

```java
protected Action updateStateInternal(...) {
    recoverCurrentState();
    buildAndValidateNewState(topComp, info);
    preEvolveModel();
    getGraph().markVisited(newState, getTimestamp());  // ← incrementa visitedCount
    // ...
    if (newState.isUnvisited()) {  // ← SEMPRE false aqui!
        getGraph().markVisited(newState, getTimestamp());
    }
```

**Análise:**
- ✅ Bug confirmado: `markVisited()` é chamado ANTES de qualquer verificação de `isUnvisited()`
- ✅ A correção proposta em design.md (`capturar isNewState ANTES de markVisited`) é correta
- ⚠️ **Problema:** O código atual tem lógica redundante (chama markVisited duas vezes)

### 7.2 Verificação do Stagnation Logic

**Arquivo:** `StatefulAgent.java:877-914`

```java
protected void checkStable() {
    if (graphStableCounter > 0) {
        if (onGraphStable(graphStableCounter)) {
            graphStableCounter = 0;
        }
    }
    // ...
}

public boolean onGraphStable(int counter) {
    if (counter > graphStableRestartThreshold) {
        requestRestart();
        return true;
    }
    return false;
}
```

**Análise:**
- ✅ A lógica atual dispara restart APENAS quando `counter > threshold`
- ✅ O design.md propõe LLM trigger em `threshold/2`, o que é **antes** do restart
- ✅ Esta abordagem é lógica: tenta quebrar estagnação antes de restart pesado

### 7.3 Verificação do SataAgent Flow

**Arquivo:** `SataAgent.java:290-341`

```java
protected Action selectNewActionNonnull() {
    resolved = selectNewActionFromBuffer();
    if (resolved != null) return resolved;
    
    resolved = selectNewActionBackToActivity();
    // ... (cadeia SATA)
    
    resolved = selectNewActionEpsilonGreedyRandomly();
    // ...
}
```

**Análise:**
- ✅ O plano propõe inserir LLM hook **ANTES** desta cadeia (top of method)
- ✅ Esta posição é estratégica: se LLM retorna ação, evita toda a cadeia SATA
- ⚠️ **Preocupação:** Se LLM retorna ação frequentemente, pode bypassar a estratégia SATA completa

---

## 8. Conclusão e Recomendações Finais

### 8.1 Veredicto

| Aspecto | Nota |
|---------|------|
| Consistência interna | 7/10 |
| Rastreabilidade | 6/10 |
| Completeza | 7/10 |
| Alinhamento estado da arte | 6/10 |
| Mitigação de riscos | 7/10 |
| **Geral** | **6.6/10** |

### 8.2 Recomendação

**APROVAR com condições:**

1. ✅ Implementar as correções de ambiguidade (seção 6.1)
2. ✅ Adicionar PRD formal com KPIs mensuráveis
3. ✅ Documentar limitação: "não prova ser melhor que Fastbot"
4. ✅ Executar experimentos de validação antes de claim public

### 8.3 Próximos Passos

1. [ ] Corrigir inconsistências de rastreamento (specs vs tasks)
2. [ ] Detalhar raw click execution
3. [ ] Adicionar PRD formal
4. [ ] Implementar changes conforme tasks.md
5. [ ] Executar experimento de validação A/B

---

## Referências

- Fastbot_Android: https://github.com/bytedance/Fastbot_Android
- V-Droid (MobiCom 2026): https://arxiv.org/abs/2503.15937
- DroidAgent: https://github.com/coinse/droidagent
- DroidBot-GPT: https://github.com/MobileLLM/DroidBot-GPT
- Qwen3-VL: https://github.com/QwenLM/Qwen3-VL/issues/1486

---

*Relatório gerado automaticamente via análise rigorosa de change OpenSpec.*
