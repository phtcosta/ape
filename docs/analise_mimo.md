# Investigação Completa do APE-RV — Código, Dados Estáticos, Observabilidade e Change

**Modelo:** MIMO v2 Free · **Data:** 2026-07-02 · **Base:** master `f70f986` + worktree `ape-mop-fairtest` (não commitado)

## 0. Metodologia

Investigação em 4 frentes com 10 subagentes paralelos: (1) código-fonte por pacote (`agent/`, `naming/`, `model/`, `tree/`+`events/`+`utils/`, raiz Monkey+`ape/`); (2) fidelidade schema JSON↔parser; (3) suficiência do log `.trace`; (4) avaliação da change em andamento. Cada agente leu arquivos **inteiros** e verificou contra achados documentados em `docs/20260622_investigacao_mop.md`, `docs/analise_claude_sonnet5.md`, `docs/analise_gemini.md`, `docs/analise_deepseek.md` e `docs/analise_claude_fable5.md`.

Legenda de métrica: **UI** = cobertura de UI; **MOP-C** = cobertura de métodos/classes/operações MOP; **MOP-V** = violações MOP distintas; **G** = geral/qualidade. Confiança: **conf** = rastreado end-to-end; **susp** = plausível, não confirmado em runtime.

---

## 1. Frente 1 — Bugs/Anomalias NOVOS no código (master)

Achados **genuinamente novos**, não documentados em nenhuma análise anterior.

### 1.1 `ape/agent/` — Núcleo de decisão

| ID | Onde | Achado | Sev | Conf | Métrica |
|---|---|---|---|---|---|
| AGT-NV20 | `ApeAgent.java:203-215` | `TypedInputGenerator.generateForType` pode retornar string vazia para tipos não suportados — EditText recebe input vazio em vez de texto random; sem fallback | Baixa | Susp | UI |
| AGT-NV21 | `StatefulAgent.java:366-377` | `fillBuffer`: `peekFirst()` sem null-check — se `seq.fillBuffer(actionBuffer)` não adiciona nada, `peekFirst()` retorna null → NPE no `getThrottle()` | Média | Susp | G |
| AGT-NV22 | `StatefulAgent.java:450` | `selectNewActionFromBuffer`: `peekFirst()` redundante — `action` já obtido de `peekFirst().action`, risco de TOCTOU se buffer modificado entre chamadas | Baixa | Conf | G |
| AGT-NV23 | `StatefulAgent.java:778-789` | `compareArrays`: `a1.length - a2.length` com Integer.MAX_VALUE causa overflow negativo — usado no comparator de `checkAndRefineOverAbstractedState` | Baixa | Susp | G |
| AGT-NV24 | `SataAgent.java:706-713` | `egreedy()`: `if (v < eps) return false; return true` — retorna true para greedy, false para random. Nome confuso mas correto; observação de API | Baixa | Conf | G |
| AGT-NV25 | `SataAgent.java:298-301` | `isDialogState`: threshold hardcoded `5` sem config — activities com muitos predecessors legítimos (>5 incoming edges) são falsamente classificadas como "dialog" | Baixa | Susp | G |
| AGT-NV26 | `StatefulAgent.java:1439` | `coverageBoostWeight` decay: `stateVisits / 5` é divisão inteira — steps abruptos em vez de decay suave | Baixa | Conf | UI |
| AGT-NV27 | `StatefulAgent.java:1522` | `isTopLeftClick`: boundary 300px hardcoded — em displays de baixa resolução cobre quase a tela toda; em 4K é 1/8 | Baixa | Conf | UI |
| AGT-NV28 | `StatefulAgent.java:1644-1650` | `recordActionHistory`: `newState.equals(_lastState)` — `_lastState` é null na primeira iteração → `newState.equals(null)` = false → classifica incorretamente como "new screen" | Média | Conf | G |
| AGT-NV29 | `StatefulAgent.java:1653-1655` | `_actionHistory.remove(0)` em ArrayList é O(n) — deveria ser ArrayDeque | Baixa | Conf | G |
| AGT-NV30 | `RandomAgent.java:62-73` | `selectNewActionRandomly` sem validação de enabled/valid — `handleNullAction()` pode entrar loop se todas actions são invalid | Média | Susp | G |
| AGT-NV31 | `StatefulAgent.java:1309-1365` | `adjustActionsByGUITree`: `resetBoosts()` zera todos boosts — se chamado múltiplas vezes, boosts anteriores são perdidos | Média | Susp | MOP-C |
| AGT-NV32 | `SataAgent.java:736-766` | `collectTrivialActivities`: `Arrays.sort` muta array original de `getGraph().getActivityNodes()` — ordenação afeta outros callers | Média | Susp | G |

### 1.2 `ape/naming/` — Abstração/Refinamento

