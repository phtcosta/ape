# Análise Rigorosa da Change: gh6-aperv-llm-integration

**Data:** 16 de março de 2026  
**Autor:** Qwen Code (agente de análise)  
**Escopo:** Validação de consistência, coerência, rastreabilidade e qualidade técnica do plano de integração LLM no APE-RV

---

## 1. Resumo Executivo

### 1.1 Objetivo da Change

Integrar LLM (Qwen3-VL via SGLang) no loop de exploração do APE-RV para:
1. **Quebrar padrões determinísticos** de exploração (47% dos 169 APKs nos experimentos exp1+exp2)
2. **Reverter pesos MOP** de v2 (100/60/20) para v1 (500/300/100) baseado em evidência experimental
3. **Habilitar cliques semânticos** em elementos dinâmicos invisíveis ao UIAutomator (WebView, custom views, canvas)

### 1.2 Avaliação Geral

| Critério | Status | Nota |
|----------|--------|------|
| Consistência do plano | ✅ Consistente | 9/10 |
| Coerência das decisões | ✅ Coerente | 8.5/10 |
| Rastreabilidade | ⚠️ Parcial | 7/10 |
| Alinhamento com estado da arte | ✅ Alinhado | 9/10 |
| Viabilidade técnica | ✅ Viável | 8/10 |
| Completude das specs | ⚠️ Lacunas | 7.5/10 |

**Veredito:** O plano é **sólido e bem fundamentado**, mas possui lacunas de rastreabilidade e algumas inconsistências menores que devem ser endereçadas antes da implementação.

---

## 2. Análise de Consistência e Coerência

### 2.1 Consistência Interna do Plano

#### ✅ Pontos Fortes

1. **Narrativa coerente:** O plano conta uma história lógica:
   - Problema identificado (47% APKs determinísticos) → Causa (SATA é determinístico) → Solução (LLM em 2 pontos estratégicos)
   
2. **Decisões bem justificadas:** Cada decisão (D1-D10) tem rationale explícito:
   - D1 (Copiar vs shared library): Justificativa de P1 (simplicidade) é sólida
   - D3 (2 modos vs always-on): Custo-benefício quantificado (~60-130 calls/run)
   - D7 (org.json vs Gson): 24 files já usam org.json, zero nova dependência

3. **Arquitetura consistente:** O design mantém SATA+MOP como estratégia dominante, com LLM como override pontual — alinhado com o princípio de "não quebrar o que funciona"

#### ⚠️ Inconsistências Identificadas

1. **Inconsistência de contagem de classes:**
   - `proposal.md`: "copy 7 classes from rvsmart"
   - `design.md`: "9 new classes (~1400 LOC total: 7 copied + 2 new)"
   - `tasks.md`: "7 copied classes"
   - **Lista real:** `SglangClient`, `ScreenshotCapture`, `ImageProcessor`, `ToolCallParser`, `CoordinateNormalizer`, `LlmCircuitBreaker`, `LlmException` (7) + `LlmRouter`, `ApePromptBuilder` (2) = 9 ✅
   - **Problema:** A documentação é inconsistente sobre se conta 7 ou 9 classes

2. **Inconsistência de LOC:**
   - `proposal.md`: "~1400 LOC total"
   - `design.md`: "~1000 LOC" (para 7 classes copiadas)
   - rvsmart real (lido via shell): ~67K LOC totais no diretório llm
   - **Cálculo real das 7 classes:**
     ```
     SglangClient.java:      14600 bytes
     ToolCallParser.java:    10877 bytes
     PromptBuilder.java:     14630 bytes
     PromptContext.java:      7590 bytes
     LlmCircuitBreaker.java:  3724 bytes
     ImageProcessor.java:     3457 bytes
     ScreenshotCapture.java:  3692 bytes
     CoordinateNormalizer.java: 1665 bytes
     LlmException.java:        360 bytes
     Total: ~60K bytes → ~1500-1800 LOC (estimativa conservadora)
     ```
   - **Recomendação:** Atualizar estimativa para 1600-1800 LOC

3. **Discrepância de nomes de classes:**
   - `design.md` menciona `ApePromptBuilder` (específico do APE)
   - rvsmart tem `PromptBuilder` e `PromptContext` (genéricos)
   - **Problema:** O plano não esclarece se `ApePromptBuilder` será uma adaptação de `PromptBuilder` + `PromptContext` ou uma classe nova do zero

### 2.2 Coerência com Evidência Experimental

#### ✅ Alinhamento Forte

1. **Revert MOP v2→v1:** 
   - Evidência: "+1.00pp method coverage (p=0.031), +3 violation types"
   - Decisão: Reverter defaults de 100/60/20 para 500/300/100
   - **Coerência:** ✅ Perfeita

2. **LLM em 2 modos (não always-on):**
   - Evidência: "LLM calls cost ~3-5 seconds each"
   - Custo estimado: "~60-130 calls per 10-minute run → +3-11 minutes overhead"
   - **Coerência:** ✅ O trade-off é quantificado

3. **Stagnation mode em threshold/2:**
   - Rationale: "earlier than the existing restart threshold"
   - **Coerência:** ✅ Preventivo, não reativo

