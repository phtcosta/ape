# Arquitetura de separação APE original × extensões RV (APE-RV)

**Data**: 2026-07-08 · **Status**: design aprovado em sessão (blocos 1–3) · **Base**: branch `mop-fairtest`
**Origem**: decisão de re-arquitetura pós-investigação `rvsec/rv-android/docs/20260708_investigacao_formas_guiar_mop.md`
**Insumos**: diff completo contra o APE upstream (`github.com/tianxiaogu/ape` @ `8f51b99`, salvo em `/tmp/ape-original-diff/`), inventário de comportamentos RV, requisitos R1–R9 derivados do memo e do pipeline rv-android.

## 0. Problema e decisões de enquadramento

O fork é 100% aditivo (131 arquivos idênticos ao upstream, 10 modificados, 17 adicionados, 0 removidos; `naming/`, `model/`, `events/` byte-a-byte intactos), mas a separação é **incompleta e mal-posicionada**:

- O grosso do RV vive na **base** `StatefulAgent.adjustActionsByGUITree()` (passes inline: MOP widget → menu gateway → WTG+frontier → coverage → sibling → form, linhas 1476–1660) e em `ApeAgent` (geração de input) — não no `SataAgent`. "Restaurar o SataAgent original" é a alavanca errada.
- Um braço "APE puro" hoje exigiria zerar ~12 flags manualmente, e **dois comportamentos não têm flag**: telemetria `[APE-STEP]` (StatefulAgent.java:1360-1378) e **FormCompletion** (StatefulAgent.java:1640-1660 — dispara sempre que há EditText vazio; pior ofensor).
- `frontierBoostWeight` (default 200) e `activityTriggerEnabled` (default true) não estão no `APERV_PROPERTY_MAPPING` do tool.py → impossível desligá-los por braço (bloqueante de experimento).

**Decisões tomadas** (com o usuário, 2026-07-08):

| Decisão | Escolha |
|---|---|
| Branch base | `mop-fairtest` (estritamente à frente de master; 6 changes cmpft3-validadas + activity-frontier) |
| Escopo estratégias do memo | A′ + B + E-mín implementadas; F/F′ só seams (flags + bit), LLM na rodada 2 |
| Braço ape-puro | **Um jar único** (`ape-rv.jar`), variante congelada `ape_pure` com tudo desligado por properties — sem segundo binário (elimina confound de build/proveniência) |
| Processo | Este design doc → 3 OpenSpec changes derivadas |
| Abordagem | **Pipeline de ScoringPass plugável** (abordagem 2) + flags para os sem-flag + mapping completo + variantes congeladas |

Abordagens rejeitadas: (1) *flags-only* — mantém a base como empilhado de `if`s e a paridade fica dependente de disciplina, não de estrutura (foi assim que FormCompletion nasceu sem flag); (3) *agent RV por herança* — o diff mostra que o SataAgent quase não mudou (+117 linhas guarded); a subclasse teria que sobrescrever métodos grandes da base e duplicar RandomAgent, com menos composibilidade que passes.

## 1. Visão e princípios

- **Um binário, comportamento por composição**: cada braço de experimento é um conjunto de flags empurrado por `ape.properties`; `ape_pure` = pipeline de scoring vazio + todas as flags RV off.
- **Três camadas**:
  1. **Núcleo APE intocado** — `naming/`, `model/`, `events/`, laço original de `adjustActionsByGUITree` (permanece byte-idêntico ao upstream);
  2. **Extensões RV plugáveis** — passes de scoring, guards, input, LLM, triggering;
  3. **Montagem em ponto único** a partir do `Config`, logada no startup: uma linha `[APE-ARCH] passes=[...] flags={...}` no .trace para auditoria de braço.
- **INV-ARCH-01 (invariante de paridade)**: com todas as flags RV desligadas, a seleção de ações é equivalente ao APE original. Exceções documentadas, sempre-ligadas: fix de crash do `ApePinchOrZoomEvent` (crash não é "comportamento") e seed handling (infra de reprodutibilidade, arm-neutral).
- **Percepção da árvore é caso especial**: webview-prune fix, actionability AndroidX e ViewPager-scrollable mudam o que o agente *vê*, não como pontua. Flag única `treeEnhancementsEnabled` (default true); `ape_pure` a desliga (documentando que desligar = herdar o bug de poda do APE original).