| ID | Onde | Achado | Sev | Conf | Métrica |
|---|---|---|---|---|---|
| N18 | `Naming.java:466` | `treeToNamingResult` é `transient` sem `readObject` — pós-desserialização é null → NPE em `naming()` e `release()` | Alta | Conf | G |
| N19 | `Naming.java:156-183` | `addNamedNode` é dead code (zero call sites no codebase inteiro); retorna sempre `true` | Baixa | Conf | G |
| N20 | `Naming.java:236-249` | `fineness` fica `-1` se `namelets.length == 0` (sentinel frágil) | Baixa | Média | G |
| N21 | `NamingFactory.java:1028-1029` | Format string com 3 `%d` mas 4 argumentos — `affectedThreshold` nunca impresso | Baixa | Conf | G |
| N22 | `NamingFactory.java:280` | Log diz "GUI trees" mas condição verifica `an.getStates().size()` — mismatch semântico entre condição, mensagem e valor logado | Média | Conf | G |
| N23 | `NamingFactory.java:76` | Typo: `guiTreeNamingBlaclist` (falta 'c' em "Blacklist") | Baixa | Conf | G |
| N24 | `AbstractNamingManager.java:46` | `debug` é `static boolean = true` (não-final) — verificações de sanidade dispendosas rodando sempre em produção | Média | Conf | G |
| N25 | `NameManager.java:27-28` | Caches estáticos `names` e `nameList` nunca são limpos — memory leak em sessões longas | Média | Conf | G |
| N26 | `NamedNodePartition.java` | Classe inteira é dead code — nunca instanciada | Baixa | Conf | G |
| N27 | `NamingFactory.java:1249-1256` | Método `createAssertActionDivergent` cria `AssertActionDivergent2` — nome enganoso | Baixa | Média | G |
| N28 | `AbstractPredicate.java:38-43` | `compareTo` retorna 0 para mesmo `Type` — PriorityQueue não garante ordem determinística entre asserts | Baixa | Média | G |
| N29 | `Naming.java:185-196` | `comparator` para Namelet ordena por `(depth, exprString)` mas `Namelet.equals()` compara por `(exprStr, namer, type)` — inconsistência pode causar comportamento indefinido em `binarySearch` | Média | Média | G |
| N30 | `Naming.java:156-183` | `addNamedNode` retorna `true` mesmo quando nó é filtrado — API incorreta | Baixa | Conf | G |
| N31 | `NamingFactory.java:1167` | Typo: `actionRefinmentThreshold` (falta 'e' em "Refinement") — propagado para Config e ModelAction | Baixa | Média | G |

### 1.3 `ape/model/` — Grafo de Exploração

| ID | Onde | Achado | Sev | Conf | Métrica |
|---|---|---|---|---|---|
| MOD-01 | `Graph.java:383-406` | **1º overload de `addTransition` NÃO marca visited para source state nem para action** — apenas marca a edge. Comparar com 2º overload que marca source+action+edge. Se o 1º overload é usado em exploração live, source e action ficam com `visitedAt=0` | **Alta** | Conf | UI |
| MOD-02 | `ModelAction.java:188-189` | **Divisão inteira na saturação**: `this.visitedCount / total` é `int/int` — para visitedCount=1 e nodes≥2: `1/2 = 0` em vez de `0.5F`. Saturação reportada como 0.0 quando deveria ser 0.5 | **Alta** | Conf | UI |
| MOD-03 | `Model.java:110` | `Logger.wformat("Fail to save...%s", actionHistory)` — passa a `List` em vez do `File`. Log imprime toString() inútil | Média | Conf | G |
| MOD-04 | `State.java:357-360` | `resolveAction` não verifica `treeHistory.isEmpty()` — `getLatestGUITree()` faz `treeHistory.get(-1)` → IndexOutOfBoundsException | Média | Susp | G |
| MOD-05 | `Graph.java:310-316` | `fireNewStateEvents`/`fireStateTransitionEvents` itera `listeners` sem null-check → NPE se `fireEvents=true` mas `listeners=null` | Média | Susp | G |
| MOD-06 | `GraphElement.java:55-64` | `visitedAt()` atribui `lastVisitTimestamp` e incrementa `visitedCount` ANTES de validar `firstVisitTimestamp == -1` → estado inconsistente se timestamp==1 | Baixa | Conf | G |
| MOD-07 | `Model.java:397-401` | NPE em `update(ModelAction)` — `state.getLatestGUITree()` retorna null quando `treeHistory` é null | Média | Conf | G |
| MOD-08 | `Model.java:219-223` | Overflow inteiro no comparador de GUITreeTransition — subtração int para runs > 2^31 passos | Baixa | Susp | G |
| MOD-09 | `State.java:487-492` | `removeLastLastGUITree` não limpa referência do tree removido — tree ainda aponta para este State via `getCurrentState()` | Baixa | Conf | G |
| MOD-10 | `Graph.java:604,624` | Argumento de formato não utilizado — `pw.format("digraph GSTG {\n", 1)` | Baixa | Conf | G |
| MOD-11 | `ModelAction.java:49` | Typo: `resovledTimestamp` (deveria ser `resolvedTimestamp`) | Baixa | Conf | G |
| MOD-12 | `Graph.java:1193` | `isLikeBack` lança `RuntimeException("Not implemented")` — dead code que crasha se condição for falsa para click action | Média | Conf | G |
| MOD-13 | `XPathActionReader.java:39-40` | `InputStream.available()` não garante tamanho completo — buffer parcial → parse JSON falha silenciosamente. Mesmo bug em `XPathletReader.java:41-42` | Média | Conf | G |
| MOD-14 | `XPathActionReader.java:56` | `System.exit(1)` em exceção de parse — mata o processo inteiro em vez de propagar exceção | Média | Conf | G |
| MOD-15 | `StateKey.java:113` | `containsTarget` usa `Arrays.binarySearch` que requer array ordenado — se `widgets` não estiver sorted, retorna resultado incorreto | Média | Susp | G |
| MOD-16 | `Graph.java:449` | `added != Utils.addToMapMapIfAbsent(...)` sanity check assume atomicidade — pode falhar falsamente sob concorrência | Baixa | Susp | G |