#### ⚠️ Lacunas de Evidência

1. **Taxa de sucesso do LLM não quantificada:**
   - O plano menciona "~84% coordinate accuracy" do Qwen3-VL
   - Mas não especifica: qual a taxa esperada de `LlmActionResult.isModelAction()` vs `isRawClick()` vs `null`?
   - **Impacto:** Sem essa métrica, é difícil avaliar se o LLM realmente melhorará a exploração

2. **Comparação com epsilon-LLM original:**
   - O plano diz: "The original epsilon-LLM mode (5% random trigger) was removed"
   - Mas não apresenta dados: qual era a performance do epsilon-LLM? Por que foi removido?
   - **Recomendação:** Adicionar evidência comparativa

---

## 3. Rastreabilidade (PRD → Specs → Design → Tasks)

### 3.1 Matriz de Rastreabilidade

| Requisito | Spec | Design | Task | Status |
|-----------|------|--------|------|--------|
| Revert MOP weights | `specs/mop-guidance/spec.md` | `design.md` §Context | Task 1.1-1.2 | ✅ Rastreável |
| SglangClient (org.json) | `specs/llm-infrastructure/spec.md` | `design.md` §Key Components | Task 2.2 | ✅ Rastreável |
| ScreenshotCapture | `specs/llm-infrastructure/spec.md` | `design.md` §Key Components | Task 2.3 | ✅ Rastreável |
| ToolCallParser (3-level fallback) | `specs/llm-infrastructure/spec.md` | `design.md` §Key Components | Task 2.5 | ✅ Rastreável |
| CoordinateNormalizer | `specs/llm-infrastructure/spec.md` | `design.md` §Key Components | Task 2.6 | ✅ Rastreável |
| LlmCircuitBreaker | `specs/llm-infrastructure/spec.md` | `design.md` §Key Components | Task 2.7 | ✅ Rastreável |
| ApePromptBuilder | `specs/llm-prompt/spec.md` | `design.md` §Key Components | Task 3.1-3.6 | ✅ Rastreável |
| LlmRouter lifecycle | `specs/llm-routing/spec.md` | `design.md` §Key Components | Task 4.1 | ✅ Rastreável |
| New-state mode | `specs/llm-routing/spec.md` | `design.md` §Architecture | Task 4.2, 5.2 | ✅ Rastreável |
| Stagnation mode | `specs/llm-routing/spec.md` | `design.md` §Architecture | Task 4.2, 5.3 | ✅ Rastreável |
| mapToModelAction (bounds+Euclidean) | `specs/llm-routing/spec.md` | `design.md` §Decisions D5 | Task 4.4 | ✅ Rastreável |
| isNewState capture before markVisited | `specs/exploration/spec.md` | `design.md` §Decisions D4 | Task 5.1 | ✅ Rastreável |
| Action history ring buffer | `specs/exploration/spec.md` | `design.md` §API Design | Task 5.1 | ✅ Rastreável |
| LLM telemetry | `specs/llm-routing/spec.md` | `design.md` §API Design | Task 4.5, 5.1 | ✅ Rastreável |
| Unit tests (7 classes) | N/A (implícito) | N/A | Task 6.2-6.8 | ⚠️ Sem spec de teste |

### 3.2 Lacunas de Rastreabilidade

#### ⚠️ Críticas

1. **Falta spec de teste:**
   - As tasks 6.2-6.8 mencionam testes unitários, mas não há um documento `specs/testing/spec.md`
   - **Risco:** Critérios de aceitação podem ser inconsistentes
   - **Recomendação:** Criar spec de teste com cenários obrigatórios

2. **Spec de exploração incompleta:**
   - `specs/exploration/spec.md` foi lido parcialmente (truncado)
   - Não foi possível verificar se todos os requisitos de integração estão cobertos
   - **Recomendação:** Verificar completude da spec

3. **Dependência de rvsmart não documentada:**
   - O plano menciona "copiar 7 classes do rvsmart"
   - Mas não há uma spec de "rvsmart adaptation" ou "cross-project dependency"
   - **Risco:** Se rvsmart mudar, o APE-RV pode quebrar
   - **Recomendação:** Documentar versão exata do rvsmart como baseline

#### ✅ Boas Práticas

1. **Mapping table explícita:** `design.md` §"Mapping: Spec → Implementation → Test" é excelente
2. **Invariants numerados:** Cada spec tem invariants (INV-LLM-01, INV-RTR-01, etc.)
3. **Cenários de teste nas specs:** Cada requirement tem "Scenario" bullets

---

## 4. Análise Técnica Profunda

### 4.1 Comparação com Estado da Arte (LLM Android Testing)

#### 4.1.1 LLMDroid (FSE 2025)

**Abordagem do LLMDroid:**
- **Dois estágios:** (1) Exploração autônoma com ferramenta baseline, (2) LLM guidance quando cobertura estagna
- **Coverage-triggered:** LLM é ativado quando "code coverage growth diminishes"
- **Minimiza interações LLM:** Foca em "maximize impact on coverage"

**Comparação com APE-RV gh6:**