## 2. Estrutura de código

### 2.1 Pipeline de scoring

Novo pacote `com.android.commands.monkey.ape.agent.scoring`:

```java
public interface ScoringPass {
    String name();                 // p/ log de montagem e testes
    boolean isEnabled();           // decidido no ctor a partir do Config
    void apply(State state, ModelAction[] actions, ScoringContext ctx);
}
```

`ScoringContext` empacota o que os passes hoje pegam da base: `MopData`, `UICoverageTracker`, acesso ao grafo/`ActivityNode`, contadores de pick. Os blocos inline de `StatefulAgent.adjustActionsByGUITree()` viram 7 passes, mais 1 novo (`MopFrontierPass`, estratégia B):

| Pass | Origem (linhas em mop-fairtest) | Flag |
|---|---|---|
| `MopWidgetPass` | StatefulAgent.java:1476-1502 | `mopWeightDirect`/`mopWeightTransitive` |
| `MenuGatewayPass` | 1503-1517 | `mopWeightOpenMenu` |
| `WtgPass` | 1520-1529 | `mopWeightWtg` |
| `FrontierPass` (genérico, existente) | 1530-1563 | `frontierBoostWeight` |
| `MopFrontierPass` (**novo — estratégia B**) | — | `mopFrontierWeight` (aditivo, novo) |
| `CoveragePass` | 1580-1602 | `coverageBoostWeight` |
| `SiblingPenaltyPass` | 1610-1635 | `siblingStatePenalty` |
| `FormCompletionPass` | 1640-1660 | `formCompletionEnabled` (**nova**) |

`adjustActionsByGUITree()` volta ao laço original (1418–1476) + um único `for (ScoringPass p : pipeline) p.apply(...)`. Montagem em `ScoringPipeline.fromConfig(Config)` — ponto único, testável.

Semântica do `MopFrontierPass` (estratégia B, memo §2): somar `mopFrontierWeight` quando `shortId == t.widgetName` AND `activityHasMop(t.targetActivity)` AND `Graph.getActivityNode(target) == null` (alvo-fronteira é MOP E não-visitado). Aditivo e independente do `FrontierPass` genérico; calibrar interação num smoke antes do experimento.

### 2.2 Fora do pipeline

- **Telemetria `[APE-STEP]`** → flag `stepTelemetryEnabled` (default true), gating log + `System.currentTimeMillis()` por ação.
- **Input** (`ApeAgent.generateInputText`, ApeAgent.java:217/232) → flags existentes `heuristicInput`/`fuzzInputTyped`; off ⇒ `StringCache` legado (verificar fallback exato no teste de paridade).
- **Estratégia A′** → `MopData`: flag `mopActivitySourceComponents` (default false) adiciona `components.activities[].reachesTarget` como fonte de `mopActivities` (MopData.java:385-389). Predicado `activityHasMop`: 17,8% → 83,6% dos apps. É o eixo que separa `sata_mop_widget` de `sata_mop_activity`.
- **Estratégia E-mín** → `SataAgent.selectTriggerCandidate` (650-666): ordenação MOP-first dos candidatos (`reachesTarget=true` primeiro), flag `triggerMopFirst` (default false). Extensão a receivers/services exported (E-ext) fica FORA desta rodada.
- **Seams F/F′ (LLM — sem mudança de comportamento nesta rodada)**: `Config.llmPercentageNoSubstrate` (default −1 = herda `llmPercentage`) + `MopData.isWidgetlessSubstrate()` (soma de `windows[].widgets` == 0, os 65 apps/29,7% Compose-puro/jogos). O `LlmRouter` não muda agora; F (prompt por-activity) e F′ (roteamento adaptativo) são rodada 2.
- **Guards e caps já flagados** (`foreignActivityGuard`, `treePackageGuard`, `dynamicEpsilon`, `backMenuPickCap`, `mopTargetPickCap`, budget): sem refactor — entram no mapping e nas variantes.
- **MODEL_MENU e tiebreaker**: o diff byte-a-byte confirma que a `menuAction` incondicional em `State` (State.java:65) e o tiebreaker por prioridade em `greedyPickLeastVisited` são adições do fork sem flag → ganham `modelMenuEnabled` e `leastVisitedPriorityTiebreak`.