### 1.4 `ape/tree/` + `ape/events/` — Captura e Execução

| ID | Onde | Achado | Sev | Conf | Métrica |
|---|---|---|---|---|---|
| TEV-01 | `GUITree.java:115-133` | `hasFocusedNode()`: branch array faz `break outer` sem setar `focused = true` — retorna **sempre false** quando focused está em array de nós | **Alta** | Conf | UI |
| TEV-02 | `GUITreeBuilder.java:136-137` | `toBoundsInParent()` usa `screen.right - parentScreen.right` / `screen.bottom - parentScreen.bottom` — deveria ser `- parentScreen.left` / `- parentScreen.top`. Gera bounds com bottom negativo | **Alta** | Conf | UI |
| TEV-03 | `GUITreeBuilder.java:499-500` | `parseRect()` retorna null para bounds malformados → `n.setBoundsInScreen(null)` → NPE | Média | Conf | G |
| TEV-04 | `GUITreeBuilder.java:535-543` | `createDocument()` retorna null em `ParserConfigurationException` → `document.appendChild()` NPE posterior | Média | Conf | G |
| TEV-05 | `GUITreeAction.java:57` | Typo: `getThrotlle()` (double 'l') — setter `setThrottle()` ok, mas caller usando `getThrottle()` não encontra | Baixa | Conf | G |
| TEV-06 | `XPathBuilder.java:29` | `xpath` estático compartilhado entre threads — `XPath` não é thread-safe, `compile()` pode corromper estado interno | Média | Susp | G |
| TEV-07 | `XPathBuilder.java:30` | `cached` HashMap declarado mas **nunca lido/escrito** — dead code | Baixa | Conf | G |
| TEV-08 | `GUITree.java:105-107` | `isIsomorphicTo()` lança `RuntimeException("Not implemented")` — dead code | Baixa | Conf | G |
| TEV-09 | `RandomHelper.java:163-164` | `nextByte()` retorna `[-128, 126]` — `Byte.MAX_VALUE` (127) **nunca retornado** (off-by-one) | Baixa | Conf | G |
| TEV-10 | `ApeTrackballEvent.java:85-95` | `fromJSONObject` não valida `deltaX.length == deltaY.length` — JSON com arrays diferentes → ArrayIndexOutOfBoundsException | Média | Conf | G |
| TEV-11 | `ApeClickEvent.java:74` | `longClick` serializado como String `"true"` mas desserializado via `getBoolean()` — funciona mas frágil | Baixa | Conf | G |
| TEV-12 | `GUITreeBuilder.java:627-628,650` | Caches estáticos `namingToGUITreeCache` etc. nunca fully cleared — memory leak em sessões longas | Média | Conf | G |
| TEV-13 | `GUITreeNode.java:627-634` | `computeAndSetImageText`: `getPixels` copia `(w-1)×(h-1)` mas hash itera `width×height` — lê zeros na última coluna/linha | Baixa | Conf | G |
| TEV-14 | `GUITree.java:48,57-59` | `loadedGUITrees.add(tree)` em `setDocument` não verifica duplicatas — se chamado 2×, entra 2× | Baixa | Conf | G |
| TEV-15 | `GUITreeBuilder.java:305-321` | `sameRow()` e `sameColumn()` retornam true para 1 filho — faz `doPatchingChildren` retornar true para lista unitária | Baixa | Conf | G |

### 1.5 Raiz `com.android.commands.monkey` + `ape/`