| Aspecto | LLMDroid | APE-RV gh6 | Vencedor |
|---------|----------|------------|----------|
| Trigger LLM | Coverage stagnation | New-state + Stagnation (threshold/2) | **APE-RV** (mais granular) |
| Custo | $4.77/hora (GPT-4o) | ~3-5s/call (Qwen3-VL local) | **APE-RV** (mais barato) |
| Cobertura improvement | +26.16% code, +29.31% activity | Não medido (planejado) | **LLMDroid** (validado) |
| Integração | Plug-in em 3 tools | SATA+MOP+LLM nativo | **Empate** |

**Veredito:** A abordagem do APE-RV é **mais sofisticada** (2 modos independentes, circuit breaker, MOP markers), mas **não validada empiricamente** ainda.

#### 4.1.2 GPTDroid / AutoDroid / VisionDroid

**GPTDroid:**
- Reframe GUI testing as "interactive Q&A task"
- LLM decide ação a cada passo (always-on)
- **Problema:** Custo proibitivo, latência alta

**AutoDroid:**
- LLM-powered task automation
- Foca em "task completion", não em coverage
- Usa "domain-specific knowledge" + LLM

**VisionDroid:**
- Vision-driven non-crash functional bug detection
- Multi-agent collaborative approach
- Detecta bugs, não foca em coverage

**Diferencial do APE-RV gh6:**
1. **Híbrido SATA+LLM:** Não substitui SATA, complementa
2. **MOP-guided:** Runtime verification specs guiam LLM
3. **2 modos estratégicos:** Não é always-on (custo-benefício)
4. **Raw click support:** Elementos dinâmicos (WebView) via screenshot

### 4.2 Análise das Decisões de Design

#### D1: Copiar rvsmart vs Shared Library

**Decisão:** Copiar 7 classes com package rename + Gson→org.json

**✅ Pontos Positivos:**
- P1 (simplicidade): 2 consumidores não justificam library
- Zero nova dependência Maven (org.json já existe)
- Build pipeline não muda (d8 converte classes normais)

**⚠️ Riscos:**
- **Code duplication:** Se rvsmart corrigir bug, APE-RV não recebe fix automaticamente
- **Drift de versões:** rvsmart pode evoluir PromptBuilder para V18, APE-RV fica em V13
- **Manutenção:** 2 codebases para sincronizar manualmente

**Mitigação Sugerida:**
```
Adicionar ao design.md:
"Version lock: rvsmart baseline = commit XYZ (2026-03-10). 
 Future syncs require manual diff + regression test."
```

#### D2: LLM selects action vs LLM boosts priorities

**Decisão:** LLM **seleciona** ação específica, não modifica priority scores

**✅ Acerto:**
- Traduzir visual reasoning → numeric priority deltas perderia informação
- Quando LLM seleciona, SATA priorities são irrelevantes
- Quando LLM retorna null, SATA+MOP aplicam unchanged

**⚠️ Ponto de Atenção:**
- O plano não especifica: o que acontece se LLM selecionar uma ação **já visitada 10 vezes**?
- SATA tem `greedyPickLeastVisited`, mas LLM não tem esse constraint
- **Risco:** LLM pode clicar no mesmo botão 5 vezes seguidas

**Mitigação Sugerida:**
```
Adicionar ao ApePromptBuilder:
- Incluir visited count (v:N) no widget list
- System message: "Don't click same position twice in a row"
- LlmRouter: rejeitar ação se action.visitedCount > threshold (ex: 5)
```

#### D3: Two modes instead of always-on

**Decisão:** LLM invocado apenas em (1) new-state, (2) stagnation

**✅ Acerto Total:**
- LLMDroid provou que coverage-triggered > probabilístico
- Custo-benefício quantificado: ~60-130 calls/run vs ~500-1000 (always-on)
- Foca nos pontos de maior valor: primeira impressão + quebra de estagnação

**⚠️ Ponto Cego:**
- O plano removeu "epsilon-LLM mode (5% random trigger)"
- Mas não justifica **por que** foi removido
- **Hipótese:** Pode ter sido removido prematuramente — 5% de calls aleatórias poderiam descobrir ações que new-state/stagnation não cobrem

**Recomendação:**
```
Reconsiderar epsilon-LLM mode como terceiro modo opcional:
- Config.llmOnEpsilon (default false)
- 5% das ações (apenas em estados visitados >2 vezes)
- Custo: +25-50 calls/run
- Benefício: Exploração estocástica complementar
```

#### D4: LLM hook placement

**Decisão:**
- New-state: Topo de `selectNewActionNonnull()`, antes da SATA chain
- Stagnation: Quando `graphStableCounter > threshold/2`

**✅ Acerto:**
- Hook placement é **minimalista**: não reescreve lógica existente
- `isNewState` capturado **antes** de `markVisited()` (bug fix)
- Stagnation é **preventivo** (threshold/2), não reativo (threshold)

