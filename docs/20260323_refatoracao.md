# APE-RV Phase 7: Refatoracao da Engine de Exploracao

**Data**: 2026-03-23
**Status**: Pre-plano (entrada para workflow SDD)
**Track SDD**: Fast-Forward (single-module, design decisions necessarias)
**Contexto**: Plateau de ~28% method coverage confirmado por calibracao (trial #139, 169 APKs). O gargalo eh estrutural, nao parametrico.

---

## 1. Motivacao

### 1.1 O Plateau

| Variante | Method | MOP | Delta vs v1 |
|----------|--------|-----|-------------|
| aperv:sata_mop_v1 (defaults) | 28.35% | 37.02% | — |
| ape (original) | 27.82% | 37.08% | -0.53pp |
| aperv:sata_mop_llm | 27.60% | 36.47% | -0.75pp |
| aperv:sata_mop_cal (#139) | 27.55% | 35.96% | -0.80pp |

Spread de 1.17pp entre 7 variantes com MOP, LLM, calibracao Optuna de 14 parametros, e diferentes pesos. A calibracao nos 30 APKs nao generalizou para 169.

### 1.2 Causa Raiz

APE-RV eh **action-centric** (rastreia visitas por ModelAction) mas nao **widget-centric** (nao sabe "70% dos widgets desta tela foram testados"). O rvsmart tem UICoverageTracker e ActivityBudgetTracker que resolvem exatamente isso.

Alem disso, o JSON de analise estatica contem `transitions[]` (Window Transition Graph) que o APE-RV ignora. O rv-agent usa BFS no WTG para navegar ate activities com metodos MOP-reachable.

### 1.3 Objetivo

Portar conceitos comprovados do rvsmart (UICoverageTracker, ActivityBudgetTracker, InputValueGenerator) e adicionar WTG navigation + dynamic epsilon para quebrar o plateau. Meta: +2-4pp em method/MOP coverage.

---

## 2. Diagnostico: O Que Falta

| Conceito | rvsmart | APE-RV |
|----------|---------|--------|
| Coverage gap por tela (% widgets nao testados) | UICoverageTracker | NAO TEM |
| Budget por Activity (cap de tempo proporcional) | ActivityBudgetTracker | activityStableRestartThreshold=MAX_VALUE (desabilitado) |
| Input heuristico por tipo (email, URL, phone) | InputValueGenerator | StringCache.nextString() (aleatorio) |
| WTG navigation (path ate MOP activities) | rv-agent TransitionManager | MopData ignora transitions[] |
| Dynamic epsilon (decay ao longo do run) | N/A | epsilon fixo = 0.05 |
| Scoring por coverage density | CoverageDensityScorer | NAO TEM |

---

## 3. Cinco Melhorias

### 3.1 UICoverageTracker

**O que**: Rastrear cobertura de widgets por estado. Cada estado registra seus widgets; cada interacao eh contada. `getCoverageGap(state)` retorna fracao de widgets nao testados.

**Adaptacao para APE-RV**: O APE-RV usa `State` (nao Screen) e `GUITreeNode` (nao ScreenItem). O ID do widget segue a mesma logica hibrida: `res:{resourceId}` > `coords:{cx},{cy}` > `unknown:{hash}`.

**Integracao**:
- **Registro**: Em `StatefulAgent.updateStateInternal()`, apos construir o GUITree, registrar todos os GUITreeNodes interagiveis do estado
- **Interacao**: Em `StatefulAgent.moveForward()`, apos executar uma acao, registrar interacao com o widget-alvo
- **Uso no scoring**: Em `adjustActionsByGUITree()`, adicionar boost proporcional ao coverage gap do estado atual (estados com muitos widgets nao testados recebem boost)

**Novo arquivo**: `src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java` (~120 linhas)

**Arquivos modificados**:
- `StatefulAgent.java` — campo `_coverageTracker`, registro em updateStateInternal, recording em moveForward
- `Config.java` — `ape.coverageBoostWeight` (int, default: 100)

**Config flags**:
- `ape.coverageBoostWeight` (int, default: 100) — peso do boost por coverage gap (0 = desabilitado)

**Invariantes**:
- INV-COV-01: `getCoverageGap(state)` retorna valor em [0.0, 1.0]
- INV-COV-02: Coverage gap decresce monotonicamente com interacoes registradas
- INV-COV-03: Estado desconhecido retorna gap = 1.0

### 3.2 ActivityBudgetTracker

**O que**: Alocar budget de iteracoes por Activity proporcional ao numero de widgets. Quando esgotado, forcar navegacao para outra Activity.

**Adaptacao para APE-RV**: O APE-RV tem ActivityNode com `getVisitedCount()` e `states`. O budget eh calculado como `baseBudget + (widgetCount * budgetPerWidget)` onde widgetCount = soma de widgets em todos os states da activity.

**Integracao**:
- **Registro**: Em `StatefulAgent.updateStateInternal()`, registrar activity com widgetCount quando encontrada pela primeira vez
- **Contagem**: Em `StatefulAgent.moveForward()`, incrementar contador da activity atual
- **Check**: Em `SataAgent.selectNewActionNonnull()`, ANTES da cadeia SATA: se budget da activity atual esgotado, forcar navegacao para activity com mais budget restante (via trivial activity logic existente)

**Novo arquivo**: `src/main/java/com/android/commands/monkey/ape/utils/ActivityBudgetTracker.java` (~45 linhas)

**Arquivos modificados**:
- `StatefulAgent.java` — campo `_budgetTracker`, registro e contagem
- `SataAgent.java` — check no inicio de selectNewActionNonnull()
- `Config.java` — 2 flags

**Config flags**:
- `ape.activityBaseBudget` (int, default: 50) — budget base por activity
- `ape.activityBudgetPerWidget` (int, default: 5) — budget adicional por widget

**Invariantes**:
- INV-BUD-01: `isBudgetExhausted()` retorna false para activities nao registradas
- INV-BUD-02: Budget calculado uma vez por activity, nao recalculado

### 3.3 WTG Navigation (transitions[] do JSON)

**O que**: Parsear `transitions[]` do JSON de analise estatica. Construir grafo de navegacao: window → [(widgetName, widgetClass, targetWindow)]. Usar para boost em widgets que levam a activities MOP-reachable.

**Dados disponiveis no JSON**:
```json
"transitions": [{
  "sourceId": 1231,
  "targetId": 1170,
  "events": [{
    "type": "click",
    "handler": "...",
    "widgetId": 42,
    "widgetClass": "android.view.MenuItem",
    "widgetName": "settings"
  }]
}]
```

**Adaptacao para APE-RV**: Adicionar terceiro pass ao MopData para parsear transitions. Construir mapa `activityName → List<WtgTransition>` onde WtgTransition = (widgetName, targetActivity, targetHasMop). No scoring, boostar widgets cujo nome (resourceId) aparece como trigger de transicao para activity MOP-reachable.

**Integracao**:
- **Parsing**: MopData.load() ganha Pass 3 para transitions[]. Cross-reference sourceId/targetId com windows[].id para obter nomes de activity
- **Scoring**: Em `StatefulAgent.adjustActionsByGUITree()`, apos o MOP pass existente, adicionar WTG pass: para cada acao, verificar se o widget trigger leva (via WTG) a uma activity com MOP methods → boost adicional
- **Navegacao**: Em `SataAgent.selectNewActionForTrivialActivity()`, usar WTG para preferir activities MOP-reachable como destino (complementa o tiebreaker MOP density existente)

**Arquivo modificado**: `src/main/java/com/android/commands/monkey/ape/utils/MopData.java` — novo pass + novas estruturas (WtgTransition, maps)

**Arquivos modificados adicionais**:
- `MopScorer.java` — novo metodo `scoreWtg()` para boost de transicao
- `StatefulAgent.java` — chamada ao WTG pass em adjustActionsByGUITree()
- `Config.java` — 1 flag

**Config flags**:
- `ape.mopWeightWtg` (int, default: 200) — boost para widgets que levam a activities MOP (0 = desabilitado)

**Invariantes**:
- INV-WTG-01: Apenas eventos tipo "click" sao considerados (nao implicit_home, implicit_rotate)
- INV-WTG-02: `scoreWtg()` retorna 0 quando MopData eh null ou transitions[] ausente
- INV-WTG-03: Window names mapeiam para activity names via windows[].name

### 3.4 Dynamic Epsilon Decay

**O que**: Epsilon comeca alto (exploracao diversa) e decai linearmente ate valor minimo (exploitation profunda) ao longo da duracao do run.

**Formula**: `epsilon = maxEpsilon - (maxEpsilon - minEpsilon) * progress` onde `progress = elapsed / totalDuration`.

**Adaptacao para APE-RV**: `ApeAgent.beginMillis` ja existe (private). Monkey.java parseia `--running-minutes`. A forma mais simples: adicionar Config flag `ape.runDurationMs` (setado pelo aperv-tool a partir do timeout do experimento). Se nao setado, usar 600000ms (10 min default).

**Integracao**:
- `SataAgent.egreedy()` (linha 672): substituir `epsilon` fixo por `computeDynamicEpsilon()`
- `computeDynamicEpsilon()`: calcula progress a partir de `beginMillis` e `Config.runDurationMs`

**Arquivos modificados**:
- `ApeAgent.java` — mudar `beginMillis` de private para protected (1 linha)
- `SataAgent.java` — novo metodo `computeDynamicEpsilon()` (~10 linhas), modificar egreedy() (~3 linhas)
- `Config.java` — 3 flags

**Config flags**:
- `ape.dynamicEpsilon` (boolean, default: true) — habilita decay (false = comportamento original)
- `ape.maxEpsilon` (double, default: 0.15)
- `ape.minEpsilon` (double, default: 0.02)
- `ape.runDurationMs` (long, default: 600000) — duracao total do run em ms

**Invariantes**:
- INV-EPS-01: Epsilon sempre em [minEpsilon, maxEpsilon]
- INV-EPS-02: Quando `dynamicEpsilon=false`, comportamento identico ao epsilon fixo atual
- INV-EPS-03: Quando progress >= 1.0 (timeout atingido), epsilon = minEpsilon

### 3.5 InputValueGenerator (Heuristic Text Input)

**O que**: Substituir geracao de texto aleatorio (StringCache.nextString()) por texto contextual baseado no tipo do campo (email, password, URL, phone, etc.).

**Deteccao de categoria** (em ordem de prioridade):
1. `GUITreeNode.isPassword()` → PASSWORD
2. `GUITreeNode.getResourceID()` contem "email" → EMAIL, "password" → PASSWORD, "phone"/"tel" → PHONE, "url"/"website" → URL, "search" → SEARCH
3. `GUITreeNode.getContentDesc()` com mesmas keywords
4. Fallback: GENERIC (delega para StringCache.nextString())

**Valores pre-definidos por categoria** (rotacao ciclica):
- EMAIL: `test@example.com`, `user@test.org`, `a@b.c`
- PASSWORD: `Test1234!`, `Password123`, `Aa1!aaaa`
- NUMBER: `42`, `0`, `999`
- PHONE: `+5561999990000`, `123456789`
- URL: `https://example.com`, `http://test.org`
- SEARCH: `test`, `crypto`, `settings`
- GENERIC: StringCache.nextString()

**Integracao**: Modificar `ApeAgent.checkInput()` (linha 180):
```java
// Antes:
String text = StringCache.nextString();

// Depois:
String text = Config.heuristicInput
    ? InputValueGenerator.generateForNode(node)
    : StringCache.nextString();
```

**Novo arquivo**: `src/main/java/com/android/commands/monkey/ape/utils/InputValueGenerator.java` (~90 linhas)

**Arquivos modificados**:
- `ApeAgent.java` — checkInput() usa InputValueGenerator
- `Config.java` — 1 flag

**Config flags**:
- `ape.heuristicInput` (boolean, default: true) — habilita input heuristico (false = comportamento original)

**Invariantes**:
- INV-INP-01: `generateForNode()` nunca retorna null
- INV-INP-02: Quando `heuristicInput=false`, comportamento identico ao atual

---

## 4. Resumo de Impacto

### Novos arquivos (3)

| Arquivo | Linhas | Descricao |
|---------|--------|-----------|
| `ape/utils/UICoverageTracker.java` | ~120 | Cobertura de widgets por estado |
| `ape/utils/ActivityBudgetTracker.java` | ~45 | Budget de iteracoes por activity |
| `ape/utils/InputValueGenerator.java` | ~90 | Geracao de texto heuristico |

### Arquivos modificados (5)

| Arquivo | Mudancas |
|---------|----------|
| `ape/utils/Config.java` | +10 flags de configuracao |
| `ape/utils/MopData.java` | Pass 3: parse transitions[], WTG structures |
| `ape/utils/MopScorer.java` | Novo metodo scoreWtg() |
| `ape/agent/StatefulAgent.java` | Campos _coverageTracker, _budgetTracker; registro/recording; WTG pass em adjustActionsByGUITree() |
| `ape/agent/SataAgent.java` | Budget check em selectNewActionNonnull(); computeDynamicEpsilon() em egreedy() |
| `ape/agent/ApeAgent.java` | beginMillis protected; checkInput() usa InputValueGenerator |

### Config flags (10 novos)

| Flag | Tipo | Default | Melhoria |
|------|------|---------|----------|
| `ape.coverageBoostWeight` | int | 100 | 3.1 UICoverageTracker |
| `ape.activityBaseBudget` | int | 50 | 3.2 ActivityBudgetTracker |
| `ape.activityBudgetPerWidget` | int | 5 | 3.2 ActivityBudgetTracker |
| `ape.mopWeightWtg` | int | 200 | 3.3 WTG Navigation |
| `ape.dynamicEpsilon` | boolean | true | 3.4 Dynamic Epsilon |
| `ape.maxEpsilon` | double | 0.15 | 3.4 Dynamic Epsilon |
| `ape.minEpsilon` | double | 0.02 | 3.4 Dynamic Epsilon |
| `ape.runDurationMs` | long | 600000 | 3.4 Dynamic Epsilon |
| `ape.heuristicInput` | boolean | true | 3.5 InputValueGenerator |

### Invariantes (12)

INV-COV-01..03, INV-BUD-01..02, INV-WTG-01..03, INV-EPS-01..03, INV-INP-01..02

---

## 5. Sequencia de Implementacao

| Ordem | Melhoria | Dependencias | Risco | Impacto Esperado |
|-------|----------|-------------|-------|------------------|
| 1 | Dynamic Epsilon Decay (3.4) | Nenhuma | Muito baixo | +0.5pp |
| 2 | InputValueGenerator (3.5) | Nenhuma | Baixo | +0.3pp (geral), +1-2pp (text apps) |
| 3 | UICoverageTracker (3.1) | Nenhuma | Baixo | +0.5-1pp |
| 4 | ActivityBudgetTracker (3.2) | Nenhuma | Medio | +0.5-1pp |
| 5 | WTG Navigation (3.3) | MopData existente | Medio | +1-2pp MOP |

Todas sao independentes e podem ser implementadas em paralelo. A ordem reflete complexidade crescente.

---

## 6. Referencia: Codigo do rvsmart

| Componente rvsmart | Path | Linhas | Adaptacao para APE-RV |
|-------------------|------|--------|----------------------|
| UICoverageTracker | rvsec/rvsec-android/rvsmart/.../core/UICoverageTracker.java | 138 | GUITreeNode em vez de ScreenItem; State hash em vez de Screen hash |
| ActivityBudgetTracker | rvsec/rvsec-android/rvsmart/.../strategy/ActivityBudgetTracker.java | 42 | Direto — mesma logica |
| CoverageDensityScorer | rvsec/rvsec-android/rvsmart/.../strategy/scorers/CoverageDensityScorer.java | ~30 | Integrado em adjustActionsByGUITree() como boost |
| InputValueGenerator | rvsec/rvsec-android/rvsmart/.../strategy/InputValueGenerator.java | ~100 | Adaptar de ScreenItem para GUITreeNode |

---

## 7. Verificacao

### Testes unitarios
- `UICoverageTrackerTest.java` — registro, interacao, gap computation
- `ActivityBudgetTrackerTest.java` — budget allocation, exhaustion
- `InputValueGeneratorTest.java` — category detection, value generation
- `MopDataTest.java` — extensao: parse transitions[]

### Teste de integracao (device)
```bash
mvn clean package
adb push target/ape-rv.jar /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/ape-rv.jar app_process /system/bin \
  com.android.commands.monkey.Monkey -p com.example.cryptoapp \
  --running-minutes 1 --ape sata
```

### Teste de validacao (experimento)
- Comparar `aperv:sata_mop_v1` (baseline) vs `aperv:sata_mop_v3` (Phase 7) em 30 APKs de pre-calibracao
- Metricas: method coverage, MOP coverage, violations, APKs com violacao
- Criterio de sucesso: Wilcoxon p < 0.05 para method ou MOP coverage

---

## 8. Proximos Passos (Workflow SDD)

1. Criar issue GH para Phase 7
2. `opsx:new` — criar change no OpenSpec
3. `opsx:ff` — gerar todos os artefatos (spec delta, design, tasks)
4. Implementar (5 tasks independentes, paralelizaveis via subagentes)
5. `opsx:verify` — verificar implementacao vs artefatos
6. `opsx:archive` — arquivar change