| ID | Onde | Achado | Sev | Conf | Métrica |
|---|---|---|---|---|---|
| MSA-NV09 | `MonkeySourceApe.java:540-542` | `generatePointerEvent` usa `bounds.right`/`bounds.bottom` como width/height — deveria ser `bounds.width()`/`bounds.height()`. Pontos gerados a partir de (0,0) em vez de (bounds.left, bounds.top) | Média | Conf | UI |
| MSA-NV10 | `MonkeySourceApe.java:1268-1270` | `attempToSendTextByKeyEvents`: `CharMap.getEvents(szRes)` pode retornar null → `events.length` NPE | Média | Conf | G |
| MSA-NV11 | `MonkeySourceApe.java:1034-1039` | `while(true)` sem limite de iterações para gerar keycode — se todas chaves excluídas são existentes, loop infinito | Baixa | Conf | G |
| MSA-NV12 | `MonkeySourceApe.java:402-406` | `generateClickEventAt` retorna silencioso quando bounds check falha — caller assume evento gerado. Throttle adicionado mesmo assim. Modelo fica inconsistente | **Alta** | Conf | UI |
| MSA-NV14 | `Monkey.java:399` | `activityStarting`: `intent.getComponent().getClassName()` — `getComponent()` retorna null para intents implícitas → NPE em callback binder | **Alta** | Conf | G |
| MSA-NV15 | `Monkey.java:771-782` | `tearDown()` do MonkeySourceApe não está no `finally` — RuntimeException mata threads/loggers/agent | **Alta** | Conf | G |
| MSA-NV16 | `Monkey.java:543-580` | `commandLineReport`: Process `p` nunca tem `destroy()`; `logOutput` não é fechado no catch → resource leak | Baixa | Conf | G |
| MSA-NV17 | `AndroidDevice.java:628,634,646` | `getFocusedStack()` parse de dumpsys assume hierarquia estrita — se formato variar entre APIs, `currentDisplay`/`currentStack` pode ser null → NPE | Média | Conf | G |
| MSA-NV18 | `AndroidDevice.java:324-326` | `executeCommandAndWaitFor`: Process nunca destruído, stdout/stderr não consumidos — se processo gerar saída que preencha buffer, `waitFor()` hang | Média | Conf | G |
| MSA-NV19 | `MonkeySourceApe.java:202-212` | `tearDown()` chama `disconnect()` que lança `IllegalStateException` se HandlerThread já morreu — `mAgent.tearDown()` e limpeza de ImageWriters nunca executam | **Alta** | Conf | G |
| MSA-NV20 | `MonkeySourceApe.java:1105` | `generateThrottleEvent`: `throttle %= base` quando `base == 0` lança `ArithmeticException` | Média | Conf | G |
| MSA-NV21 | `AndroidDevice.java:176` | `checkInteractive()`: Process nunca destruído, stream não consumido — leak recorrente | Baixa | Conf | G |
| MSA-NV22 | `MonkeySourceApe.java:692` | `generateScrollEventAt`: `action.getResolvedNode().getBoundsInScreen()` — se `getResolvedNode()` retornar null, NPE | Média | Conf | G |
| MSA-NV23 | `MonkeySourceApe.java:402-406` | `generateClickEventAt` retorna sem gerar eventos mas `generateEventsForAction` já registrou no histórico — logs de ação incorretos | **Alta** | Conf | UI |
| MSA-NV24 | `MonkeySourceApe.java:788` | `if (info != null)` é redundante (sempre true após `if (info == null) continue` na linha 784) | Baixa | Conf | G |
| MSA-NV25 | `Monkey.java:320-331` | `appCrashed` no ActivityController: `mRequestBugreport=true` causa `mAbort=true` indevido quando `mIgnoreCrashes=true` | Média | Conf | G |
| MSA-NV26 | `ImageWriterQueue.java:84-88` | `flush()` synchronized chama `writePNG()` (I/O pesado) segurando lock — producer thread bloqueia | Média | Conf | G |

---

## 2. Frente 2 — Schema `<apk>.json` ↔ Parser (NOVOS)