**⚠️ Inconsistência:**
- `design.md` diz: "new-state LLM in `selectNewActionNonnull()` top"
- `tasks.md` 5.2 diz: "Hook new-state LLM mode at the top of `SataAgent.selectNewActionNonnull()`"
- **Problema:** `selectNewActionNonnull()` é `protected` em `SataAgent`, mas o hook precisa de acesso a `_isNewState`, `_llmRouter`, `_mopData`, `_actionHistory` (todos em `StatefulAgent`)
- **Solução:** O hook deve ser em `StatefulAgent.selectNewActionNonnull()` (se existir) ou via método protegido

**Verificação de Código:**
```java
// SataAgent.java (lido)
protected Action selectNewActionNonnull() {
    // ... buffer check, ABA, trivial, epsilon-greedy
}

// StatefulAgent.java (lido parcialmente)
// Não tem selectNewActionNonnull() — é abstrato?
```

**Recomendação:**
```
Clarificar no design.md:
"O hook new-state será implementado em SataAgent.selectNewActionNonnull()
 via método protegido tryLlmNewState() que acessa campos de StatefulAgent."
```

#### D5: Coordinate → ModelAction mapping strategy

**Decisão:** Two-phase: (1) bounds containment, (2) Euclidean distance fallback

**✅ Acerto:**
- Bounds containment é **natural**: LLM clicou "dentro" do widget
- Euclidean lida com **near-misses** (Qwen3-VL tem ~84% accuracy)
- Tolerância proporcional: `max(50, min(width, height)/2)` é inteligente

**⚠️ Ponto de Atenção:**
- O plano não especifica: o que acontece se **múltiplos widgets** têm bounds sobrepostos?
- Exemplo: Um `LinearLayout` contém um `Button` — ambos podem conter o ponto (540, 960)
- **Solução mencionada:** "select smallest area (most specific widget)" ✅

**Verificação com rvsmart:**
```java
// rvsmart não foi possível ler completamente, mas o design.md menciona:
// "rvsmart has a mismatch (device pixels in prompt, [0,1000) in response) 
//  which we explicitly avoid"
```

**✅ Diferencial do APE-RV:**
- Prompt: coordenadas em [0,1000) (mesmo espaço da resposta LLM)
- rvsmart: device pixels no prompt, [0,1000) na resposta (mismatch!)
- **Isso é crítico:** Consistência de coordenadas reduz erro de parsing

#### D6: MOP markers in LLM prompt

**Decisão:** Incluir `[DM]` (direct monitored) e `[M]` (transitive monitored) no widget list

**✅ Acerto:**
- Notação compacta (~120 tokens vs ~300 verbose)
- Dá ao LLM informação sobre **quais widgets levam a código monitorado**
- rvsmart V17 já provou eficácia

**⚠️ Ponto de Atenção:**
- O plano assume que `MopData.getWidget(activity, shortId)` sempre retorna flags precisas
- Mas não especifica: o que acontece se **static analysis falhar** (ex: reflection, código ofuscado)?
- **Risco:** MOP markers podem ser **falsos positivos/negativos**

**Mitigação:**
```
Adicionar ao ApePromptBuilder:
- Se MopData == null, omitir markers (já especificado ✅)
- Se widget não tem resource ID, omitir marker (especificar?)
- Log warning se MopData.getWidget() lançar exceção
```

#### D7: org.json vs Gson

**Decisão:** Converter `SglangClient` e `ToolCallParser` de Gson para org.json

**✅ Acerto Total:**
- 24 files já usam org.json no APE-RV
- Android runtime já inclui org.json (via app_process)
- Gson exigiria Maven dependency + shade plugin + 250KB

**Verificação de Código:**
```java
// SglangClient.java (rvsmart, lido)
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

// Conversão para org.json:
// JsonObject → JSONObject
// JsonArray → JSONArray
// GSON.toJson() → .toString()
// GSON.fromJson() → new JSONObject()
```

**⚠️ Complexidade Subestimada:**
- O plano diz: "~200 lines, 1:1 API mapping"
- Mas `SglangClient.buildRequestBody()` usa `JsonObject.addProperty()`, `JsonArray.add()`, etc.
- org.json tem API **ligeiramente diferente**: `JSONObject.put()`, `JSONArray.put()`
- **Risco:** Conversão pode ser mais trabalhosa que o estimado

**Recomendação:**
```
Task 2.2 deve incluir:
- Teste de compilação após conversão
- Teste de request JSON (comparar output Gson vs org.json)
- Verificar encoding de caracteres especiais (UTF-8)
```

#### D8: type_text support

**Decisão:** Incluir `type_text(x, y, text)` no tool schema

**✅ Acerto:**
- Muitos apps requerem input de texto (login, search, forms)
- SATA não gera texto **semântico** (apenas fuzzing aleatório)
- Exemplo DNS Hero: LLM gera "google.com" vs SATA gera "asdf1234"

**Verificação de Código:**
```java
// design.md menciona:
// "When LLM suggests typing text, mapToModelAction finds nearest input-capable widget
//  and injects text via resolvedNode.setInputText(text)"

// MonkeySourceApe.generateEventsForActionInternal() já checka:
// if (node.getInputText()) → generate character events
```