## 3. Flags e defaults

**Política**: defaults do `Config` preservam o comportamento atual do aperv (standalone não muda); **braços nunca herdam defaults** — variantes congeladas setam todas as flags arm-defining explicitamente.

Flags novas:

| Flag | Default | Gate |
|---|---|---|
| `formCompletionEnabled` | true | FormCompletionPass + contexto no ApeAgent/SataAgent |
| `stepTelemetryEnabled` | true | log `[APE-STEP]` + timing por ação |
| `modelMenuEnabled` | true | criação do `menuAction` em todo State |
| `leastVisitedPriorityTiebreak` | true | tiebreaker RV em `greedyPickLeastVisited` |
| `treeEnhancementsEnabled` | true | webview-prune fix + actionability AndroidX + ViewPager scrollable |
| `activityBudgetEnabled` | true | instância/consulta do ActivityBudgetTracker |
| `mopActivitySourceComponents` | false | estratégia A′ |
| `mopFrontierWeight` | 0 | estratégia B |
| `triggerMopFirst` | false | estratégia E-mín |
| `llmPercentageNoSubstrate` | −1 | seam F′ |
| `apePureMode` | false | **kill-switch**: no load do Config força TODAS as flags RV para off/0 e loga o que forçou |

`apePureMode` é defesa-em-profundidade: o braço `ape_pure` não depende de enumerar ~18 flags no tool.py; flags RV futuras são obrigadas a se registrar no kill-switch (invariante testável).

## 4. Braços (variantes congeladas no tool.py)

| flag | `ape_pure` | `sata` | `sata_mop_widget` | `sata_mop_activity` | `sata_mop_act_frontier` |
|---|---|---|---|---|---|
| `apePureMode` | **true** | false | false | false | false |
| exploração RV (coverage, dynEps, input, guards, form, tree, budget, caps) | — (forçado off) | **ON** | ON | ON | ON |
| `mop_data` (push do JSON) | — | — | **sim** | sim | sim |
| `mopWeightDirect/Transitive/OpenMenu/Wtg` | — | 0 | **500/300/250/200** | idem | idem |
| `mopActivitySourceComponents` (A′) | — | false | false | **true** | true |
| `frontierBoostWeight` | — | 0 | 0 | 0 | **200** |
| `mopFrontierWeight` (B) | — | 0 | 0 | 0 | **≈200 (calibrar em smoke)** |
| `activityTriggerEnabled` + `triggerMopFirst` (E-mín) | — | false | false | false | **true** |

- `ape_pure` = baseline APE original; `sata` = isola melhorias de exploração RV; `sata_mop_widget` = mecanismo widget atual (controle); `sata_mop_activity` = isola A′; `sata_mop_act_frontier` = pacote de alcance (A′+B+E-mín). Segue o experimento §3 do memo; braços LLM (`…_llm`, F′) = rodada 2, sempre isolados (latência não pode contaminar steering).
- **`APERV_PROPERTY_MAPPING` completo** (tool.py:74-113): entram `frontierBoostWeight`, `activityTriggerEnabled` e todas as flags novas. **Política: flag nova → mapping + variantes no mesmo commit.**
- **Teste de guarda (pytest no aperv-tool)**: (i) toda variante seta explicitamente todas as chaves arm-defining; (ii) toda chave arm-defining tem entrada no mapping.

Contrato de proveniência de métricas (confirmado no código): primárias e guard-rails (`cov_mop`/`mop_unique`, `cov_act`, `cov_method`, crash) vêm do APK instrumentado + monitor RVSec via **logcat** — independentes do jar; métricas de mecanismo (share de steps mop>0, decision_source, telas-MOP visitadas) vêm do **.trace** e só existem nos braços com telemetria ligada. `ape_pure` sem telemetria não quebra as primárias.

## 5. Paridade e testes