| ID | Onde | Achado | Sev | Conf | Métrica |
|---|---|---|---|---|---|
| FID-01 | JSON root / `MopData.java:178` | `"complete": true` ausente em JSONs legados (169/169) e 7/12 novos → parser rejeita arquivo inteiro; sentinela presente no cryptoapp | Média | Conf | MOP-C |
| FID-02 | `MopData.java:314` / JSON `widgets[].idName` | `idName: ""` aceito como chave — widgets sem resource-id (41% no cryptoapp) entram no mapa com chave `""`; múltiplos colidem (último vence) | Baixa | Conf | MOP-V |
| FID-03 | `MopData.java:468` vs `MopScorer.java:84` | **WTG key mismatch OPTIONSMENU** — `wtgTransitions` keyado por `window.name` (ex: `...#OptionsMenu`), consultado por `baseActivity()` (ex: `...MainActivity`). Chave nunca casa → transições de menu invisíveis ao WTG scoring | **Alta** | Conf | MOP-C |
| FID-04 | JSON transitions / `MopData.java:473` | **WTG targets incorretos** — 3 botões distintos mapeiam à mesma activity errada (showGenerated, showScreenMessageDigest, showScreenCipher todos → CipherActivity). Bug do produtor, não do parser | Média | Conf | MOP-V |
| FID-05 | JSON `widgets[].id` / `MopData.java:343` | Widget `id` numérico lido mas nunca usado — dead data no POJO. `getWidget()` indexa por `idName` (String), não pelo id numérico | Baixa | Conf | MOP-C |
| FID-06 | `MopData.java:340-366` | Widget POJO não inclui propriedades runtime (`checked`, `enabled`, `clickable`) — MOP scoring é puramente estático, não distingue disabled de enabled | Baixa | Conf | G |
| FID-07 | `MopData.java:349-350` | `prompt` e `spinnerMode` sempre null — dead data pipeline (produtor nunca emite, parser armazena null, LLM recebe null) | Baixa | Conf | G |
| FID-08 | `MopData.java:313-316` | Widgets sem resource-id tornam MOP-opacos — 41% dos widgets no cryptoapp; apps com layouts programáticos são especialmente vulneráveis | Média | Conf | MOP-V |
| FID-09 | `MopData.java:468` / `MopScorer.java:84` | WTG key para OPTIONSMENU usa janela, não activity — widget `menu_item_cipher` (OPTIONSMENU) não encontrado pelo WTG scoring | Média | Conf | MOP-C |
| FID-10 | `MopData.java:473-476` | `WtgTransition` usa `widgetName` vazio como fallback para "" — transições com `type != "click"` são filtradas, mas se click tiver `widgetName: null`, gera chave "" colidindo | Baixa | Média | MOP-V |
| FID-11 | `MopData.java:315` | Widgets com `idName` duplicado na mesma activity são silently dropped (LinkedHashMap última-escrita-vence) | Média | Conf | MOP-V |
| FID-12 | JSON `reachability` vs `windows` | Reachability classes não incluem widgets — flags `directMop`/`transitiveMop` derivados por cross-reference de `bySignature`. Se assinatura do handler não casar, widget perde MOP flag silenciosamente | Média | Conf | MOP-C |
| FID-13 | `MopData.java:584` | OPTIONSMENU gateway detection usa `w.type == "OPTIONSMENU"` — se produtor emitir tipo diferente (ex: `"OPTIONS_MENU"`), detecção falha silenciosamente | Baixa | Conf | MOP-C |
| FID-14 | `MopData.java:579-612` / `MopScorer.java:77-91` | `scoreWtg` não verifica se o widget atual é o源头 do WTG — qualquer widget com mesmo `idName` em activity com WTG recebe boost, mesmo que não seja o源头 | Média | Conf | MOP-C |
| FID-15 | JSON `transitions[].events[].handler` | Handler signatures incluem lambdas — se reachability não incluir a lambda (generated code), handler não casa e widget perde MOP flag | Média | Conf | MOP-C |

### Resumo quantitativo do cryptoapp.apk.json
- 51 widgets → 21 (41%) com `idName=""` (MOP-opacos)
- 30 widgets com `idName` → todos com resource-id presente
- `contentDescription`, `tooltipText`, `prompt`, `spinnerMode`: 100% null
- OPTIONSMENU: 3 widgets, 2 sem listeners (WTG-only)
- WTG transitions: 9 de OPTIONSMENU (4 targets corretos), 6 de MainActivity (3 targets errados)

---

## 3. Frente 3 — Suficiência do Log `.trace`

### 3.1 Lacunas identificadas (NOVAS)

| ID | Lacuna | Impacto | Métrica |
|---|---|---|---|
| L-1 | **Ausência do conjunto candidato** no ponto de seleção — `greedyPickLeastVisited` e `pickAction` não logam quais ações estavam no conjunto, visitCount, nem priority de cada uma | Impossível reconstruir por que X foi escolhida em vez de Y | G |
| L-2 | **Epsilon-greedy outcome não logado** — `v` e `epsilon` logados, mas branch taken (random vs greedy) não | Inferência indireta necessária | G |
| L-3 | **Per-action boost breakdown ausente** — logs de boost mostram agregados por state, não por widget específico | Ranking final de prioridades inreconstruível | MOP-C |
| L-4 | **Action validation failure sem reason** — "Mark an action invalid" sem explicar por quê | Ação descartada sem motivo | G |
| L-5 | **Widget count por step não logado** — número de widgets/actions disponíveis na tela em cada step não é emitido | Cobertura intra-tela precisa ser inferida indiretamente | UI |
| L-6 | **Coverage interaction não logada** — `recordInteraction` executado silenciosamente | Qual widget foi interagido é invisível | UI |
| L-7 | **Action buffer contents ocultos** — `dformat` em nível DEBUG desativado | Ações pendentes invisíveis | G |
| L-8 | **graphStableCounter não logado por step** — incrementado mas só aparece quando atinge threshold | Timing de restarts inreconstruível | G |
| L-9 | **MOP violations ausentes do .trace** — violações JavaMOP ficam no logcat, não no trace | Gap entre detecção e registro | MOP-V |
| L-10 | **Action history ring buffer não logado** — `_actionHistory` gravado mas nunca no trace | Contexto LLM invisível | G |