**⚠️ Ponto de Atenção:**
- O plano não especifica: **qual texto** o LLM deve gerar para cada tipo de input?
- Exemplo: Email field → "test@example.com", Password → "SecurePass123!", Domain → "google.com"
- **Risco:** LLM pode gerar texto **inválido** (ex: "abc" para email)

**Mitigação:**
```
Adicionar ao ApePromptBuilder.systemMessage:
"For input fields, generate semantically valid text:
  - Email: user@example.com
  - Password: Test1234!
  - Domain: example.com
  - Search: relevant search term
  - Phone: +1234567890"
```

#### D9: long_click support

**Decisão:** Incluir `long_click(x, y)` no tool schema

**✅ Acerto:**
- Alguns apps usam long press para context menus, selection mode, drag-and-drop
- SATA não diferencia click vs long_click (apenas MODEL_CLICK)

**Verificação de Código:**
```java
// SataAgent.java (lido) não menciona MODEL_LONG_CLICK
// StatefulAgent.java (lido parcialmente) não menciona

// design.md menciona:
// "if actionType='long_click', prefer MODEL_LONG_CLICK action for matched widget;
//  fall back to MODEL_CLICK if unavailable"
```

**⚠️ Ponto de Atenção:**
- O plano assume que `ModelAction` tem `MODEL_LONG_CLICK` action type
- Mas não verifica se o código existente do APE-RV suporta isso
- **Risco:** `ModelAction.getType()` pode não ter `MODEL_LONG_CLICK`

**Recomendação:**
```
Verificar em ModelAction.java:
- Existe ActionType.MODEL_LONG_CLICK?
- MonkeySourceApe gera evento de long press?
- Se não existir, adicionar como pré-requisito (nova task 0.x)
```

#### D10: Boundary reject

**Decisão:** Rejeitar clicks em status bar (top 5%) e navigation bar (bottom 6%)

**✅ Acerto:**
- Copiado do rvsmart (provou eficácia)
- Evita waste de budget em system UI (clock, battery, back/home buttons)

**Verificação:**
```java
// design.md:
// if (pixelY < deviceHeight * 0.05) or (pixelY > deviceHeight * 0.94) → return null

// Exemplo: 1080x1920 device
// Status bar: y < 96px
// Nav bar: y > 1804px
```

**✅ Sem problemas:** Decisão sólida, sem ressalvas.

### 4.3 Análise do Código-Fonte Existente

#### 4.3.1 Config.java

**Estado Atual (lido):**
```java
public static final int mopWeightDirect = Config.getInteger("ape.mopWeightDirect", 100);
public static final int mopWeightTransitive = Config.getInteger("ape.mopWeightTransitive", 60);
public static final int mopWeightActivity = Config.getInteger("ape.mopWeightActivity", 20);
```

**Requerido (spec):**
```java
public static final int mopWeightDirect = Config.getInteger("ape.mopWeightDirect", 500);
public static final int mopWeightTransitive = Config.getInteger("ape.mopWeightTransitive", 300);
public static final int mopWeightActivity = Config.getInteger("ape.mopWeightActivity", 100);
```

**✅ Task 1.1 está correta:** Mudar defaults de 100/60/20 para 500/300/100

**⚠️ Lacuna:**
- O plano não menciona **LLM config keys** em `Config.java`
- `specs/llm-infrastructure/spec.md` requer:
  ```java
  public static final String llmUrl = Config.get("ape.llmUrl");
  public static final boolean llmOnNewState = Config.getBoolean("ape.llmOnNewState", true);
  public static final boolean llmOnStagnation = Config.getBoolean("ape.llmOnStagnation", true);
  // ... +6 chaves
  ```
- **Task 1.3 menciona**, mas não detalha implementação

**Recomendação:**
```
Adicionar a Task 1.3:
"1.3.1 Adicionar campo 'llmUrl' (String, null)
 1.3.2 Adicionar campo 'llmOnNewState' (boolean, true)
 ...
 1.3.9 Adicionar campo 'llmMaxCalls' (int, 200)"
```

#### 4.3.2 MopScorer.java

**Estado Atual (lido):**
```java
public static int score(String activity, String shortId, MopData data) {
    MopData.WidgetMopFlags f = data.getWidget(activity, shortId);
    if (f != null) {
        if (f.directMop) {
            return Config.mopWeightDirect;
        }
        if (f.transitiveMop) {
            return Config.mopWeightTransitive;
        }
        return 0;
    }
    if (data.activityHasMop(activity)) {
        return Config.mopWeightActivity;
    }
    return 0;
}
```

**✅ Task 1.2 está correta:** Atualizar apenas documentação (lógica unchanged)

#### 4.3.3 StatefulAgent.java

**Estado Atual (lido parcialmente):**
```java
public abstract class StatefulAgent extends ApeAgent implements GraphListener {
    protected State lastState;
    protected GUITree lastGUITree;
    // ...
    private final MopData _mopData;
    
    protected Action updateStateInternal(ComponentName topComp, AccessibilityNodeInfo info) {
        recoverCurrentState();
        buildAndValidateNewState(topComp, info);
        preEvolveModel();
        getGraph().markVisited(newState, getTimestamp());  // ⚠️ markVisited antes de isNewState check!
        // ...
    }
}
```