1. **Caracterização ANTES do refactor**: fixtures de GUITree + seed fixa capturam prioridades do `adjustActionsByGUITree` atual (golden data); o refactor reproduz byte-igual com flags nos valores atuais.
2. **INV-ARCH-01**: com `apePureMode=true` — (i) prioridades = laço original (referência: `/tmp/ape-original`), (ii) nenhum `menuAction`, (iii) epsilon fixo 0.05, (iv) input `StringCache` legado, (v) zero linhas `[APE-STEP]`.
3. **Kill-switch**: registro de flags em `apePureMode` conferido contra a lista arm-defining (falha se nascer flag RV não registrada).
4. **Montagem**: matriz flags→passes esperados + linha `[APE-ARCH]`.
5. **Suite existente (538 testes) verde**; testes dos passes viram testes por-pass.
6. **Pré-requisitos de experimento embutidos**: verificar/consertar bug "seed ignored" (auditoria 2026-07; pareamento por seed depende disso) e fix G-2 (unidade MB/MiB do reject `too-large`; desbloqueia redreader, 3/657 runs).
7. **Smoke em device** (cryptoapp) nos braços `ape_pure`, `sata`, `sata_mop_act_frontier` antes de experimento grande.

## 6. OpenSpec changes derivadas

| # | Change | Conteúdo | Repo |
|---|---|---|---|
| 1 | `rv-scoring-pipeline` | Extração dos 8 passes + `ScoringPipeline.fromConfig` + flags de paridade (`formCompletionEnabled`, `stepTelemetryEnabled`, `modelMenuEnabled`, `leastVisitedPriorityTiebreak`, `treeEnhancementsEnabled`, `activityBudgetEnabled`) + `apePureMode` + testes de caracterização/paridade. Nenhum default muda. | ape (mop-fairtest) |
| 2 | `mop-reach-strategies` | A′ (`mopActivitySourceComponents`) + B (`MopFrontierPass`/`mopFrontierWeight`) + E-mín (`triggerMopFirst`) + seams F′ (`llmPercentageNoSubstrate`, `MopData.isWidgetlessSubstrate()`) + fix G-2. Depende da 1 (B nasce como pass). | ape (mop-fairtest) |
| 3 | `aperv-arm-variants` | Mapping completo + variantes + pytest de guarda + verificação do seed. Paralelo à 2 após a 1 fixar nomes de properties. | rv-android |

Ordem: 1 → 2, com 3 em paralelo assim que a 1 fixar os nomes das properties. Processo: cada change segue o workflow do repo alvo — no rv-android, issue no GitHub + OpenSpec change vinculada; sempre via comandos `openspec` (create/validate --strict/archive).

### 6.1 Disposição de variantes na change 3 (`get_variants()`, tool.py:201-301)

Hoje existem 13 variantes, e todas setam só 2-3 chaves, **herdando o resto dos defaults do Config** — a causa direta da contaminação por `frontierBoostWeight`/`activityTriggerEnabled`. Disposição:

- **Criar (4)**: `ape_pure`, `sata_mop_widget`, `sata_mop_activity`, `sata_mop_act_frontier` — conjunto completo e explícito de flags arm-defining (matriz do §4).
- **Manter, tornando explícitas**: `default`/`sata` (baseline aperv-sem-MOP com frontier=0, trigger=false etc. escritos), `bfs`, `random`, `sata_llm`, `sata_mop_llm` (base da rodada 2 LLM).
- **Redefinir**: `sata_mop` → alias documentado de `sata_mop_widget` (não quebra YAMLs antigos).
- **Congelar como estão**: as 6 `sata_mop_llm_*` do gh43 (reprodutibilidade histórica), isentas da política de explicitação.
- A DSL `tool[:variant][@param=value,...]` (configuration_factory.py:270-311) permanece; o `@override` cobre smokes de calibração (ex.: `aperv:sata_mop_act_frontier@mop_frontier_weight=400`) sem variante nova.

## 7. Questões abertas (registradas, não bloqueiam)

- Validação one-shot de equivalência contra o jar upstream em device (mesmo app, mesma seed, comparar sequência de ações) — opcional, defensibilidade extra para a tese.
- Calibração `mopFrontierWeight` × `frontierBoostWeight` (interação dos dois passes de fronteira) — smoke antes do experimento.
- E-ext (receivers/services exported via `am`) e F/F′ (LLM rodada 2) — fora desta rodada; seams prontos.
- Braço adicional isolando B de E-mín, se o resultado de `sata_mop_act_frontier` exigir decomposição.