### 3.2 Cobertura estimada ANTES vs DEPOIS da instrumentação proposta

| Métrica | Antes | Depois |
|---|---|---|
| Decisão (por que X e não Y) | ~30% (só vencedor) | ~85% (candidatos + boosts + branch) |
| Causalidade (boost vs motivo) | ~20% (agregados) | ~70% (per-action ranking) |
| UI/state (widgets vs interagidos) | ~10% (só via XML dump) | ~60% (count + interaction) |
| MOP violations (detecção vs log) | ~0% no trace | ~40% (logcat bridge, periódico) |

### 3.3 Proposta concreta de instrumentação (10 mudanças, ~35 linhas, custo zero no hot path)

| # | Local | Mudança | Fecha |
|---|---|---|---|
| I-1 | `State.java:124-140,160-176` | Log do conjunto candidato + vencedor em `greedyPickLeastVisited` e `pickAction` | L-1 |
| I-2 | `SataAgent.java:705-713` | Log do branch taken em `egreedy` (`exploit=%b`) | L-2 |
| I-3 | `StatefulAgent.java:1457` | Dump top-3 ações por prioridade final (pri, mop, wtg, cov, menu) | L-3 |
| I-4 | `StatefulAgent.java:1235-1246` | Adicionar reason ao warning de action invalid | L-4 |
| I-5 | `StatefulAgent.java:1266-1272` | Adicionar campo `widgets=%d` ao `[APE-STEP]` | L-5 |
| I-6 | `StatefulAgent.java:1195` | Log de `recordInteraction` (widget id, state) | L-6 |
| I-7 | `StatefulAgent.java:437` | Log de buffer size em peek | L-7 |
| I-8 | `StatefulAgent.java:1211-1232` | Log de stability counters por step | L-8 |
| I-9 | `MonkeySourceApe.java` (a cada 50 steps) | Bridge logcat→trace para MOP violations | L-9 |
| I-10 | `StatefulAgent.java:1595` | Log de action history size | L-10 |

---

## 4. Frente 4 — Avaliação da Change em Andamento

### 4.1 Mudanças que CORRIGEM o que alegam

| # | Mudanças | Veredito |
|---|---|---|
| #0 | Parser fidelity | **CORRIGE.** Retém flag mais forte por colisão (mopRank), re-chaveia WTG por baseActivity, precompute do menu re-apontado. Testes pairwise reais nos 2 sentidos |
| #4 | Dump UICoverageTracker | **CORRIGE.** Read-only, roda uma vez em tearDown, sem custo no hot path |

### 4.2 Mudanças com PROBLEMAS SIGNIFICATIVOS

| ID | Onde | Achado | Sev | Métrica |
|---|---|---|---|---|
| CHG-01 | `StatefulAgent.java:184` | **BLOQUEANTE: fill determinístico é código morto.** Pipeline `checkInput(checkFuzzing(checkRestart(updateStateInternal())))` — `updateStateInternal` chama `moveForward()` que **anula `newState`** em todos os caminhos antes de `checkInput` rodar. O override `inFormCompletionContext()` lê `newState==null` e retorna sempre false. O ramo "preencher deterministicamente" NUNCA dispara | **Bloqueante** | MOP-C |
| CHG-02 | `SataAgent.java:497` | **Guard INV-FORM-06 é derrotado**: EARLY_STAGE roda antes do epsilon-greedy e roleta o submit com prioridade ~752 (32 base + 20 unvisited + 500 mop + 100 W_SUBMIT + 100 cov) vs ~302 por campo — P(submit vazio) ≈ 55-71% na 1ª visita | **Alta** | MOP-V |
| CHG-03 | `SataAgent.java:471-476` | **Short-circuit MOP sombreado pelo EARLY_STAGE**: ações unvisited-by-name são consumidas pela roleta antes do ramo do short-circuit. Quantificado: EARLY_STAGE=57.6% das decisões; dentro de EPSILON_GREEDY apenas 1.5% das seleções foram UNVISITED → mecanismo determinístico opera em **<1% das decisões** | **Alta** | MOP-C |
| CHG-04 | `StatefulAgent.java:681` | One-shot do short-circuit queimável por restart: `markVisited` precede `checkRestart` | Média | MOP-C |
| CHG-05 | `FormCompletion.java:51` | Predicado 'unfilled' **nunca converge**: `inputText` é anotação transiente por captura — nada copia entre capturas nem deriva de `getText()` → `hasUnfilledEditText` permanentemente true | **Alta** | MOP-C |
| CHG-06 | `SataAgent.java:241` | decision_source segue correlacional: sub-ramos path-based (ABA/refillBuffer/global/shortest-path) não consomem priority; Back/Menu unvisited dentro de EPSILON_GREEDY são atribuídos por argmax de boost — todo estado novo com MENU unvisited sai `decision_source=Menu` embora boost irrelevante | Média | G |
| CHG-07 | `SataAgent.java:244` | `formBoost` invisível na atribuição (não existe `DecisionSource.Form`): campo escolhido por W_FILL=150 é rotulado Coverage ou SATA | Média | G |
| CHG-08 | `MopData.java:643` | Gateway OPTIONSMENU sobre-aproximado pós re-chaveação: qualquer aresta click da base activity que alcança MOP qualifica o menu (+250) | Média | UI |
| CHG-09 | `FormCompletion.java:83,112` + `GUITreeNode:199` | Heurística de submit: (a) candidato arbitrário entre empates; (b) lone-Button ignora texto; (c) Compose/Material sem 'Button' → submit=none; (d) `isEditText` exato → **form-completion inerte em apps AndroidX/Material/Compose** | **Alta** | MOP-V |
| CHG-10 | `MopData.java:359` | Premissa do drop de widgets sem id é factualmente falsa no spec — `extractShortId` retorna `""` exatamente para nós sem resourceId | Média | MOP-C |
| CHG-11 | `ApeAgent.java:189` | Cenário "legacy toss preservado" do spec é insatisfazível | Média | G |
| CHG-12 | vários | Débito de spec/tasks: spec `mop-guidance` não sincronizada; tasks marcadas [x] com testes prometidos ausentes; comentário "+100 fallback substrate" obsoleto | Média | G |