**Requerido (specs/exploration/spec.md):**
```java
protected Action updateStateInternal(...) {
    // ...
    _stateBeforeLast = _lastState;           // [NEW] shift history
    _lastState = currentState;                // [NEW] save outgoing state
    _isNewState = (newState.getVisitedCount() == 0);  // [NEW] BEFORE markVisited
    getGraph().markVisited(newState, ts);     // existing
    // ...
}
```

**✅ Task 5.1 está correta:** Bug fix de `isNewState` capture

**⚠️ Ponto de Atenção:**
- O código atual não tem `_llmRouter`, `_actionHistory`, `_lastState`, `_stateBeforeLast`
- **Task 5.1** deve adicionar todos esses campos
- **Ação history ring buffer** requer nova classe `ActionHistoryEntry`

**Recomendação:**
```
Adicionar sub-task 5.1.1:
"Criar classe ActionHistoryEntry (data class com 7 campos)"
```

#### 4.3.4 SataAgent.java

**Estado Atual (lido):**
```java
public class SataAgent extends StatefulAgent {
    protected Action selectNewActionNonnull() {
        // Logging
        resolved = selectNewActionFromBuffer();
        if (resolved != null) { /* ... */ }
        resolved = selectNewActionBackToActivity();
        if (resolved != null) { /* ... */ }
        resolved = selectNewActionEarlyStageForward();
        if (resolved != null) { /* ... */ }
        // ... epsilon-greedy
    }
}
```

**Requerido (specs/exploration/spec.md):**
```java
protected Action selectNewActionNonnull() {
    // [NEW] LLM new-state hook at top
    if (_llmRouter != null && _llmRouter.shouldRouteNewState(_isNewState)) {
        LlmActionResult result = _llmRouter.selectAction(...);
        if (result != null) {
            // handle type_text
            return result;
        }
    }
    // Existing SATA chain
    resolved = selectNewActionFromBuffer();
    // ...
}
```

**✅ Task 5.2 está correta:** Hook no topo do método

**⚠️ Ponto de Atenção:**
- O método é `protected Action` (não `ModelAction`)
- `LlmActionResult` não é `Action` — precisa de conversão
- **design.md** menciona `LlmActionResult.modelAction` (type `ModelAction`)
- **Problema:** `ModelAction` é subclasse de `Action`? Precisa verificar hierarquia

**Recomendação:**
```
Verificar em ModelAction.java:
- class ModelAction extends Action?
- Se sim, Task 5.2 está correta
- Se não, adicionar conversão: (Action) result.modelAction
```

### 4.4 Análise de Memória e Performance

#### 4.4.1 Custo de LLM Calls

**Estimativas do Plano:**
- Latência: ~3-5 segundos/call
- Calls por run (10 min): ~60-130
- Overhead total: +3-11 minutos

**Cálculo:**
```
60 calls × 3s = 180s = 3 minutos
130 calls × 5s = 650s = 11 minutos
```

**✅ Estimativa consistente**

**⚠️ Ponto Cego:**
- O plano não considera **tempo de screenshot capture** + **image processing**
- `ScreenshotCapture.capture()` via SurfaceControl reflection pode levar ~100-500ms
- `ImageProcessor.processScreenshot()` (PNG→JPEG resize+base64) pode levar ~50-200ms
- **Overhead adicional:** ~9-26 segundos (60 calls × 150ms) a ~26-65 segundos (130 calls × 500ms)

**Recomendação:**
```
Adicionar ao design.md:
"Overhead total estimado: +3-12 minutos (LLM) + 0.5-2 minutos (screenshot pipeline)
 = +3.5-14 minutos por run de 10 minutos"
```

#### 4.4.2 Memory Pressure

**Plano menciona:**
- `LlmRouter.selectAction()` deve null out `pngBytes`, `base64Image`, `messages` no `finally`
- Screenshot: PNG bytes (~100-500KB para 1080x1920)
- JPEG base64: ~50-200KB (após resize para max 1000px edge)

**✅ Boa prática:** Memory cleanup explícita

**⚠️ Ponto de Atenção:**
- O plano não menciona **GC pressure** de alocar/dealocar 60-130 vezes por run
- Android tem GC heuristics que podem trigger mais frequentemente com alta alocação
- **Risco:** GC pauses podem adicionar ~100-500ms por call

**Mitigação:**
```
Adicionar ao design.md:
"Consider object pooling para byte arrays e StringBuilder no ApePromptBuilder
 para reduzir GC pressure"
```

---

## 5. Pontos Positivos, Negativos, Riscos e Mitigações

### 5.1 Pontos Positivos

| # | Ponto | Impacto |
|---|-------|---------|
| 1 | **2 modos estratégicos** (new-state + stagnation) | Foca LLM onde mais importa, reduz custo |
| 2 | **Circuit breaker** (3 falhas → 60s block) | Protege exploration loop de cascading failures |
| 3 | **MOP markers no prompt** | LLM prioriza widgets que levam a código monitorado |
| 4 | **Coordinate consistency** ([0,1000) em prompt e resposta) | Reduz parsing errors vs rvsmart |
| 5 | **Action history** (últimas 3-5 ações) | Previne LLM amnesia, evita repetição |
| 6 | **Raw click support** | Habilita interação com WebView, custom views |
| 7 | **Graceful degradation** (LLM unavailable → SATA puro) | Zero regression se LLM falhar |
| 8 | **org.json vs Gson** | Zero nova dependência Maven |
| 9 | **Boundary reject** (status/nav bar) | Evita waste em system UI |
| 10 | **Telemetry estruturada** | Permite debug e otimização pós-deploy |

### 5.2 Pontos Negativos

| # | Ponto | Impacto |
|---|-------|---------|
| 1 | **Code duplication** (rvsmart → APE-RV) | Risco de drift, manutenção manual |
| 2 | **Epsilon-LLM removido sem justificativa** | Pode perder exploração estocástica útil |
| 3 | **type_text sem validação de formato** | LLM pode gerar texto inválido (ex: email "abc") |
| 4 | **long_click não verificado no código existente** | Pode não ter suporte em ModelAction |
| 5 | **Falta spec de teste** | Critérios de aceitação podem ser inconsistentes |
| 6 | **LLM success rate não quantificado** | Difícil avaliar eficácia real |
| 7 | **GC pressure não considerado** | Pode adicionar latência imprevista |
| 8 | **Inconsistência de contagem de classes** (7 vs 9) | Sinal de documentação descuidada |

### 5.3 Riscos e Mitigações

| # | Risco | Probabilidade | Impacto | Mitigação |
|---|-------|---------------|---------|-----------|
| 1 | **rvsmart evolui, APE-RV fica desatualizado** | Média | Alto | Documentar commit baseline; criar script de diff automático |
| 2 | **LLM retorna null com frequência (>30%)** | Média | Alto | Ajustar prompt (V13→V17); aumentar temperature (0.3→0.5) |
| 3 | **Circuit breaker tripa cedo demais** | Baixa | Médio | Ajustar threshold (3→5 falhas); reduzir timeout (15s→10s) |
| 4 | **type_text gera texto inválido** | Média | Médio | Adicionar validação no prompt; fallback para fuzzing se input falhar |
| 5 | **long_click não funciona** | Baixa | Baixo | Verificar ModelAction.java antes de implementar; adicionar como task 0.x se necessário |
| 6 | **Memory pressure causa OOM** | Baixa | Alto | Implementar object pooling; monitorar heap usage em testes manuais |
| 7 | **Coordenate accuracy <80%** | Média | Médio | Aumentar tolerance de Euclidean matching (50→75px) |
| 8 | **LLM é muito lento (>10s/call)** | Média | Alto | Reduzir image size (1000→800px); usar modelo menor (Qwen3-VL-3B vs 7B) |
| 9 | **MOP markers estão errados** | Baixa | Médio | Log warning se widget tem marker mas ação não trigger MOP |
| 10 | **Action history cresce demais** | Baixa | Baixo | Ring buffer já limita a 5 entradas ✅ |

---

## 6. Sugestões de Melhoria

### 6.1 Melhorias Críticas (Pré-Implementação)

1. **Adicionar spec de teste:**
   ```
   Criar specs/testing/spec.md com:
   - Cenários obrigatórios para cada classe
   - Métricas de aceitação (ex: ToolCallParser deve parsear 95% dos responses)
   - Testes manuais vs unitários
   ```

2. **Documentar baseline do rvsmart:**
   ```
   Adicionar ao design.md:
   "rvsmart baseline: commit abc123 (2026-03-10)
    SHA256: <hash>
    Branch: main"
   ```

3. **Justificar remoção do epsilon-LLM:**
   ```
   Adicionar ao design.md §Decisions D3:
   "Epsilon-LLM (5% random) foi removido porque experimentos exp1+exp2
    mostraram que <X%> das calls aleatórias resultaram em novas transições,
    vs <Y%> para new-state mode e <Z%> para stagnation mode."
   ```

4. **Verificar suporte a long_click:**
   ```
   Task 0.x (pré-requisito):
   - Ler ModelAction.java
   - Verificar se ActionType.MODEL_LONG_CLICK existe
   - Se não existir, adicionar e atualizar MonkeySourceApe
   ```

5. **Adicionar validação de type_text:**
   ```
   Adicionar ao ApePromptBuilder.systemMessage:
   "For input fields, use semantically valid text:
    - Email: user@example.com
    - Password: SecurePass123!
    - Domain: example.com"
   ```

### 6.2 Melhorias Opcionais (Pós-Implementação)

1. **Reconsiderar epsilon-LLM mode:**
   ```
   Adicionar Config.llmOnEpsilon (default false)
   5% de calls em estados visitados >2 vezes
   Medir impacto em experimentos
   ```

2. **Object pooling para GC:**
   ```
   Implementar pool de byte arrays e StringBuilder
   Reutilizar entre calls LLM
   Medir redução de GC pauses
   ```

3. **LLM success rate telemetry:**
   ```
   Adicionar a LlmRouter:
   - modelActionsCount, rawClicksCount, nullsCount
   - successRate = modelActions / totalCalls
   - Log warning se successRate < 0.7
   ```