### 4.3 Interações avaliadas

- **Short-circuit MOP vs Back/Menu vs FormCompletion**: precedência Back/Menu unvisited > MOP short-circuit > roulette. OK no design, mas SHORT-CIRCUIT MOP opera em <1% das decisões (CHG-03)
- **`attributeDecisionSource`**: only on EARLY_STAGE/EPSILON_GREEDY branches. Correto, mas zero testes unitários
- **Spec desatualizado**: `openspec/specs/mop-guidance/spec.md` ainda cita `mopWeightActivity`, fallback +100 e INV-MOP-07. **Precisa de `opsx:sync`**

### 4.4 Pronta para experimento de validação (§7.5)?

**NÃO.** Bloqueadores: CHG-01 (fill determinístico morto), CHG-02+CHG-03 (mecanismo-bandeira opera fora do caminho dominante), CHG-05 (convergência), e S5/A5 (sem fail-fast de load). Parts #1 e #4 estão sólidas.

---

## 5. Mapeamento Explícito ao Objetivo

### (a) Cobertura de UI
Achados que afetam diretamente: **MOD-01** (addTransition 1º overload não marca visited), **MOD-02** (divisão inteira saturação), **TEV-01** (hasFocusedNode sempre false), **TEV-02** (bounds-in-parent sinal trocado), **MSA-NV12/23** (clique silencioso no-op), **MSA-NV09** (pontos de gesture errados), **R1** (waitForActivity sem timeout — runs de 0 ações), **T1** (clearChildren一半), **N18** (pós-desserialização NPE), **CHG-09** (form-completion inerte em Compose).