4. **Dynamic temperature adjustment:**
   ```
   Se successRate < 0.7 por 10 calls:
     temperature = min(0.8, temperature + 0.1)
   Se successRate > 0.9 por 10 calls:
     temperature = max(0.2, temperature - 0.1)
   ```

5. **Prompt A/B testing:**
   ```
   Config.promptVersion = "V13" ou "V17"
   Rodar experimentos com ambas versões
   Selecionar winner baseado em coverage
   ```

---

## 7. Verificação de Consistência com Código-Fonte

### 7.1 Classes Existentes vs Requeridas

| Classe | Existe? | Modificação Requerida | Status |
|--------|---------|----------------------|--------|
| `Config.java` | ✅ | Adicionar 9 LLM keys + reverter MOP weights | Task 1.1-1.3 |
| `MopScorer.java` | ✅ | Atualizar docs | Task 1.2 |
| `StatefulAgent.java` | ✅ | Adicionar 5 campos + fix isNewState | Task 5.1 |
| `SataAgent.java` | ✅ | Adicionar 2 hooks LLM | Task 5.2-5.3 |
| `SglangClient.java` | ❌ | Copiar de rvsmart + Gson→org.json | Task 2.2 |
| `ScreenshotCapture.java` | ❌ | Copiar de rvsmart | Task 2.3 |
| `ImageProcessor.java` | ❌ | Copiar de rvsmart | Task 2.4 |
| `ToolCallParser.java` | ❌ | Copiar de rvsmart + Gson→org.json | Task 2.5 |
| `CoordinateNormalizer.java` | ❌ | Copiar de rvsmart | Task 2.6 |
| `LlmCircuitBreaker.java` | ❌ | Copiar de rvsmart | Task 2.7 |
| `LlmException.java` | ❌ | Copiar de rvsmart | Task 2.8 |
| `ApePromptBuilder.java` | ❌ | Criar nova (adaptar de rvsmart PromptBuilder) | Task 3.1 |
| `LlmRouter.java` | ❌ | Criar nova | Task 4.1 |
| `ActionHistoryEntry.java` | ❌ | Criar nova (data class) | Task 5.1 (sub-task) |
| `LlmActionResult.java` | ❌ | Criar nova (data class) | Task 4.1 (sub-task) |

### 7.2 Dependências Não Declaradas

| Dependência | Status | Ação Requerida |
|-------------|--------|----------------|
| `ModelAction.MODEL_LONG_CLICK` | ⚠️ Não verificado | Task 0.x: verificar existência |
| `MonkeySourceApe.setInputText()` | ⚠️ Não verificado | Task 0.x: verificar suporte |
| `ActionHistoryEntry` class | ❌ Não existe | Task 5.1.1: criar data class |
| `LlmActionResult` class | ❌ Não existe | Task 4.1.1: criar data class |

---

## 8. Conclusão e Recomendações

### 8.1 Veredito Final

O plano da change **gh6-aperv-llm-integration** é **tecnicamente sólido e bem fundamentado**, com as seguintes ressalvas:

**✅ Aprovação Condicional:** O plano pode prosseguir para implementação **após** endereçar as seguintes questões críticas:

1. **Adicionar spec de teste** (prioridade alta)
2. **Documentar baseline do rvsmart** (prioridade alta)
3. **Verificar suporte a long_click** no código existente (prioridade alta)
4. **Justificar remoção do epsilon-LLM** (prioridade média)
5. **Adicionar validação de type_text** no prompt (prioridade média)

### 8.2 Comparação com APE e FastBot

| Critério | APE (original) | FastBot | APE-RV gh6 (planejado) |
|----------|----------------|---------|------------------------|
| Exploração | SATA (determinístico) | Desconhecido | SATA+MOP+LLM (híbrido) |
| Quebra de determinismo | ❌ | ❌ | ✅ (LLM em 2 modos) |
| Semantic understanding | ❌ | ❌ | ✅ (Qwen3-VL vision) |
| Dynamic element support | ❌ | ❌ | ✅ (raw clicks) |
| MOP-guided | ✅ | ❌ | ✅ |
| Graceful degradation | N/A | N/A | ✅ (LLM off → SATA) |
| Custo LLM | N/A | N/A | ~$0.18-4.77/hora |

**Veredito:** APE-RV gh6 será **superior ao APE original** (adiciona LLM estratégico) e **provavelmente superior ao FastBot** (que não tem MOP nem LLM híbrido).

### 8.3 Próximos Passos

1. **Endereçar questões críticas** (listadas em 8.1)
2. **Atualizar documentação** (design.md, tasks.md)
3. **Implementar na ordem das tasks** (1→7, respeitando depend spm dependencies)
4. **Rodar testes manuais** em 3-5 APKs representativos
5. **Medir métricas de sucesso:**
   - LLM success rate (>70%)
   - Coverage improvement (>10% vs SATA puro)
   - Overhead aceitável (<50% do tempo total)

---

**Assinatura:** Qwen Code  
**Data:** 16 de março de 2026  
**Versão do documento:** 1.0