### (b) Cobertura de métodos/classes/operações MOP
Achados: **FID-03/09** (WTG key mismatch), **FID-04** (WTG targets errados do produtor), **FID-11** (widgets idName duplicado dropped), **FID-12** (reachability cross-reference frágil), **FID-14** (scoreWtg não verifica源头), **CHG-01/03/05** (change #1/#2/#5 não operam no caminho dominante), **S1** (dialogs indexados pela classe errada), **U4** (typed input lookup exato), **A1** (triggering quebrado para subpacotes).

### (c) Violações MOP encontradas
Achados: **CHG-02** (submit-before-fill impede fluxo completo), **CHG-09** (heurística de submit erra em Compose/Material), **FID-08** (41% widgets MOP-opacos), **L-9** (violações não aparecem no trace).

### Mecanismos que limitam artificialmente o que é contado
- **O1/A3** (interação creditada sem execução)
- **O2** (superconta de estados por parser ingênuo)
- **U3** (eviction re-zera cobertura observável)
- **MOD-01** (addTransition 1º overload não marca visited → contadores zerados)
- **MOD-02** (divisão inteira → saturação sub-representada → ação parece não-saturada por mais tempo)
- **M1/M2** (contadores inflados suprimem re-exploração de telas refinadas)

---

## 6. Lista Priorizada de Próximos Passos

### (i) Bloqueadores de validade de experimento

| # | Item | Fix | Experimento mínimo |
|---|---|---|---|
| 1 | **CHG-01** fill determinístico morto | Ler `currentState` ou capturar contexto antes de `moveForward` em `inFormCompletionContext` | Teste unitário + run curto com `Input text` dentro de form context |
| 2 | **CHG-03** short-circuit sombreado + **CHG-02** guard furado | Aplicar exclusão do submit e preferência MOP na roleta do EARLY_STAGE, ou mover short-circuit antes do EARLY_STAGE | Grep `strategy=`×`decision_source`: fração de alvos MOP unvisited consumidos por EARLY_STAGE deve cair de ~99% |
| 3 | **CHG-05** convergência unfilled | Derivar 'filled' de `getText()` da captura corrente ou persistir por identidade de widget (xpath) no nível do State | Teste host: duas capturas consecutivas, campo digitado não volta a unfilled |
| 4 | **S5/A5** load 1-arg + falha silenciosa | Passar package/mainActivity no load; logar `package=/parsedWidgets=/collided=`; fail-fast configurável | Inspeção de 1 linha do trace |
| 5 | **MOD-01+MOD-02** inflação de contadores | Remover `visitedCount++` incondicional de `rebuildHistory`; cast para float na saturação | Teste host: rebuild 2× e assert visitedCount estável |
| 6 | **I-1 a I-10** instrumentação | As 10 mudanças de log (§3.3) | Sem elas, pós-hoc do fair-test repete inferências não-causais |

### (ii) Débito técnico geral (independente de MOP)

| Prioridade | Item | Ação |
|---|---|---|
| Alta | **TEV-01** hasFocusedNode sempre false | Corrigir para setar `focused = true` antes do break |
| Alta | **TEV-02** bounds-in-parent sinal trocado | Corrigir para `screen.left - parentScreen.left` |
| Alta | **MSA-NV12/23** clique silencioso no-op | Descartar ação em vez de clicar no centro; não registrar no histórico |
| Alta | **MSA-NV15** tearDown em finally | Mover para finally block |
| Alta | **MSA-NV19** tearDown NPE em disconnect | Wrapping em try-catch |
| Alta | **N18** treeToNamingResult transient | Adicionar readObject ou inicializar no construtor |
| Média | **MOD-02** divisão inteira saturação | Cast para float |
| Média | **MOD-03** argumento de formato errado | Trocar `actionHistory` por `file` |
| Média | **MSA-NV09** pontos de gesture | Usar `bounds.width()`/`bounds.height()` |
| Média | **U2** RNG não-semeável | Semear ThreadLocalRandom com `-s` do Monkey |
| Média | **TEV-12** static cache memory leak | Limpar em release() |
| Baixa | **AGT-NV27** 300px hardcoded | Usar proporção da tela |
| Baixa | **FID-05** Widget.id dead data | Remover do POJO ou usar como fallback |

### (iii) Lacunas de observabilidade

Proposta §3.3 (10 instrumentações) + O2 (new= no Create state), O4 (linhas de boost incondicionais), O8 (telemetria de budget), C7 (DecisionSource.Form).

### (iv) Propostas novas (ninguém tinha levantado)

1. **Re-chavear janelas DIALOG à activity hospedeira** usando arestas WTG activity→dialog já no JSON — desbloqueia 86 widgets flagged (S1)
2. **Política de match para nós sem id**: casar `""` apenas quando `(activity,eventType,className)` for única — recupera labnex/duress sem ruído uniforme (S2)
3. **Reordenar pipeline** `checkRestart(updateStateInternal(...))` → decidir restart **antes** de marcar visited/coverage (resolve A3/O1 na raiz)
4. **`isDialogState` invertido**: trocar para `!hasGreedyActionForward` e medir efeito no ABA (A7)
5. **Timeout+relaunch em waitForActivity** com contador e `startRandomMainApp` forçado após N ciclos (R1)
6. **Semear RandomHelper** com `-s` do Monkey (U2) — pré-requisito para estudo de variância

---

## 7. Limitações

- **Sem execução em dispositivo**: mecanismos confirmados por leitura de código e evidência empírica em traces reais. Frequências de disparo de N19, N24, T3, T7, M3-M5, R3, R5 não foram medidas
- **Corpus de JSON**: apenas fixture cryptoapp verificável dentro deste repo; números de FID-01 sobre JSONs legados vêm do levantamento da análise fable5
- **Cadeia de violações MOP** (JavaMOP→logcat→contagem) é externa ao repo — Q4 respondida só do lado do trace
- **Proveniência upstream**: não diffei contra APE original da ETH; T3, AGT-NV09, A12 e parte do débito podem ser herdados
- **openspec validate --strict não executado**
- **Achados de severidade média/baixa** foram rastreados mas não receberam refutador dedicado — severidades podem estar superestimadas
- **FormCompletion.java (novo)** foi avaliado estáticamente; validação em dispositivo é pré-requisito antes de qualquer conclusão sobre o worktree
