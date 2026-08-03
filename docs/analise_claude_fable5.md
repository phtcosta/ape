# Análise completa do APE-RV — código, dados estáticos, observabilidade e change em andamento

**Modelo:** Claude Fable 5 · **Data:** 2026-07-02 · **Base:** master `f70f986` + worktree `ape-mop-fairtest` (não commitado)

## 0. Metodologia e escopo

Investigação em 4 fases com 32 subagentes: (1) catálogo de achados já documentados (`docs/20260622_investigacao_mop.md`, `docs/analise_claude_sonnet5.md`, `docs/20260621_plano_correcao...md`) para dedup NOVO vs conhecido; (2) 9 auditores em paralelo — um por pacote (`agent/`, `naming/`, `model/`, `tree/`+`events/`, `utils/`, raiz Monkey+`ape/`, `llm/`+`reducer/`) mais Frente 2 (schema JSON, incluindo levantamento python de chaves sobre 169 JSONs legados + 12 exemplares novos do rv-android) e Frente 3 (log `.trace`), todos lendo os arquivos **inteiros** (≈32k linhas cobertas); (3) 2 avaliadores da change (correção end-to-end + testes/specs, com `mvn test` real no worktree); (4) **verificação adversarial**: cada achado novo bloqueante/alto (20) recebeu um agente cético instruído a refutá-lo, com acesso ao código e a traces reais de experimentos em `rvsec/rv-android/results/`.

**Resultado da verificação:** 19 confirmados, 1 **refutado**, 7 com severidade **rebaixada** após a tentativa de refutação. Total bruto: 145 achados (após dedup entre agentes: ~130 únicos). As severidades abaixo já são as pós-verificação. Legenda de métrica: **UI** = cobertura de UI; **MOP-C** = cobertura de métodos/classes/operações MOP; **MOP-V** = violações MOP distintas; **G** = geral/qualidade. Confiança: **conf** = rastreado end-to-end; **susp** = plausível, disparo não rastreado. `[VERIF]` = sobreviveu à refutação adversarial.

---

## 1. Frente 1 — Catálogo de bugs/anomalias no código (master)

### 1.1 `ape/agent/` — núcleo de decisão

| ID | Onde | Achado | Sev | Métrica | Status |
|---|---|---|---|---|---|
| A1 | `StatefulAgent.java:1090` | `dispatchTrigger` deriva o package do `ComponentName` por substring do className — para componentes em subpacotes (caso do fixture real: `br.unb.cic.cryptoapp.messagedigest.*` sob package `br.unb.cic.cryptoapp`) o Intent explícito não resolve **nada** e a falha é 100% silenciosa (broadcast a componente inexistente não erra; o log `[APE-RV] Triggering` sai antes). O mecanismo gh11/gh13 de triggering só funciona para classes no pacote raiz — e os runs gh11 (componentPercentage=0.05) exercitaram o path quebrado | alta/conf `[VERIF]` | MOP-C | NOVO |
| A2 | `SataAgent.java:246` | `checkBackTrack` é **triplamente morto**: zero call sites, BFS que nunca enfileira (marca o nó desenfileirado em vez do target — confirma K08 com precisão) e guard insatisfazível. Não existe fuga de saturação além de restarts | media/conf | UI | K08 ampliado |
| A3 | `StatefulAgent.java:671` + `ApeAgent.java:325` | Ação é marcada visitada (`markVisited`) e contada no coverage tracker **na seleção**; `checkRestart` pode então descartá-la e substituí-la por `EVENT_RESTART` (a cada 100–300 passos + restarts de estabilidade). O widget descartado — tipicamente o unvisited de maior valor — vira "visitado" sem execução, para sempre | media/conf `[VERIF]` (frequência ~0,3–1% dos passos) | UI | NOVO |
| A4 | `StatefulAgent.java:297` | `checkFuzzing(ModelAction)` é **overload jamais invocado** (dispatch estático resolve para `ApeAgent.checkFuzzing(Action)`): a proteção `fuzzingActivityVisitThreshold` é config morta; fuzzing 2% dispara mesmo em activities recém-descobertas | media/conf | UI | NOVO |
| A5 | `StatefulAgent.java:162` | `MopData.load` continua **1-arg** no único call site de produção → `mopStrictPackageMatch` é inalcançável no master, **contradizendo o status "corrigido gh15" de K24**. O guard desenhado contra a classe de falha K01 (skew JSON×APK, que invalidou o experimento de junho) não pode disparar | media/conf | MOP-C | contradiz K24 |
| A6 | `ReplayAgent.java:156` | Todo model action sem target replaya como `getBackAction()` — MODEL_MENU gravado (inclusive os induzidos por `mopWeightOpenMenu`) vira BACK no replay; reprodução de crash diverge silenciosamente | media/conf | G | NOVO |
| A7 | `SataAgent.java:291` | `isDialogState` contradiz a intenção logada ("saturated dialog"): a condição seleciona estados com >5 in-edges **que ainda têm ações greedy** — bloqueia o ABA justamente para hubs não-saturados | media/susp | UI | NOVO |
| A8 | `StatefulAgent.java:664` | `widgetCount` do budget conta **ações** requireTarget (um widget com click+long-click+4 scrolls conta 6): budget por activity inflado 2–6× e heurística de "widget" divergente da do UICoverageTracker | baixa/conf | UI | NOVO |
| A9 | `StatefulAgent.java:1356` | `priority += 10; // make it weaker` — comentário e lógica opostos: somar prioridade **promove** a edge flaky na roleta | baixa/conf | UI | NOVO |
| A10 | `SataAgent.java:341` | Hook LLM de estagnação exige igualdade exata no midpoint do contador **e** buffer vazio no mesmo passo — janela frequentemente perdida; `llmOnStagnation` dispara muito menos que o desenhado | baixa/conf | UI | NOVO |
| A11 | `StatefulAgent.java:153` | `refreshStatesCheckingBlacklist` cresce monotonicamente e fica órfã após refinamento (referências a States substituídos): blacklist inefetiva + retenção de memória | baixa/conf | G | NOVO |
| A12 | `SataAgent.java:414` | Short-circuit BACK/MENU unvisited preempta qualquer boost MOP e usa só `isValid()` (sem `enabled`) — mecânica de código por trás da fatia de orçamento K56 | obs/conf | UI | K56 |
| A13 | `SataAgent.java:65,155,441` | Código morto: `unsaturatedActionsFilter`, `weakActionSubsequenceFilter`, `fillTransition(State[])` + 2 flags de Config associadas | obs/conf | G | NOVO |
| A14 | `ApeAgent.java:76` | `ape.agentType` desconhecido cai **silenciosamente** em SataAgent (braço errado rodaria sem indício); tipo `ape` prometido no CLAUDE.md não existe | obs/conf | G | NOVO |

### 1.2 `ape/naming/` — abstração/refinamento (inovação central)

| ID | Onde | Achado | Sev | Métrica | Status |
|---|---|---|---|---|---|
| N1 | `Naming.java:438` | `select(List<Namelet>)` testa `binarySearch == -1` em vez de `< 0` — pai ausente com ponto de inserção ≠ 0 é tratado como presente; pode retornar namelet refinado cujo pai não casou (mesmo padrão de K03, ocorrência nova) | media/susp | UI | NOVO |
| N2 | `NamingFactory.java:280,1180` | Guarda de `maxGUITreesPerState` **inalcançável** (copy-paste testa `an.getStates().size()` duas vezes): a flag é dead config, não há limite real de GUITrees por estado (alimenta o OOM conhecido); com `maxStatesPerActivity>20` o refinamento seria silenciosamente travado | media/conf | G | NOVO |
| N3 | `Naming.java:156` | `ignoreEmpty`/`ignoreOutOfBounds` (default true) **sem efeito**: `addNamedNode`, único consumidor, tem zero call sites — nós vazios/offscreen entram no StateKey e inflam estados (agrava K27) | media/conf | UI | NOVO |
| N4 | `TextNamer.java:110` | Supressão de @text para EditText (`getAttributeValue`) é código morto: sob refinamento TEXT, cada string digitada fabrica TextName→StateKey→estado novo. Interage diretamente com FormCompletion/K39 (fragmentação por digitação) | media/susp | UI | NOVO |
| N5 | `NamerType.java:43` | `complementOf()` ignora o argumento (é `allOf()`) — validação "Incomplete lattice" do NamerLattice é vácua | baixa/conf | G | NOVO |
| N6 | `NamerFactory.java:220` | `escapeToXPathString` é no-op (`replaceAll("\"","\\\"")` produz a mesma string); segurança dos XPaths depende só do `removeQuotes` a dois arquivos de distância | baixa/conf | G | NOVO |
| N7 | `ActionPatchNamer.java:100` | Chave de interning (`toString`) omite `scrollType` enquanto equals/hashCode o incluem — dois Names iguais exceto direção de scroll colapsam no cache (ações de scroll erradas para o segundo) | baixa/susp | UI | NOVO |
| N8 | `Naming.java:489` | `finally` de `naming()` dereferencia `results` nulo quando `namingInternal` lança — NPE mascara a exceção original no funil único de abstração | baixa/conf | G | NOVO |
| N9 | `AssertStatesDivergent.java:43` | Predicado nunca popula o set `states` (vacuamente true) — coleção-nunca-populada clássica; hoje sem call sites | baixa/conf | G | NOVO |
| N10 | `NameManager.java:29` + `Naming` | Caminho de reload de modelo (`Graph.readGraph`) quebrado: `treeToNamingResult` transient fica null pós-desserialização; intern estático reseta `orders` → IllegalStateException em compareTo | baixa/susp | G | NOVO |
| N11 | `NamerComparator.java:44` | Desempate por soma de ordinais não é ordem total ({TYPE,TEXT} vs {INDEX,PARENT} empatam): escolha do namer refinado depende da ordem de iteração de HashMap — **irreprodutibilidade** do refinamento | baixa/conf | G | NOVO |
| N12 | `NamingFactory.java:162` | Blacklists de refinamento (`NDActionBlacklist`, `actionRefinementBlacklist`, `guiTreeNamingBlacklist`) nunca resetam, com limiares fixos; combinadas com o teto `maxStatesPerActivity=10`, o CEGAR desliga cedo e permanentemente em activities complexas | baixa/conf | UI | NOVO |
| N13 | `NamingFactory.java:370` etc. | Código morto acumulado: `visited`/`queue` em stateRefinement, `MonolithicNamingManager`, `NamedNodePartition`, `GUITreeProperty`, `Naming.join` | obs/conf | G | NOVO |
| N14 | `Naming.java:252` | **Refutação parcial de K04**: `hasChild()` invertido é neutralizado pelo único chamador (`isLeaf` — dupla inversão acidentalmente correta); impacto do K04 está superestimado no catálogo | obs/conf | G | corrige K04 |
| N15 | `Namelet.java:159` | K06 confirmado na linha, mas o vetor óbvio (aspas) está defendido por `removeQuotes` na captura — risco real é menor que o catalogado | baixa/conf | G | refina K06 |

### 1.3 `ape/model/` — grafo de exploração

| ID | Onde | Achado | Sev | Métrica | Status |
|---|---|---|---|---|---|
| M1 | `Graph.java:1293` | **`rebuildHistory()` infla `visitedCount` de TODAS as arestas a cada rebuild** (dobra no 1º refinamento, cresce a cada um): `markVisited` já conta na re-adição e o loop soma de novo sobre o histórico inteiro, sem reset em lugar algum. Consumidores viciados: `weakActionSubsequenceFilter` (`visitedCount<3` — desiste cedo de re-explorar transições não-determinísticas), comparações do SataAgent, printVis. Dispara em todo refinamento (`evolveModel` default true) — rotineiro em apps reais | **alta**/conf `[VERIF]` | UI | NOVO |
| M2 | `Graph.java:427` + `Model.java:272` | **Rebuild dupla-conta visitas no `ActivityNode`** (sobrevive ao rebuild com a contagem viva + replay do histórico, nunca resetado, cumulativo e superlinear). Vicia 4 pontos de decisão: `doABA` ("never move to hot activity"), backtrack para activity mais fria (e mascara o tiebreaker MOP de 612-614, que exige igualdade exata), `collectTrivialActivities`, gate de fuzzing. Direção do viés: **activities refinadas (telas complexas, tipicamente as com MOP) ficam artificialmente quentes e são despriorizadas** | **alta**/conf `[VERIF]` | UI + MOP-C | NOVO |
| M3 | `Model.java:272` | Rebuild marca visitado só o **source** de cada transição: estados target-only renascem unvisited com in-transitions, quebrando o invariante que `checkAndRefreshNewState` transforma em RuntimeException não tratada | media/conf | UI | NOVO |
| M4 | `Graph.java:1307` | `Graph.contains()` lança RuntimeException (em vez de false) para estado recriado com a mesma chave após `graph.remove` fora de rebuild | media/susp | G | NOVO |
| M5 | `Graph.java:456` | `graphId` de aresta usa `edges.size()` como sequencial: após remoções, IDs reutilizados/duplicados no trace/dot | baixa/conf | G | NOVO |
| M6 | `Model.java:87` | `resolveModelAction` no tearDown pode lançar IllegalStateException (Names renomeados in-place) e truncar `action-history.log` | baixa/susp | G | NOVO |
| M7 | `XPathActionController.java:108` | `State.getAction` lança IllegalStateException para widget fora do estado abstrato; só XPathExpressionException é capturada — sobe até o loop de eventos (K05) | baixa/conf | G | NOVO |
| M8 | `Graph.java:1239` | NPE latente em `Graph.remove` (`actions.get(target).isEmpty()` sem null-check pós `removeFromMapSet`) | obs/conf | G | NOVO |
| M9 | `Graph.java:473` | `printStatistics()` a cada `addStateTransition` com verbose: O(atividades) linhas de log por ação — custo de throughput somado a K58 | obs/conf | UI | NOVO |
| M10 | `Graph.java:1287` | **K07 corrigido em impacto**: os self-assignments de fv/lv existem, mas os timestamps ficam corretos (reparados por `markVisited` na re-adição cronológica). O dano persistente do rebuild é o **contador** (M1), não os timestamps | baixa/conf | G | refina K07 |

### 1.4 `ape/tree/` + `ape/events/` — captura e execução

| ID | Onde | Achado | Sev | Métrica | Status |
|---|---|---|---|---|---|
| T1 | `GUITreeNode.java:555` | **`clearChildren` itera NodeList viva do DOM com `i++` e remove só metade dos filhos**: `checkAndRemoveWebView` (WebView >64 descendentes, default) deixa "nós fantasma" no documento — o naming percorre o DOM (não a árvore lógica), nomeia-os e eles viram widgets/ações reais do State com clickable preservado. O agente gasta ações em conteúdo que o filtro tentou descartar; modelo diverge da árvore lógica | **alta**/conf `[VERIF]` | UI | NOVO |
| T2 | `GUITreeBuilder.java:582` | `fillNode` **nunca captura `isPassword()`** (`setIsPassword` tem zero call sites): a detecção de senha prioridade-1 do InputValueGenerator é código morto; resta o fallback por keyword (frágil sob ofuscação). Atenuante verificado: o caminho gh13 T1.3 (`fuzzInputTyped`) detecta senha via inputType/hint estáticos em apps não-ofuscados | media/conf `[VERIF]` (rebaixado) | UI | NOVO |
| T3 | `GUITreeNode.java:325` | `setText` não sincroniza `@text` no DOM enquanto `computeAndSetImageText` (default on) muta o texto **após** a criação do Element: XPath de refinamento `[@text="#hash"]` casa no documento reconstruído mas não no vivo → refinamento silenciosamente inerte em árvores recém-capturadas + não-determinismo espúrio (herdado do upstream ETH) | media/conf `[VERIF]` (rebaixado) | G | NOVO |
| T4 | `MonkeySourceApe.java:359` | Fallback de `generateClickEventAt`: bounds sem interseção com a área visível → **clique no centro da TELA**, registrado no modelo como a ação original (arestas falsas, cobertura creditada errada). **Verificado empiricamente em traces reais**: 260 ocorrências em 17/1513 runs do baseline_v2; caso extremo imagepipe ~13% dos steps | media/conf `[VERIF]` (rebaixado; prevalência ~1,1% dos runs) | UI | NOVO |
| T5 | `GUITreeNode.java:199` | **Duas heurísticas para "é EditText"**: o gate real de injeção de texto usa igualdade exata com `android.widget.EditText`, enquanto `GUITreeBuilder.editTextWidgets` reconhece 4 classes — `AutoCompleteTextView` (caixas de busca) etc. nunca recebem texto | media/conf | UI | NOVO |
| T6 | `ApeDragEvent.java:76` | `toJSONObject` grava `float[]` cru (serializa `[F@hash`): replay de FuzzAction com drag quebra com JSONException | media/conf | G | NOVO |
| T7 | `GUITreeBuilder.java:465` | `checkAndRemoveWebView` conta **todos** os nós onde o original contava só acionáveis (comentário preserva a intenção: `// count(node, actionNodeFilter)`): com default, WebViews reais quase sempre excedem 64 → conteúdo web descartado; o default "manter webviews" é ilusório. Em conjunto com T1: descartado *pela metade* | media/susp | UI | NOVO |
| T8 | `GUITreeBuilder.java:475` | Caminho de reconstrução por XML quebrado 3×: `bounds` nunca serializado (NPE no load), regex rejeita coordenadas negativas, parâmetro index ignorado — os step-N.xml salvos **não são recarregáveis** | baixa/conf | G | NOVO |
| T9 | `GUITree.java:183` | `getCountOfTargetNodes(String)` faz `binarySearch(Name[], String)` — ClassCastException garantida se algum dia usado (hoje morto) | baixa/conf | G | NOVO |
| T10 | `MonkeySourceApe.java:376` | `ClickPoint` RIGHT/BOTTOM/etc. usam `Math.min` onde deveria ser `Math.max` — clicam na borda **oposta** (latente: só CENTER/RANDOM/TOP_LEFT em uso) | baixa/conf | G | NOVO |
| T11 | `ApeFuzzer.java:190` | K33/K34 reconfirmados + novo: mesmo restaurando o `events.add`, a validação do construtor (`<4`) e os 2 slots null do array precisam de correção; ~15% das iterações de fuzz (3/20 slots) não produzem evento | media/conf | G | K33/K34 ampliado |
| T12 | `GUITree.java:284` | K03 reconfirmado presente (binarySearch `== -1`) | media/conf | G | K03 |
| T13 | `GUITreeNode.java:100` | `getIndexPath`: memoização nunca armazena + zero chamadores | obs/conf | G | NOVO |

### 1.5 `ape/utils/`

| ID | Onde | Achado | Sev | Métrica | Status |
|---|---|---|---|---|---|
| U1 | `StringCache.java:108` | `nextString()` sorteia `nextInt(size)` **antes** do check de lista vazia → IllegalArgumentException no caminho de input (categoria GENERIC) quando nenhum texto foi cacheado ainda | media/conf | UI | NOVO |
| U2 | `RandomHelper.java:27` | RandomHelper usa **ThreadLocalRandom não-semeável** em 34 call sites de decisão (incl. a roleta `randomPickWithPriority` e `toss(inputRate)`), enquanto `egreedy()` usa o Random semeado do Monkey: a seed `-s` é ignorada pela maior parte do agente — **nenhum run é reproduzível** e o RNG é misto | media/conf | G | NOVO |
| U3 | `UICoverageTracker.java:260` | Rollup por Activity (fix gh15 A-4/K29) é **write-only**: `getActivityCoverageGap`/`activityRollup`/`getTotalElements`/`getTotalInteractions` têm zero callers de produção — o invariante "eviction não perde cobertura" vale só para um mapa que ninguém lê; state evicted revisitado volta a gap 1.0 (coverage boost re-dispara em widgets já testados) | media/conf | UI | NOVO |
| U4 | `ApeAgent.java:208` | Typed input (T1.3) usa lookup **exato** sem containment e deriva a activity por heurística diferente do scorer (`getTopActivityClassName` vs `newState.getActivity()`): dado K53 (id exato quase nunca casa; +500 é 100% resgatado por containment), o typed input é quase inerte | media/conf | MOP-C | NOVO |
| U5 | `InputValueGenerator.java:141` | `matchKeywords` substring ingênuo: 'account'→NUMBER (via 'count'), 'security…'→URL (via 'uri'), '…tel…'→PHONE — input errado em formulários de login/cadastro | baixa/conf | UI | NOVO |
| U6 | `ActivityBudgetTracker.java:27` | Budget por activity congelado no primeiro registro (dialog com 2-3 ações → budget ~60 para sempre) | baixa/conf | UI | NOVO |
| U7 | `Config.java:226` | `getInteger`/`getLong`/`getDouble` engolem NumberFormatException silenciosamente (braço miscalibrado roda sem warning); defaults não-String no Properties | baixa/conf | G | NOVO |
| U8 | `SystemBroadcastCatalog.java:137` | `parseEntry` descarta ações sem extras e duplicatas (~120 de 187 entries retidos); hoje inócuo | obs/conf | MOP-V | NOVO |
| U9 | `Logger.java:22` | `debug` hardcoded false (não vem de Config); sem timestamp próprio — correlação temporal do trace depende do wrapper externo | obs/conf | G | NOVO |
| U10 | `Config.java:40` | K58 reconfirmado: `takeScreenshotForEveryStep`/`saveGUITreeToXmlEveryStep` seguem TRUE por default (custo 20–40% de throughput) | media/conf | UI | K58 |
| U11 | — | Fixes gh15 **verificados presentes** no master: K22 (fall-through +100), K25 (normalizeEventType), K26 (chave xpath\|TYPE), K29-LRU, K09 (componentPercentage 0.0). K02/K20/K37 reconfirmados presentes | — | — | verificação |

### 1.6 Raiz `com.android.commands.monkey` + `ape/` raiz

| ID | Onde | Achado | Sev | Métrica | Status |
|---|---|---|---|---|---|
| R1 | `MonkeySourceApe.java:1190` | **`waitForActivity` sem timeout**: se a activity nunca chega ao topo (crash-no-launch, trampoline, race com GrantPermissionsActivity dentro da janela de 2000ms → `stopPackages` remove a task), cada `getNextEvent` enfileira só um throttle de 100ms — sem contador, sem desistência, sem relaunch (o branch de recuperação exige `!waitForActivity`). **Um episódio consome o run inteiro com 0 ações**; candidato a explicar parte dos runs ≤2-states (K57) | **alta**/conf `[VERIF]` | UI | NOVO |
| R2 | `ApeAgent.java:348` | ~~totalBadStates>100 encerra a corrida cedo~~ — **REFUTADO** pela verificação adversarial: todo State contém MODEL_BACK/MENU incondicionalmente válidos, logo `BadStateException` é efetivamente inalcançável no fluxo SATA; 310 execuções reais de 600s têm **zero** ocorrências. Permanece como landmine latente (severidade baixa) | baixa `[VERIF-REFUT]` | UI | NOVO (refutado) |
| R3 | `AndroidDevice.java:665` + SataAgent | `getFocusedStack` retorna null (ou lança NPE interna se TASK_PATTERN casa sem STACK_PATTERN, formato API 28+) e os 2 callers em SataAgent desreferenciam sem checagem — NPE mata a corrida | media/susp | G | NOVO |
| R4 | `Monkey.java:780` | **`tearDown` não está em finally**: qualquer RuntimeException do caminho quente (K03/K05/K06/R3…) sai via `exit(1)` perdendo model, coverage dump, sataTimeline e caudas bufferizadas de produce/consume.log — corrompe a medição do que já foi explorado | media/conf | G | NOVO |
| R5 | `Monkey.java:1396` | SecurityException em `MonkeyActivityEvent` (todo EVENT_START/RESTART) encerra a corrida mesmo em `--running-minutes`; aperv-tool não passa `--ignore-security-exceptions` | media/susp | UI | NOVO |
| R6 | `MonkeySourceApe.java:902` | `checkPackage` é dead code: topComp (tasks) e árvore de acessibilidade (janela focada) nunca são validados entre si — GUITree de overlay pode ser atribuída à activity errada no modelo | media/susp | UI | NOVO |
| R7 | `MonkeySourceApe.java:1239` | Guarda "Input only once" de `doInput` loga o aviso mas **não retorna** — dedup prometida não acontece | baixa/conf | G | NOVO |
| R8 | `MonkeySourceApe.java:180` | `mImageWriters` alocado com `Config.imageWriterCount` mas inicializado com loop hardcoded `i<3`: flag é dead config; qualquer valor ≠3 crasha (startup ou NPE tardio) | baixa/conf | G | NOVO |
| R9 | `Monkey.java:1317` | `mMonitorNativeCrashes` zerado na 1ª iteração (divergência do AOSP): checagem one-shot | baixa/conf | G | NOVO |
| R10 | `MonkeySourceApe.java:1105` | `generateThrottleEvent` com `--randomize-throttle` e base==0: módulo por zero (guard checa a variável errada); latente | baixa/conf | G | NOVO |
| R11 | `AndroidDevice.java:239` | `getGrantedPermissions` (nome enganoso; retorna requested) devolve null em RemoteException → `clearPackage`/grant recusam silenciosamente o pacote-alvo; CLEAN_RESTART degrada | baixa/susp | G | NOVO |
| R12 | `Monkey.java:399` | `activityStarting` desreferencia `intent.getComponent()` sem null-check em callback binder; falha do controller pode resetar `mController` no AMS → filtro de pacote silenciosamente desabilitado | obs/susp | G | NOVO |
| R13 | raiz `ape/` | Cluster de código morto: `OnlyAddedUnsaturatedActionFilter` (comentário invertido), `TrivialStateException`/`NoValidActionException` nunca lançadas, `onLostFocused`/`lostFocusedCounter`/`checkNativeApp` sem chamadores | obs/conf | G | NOVO |

**Saudável (verificado):** ponte Agent→fila íntegra e single-threaded (produce/consume/drop simétricos); crash/ANR em modo contínuo corretos (não encerram o experimento); contadores GSTG resetam corretamente; `adjustActionsByGUITree` faz resetBoosts+setPriority para todas as ações antes de qualquer continue; roleta `randomPickWithPriority` sem off-by-one; lattice de naming consistente; dedup de arestas por (source,action,target) é by-design sem perda; re-resolução de ações no rebuild usa o XPathName pós-renomeação correto.

### 1.7 `ape/llm/` + `reducer/` (varredura rápida, baixa prioridade)

- L1 `ScreenshotCapture.java:68` (media/susp, UI): fallback `androidx.test` é código morto comprovado (jar sem androidx) e a assinatura `SurfaceControl.screenshot(Rect,int,int,int)` não existe em API 29+ — em Q o braço LLM degradaria silenciosamente para SATA via breaker.
- L2 `SglangClient.java:151` (media/susp, UI): `llmTimeoutMs` é read-timeout por operação, não deadline total — servidor gotejante bloqueia o loop single-threaded por N×15s (agrava K30).
- L3 `ToolCallParser.java:129` (baixa/susp): `fixMalformedJson`/`findMatchingBrace` sem awareness de literais de string — podem corromper o argumento `text` ou truncar o JSON.
- L4 `LlmRouter.java:406` (baixa/conf): catch-all de `selectAction` é o único caminho de falha que não penaliza o breaker (mesma classe do K31 pré-fix).
- L5 `Reducer.java:140` (baixa/conf): `begin` nunca avança no loop de crashes múltiplos — mas `reducer/` **não é compilado pelo Maven nem entra no ape-rv.jar** (verificado), impacto restrito.
- Saudável: contrato "nunca lança" do LlmRouter cumprido; K31 fix presente; circuit breaker correto; `shouldRouteRandom` não consome o RNG quando `llmPercentage=0` (não perturba braços sem LLM).

---

## 2. Frente 2 — Schema `<apk>.json` ↔ parser

Corpus inspecionado: fixture `test-apks/cryptoapp.apk.json` + levantamento recursivo de chaves em 169 JSONs legados (`rvsec/rv-android/data/apks/`) + 12 exemplares únicos novos (`results/*/instrumented_apks/`).

**Tabela campo → status no parser (schema novo/gh60):**

| Campo JSON | Status |
|---|---|
| `package`, `mainActivity` | consumido só p/ sanity check T1.7 — **morto no master** (load 1-arg, A5) |
| `complete` | consumido (sentinela); **ausente em 169/169 legados e 7/12 novos → arquivo inteiro rejeitado** |
| `reachability[].methods.{signature,reachesTarget,directlyReachesTarget}` | consumido; `reachable`/`name`/`componentType`/`isMain` guardados, não usados no scoring |
| `reachesMop`/`directlyReachesMop`/`mopMethods` (vocabulário legado) | ignorados (por design; sentinela rejeita antes) |
| `windows[].{id,type,name,isMain}`; `widgets[].{idName,type,text,hint,inputType,…}` | consumidos; `widgets[].id` guardado, nunca usado p/ matching; campos novos (hint/inputType/…) presentes só em 3/12 exemplares — ausência degrada graciosamente |
| `listeners[].{eventType,handler}` | consumido; `handlerReachesTarget`/`handlerDirectlyReachesTarget` **emitidos 0× em todo o corpus** (confirma K14/K16 — caminho D8 dormant) |
| `transitions[].*` | consumido; só `type=='click'` alimenta WTG (INV-WTG-01 por design; corpus tem 28 `select`, 4 `touch`, 2 `item_click`, 2 `editor_action` fora do steering) |
| `components.*` | consumido integralmente; campos ausentes nos legados → defaults graciosos |

Nenhum campo do schema novo é ignorado silenciosamente. Achados:

| ID | Onde | Achado | Sev | Métrica | Status |
|---|---|---|---|---|---|
| S1 | `MopData.java:307` | **Janelas DIALOG indexadas pela classe do dialog** (`android.app.AlertDialog`, `BottomSheetDialog` — verificado nos JSONs reais), chave que nunca casa com `newState.getActivity()` (que permanece a activity hospedeira com o dialog aberto): flags MOP de dialogs **estruturalmente inalcançáveis** para +500/+300 e `activityHasMop`. Impacto no corpus carregável (gh60): 5/169 apps, 86 widgets flagged (filemanager 44, medtimer 22, litube 9). Mitigação parcial: o boost WTG ainda dá +200 ao widget que **abre** o dialog | media/conf `[VERIF]` (rebaixado) | MOP-C | NOVO |
| S2 | `MopData.java:314` | **`idName=""` é armazenado como chave e casa com todo widget de runtime sem resource-id** (GUITreeBuilder devolve `""` para nós sem id): canal de match espúrio nos dois sentidos — flagged-com-"" clobberado (6/6 nos exemplares novos: duress, litube, keepitup) ou +500 espúrio para todo nó sem id via containment. **Guardas inconsistentes**: `scoreWtg`/PromptBuilder tratam `""` como no-match; `MopScorer.score` e `ApeAgent.generateInputText` fazem o lookup — duas partes derivando a mesma chave com heurísticas diferentes. 21/51 widgets do cryptoapp têm `idName==""` | **alta**/conf `[VERIF]` | MOP-C | NOVO |
| S3 | `MopScorer.java:139` | Heurística de Spinner: consumer pergunta `itemSelected`, producer emite `select` (corpus: `select`:27, `itemSelected`:0) — a normalização K25 não reconcilia (`itemselected` ≠ `select`); entrada per-eventType nunca casa | media/conf | MOP-C | NOVO |
| S4 | `MopData.java:246` | Uma única JSONException em qualquer elemento de qualquer passe descarta o **arquivo inteiro** (return null), sem degradação por elemento | media/susp | MOP-C | NOVO |
| S5 | `StatefulAgent.java:162` | Toda falha de load colapsa em null + 1 warn: **o braço `sata_mop` roda silenciosamente como SATA puro** — exatamente o mecanismo que escondeu o K01 por um experimento inteiro. Não há fail-fast quando `mopDataPath` foi explicitamente configurado | media/conf | G (validade experimental) | NOVO |
| S6 | `MopData.java:42` | Javadoc D7 ("*Target só aparece onde o JSON é lido") é **falso**: vocabulário Target vaza para `ComponentInfo` (campos públicos `reachesTarget`/`targetMethods`), `StatefulAgent:1011-1119` e logs `[APE-RV]` | obs/conf | G | NOVO |
| S7 | `MopData.java:303` | Producer emite janelas com id duplicado (duress: 6× id=3349) e `windowsById` faz last-write-wins sem log — transitions podem ligar à instância errada | obs/conf | MOP-C | NOVO |
| S8 | corpus | 169/169 exemplares de `data/apks` são schema legado sem `complete` → parser atual rejeita 100% em bloco (o pipeline de experimento usa os JSONs frescos; risco é só para reanálises offline) | baixa/conf | MOP-C | NOVO |

**Identidade de widget (pergunta 4 do prompt):** a ponte é `idName` estático ↔ `extractShortId(getViewIdResourceName())` de runtime. Ela falha silenciosamente em: (a) nós sem id (→ `""`, S2); (b) dialogs (S1); (c) ofuscação R8 (resource names removidos — K15/K53, produtor); (d) Compose (sem resourceID por construção — K17). O containment ±2 do gh15 resgata (c) parcialmente, mas 100% dos +500 observados vêm de containment (K53), i.e., o match exato é a exceção, não a regra.

**Robustez:** parser é 100% opt-based (sem NPE em campo ausente; 170/170 arquivos parseiam sem exceção); sentinela e rejeição de vocabulário legado funcionam. As fragilidades reais são semânticas (S1-S3), não sintáticas.

---

## 3. Frente 3 — Suficiência do log `.trace`

Emissão: `Logger` → stdout (prefixo `[APE]`, PrintStream sincronizado, sem interleaving intra-linha) + `produce/consume.log` (clockTime, agentTimestamp, inputText por ação) + `action-history.log`/`sataGraph.*` no tearDown.

**Q1 — candidatas não-escolhidas:** SÃO logadas a cada passo (`printStrategy`→`printActions`, com `[P=total]` **pós-boost** — correção ao K60), mas **sem decomposição mop/wtg/cov/menu por candidata, sem visitedCount e sem step-id**: o flip de argmax **não é reconstruível** (não dá para subtrair o boost e recomputar o ranking contrafactual).

**Q2 — causalidade:** **Não.** `decision_source` só assume SATA/LLM/Budget — os valores MOP/Coverage/WTG/Menu/Fuzz/Component do enum **nunca são atribuídos no master** (O3, verificado). A sub-estratégia (USE_BUFFER/EARLY_STAGE/EPSILON_GREEDY…) — essencial para distinguir "boost participou da roleta" de "boost irrelevante (buffer/backtrack/short-circuit)" — só existe na linha solta `Select action %s by strategy %s`, sem step-id (join posicional pelo bracket `begin/end step [N]`, determinístico na prática mas frágil e não documentado). O índice da roleta não é logado: "escolhida por causa do boost" é **inexprimível por construção**.

**Q3 — cobertura intra-tela:** reconstituível apenas por stitching frágil (`Create state [W=][A=]` + candidatas + linha condicional de Coverage boost com denominadores **inconsistentes** — gap usa widgets registrados xpath|TYPE incl. BACK/MENU; boosted/total usa alvos target). Sem dump final no master (getters implementados com zero call sites — U3).

**Q4 — violações MOP:** o aperv **não loga nada** sobre violações; a cadeia JavaMOP→logcat RVSEC/RVSEC-COV→contagem é externa ao repo (limitação declarada). Do lado do trace falta wall-clock no `[APE-STEP]` — atribuir uma violação (timestamp logcat) a um passo exige join de 3 arquivos via produce.log. Riscos externos típicos (não verificáveis aqui): buffer do logcat, rate-limit do chatty ("identical N lines" **colapsa violações repetidas**), truncamento ~4KB.

**Achados de observabilidade** (lacuna = achado):

| ID | Onde | Achado | Sev | Métrica |
|---|---|---|---|---|
| O1 | `ApeAgent.java:325` | **[APE-STEP] emitido ANTES de checkRestart**: linhas-fantasma de ações substituídas por EVENT_RESTART, com cobertura e visited-count já contabilizados (A3) — análises que contam [APE-STEP] como ação executada ficam infladas; detecção pós-hoc exige cruzar produce.log | media/conf `[VERIF]` | UI |
| O2 | `Model.java:467` | `Create state ...` é emitida a **cada passo** (novo ou existente): parser que a trate como criação superconta estados; contagem correta exige dedup por graphId (não documentado) | media/conf | G |
| O3 | `StatefulAgent.java:1267` | decision_source ∈ {SATA,LLM,Budget} apenas (ver Q2) — scripts que filtrem `decision_source=MOP` contam **zero silenciosamente** | **alta**/conf `[VERIF]` | MOP-C |
| O4 | `StatefulAgent.java:1452` | Linhas de Coverage/WTG boost condicionais a `boosted>0` (viés de sobrevivência: tela 100% coberta emite nada) + denominadores inconsistentes; a linha MOP boost, em contraste, é incondicional (saudável) | media/conf | UI |
| O5 | `StatefulAgent.java:1266` | [APE-STEP] sem wall-clock (ver Q4) | media/conf | MOP-V |
| O6 | `MonkeySourceApe.java:404` | Clique com "Invalid bounds" vira no-op silencioso já contabilizado (evento não enfileirado; ação consta como executada no [APE-STEP]/histórico/cobertura) | baixa/conf | UI |
| O7 | `MonkeySourceApe.java:1246` | Decisão de digitar (checkInput) ocorre após o [APE-STEP] e `Input text is...` cai **fora** do bracket do passo — associação posicional | baixa/conf | G |
| O8 | `SataAgent.java:322` | Budget exhausted sem telemetria no fallthrough (trivial==null): não dá para saber quantos passos rodaram em regime de budget estourado | baixa/conf | G |

**Proposta MÍNIMA de instrumentação** (5 mudanças, custo ~zero no caminho quente):
1. `[APE-STEP]`: acrescentar `clock=%d` e `strategy=%s` (SataEventType, setado junto do decision_source) — `StatefulAgent:1266`.
2. Substituição por restart: 1 linha `[APE-STEP-SUBST] step=N replaced_by=%s` em `ApeAgent.checkRestart:249/259` (fecha O1 no log; o dano de estado A3 exige fix separado).
3. Candidatas: incluir `[VC=%d][MOP=%d][WTG=%d][COV=%d][MENU=%d]` em `ModelAction.resolvedInfo` (linha 140) — reaproveita `printActions`, zero linhas novas; fecha Q1 (flip de argmax passa a ser recomputável).
4. `MopData.load` (linha 240): logar `package=`, `parsedWidgets=` (pré-colisão), `droppedNullId=`, `collided=` e **passar expectedPackage real no load** (fecha S5/A5 e dá validação pós-hoc do pareamento JSON↔APK).
5. tearDown: dump `[APE-COV-FINAL] activity=%s gap=%.2f widgets=%d` via `getActivityCoverageGap` já existente + `Create state` com `new=true|false` (`Model:467`) — fecha Q3/O2.

---

## 4. Frente 4 — Avaliação da change em andamento (`mop-fairtest`)

**`mvn test` no worktree: 381 run / 0 failures / 0 errors / 15 skipped** (bate com tasks 6.6); `mvn package` OK.

### Veredicto por alegação

| # | Alegação | Veredicto |
|---|---|---|
| 1 | Parser fidelity (K02/K20) | **CORRIGE.** Retém o flag mais forte por colisão (mopRank, ordem-independente), re-chaveia WTG por base activity nas duas pontas, precompute do menu re-apontado; testes pairwise reais nos 2 sentidos. Resíduos: colisão flagged-vs-flagged de mesmo rank ainda perde um widget; comentário obsoleto "+100 fallback" (C12) |
| 2 | Boost discriminativo | **PARCIAL.** Remoção do +100 completa e correta (K10/K22). Mas o short-circuit greedy é **sombreado pelo EARLY_STAGE** (C3) e queimável por restart (C4) |
| 3 | decision_source | **PARCIAL.** Implementado como largest-boost com tie MOP>WTG>Menu>Coverage, honestamente documentado como não-contrafactual. Mas sub-ramos path-based e short-circuits Back/Menu mislabelam (C6); formBoost invisível (C7); zero teste unitário (era lógica pura host-testável) |
| 4 | Dump UICoverageTracker | **CORRIGE** (K59 fechado). Read-only verificado. Omissões: estados LRU-evictados e activityRollup fora do dump |
| 5 | FormCompletion | **NÃO CORRIGE / INTRODUZ REGRESSÃO** (C1, C2, C5, C9) |

### Achados na change

| ID | Onde (worktree) | Achado | Sev | Métrica | Status |
|---|---|---|---|---|---|
| C1 | `StatefulAgent.java:184` | **BLOQUEANTE: o fill determinístico do form-completion é código morto.** O pipeline é `checkInput(checkFuzzing(checkRestart(updateStateInternal())))`; `updateStateInternal` chama `moveForward()` que **anula `newState`** (`doMoveForward`, :1195) em todos os caminhos de retorno **antes** de `checkInput` rodar — o override `inFormCompletionContext()` lê `newState==null` e retorna sempre false. O ramo "preencher deterministicamente" NUNCA dispara; o `toss(inputRate=0.8)` legado vale em 100% dos passos. O estado correto estaria em `currentState`. INV-FORM-03/INV-INP-04 é entregue como código inalcançável **sem nenhum teste cobrindo o caminho** (FormCompletionTest só testa a utility pura) | **bloqueante**/conf `[VERIF]` | MOP-C | NOVO |
| C2 | `SataAgent.java:497` | **Guard INV-FORM-06 é derrotado pelos próprios caminhos de seleção**: (a) EARLY_STAGE roda **antes** do epsilon-greedy e roleta o submit com prioridade ~752 (32 base + 20 unvisited + 500 mop + 100 W_SUBMIT + 100 cov) vs ~302 por campo — P(submit vazio) ≈ 55–71% na 1ª visita; (b) com o submit excluído do short-circuit, `egreedy→greedyPickLeastVisited` desempata vc=0 por **maior** priority e escolhe deterministicamente o submit excluído. O cenário do spec ("submit not clicked before fields are filled … or any other selection path") não é garantido por nenhum caminho. Agravante: W_SUBMIT é aplicado exatamente quando o form está vazio. Após o clique vazio, o submit vira visited e o guard nunca mais se aplica àquele alvo | **alta**/conf `[VERIF ×2 agentes]` | MOP-V | NOVO (raiz do já-documentado "#1×#2 submit-before-fill") |
| C3 | `SataAgent.java:471-476` | **Short-circuit MOP sombreado pelo EARLY_STAGE**: ações unvisited-by-name são consumidas pela roleta do EARLY_STAGE antes do ramo onde o short-circuit vive; o EPSILON_GREEDY só é alcançado quando já não há unvisited-by-name alcançável. **Quantificado em traces reais (cmpmop, cadeia idêntica)**: EARLY_STAGE=57,6% das decisões, e dentro do EPSILON_GREedy apenas 1,5% das seleções foram UNVISITED → o mecanismo determinístico opera em **<1% das decisões**; K12 (diluição por roleta) persiste no caminho dominante. O design promete "reached deterministically rather than via roulette" — falso no caminho dominante. **Risco concreto de repetir um null não-informativo atribuível a posicionamento do mecanismo, não a MOP em princípio** | **alta**/conf `[VERIF ×2]` | MOP-C | NOVO |
| C4 | `StatefulAgent.java:681` | One-shot do short-circuit queimável por restart: `markVisited` precede `checkRestart` e o short-circuit não chama `checkDisableRestart` (ao contrário do EARLY_STAGE greedy). Fix trivial | media/conf `[VERIF]` (rebaixado; coincidência ~1/200) | MOP-C | NOVO |
| C5 | `FormCompletion.java:51` | Predicado 'unfilled' **nunca converge**: `inputText` é anotação transiente por captura de GUITree (nada copia entre capturas nem deriva de `getText()`) → `hasUnfilledEditText` permanentemente true em qualquer tela com EditText: re-boost sem critério de progresso e **submit excluído do short-circuit para sempre** | alta/conf | MOP-C | K39 confirmado estaticamente |
| C6 | `SataAgent.java:241` | decision_source segue correlacional: EARLY_STAGE inclui sub-ramos path-based (ABA/refillBuffer/global/shortest-path) que não consomem priority; Back/Menu-unvisited dentro do EPSILON_GREEDY são atribuídos por argmax de boost — todo estado novo com MODEL_MENU unvisited e menuBoost=250 sai `decision_source=Menu` embora o boost tenha sido irrelevante | media/conf | G | K42 refinado |
| C7 | `SataAgent.java:244` | formBoost invisível na atribuição (não existe `DecisionSource.Form`): campo escolhido por W_FILL=150 é rotulado Coverage ou SATA — a influência da mudança #5 na seleção não é mensurável pelo [APE-STEP] | media/conf | G | NOVO |
| C8 | `MopData.java:643` | Gateway de OPTIONSMENU sobre-aproximado pós re-chaveação: qualquer aresta click da base activity que alcança MOP qualifica o menu (+250) — desvia passos para menus sem caminho MOP e infla decision_source=Menu (regressão de precisão declarada como deliberada no design) | media/conf | UI | NOVO (parente do já-documentado "W breaks menu gateway") |
| C9 | `FormCompletion.java:83,112` + `GUITreeNode:199` | Heurística de submit: (a) candidato arbitrário entre empates de mopBoost via containment — pode ser um MODEL_SCROLL do container; (b) lone-Button ignora o texto (um único "Cancel"/"Delete" vira submit); (c) Compose/Material sem 'Button' no className → submit=none; (d) `isEditText` exato → **form-completion inteira é inerte em apps AndroidX/Material/Compose** (AppCompatEditText/TextInputEditText não casam) — no corpus de 169 APKs a capability pode ser no-op na maioria | media–alta/conf | MOP-V | K40 + já-documentado (isEditText) |
| C10 | `MopData.java:359` | Premissa do drop de widgets sem id é **factualmente falsa** no spec ("extractShortId never yields '' for a real widget" — ele retorna `""` exatamente para nós sem resourceId, e a chave ERA alcançável): o drop elimina o único caminho widget-level para apps 100% sem id (labnex/duress). Trade-off defensável (o match "" era uniforme/ruidoso — mesmo argumento da remoção do +100), mas decidido sob justificativa errada | media/conf `[VERIF]` (rebaixado) | MOP-C | premissa já apontada em analise_claude_sonnet5; verificação nova |
| C11 | `ApeAgent.java:189` | Cenário "legacy toss preservado" do spec é **insatisfazível**: qualquer EditText unfilled selecionado torna o próprio estado 'form context' (se C1 for corrigido, o toss legado morre por construção — o spec descreve um estado impossível) | media/conf | G | NOVO |
| C12 | vários | Débito de spec/tasks: spec principal `mop-guidance` não sincronizada (mopWeightActivity/+100/INV-MOP-07 ainda mandatórios — K38); INV-MOP-07 double-booked com gh13 arquivado; tasks 2.2/#2 e 3.4/#1 marcadas [x] com testes prometidos **ausentes** (gating unvisited e wiring do guard têm cobertura zero); comentário "+100 fallback substrate" obsoleto (K45); casos de borda ausentes (colisão tripla, idName null, precedência Back/Menu>MOP) — K44 | media/conf | G | K38/K41/K44/K45 |

### Cruzamento com a Frente 3 (a telemetria nova fecha as lacunas?)

- **Q1 (candidatas):** NÃO fechada — sem decomposição por candidata (item 3 da proposta mínima não implementado).
- **Q2 (causalidade):** PARCIAL — atribuição existe, mas mislabeling nos sub-ramos (C6), sem `strategy=`/`clock=`, e a substituição por restart (O1) segue não logada e contaminando a linha.
- **Q3 (intra-tela):** MAJORITARIAMENTE fechada pelo dump UICOV (#4) — falta rollup por activity e evictados.
- **Q4 (violações MOP):** NÃO endereçada.
- Itens 1, 2, 3 e 5 da proposta mínima: não implementados; item 4: parcial (só `droppedFlaggedNoId`; load segue 1-arg).

**Pronta para o experimento de validação (§7.5)?** **Não.** Bloqueadores antes de qualquer fair-test: C1 (metade central da mudança #5 é inalcançável), C2+C3 (o mecanismo-bandeira da mudança #2 e o guard da #5 operam fora do caminho de seleção dominante — o experimento mediria de novo um tratamento ~inerte, repetindo o padrão do null do cmpmop), C5 (convergência), e S5/A5 (sem fail-fast/echo de load, um erro de pareamento JSON×APK repetiria o K01 invisivelmente). As partes #1 e #4 estão sólidas e podem ir adiante.

---

## 5. Mapeamento explícito ao objetivo

Cada achado já carrega a métrica na coluna correspondente. Síntese dos que afetam **diretamente** cada métrica:

- **(a) Cobertura de UI:** M1, M2 (backtracking vicia contra telas complexas/refinadas), R1 (runs de 0 ações), T1/T7 (WebView), A3/O1 (widgets falsos-visitados), T4 (cliques no centro), N3/N4 (inflação de estados consome maxStatesPerActivity), U1, U10/K58, A2 (sem fuga de saturação), T5 (busca sem texto).
- **(b) Cobertura MOP:** C1, C2, C3, C5 (change), S1, S2, S3 (ponte estática↔runtime), A1 (triggering), U4 (typed input inerte), A5/S5 (validade do braço), M2 (despriorização de telas MOP).
- **(c) Violações MOP:** C2/C9 (submit-before-fill impede o fluxo completo que dispara monitores), O5/Q4 (atribuição e possível colapso de violações repetidas no chatty — externo), U8 (menor).
- **Mecanismos que limitam artificialmente o que é contado** (pergunta §6.2): O1/A3 (interação creditada sem execução), O2 (superconta de estados por parser ingênuo), O4 (viés de sobrevivência nas linhas de boost), U3 (eviction re-zera cobertura observável), O6 (no-op contabilizado), M1/M2 (contadores inflados suprimem re-exploração), dedup do chatty no logcat (externo, não verificado).

---

## 6. Priorização (impacto × esforço)

### (i) Bloqueadores de validade de experimento — corrigir primeiro

| # | Item | Fix | Experimento mínimo de validação |
|---|---|---|---|
| 1 | **C1** fill determinístico morto | ler `currentState` (ou capturar o contexto antes de `moveForward`) em `inFormCompletionContext`; teste host de `checkInput` com o pipeline real | teste unitário + run curto contando `Input text` dentro de form context |
| 2 | **C3** short-circuit MOP sombreado (+ **C2** guard furado) | aplicar exclusão do submit e preferência MOP **na roleta do EARLY_STAGE** (`getGreedyActions`/`randomPickWithPriority`) ou mover o short-circuit para antes do EARLY_STAGE | grep de `strategy=`×`decision_source` num run: fração de alvos MOP unvisited consumidos por EARLY_STAGE deve cair de ~99% |
| 3 | **C5** convergência do unfilled | derivar 'filled' de `getText()` da captura corrente ou persistir por identidade de widget (xpath) no nível do State | teste host: duas capturas consecutivas, campo digitado não volta a unfilled |
| 4 | **A5/S5** load 1-arg + falha silenciosa | passar package/mainActivity de runtime no load; logar `package=/parsedWidgets=/collided=`; opcional fail-fast configurável quando `mopDataPath` setado e load falha | inspeção de 1 linha do trace de qualquer run |
| 5 | **M1/M2** inflação de contadores no rebuild | remover o `visitedCount++` incondicional de `rebuildHistory` (ou resetar antes); resetar/recontar ActivityNode no rebuild | teste host: rebuild 2× e assert visitedCount estável; comparar histograma de restarts/backtracks antes/depois num run |
| 6 | **O1/O3 + proposta mínima §3** | as 5 mudanças de instrumentação (inclui `[APE-STEP-SUBST]`, `strategy=`, decomposição por candidata) | sem elas, o pós-hoc do próprio fair-test repete inferências não-causais |

### (ii) Débito técnico geral (independente de MOP)

Por impacto: **R1** waitForActivity (timeout + relaunch — runs de 0 ações), **R4** tearDown em finally (preserva artefatos de medição), **U2** RNG semeável (reprodutibilidade de qualquer experimento), **T1/T7** WebView (metade-fantasma + threshold sobre nós totais), **U1** StringCache crash, **T2** capturar `isPassword` + **T5/C9d** unificar/ampliar `isEditText` (sufixo/`instanceof`-like), **T4/O6** fallback de clique → descartar a ação em vez de clicar no centro, **A1** dispatchTrigger usar o `package` do JSON, **R3** null-check de getFocusedStack, **R5** `--ignore-security-exceptions` no aperv-tool, **S3** aceitar `select` na heurística de Spinner, **S4** degradação por elemento no parser, **A4** checkFuzzing overload, **N2** guarda maxGUITreesPerState, **N3** religar ignoreEmpty/ignoreOutOfBounds, **U7** warn em NumberFormatException.

### (iii) Lacunas de observabilidade

A proposta mínima da §3 (5 itens) + O2 (`new=` no Create state), O4 (linhas de boost incondicionais ou com marcador de "nada a boostar"), O8 (telemetria de budget no fallthrough), C7 (`DecisionSource.Form` ou coluna própria). Fora do repo (registrar como requisito no rv-android): verificar rate-limit do chatty/`logcat -b` size para violações repetidas, e join por wall-clock.

### (iv) Propostas novas (ninguém tinha levantado)

1. **Re-chavear janelas DIALOG à activity hospedeira** usando as arestas WTG activity→dialog já presentes no JSON (consumer-side, sem tocar o producer) — desbloqueia os 86 widgets flagged de dialogs (S1).
2. **Política de match para nós sem id**: em vez de dropar (change #1) ou casar espuriamente (master), casar `""` apenas quando `(activity,eventType,className)` for única — recupera labnex/duress sem o ruído uniforme (S2/C10).
3. **Reordenar o pipeline** `checkRestart(updateStateInternal(...))` → decidir restart **antes** de marcar visited/coverage (resolve A3/O1 na raiz, não só no log).
4. **`isDialogState` invertido** (A7): trocar para `!hasGreedyActionForward` e medir efeito no ABA.
5. **Timeout+relaunch em waitForActivity** com contador e `startRandomMainApp` forçado após N ciclos (R1).
6. **Semear RandomHelper** com o `-s` do Monkey (U2) — pré-requisito barato para qualquer estudo de variância entre braços.

---

## 7. Limitações

- **Sem execução em dispositivo/emulador**: os mecanismos foram confirmados por leitura de código e, quando possível, por evidência empírica em traces reais de experimentos passados (`rvsec/rv-android/results/`: baseline_v2 para T4, aperv_precal_macro para R2, shards do cmpmop para C3). Frequências de disparo de N1, N4, T3, T7, M3-M4, R3, R5-R6 não foram medidas.
- **Corpus de JSON**: apenas o fixture cryptoapp é verificável dentro deste repo; os números de S1/S2 sobre duress/litube/labnex vêm dos exemplares do rv-android e do catálogo prévio. O produtor (gator/RvsecAnalysisClient) não foi auditado nesta rodada.
- **Cadeia de violações MOP** (JavaMOP→logcat→contagem) é externa ao repo `ape` — Q4 respondida só do lado do trace; os riscos de chatty/buffer são plausíveis e não verificados.
- **Proveniência upstream**: não diffei contra o APE original da ETH; T3, A9, A12 e parte do débito podem ser herdados (T3 confirmado herdado pela verificação).
- **`openspec validate --strict` não foi executado**; a suite do worktree foi rodada (381/0/0/15) mas as tasks de device (6.2-6.4, 4.2-4.3) seguem abertas por natureza.
- A verificação adversarial cobriu os 20 achados novos bloqueantes/altos; os de severidade média/baixa (maioria do catálogo) foram rastreados pelo auditor original mas não receberam refutador dedicado — as severidades deles podem estar superestimadas na mesma proporção observada nos verificados (7/20 rebaixados).

---

# Anexos — material integral da investigação

Os anexos abaixo trazem, sem compressão, o que cada agente produziu. As tabelas do corpo (§1-§4) são o índice sintético; aqui está o mecanismo completo de cada achado, o raciocínio integral da verificação adversarial e os relatórios por escopo (incluindo o que foi verificado e está saudável). A correspondência com as tabelas é por `arquivo:linha`.


## Anexo A — Detalhamento integral dos 145 achados (por agente)


### A.1 Pacote ape/agent/

**agent-1. dispatchTrigger deriva o package do ComponentName por substring do className, quebrando o trigger de qualquer componente fora do pacote raiz**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1090` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · NOVO

dispatchTrigger faz packageName = className.substring(0, lastDot), mas ComponentName exige o package da APLICACAO (manifest), nao o prefixo Java da classe. Verificado no fixture real test-apks/cryptoapp.apk.json: package=br.unb.cic.cryptoapp, mas componentes vivem em subpacotes (br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity etc.) — o Intent explicito recebe package errado e nao resolve nenhum componente. Broadcast a componente inexistente nao lanca erro, entao a falha e 100% silenciosa (o log '[APE-RV] Triggering...' ainda e emitido). ComponentInfo nao carrega packageName e o `package` do JSON (parseado em MopData.load) nao e propagado. Todo o mecanismo gh11/gh13 T1.4-T1.5 de alcancar MOP em receivers/services/providers so funciona para classes no pacote raiz. Mitigante: componentPercentage default 0.0 (Config.java:178), mas os experimentos gh11 rodaram com 0.05.

**agent-2. checkBackTrack e codigo triplamente morto: zero call sites e, mesmo se chamado, jamais retornaria backtrack**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:246` · severidade: media · confiança: confirmado · métrica: ui · NOVO

grep no repositorio inteiro mostra que checkBackTrack() nao tem NENHUM call site — o mecanismo de escapar de estados saturados via BACK-chain nunca roda (o contador SATURATED_STATE e sempre 0). Alem disso ele e internamente incapaz de funcionar: (a) visited.add(newState) antes do loop + o teste `!visited.contains(state)` (linha 269) checa o no DESENFILEIRADO em vez do target, logo a fila nunca cresce alem de newState (confirma e supera o K08, que era suspeita); (b) ao achar target insaturado o break deixa state==newState, e o guard `doBackTrack && newState != state` (linha 277) e sempre falso. Resultado: sem rota de fuga de saturacao alem dos thresholds de restart — contribui para o desperdicio BACK/MENU e loops (K56/K57).

**agent-3. Acao e marcada como visitada e contada na cobertura ANTES da execucao; checkRestart descarta a acao selecionada em todo restart**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:671` · severidade: media · confiança: confirmado · métrica: ui · NOVO

updateStateInternal chama getGraph().markVisited(action) e moveForward()->_coverageTracker.recordInteraction() no momento da SELECAO. O pipeline em ApeAgent.java:325 (checkInput(checkFuzzing(checkRestart(...)))) permite que checkRestart substitua a acao por EVENT_RESTART (a cada 100-300 passos por threshold, mais restarts por estabilidade GSTG). A acao descartada — tipicamente a acao unvisited de maior valor, escolhida pelo greedy — perde permanentemente o status unvisited (+20 de prioridade, alvo do isActionUnvisitedByName) e o widget fica com interactionCount>0 no UICoverageTracker sem nunca ter sido tocado, suprimindo o coverage boost. A linha [APE-STEP] tambem ja foi logada para a acao fantasma, enviesando telemetria. ~5-10 acoes falsamente visitadas por run de 300s, concentradas exatamente nos momentos de estagnacao.

**agent-4. checkFuzzing(ModelAction) e um overload nunca invocado — a protecao fuzzingActivityVisitThreshold e config morta**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:297` · severidade: media · confiança: confirmado · métrica: ui · NOVO

ApeAgent.updateStateWrapper (linha 325) chama checkFuzzing com tipo estatico Action, que resolve para ApeAgent.checkFuzzing(Action) (identidade). StatefulAgent.checkFuzzing(ModelAction) e um OVERLOAD, nao override, e o grep mostra zero call sites. Consequencia: `this.disableFuzzing = true` para activities com getVisitedCount() < fuzzingActivityVisitThreshold (default 10, Config.java:59) nunca executa — o fuzzing (doFuzzing default on, 2%/passo em MonkeySourceApe:812) dispara tambem em activities recem-descobertas, podendo navega-las para fora antes da exploracao sistematica. disableFuzzing so e setado pelo ReplayAgent. Padrao classico 'flag de Config lida mas sem efeito real'.

**agent-5. MopData.load ainda e chamado na forma 1-arg no unico call site de producao — mopStrictPackageMatch continua inerte no master**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:162` · severidade: media · confiança: confirmado · métrica: geral · CONHECIDO — K24 (mas contradiz o status 'corrigido': o call site de producao segue sem passar o package)

O construtor do StatefulAgent chama MopData.load(Config.mopDataPath), que delega para load(path, null, null); com expectedPackage/expectedMainActivity nulos a checagem T1.7 e pulada (javadoc do proprio load). grep confirma que nenhum outro codigo de producao chama a versao 3-arg. Ou seja, o guard desenhado para pegar skew JSON×APK (exatamente a classe de falha K01 que invalidou o experimento de junho) nao pode disparar, apesar de K24 constar como 'corrigido gh15' — no master a correcao aparentemente criou a API mas nao ligou o call site (o package/mainActivity de runtime nunca sao passados).

**agent-6. ReplayAgent reexecuta qualquer model action sem target como BACK — MODEL_MENU gravado vira MODEL_BACK no replay**

`src/main/java/com/android/commands/monkey/ape/agent/ReplayAgent.java:156` · severidade: media · confiança: confirmado · métrica: geral · NOVO

No selectNewActionNonnull, o ramo `actionType.isModelAction() && !requireTarget()` retorna incondicionalmente newState.getBackAction(). ActionType.java confirma que MODEL_MENU nao requer target, entao um MENU gravado (inclusive os induzidos pelo mopWeightOpenMenu/gateway de OPTIONSMENU) replaya como BACK, divergindo silenciosamente do trace original. Corrompe reproducao de crashes (reducer/delta debugging) e qualquer validacao por replay de sequencias que dependem de menus para alcancar operacoes MOP.

**agent-7. isDialogState contradiz a intencao logada: bloqueia ABA justamente para estados populares que AINDA TEM acoes greedy pendentes**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:291` · severidade: media · confiança: suspeita · métrica: ui · NOVO

isDialogState retorna true quando o estado tem >5 in-edges E hasGreedyActionForward(state)==true (ha acoes nao visitadas nele ou na vizinhanca). doABA entao recusa mover para ele com a mensagem 'Never move to a saturated dialog state' — mas a condicao seleciona estados NAO saturados. Ou a mensagem esta errada ou a condicao deveria ser !hasGreedyActionForward. Como implementado, o ABA (mecanismo central de revisita do SATA) evita voltar exatamente aos hubs com trabalho unvisited pendente, perdendo cobertura intra-tela (coerente com o K54 de telas MOP-bearing piores). Nao consegui diffar contra o APE upstream para confirmar se e heranca intencional.

**agent-8. widgetCount do budget conta ACOES requireTarget, nao widgets — orcamento por activity inflado e inconsistente com o UICoverageTracker**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:664` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

updateStateInternal computa widgetCount iterando newState.getActions() e contando cada acao com requireTarget — um mesmo widget com click+long-click+4 scrolls conta ate 6. O ActivityBudgetTracker recebe budget = base + perWidget*count inflado ~2-6x, tornando isBudgetExhausted (SataAgent:319) quase inatingivel. Alem disso e uma segunda heuristica de 'widget' divergente da usada por UICoverageTracker.widgetId (duas partes derivando a mesma chave de formas diferentes).

**agent-9. Comentario/logica invertidos no passo de prioridade: edge fraca ganha +10 com comentario 'make it weaker'**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1356` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

Em adjustActionsByGUITree, para edges nao-strong com edges.size()>1 o codigo faz `priority += 10; // make it weaker`. Somar prioridade AUMENTA a chance na roleta (randomlyPickAction pondera por priority), o oposto de enfraquecer. Acoes com transicoes flaky/nao-deterministicas sao portanto promovidas. Pode ser heranca do APE upstream com comentario enganoso (talvez a intencao fosse 're-testar a edge fraca'), mas como esta, o sinal contradiz o rotulo e compete com os boosts MOP/coverage na mesma escala (~K11/K49).

**agent-10. Hook LLM de estagnacao usa igualdade exata no midpoint e exige buffer vazio no mesmo passo — perde a janela com frequencia**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:341` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

A condicao `graphStableCounter == graphStableRestartThreshold / 2 && actionBufferSize() == 0` so testa igualdade num unico valor do contador. Como o contador cresce +1 por edge EXISTING e replays de buffer percorrem edges existentes, o midpoint frequentemente ocorre com actionBufferSize()>0, e a igualdade nunca se repete naquela subida (proximo evento e o restart no threshold). O modo llmOnStagnation vira loteria de alinhamento em vez de gatilho confiavel. So afeta o braco LLM (llmUrl != null).

**agent-11. refreshStatesCheckingBlacklist cresce monotonicamente e fica obsoleta apos refinamento do modelo**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:153` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

O Set<State> so recebe add (linha 612) e nunca e limpo — nem em startNewEpisode, nem em onActivityStopped, nem em updateModel. Apos refinamento de naming os objetos State sao substituidos, entao as referencias antigas nunca mais casam (blacklist inefetiva: estados triviais re-checados com ate 5 getRootInActiveWindowSlow, custo de throughput ~K58) e os States antigos ficam retidos, agravando o risco conhecido de OOM por reter GUITrees.

**agent-12. Short-circuit de BACK/MENU unvisited no estagio epsilon-greedy usa isValid() sem checar enabled e preempta qualquer boost MOP**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:414` · severidade: observacao · confiança: confirmado · métrica: ui · CONHECIDO — K56 (a mecanica de codigo correspondente ao desperdicio medido)

selectNewActionEpsilonGreedyRandomly retorna BACK (depois MENU) incondicionalmente se unvisited e valid, antes da roleta ponderada — os boosts MOP/WTG/coverage do passo sao ignorados nesses casos. E o mecanismo de codigo por tras da fatia de orcamento BACK+MENU observada nos traces (K56). Diferente dos demais seletores, usa apenas isValid() em vez de ENABLED_VALID.

**agent-13. DecisionSource MOP/Coverage/WTG/Menu/Component/Fuzz sao valores de enum jamais atribuidos no master**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:224` · severidade: observacao · confiança: confirmado · métrica: geral · CONHECIDO — K21/K60

grep confirma que apenas SATA (logActionSelected), LLM e Budget sao setados. Toda acao da cadeia SATA — mesmo quando o boost MOP foi decisivo na roleta — e atribuida a SATA no [APE-STEP]. Os campos mop=/wtg=/coverage= na mesma linha mitigam parcialmente, mas sem as prioridades das candidatas nao escolhidas (K60) o flip de decisao por MOP segue nao reconstruivel. Coerente com K21, cujo fix gh15 cobriu apenas os early-returns LLM/Budget.

**agent-14. Codigo morto adicional no SataAgent: unsaturatedActionsFilter, weakActionSubsequenceFilter e fillTransition(State[]) sem call sites**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:65` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

Os dois SubsequenceFilters (linhas 65 e 155) e o par fillTransition/fillTransition(State[],boolean,boolean) (linha 441, que le as flags fillTransitionsByHistory e fallbackToGraphTransition) nao tem nenhum chamador fora deles mesmos — heranca do APE upstream. As duas flags de Config associadas sao efetivamente mortas. Sem impacto em runtime, mas induz auditorias e planos a supor mecanismos que nao rodam.

**agent-15. createAgent aceita ape.agentType desconhecido silenciosamente (fallback SataAgent) e nao existe tipo 'ape' apesar do CLAUDE.md**

`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:76` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

Qualquer valor nao reconhecido de ape.agentType (typo, ou 'ape' prometido no CLAUDE.md como 'Phase 2: wire into CLI') cai sem warning no SataAgent — um experimento configurado com braco errado rodaria SATA sem nenhum indicio no log. ApeAgent no codigo e a base abstrata, nao o agente CEGAR descrito na doc (drift de documentacao).


### A.2 Pacote ape/naming/

**naming-1. select(List<Namelet>) trata Collections.binarySearch como se retornasse apenas -1 quando não encontra, aceitando cadeias de namelets com pai ausente**

`src/main/java/com/android/commands/monkey/ape/naming/Naming.java:438` · severidade: media · confiança: suspeita · métrica: ui · NOVO

O teste `binarySearch(...) == -1` só detecta ausência quando o ponto de inserção é 0; para qualquer outro ponto o retorno é -(ip)-1 (ex.: -2, -3) e o código trata o pai como presente, continuando a subir a cadeia e podendo retornar um namelet refinado cujo pai NÃO casou o elemento. Agrava: o comparator usado (depth+exprString) ignora o Namer, então um namelet distinto comparator-igual também conta como 'encontrado'. O caminho é quente (namingInternal→select por nó, a cada naming de árvore). Na prática o disparo é estreito porque a expr do filho normalmente implica a do pai (props do namer filho ⊇ pai), mas em cadeias profundas (depth≥2) pós-refinamento a garantia não é estrutural — quando dispara, o nó recebe Name mais fino que o justificado e o StateKey diverge silenciosamente (fusão/divisão indevida de estados). O correto é `< 0`.

**naming-2. Guarda de maxGUITreesPerState é inalcançável (copy-paste usa an.getStates().size() em vez de state.getGUITrees().size()) — flag vira dead config**

`src/main/java/com/android/commands/monkey/ape/naming/NamingFactory.java:280` · severidade: media · confiança: confirmado · métrica: geral · NOVO

Em refine() (linhas 276-283) e em actionRefinement() (linhas 1176-1183) as duas guardas testam a MESMA expressão `an.getStates().size()`, primeiro contra maxStatesPerActivity (default 10, retorna) e depois contra maxGUITreesPerState (default 20) — a segunda nunca é verdadeira. A mensagem de log ('Already too many GUI trees %d in the state') revela a intenção: limitar GUITrees por estado. Resultado: o limite de árvores por estado nunca é aplicado no refinamento (custo de refinamento cresce com tts ilimitadas; expõe o OOM conhecido), e `ape.maxGUITreesPerState`, documentado no CLAUDE.md como 'state space limit', não tem nenhum uso efetivo no código (grep: só a definição em Config e o import em NamingFactory).

**naming-3. ignoreEmpty/ignoreOutOfBounds (default true) não têm efeito: addNamedNode, único consumidor, é código morto — nós vazios/fora da tela entram no StateKey**

`src/main/java/com/android/commands/monkey/ape/naming/Naming.java:156` · severidade: media · confiança: confirmado · métrica: ui · NOVO

addNamedNode é o único lugar que lê Config.ignoreEmpty/ignoreOutOfBounds, mas tem zero call-sites (namingInternal usa Utils.addToMapMap diretamente, linha 522). Consequência: todo nó do dom — inclusive vazios e fora do root (itens de lista rolados para fora, overlays offscreen) — recebe Name e compõe o vetor widgets do StateKey. Estados passam a ser distinguidos por conteúdo invisível, inflando a contagem de estados por atividade (esgotando o budget maxStatesPerActivity=10 que bloqueia refinamento) e fragmentando a cobertura por estado (mesmo mecanismo que amplifica K27). As flags estão definidas como se ativas por default, mas o comportamento prometido nunca ocorre.

**naming-4. Exceção de EditText no TextNamer é código morto: getAttributeValue (que zeraria @text de EditText) não tem chamadores; texto digitado entra no TextName**

`src/main/java/com/android/commands/monkey/ape/naming/TextNamer.java:110` · severidade: media · confiança: suspeita · métrica: ui · NOVO

getAttributeValue(Element,prop) retorna "" para o texto de EditText (via GUITreeBuilder.isEditText), mas nenhum código o chama; naming() (linha 127-129) usa node.getText() cru. Sob qualquer naming refinado com TEXT, cada string distinta digitada num EditText gera um TextName novo → StateKey novo → estado novo. Com fuzzing de input (fuzzInputTyped/inputRate 0.8) cada digitação pode fabricar um estado, queimando o teto maxStatesPerActivity=10 e distorcendo a métrica de estados/cobertura; também invalida a premissa de convergência do FormCompletion (K39: identidade do widget muda ao digitar). Disparo condicionado a refinamento TEXT ter ocorrido na atividade (base naming usa só TYPE), por isso suspeita.

**naming-5. complementOf() ignora o argumento e retorna sempre o conjunto completo — validação 'Incomplete lattice' do NamerLattice é vácua**

`src/main/java/com/android/commands/monkey/ape/naming/NamerType.java:43` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

O método constrói um EnumSet com TODOS os NamerType.used e nunca remove os membros de `set`; é allOf() com outro nome. Único call-site é a checagem de completude do reticulado em NamerLattice:50-55: `typeToNamer.containsKey(complementOf(...))` vira `containsKey(allOf())`, que é sempre true (topNamer já validado). Qualquer lista futura de namers com complementos faltantes passaria a validação silenciosamente e produziria getNamer(types)==null em join/meet/getLocalNamer (NPE tardio). Hoje o NamerFactory fornece o reticulado completo, então o impacto é a perda da rede de segurança, não um defeito ativo.

**naming-6. escapeToXPathString é no-op (replaceAll("\"", "\\\"") produz a mesma string) — segurança dos XPaths de refinamento depende só do removeQuotes upstream**

`src/main/java/com/android/commands/monkey/ape/naming/NamerFactory.java:220` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

Na replacement string do Java, `\\\"` desescapa para aspas literal: aspas são substituídas por aspas — nenhum escape ocorre (o próprio TODO admite). XPath 1.0 não tem escape dentro de literal com aspas duplas, então qualquer atributo com `"` que chegue a nameToXPathString gera RuntimeException em compileAbortOnError, dentro de stateRefinement/actionRefinement — abortando o passo (e via K05, potencialmente a thread). Hoje o disparo está defendido porque GUITreeBuilder.fillNode aplica StringCache.removeQuotes a text e content-desc na captura; mas resource-id e class NÃO passam por removeQuotes, e a defesa fica a dois arquivos de distância do ponto vulnerável — invariante frágil sem teste.

**naming-7. Chave de interning do ActionPatchName (toString) omite scrollType — dois Names iguais exceto na direção de scroll colapsam no cache, o primeiro vence**

`src/main/java/com/android/commands/monkey/ape/naming/ActionPatchNamer.java:100` · severidade: baixa · confiança: suspeita · métrica: ui · NOVO

NameManager.getCachedName usa name.toString() como chave, mas ActionPatchName.toString() = baseName + patches[patch], sem o scrollType, enquanto equals()/hashCode() incluem scrollType. Dois nós com mesmo baseName+patch e scrollType diferente compartilhariam o Name interned do primeiro, e State.buildActions (State.java:217, via decodeActions) geraria as ações de scroll ERRADAS para o segundo (ex.: só vertical num carrossel horizontal) — perda direta de cobertura de UI. No caminho vivo atual está mascarado: fillNode seta scrollable booleano (=3) e getScrollType é função do className, que está dentro do TypeName base dos widgets interativos; o par (1/2 direcional) só nasce em resetActions (caminho de rebuild/replay). Defeito latente de consistência chave-de-cache≠equals, com o mesmo padrão valendo para TextName/TypeName (contentDesc/resourceId omitidos do toString quando vazios).

**naming-8. finally de naming() dereferencia `results` que é null quando namingInternal lança — NPE mascara a exceção original do caminho de naming**

`src/main/java/com/android/commands/monkey/ape/naming/Naming.java:489` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

O bloco finally chama Logger.dformat com results.getNameSize(); se namingInternal lançar (ex.: 'A node has no namelets', o NPE de K06, ou saveXmlOnError paths), `results` ainda é null e o finally lança NullPointerException, substituindo a exceção original e destruindo o diagnóstico (o stack aponta para o Logger, não para a causa). Como esse é o funil único de abstração (GUITreeBuilder:182-204), qualquer falha de naming chega ao agente como NPE genérico — dificultando triagem de falhas de campo do tipo K05/K06.

**naming-9. AssertStatesDivergent.eval nunca popula o set `states` (falta states.add) — predicado é vacuamente true; classe hoje sem call-sites**

`src/main/java/com/android/commands/monkey/ape/naming/AssertStatesDivergent.java:43` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

O laço testa `states.contains(tmp)` mas nunca adiciona tmp ao set, então o predicado jamais detecta dois GUITrees caindo no mesmo estado e sempre retorna true. É o padrão clássico de 'coleção nunca populada'. Mitigante: grep mostra zero instanciações no código atual (o NamingFactory usa AssertSourceDivergent/AssertActionDivergent/AssertStatesFewerThan), então é defeito latente em código morto — mas qualquer reativação futura da classe silenciaria a constraint de divergência de estados sem aviso.

**naming-10. Caminho de reload de modelo quebrado em dois pontos: treeToNamingResult transient fica null pós-desserialização e o intern estático do NameManager reseta orders, disparando IllegalStateException em compareTo**

`src/main/java/com/android/commands/monkey/ape/naming/NameManager.java:29` · severidade: baixa · confiança: suspeita · métrica: geral · NOVO

Graph.readGraph (usado por ApeAgent via ape.modelFile) desserializa o modelo inteiro, incluindo Namings e Names. (1) Naming.treeToNamingResult é transient com inicializador de campo → após readObject fica null → NPE na primeira chamada a naming(). (2) O cache estático NameManager.names/nameList não é reconstruído: Names novos criados pós-load recebem order a partir de 0, colidindo com orders desserializados; AbstractName.compareTo lança IllegalStateException quando dois Names distintos têm o mesmo order (Arrays.sort em NamingResult). Latente hoje porque ApeAgent não está plugado no CLI (Fase 2 pendente), mas bloqueia qualquer experimento de continuação/warm-start de modelo.

**naming-11. Desempate por soma de ordinais não é ordem total: conjuntos de tipos distintos empatam (ex.: {TYPE,TEXT} vs {INDEX,PARENT}, soma 3) — ordem de candidatos de refinamento fica dependente de iteração de HashMap**

`src/main/java/com/android/commands/monkey/ape/naming/NamerComparator.java:44` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

NamerComparator e NamerLattice.comparator retornam 0 para namers diferentes com mesmo tamanho e mesma soma de ordinais (TYPE=0,INDEX=1,PARENT=2,TEXT=3,ANCESTOR=4: {0,3} vs {1,2}; {0,4} vs {1,3}). getSortedAbove ordena os uppers vindos de HashMap.values() com sort estável, então a posição relativa dos empatados herda a ordem de iteração do HashMap — e o refinamento (stateRefinement/actionRefinement) aceita o PRIMEIRO namer que passa nas checagens, com `upperBounds` podando os demais. Qual abstração o CEGAR escolhe entre dois refinamentos igualmente válidos passa a depender de detalhe de implementação do HashMap, não de critério do algoritmo — risco de irreprodutibilidade entre JVMs/versões e viés não documentado na comparação de braços experimentais.

**naming-12. Código morto acumulado no pacote: visited/queue calculados e nunca usados em stateRefinement; MonolithicNamingManager, NamedNodePartition, GUITreeProperty e Naming.join sem call-sites**

`src/main/java/com/android/commands/monkey/ape/naming/NamingFactory.java:370` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

Em stateRefinement (linhas 370-373) `visited` e `queue` são construídos via collectSortedAbove e nunca lidos — vestígio de uma busca BFS de namers abandonada (o método collectSortedAbove só existe para isso). MonolithicNamingManager, NamedNodePartition e GUITreeProperty não têm nenhuma referência externa; Naming.join/NamerLattice.meet idem. Nenhum efeito em runtime, mas confunde auditoria da inovação central e infla a superfície aparente do lattice.

**naming-13. Refutação parcial de K04: hasChild() invertido é neutralizado pelo único chamador — AbstractNamingManager.isLeaf fica acidentalmente correto mesmo com activityManagerType=activity**

`src/main/java/com/android/commands/monkey/ape/naming/Naming.java:252` · severidade: observacao · confiança: confirmado · métrica: geral · CONHECIDO — K04

hasChild() retorna true quando NÃO há filhos (invertido em relação ao nome), mas o único call-site no repositório é AbstractNamingManager.isLeaf (linha 73), que retorna naming.hasChild() — e isLeaf deve ser true exatamente quando não há filhos, então a dupla inversão produz semântica correta. O StateNamingManager (manager default, activityManagerType='state') sobrescreve isLeaf com namingToEdge e nem usa hasChild. Ou seja: a alegação de K04 de que o bug 'corrompe refinamento se activityManagerType=activity' não se sustenta no código atual; o risco real é apenas o nome enganoso para chamadores futuros.

**naming-14. K06 confirmado com precisão de linha: filter() engole XPathExpressionException e retorna null; Naming.select(Document) dereferencia sem null-check**

`src/main/java/com/android/commands/monkey/ape/naming/Namelet.java:159` · severidade: baixa · confiança: confirmado · métrica: geral · CONHECIDO — K06

Namelet.filter captura XPathExpressionException com corpo vazio e retorna null; Naming.select(Document) (Naming.java:456-458) chama nodes.getLength() sem checar. Verificação adicional desta auditoria: o vetor de entrada mais óbvio (aspas em text/content-desc) está defendido por StringCache.removeQuotes na captura, e a compilação da expr acontece antes (compileAbortOnError em nameToXPathString), então o disparo requer falha de AVALIAÇÃO em runtime (não de compilação) — mais raro do que K06 sugere, mas o padrão sentinela-null permanece no caminho quente de naming.

**naming-15. Blacklists de refinamento nunca resetam e usam limiares fixos: ação com ≥3 alvos ND ou refinamento falho fica permanentemente irrefinável**

`src/main/java/com/android/commands/monkey/ape/naming/NamingFactory.java:162` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

NDActionBlacklist adiciona a ação quando outStateTransitions.size()>=3 e nunca remove; actionRefinementBlacklist idem após uma rodada sem sucesso; guiTreeNamingBlaclist acumula por árvore. Combinado com o teto maxStatesPerActivity=10 (linha 276) — que também bloqueia QUALQUER refinamento na atividade dado que a fragmentação de estados (achado ignoreEmpty acima, K27) consome esse teto rápido — o CEGAR tende a desligar-se cedo em atividades movimentadas e nunca reconsiderar, mesmo que abstrações posteriores (batchAbstract) reduzam a contagem de estados. Limiar hardcoded affectedThreshold=8 em batchAbstract idem. É desenho conservador do APE original, mas vale registrar como mecanismo que limita silenciosamente a capacidade de resolver não-determinismo ao longo de runs de 300s+.


### A.3 Pacote ape/model/

**model-1. rebuildHistory() infla visitedCount de TODAS as arestas a cada rebuild do modelo (dobra e cresce a cada refinamento)**

`src/main/java/com/android/commands/monkey/ape/model/Graph.java:1293` · severidade: alta · confiança: confirmado · métrica: ui · NOVO

rebuildHistory() itera treeTransitionHistory INTEIRO e executa edge.visitedCount++ incondicionalmente. Mas as arestas re-adicionadas no rebuild ja receberam visitedCount++ via markVisited(edge) em addTransition(tt) (Graph.java:429->visitedAt), e as arestas sobreviventes ja tem contagem correta do caminho vivo. Resultado: apos cada Model.rebuild() (chamado em todo refinamento de naming, Model.java:274), cada aresta ganha +N onde N = suas ocorrencias no historico — dobra no 1o rebuild, triplica no 2o, etc. Consumidor verificado: SataAgent.weakActionSubsequenceFilter (linha ~163: strength==0 && visitedCount<3) passa a rejeitar cedo demais arestas fracas pouco visitadas, suprimindo a re-exploracao de transicoes nao-deterministicas; printVis tambem corrompido. Distinto de K07: os timestamps fv/lv acabam corretos (reparados por markVisited na re-adicao em ordem cronologica); o dano persistente e o contador.

**model-2. Rebuild dupla-conta visitas no ActivityNode: markVisited(source) re-adiciona todo o historico a um contador nunca resetado**

`src/main/java/com/android/commands/monkey/ape/model/Graph.java:427` · severidade: alta · confiança: confirmado · métrica: ui · NOVO

No rebuild (Model.java:272), addTransition(source,action,target,tt) chama markVisited(source,ts) para CADA tree-transition re-adicionada; markVisited(State) propaga para ActivityNode.visitedAt (Graph.java:588,598-601). Os States removidos sao recriados zerados, mas o ActivityNode sobrevive ao rebuild com a contagem viva intacta — cada rebuild soma de novo ~1 visita por tt historica cujo source pertence aquela activity. Consumidores verificados: SataAgent.doABA (linha 502: 'never move to hot activity'), backtrack para activity mais fria (609-612), collectTrivialActivities (mediana/media como threshold, 737-785) e gate de fuzzing (StatefulAgent:310). Activities que sofrem refinamento (telas complexas, tipicamente as com MOP) ficam artificialmente 'quentes' e sao despriorizadas no backtracking — perda direta de cobertura de UI e de alcance de telas MOP.

**model-3. Cap maxGUITreesPerState nunca e aplicado: a condicao testa an.getStates().size() (copy-paste do check anterior)**

`src/main/java/com/android/commands/monkey/ape/naming/NamingFactory.java:280` · severidade: media · confiança: confirmado · métrica: geral · NOVO

Em refine() (linha 280) e actionRefinement() (linha 1180): 'if (an.getStates().size() > maxGUITreesPerState)' compara o numero de ESTADOS da activity com o cap de ARVORES por estado, enquanto a mensagem de log imprime state.getGUITrees().size() — a intencao era claramente state.getGUITrees().size(). Com defaults (maxStatesPerActivity=10 < maxGUITreesPerState=20) o check e codigo morto: o guard de estados dispara antes. Consequencias: (1) o cap por-estado de GUITrees documentado no CLAUDE.md nao existe em lugar nenhum do codigo (grep confirma que so estes 2 sites usam a flag) — estados acumulam arvores sem limite, alimentando o OOM conhecido; (2) se o usuario elevar maxStatesPerActivity acima de 20, o refinamento passa a ser silenciosamente travado em 20 estados/activity pelo check errado. Arquivo fora do pacote model/, mas e exatamente o comportamento dos caps pedido no escopo.

**model-4. Rebuild marca visitado apenas o estado SOURCE de cada transicao: estados 'target-only' renascem unvisited, quebrando o invariante 'unvisited => sem transicoes'**

`src/main/java/com/android/commands/monkey/ape/model/Model.java:272` · severidade: media · confiança: confirmado · métrica: ui · NOVO

O caminho vivo marca newState (o lado de chegada) a cada passo (StatefulAgent:652), mas o rebuild (Graph.addTransition tt-based, linha 427) so chama markVisited(source). Estado removido cujas arvores aparecem apenas como target (ex.: ultima tela antes de restart) renasce com visitedCount=0 e fv=-1, mas com in-transitions. Efeitos: (a) _isNewState (StatefulAgent:651) e comparacoes de doABA usam contagens subestimadas; (b) se esse estado voltar a ser newState e for trivial, checkAndRefreshNewState/refreshNewState fazem 'if (newState.isUnvisited()) graph.remove(newState)' e lancam RuntimeException("An unvisited state has non-empty transitions.") (StatefulAgent:531-535,586-592) — crash nao tratado do loop de eventos (mesma classe de exposicao de K05). O sub-caso do crash e plausivel mas nao foi rastreado ate um disparo real.

**model-5. Graph.contains() lanca RuntimeException em vez de retornar false quando um estado igual-por-chave foi recriado apos remocao**

`src/main/java/com/android/commands/monkey/ape/model/Graph.java:1307` · severidade: media · confiança: suspeita · métrica: geral · NOVO

contains() trata 'check != state' com mesmo StateKey como violacao de sanidade e lanca. Porem graph.remove(newState) e chamado pelo agente fora de rebuild (StatefulAgent:531/582, sem bump de versao/naming): se a mesma tela reaparecer, getOrCreateState cria um NOVO objeto State com a MESMA StateKey. Qualquer referencia retida ao objeto antigo (GUITree.currentState das arvores do estado removido, ModelAction.getState()) que passe por Model.isStale()->graph.contains() derruba o passo com RuntimeException nao tratada, em vez do retorno false que isStale espera. Sentinela ambiguo: 'mesmo key, outro objeto' e simultaneamente estado-stale legitimo e corrupcao. Nao rastreei um call-site vivo que retenha a referencia antiga ate o disparo, por isso suspeita.

**model-6. graphId de arestas usa edges.size() como sequencial: apos remocoes, IDs de arestas sao reutilizados/duplicados**

`src/main/java/com/android/commands/monkey/ape/model/Graph.java:456` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

edge.setGraphId(graphId + "e" + edges.size()) mina o ID pelo tamanho atual do mapa. Remocoes fora de rebuild (graph.remove(newState) do refresh-check) encolhem edges sem trocar a versao g<N>, entao a proxima aresta criada recebe um ID que pode colidir com o de uma aresta viva. States nao sofrem disso (stateCounter monotonico). Impacto: traces/dot/vis com duas arestas 'g0eK' distintas — analises pos-hoc por ID de aresta (reconstrucao de caminhos, K60-style) ficam ambiguas.

**model-7. ActionRecord.resolveModelAction pode lancar IllegalStateException no tearDown e truncar action-history.log**

`src/main/java/com/android/commands/monkey/ape/model/Model.java:87` · severidade: baixa · confiança: suspeita · métrica: geral · NOVO

saveActionHistory (tearDown, StatefulAgent:1666) chama resolveModelAction em cada registro historico; tree.pickNodes(modelAction) usa binarySearch sobre currentNames da arvore, que e RENOMEADA in-place a cada rebuild — o Name antigo do registro pode nao existir mais e pickNodes lanca IllegalStateException (GUITree.java:166-169). O try-with-resources em Model.saveActionHistory captura so IOException, entao a excecao escapa: action-history.log fica parcial e o resto do tearDown (printActivityNodes, dumps do naming, contadores) e abortado. Perde-se o artefato usado por replay/reducer em runs com refinamentos que renomearam widgets acionados.

**model-8. resolveAction chama State.getAction(), que lanca IllegalStateException para widget fora do estado abstrato; so XPathExpressionException e capturada**

`src/main/java/com/android/commands/monkey/ape/model/xpathaction/XPathActionController.java:108` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

Se o XPath do usuario casa um no cujo XPathName nao esta entre os widgets do estado (cenario comum: naming abstraiu o no, ou o no nao e action-bearing), State.getAction lanca IllegalStateException apos dumpState, e o catch cobre apenas XPathExpressionException — a excecao sobe por selectNewActionFromBuffer (StatefulAgent:391) ate o loop de eventos sem handler (K05). Alem disso XPathActionReader/XPathletReader fazem System.exit(1) em JSON malformado ou IOException. Mitigante: feature opt-in (exige /sdcard/ape.xpath.actions + enableXPathAction), inativa nos experimentos.

**model-9. NPE latente em Graph.remove: actions.get(target).isEmpty() sem null-check apos removeFromMapSet**

`src/main/java/com/android/commands/monkey/ape/model/Graph.java:1239` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

Utils.removeFromMapSet retorna false silenciosamente quando a chave nao existe (nao remove entradas vazias), e a linha seguinte faz actions.get(action.getTarget()).isEmpty() sem checar null. Uma dupla remocao do mesmo estado (ou remocao de estado cujo par activity/Name ja foi limpo) causaria NPE dentro da remocao de estado. Rastreei os caminhos atuais (rebuild remove cada estado 1x; refresh-check substitui newState apos remover) e nao encontrei double-remove hoje — e uma mina para manutencao futura, nao um disparo ativo.

**model-10. printStatistics() roda a cada addStateTransition quando verbose: O(atividades) linhas de log por acao**

`src/main/java/com/android/commands/monkey/ape/model/Graph.java:473` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

addStateTransition chama printStatistics() em todo passo com verbose=true (default fora de rebuild), que imprime uma linha por ActivityNode alem do cabecalho. Em apps com dezenas de activities isso adiciona centenas de writes de log por acao, somando-se ao overhead ja medido de screenshot/XML por passo (K58 cobre aquele mecanismo, nao este). Reduz o numero de acoes executadas no orcamento de 300s — afeta indiretamente todas as metricas.

**model-11. Self-assignment de fv/lv em rebuildHistory (K07) — confirmado, mas com impacto menor que o catalogado**

`src/main/java/com/android/commands/monkey/ape/model/Graph.java:1287` · severidade: baixa · confiança: confirmado · métrica: geral · CONHECIDO — K07

As atribuicoes edge.firstVisitTimestamp=fv e edge.lastVisitTimestamp=lv sao de fato self-assignments (o valor lido da propria aresta e reatribuido). Correcao ao catalogo: os timestamps NAO ficam obsoletos apos o rebuild, porque markVisited(edge) em addTransition(tt) (linha 429) ja seta fv/lv corretamente durante a re-adicao em ordem cronologica (Model.java:219 ordena por timestamp) — os dois ifs sao no-ops inofensivos. O dano real do metodo e o visitedCount++ da linha 1293 (finding separado).


### A.4 Pacotes ape/tree/ + ape/events/

**tree-events-1. clearChildren itera NodeList VIVA com i++ e remove apenas metade dos filhos DOM — checkAndRemoveWebView deixa 'nos fantasma' no documento que viram widgets/acoes no modelo**

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeNode.java:555` · severidade: alta · confiança: confirmado · métrica: ui · NOVO

clearChildren faz `for (i=0; i<childNodes.getLength(); i++) removeChild(item(i))` sobre um NodeList vivo do DOM: apos remover item(0) a lista compacta e i++ pula o proximo — sobram os filhos de posicao impar. Chamado por GUITreeBuilder.checkAndRemoveWebView (GUITreeBuilder.java:469) quando um WebView tem >64 descendentes (default, alwaysIgnoreWebView=false). Os filhos logicos (GUITreeNode) sao zerados, mas metade dos Elements permanece no DOM com userData ainda apontando para os GUITreeNodes removidos; Naming.namingInternal (Naming.java:494+) percorre o DOM, entao esses nos fantasma recebem nomes, entram em currentNames/currentNodes do GUITree e viram widgets do State com clickable/scrollable preservados — o agente gasta acoes clicando em conteudo web que o filtro tentou descartar, e o modelo diverge da arvore logica. descendantCount tambem nao e recalculado.

**tree-events-2. fillNode nunca captura AccessibilityNodeInfo.isPassword() — setIsPassword tem ZERO call sites, entao GUITreeNode.isPassword() e sempre false e a deteccao de campo de senha do InputValueGenerator (prioridade 1 da spec) esta morta**

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeBuilder.java:582` · severidade: alta · confiança: confirmado · métrica: ui · NOVO

fillNode copia checked/enabled/checkable/clickable/longClickable/scrollable/focusable/focused mas omite info.isPassword(); grep confirma que setIsPassword nao e chamado em lugar nenhum. InputValueGenerator.detectCategory (utils/InputValueGenerator.java:79,104) usa node.isPassword() como sinal de maior prioridade — nunca dispara; so resta o fallback por keyword em resourceId/contentDesc, que falha em apps ofuscados (R8 remove resource names) e campos sem id. Campos de senha recebem string GENERIC aleatoria → logins nunca completam → paredes de login (K57: ~16% dos runs presos) permanecem, bloqueando telas profundas onde vivem operacoes MOP. O atributo XML 'password' gravado por fillElement (linha 565) tambem e sempre 'false'.

**tree-events-3. setText nao sincroniza o atributo @text do DOM (ao contrario de setClickable/setIndex/setClassName) — computeAndSetImageText muta o texto DEPOIS da criacao do Element, e o naming por XPath ve valores diferentes no documento vivo vs reconstruido**

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeNode.java:325` · severidade: alta · confiança: confirmado · métrica: geral · NOVO

Em buildNodeAndXmlFromNodeInfo, os Elements sao criados (fillElement grava text='') e SO DEPOIS computeImageText (default true) chama computeAndSetImageText → setText('#hash') no no, sem atualizar o DOM. TextNamer.naming(node) usa node.getText() → o Name carrega '#hash', e NamerFactory:214 deriva o XPath do namelet de refinamento de name.toXPath() com [@text="#hash"] (TextName.appendXPathLocalProperties). Naming.select avalia esse XPath sobre o Document: no documento vivo @text='' → 0 matches (refinamento silenciosamente inerte); mas GUITree.releaseLoadedData roda a CADA acao consumida (StatefulAgent:684) e getDocument() reconstroi o DOM via fillElement lendo node.getText() → agora @text='#hash' casa. O mesmo GUITree nomeia diferente antes/depois do release do documento → refinamento inconsistente, nao-determinismo espurio e risco de IllegalStateException 'Cannot find widget' em pickNodes no caminho quente.

**tree-events-4. Fallback de generateClickEventAt: se os bounds do widget nao intersectam a area visivel, o clique e executado no CENTRO DA TELA e o modelo registra a acao como se tivesse acertado o widget pretendido**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:359` · severidade: alta · confiança: confirmado · métrica: ui · NOVO

getVisibleBounds(nodeRect) retorna null quando Rect.intersect falha (widget com bounds vazios, rolado para fora, arvore obsoleta durante animacao/dialog); generateClickEventAt entao usa AndroidDevice.getDisplayBounds() inteiro e clica no exactCenter — um ponto arbitrario que pode acionar OUTRO widget (ou nada). A transicao resultante e atribuida a acao original no modelo → arestas falsas, cobertura de UI creditada errada e steering MOP avaliado sobre transicoes que nunca ocorreram. So um warning 'Error to fetch bounds' e emitido. Arquivo fora dos 2 pacotes do escopo, mas e o consumidor direto de GUITreeNode.getBoundsInScreen no caminho de execucao (foco explicito: 'coordenadas de clique fora do widget').

**tree-events-5. GUITreeNode.isEditText() casa APENAS 'android.widget.EditText' exato, enquanto GUITreeBuilder.isEditText reconhece 4 classes — AutoCompleteTextView/MultiAutoCompleteTextView/ExtractEditText nunca recebem texto de entrada**

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeNode.java:199` · severidade: media · confiança: confirmado · métrica: ui · NOVO

Duas heuristicas para a mesma decisao: o gate real de injecao de texto (ApeAgent.checkInput:188 e MonkeySourceApe.doInput:1252) usa GUITreeNode.isEditText() (igualdade exata de className), mas o conjunto editTextWidgets de GUITreeBuilder:522-529 (que inclui AutoCompleteTextView — caixas de BUSCA tipicas — e MultiAutoCompleteTextView) so e usado por TextNamer. AccessibilityNodeInfo reporta 'android.widget.AutoCompleteTextView' para esses widgets → checkInput nunca sorteia input para eles → fluxos de busca/filtragem ficam inexplorados e formularios com autocomplete nunca sao preenchidos. Contribui para a discrepancia K19 (taxa efetiva de preenchimento ~42% vs inputRate=0.8).

**tree-events-6. ApeDragEvent/ApePinchOrZoomEvent.toJSONObject gravam float[] cru no JSONObject — serializado como '[F@hash', quebrando o replay de qualquer FuzzAction que contenha drag**

`src/main/java/com/android/commands/monkey/ape/events/ApeDragEvent.java:76` · severidade: media · confiança: confirmado · métrica: geral · NOVO

jEvent.put("values", values) com float[] nao e convertido para JSONArray pelo org.json do Android; na serializacao vira a string toString() do array ('[F@1a2b3c'). fromJSONObject/ApeEvents.toApeEvent chama getJSONArray("values") → JSONException. ReplayAgent:160-161 reconstitui FUZZ via FuzzAction.fromJSON → replay de runs gravados falha em qualquer fuzz burst contendo drag (~2 dos 20 tipos por iteracao, com 5-15 eventos por burst a probabilidade por FuzzAction e alta). Reproducao de crash e o reducer (delta debugging) ficam quebrados para esses casos. ApeTrackballEvent faz a conversao corretamente (constroi JSONArray), provando o padrao esperado.

**tree-events-7. checkAndRemoveWebView usa getDescendantCount() (TODOS os nos) onde o codigo original contava apenas nos com acao — WebViews com >64 nos totais sao removidos inteiros, zerando cobertura em apps hibridos**

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeBuilder.java:465` · severidade: media · confiança: suspeita · métrica: ui · NOVO

O proprio comentario preserva a intencao original: `// count(node, actionNodeFilter)`. Qualquer pagina web real facilmente excede 64 nos totais mesmo com poucos acionaveis, entao com a config default (alwaysIgnoreWebView=false, threshold=64) o conteudo do WebView e descartado quase sempre — o default 'manter webviews' e ilusorio. Em apps hibridos toda a superficie de UI dentro do WebView (e handlers MOP atras dela) fica inalcancavel. Compoe com o bug do clearChildren (metade fantasma no DOM): o descarte e ao mesmo tempo agressivo demais e mal executado. Suspeita porque a troca pode ter sido deliberada no APE upstream, mas o comentario contradiz o codigo (violacao P4).

**tree-events-8. getAttributeValue — a supressao de @text para EditText — tem ZERO call sites: texto digitado entra no TextName e fragmenta estados a cada string digitada**

`src/main/java/com/android/commands/monkey/ape/naming/TextNamer.java:110` · severidade: media · confiança: suspeita · métrica: ui · NOVO

TextNamer.getAttributeValue retorna '' para classes EditText (via GUITreeBuilder.isEditText), claramente para impedir que o conteudo digitado participe da abstracao — mas nenhum codigo chama esse metodo (grep: unica ocorrencia e a definicao). TextNamer.naming(node):128 usa node.getText() cru; quando o refinamento promove um EditText a namer TEXT, cada valor digitado gera um Name (e um State) distinto, e o XPath do namelet refinado ([@text="valor"]) deixa de casar assim que o texto muda → fragmentacao de estados e refinamento instavel exatamente nas telas de formulario que o objetivo MOP precisa atravessar. Descoberto rastreando o consumidor de GUITreeBuilder.isEditText do escopo.

**tree-events-9. contains() testa Arrays.binarySearch com index == -1 em vez de index < 0 — ponto de insercao negativo arbitrario indexa currentNodes**

`src/main/java/com/android/commands/monkey/ape/tree/GUITree.java:284` · severidade: media · confiança: confirmado · métrica: geral · CONHECIDO — K03

Para um Name ausente cujo ponto de insercao nao e o inicio do array, binarySearch retorna -(ip)-1 < -1 e o codigo segue para currentNodes[index] com indice negativo → ArrayIndexOutOfBoundsException no caminho de Model.contains/validacao. Reconfirmado presente na working tree (linha 284). Ja catalogado como TREE-01.

**tree-events-10. generatePinchOrZoomEvent constroi os pontos e nunca faz events.add — ~15% das iteracoes de fuzzing (3/20 slots) silenciosamente nao produzem evento**

`src/main/java/com/android/commands/monkey/ape/events/ApeFuzzer.java:190` · severidade: media · confiança: confirmado · métrica: geral · CONHECIDO — K33

Reconfirmado: o metodo termina sem `events.add(new ApePinchOrZoomEvent(points))` (K33), e o dimensionamento `new PointF[4 + count << 1]` tem precedencia errada (K34) — (4+count)*2 em vez de 4+2*count, o que por acidente evita overflow (o fill usa 2*count+6 slots). Verificacao adicional nova: mesmo que o add fosse restaurado, ApePinchOrZoomEvent valida points.length<4 no construtor mas generateMonkeyEvents precisa de >=6 pontos e contagem par — eventos de 4-5 pontos causariam AIOOBE; o consumo real do fuzzer (2*count+6 >= 6, par) e seguro, mas sobram 2 slots null no array que fromPointsArray converteria em NPE se o array fosse usado integralmente — o fix precisa cuidar dos dois detalhes.

**tree-events-11. Caminho de reconstrucao por XML esta quebrado de tres formas (bounds nunca serializado → NPE no load; regex de bounds rejeita coordenadas negativas; parametro index ignorado) — hoje morto, mas os step-N.xml salvos nao sao re-carregaveis**

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeBuilder.java:475` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

fillElement (linha 550) nao grava o atributo 'bounds', entao os XMLs salvos por saveGUITreeToXmlEveryStep (StatefulAgent:839, default true) nao tem bounds; buildNodeFromXml:499 faz parseRect(getAttribute('bounds')) → parseRect('') retorna null → setBoundsInScreen(null) NPE imediato. Alem disso BOUNDS_RECT (linha 71) so aceita [0-9]+, rejeitando bounds negativos legitimos de views parcialmente fora da tela, e o parametro `index` de buildNodeFromXml e computado (ci++) mas nunca usado (setIndex le o atributo). Nenhum call site atual usa os construtores XML (grep), logo severidade baixa — mas qualquer tentativa futura de replay/analise offline a partir dos XMLs por passo falha na primeira linha, e toBoundsInParent (linha 135) ainda calcula right/bottom subtraindo parentScreen.right/bottom em vez de left/top (matematica de coordenadas errada).

**tree-events-12. getCountOfTargetNodes(String) faz Arrays.binarySearch(Name[], String) — ClassCastException garantida se algum dia for chamado; hoje codigo morto**

`src/main/java/com/android/commands/monkey/ape/tree/GUITree.java:183` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

currentNames e Name[] e o parametro e String; a comparacao Name.compareTo(String) lanca ClassCastException na primeira sondagem. O unico caminho e State.getCountOfTargetNodes (State.java:421) que delega e tambem nao tem chamadores. Sentinela de bug latente: qualquer uso futuro (p.ex. instrumentacao MOP contando alvos por nome) explode em runtime em vez de falhar em compilacao.

**tree-events-13. ClickPoint.RIGHT/BOTTOM/TOP_RIGHT/BOTTOM_RIGHT usam Math.min(bounds.left, bounds.right-1) — logica invertida faz RIGHT clicar na borda ESQUERDA (latente: so CENTER/RANDOM/TOP_LEFT sao usados hoje)**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:376` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

Math.min entre left e right-1 devolve sempre left (e min(top,bottom-1) devolve top), entao os pontos de clique RIGHT/BOTTOM sao identicos a LEFT/TOP. Call sites atuais usam apenas CENTER (default, useRandomClick=false hardcoded), RANDOM e TOP_LEFT (generateClearEvent) — todos corretos — mas qualquer uso futuro dos outros enum values (p.ex. para widgets com area de toque assimetrica) clicaria no lado oposto do pretendido.

**tree-events-14. getIndexPath: memoizacao nunca armazena (campo indexPath permanece null para sempre) e o metodo nao tem nenhum chamador**

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeNode.java:100` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

O `if (this.indexPath == null)` computa o caminho recursivamente e retorna sem atribuir o campo — a cache prometida nunca popula (padrao 'valor calculado mas nunca guardado'). Grep mostra zero call sites fora da propria classe, entao e codigo morto com bug de memoizacao embutido; se reativado para naming por index-path, cada chamada refaz a recursao inteira e enche o StringCache.

**tree-events-15. Guarda 'Input only once' de doInput avisa mas nao retorna — o branch de deduplicacao por timestamp nao previne nada**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:1239` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

Se lastInputTimestamp == getTimestamp() o codigo loga 'checkVirtualKeyboard: Input only once.' e SEGUE executando o input mesmo assim (falta o return); o else so atualiza o timestamp. A mensagem promete dedup que nao acontece — ou o guard e inutil ou ha input duplicado intencional nao documentado (violacao P4). Impacto pratico pequeno (multiplos inputs no mesmo passo sao raros), mas e o padrao classico de 'return faltando' do escopo.


### A.5 Pacote ape/utils/

**utils-1. nextString() sorteia nextInt(stringList.size()) ANTES do check isEmpty — IllegalArgumentException com lista vazia**

`src/main/java/com/android/commands/monkey/ape/utils/StringCache.java:108` · severidade: media · confiança: confirmado · métrica: ui · NOVO

O guard `if (!stringList.isEmpty())` vem depois do `ThreadLocalRandom.current().nextInt(stringList.size())`; com size==0 o nextInt(0) lança IllegalArgumentException. A lista só é populada por GUITreeBuilder (text/contentDesc não-vazios, truncados a 8 chars) ou /sdcard/ape.strings. Chamado no caminho quente de input: ApeAgent.generateInputText → InputValueGenerator GENERIC (categoria fallback mais comum) → StringCache.nextString(). Se o primeiro EditText for tocado antes de qualquer texto não-vazio ser cacheado (app image-only/splash sem texto), a exceção sobe não-tratada pela thread de eventos (mesma cadeia do K05) e mata o run.

**utils-2. RandomHelper usa ThreadLocalRandom não-semeável — a seed -s do Monkey é ignorada por todas as decisões do agente; runs irreprodutíveis**

`src/main/java/com/android/commands/monkey/ape/utils/RandomHelper.java:27` · severidade: media · confiança: confirmado · métrica: geral · NOVO

Monkey cria mRandom=new Random(mSeed) (Monkey.java:697) e o repassa a MonkeySourceApe, mas RandomHelper.getRandom() retorna ThreadLocalRandom.current(), que não aceita setSeed. Há 34 call sites em ape.agent/model/events via RandomHelper (incluindo randomPickWithPriority — a roleta central de seleção de ação, SataAgent:972 — e RandomHelper.toss(inputRate)/fuzzingRate), enquanto egreedy() usa ape.getRandom() semeado — RNG misto. Consequência: nenhum run é reproduzível com -s, flips de argmax não podem ser re-executados (agrava K60/K61) e baselines de RNG entre braços não são controláveis.

**utils-3. mopStrictPackageMatch continua inalcançável em produção: o único call site usa load(path) 1-arg, nunca passando package/mainActivity de runtime**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:148` · severidade: media · confiança: confirmado · métrica: mop-cobertura · NOVO

StatefulAgent:162 chama `MopData.load(Config.mopDataPath)` → load(path,null,null); com expected==null a comparação T1.7 é pulada, então mesmo com ape.mopStrictPackageMatch=true nada é rejeitado nem sequer logado (warn-only também não dispara). O overload 3-arg só é exercitado por MopDataTest. CLAUDE.md documenta a flag como rejeição "quando o JSON diverge dos valores de runtime" — drift doc/código. Impacto: um JSON errado/stale pareado ao APK errado (classe de skew do K01) é aceito silenciosamente, invalidando o braço MOP sem sinal. K24 foi marcado corrigido no gh15, mas o residual do call site permanece.

**utils-4. Rollup por Activity é write-only: getActivityCoverageGap, activityRollup, getTotalElements e getTotalInteractions têm ZERO call sites de produção**

`src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java:260` · severidade: media · confiança: confirmado · métrica: ui · NOVO

grep no repositório: nenhum caller fora do próprio arquivo/testes. O invariante INV-COV-05 ("eviction não perde cobertura") vale só para um mapa que ninguém lê: quando um state evicted é revisitado, getCoverageGap volta a 1.0 e getInteractionCount a 0 — coverage boost re-dispara para widgets já testados e computeDynamicEpsilon volta ao máximo. Latente sob o cap default coverageMaxStates=2000 em runs de 300s, mas o comentário em Config.java:162-164 e o javadoc prometem propriedade que o sistema observável não tem; e o mecanismo de reporting agregado por Activity (motivação do K27/K59) está morto.

**utils-5. Colisão do sentinela "": widgets com idName="" entram no widgetData sob a chave vazia e casam com todo nó de runtime sem resourceId**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:314` · severidade: media · confiança: suspeita · métrica: mop-cobertura · NOVO

parseWindows só filtra idName==null; idName=="" (frequente no fixture: 16/31 widgets em CryptographyActivity, 21 no total do cryptoapp) é inserido com last-write-wins numa única entrada "". Do lado do consumo, extractShortId retorna "" tanto para resourceId null quanto malformado, e MopScorer.score/getWidget e ApeAgent.generateInputText consultam getWidget(activity, "") — devolvendo um widget arbitrário (o último parseado). Se esse widget for MOP-flagged, TODOS os nós sem id da activity ganham +500/+300 espúrios (inclusive via varredura de containment de pais/filhos sem id); o inputType/hint dele também contamina o typed input de qualquer EditText sem id. No cryptoapp nenhum widget "" é flagged (verificado), mas nada impede no corpus.

**utils-6. Typed input (T1.3) usa lookup exato sem containment e deriva a activity por heurística diferente do scorer — quase nunca dispara**

`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:208` · severidade: media · confiança: confirmado · métrica: mop-cobertura · NOVO

generateInputText faz `md.getWidget(activity, extractShortId(...))` exato, enquanto o boost MOP usa mopBoostWithContainment (pais/filhos ≤2 níveis) justamente porque o id exato quase nunca casa (K53: +500 é 100% resgatado por containment). Além disso a activity vem de ape.getTopActivityClassName() (AndroidDevice, pode divergir/atrasar) e não de newState.getActivity() — duas derivações da mesma chave. Resultado: a feature fuzzInputTyped fica praticamente inerte nos mesmos apps em que o boost precisa de containment, caindo no gerador legado sem registro.

**utils-7. matchKeywords com substring ingênuo: 'account'→NUMBER (via 'count'), 'security…'→URL (via 'uri'), '…tel…'→PHONE**

`src/main/java/com/android/commands/monkey/ape/utils/InputValueGenerator.java:141` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

contains() sem fronteira de palavra classifica errado campos comuns de login/cadastro: um campo 'account'/'account_name' recebe "42", 'security_question/answer' recebe "https://example.com", ids contendo 'tel' fora de telefone recebem número de telefone. Como ~16% dos runs já ficam presos em login-walls (K57), input errado nesses formulários custa cobertura de UI e das operações MOP atrás do login. Detecção só melhora se resourceId/contentDesc casarem categoria correta primeiro (email/password vêm antes de number, mitigando parte).

**utils-8. Orçamento por activity congelado no primeiro registro e nunca resetado — subestimado se o primeiro state for uma tela esparsa**

`src/main/java/com/android/commands/monkey/ape/utils/ActivityBudgetTracker.java:27` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

registerActivity é idempotente e computa budget = 50 + 5×widgetCount usando o widgetCount do PRIMEIRO state visto da activity (StatefulAgent:667); se for um dialog/permission-wall com 2-3 ações, o budget fica ~60 para sempre, mesmo que a activity real tenha dezenas de widgets. counts crescem monotonicamente o run inteiro. Mitigação: em SataAgent:319 a exaustão é advisory (tenta navegação trivial, senão cai no SATA normal), então o efeito é desvio persistente de prioridade, não bloqueio — mas em runs longos toda activity converge para 'exhausted' e o desvio vira permanente.

**utils-9. getInteger/getLong/getDouble engolem NumberFormatException silenciosamente; defaults não-String são inseridos no Properties**

`src/main/java/com/android/commands/monkey/ape/utils/Config.java:226` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

Um valor malformado em ape.properties (ex.: 'ape.mopWeightDirect=5OO' ou espaço à direita) cai no default sem nenhum warning — um braço experimental miscalibrado roda como se estivesse configurado. Adicionalmente getBoolean/getInteger fazem configurations.put(key, defaultValue) com objeto não-String; Properties.getProperty ignora valores não-String, então um Config.get(key) subsequente retornaria null em vez do default (latente hoje, pois cada chave é lida por um único accessor).

**utils-10. parseEntry descarta ações sem extras e duplicatas (first-wins) — catálogo retém ~120 dos 187 entries documentados**

`src/main/java/com/android/commands/monkey/ape/utils/SystemBroadcastCatalog.java:137` · severidade: observacao · confiança: confirmado · métrica: mop-violacoes · NOVO

`if (!extras.isEmpty() && !catalog.containsKey(action))` faz size()/hasAction subnotificarem e ignora variantes adicionais de comando adb da mesma action. Hoje é inócuo: o único consumidor (StatefulAgent:1104) usa lookup() só para enriquecer extras, e a lista de ações vem dos intent filters do MopData. Também: parseExtrasFromAdb para a coleta de valor em tokens contendo '/.', o que falha para componentes totalmente qualificados 'pkg/pkg.Cls' (valor engoliria o componente). Risco só se hasAction/size passarem a gatear triggering.

**utils-11. [K02 confirmado] widgetData é Map<idName> last-write-wins por base-activity, mesclando inclusive widgets do #OptionsMenu no namespace da activity**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:313` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · CONHECIDO — [K02]

Confirmado com ceticismo no código atual: parseWindows agrupa por baseActivity(w.name) (o sufixo #OptionsMenu é removido), então itens de menu e widgets da activity compartilham o mesmo mapa e colisões de idName sobrescrevem — um widget flagged pode ser substituído por um unflagged homônimo antes do scoring. O flag de activity (mopActivities) sobrevive porque é avaliado por widget antes do overwrite, mas o sinal discriminativo por-widget se perde exatamente como o K02 mediu (45% dos flagged).

**utils-12. [K20 confirmado] scoreWtg consulta wtgTransitions com base-activity mas o mapa é keyed pelo nome de janela com sufixo #**

`src/main/java/com/android/commands/monkey/ape/utils/MopScorer.java:84` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · CONHECIDO — [K20]

Confirmado end-to-end: parseTransitions usa source.name (ex.: 'br.unb.cic.cryptoapp.MainActivity#OptionsMenu' — presente no fixture real) como chave; StatefulAgent:1420 passa newState.getActivity() (nome base via StateKey). Todas as arestas WTG originadas de janelas OPTIONSMENU/dialog sufixadas são invisíveis ao boost WTG. O formato de widgetName no fixture ('buttonGenerated', 'menu_item_cipher') casa com extractShortId, então o join por widgetName em si está correto — o defeito é só a chave da janela.

**utils-13. [K37 confirmado] maxStringPieceLength: zero usos fora de Config.java — dead config**

`src/main/java/com/android/commands/monkey/ape/utils/Config.java:105` · severidade: observacao · confiança: confirmado · métrica: geral · CONHECIDO — [K37]

grep em todo src/main/java: nenhuma referência a Config.maxStringPieceLength fora da própria definição. Flag lida do ape.properties e impressa em printConfigurations, sugerindo efeito que não existe. Demais flags do arquivo foram cruzadas: todas as outras têm ≥1 uso real (verificado por contagem de call sites), e todas as flags documentadas no CLAUDE.md existem no código (llmMaxCalls já removido da doc, K32).

**utils-14. [K58 confirmado] takeScreenshotForEveryStep e saveGUITreeToXmlEveryStep continuam TRUE por default**

`src/main/java/com/android/commands/monkey/ape/utils/Config.java:40` · severidade: media · confiança: confirmado · métrica: ui · CONHECIDO — [K58]

Defaults confirmados no código atual (linhas 40-41). O custo medido de 20-40% do throughput de passos por run (K58) permanece o comportamento de fábrica de qualquer braço que não sobrescreva via ape.properties — menos ações por orçamento de 300s afeta diretamente cobertura de UI e de operações MOP em todos os braços igualmente.

**utils-15. Logger.debug hardcoded false; todo trace via System.out.format sem buffer e sem timestamp**

`src/main/java/com/android/commands/monkey/ape/utils/Logger.java:22` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

dprintln/dformat são inertes sem recompilar (flag não vem de Config, inconsistente com o resto do projeto). Todos os níveis vão a System.out por format() individual — sem perda/truncamento detectado (formato fixo '%s'), mas sem timestamp próprio a correlação temporal do trace depende do wrapper externo, e o volume por passo (várias linhas iformat no caminho de boost) soma I/O síncrono ao custo do K58.


### A.6 Raiz Monkey + ape/

**monkey-root-1. waitForActivity sem timeout: se a activity esperada nunca chega ao topo, a corrida inteira fica presa em throttles de 100ms ate o fim do orcamento**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:1190` · severidade: alta · confiança: confirmado · métrica: ui · NOVO

generateActivityEvents seta waitForActivity=true; o unico ponto que o limpa e checkAppActivity quando um pacote permitido esta no topo. Se o app nunca chega ao foreground (crash antes da primeira activity com processo morto por mKillProcessAfterError, launcher-trampoline para outro pacote, ou o race em que GrantPermissionsActivity aparece dentro da janela de 2000ms e o controller faz stopPackages), cada getNextEvent cai em 'still waiting... another 100ms' e enfileira so um throttle — sem contador, sem restart, sem desistencia. startRandomMainApp nunca e re-disparado porque o branch de bloqueio exige !waitForActivity. Um unico episodio consome todo o --running-minutes com 0 acoes; consistente com o padrao de runs com <=2 states do K57, mas mecanismo distinto e nao documentado.

**monkey-root-2. Threshold acumulativo hardcoded totalBadStates>100 encerra a corrida inteira antecipadamente via StopTestingException, sem nunca resetar**

`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:348` · severidade: alta · confiança: confirmado · métrica: ui · NOVO

updateStateWrapper incrementa totalBadStates a cada BadStateException ('No available action on the current state', SataAgent:403/StatefulAgent:1576) e lanca StopTestingException quando o acumulado da corrida passa 100. getNextEvent captura, limpa a fila e retorna null; runMonkeyCycles interpreta null como fim e sai do loop — mesmo em modo --running-minutes, que so protege contra mAbort. Apps com muitas telas sem acoes validas (WebView, telas vazias, dialogs de sistema) queimam o limite em minutos e perdem o resto do orcamento de exploracao; o contador nunca decai nem reseta apos recuperacao bem-sucedida. O tearDown roda (saida 'limpa'), entao o corte fica invisivel no trace exceto pela mensagem 'Too many bad states'.

**monkey-root-3. getFocusedStack pode retornar null (ou lancar NPE interna) e os dois callers em SataAgent desreferenciam sem checagem — NPE no caminho quente mata a corrida inteira**

`src/main/java/com/android/commands/monkey/ape/AndroidDevice.java:665` · severidade: media · confiança: suspeita · métrica: geral · NOVO

getFocusedStack devolve null em IOException, quando nenhum stack casa focusedStackId, ou quando os regex (formato de dumpsys pre-P: 'Stack #N:' sem sufixo, 'mFocusedStack=ActivityStack{...}') nao casam com a versao do Android; pior, se TASK_PATTERN casar sem STACK_PATTERN (formato API 28+: 'Stack #0: type=standard...'), currentStack.tasks.add lanca NPE dentro do proprio metodo, que so captura IOException/InterruptedException. SataAgent:647 (selectNewActionBackToActivity) e :881 (backToTrivialActivity) chamam stack.getTasks()/taskStack.dump() direto no retorno. Como nenhuma camada acima de updateState trata RuntimeException, a excecao sobe ate Monkey.main e o processo sai com exit(1), sem tearDown.

**monkey-root-4. tearDown do MonkeySourceApe nao esta em finally: qualquer RuntimeException no caminho quente perde model, coverage dump, sataTimeline e caudas de produce/consume.log**

`src/main/java/com/android/commands/monkey/Monkey.java:780` · severidade: media · confiança: confirmado · métrica: geral · NOVO

runMonkeyCycles so tem finally para restaurar rotacao; ((MonkeySourceApe)mEventSource).tearDown() na linha 780-782 so executa no retorno normal. getNextEvent captura apenas StopTestingException, entao qualquer NPE/AIOOBE do agente/naming (ex.: K03, K05, K06, ou o achado do getFocusedStack) propaga ate main, que faz exit(1) sem fechar os PrintWriters bufferizados nem chamar Agent.tearDown — perdendo os artefatos finais que os experimentos usam para medir cobertura. Ou seja, alem de matar a corrida, um unico bug de estado corrompe a medicao da parte ja explorada. Complementa K05 (que documenta apenas a morte da thread).

**monkey-root-5. SecurityException ao (re)iniciar a activity encerra a corrida mesmo em modo continuo: aperv-tool nao passa --ignore-security-exceptions e o override do modo continuo nao cobre este caso**

`src/main/java/com/android/commands/monkey/Monkey.java:1396` · severidade: media · confiança: suspeita · métrica: geral · NOVO

runMonkeyCycles seta systemCrashed=!mIgnoreSecurityExceptions quando injectEvent retorna INJECT_ERROR_SECURITY_EXCEPTION — que so e produzido por MonkeyActivityEvent (startActivityAsUser), usado em todo EVENT_START/RESTART/CLEAN_RESTART do APE. O bloco do --running-minutes (linhas 1266-1269) forca mIgnoreCrashes=false/mIgnoreTimeouts=false/mKillProcessAfterError=true mas nao toca mIgnoreSecurityExceptions, e a linha de comando gerada por aperv-tool (_build_main_command em rvsec/rv-android/modules/aperv-tool/.../aperv/tool.py) nao inclui a flag. Uma unica SecurityException num restart (activity protegida por permissao, restricao de background-launch em APIs novas) termina a corrida silenciosamente antes do tempo.

**monkey-root-6. checkPackage e dead code: topComp (via tasks) e a arvore de acessibilidade (via janela focada) sao obtidos por heuristicas distintas e nunca validados entre si antes de updateState**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:902` · severidade: media · confiança: suspeita · métrica: ui · NOVO

generateEvents captura topComp=getTopActivityComponentName() (task record) e info=getRootInActiveWindow() (janela ativa) e o proprio comentario admite 'this two operations may not be the same'. O metodo checkPackage, que compararia os pacotes, nao tem nenhum call site. Quando um overlay (IME, dialog de sistema, janela de outro app) e a janela ativa enquanto a task do alvo segue no topo, a GUITree do overlay e atribuida a activity do app no modelo — fragmentando states e poluindo o naming (soma-se ao K27 por outro mecanismo). Tambem ha a dualidade isPackageValid (whitelist estrita, usada em checkAppActivity) vs checkEnteringPackage (blacklist-ou-whitelist, usada no controller): rodar so com blacklist reiniciaria o app em loop infinito, mascarado porque aperv sempre usa -p.

**monkey-root-7. Guarda 'Input only once' em doInput e no-op: loga o aviso mas nao retorna, e o texto e enviado de qualquer forma**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:1239` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

if (lastInputTimestamp == mAgent.getTimestamp()) apenas imprime 'checkVirtualKeyboard: Input only once.' e o fluxo continua para generateClearEvent/sendText nos dois braços — o return que faria a deduplicacao esta ausente. Se dois MODEL_CLICKs com inputText ocorrerem no mesmo timestamp (acao + acao de fuzz, ou re-execucao), o texto e digitado duas vezes, com o ENTER do fallback de key-events podendo submeter formularios duplicadamente. Impacto raro, mas e um padrao classico de efeito colateral perdido no caminho de preenchimento de formulario (K18/K19 tornam esse caminho ja fragil).

**monkey-root-8. connect() aloca mImageWriters com Config.imageWriterCount mas inicializa com loop hardcoded 'i < 3' — a flag e dead config e qualquer valor != 3 crasha**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:180` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

mImageWriters = new ImageWriterQueue[imageWriterCount] seguido de for (i=0; i<3; i++). Com ape.imageWriterCount=2 ocorre ArrayIndexOutOfBoundsException no startup; com 5, os indices 3-4 ficam null e nextImageWriter() sorteia sobre o array inteiro, gerando NPE tardio em takeScreenshot que derruba a corrida (sem tearDown, ver achado do finally). Com o default 3 e inocuo, mas a flag anunciada em Config nao tem efeito real — padrao 'flag lida sem efeito'.

**monkey-root-9. ClickPoint RIGHT/BOTTOM/TOP_RIGHT/BOTTOM_RIGHT/BOTTOM_LEFT usam Math.min onde deveria ser Math.max — clicam na borda oposta a prometida (latente)**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:376` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

Math.min(bounds.left, bounds.right-1) sempre escolhe left (ja que left <= right-1), entao RIGHT clica na esquerda; analogamente BOTTOM clica no topo. Hoje e latente: os unicos usos reais sao CENTER (default), RANDOM (fuzz) e TOP_LEFT (generateClearEvent). Se alguem ativar useRandomClick ou usar os cantos para variar pontos de clique (estrategia comum para widgets com hit-targets assimetricos), os eventos irao para a coordenada errada e o modelo registrara transicoes de um widget que nao foi tocado.

**monkey-root-10. mMonitorNativeCrashes e zerado na primeira iteracao do loop: --monitor-native-crashes vira verificacao one-shot (divergencia do AOSP)**

`src/main/java/com/android/commands/monkey/Monkey.java:1317` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

Dentro do synchronized, 'if (mMonitorNativeCrashes) { mMonitorNativeCrashes = false; ... }' desliga a flag permanentemente apos o primeiro ciclo — no Monkey AOSP a flag nao e limpa e checkNativeCrashes roda todo ciclo. Crashes nativos (relevantes para apps com JNI, categoria que o RVSec instrumenta) passam despercebidos apos o step 1. Mitigado no caminho aperv porque mUseApe forca mIgnoreNativeCrashes=true e o orquestrador nao passa a flag, mas e logica invertida real para quem usar o binario como monkey tradicional.

**monkey-root-11. generateThrottleEvent com --randomize-throttle e base==0 lanca ArithmeticException (modulo por zero)**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:1105` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

Com mRandomizeThrottle e mThrottle>0, o codigo faz 'throttle %= base' usando o PARAMETRO base, nao mThrottle. generateEventsForActionInternal chama generateThrottleEvent(action.getThrottle()) para EVENT_NOP, cujo throttle pode ser 0 → divisao modulo zero → excecao nao tratada mata a corrida (sem tearDown). Latente no caminho aperv (flag nao usada), mas o guard checa a variavel errada — checa mThrottle e divide por base.

**monkey-root-12. getGrantedPermissions retorna requestedPermissions (nome enganoso) e null em RemoteException; null armazenado faz clearPackage/grant recusarem silenciosamente o pacote alvo**

`src/main/java/com/android/commands/monkey/ape/AndroidDevice.java:239` · severidade: baixa · confiança: suspeita · métrica: geral · NOVO

O metodo promete permissoes concedidas mas devolve todas as requestedPermissions do manifest (intencional para re-grant pos pm clear), e em RemoteException devolve null. O construtor de MonkeySourceApe armazena esse null em packagePermissions; depois clearPackage()/grantRuntimePermissions() interpretam null como 'untracked package' e retornam sem agir — EVENT_CLEAN_RESTART degrada para restart sem clear e sem re-grant pelo resto da corrida, sem nenhum erro alem de um wprintln. Sentinela null representando dois casos distintos (falha transitoria vs pacote desconhecido).

**monkey-root-13. Cluster de codigo morto no pacote ape raiz: OnlyAddedUnsaturatedActionFilter (0 usos, comentario invertido), TrivialStateException/NoValidActionException nunca lancadas, onLostFocused/lostFocusedCounter/checkNativeApp sem chamadores**

`src/main/java/com/android/commands/monkey/ape/OnlyAddedUnsaturatedActionFilter.java:38` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

OnlyAddedUnsaturatedActionFilter nao tem nenhum call site e contem comentario invertido ('return false; // Include BACK' — o codigo EXCLUI acoes sem target). TrivialStateException e NoValidActionException nao tem nenhum throw no codebase. Agent.onLostFocused so tem implementacao default (nunca invocado) e MonkeySourceApe.lostFocusedCounter nunca e incrementado — o mecanismo de recuperacao de 'perda de foco' que a interface promete simplesmente nao existe. AndroidDevice.checkNativeApp (com IPackageStatsObserver de asBinder()=null, que falharia se chamado) tambem nao tem chamadores. Nada disso dispara em runtime, mas mascara a leitura de quais mecanismos de recuperacao realmente operam.

**monkey-root-14. ApeActivityController.activityStarting desreferencia intent.getComponent() sem null-check dentro de callback binder do AMS (idem ApeAgent.activityStarting:136)**

`src/main/java/com/android/commands/monkey/Monkey.java:399` · severidade: observacao · confiança: suspeita · métrica: geral · NOVO

Se o AMS entregar um intent sem component (intents implicitos em alguns fluxos de start), o NPE e parcelado de volta ao system_server; o AMS captura apenas RemoteException nesses pontos e, em falha do controller, pode resetar mController para null — desabilitando silenciosamente o enforcement do filtro de pacote pelo resto da corrida (monkey passa a vagar por outros apps sem bloqueio). Cadeia nao rastreada ate um disparo real (na maioria das versoes o intent chega resolvido com component), por isso suspeita.

**monkey-root-15. Fallback de generateClickEventAt clica no centro da TELA quando o node nao intersecta a area visivel — acao executada em widget arbitrario mas registrada no modelo como o widget original**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:359` · severidade: observacao · confiança: suspeita · métrica: ui · NOVO

getVisibleBounds(nodeRect) retorna null quando o retangulo do node nao intersecta os bounds visiveis; o fallback usa AndroidDevice.getDisplayBounds() e clica no centro do display. validateResolvedAction filtra isEmpty/isOutOfRoot antes, mas nodes parcialmente fora da tela ou com bounds obsoletos (arvore capturada antes de um scroll/animacao) passam. O clique atinge outro widget, e a transicao resultante e atribuida a acao original — ruido direto no modelo e nos contadores de cobertura por acao (K26/K28 medem em cima disso).


### A.7 ape/llm/ + reducer/ (varredura rápida)

**llm-reducer-1. Fallback captureViaUiAutomation e codigo morto (androidx.test nunca esta no classpath do app_process) e o caminho primario SurfaceControl.screenshot(Rect,int,int,int) nao existe em API 29+**

`src/main/java/com/android/commands/monkey/ape/llm/ScreenshotCapture.java:68` · severidade: media · confiança: suspeita · métrica: ui · NOVO

O fallback usa Class.forName("androidx.test.platform.app.InstrumentationRegistry"); ape-rv.jar contem apenas classes.dex sem androidx (verificado via unzip -l), logo o Class.forName sempre lanca e o fallback retorna null incondicionalmente. O caminho primario reflete a assinatura SurfaceControl.screenshot(Rect,int,int,int), que foi removida no Android Q — em dispositivos Q (suportados segundo CLAUDE.md) toda captura retorna null, o breaker abre (pos-K31) e o braco LLM degrada silenciosamente para SATA puro. Em API <=28 (AVD RVSec) o primario funciona, o que mascara o problema.

**llm-reducer-2. fixMalformedJson e findMatchingBrace operam sobre a string inteira sem awareness de literais de string JSON, podendo corromper argumentos text ou truncar o candidato**

`src/main/java/com/android/commands/monkey/ape/llm/ToolCallParser.java:129` · severidade: baixa · confiança: suspeita · métrica: ui · NOVO

FIX_LEADING_ZERO reescreve ': .91' -> ': 0.91' mesmo dentro do valor de "text" (muta o texto que sera digitado); o balanceamento de chaves (linhas 137-147) conta '{'/'}' dentro de strings, acrescentando '}' espurio quando o texto contem '{'; e findMatchingBrace (linha 110) fecha o candidato num '}' que esta dentro de uma string, gerando JSON truncado -> parse null -> breaker.recordFailure() penaliza o run. Dispara apenas no fallback XML/JSON-inline (~50% das respostas Qwen3-VL) quando o texto contem chaves ou padroes ':.N'.

**llm-reducer-3. llmTimeoutMs aplicado apenas como connect/read timeout por operacao — sem deadline total, o loop single-threaded do agente pode bloquear por N x 15s numa unica chamada**

`src/main/java/com/android/commands/monkey/ape/llm/SglangClient.java:151` · severidade: media · confiança: suspeita · métrica: ui · NOVO

setReadTimeout limita o intervalo entre reads individuais; um servidor SGLang lento que goteja bytes mantem o loop de leitura (linhas 151-158) vivo indefinidamente, muito alem dos 15s configurados. Como selectAction roda sincrono no loop de eventos do Monkey, cada chamada degenerada consome orcamento de exploracao (agrava o mecanismo K30 de regressao por latencia, que e sobre frequencia de chamadas, nao sobre duracao por chamada nao-limitada).

**llm-reducer-4. catch-all de selectAction nao chama breaker.recordFailure(), unico caminho de falha sem penalidade no circuit breaker**

`src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java:406` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

Screenshot null, chat null e parse null registram falha no breaker, mas uma excecao inesperada no pipeline (linhas 406-409) apenas incrementa nullCount e retorna null. Um defeito persistente que lance nesse caminho re-tentaria o pipeline inteiro (screenshot+encode+prompt) a cada passo elegivel sem nunca abrir o breaker — mesma classe de falha do K31 pre-correcao. Hoje os sub-passos sao todos guardados internamente, entao nao ha rota conhecida que dispare; e um buraco latente de consistencia.

**llm-reducer-5. Variavel begin nunca avanca no loop de main: com multiplos crashes, cada crashLog inclui todos os segmentos anteriores (falta begin = i + 1)**

`reducer/ape/Reducer.java:140` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

crashLog = actionRecords.subList(begin, i+1) com begin fixo em 0 faz o segundo crash em diante processar um log que contem crashes e acoes anteriores: a impressao indexada do log fica errada e o scan reverso por lastNonCrash pode cruzar para o segmento anterior se houver CrashActions adjacentes. Mitigado parcialmente porque reduce() re-deriva firstState no ultimo START. Impacto restrito: reducer/ esta fora de src/main/java, nao e compilado pelo Maven nem entra no ape-rv.jar (verificado: target nao contem Reducer) — ferramenta offline de minimizacao de crash.

**llm-reducer-6. Mensagens de log/erro defeituosas em reduce(): copy-paste 'The first action is expected to be CRASH' para a ULTIMA acao; IllegalArgumentException("Invalid ") truncada; iformat com 2 args sem placeholder**

`reducer/ape/Reducer.java:61` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

Linha 61 reporta 'first action' ao validar a ultima acao do log; linha 91 lanca excecao com mensagem inacabada 'Invalid '; linha 94 passa lastState/firstState a um format string sem %s. Sao defeitos so de diagnostico numa ferramenta nao compilada no jar, sem efeito em execucao de experimento.

**llm-reducer-7. JSON_INLINE_PATTERN definido mas nunca usado (parseJsonInline usa findMatchingBrace) — codigo morto**

`src/main/java/com/android/commands/monkey/ape/llm/ToolCallParser.java:46` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

O Pattern compilado nas linhas 46-49 nao tem nenhuma referencia no arquivo nem no restante do codebase; a extracao inline real e feita por varredura de chaves. Sem efeito funcional, apenas confunde a leitura de qual estrategia de matching esta ativa.

**llm-reducer-8. bestTolerance calculado e atribuido no fallback euclidiano mas nunca lido (dead store); e o retry de long_click aceita qualquer ActionType, inclusive scroll**

`src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java:524` · severidade: observacao · confiança: confirmado · métrica: ui · NOVO

Na passada euclidiana (linhas 522-552) bestTolerance e mantido mas nenhum codigo o consome — dead store inocuo. No retry de long_click sem match (linhas 502-519) o filtro de tipo desaparece por completo: alem de MODEL_CLICK, um MODEL_SCROLL contendo o ponto pode ser retornado como 'long_click', executando acao de tipo diferente da pedida pelo LLM. Nao causa crash (acao continua valida), so degrada fidelidade do steering.


### A.8 Frente 2 — schema <apk>.json

**schema-1. Widgets de janelas DIALOG sao indexados pelo nome da classe do dialog, chave que nunca casa com a activity de runtime — flags MOP de dialogs estruturalmente inalcancaveis**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:307` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · NOVO

parseWindows agrupa TODA janela (ACTIVITY, OPTIONSMENU e DIALOG) por baseActivity(w.name). Para DIALOGs o producer emite name = classe do dialog (ex.: 'android.app.AlertDialog', 'com.google.android.material.bottomsheet.BottomSheetDialog' — verificado em duress e litube), nao a activity hospedeira. Em runtime, com o dialog aberto, newState.getActivity() continua sendo a activity hospedeira, entao getWidget(host, shortId) e activityHasMop(host) nunca alcancam os widgets/flags indexados sob a chave do dialog. Levantamento no corpus: 2.170 janelas DIALOG / 23.103 widgets; 17 apps tem widgets DIALOG flagged (~184, ex.: superuser 42, filemanager 41, pyload 36, litube 18) que jamais podem receber +500/+300 nem contribuir para mopActivities da activity real.

**schema-2. idName vazio ("") e armazenado como chave de lookup e casa com todo widget de runtime sem resource-id — canal de match espurio nos dois sentidos**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:314` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · NOVO

parseWindows so filtra idName==null, mas o producer emite "" (23% dos widgets no corpus legado; 534/1285 nos exemplares novos; null_id=0). Em runtime, GUITreeBuilder:587 usa cacheStringEmptyOnNull(getViewIdResourceName()) e extractShortId devolve "" para nos sem id — logo getWidget(activity,"") retorna o ULTIMO widget sem id parseado da activity. Se um widget flagged com idName="" vencer a ordem de escrita, TODO no sem id (e, via mopBoostWithContainment ±2 niveis, praticamente toda acao) ganha +500/+300; se perder, a flag e clobberada (nos 12 exemplares novos, 6/6 widgets flagged com idName="" foram sobrescritos por "" unflagged — duress.keyboard perde um EditText e um Button flagged, litube 3, keepitup 1). Guardas inconsistentes: ApePromptBuilder:447 e MopScorer.scoreWtg:81 tratam "" como no-match, mas MopScorer.score e ApeAgent.generateInputText:208 (fuzz tipado T1.3) fazem o lookup com "" — duas partes derivando a mesma chave com heuristicas diferentes.

**schema-3. Heuristica de Spinner mapeia MODEL_CLICK para 'itemSelected', mas o producer emite 'select' — token que a normalizacao nao reconcilia**

`src/main/java/com/android/commands/monkey/ape/utils/MopScorer.java:139` · severidade: media · confiança: confirmado · métrica: mop-cobertura · NOVO

eventTypeOf devolve 'itemSelected' para clicks em Spinner; o corpus real de listeners tem eventType ∈ {click:178, select:27, touch:3, item_click:2, editor_action:1} — 'item_selected'/'itemSelected' aparece 0 vezes. normalizeEventType (fix do K25) so remove separadores/caixa: 'itemselected' != 'select', entao a entrada per-eventType nunca casa e cai sempre no agregado match-any. Mascarado na maioria dos casos, mas inverte o resultado quando o widget tem listener click unflagged E listener select/touch/editor_action flagged: a query 'click' encontra a chave 'click'=false e retorna resolved-unflagged (+100 em vez de +500/+300). A discriminacao per-eventType (T1.6/INV-MOP-14) na pratica so existe para 'click'.

**schema-4. Uma unica JSONException em qualquer elemento de qualquer passe descarta o arquivo INTEIRO (return null), sem degradacao por elemento**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:246` · severidade: media · confiança: suspeita · métrica: geral · NOVO

Os 4 passes (reachability, windows, transitions, components) rodam dentro de um unico try; getJSONObject(i) lanca JSONException se um elemento de array tiver tipo inesperado (ex.: um widget serializado como string), e o catch em load() devolve null — jogando fora inclusive todos os dados validos ja parseados. O agente entao roda como SATA puro com apenas um wprintln. Nos 170 exemplares atuais nenhum dispara (tipos uniformes), por isso confidence=suspeita quanto ao gatilho real; o mecanismo esta confirmado no codigo. Um catch por elemento (skip-and-count) preservaria o resto do arquivo.

**schema-5. Toda falha de MopData.load colapsa em null e o braco 'sata_mop' roda silenciosamente como SATA puro — sem fail-fast quando mopDataPath foi explicitamente configurado**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:162` · severidade: media · confiança: confirmado · métrica: geral · NOVO

Arquivo ausente no device, JSON malformado, sentinela 'complete' ausente e mismatch de pacote produzem o mesmo null com apenas Logger.wprintln; nenhum flag de erro chega a camada de experimento e o trace segue emitindo [APE-STEP] normalmente. E exatamente o mecanismo que deixou o K01 (jar defasado) invisivel por um experimento inteiro: o rotulo do braco nao reflete se o MOP esteve ativo. Um modo estrito (abortar ou marcar o trace quando mopDataPath!=null e load==null) tornaria o confound detectavel imediatamente.

**schema-6. Config.mopStrictPackageMatch continua morto no master: unico call site de producao usa load 1-arg, entao a checagem estrita e inalcancavel**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:162` · severidade: media · confiança: confirmado · métrica: geral · CONHECIDO — K24

MopData.load(String,String,String) so rejeita em mismatch se expectedPackage/expectedMainActivity forem nao-nulos; o unico call site de producao e StatefulAgent:162 com MopData.load(Config.mopDataPath) → load(path,null,null). O catalogo marca B9 como corrigido no gh15, mas o master atual nao passa os valores de runtime — ou o fix nao foi mergeado ou regrediu. So testes exercitam o caminho de 3 args (MopDataTest:391-401).

**schema-7. 169/169 exemplares de data/apks sao schema legado (reachesMop/mopMethods, sem 'complete') — o parser atual rejeita todos em bloco via sentinela**

`/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/data/apks:1` · severidade: baixa · confiança: confirmado · métrica: mop-cobertura · NOVO

Levantamento recursivo de chaves: os 169 JSONs (datados 2026-03-31) usam reachesMop/directlyReachesMop/mopMethods e nao tem a chave 'complete'; o parser atual le reachesTarget/directlyReachesTarget/targetMethods e exige complete==true, entao load() devolve null para 100% deles (e mesmo sem a sentinela, bySignature ficaria vazio). O pipeline de experimento nao os usa (aperv-tool le o JSON fresco de task.results_dir, verificado em tool.py:428), mas qualquer script de calibracao/analise offline apontado para data/apks degrada silenciosamente para 'sem MOP'. 7 dos 12 exemplares unicos em results/*/instrumented_apks tambem sao pre-sentinela; apenas os mais recentes (ex.: g7_crash_cryptoapp) tem o schema novo completo.

**schema-8. Javadoc D7 ('*Target so aparece onde o JSON e lido') e falso: o vocabulario Target vaza para ComponentInfo, StatefulAgent e logs [APE-RV]**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:42` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

ComponentInfo.java:28-29 expoe campos publicos reachesTarget/targetMethods; StatefulAgent.java:1011/1020/1031 decide triggering com c.reachesTarget/c.targetMethods e loga 'reachesTarget=true' (linhas 1074/1119); MopData.ReachabilityMethod tambem expoe reachesTarget/directlyReachesTarget consumidos por testes. A fronteira prometida pelo D7 vale para o caminho de widgets (directMop/transitiveMop) mas nao para componentes/reachability. Incoerencia de doc/design, nao de comportamento.

**schema-9. Producer emite janelas com id duplicado e windowsById faz last-write-wins — transitions podem ligar a instancia errada**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:303` · severidade: observacao · confiança: confirmado · métrica: mop-cobertura · NOVO

duress.keyboard tem 6 janelas identicas (id=3349, 'android.app.AlertDialog', DIALOG) e litube repete id 21288; windowsById.put sobrescreve, entao o join de parseTransitions resolve sempre a ultima instancia. Nos casos observados as duplicatas compartilham nome/tipo (impacto ~nulo), mas o parser nao detecta nem loga a colisao; se o producer emitir ids colidentes de janelas distintas, o WTG ligaria origem/destino errados silenciosamente.

**schema-10. WTG-KEY ainda presente no master: wtgTransitions keyed por nome de janela com sufixo '#' mas scoreWtg consulta pela activity base de runtime**

`src/main/java/com/android/commands/monkey/ape/utils/MopScorer.java:84` · severidade: media · confiança: confirmado · métrica: mop-cobertura · CONHECIDO — K20

parseTransitions (MopData.java:468) chaveia por source.name completo (inclui '#OptionsMenu' e nomes de dialog); scoreWtg e o passe WTG de StatefulAgent:1419 consultam com newState.getActivity() (classe base). Arestas com origem em menus/dialogs nunca sao recuperadas para boost de widget. Confirmado inalterado no master (o javadoc de getWtgTransitions:665 ate documenta a assimetria).

**schema-11. PARSER-DROP ainda presente no master: Map<idName> ultima-escrita-vence descarta widgets flagged colidentes entre janelas da mesma activity**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:314` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · CONHECIDO — K02

widgets.put(wd.idName, wd) sem reconciliacao: nos 12 exemplares novos ha 561 sobrescritas em 1285 widgets, incluindo 6 casos flagged→unflagged. Reconfirma o K02 na versao do master (que, alem de dropar, tambem serve matches espurios via a chave "" — ver achado da linha 314 sobre idName vazio).

**schema-12. Visao WTG consome apenas eventos type=='click'; select/touch/item_click/editor_action (~36 eventos no corpus) ficam fora do steering**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:467` · severidade: observacao · confiança: confirmado · métrica: mop-cobertura · NOVO

parseTransitions filtra '"click".equals(e.type)' (INV-WTG-01, por design). O corpus novo tem 28 'select', 4 'touch', 2 'item_click', 2 'editor_action' em transitions[].events — navegacao disparada por spinner/teclado nao gera aresta WTG, perdendo steering para telas MOP alcancadas por esses eventos. Como e invariante documentada, reporto como limitacao de design, nao bug.

**schema-13. handlerReachesTarget/handlerDirectlyReachesTarget: esperados pelo parser, emitidos 0 vezes por qualquer producer do corpus**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:374` · severidade: observacao · confiança: confirmado · métrica: mop-cobertura · CONHECIDO — K14

Confirmacao no master do K14/K16: nenhuma ocorrencia de handlerReachesTarget em 170 exemplares legados nem nos 12 novos; o caminho D8 (producer-supplied wins) e codigo dormant e todo flag deriva do cross-reference bySignature, cujo join exato por assinatura e o gargalo ja documentado (0,43%). O caminho de fallback em si esta correto e null-safe.


### A.9 Frente 3 — log .trace

**trace-1. [APE-STEP] e emitido ANTES de checkRestart: linhas-fantasma de acoes selecionadas mas substituidas por EVENT_RESTART, com cobertura e visited-count ja contabilizados**

`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:325` · severidade: alta · confiança: confirmado · métrica: ui · NOVO

resolveNewAction emite [APE-STEP] e updateStateInternal ja executou markVisited(action), recordActionHistory e moveForward→_coverageTracker.recordInteraction (StatefulAgent:1195) antes de o wrapper aplicar checkInput(checkFuzzing(checkRestart(...))). checkRestart (default on, threshold aleatorio 100-300 passos + requestRestart de estabilidade) descarta a acao e retorna EVENT_RESTART. Resultado: (a) o .trace registra uma acao 'executada' que nunca rodou (analises cmpmop que contam [APE-STEP] como acao executada ficam infladas); (b) o widget fica marcado como interagido no UICoverageTracker e visitado no grafo, suprimindo o coverage-boost e o greedy nesse widget para sempre. Deteccao pos-hoc so e possivel cruzando produce.log (actionType EVENT_RESTART no mesmo agentTimestamp) — fragil e nao documentado.

**trace-2. checkFuzzing(ModelAction) e um OVERLOAD morto (nao override): a protecao anti-fuzz em activities pouco visitadas nunca executa**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:297` · severidade: media · confiança: confirmado · métrica: ui · NOVO

ApeAgent.checkFuzzing(Action origin) e o metodo chamado em ApeAgent:325; StatefulAgent declara checkFuzzing(ModelAction) — assinatura diferente, dispatch estatico escolhe a versao base (grep confirma zero call sites do overload). O guard 'an.getVisitedCount() < fuzzingActivityVisitThreshold → disableFuzzing=true' e codigo morto: o fuzzing (2%/passo, inclui app-switch, rotacao, teclas aleatorias, que abandonam a tela) dispara tambem em activities recem-descobertas, exatamente onde o custo em cobertura e maior. Nenhuma linha de log revela isso pos-hoc (disableFuzzing nao e logado).

**trace-3. decision_source no [APE-STEP] so pode ser SATA/LLM/Budget: os valores MOP/Coverage/WTG/Menu/Fuzz/Component do enum nunca sao atribuidos e a sub-estrategia SATA nao e campo da linha**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1267` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · NOVO

Grep confirma: setDecisionSource so ocorre com SATA (SataAgent:224), Budget (323) e LLM (335/346/357). 'Escolhida por causa do boost' e inexprimivel no trace por construcao. A sub-estrategia (USE_BUFFER/EARLY_STAGE/TRIVIAL_ACTIVITY/EPSILON_GREEDY/NULL) — essencial para distinguir 'boost participou da roleta' (EARLY_STAGE via randomPickWithPriority; EPSILON_GREEDY roleta/desempate) de 'boost irrelevante' (USE_BUFFER, backtrack, Back/Menu unvisited short-circuit em SataAgent:414-427, que retorna ANTES de qualquer roleta) — so existe na linha solta 'Select action %s by strategy %s' (SataAgent:219), sem step=N, exigindo join posicional dentro do bracket '>>>>>>>> begin/end step [N]'. Resposta a Q2: distincao e parcialmente reconstruivel por esse join fragil, nunca causalmente (indice da roleta nao e logado).

**trace-4. Candidatas NAO-escolhidas SAO logadas por passo (printActions com P pos-boost), mas sem decomposicao mop/wtg/cov/menu nem visitedCount — flip de argmax segue irreconstruivel**

`src/main/java/com/android/commands/monkey/ape/model/State.java:282` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · CONHECIDO — K60

Correcao a K60: SataAgent.printStrategy→newState.printActions roda no inicio de selectNewActionNonnull, APOS adjustActionsByGUITree (StatefulAgent:1258-1259), logo cada candidata sai com '[P=total]' ja com boost (Action.resolvedInfo:204). Porem: (1) a decomposicao por mecanismo so existe para a ESCOLHIDA ([APE-STEP]); para as demais nao da para subtrair o boost e recomputar o ranking contrafactual; (2) greedyPickLeastVisited desempata por visitedCount, que nao e impresso (so o flag UNVISITED); (3) as linhas usam Logger.format (prefixo '[APE] ' sem '*** INFO ***'), entao pipelines que filtram por INFO as descartam; (4) nao ha step=N nas linhas — join posicional. Q1: candidatas existem no trace bruto, mas 'o boost mudou o argmax?' nao e respondivel por passo.

**trace-5. Linha 'Create state ...' e emitida a CADA passo (estado novo ou existente): parser que a trate como criacao de estado superconta**

`src/main/java/com/android/commands/monkey/ape/model/Model.java:467` · severidade: media · confiança: confirmado · métrica: geral · NOVO

checkAndAddStateData sempre loga 'Create state %s for GUI tree %s' apos graph.getOrCreateState, inclusive quando o estado ja existia e apenas recebeu mais uma GUITree. A propria descricao do formato do trace ('novo state abstrato') induz o erro. Contagem correta de estados exige dedup por graphId (gNsM) — inferencia indireta nao documentada. Fix minimo: computar isNew (state.getGUITrees().size()==1 apos append) e emitir 'new=true|false' na linha (1 linha alterada, custo zero).

**trace-6. K24 NAO esta corrigido no master: MopData.load continua 1-arg, mismatch de package jamais e logado nem rejeitado; linha 'MopData: loaded' nao permite validar pareamento JSON↔APK pos-hoc**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:162` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · CONHECIDO — K24

StatefulAgent:162 chama MopData.load(Config.mopDataPath) → load(path,null,null); com expectedPackage=null o bloco de mismatch (MopData:224-238) e inalcancavel, entao mopStrictPackageMatch segue inerte e o warning de mismatch nunca aparece no trace — apesar de o catalogo marcar B9 como [corrigido gh15]. Alem disso a linha de load (MopData:240) nao imprime package/mainActivity do JSON, e conta widgets POS-colisao (o drop last-write-wins de K02 e invisivel: nao ha 'parsed X, kept Y, dropped-null-id Z'). Um experimento com JSON errado ou 45% de widgets descartados e indistinguivel de um run saudavel olhando so o .trace.

**trace-7. Cobertura intra-tela irreconstruivel com precisao: linha de Coverage/WTG boost so sai quando boosted>0, denominadores inconsistentes, e getters de telemetria (getTotalElements/getActivityCoverageGap) sem nenhum call site**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1452` · severidade: media · confiança: confirmado · métrica: ui · NOVO

(1) '[APE-RV] Coverage boost' e '[APE-RV] WTG boost' sao condicionais a boosted>0: tela 100% coberta e tela sem alvos validos emitem nada — vies de sobrevivencia na reconstrucao (a linha MOP boost, em contraste, e incondicional: saudavel). (2) O gap= logado usa denominador de widgets registrados (xpath|TYPE, inclui BACK/MENU — UICoverageTracker.getCoverageGap), enquanto boosted/total usa alvos targeted+valid+resolved — numeros nao comparaveis entre si. (3) getTotalElements, getTotalInteractions e getActivityCoverageGap (rollup por activity, ja implementado) tem ZERO call sites: telemetria computada e nunca emitida; nao ha dump final de cobertura UI no master (K59 so no worktree). Q3: reconstrucao exige stitching fragil de Create state [W=][A=], candidatas e linhas condicionais.

**trace-8. [APE-STEP] nao tem wall-clock: atribuir violacoes MOP (timestamps do logcat RVSEC) a um passo exige join de tres arquivos; aperv nao loga nada sobre violacoes**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1266` · severidade: media · confiança: confirmado · métrica: mop-violacoes · NOVO

Nenhum codigo do repo toca a cadeia JavaMOP→logcat RVSEC/RVSEC-COV→contagem (grep RVSEC/logcat: so headers de copyright) — perdas por buffer do logcat, rate-limit do chatty ('identical N lines' colapsa violacoes repetidas) e truncamento ~4KB ficam fora deste repo (limitacao declarada). Do lado do trace, a unica ponte temporal e produce.log (clockTime + agentTimestamp por acao) + o 'Elapsed' do bracket de passo (resolucao 1s): [APE-STEP] em si nao carrega relogio. Fix minimo: acrescentar clock=%d (System.currentTimeMillis()) na linha [APE-STEP] — custo desprezivel, habilita join direto trace↔logcat por timestamp.

**trace-9. Clique com 'Invalid bounds' vira no-op silencioso ja contabilizado: evento nao e enfileirado mas a acao consta como executada no [APE-STEP], no historico e na cobertura**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:404` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

generateClickEventAt retorna sem addEvent quando o ponto calculado nao esta em bounds (linhas 402-406), emitindo apenas um WARNING sem step id; o throttle ainda e enfileirado. A acao ja foi logada em [APE-STEP], appendToActionHistory/produce.log e recordInteraction. Pos-hoc, um widget 'interagido' que nunca recebeu toque so e detectavel casando o WARNING posicional — e a transicao resultante (self-loop espurio) contamina o modelo. Mesma familia do caso bounds==null (linha 360) que clica no centro da TELA em vez do widget.

**trace-10. Decisao de digitar texto (checkInput/inputRate) ocorre apos o [APE-STEP] e o log 'Input text is ...' cai FORA do bracket begin/end step**

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:1246` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

checkInput roda depois da emissao do [APE-STEP] (ApeAgent:325), entao a linha do passo nao distingue click de click+type. O texto digitado aparece em 'Input text is %s' (doInput), mas esse log e emitido em generateEvents, apos o '>>>>>>>> end step [N]' — a associacao ao passo e posicional (entre brackets). produce.log registra inputText no JSON da acao (join por agentTimestamp). Relevante para diagnosticar K19 (taxa efetiva de preenchimento ~42% vs inputRate=0.8) so com o trace: possivel, mas por stitching. Fix minimo: incluir typed=1 e o widget no [APE-STEP] ou mover a decisao de input para antes da emissao.

**trace-11. Budget por activity nao emite telemetria de esgotamento continuado: so ha log quando a navegacao trivial esta disponivel**

`src/main/java/com/android/commands/monkey/ape/utils/ActivityBudgetTracker.java:27` · severidade: baixa · confiança: confirmado · métrica: geral · NOVO

isBudgetExhausted pode ficar true para sempre (budget nunca reseta, por design), mas o unico log e '[APE-RV] Budget exhausted...' em SataAgent:322, emitido apenas quando selectNewActionForTrivialActivity retorna nao-null; no caminho de fallthrough (trivial==null, caso comum em apps pequenos) nada e logado — pos-hoc nao da para saber quantos passos rodaram em regime de budget estourado nem qual fracao das activities esgotou o budget (relevante para explicar loops de login/permission-wall, K57). Fix minimo: no tearDown, uma linha por activity 'budget=X used=Y' (mapa ja existe).


### A.10 Frente 4 — corretude da change (worktree)

**change-correctness-1. Fill deterministico do form-completion e codigo morto: inFormCompletionContext() sempre false em checkInput porque moveForward() ja anulou newState**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:184` · severidade: bloqueante · confiança: confirmado · métrica: mop-cobertura · NOVO

O pipeline e checkInput(checkFuzzing(checkRestart(updateStateInternal()))) (ApeAgent.java:337). updateStateInternal chama moveForward() (StatefulAgent:683) para toda ModelAction, e doMoveForward seta newState=null (StatefulAgent:1195). Quando checkInput roda (ApeAgent:192), o override de inFormCompletionContext le newState==null e retorna false — o ramo 'preencher deterministicamente' NUNCA dispara e o toss(inputRate=0.8) legado continua valendo em 100% dos passos. A metade central da alegacao #5 (INV-FORM-03/INV-INP-04, requisito 'Fill all... deterministically') nao e entregue; nenhum teste cobre o caminho.

**change-correctness-2. Predicado 'unfilled' nunca converge: inputText e transiente por captura de GUITree, entao todo EditText volta a ser 'unfilled' a cada passo**

`src/main/java/com/android/commands/monkey/ape/agent/FormCompletion.java:51` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · CONHECIDO — K39

isUnfilledEditText testa node.getInputText()==null, mas inputText (GUITreeNode.java:83,479) e uma anotacao do agente setada apenas em checkInput/LLM sobre o no da captura corrente; cada nova GUITree cria nos frescos com inputText=null e nada deriva inputText do texto realmente presente na tela (getText). Logo hasUnfilledEditText e permanentemente true em qualquer estado com EditText: campos nunca ficam 'filled', o laco preencher-todos->submeter nao termina, W_FILL e reaplicado para sempre e a exclusao INV-FORM-06 bloqueia o submit do short-circuit MOP indefinidamente. O Data Flow passo 7 do design ('once filled, hasUnfilledEditText becomes false') e uma premissa falsa, agora confirmada estaticamente.

**change-correctness-3. Guard INV-FORM-06 e derrotado pelos proprios caminhos de selecao: o submit MOP-boosted ainda e clicado antes dos campos**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:497` · severidade: alta · confiança: confirmado · métrica: mop-violacoes · NOVO

A exclusao so remove o submit do short-circuit MOP. (a) EARLY_STAGE roda ANTES do ramo epsilon-greedy e usa randomPickWithPriority: o submit carrega mop=500 + W_SUBMIT=100 (+cov), a maior roleta da tela, e e escolhido com probabilidade dominante num form recem-visto. (b) No proprio metodo, quando o short-circuit retorna null, egreedy->greedyPickLeastVisited(ENABLED_VALID) (State.java:124-140) empata todos em visitedCount=0 e desempata por MAIOR priority — escolhe deterministicamente o submit excluido. O cenario do spec 'submit not clicked before fields are filled' nao e garantido por nenhum caminho; os testes cobrem so o parametro excluded de pickBestMopTarget.

**change-correctness-4. Short-circuit MOP e sombreado por EARLY_STAGE: dispara so quando nao ha acao unvisited-by-name alcancavel, janela estreita**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:476` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · NOVO

selectNewActionEpsilonGreedyRandomly so e alcancado depois de USE_BUFFER, TRIVIAL_ACTIVITY e dos dois EARLY_STAGE. findGreedyActionForward coleta exatamente as acoes unvisited-by-name (getGreedyActions) e as consome por roleta (randomPickWithPriority, SataAgent:1072) ou por caminho (findShortestPaths) — ou seja, um alvo MOP nao-visitado numa tela nova quase sempre passa pela roleta de K12, nao pelo argmax novo. O short-circuit cobre apenas alvos ja visitados-por-nome em outro estado mas unvisited neste. A alegacao #2 ('alcancado deterministicamente em vez de roleta') vale so nessa janela residual; K12 e resolvido parcialmente.

**change-correctness-5. One-shot do short-circuit MOP pode ser queimado por restart: acao marcada visitada antes de checkRestart e sem checkDisableRestart**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:681` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · NOVO

markVisited(action) roda em updateStateInternal (StatefulAgent:681) antes de checkRestart poder substituir a acao (ApeAgent:337; restart periodico a cada 100-300 passos). O caminho EARLY_STAGE greedy se protege via checkDisableRestart (SataAgent:1104), mas o novo short-circuit nao chama disableRestart. Se o restart cair no mesmo passo, o alvo MOP unvisited vira 'visited' sem nunca executar e, como o short-circuit exige isUnvisited, aquele alvo perde permanentemente a selecao deterministica (resta so roleta). Agrava o achado Frente-3 de [APE-STEP] pre-checkRestart, que segue nao enderecado.

**change-correctness-6. Candidato a submit e arbitrario entre empates de mopBoost e pode ser um scroll/long-click via containment**

`src/main/java/com/android/commands/monkey/ape/agent/FormCompletion.java:83` · severidade: media · confiança: confirmado · métrica: mop-cobertura · NOVO

mopBoostWithContainment (StatefulAgent:1504) da o MESMO +500 a scrolls/long-clicks/pais/filhos do widget flagged (K53: 100% dos +500 vem de containment), e o loop de selectSubmitCandidate mantem o PRIMEIRO maximo (comparacao estrita >, sem filtro de tipo/click). O 'submit' — que recebe W_SUBMIT e a exclusao INV-FORM-06 — pode ser um MODEL_SCROLL do container, enquanto o clique real no submit continua elegivel ao short-circuit com form vazio. A ordem do array de acoes decide o comportamento.

**change-correctness-7. decision_source segue correlacional em sub-ramos: EARLY_STAGE inclui caminhos que nao consomem priority e EPSILON_GREEDY inclui os short-circuits Back/Menu**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:241` · severidade: media · confiança: confirmado · métrica: geral · CONHECIDO — K42

O rotulo EARLY_STAGE cobre tambem ABA/refillBuffer/global-action/findShortestPaths (SataAgent:657-741,1080-1099), onde a primeira acao do caminho e retornada sem consulta a priority — uma acao com cov/mop boost escolhida por razoes de caminho e rotulada Coverage/MOP, contradizendo o proprio comentario ('branches that actually consume priority'). No ramo EPSILON_GREEDY, back/menu-unvisited retornam antes do egreedy: um MODEL_MENU com menuBoost=250 escolhido por ser unvisited sai como decision_source=Menu. E prova de correlacao (maior boost na acao escolhida), nunca de causalidade — o proprio codigo documenta isso, mas os sub-ramos violam ate a versao fraca.

**change-correctness-8. formBoost e invisivel na atribuicao: nao existe DecisionSource.Form e attributeDecisionSource ignora getFormBoost**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:244` · severidade: media · confiança: confirmado · métrica: geral · NOVO

Um campo escolhido pela roleta por causa de W_FILL=150 (maior boost da acao) e rotulado Coverage (cov=100 e o maior entre os 4 considerados) ou SATA — o mecanismo form nunca aparece em decision_source, entao a influencia da mudanca #5 na selecao nao e mensuravel pelo [APE-STEP] (so a coluna form= do escolhido). OQ5 foi 'resolvida' como reuso do enum, mas o reuso mislabela em vez de omitir. Fuzz/Component continuam valores mortos do enum.

**change-correctness-9. Gateway de OPTIONSMENU sobre-aproximado: qualquer aresta click da base activity que alcanca MOP qualifica o menu (+250)**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:643` · severidade: media · confiança: confirmado · métrica: ui · NOVO

Com a re-chaveacao por base activity (D3a), precomputeMopOptionsMenus perde a distincao 'aresta originada do menu': uma activity cujo caminho MOP e um botao comum (ja boostado +200 pelo WTG) tambem ganha +250 em MODEL_MENU, desviando passos para menus sem caminho MOP e inflando decision_source=Menu. O design declara a sobre-aproximacao deliberada (recall preservado), mas e uma regressao de precisao mensuravel do sinal de menu — a alternativa (indice separado sufixado) foi descartada por P1 sem medir o custo em falsos positivos.

**change-correctness-10. Heuristica de submit: lone-Button ignora o texto (um unico botao 'Cancel'/'Delete' vira submit) e falha em Compose/AndroidX sem 'Button' no className**

`src/main/java/com/android/commands/monkey/ape/agent/FormCompletion.java:112` · severidade: media · confiança: confirmado · métrica: ui · CONHECIDO — K40

Quando buttonCount==1 o botao e retornado SEM consultar a word-list — um form cujo unico Button visivel e 'Cancel' recebe W_SUBMIT nele. No sentido inverso, Compose/material expoem botoes com className generico (sem 'Button') e o word-match exige getText() visivel, entao telas Compose ficam submit=none. isEditText (GUITreeNode:200) usa igualdade exata com android.widget.EditText — cobre AppCompat/TextInputEditText (herdam o accessibility className) mas nao BasicTextField/custom overrides.

**change-correctness-11. Spec base nao sincronizada: mopWeightActivity/+100/INV-MOP-07 ainda mandatorios no spec principal, contradizendo o codigo do worktree**

`/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/specs/mop-guidance/spec.md:124` · severidade: media · confiança: confirmado · métrica: geral · CONHECIDO — K38

openspec/specs/mop-guidance/spec.md linhas 124-216 e 485 ainda exigem o fallback +100 e Config.mopWeightActivity==100, removidos pelo codigo. Os deltas existem em changes/, mas ate o sync/archive o spec vigente contradiz a implementacao; o design da propria mudanca reconhece a dupla-reserva do numero INV-MOP-07 com o gh13 ja arquivado, exigindo reconciliacao semantica manual no archive.

**change-correctness-12. Dump UICOV omite estados LRU-evictados e o activityRollup; sem linha agregada por activity**

`src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java:308` · severidade: baixa · confiança: confirmado · métrica: ui · NOVO

dump() itera apenas stateData vivo; estados alem de coverageMaxStates=2000 (raro em 300s, real em runs longos) somem do dump e o rollup por activity nunca e impresso — getActivityCoverageGap segue sem call-site de log. A tarefa 2.4 (linha na eviccao) foi explicitamente pulada. Fecha K59 no caso comum, mas a leitura por-tela em runs longos e a visao anti-fragmentacao (K27) por activity ficam incompletas.

**change-correctness-13. Comentario obsoleto em parseWindows: descreve activityHasMop como 'the +100 fallback substrate' que a mudanca #2 removeu**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:328` · severidade: baixa · confiança: confirmado · métrica: geral · CONHECIDO — K45

O comentario da propria mudanca #1 referencia o fallback +100 como vigente; #2 (mesmo worktree) o removeu. Viola P4 (current-state comments) e vai confundir a proxima auditoria do parser.

**change-correctness-14. fields= conta acoes, nao widgets: CLICK+LONG_CLICK+scrolls sobre o mesmo EditText recebem W_FILL cada e inflam a telemetria**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1473` · severidade: observacao · confiança: confirmado · métrica: geral · NOVO

isUnfilledEditText aceita qualquer tipo de acao com alvo cujo no e EditText; um unico campo com clique e long-click gera fields=2 e boosta o long-click (que abre menu de contexto, nao digita). Distorce a linha [APE-RV] FORM boost e gasta boost em acoes que nao preenchem.

**change-correctness-15. MopData.load segue 1-arg no unico call-site de producao: strict-match inerte e telemetria de load parcial (sem collided=, parsedWidgets=, package=)**

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:162` · severidade: observacao · confiança: confirmado · métrica: geral · CONHECIDO — K24

A proposta minima da Frente 3 (item 4) pedia expectedPackage real no load e contadores de colisao pre-descarte; a mudanca so adicionou droppedFlaggedNoId. load(Config.mopDataPath) continua sem expectedPackage, entao mopStrictPackageMatch permanece inalcancavel em runtime, e o numero de widgets perdidos por colisao (K02) nao e observavel no trace — so o total pos-colisao.


### A.11 Frente 4 — testes e specs da change (worktree)

**change-tests-1. Guard INV-FORM-06 cobre so o ramo EPSILON_GREEDY; EARLY_STAGE (roleta por prioridade) continua clicando o submit MOP em formulario vazio**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:496` · severidade: alta · confiança: confirmado · métrica: mop-violacoes · NOVO

A cadeia selectNewActionNonnull consome EARLY_STAGE (findGreedyActionForward -> RandomHelper.randomPickWithPriority sobre acoes unvisited-by-name, SataAgent.java:1072) ANTES de selectNewActionEpsilonGreedyRandomly. Na 1a visita a um form MOP, o submit carrega ~752 de prioridade (base+unvisited+mop500+W_SUBMIT100+coverage) vs ~302 por campo, entao a roleta EARLY_STAGE escolhe o submit vazio com probabilidade dominante — exatamente a regressao submit-before-fill que o guard deveria eliminar. O cenario do spec ('selection SHALL proceed so an unfilled EditText action is filled first') nao e garantido pela implementacao. Agravante: W_SUBMIT e aplicado mesmo com campos vazios.

**change-tests-2. Short-circuit MOP (mudanca #2) fica sombreado pelo EARLY_STAGE: acoes unvisited raramente chegam ao ramo onde ele vive**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:471` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · NOVO

O short-circuit exige acao ENABLED_VALID_UNVISITED com mopBoost>0, mas qualquer acao unvisited-by-name e consumida antes pela roleta do EARLY_STAGE (getGreedyActions:630-651 + randomPickWithPriority). O ramo EPSILON_GREEDY so e alcancado quando forward/backward greedy falham, i.e. quase sempre quando ja nao ha unvisited-by-name — sobra apenas o residuo 'visitado por nome em outro estado, unvisited local'. O 'caminho deterministico para o alvo MOP' prometido pela proposal opera numa minoria de decisoes; na maioria o boost continua diluido em roleta (K12 persiste no caminho dominante). Implementado conforme o spec, mas o spec posiciona o mecanismo onde ele pouco dispara.

**change-tests-3. Contexto de formulario nunca converge: inputText vive no GUITreeNode por captura, entao campos digitados voltam a contar como unfilled e o submit fica excluido do short-circuit para sempre**

`src/main/java/com/android/commands/monkey/ape/agent/FormCompletion.java:51` · severidade: alta · confiança: confirmado · métrica: mop-violacoes · CONHECIDO — K39

isUnfilledEditText testa node.getInputText()==null, mas inputText so e setado por checkInput no no da arvore corrente; a proxima captura cria GUITreeNodes novos (nada copia inputText, verificado em GUITreeNode/GUITreeBuilder/MonkeySourceApe). Logo hasUnfilledEditText permanece true em qualquer tela com EditText: (a) re-boost e re-digitacao sem criterio de progresso; (b) a exclusao INV-FORM-06 em selectUnvisitedMopTarget nunca e levantada — o short-circuit MOP jamais seleciona o submit exatamente nas telas de formulario onde os alvos JCA vivem; ele so e clicado via roleta nao guardada.

**change-tests-4. Premissa falsa no drop de widgets sem id: a chave "" ERA alcancavel em runtime, e o drop elimina o unico caminho widget-level para apps 100% sem resource-id**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:359` · severidade: alta · confiança: confirmado · métrica: mop-cobertura · NOVO

O spec/design justifica INV-MOP-20 com 'extractShortId nunca produz "" para um widget real', mas extractShortId retorna "" justamente para nos SEM resourceId ou malformado (MopData:~735), e mopBoostWithContainment (StatefulAgent:1506-1533) chama MopScorer.score com esse "" sem guarda — inclusive para ancestrais/descendentes no containment. Antes, um widget flagged sem id no JSON casava (grosseiramente) com widgets runtime sem id; agora labnex/duress (100% sem id) perdem qualquer boost widget-level, e pos-#2 nao ha mais fallback de activity. Trade-off defensavel (o match "" era uniforme/ruidoso), mas a justificativa do spec e factualmente errada e a perda nao foi decidida conscientemente.

**change-tests-5. isEditText exige className exato 'android.widget.EditText': form-completion inteira e inerte em apps AndroidX/Material/Compose**

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeNode.java:199` · severidade: media · confiança: confirmado · métrica: mop-violacoes · NOVO

AppCompatEditText, TextInputEditText, MaterialAutoCompleteTextView e qualquer campo Compose nao satisfazem o equals exato, entao hasUnfilledEditText=false nessas telas: sem boost de campos, sem fill deterministico, sem guard INV-FORM-06. Nenhum teste cobre classes AndroidX/Compose e o spec da capability nao menciona a limitacao. No corpus de 169 APKs (muitos com AppCompat), a capability pode ser no-op na maioria dos formularios reais — mesmo padrao do problema ja conhecido do heuristic de submit (K40), mas aqui atinge a deteccao raiz.

**change-tests-6. Toss de inputRate vira codigo morto: qualquer EditText unfilled selecionado torna o proprio estado 'form context', e o cenario 'legacy toss preservado' do spec e insatisfazivel**

`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:189` · severidade: media · confiança: confirmado · métrica: geral · NOVO

Em checkInput, se a acao selecionada e um EditText valido/resolvido com getInputText()==null, entao esse mesmo EditText satisfaz hasUnfilledEditText(newState) e inFormCompletionContext()==true — o ramo legacy toss(inputRate) nunca executa para agentes Stateful. O cenario do heuristic-input delta ('single EditText, context false') descreve um estado impossivel. Efeito pratico: fill deterministico global (100% em vez de 80%), mudanca de comportamento em TODAS as telas com EditText, nao so formularios — aplicada igualmente aos dois bracos, mas o spec/design alegam preservacao do comportamento legado que de fato nao existe mais.

**change-tests-7. attributeDecisionSource contamina Menu/MOP: os short-circuits Back/Menu-unvisited dentro do EPSILON_GREEDY ignoram prioridade mas sao atribuidos por maior boost**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:240` · severidade: media · confiança: confirmado · métrica: geral · CONHECIDO — K42

Back/Menu-unvisited (SataAgent:457-470) e o proprio short-circuit MOP retornam acoes escolhidas por regra 'unvisited-first' que nao le priority, porem o chamador loga EPSILON_GREEDY e a atribuicao aplica argmax de boosts. Todo estado novo com MODEL_MENU unvisited e menuBoost=250 (gateway gh13) sera logado decision_source=Menu embora o boost tenha sido irrelevante para a escolha — inflando sistematicamente as contagens Menu (e MOP) que o fair-test vai ler. O disclaimer 'contribution, not decisiveness' cobre o caso roleta, mas aqui nem contribuicao houve.

**change-tests-8. Tasks 2.2 (#2) e 3.4 (#1) marcadas [x] mas os testes prometidos nao existem: gating unvisited e wiring do guard INV-FORM-06 tem cobertura zero**

`src/test/java/com/android/commands/monkey/ape/agent/SataAgentMopShortCircuitTest.java:17` · severidade: media · confiança: confirmado · métrica: geral · NOVO

Os 8 testes cobrem apenas o ranking puro de pickBestMopTarget com 'excluded' passado a mao. 'Visited MOP target not force-picked' (INV-SEL-MOP-01) e 'com EditText unfilled o short-circuit nao pega o submit; preenchido, pega' dependem do filtro ENABLED_VALID_UNVISITED e de hasUnfilledEditText->selectSubmitCandidate em selectUnvisitedMopTarget — nada disso e exercitado. Era host-testavel: ModelAction.resolveAt e publico e GUITreeNode.buildEmptyNode existe (FormCompletionTest ja usa Unsafe/reflection). A justificativa 'device-gated' e superconservadora e mascara exatamente os buracos dos achados #1-#3.

**change-tests-9. attributeDecisionSource sem nenhum teste unitario; validacao 100% deferida para device (tasks 4.2/4.3 abertas)**

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:232` · severidade: baixa · confiança: confirmado · métrica: geral · CONHECIDO — K41

A regra (2 branches, argmax, precedencia MOP>WTG>Menu>Coverage) e logica pura sobre 4 ints e um enum — trivialmente testavel no host, como o proprio padrao dos demais testes novos demonstra. As tarefas de verificacao em emulador continuam [ ]; ate la a distribuicao decision_source que motiva a mudanca #3 esta sem qualquer gate automatizado.

**change-tests-10. Comentario em parseWindows ainda descreve activityHasMop como 'the +100 fallback substrate' — fallback removido pela mudanca #2 (viola P4)**

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:330` · severidade: baixa · confiança: confirmado · métrica: geral · CONHECIDO — K45

O comentario novo escrito pela propria mudanca #0 ('so activityHasMop stays correct (it is the +100 fallback substrate)') contradiz o estado pos-#2, em que activityHasMop sobrevive apenas como predicado de WTG/stateMopDensity/mopReach. Confusao futura garantida para quem auditar o parser.

**change-tests-11. Spec principal ainda manda o fallback +100/mopWeightActivity/INV-MOP-07 que o codigo do worktree removeu; INV-MOP-07 segue double-booked com gh13**

`openspec/specs/mop-guidance/spec.md:124` · severidade: observacao · confiança: confirmado · métrica: geral · CONHECIDO — K38

openspec/specs/mop-guidance/spec.md:124-216 e :485 contradizem MopScorer/Config atuais. Esperado antes do sync/archive das 4 changes, mas a reconciliacao e load-bearing: o design de #2 documenta que a remocao de INV-MOP-07 e 'por semantica, nao por numero' por colisao com o gh13 ja arquivado — se o archive nao seguir essa nota, a spec principal fica incoerente.

**change-tests-12. Casos de borda ausentes nos testes novos: colisao tripla embaralhada, idName ausente (null), precedencia Back/Menu>MOP, heuristica lone-Button/submit-word**

`src/test/java/com/android/commands/monkey/ape/utils/MopDataTest.java:680` · severidade: baixa · confiança: confirmado · métrica: geral · CONHECIDO — K44

Os testes de parser sao bons (JSON sintetico pelo parser real, ordem-independencia nos 2 sentidos), mas falta: (a) colisao direct/transitive/unflagged no mesmo idName em ordem embaralhada (K44); (b) widget flagged com campo idName ausente no JSON (so "" e testado; o parser trata null por outro ramo); (c) nenhum teste pina que Back/Menu-unvisited precedem o short-circuit MOP; (d) FormCompletion lone-Button e submit-word (incl. classes AndroidX) sem cobertura; (e) gap do UICOV formatado %.1f perde precisao (0.333->0.3) sem teste que documente o arredondamento.


## Anexo B — Verificação adversarial: veredito integral dos 20 achados novos bloqueantes/altos

Cada achado abaixo recebeu um agente cético independente instruído a REFUTÁ-LO, lendo o código real e, quando disponível, traces de experimentos em `rvsec/rv-android/results/`. `severidade corrigida` = ajuste pós-refutação.


### B.1 [CONFIRMADO] → severidade corrigida: **alta** — dispatchTrigger deriva o package do ComponentName por substring do className, quebrando o trigger de qualquer componente fora do pacote raiz

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1090` · alegado: alta/confirmado · métrica: mop-cobertura · origem: agent (master)

**Alegação detalhada:** dispatchTrigger faz packageName = className.substring(0, lastDot), mas ComponentName exige o package da APLICACAO (manifest), nao o prefixo Java da classe. Verificado no fixture real test-apks/cryptoapp.apk.json: package=br.unb.cic.cryptoapp, mas componentes vivem em subpacotes (br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity etc.) — o Intent explicito recebe package errado e nao resolve nenhum componente. Broadcast a componente inexistente nao lanca erro, entao a falha e 100% silenciosa (o log '[APE-RV] Triggering...' ainda e emitido). ComponentInfo nao carrega packageName e o `package` do JSON (parseado em MopData.load) nao e propagado. Todo o mecanismo gh11/gh13 T1.4-T1.5 de alcancar MOP em receivers/services/providers so funciona para classes no pacote raiz. Mitigante: componentPercentage default 0.0 (Config.java:178), mas os experimentos gh11 rodaram com 0.05.

**Veredito do refutador:** StatefulAgent.java:1090 derives packageName por substring do className e usa em ComponentName (linha 1092); ComponentInfo.java não tem campo packageName; MopData parseia `package` (MopData.java:185, getPackageName:618) mas dispatchTrigger não o usa. Falha é 100% silenciosa: AndroidDevice.java:434-450 (broadcastIntent) e 456-531 (startService/startActivity via reflection) ignoram o result code do AMS e retornam true; o log '[APE-RV] Triggering' (StatefulAgent.java:1101) sai antes do dispatch. Testes (StatefulAgentTriggerTest.java:19-21) deferem o lado dispatch para smoke nunca executado. Caminho de disparo: único call site SataAgent.java:361-366, gated por componentPercentage (default 0.0, Config.java:178); porém gh11 rodou com default 0.05 quando mopDataPath setado (openspec archive gh11 tasks.md:34) e `git show b412117` prova que a mesma derivação por substring existia no build gh11 — os experimentos gh11 exercitaram o path quebrado para qualquer receiver/service em subpacote. Nuance que não refuta: no fixture cryptoapp.apk.json todos os componentes têm reachesTarget=false (0 receivers/services), logo zero tuples são construídos para ESTE app — o defeito é do mecanismo geral, verificado pela estrutura de subpacotes (br.unb.cic.cryptoapp.messagedigest.* vs package br.unb.cic.cryptoapp). Severidade alta se sustenta para o mecanismo (falha total e silenciosa para componentes fora do pacote raiz sempre que habilitado, e contaminou os runs gh11 com 0.05); mitigante real: hoje default 0.0 e experimentos atuais confirmam 0 triggers (docs/20260622_investigacao_mop.md A-3).


### B.2 [CONFIRMADO] → severidade corrigida: **alta** — rebuildHistory() infla visitedCount de TODAS as arestas a cada rebuild do modelo (dobra e cresce a cada refinamento)

`src/main/java/com/android/commands/monkey/ape/model/Graph.java:1293` · alegado: alta/confirmado · métrica: ui · origem: model (master)

**Alegação detalhada:** rebuildHistory() itera treeTransitionHistory INTEIRO e executa edge.visitedCount++ incondicionalmente. Mas as arestas re-adicionadas no rebuild ja receberam visitedCount++ via markVisited(edge) em addTransition(tt) (Graph.java:429->visitedAt), e as arestas sobreviventes ja tem contagem correta do caminho vivo. Resultado: apos cada Model.rebuild() (chamado em todo refinamento de naming, Model.java:274), cada aresta ganha +N onde N = suas ocorrencias no historico — dobra no 1o rebuild, triplica no 2o, etc. Consumidor verificado: SataAgent.weakActionSubsequenceFilter (linha ~163: strength==0 && visitedCount<3) passa a rejeitar cedo demais arestas fracas pouco visitadas, suprimindo a re-exploracao de transicoes nao-deterministicas; printVis tambem corrompido. Distinto de K07: os timestamps fv/lv acabam corretos (reparados por markVisited na re-adicao em ordem cronologica); o dano persistente e o contador.

**Veredito do refutador:** Confirmado por leitura integral: Graph.java:1283-1293 (rebuildHistory) itera treeTransitionHistory inteiro e faz edge.visitedCount++ incondicional, sem reset em lugar algum (visitedCount só é incrementado: GraphElement.java:63 e Graph.java:1293). No Model.rebuild() (Model.java:254-274), as arestas re-adicionadas já recebem +1 por ocorrência via markVisited em Graph.java:429 (visitedAt→visitedCount++, GraphElement.java:55-63) e as sobreviventes já têm N correto do caminho vivo (Graph.java:397); rebuildHistory então soma +N a todas → 2N no 1º rebuild, 3N para sobreviventes no 2º (arestas re-criadas são objetos novos e ficam em 2N — nuance que não refuta). fv/lv: linhas 1287-1291 são auto-atribuições no-op e os timestamps corretos vêm do markVisited na re-adição em ordem cronológica (histórico ordenado em Model.java:219), distinto de K07 como alegado. Disparo real: evolveModel=true por default (Config.java:57); StatefulAgent.java:697 → Model.resolveNonDeterministicTransitions (Model.java:345) e StatefulAgent.java:822 → actionRefinement → NamingFactory.java:251/512 → model.rebuild() → rebuildHistory — rotineiro em apps reais. Consumidor: SataAgent.java:163 (strength==0 && visitedCount<3) — aresta fraca visitada 2× vira 4 após um rebuild e é excluída imediatamente, suprimindo re-exploração de transições não-determinísticas; também vicia comparações em SataAgent.java:493/502 (arestas antigas infladas vs. novas) e printVis (Graph.java:678-691). Severidade alta sustentada para métrica de exploração de UI: corrupção silenciosa, monotônica e permanente numa heurística central do SATA.


### B.3 [CONFIRMADO] → severidade corrigida: **alta** — Rebuild dupla-conta visitas no ActivityNode: markVisited(source) re-adiciona todo o historico a um contador nunca resetado

`src/main/java/com/android/commands/monkey/ape/model/Graph.java:427` · alegado: alta/confirmado · métrica: ui · origem: model (master)

**Alegação detalhada:** No rebuild (Model.java:272), addTransition(source,action,target,tt) chama markVisited(source,ts) para CADA tree-transition re-adicionada; markVisited(State) propaga para ActivityNode.visitedAt (Graph.java:588,598-601). Os States removidos sao recriados zerados, mas o ActivityNode sobrevive ao rebuild com a contagem viva intacta — cada rebuild soma de novo ~1 visita por tt historica cujo source pertence aquela activity. Consumidores verificados: SataAgent.doABA (linha 502: 'never move to hot activity'), backtrack para activity mais fria (609-612), collectTrivialActivities (mediana/media como threshold, 737-785) e gate de fuzzing (StatefulAgent:310). Activities que sofrem refinamento (telas complexas, tipicamente as com MOP) ficam artificialmente 'quentes' e sao despriorizadas no backtracking — perda direta de cobertura de UI e de alcance de telas MOP.

**Veredito do refutador:** CONFIRMADO em todos os elos. (1) Mecanismo: Model.java:272 chama graph.addTransition(source,action,target,tt) para cada tt histórica; Graph.java:427 markVisited(source,ts) → Graph.java:586-588 propaga para markVisited(activity) → Graph.java:598-601 ActivityNode.visitedAt → GraphElement.java:55-63 visitedCount++ incondicional. (2) ActivityNode sobrevive ao rebuild: Graph.java:101 mapa activities nunca é limpo; Graph.remove (Graph.java:1229-1230) só chama an.removeState(state), sem resetar contadores; ActivityNode.java não tem reset. A assimetria prova o defeito: States removidos são recriados zerados (Model.java:252) e a recontagem é correta para eles, mas a ActivityNode acumula o replay por cima da contagem viva — e States sobreviventes que são source de in-transitions removidas também inflam. (3) Disparo em runtime: StatefulAgent.java:656/687-697 (todo passo, evolveModel default true em Config.java:57) → Model.java:338 resolveNonDeterministicTransitions em NEW_ACTION_TARGET → NamingFactory.java:251/512 model.rebuild(). Refinamento é o loop CEGAR central do APE; a inflação é cumulativa e superlinear (histórico cresce entre rebuilds). (4) Consumidores nas linhas citadas: SataAgent.java:502 (doABA nega mover para activity 'quente'), 609-612 (backtrack prefere activity fria — e mascara o tiebreaker MOP de 612-614, que exige igualdade exata), 745-758 (collectTrivialActivities mediana/média), StatefulAgent.java:310 (gate de fuzzing). Direção do viés confere: activities refinadas (telas complexas/MOP) ficam artificialmente quentes e despriorizadas. Imprecisão menor sem impacto no veredito: a soma é ~1 por tt ligada a states REMOVIDOS (não toda tt da activity), e in-transitions inflam também activities vizinhas. Severidade alta se sustenta: contador sem reset alimentando 4 pontos de decisão do agente principal, com perda direta de cobertura de UI.


### B.4 [CONFIRMADO] → severidade corrigida: **alta** — clearChildren itera NodeList VIVA com i++ e remove apenas metade dos filhos DOM — checkAndRemoveWebView deixa 'nos fantasma' no documento que viram widgets/acoes no modelo

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeNode.java:555` · alegado: alta/confirmado · métrica: ui · origem: tree-events (master)

**Alegação detalhada:** clearChildren faz `for (i=0; i<childNodes.getLength(); i++) removeChild(item(i))` sobre um NodeList vivo do DOM: apos remover item(0) a lista compacta e i++ pula o proximo — sobram os filhos de posicao impar. Chamado por GUITreeBuilder.checkAndRemoveWebView (GUITreeBuilder.java:469) quando um WebView tem >64 descendentes (default, alwaysIgnoreWebView=false). Os filhos logicos (GUITreeNode) sao zerados, mas metade dos Elements permanece no DOM com userData ainda apontando para os GUITreeNodes removidos; Naming.namingInternal (Naming.java:494+) percorre o DOM, entao esses nos fantasma recebem nomes, entram em currentNames/currentNodes do GUITree e viram widgets do State com clickable/scrollable preservados — o agente gasta acoes clicando em conteudo web que o filtro tentou descartar, e o modelo diverge da arvore logica. descendantCount tambem nao e recalculado.

**Veredito do refutador:** GUITreeNode.java:551-560: clearChildren itera NodeList viva do DOM com i++ removendo item(i) — apos remover item(0) a lista compacta e o proximo e pulado; sobram os filhos de indice impar COM subarvores inteiras (so filhos diretos sao iterados), e descendantCount/height nao sao recalculados. Disparo real: GUITreeBuilder.java:458-459 chama checkAndRemoveWebView quando !alwaysIgnoreWebView (default false, Config.java:73) e node.isWebView(); linha 466-469 chama clearChildren se descendantCount>64 (Config.java:75). Os Elements filhos ja estao anexados nesse momento (GUITreeNode.addChild:253-256 faz domNode.appendChild durante a recursao em GUITreeBuilder.java:430-456, antes da linha 458). Consequencia confirmada: Naming.namingInternal (Naming.java:494-540) percorre o DOM (getChildNodes, linha 529), recupera os GUITreeNodes fantasma via userData (linha 516; setDomNode em GUITreeNode.java:528-533 mantem o ponteiro), nomeia-os (linha 520) e eles entram no NamingResult → setCurrentNaming (GUITreeBuilder.java:183) → StateKey.getWidgets → State constroi ModelActions por widget com clickable/scrollable preservados (State.java:57-68, 216-222 via NamerFactory.decodeActions). Tentativas de refutacao falharam: o document e sempre criado no caminho live (GUITreeBuilder.java:219); o naming nao usa a arvore logica; os fantasmas nao causam excecao (os filtros XPath dos namelets ainda os casam pois seguem no documento). Unica ressalva: a frequencia depende do app expor WebView com >64 descendentes na arvore de acessibilidade (comum em apps hibridos/ads/OAuth; possivelmente raro no benchmark cripto do RVSEC) — mas quando dispara a corrupcao da abstracao de estado e deterministica, sustentando severidade alta para a metrica ui.


### B.5 [CONFIRMADO] → severidade corrigida: **media** — fillNode nunca captura AccessibilityNodeInfo.isPassword() — setIsPassword tem ZERO call sites, entao GUITreeNode.isPassword() e sempre false e a deteccao de campo de senha do InputValueGenerator (prioridade 1 da spec) esta morta

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeBuilder.java:582` · alegado: alta/confirmado · métrica: ui · origem: tree-events (master)

**Alegação detalhada:** fillNode copia checked/enabled/checkable/clickable/longClickable/scrollable/focusable/focused mas omite info.isPassword(); grep confirma que setIsPassword nao e chamado em lugar nenhum. InputValueGenerator.detectCategory (utils/InputValueGenerator.java:79,104) usa node.isPassword() como sinal de maior prioridade — nunca dispara; so resta o fallback por keyword em resourceId/contentDesc, que falha em apps ofuscados (R8 remove resource names) e campos sem id. Campos de senha recebem string GENERIC aleatoria → logins nunca completam → paredes de login (K57: ~16% dos runs presos) permanecem, bloqueando telas profundas onde vivem operacoes MOP. O atributo XML 'password' gravado por fillElement (linha 565) tambem e sempre 'false'.

**Veredito do refutador:** Alegacao factualmente correta: fillNode (GUITreeBuilder.java:582-606) copia checked/enabled/checkable/clickable/longClickable/scrollable/focusable/focused mas nunca chama info.isPassword(); setIsPassword (GUITreeNode.java:352) tem zero call sites em todo src/ (main e test), logo GUITreeNode.isPassword() e sempre false e fillElement (GUITreeBuilder.java:565) sempre grava password="false". O caminho dispara em runtime: ApeAgent.updateState:325 → checkInput:185-195 (inputRate=0.8, Config.java:64) → generateInputText:203-215 → InputValueGenerator.generateForNode:166-172 → detectCategory:79/104 com isPassword sempre false — prioridade 1 da spec e codigo morto; resta so o fallback por keyword em resourceId/contentDesc. Severidade rebaixada para media por dois atenuantes que o achado ignora: (1) o caminho gh13 T1.3 (ApeAgent.java:204-212, fuzzInputTyped=true default) detecta senha via inputType/hint estaticos (TypedInputGenerator.java:34,64 — "Password"/"senha"), cobrindo apps instrumentados nao-ofuscados (embora tambem morra sob ofuscacao, pois getWidget usa o short resourceId); (2) a cadeia causal "GENERIC → logins nunca completam → K57 desbloqueado" e superestimada: PASSWORD_VALUES ("Test1234!", InputValueGenerator.java:48-50) sao credenciais arbitrarias, nao validas — capturar isPassword ajudaria apenas validacao client-side de formato e fluxos registro-depois-login, nao autenticacao real. O defeito e real e o flag runtime e o unico sinal resiliente a ofuscacao, mas o impacto alegado nao esta demonstrado.


### B.6 [CONFIRMADO] → severidade corrigida: **media** — setText nao sincroniza o atributo @text do DOM (ao contrario de setClickable/setIndex/setClassName) — computeAndSetImageText muta o texto DEPOIS da criacao do Element, e o naming por XPath ve valores diferentes no documento vivo vs reconstruido

`src/main/java/com/android/commands/monkey/ape/tree/GUITreeNode.java:325` · alegado: alta/confirmado · métrica: geral · origem: tree-events (master)

**Alegação detalhada:** Em buildNodeAndXmlFromNodeInfo, os Elements sao criados (fillElement grava text='') e SO DEPOIS computeImageText (default true) chama computeAndSetImageText → setText('#hash') no no, sem atualizar o DOM. TextNamer.naming(node) usa node.getText() → o Name carrega '#hash', e NamerFactory:214 deriva o XPath do namelet de refinamento de name.toXPath() com [@text="#hash"] (TextName.appendXPathLocalProperties). Naming.select avalia esse XPath sobre o Document: no documento vivo @text='' → 0 matches (refinamento silenciosamente inerte); mas GUITree.releaseLoadedData roda a CADA acao consumida (StatefulAgent:684) e getDocument() reconstroi o DOM via fillElement lendo node.getText() → agora @text='#hash' casa. O mesmo GUITree nomeia diferente antes/depois do release do documento → refinamento inconsistente, nao-determinismo espurio e risco de IllegalStateException 'Cannot find widget' em pickNodes no caminho quente.

**Veredito do refutador:** Mecanismo confirmado ponta a ponta. (1) GUITreeBuilder.java:218-233 cria os Elements primeiro (fillElement:552 grava @text a partir de node.getText(), ainda '') e SÓ DEPOIS chama computeImageText (linha 228; Config.computeImageText default true em Config.java:80); computeAndSetImageText → setText("#hash") em GUITreeNode.java:637, e setText (GUITreeNode.java:325-327) não sincroniza o domNode — ao contrário de setClickable (:360-365), setIndex (:487-495) e setClassName (:303-311). (2) TextNamer.java:127-128 nomeia por node.getText() → Name carrega '#hash'; TextName.appendXPathLocalProperties (TextNamer.java:91-97) emite [@text="#hash"]; NamerFactory.java:213-217 deriva o XPath do namelet de refinamento (usado em NamingFactory.java:362/382, 729-730, 1226/1237); Naming.select (Naming.java:453-464) e namingInternal (:494-540) selecionam o namer via XPath sobre o Document — no doc vivo @text='' → namelet refinado nunca casa → nó cai no namelet base → refinamento inerte em árvores recém-capturadas. (3) Disparo em runtime real: StatefulAgent.java:634 captura bitmap incondicionalmente a cada passo; StatefulAgent.java:683-685 chama GUITree.releaseLoadedData() a cada ação consumida; GUITree.getDocument() (GUITree.java:196-203) reconstrói via fillElement lendo getText()='#hash' → doc reconstruído casa o XPath; avaliações de refinamento/rebuild usam tree.getDocument() (AbstractNamingManager.java:120/133; NamingFactory.java:256/517) → divergência vivo vs reconstruído real. O caminho até 'Cannot find widget' (GUITree.java:140/154/168 via State.resolveAction:365) propaga sem catch até Monkey.main (ApeAgent.java:352 rethrow; MonkeySourceApe.java:1290 só captura StopTestingException) — risco plausível, não rastreei sequência garantida. Correções ao achado: (a) o cache treeToNamingResult (Naming.java:466-482) congela o resultado por (Naming, GUITree), então a MESMA árvore sob o MESMO Naming não renomeia após o release — a divergência se manifesta entre árvores equivalentes (viva vs reconstruída) e para a mesma árvore sob Namings novos (avaliação de candidato/rebuild), o que atenua a frase mais forte do achado; (b) o gatilho exige ImageButton acionável folha com text vazio E cadeia de ≥2 refinamentos (primeiro atribuindo namer com TEXT ao widget, depois derivando namelet do Name dele) — real em execuções longas, mas não a cada passo; (c) comportamento herdado do APE upstream (ETH), não introduzido pelo fork. Efeito primário confirmado: refinamento silenciosamente inerte em árvores vivas + estados espúrios/não-determinismo recorrente; crash é risco secundário plausível. Por isso rebaixo de alta para media.


### B.7 [CONFIRMADO] → severidade corrigida: **media** — Fallback de generateClickEventAt: se os bounds do widget nao intersectam a area visivel, o clique e executado no CENTRO DA TELA e o modelo registra a acao como se tivesse acertado o widget pretendido

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:359` · alegado: alta/confirmado · métrica: ui · origem: tree-events (master)

**Alegação detalhada:** getVisibleBounds(nodeRect) retorna null quando Rect.intersect falha (widget com bounds vazios, rolado para fora, arvore obsoleta durante animacao/dialog); generateClickEventAt entao usa AndroidDevice.getDisplayBounds() inteiro e clica no exactCenter — um ponto arbitrario que pode acionar OUTRO widget (ou nada). A transicao resultante e atribuida a acao original no modelo → arestas falsas, cobertura de UI creditada errada e steering MOP avaliado sobre transicoes que nunca ocorreram. So um warning 'Error to fetch bounds' e emitido. Arquivo fora dos 2 pacotes do escopo, mas e o consumidor direto de GUITreeNode.getBoundsInScreen no caminho de execucao (foco explicito: 'coordenadas de clique fora do widget').

**Veredito do refutador:** Codigo confirma a alegacao literalmente: MonkeySourceApe.java:291-298 (getVisibleBounds retorna null quando Rect.intersect falha) e :357-367 (fallback para AndroidDevice.getDisplayBounds() + clique no exactCenter, pois useRandomClick=false na linha 85); apenas o warning 'Error to fetch bounds.' e emitido. A transicao resultante e atribuida a acao original sem qualquer verificacao: StatefulAgent.java:916 (model.addTransition(currentState, currentAction, newState, ...)). Callers reais: MonkeySourceApe.java:853/860 (MODEL_CLICK/MODEL_LONG_CLICK com getResolvedNode().getBoundsInScreen()). Disparo em runtime COMPROVADO em traces de experimento reais (rvsec/rv-android/results/baseline_v2): 260 ocorrencias em 17 de 1513 traces (~1,1% dos runs); exemplo concreto no trace de trickytripper: MODEL_CLICK selecionado em node com bounds [112,-92,250,-1] (totalmente fora da tela, header de DatePicker reportado visibleToUser=true) seguido do warning e continuacao normal da exploracao. Caso extremo: imagepipe run 3 tem 67 warnings em 509 steps (~13% dos steps), repetido nas 3 execucoes do app (ImageButtons tools_redo/tools_paint com Y negativo). O filtro excludeInvisibleNode=true (Config.java:77, GUITreeBuilder.java:442) mitiga mas nao elimina, pois apps reais reportam nodes clicaveis visible-to-user com bounds off-screen. Severidade corrigida de alta para media: mecanismo e corrupcao do modelo confirmados e sistematicos nos apps afetados, mas prevalencia global baixa (~1,1% dos runs) limita a distorcao agregada das metricas de UI/MOP.


### B.8 [CONFIRMADO] → severidade corrigida: **alta** — waitForActivity sem timeout: se a activity esperada nunca chega ao topo, a corrida inteira fica presa em throttles de 100ms ate o fim do orcamento

`src/main/java/com/android/commands/monkey/MonkeySourceApe.java:1190` · alegado: alta/confirmado · métrica: ui · origem: monkey-root (master)

**Alegação detalhada:** generateActivityEvents seta waitForActivity=true; o unico ponto que o limpa e checkAppActivity quando um pacote permitido esta no topo. Se o app nunca chega ao foreground (crash antes da primeira activity com processo morto por mKillProcessAfterError, launcher-trampoline para outro pacote, ou o race em que GrantPermissionsActivity aparece dentro da janela de 2000ms e o controller faz stopPackages), cada getNextEvent cai em 'still waiting... another 100ms' e enfileira so um throttle — sem contador, sem restart, sem desistencia. startRandomMainApp nunca e re-disparado porque o branch de bloqueio exige !waitForActivity. Um unico episodio consome todo o --running-minutes com 0 acoes; consistente com o padrao de runs com <=2 states do K57, mas mecanismo distinto e nao documentado.

**Veredito do refutador:** Codigo confirma integralmente o mecanismo. (1) MonkeySourceApe.java:1162 seta waitForActivity=true em generateActivityEvents; o unico ponto que o limpa e MonkeySourceApe.java:1185 (checkAppActivity, quando pacote permitido esta no topo) — grep completo nao acha outro write. (2) MonkeySourceApe.java:1190-1193: com waitForActivity=true e topo nao-permitido, enfileira so um throttle de 100ms e retorna, sem contador/deadline/relaunch; como getNextEvent (1283-1299) chama checkAppActivity antes de hasEvent(), esse throttle bloqueia generateEvents() — agente e resgate LLM nunca rodam. startRandomMainApp so e alcancavel via cn==null (AndroidDevice.java:141-148: getTasks vazio, na pratica nunca — launcher e sempre task) ou via branch que exige !waitForActivity (1204-1206). (3) Monkey.java:1279-1283 so encerra por mEndTime; throttles nao contam (1404); mAbort e neutralizado em modo continuo (1352-1354); 1269 forca mKillProcessAfterError=true com --running-minutes. Gatilho concreto no codigo: Monkey.java:404-412 — GrantPermissionsActivity dispara stopPackages() → forceStopPackage (AndroidDevice.java:356), removendo a task do app; se ocorre na janela de 2000ms pos-launch, topo vira launcher (nao permitido) e o run inteiro vira throttles de 100ms. Ressalvas: no crash-antes-da-primeira-activity, RunningTaskInfo.topActivity pode ainda reportar a task morta e limpar a flag (caindo na recuperacao nullInfoCounter>10 → stopTopActivity, linhas 797-806), entao nem todo crash trava; e a correlacao com K57 nao e verificavel neste repo (frequencia em escala nao quantificada). Impacto por ocorrencia (run inteiro com 0 acoes) sustenta severidade alta, mas nao bloqueante por nao disparar em todo run.


### B.9 [REFUTADO] → severidade corrigida: **baixa** — Threshold acumulativo hardcoded totalBadStates>100 encerra a corrida inteira antecipadamente via StopTestingException, sem nunca resetar

`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:348` · alegado: alta/confirmado · métrica: ui · origem: monkey-root (master)

**Alegação detalhada:** updateStateWrapper incrementa totalBadStates a cada BadStateException ('No available action on the current state', SataAgent:403/StatefulAgent:1576) e lanca StopTestingException quando o acumulado da corrida passa 100. getNextEvent captura, limpa a fila e retorna null; runMonkeyCycles interpreta null como fim e sai do loop — mesmo em modo --running-minutes, que so protege contra mAbort. Apps com muitas telas sem acoes validas (WebView, telas vazias, dialogs de sistema) queimam o limite em minutos e perdem o resto do orcamento de exploracao; o contador nunca decai nem reseta apos recuperacao bem-sucedida. O tearDown roda (saida 'limpa'), entao o corte fica invisivel no trace exceto pela mensagem 'Too many bad states'.

**Veredito do refutador:** Mecanica do codigo confirmada literalmente: totalBadStates e acumulativo e nunca resetado (ApeAgent.java:101,337; unico incremento, sem decaimento), lanca StopTestingException em >100 (ApeAgent.java:348-349); getNextEvent captura e retorna null (MonkeySourceApe.java:1287-1293); com mCountEvents=true por default em modo ape (Monkey.java:118,725-738), null causa break em runMonkeyCycles (Monkey.java:1423) mesmo com --running-minutes, e tearDown roda (Monkey.java:780-782), saida 'limpa'. POREM o cenario de disparo alegado e falso, o que refuta o achado como severidade alta: (1) telas sem acoes validas NAO lancam BadStateException — todo State sempre contem MODEL_BACK e MODEL_MENU (State.java:63-66) que validateResolvedAction aceita incondicionalmente (MonkeySourceApe.java:615-618); updateStateInternal sempre chama validateAllNewActions (StatefulAgent.java:636) e adjustActionsByGUITree da prioridade >=8 a back/menu (StatefulAgent.java:1313-1314), logo selectNewActionEpsilonGreedyRandomly (SataAgent.java:414-435) sempre retorna no minimo BACK e os throw sites (SataAgent.java:403, StatefulAgent.java:1576) sao efetivamente inalcancaveis no fluxo SATA usado pelo aperv (--ape sata/sata_mop); resta apenas uma corrida de nao-determinismo estreita em RandomAgent.handleNullAction (re-resolucao randomica de node em State.resolveAction:365-366), modo nao usado nos experimentos. (2) Evidencia empirica: 310 execucoes reais de 600s (rvsec/rv-android/results/aperv_precal_macro/trial_*/**/*.trace, que capturam o stdout do APE — 'begin step' presente) tem ZERO ocorrencias de 'Bad state'/'No available action'/'Too many bad states' — o contador nunca incrementou sequer uma vez. O mecanismo existe como landmine latente hardcoded (justifica severidade baixa), mas 'apps queimam o limite em minutos e perdem o orcamento' nao se sustenta nem no codigo nem nos dados.


### B.10 [CONFIRMADO] → severidade corrigida: **media** — Widgets de janelas DIALOG sao indexados pelo nome da classe do dialog, chave que nunca casa com a activity de runtime — flags MOP de dialogs estruturalmente inalcancaveis

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:307` · alegado: alta/confirmado · métrica: mop-cobertura · origem: schema (master)

**Alegação detalhada:** parseWindows agrupa TODA janela (ACTIVITY, OPTIONSMENU e DIALOG) por baseActivity(w.name). Para DIALOGs o producer emite name = classe do dialog (ex.: 'android.app.AlertDialog', 'com.google.android.material.bottomsheet.BottomSheetDialog' — verificado em duress e litube), nao a activity hospedeira. Em runtime, com o dialog aberto, newState.getActivity() continua sendo a activity hospedeira, entao getWidget(host, shortId) e activityHasMop(host) nunca alcancam os widgets/flags indexados sob a chave do dialog. Levantamento no corpus: 2.170 janelas DIALOG / 23.103 widgets; 17 apps tem widgets DIALOG flagged (~184, ex.: superuser 42, filemanager 41, pyload 36, litube 18) que jamais podem receber +500/+300 nem contribuir para mopActivities da activity real.

**Veredito do refutador:** Codigo confirmado: MopData.java:300-321 (parseWindows) agrupa TODA janela por baseActivity(w.name), e baseActivity (MopData.java:732) so remove sufixo '#'; para DIALOGs o producer emite name = classe do dialog (verificado nos JSONs reais: 'android.app.AlertDialog' em duress.keyboard_51, 'com.google.android.material.bottomsheet.BottomSheetDialog' em litube). Runtime confirmado: o scoring usa activity = newState.getActivity() (StatefulAgent.java:1368), derivado de AndroidDevice.getTopActivityComponentName() (MonkeySourceApe.java:284 → GUITree.java:82), que permanece a activity hospedeira com dialog aberto — logo getWidget (MopData.java:642) e activityHasMop (MopData.java:649) nunca alcancam as chaves de classe-de-dialog; +500/+300 e o fallback +100 sao estruturalmente inalcancaveis (mesmo em ApeAgent.java:208 e ApePromptBuilder.java:446-469). Levantamento reproduzido: os numeros do achado (~2170/23103, 17 apps, ~184) batem com o corpus LEGADO rvsec/rv-android/data/apks (2132/22749, 16 apps, 166: superuser 42, filemanager 41, pyload 36) — mas esses arquivos usam chave reachesMop e nao tem sentinel 'complete:true', entao MopData.load (linha 178) os rejeita inteiros; no corpus gh60 carregavel em runtime (out/sweep_20260604, 169 arquivos complete:true) o impacto real e 5/169 apps (~3%) com 86 widgets DIALOG flagged (filemanager 44, medtimer 22, litube 9). Ha mitigacao parcial nao citada: o boost WTG ainda dispara — mopActivities contem a classe do dialog e scoreWtg (MopScorer.java:86) casa activityHasMop(t.targetActivity) para transicoes activity→DIALOG (litube: 15), dando +200 (mopWeightWtg, Config.java:167) ao widget que ABRE o dialog; perde-se apenas a orientacao dentro do dialog e o fallback de activity. Defeito real, estrutural e silencioso, porem com abrangencia menor que a alegada e compensacao parcial via WTG → severidade corrigida de alta para media.


### B.11 [CONFIRMADO] → severidade corrigida: **alta** — idName vazio ("") e armazenado como chave de lookup e casa com todo widget de runtime sem resource-id — canal de match espurio nos dois sentidos

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:314` · alegado: alta/confirmado · métrica: mop-cobertura · origem: schema (master)

**Alegação detalhada:** parseWindows so filtra idName==null, mas o producer emite "" (23% dos widgets no corpus legado; 534/1285 nos exemplares novos; null_id=0). Em runtime, GUITreeBuilder:587 usa cacheStringEmptyOnNull(getViewIdResourceName()) e extractShortId devolve "" para nos sem id — logo getWidget(activity,"") retorna o ULTIMO widget sem id parseado da activity. Se um widget flagged com idName="" vencer a ordem de escrita, TODO no sem id (e, via mopBoostWithContainment ±2 niveis, praticamente toda acao) ganha +500/+300; se perder, a flag e clobberada (nos 12 exemplares novos, 6/6 widgets flagged com idName="" foram sobrescritos por "" unflagged — duress.keyboard perde um EditText e um Button flagged, litube 3, keepitup 1). Guardas inconsistentes: ApePromptBuilder:447 e MopScorer.scoreWtg:81 tratam "" como no-match, mas MopScorer.score e ApeAgent.generateInputText:208 (fuzz tipado T1.3) fazem o lookup com "" — duas partes derivando a mesma chave com heuristicas diferentes.

**Veredito do refutador:** Todos os elementos verificados no código. (1) MopData.java:313-315 filtra só idName==null e armazena "" como chave num LinkedHashMap last-write-wins — clobbering bidirecional confirmado; extractShortId (MopData.java:689-693) devolve "" para null ou sem ":id/". (2) GUITreeBuilder.java:587 + StringCache.java:63-66 dão resourceID="" a todo nó sem id; MopScorer.score (MopScorer.java:35-55) faz getWidget(activity,"") sem guarda e é chamado no hot path por mopBoostWithContainment (StatefulAgent.java:1469-1506, nó + ancestrais/descendentes ±2) a cada passe de scoring de estado novo (StatefulAgent.java:1367-1389). (3) Guardas inconsistentes confirmadas: ApePromptBuilder.java:447/468 e MopScorer.scoreWtg:81 tratam "" como no-match; MopScorer.score e ApeAgent.java:208 não. (4) Gatilho empírico: test-apks/cryptoapp.apk.json tem 21/51 widgets (41%) com idName=="" e null_id=0 — o producer emite "" de fato. Nuance que não refuta: no fixture verificável nenhum widget "" é flagged (canal +500 espúrio é latente, dependente de ordem de emissão), e o clobber degrada para o fallback de atividade +100 (MopData.java:317-319 popula mopActivities independente de idName), não para 0; os 6/6 clobbers citados (duress.keyboard/litube/keepitup) estão em exemplares fora deste repo e não foram re-verificáveis. Severidade alta se sustenta: ambos os lados do defeito corrompem exatamente o sinal mop-cobertura no caminho de scoring por passo, e um widget flagged com idName="" nunca pode casar corretamente — só espúria ou perdidamente.


### B.12 [CONFIRMADO] → severidade corrigida: **media** — [APE-STEP] e emitido ANTES de checkRestart: linhas-fantasma de acoes selecionadas mas substituidas por EVENT_RESTART, com cobertura e visited-count ja contabilizados

`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:325` · alegado: alta/confirmado · métrica: ui · origem: trace (master)

**Alegação detalhada:** resolveNewAction emite [APE-STEP] e updateStateInternal ja executou markVisited(action), recordActionHistory e moveForward→_coverageTracker.recordInteraction (StatefulAgent:1195) antes de o wrapper aplicar checkInput(checkFuzzing(checkRestart(...))). checkRestart (default on, threshold aleatorio 100-300 passos + requestRestart de estabilidade) descarta a acao e retorna EVENT_RESTART. Resultado: (a) o .trace registra uma acao 'executada' que nunca rodou (analises cmpmop que contam [APE-STEP] como acao executada ficam infladas); (b) o widget fica marcado como interagido no UICoverageTracker e visitado no grafo, suprimindo o coverage-boost e o greedy nesse widget para sempre. Deteccao pos-hoc so e possivel cruzando produce.log (actionType EVENT_RESTART no mesmo agentTimestamp) — fragil e nao documentado.

**Veredito do refutador:** Confirmado por leitura direta: ApeAgent.java:325 aplica checkInput(checkFuzzing(checkRestart(updateStateInternal(...)))) — updateStateInternal (StatefulAgent.java:669-673) já emitiu [APE-STEP] (resolveNewAction, StatefulAgent.java:1266-1279), executou getGraph().markVisited(action) (671; Graph.java:564-573 move a ação de unvisitedActions para visitedActions, sem reversão), recordActionHistory (672) e moveForward→_coverageTracker.recordInteraction (StatefulAgent.java:1195; count=1 desativa para sempre o gate count==0 do coverage boost em StatefulAgent.java:1446) antes de checkRestart (ApeAgent.java:241-262) descartar a ação e retornar getStartAction(EVENT_RESTART/EVENT_CLEAN_RESTART). Dispara em execução real: SataAgent extends StatefulAgent extends ApeAgent; ape.checkRestart default true (Config.java:61), threshold aleatório 100-300 (Config.java:62-63) + requestRestart via onStateStable>50/onActivityStable>200/onGraphStable (StatefulAgent.java:1156-1161, 941-957), consumido no mesmo passo. startNewEpisode→resetTrace (StatefulAgent.java:467-490) só limpa ponteiros do agente, não desfaz grafo/coverage. Detecção pós-hoc de fato exige cruzar produce.log (MonkeySourceApe.java:893-895 grava a ação final com o mesmo agentTimestamp). Severidade ajustada para media: frequência ~1 fantasma por 100-300 passos (~0,3-1% das linhas [APE-STEP], inflação simétrica entre braços cmpmop); recordActionHistory é só o ring buffer LLM de 5 entradas e no-op sem _llmRouter (StatefulAgent.java:1595-1598); action-history.log/produce.log registram corretamente EVENT_RESTART; por restart apenas 1 widget×estado perde o +5 unvisited e o coverage boost (permanece selecionável via roleta), e nos restarts por estagnação o boost já estava decaído (StatefulAgent.java:1439). Mecanismo real e não documentado, mas impacto limitado — alta é inflada.


### B.13 [CONFIRMADO] → severidade corrigida: **alta** — decision_source no [APE-STEP] so pode ser SATA/LLM/Budget: os valores MOP/Coverage/WTG/Menu/Fuzz/Component do enum nunca sao atribuidos e a sub-estrategia SATA nao e campo da linha

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1267` · alegado: alta/confirmado · métrica: mop-cobertura · origem: trace (master)

**Alegação detalhada:** Grep confirma: setDecisionSource so ocorre com SATA (SataAgent:224), Budget (323) e LLM (335/346/357). 'Escolhida por causa do boost' e inexprimivel no trace por construcao. A sub-estrategia (USE_BUFFER/EARLY_STAGE/TRIVIAL_ACTIVITY/EPSILON_GREEDY/NULL) — essencial para distinguir 'boost participou da roleta' (EARLY_STAGE via randomPickWithPriority; EPSILON_GREEDY roleta/desempate) de 'boost irrelevante' (USE_BUFFER, backtrack, Back/Menu unvisited short-circuit em SataAgent:414-427, que retorna ANTES de qualquer roleta) — so existe na linha solta 'Select action %s by strategy %s' (SataAgent:219), sem step=N, exigindo join posicional dentro do bracket '>>>>>>>> begin/end step [N]'. Resposta a Q2: distincao e parcialmente reconstruivel por esse join fragil, nunca causalmente (indice da roleta nao e logado).

**Veredito do refutador:** ModelAction.java:42-44 declara 9 valores de DecisionSource, mas grep em todo src/ mostra setDecisionSource apenas com SATA (SataAgent.java:224), Budget (:323) e LLM (:335/:346/:357); MOP/Coverage/WTG/Menu/Fuzz/Component nunca sao atribuidos — decision_source no [APE-STEP] (StatefulAgent.java:1267-1272) so emite 3 valores. A sub-estrategia SataEventType nao e campo da linha; existe apenas em 'Select action %s by strategy %s' (SataAgent.java:219) sem step=N, exigindo join posicional no bracket begin/end step (ApeAgent.java:318/355). O short-circuit Back/Menu unvisited (SataAgent.java:414-428) retorna antes da roleta e ainda assim e rotulado EPSILON_GREEDY (:395), conflando short-circuit com roleta — pior que o alegado. A roleta (RandomHelper.java:42-65, chamada em SataAgent.java:972) nao loga indice nem candidatos: 'escolhida por causa do boost' e inexprimivel causalmente. Caminho dispara em todo step via resolveNewAction (StatefulAgent.java:1256-1259). Mitigacao parcial que nao refuta: a linha APE-STEP loga os boosts da acao escolhida (mop=/wtg=/coverage=/menu=, StatefulAgent.java:1270-1272) e o join posicional e deterministico na pratica (single-threaded, um logActionSelected por step; checkBackTrack:281 nao tem call sites). Severidade alta mantida: scripts que filtrem decision_source=MOP contam zero silenciosamente.


### B.14 [CONFIRMADO] → severidade corrigida: **bloqueante** — Fill deterministico do form-completion e codigo morto: inFormCompletionContext() sempre false em checkInput porque moveForward() ja anulou newState

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:184` · alegado: bloqueante/confirmado · métrica: mop-cobertura · origem: change-correctness (worktree)

**Alegação detalhada:** O pipeline e checkInput(checkFuzzing(checkRestart(updateStateInternal()))) (ApeAgent.java:337). updateStateInternal chama moveForward() (StatefulAgent:683) para toda ModelAction, e doMoveForward seta newState=null (StatefulAgent:1195). Quando checkInput roda (ApeAgent:192), o override de inFormCompletionContext le newState==null e retorna false — o ramo 'preencher deterministicamente' NUNCA dispara e o toss(inputRate=0.8) legado continua valendo em 100% dos passos. A metade central da alegacao #5 (INV-FORM-03/INV-INP-04, requisito 'Fill all... deterministically') nao e entregue; nenhum teste cobre o caminho.

**Veredito do refutador:** ApeAgent.java:337 executa checkInput por ultimo no pipeline; StatefulAgent.updateStateInternal (linha 655) anula newState em TODOS os caminhos de retorno antes disso — via moveForward()/doMoveForward() (linhas 683/1195, apos copiar para currentState na 1190) ou via resetTrace() (linhas 685/484). Logo o override inFormCompletionContext (StatefulAgent:183-184, 'newState != null && ...') retorna sempre false quando lido em ApeAgent:192, e o fill deterministico nunca dispara; vale só o toss(inputRate=0.8) (Config.java:64). Sem rotas alternativas: checkInput só e chamado na linha 337 (o fallback BadState na 345 o pula), setNewState (StatefulAgent:299) tem zero chamadores, nenhuma subclasse sobrescreve os metodos, e nenhum teste cobre checkInput/inFormCompletionContext (FormCompletionTest só testa a utility pura). O estado correto estaria em currentState (doMoveForward:1190), provando leitura de campo obsoleto e contradizendo o javadoc (StatefulAgent:177-181, ApeAgent:200-203). Mitigacao parcial: o boost pass (StatefulAgent:1471-1491) e a exclusao INV-FORM-06 (SataAgent:501-503) rodam corretamente antes de moveForward, e campos com toss falho continuam 'unfilled' (FormCompletion.java:43-52), re-boostados; mas a exclusao do submit nao cobre o caminho roleta, submits com campos vazios seguem possiveis, e o requisito INV-FORM-03/INV-INP-04 ('fill deterministically') e entregue como codigo inalcancavel sem teste — bloqueante para aceitacao do change.


### B.15 [CONFIRMADO] → severidade corrigida: **alta** — Guard INV-FORM-06 e derrotado pelos proprios caminhos de selecao: o submit MOP-boosted ainda e clicado antes dos campos

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:497` · alegado: alta/confirmado · métrica: mop-violacoes · origem: change-correctness (worktree)

**Alegação detalhada:** A exclusao so remove o submit do short-circuit MOP. (a) EARLY_STAGE roda ANTES do ramo epsilon-greedy e usa randomPickWithPriority: o submit carrega mop=500 + W_SUBMIT=100 (+cov), a maior roleta da tela, e e escolhido com probabilidade dominante num form recem-visto. (b) No proprio metodo, quando o short-circuit retorna null, egreedy->greedyPickLeastVisited(ENABLED_VALID) (State.java:124-140) empata todos em visitedCount=0 e desempata por MAIOR priority — escolhe deterministicamente o submit excluido. O cenario do spec 'submit not clicked before fields are filled' nao e garantido por nenhum caminho; os testes cobrem so o parametro excluded de pickBestMopTarget.

**Veredito do refutador:** Confirmado por leitura integral dos caminhos. (a) SataAgent.java:420 executa EARLY_STAGE antes do epsilon-greedy (linha 435); findGreedyActionForward → StateActionDiffer.getUnsaturated (inclui o submit unvisited) → RandomHelper.randomPickWithPriority (SataAgent.java:1072) — roleta por priority sem nenhuma exclusão INV-FORM-06; priority do submit = 32 base + 20 unvisited + 500 mop (StatefulAgent.java:1394) + 100 W_SUBMIT (StatefulAgent.java:1483) + 100 coverage ≈ 750 vs ≈300 por campo (W_FILL=150, FormCompletion.java:25,31) — maior fatia da roleta num form recém-visto. (b) No próprio método: com o submit excluído e sendo o único mopBoost>0, selectUnvisitedMopTarget retorna null e egreedy() (prob 0.95, epsilon=0.05) → greedyPickLeastVisited(ENABLED_VALID) (SataAgent.java:484); State.java:124-140 desempata vc=0 por MAIOR priority — escolhe deterministicamente o submit excluído (só os short-circuits back/menu unvisited, linhas 457-470, podem preempção temporária). O spec (spec.md:40 'or any other selection path') e o design.md:105 ('subsumes the priority-ordering half of OQ4') prometem garantia que o código não entrega. Testes (SataAgentMopShortCircuitTest.java:74-95) cobrem apenas o parâmetro excluded de pickBestMopTarget, nunca os caminhos EARLY_STAGE/least-visited. Atenuante: autocorrige após 1 submit desperdiçado por form (submit vira visited, campos são preenchidos e re-submit posterior ainda pode disparar o MOP), logo degrada e não zera a métrica — mas a garantia central da mudança não existe nos caminhos dominantes, sustentando severidade alta.


### B.16 [CONFIRMADO] → severidade corrigida: **alta** — Short-circuit MOP e sombreado por EARLY_STAGE: dispara so quando nao ha acao unvisited-by-name alcancavel, janela estreita

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:476` · alegado: alta/confirmado · métrica: mop-cobertura · origem: change-correctness (worktree)

**Alegação detalhada:** selectNewActionEpsilonGreedyRandomly so e alcancado depois de USE_BUFFER, TRIVIAL_ACTIVITY e dos dois EARLY_STAGE. findGreedyActionForward coleta exatamente as acoes unvisited-by-name (getGreedyActions) e as consome por roleta (randomPickWithPriority, SataAgent:1072) ou por caminho (findShortestPaths) — ou seja, um alvo MOP nao-visitado numa tela nova quase sempre passa pela roleta de K12, nao pelo argmax novo. O short-circuit cobre apenas alvos ja visitados-por-nome em outro estado mas unvisited neste. A alegacao #2 ('alcancado deterministicamente em vez de roleta') vale so nessa janela residual; K12 e resolvido parcialmente.

**Veredito do refutador:** Nao consegui refutar; o codigo confirma a alegacao ponto a ponto. (1) Ordem da cadeia: SataAgent.java:409-445 — selectNewActionEpsilonGreedyRandomly (que contem o short-circuit MOP em :476) so e alcancado depois de selectNewActionFromBuffer (:410, USE_BUFFER), selectNewActionBackToActivity (:415), selectNewActionEarlyStageForward (:420), selectNewActionForTrivialActivity (:425) e selectNewActionEarlyStageBackward (:430); nenhum desses estagios e gated por flag de 'fase inicial' — rodam a execucao inteira. (2) findGreedyActionForward (:1066) consome o alvo primeiro: getGreedyActions(prev,next) (:1070) coleta as acoes nao-saturadas/unvisited-by-name (default useActionDiffer=true, Config.java:87 → StateActionDiffer.getUnsaturated; fallback :647 usa isActionUnvisitedByName) e escolhe por roleta randomPickWithPriority em :1072, ou por caminho via findShortestPaths(greedySubsequenceFilter) em :1091. Como acao unvisited ⇒ resolvedSaturation=0 (ModelAction.resolveAt:188-192) ⇒ nao-saturada, todo candidato ENABLED_VALID_UNVISITED do short-circuit (:504) e tambem candidato do 0-step do EARLY_STAGE — o sombreamento e ate mais forte que o alegado; a janela residual e apenas o carve-out do differ (acao matched cujo homonimo no estado anterior da mesma Activity esta saturado, StateActionDiffer:75) ou visitado-por-nome no fallback, e ainda atras dos short-circuits Back/Menu unvisited (:457-470). (3) A roleta e ponderada pelo boost (StatefulAgent:1394 soma mopBoost a priority antes; base ~32-70 por getActionBasePriority<<3 vs +500 mopWeightDirect), logo o alvo MOP e favorecido probabilisticamente mas nao deterministicamente — com ~20 acoes no estado a chance de perder o alvo naquele passo e substancial (~50%+), e apos refill de buffer o USE_BUFFER bypassa tudo por varios passos. Para a metrica mop-cobertura sob budget fixo, o mecanismo anunciado como determinista (comentario INV-SEL-MOP-01/02, :471-475) e majoritariamente inerte no caminho dominante; severidade alta se sustenta.


### B.17 [CONFIRMADO] → severidade corrigida: **media** — One-shot do short-circuit MOP pode ser queimado por restart: acao marcada visitada antes de checkRestart e sem checkDisableRestart

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:681` · alegado: alta/confirmado · métrica: mop-cobertura · origem: change-correctness (worktree)

**Alegação detalhada:** markVisited(action) roda em updateStateInternal (StatefulAgent:681) antes de checkRestart poder substituir a acao (ApeAgent:337; restart periodico a cada 100-300 passos). O caminho EARLY_STAGE greedy se protege via checkDisableRestart (SataAgent:1104), mas o novo short-circuit nao chama disableRestart. Se o restart cair no mesmo passo, o alvo MOP unvisited vira 'visited' sem nunca executar e, como o short-circuit exige isUnvisited, aquele alvo perde permanentemente a selecao deterministica (resta so roleta). Agrava o achado Frente-3 de [APE-STEP] pre-checkRestart, que segue nao enderecado.

**Veredito do refutador:** Mecanismo confirmado integralmente: StatefulAgent.java:681 marca a ação visitada dentro de updateStateInternal, e ApeAgent.java:337 só depois aplica checkRestart, que substitui a ação por um restart (ApeAgent.java:267-271) quando contSteps > threshold (default ativo: Config.java:61-63, checkRestart=true, threshold 100-300; disableRestart resetado a cada passo em ApeAgent.java:335). O short-circuit MOP (SataAgent.java:476-481) não chama checkDisableRestart, ao contrário do caminho EARLY_STAGE (SataAgent.java:1076/1097/1122). A queima é irreversível para o short-circuit: visitedAt seta firstVisitTimestamp permanentemente (GraphElement.java:55-63) e selectUnvisitedMopTarget filtra ENABLED_VALID_UNVISITED (SataAgent.java:504). O agravante de telemetria também procede ([APE-STEP] logado pré-checkRestart em StatefulAgent.java:1276). Severidade rebaixada para media: (a) o disparo exige coincidência restart-no-mesmo-passo (~1/200 por disparo do short-circuit, que é one-shot por alvo e raro dado o substrato MOP esparso — queimas esperadas por run são fração pequena de 1); (b) o alvo queimado não é perdido para a exploração — com visitedCount=1 continua favorecido por greedyPickLeastVisited (SataAgent.java:484) e elegível na roleta, perdendo só a seleção determinística; (c) o padrão é herdado do upstream (short-circuits Back/Menu em SataAgent.java:459-469 têm a mesma exposição, e a proteção EARLY_STAGE é ela própria parcial, e.g. branch global SataAgent.java:1080-1089). Fix trivial: chamar checkDisableRestart(mopTarget) no short-circuit.


### B.18 [CONFIRMADO] → severidade corrigida: **alta** — Guard INV-FORM-06 cobre so o ramo EPSILON_GREEDY; EARLY_STAGE (roleta por prioridade) continua clicando o submit MOP em formulario vazio

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:496` · alegado: alta/confirmado · métrica: mop-violacoes · origem: change-tests (worktree)

**Alegação detalhada:** A cadeia selectNewActionNonnull consome EARLY_STAGE (findGreedyActionForward -> RandomHelper.randomPickWithPriority sobre acoes unvisited-by-name, SataAgent.java:1072) ANTES de selectNewActionEpsilonGreedyRandomly. Na 1a visita a um form MOP, o submit carrega ~752 de prioridade (base+unvisited+mop500+W_SUBMIT100+coverage) vs ~302 por campo, entao a roleta EARLY_STAGE escolhe o submit vazio com probabilidade dominante — exatamente a regressao submit-before-fill que o guard deveria eliminar. O cenario do spec ('selection SHALL proceed so an unfilled EditText action is filled first') nao e garantido pela implementacao. Agravante: W_SUBMIT e aplicado mesmo com campos vazios.

**Veredito do refutador:** Confirmado por leitura direta: (1) SataAgent.java:420 consome EARLY_STAGE (selectNewActionEarlyStageForward → findGreedyActionForward:1066 → RandomHelper.randomPickWithPriority, RandomHelper.java:42-65) antes de selectNewActionEpsilonGreedyRandomly:435; (2) o guard INV-FORM-06 existe apenas em selectUnvisitedMopTarget (SataAgent.java:496-505), chamado somente pelo ramo EPSILON_GREEDY (linha 476) — grep confirma que FormCompletion não aparece em nenhum ponto do caminho EARLY_STAGE; (3) prioridades da roleta incluem todos os boosts via adjustActionsByGUITree (StatefulAgent.java:1320-1491): submit = 32 base (1324) + 20 unvisited (1345) + 500 mop (1394, Config.java:128) + 100 W_SUBMIT (1483) + 100 coverage (1458) = 752 vs campo = 32+20+150 W_FILL (1475)+100 = 302 — números da alegação batem exatamente; (4) W_SUBMIT é aplicado exatamente quando hasUnfilledEditText é true (StatefulAgent.java:1471), ou seja, com o formulário vazio; (5) na 1ª visita todas as ações são unvisited → getGreedyActions não-vazio → EARLY_STAGE dispara e o guard nunca roda; P(submit vazio) ≈ 55-71%. Agravante estrutural: após o clique vazio o submit fica visited e o short-circuit guardado (limitado a unvisited, linha 493) nunca mais dispara para esse alvo. Tentativas de refutação falharam: ABA (829) retorna null em 1ª visita; useActionDiffer=true (Config.java:87) não altera (getUnsaturated inclui o submit unvisited). Severidade alta sustentada: a primeira oportunidade MOP-ponderada de cada submit handler é gasta em formulário vazio com probabilidade dominante, violando o cenário do spec para a métrica mop-violacoes.


### B.19 [CONFIRMADO] → severidade corrigida: **alta** — Short-circuit MOP (mudanca #2) fica sombreado pelo EARLY_STAGE: acoes unvisited raramente chegam ao ramo onde ele vive

`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:471` · alegado: alta/confirmado · métrica: mop-cobertura · origem: change-tests (worktree)

**Alegação detalhada:** O short-circuit exige acao ENABLED_VALID_UNVISITED com mopBoost>0, mas qualquer acao unvisited-by-name e consumida antes pela roleta do EARLY_STAGE (getGreedyActions:630-651 + randomPickWithPriority). O ramo EPSILON_GREEDY so e alcancado quando forward/backward greedy falham, i.e. quase sempre quando ja nao ha unvisited-by-name — sobra apenas o residuo 'visitado por nome em outro estado, unvisited local'. O 'caminho deterministico para o alvo MOP' prometido pela proposal opera numa minoria de decisoes; na maioria o boost continua diluido em roleta (K12 persiste no caminho dominante). Implementado conforme o spec, mas o spec posiciona o mecanismo onde ele pouco dispara.

**Veredito do refutador:** CONFIRMADO por codigo + traces reais. (a) Codigo: a cadeia de selecao (SataAgent.java:409-445) coloca EARLY_STAGE (:420 forward, :430 backward) antes de selectNewActionEpsilonGreedyRandomly (:435); o short-circuit MOP vive em :476, apos os checks Back/Menu-unvisited (:457-470). getGreedyActions (:634-651) filtra por isActionUnvisitedByName e alimenta RandomHelper.randomPickWithPriority (:1072) — roleta ponderada (RandomHelper.java:42-63), nao argmax; mopBoost entra so como somando de priority (StatefulAgent.java:1394). Se nao ha greedy local, EARLY_STAGE ainda navega a greedy states remotos (:1080-1100); EPSILON_GREEDY so e alcancado quando nao ha unvisited-by-name alcancavel — restando o residuo 'visitado-por-nome, unvisited local', como alegado. (b) Runtime: nos traces cmpmop (shards 00-01, braco sata_mop; estrutura da cadeia identica ao worktree): EARLY_STAGE=21.422 (57,6%), EPSILON_GREEDY=14.849 (39,9%), USE_BUFFER=886. Dentro de EPSILON_GREEDY, apenas 216/14.849 (1,5%) das selecoes foram UNVISITED — como greedyPickLeastVisited (State.java:124-139) prefere deterministicamente vc=0, isso e limite superior de quando uma acao unvisited sequer EXISTE no ponto do short-circuit: ~0,6% das decisoes, antes ainda do filtro mopBoost>0 (~3% das decisoes no run antigo). O ramo dispara com frequencia (40%), mas acoes unvisited raramente sobrevivem ate ele — exatamente a alegacao. (c) O design.md:40 promete 'reached deterministically rather than via roulette probability' e D2 diz que o short-circuit 'defeats roulette dilution' — falso no caminho dominante: primeiros encontros do alvo MOP sao unvisited-by-name e sao consumidos pela roleta do EARLY_STAGE (+500 sobre base ~150 so multiplica odds ~3-4x). K12 persiste em ~58% das decisoes; o mecanismo deterministico opera em <1%. Atenuantes que impedem rebaixar: o fair-test existe precisamente para testar se guiamento MOP move mop-cobertura, e o mecanismo-bandeira da mudanca #2 fica ~99% dormante — risco concreto de repetir um null nao-informativo atribuivel a posicionamento, nao a MOP em principio. Boost discriminativo ainda age via peso de roleta e tiebreak least-visited, logo tratamento enfraquecido, nao nulo — mas a promessa central do spec nao se realiza no caminho dominante.


### B.20 [CONFIRMADO] → severidade corrigida: **media** — Premissa falsa no drop de widgets sem id: a chave "" ERA alcancavel em runtime, e o drop elimina o unico caminho widget-level para apps 100% sem resource-id

`src/main/java/com/android/commands/monkey/ape/utils/MopData.java:359` · alegado: alta/confirmado · métrica: mop-cobertura · origem: change-tests (worktree)

**Alegação detalhada:** O spec/design justifica INV-MOP-20 com 'extractShortId nunca produz "" para um widget real', mas extractShortId retorna "" justamente para nos SEM resourceId ou malformado (MopData:~735), e mopBoostWithContainment (StatefulAgent:1506-1533) chama MopScorer.score com esse "" sem guarda — inclusive para ancestrais/descendentes no containment. Antes, um widget flagged sem id no JSON casava (grosseiramente) com widgets runtime sem id; agora labnex/duress (100% sem id) perdem qualquer boost widget-level, e pos-#2 nao ha mais fallback de activity. Trade-off defensavel (o match "" era uniforme/ruidoso), mas a justificativa do spec e factualmente errada e a perda nao foi decidida conscientemente.

**Veredito do refutador:** Alegacao confirmada em todos os pontos de codigo. (1) Premissa falsa: spec.md:13 (mop-parser-fidelity) diz "The empty-string key is unreachable at runtime", e MopData.java:334-335 diz "extractShortId never yields '' for a real widget" — mas extractShortId (MopData.java:742-745) retorna "" exatamente para widgets sem resourceId, e nodes runtime sem view-id recebem resourceID "" via cacheStringEmptyOnNull (GUITreeBuilder.java:587); logo getWidget(activity, "") e uma consulta runtime real. (2) O codigo antigo (git show HEAD) era `if (wd.idName != null) widgets.put(...)` e o produtor emite "idName": "" (21/51 widgets no cryptoapp.apk.json sao "", 0 null) — o bucket "" ERA populado e alcancavel; o drop novo (MopData.java:333-341) o elimina. (3) O caminho dispara em toda passada MOP: StatefulAgent.java:1378-1399 chama mopBoostWithContainment (1504-1541) para toda acao target valida, sem guarda de "" (idem MopScorer.score:34-53); scoreWtg tem guarda shortId.isEmpty() (MopScorer.java:79) e o fallback +100 foi removido (MopScorer.java:48-52; spec mop-discriminative-boost:13) — apps 100% sem id perdem todo boost widget-level e WTG, restando so menu-gateway e stateMopDensity. SEVERIDADE corrigida de alta para media por dois atenuantes verificados: (a) o match "" perdido era uniforme por (activity, eventType) — todos os widgets sem id resolviam para o MESMO widget armazenado (last-write-wins sem preferencia de flag, podendo ate ser sobrescrito por sibling unflagged), logo nao re-rankeava widgets entre si, apenas deslocava massa de roleta para acoes target coletivamente — o mesmo argumento de inercia que o projeto usou para remover o +100; (b) a perda nao foi totalmente inconsciente: design.md:98 nomeia labnex/duress explicitamente e aceita o trade-off ("accepted... deferred"), embora sob a mesma premissa falsa ("still unscorable", implicando que nada era perdido). No fixture disponivel (cryptoapp) nenhum widget "" e flagged (drop=0, sem mudanca de comportamento); o dano fica restrito a apps com widgets flagged sem id (labnex/duress, afirmado pelo proprio design.md:98, nao verificavel neste repo). O defeito central que permanece: justificativa factualmente errada no spec e no comentario do codigo, e decisao tomada sem saber que um caminho vivo estava sendo removido.


## Anexo C — Relatórios integrais dos 11 agentes (síntese, cobertura, limitações)


### C — A.1 Pacote ape/agent/

Auditoria read-only do pacote ape/agent (5 arquivos, 3.498 linhas, todos lidos integralmente) com verificacao de call sites em MonkeySourceApe, Graph, State, ModelAction, ActionType, RandomHelper, MopData, ComponentInfo, AndroidDevice e no fixture cryptoapp.apk.json.

MAIS GRAVES (novos): (1) dispatchTrigger monta o ComponentName com package derivado do className por substring — no fixture real os componentes vivem em subpacotes do package br.unb.cic.cryptoapp, logo o component triggering (mecanismo dedicado a alcancar MOP em receivers/services) falha silenciosamente para qualquer componente fora do pacote raiz; (2) checkBackTrack e triplamente morto: zero call sites, BFS que nunca enfileira (visited.add(newState)+contains(state) — confirma K08) e guard newState!=state insatisfazivel — nao existe fuga de saturacao alem de restarts; (3) acoes sao marcadas visitadas e contadas no coverage tracker na SELECAO, e checkRestart descarta a acao escolhida em todo restart — falso 'visitado' sistematico exatamente nas acoes unvisited de maior valor; (4) StatefulAgent.checkFuzzing(ModelAction) e overload jamais invocado (dispatch estatico resolve para ApeAgent.checkFuzzing(Action)) — fuzzingActivityVisitThreshold e config morta e o fuzzing 2% dispara em activities recem-descobertas; (5) o unico call site de producao ainda chama MopData.load 1-arg — mopStrictPackageMatch segue inerte no master apesar do status 'corrigido gh15' em K24 (o guard contra skew classe-K01 nao pode disparar); (6) ReplayAgent replaya MODEL_MENU como BACK (todo model action sem target vira getBackAction()).

SUSPEITAS RELEVANTES: isDialogState contradiz a mensagem 'saturated dialog' — bloqueia ABA para hubs que AINDA tem acoes greedy; `priority += 10 // make it weaker` promove (nao enfraquece) edges flaky.

SAUDAVEL (verificado): onVisitStateTransition cobre exaustivamente os 3 StateTransitionVisitTypes e os contadores GSTG (graph/state/activity) resetam corretamente em novas edges, startNewEpisode e onActivityStopped, com thresholds de restart que zeram apos disparar (sem starvation permanente); adjustActionsByGUITree faz resetBoosts()+setPriority(base) para TODAS as acoes antes de qualquer continue (sem prioridade/boost stale entre passos); os passes MOP/WTG/coverage sao aditivos e logam telemetria consistente; mopBoostWithContainment respeita o bound de 2 niveis; selectNewActionFromBuffer valida identidade da acao contra newState antes de reutilizar (sem uso de acao de estado errado); randomlyPickAction/randomPickWithPriority estao corretos (sem off-by-one na roleta; lancam em prioridade <=0); greedyPickLeastVisited desempata por prioridade corretamente; findShortestPaths nunca chama include com path vazio (o filtro de trivial activity e seguro); o hook de budget e advisory com fallthrough correto (sem loop de RESTART); a atribuicao LLM/Budget de decision_source funciona nos early-returns; recoverCurrentState marca currentStateRecovered e checkNonDeterministicTransitions o respeita. O loop e single-threaded e nenhum padrao de concorrencia indevido foi encontrado no escopo.


**Cobertura de leitura:** Lidos integralmente: /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java (1741), SataAgent.java (1050), ApeAgent.java (428), ReplayAgent.java (194), RandomAgent.java (85). Lidos parcialmente para verificacao de call sites: MonkeySourceApe.java, model/Graph.java, model/State.java, model/ModelAction.java, model/ActionType.java, model/StateTransitionVisitType.java, utils/RandomHelper.java, utils/MopData.java, utils/ComponentInfo.java, utils/Config.java, AndroidDevice.java, e test-apks/cryptoapp.apk.json.


**Limitações declaradas:** Sem execucao em dispositivo/emulador e sem mvn (proibido): a falha silenciosa do broadcast com package errado foi inferida da semantica Android de intents explicitos, nao observada ao vivo. Nao diffei contra o APE upstream (ICSE'19), entao a proveniencia (fork vs upstream) de isDialogState e do '+10 make it weaker' fica em aberto. Nao reanalisei traces cmpmop para quantificar a frequencia real de restarts que descartam acao marcada. Classes fora do escopo (MopScorer, UICoverageTracker, ActivityBudgetTracker, LlmRouter, Subsequence) foram lidas apenas no necessario para rastrear os caminhos quentes.


### C — A.2 Pacote ape/naming/

Auditoria read-only completa do pacote ape/naming (38 arquivos, 5.272 linhas, todos lidos integralmente), com rastreio dos caminhos quentes: captura (GUITreeBuilder.fillNode) → naming (Naming.namingInternal/select → Namer.naming → NameManager interning) → StateKey (GUITreeBuilder.getStateKey) → ações (State.buildActions via NamerFactory.decodeActions) → refinamento (NamingFactory.resolveNonDeterminism/actionRefinement/batchAbstract → StateNamingManager.updateNaming → Model.rebuild).

ACHADOS NOVOS PRINCIPAIS: (1) misuse de Collections.binarySearch em Naming.select (`== -1` em vez de `< 0`) — exatamente o padrão-alvo do escopo; disparo estreito porque a expr do namelet filho normalmente implica a do pai, mas sem garantia estrutural em cadeias profundas. (2) Guarda de maxGUITreesPerState inalcançável em NamingFactory (copy-paste testa an.getStates() duas vezes) — a flag é dead config e o limite de GUITrees por estado nunca vale no refinamento. (3) ignoreEmpty/ignoreOutOfBounds sem efeito algum (único consumidor, Naming.addNamedNode, é morto): nós offscreen/vazios entram no StateKey e inflam estados. (4) Exceção de EditText no TextNamer é morta — texto digitado fragmenta estados sob refinamento TEXT (interage com K39/FormCompletion). (5) complementOf() ignora o argumento → validação do lattice vácua. (6) escapeToXPathString é no-op; segurança dos XPaths depende de removeQuotes a dois arquivos de distância. (7) Chave de interning do NameManager (toString) ≠ equals — ActionPatchName omite scrollType (latente no caminho vivo). (8) AssertStatesDivergent nunca popula o set (vacuamente true; classe hoje morta). (9) finally de naming() mascara exceções com NPE. (10) empates não-resolvidos no NamerComparator tornam a escolha do namer refinado dependente de ordem de HashMap (reprodutibilidade). (11) Caminho de reload de modelo (Graph.readGraph) quebrado por transient+intern estático.

REFUTAÇÕES/DEDUP: K04 (hasChild invertido) é neutralizado pelo único chamador (isLeaf) — dupla negação acidentalmente correta, impacto de K04 está superestimado. K06 confirmado na linha, mas o vetor de aspas está defendido por removeQuotes na captura. K36 (IndexName sem equals) é mitigado na prática pelo interning por toString — todos os usos passam por NameManager.

SAUDÁVEL: o lattice em si é completo e consistente (join/meet/refinesTo coerentes com containsAll; AbstractNamer.refinesTo correto); Namelet.equals/hashCode/compareTo são mutuamente consistentes; StateNamingManager (manager default, activityManagerType='state') tem walk de namingToEdge correto e sanity-checks agressivos (debug=true) que detectariam corrupção; ParentNamer/AncestorNamer respeitam a ordem BFS de nomeação (temp names de pais sempre setados antes dos filhos); checkStateRefinement/checkActionRefinement implementam corretamente os critérios de separação + anti-explosão com upperBounds; os predicados ativos (AssertSourceDivergent, AssertActionDivergent/2, AssertStatesFewerThan) estão logicamente corretos; a poda por upperBounds via refinesTo é válida; getMaxStatesForRefinementThreshold produz faixas sãs (2..8); caches por-Naming têm release() para árvores removidas. Não encontrei no pacote nenhum defeito que explique sozinho fusão/divisão MASSIVA de estados — o mecanismo dominante de distorção de contagem continua sendo a fragmentação StateKey×naming já catalogada (K27), agravada pelos achados (3) e (4) acima.


**Cobertura de leitura:** Integralmente lidos (38/38 do escopo): NamingFactory.java, Naming.java, Namelet.java, NamerFactory.java, NamerLattice.java, NamerType.java, Namer.java, AbstractNamer.java, NamerComparator.java, Name.java, AbstractName.java, AbstractLocalName.java, TextNamer.java, TypeNamer.java, IndexNamer.java, ParentNamer.java, AncestorNamer.java, CompoundNamer.java, ActionPatchNamer.java, EmptyNamer.java, SingletonNamer.java, NameManager.java, AbstractNamingManager.java, StateNamingManager.java, ActivityNamingManager.java, MonolithicNamingManager.java, NamingManager.java, Predicate.java, AbstractPredicate.java, AbstractGUITreePredicate.java, AssertStatesDivergent.java, AssertStatesFewerThan.java, AssertActionDivergent.java, AssertActionDivergent2.java, AssertSourceDivergent.java, AssertTargetsFewerThan.java, NamedNodePartition.java, GUITreeProperty.java. Lidos parcialmente para verificação de call-sites: Config.java, GUITreeBuilder.java, GUITreeNode.java, Model.java, State.java, ModelAction.java, Graph.java, ApeAgent.java, XPathBuilder.java, StringCache.java.


**Limitações declaradas:** Sem execução em dispositivo/emulador e sem rodar mvn/testes: as frequências de disparo dos achados condicionais (binarySearch em cadeias profundas; fragmentação por texto de EditText sob refinamento TEXT; colisão de scrollType via resetActions) não foram medidas em traces reais — seriam confirmáveis com um trace instrumentado de refinamentos. Model.rebuild e Graph (fora do escopo) foram lidos apenas nos pontos de contato com naming; a interação completa rebuild×namingToEdge não foi auditada. A cadeia LLM/MOP não foi tocada (fora do escopo). GUITreeNode.isEditText cobre só 4 classes legadas (AppCompat/AndroidX EditText ficam de fora) — impacto em input de texto é real mas o arquivo está fora do escopo deste pacote e o tema já aparece no memo mop-fairtest, então não foi listado como finding.


### C — A.3 Pacote ape/model/

Auditoria read-only do pacote ape/model/ (22 arquivos) + xpathaction/ (6 arquivos), todos lidos integralmente, com verificacao de call-sites em StatefulAgent, SataAgent, NamingFactory, Utils, Naming, GUITree e NamerFactory.

ACHADOS PRINCIPAIS (novos): (1) Graph.rebuildHistory() infla visitedCount de todas as arestas a cada rebuild — a contagem dobra no 1o refinamento e cresce a cada refinamento seguinte, porque markVisited ja conta durante a re-adicao e o loop soma de novo sobre TODO o treeTransitionHistory, inclusive arestas sobreviventes; consumidor direto e o weakActionSubsequenceFilter do SataAgent (visitedCount<3), que passa a desistir cedo de re-explorar arestas fracas. (2) O rebuild dupla-conta visitas no ActivityNode (nunca resetado), tornando 'quentes' as activities que sofreram refinamento — exatamente as telas complexas — e despriorizando-as no backtracking do SataAgent (doABA, colder-activity, trivial-activity blacklist, gate de fuzzing). (3) O cap maxGUITreesPerState e letra morta: NamingFactory testa an.getStates().size() (copy-paste) nos 2 unicos usos da flag; com defaults e codigo morto e nao existe nenhum limite real de GUITrees por estado (alimenta o OOM conhecido); com maxStatesPerActivity>20 o refinamento trava silenciosamente em 20. (4) O rebuild marca visitado so o SOURCE de cada transicao: estados target-only renascem unvisited com in-transitions, quebrando o invariante que refreshNewState/checkAndRefreshNewState transformam em RuntimeException nao tratada. (5) Graph.contains lanca em vez de retornar false para estado recriado com a mesma chave apos remocao pelo refresh-check (suspeita). Menores: reuso de graphId de arestas apos remocoes (telemetria ambigua), IllegalStateException do xpathaction (opt-in), truncamento potencial do action-history.log no tearDown, NPE latente em Graph.remove, e printStatistics por passo. K07 confirmado mas com impacto corrigido: os timestamps sao reparados na re-adicao; o dano real e o contador.

SAUDAVEL: dedup de arestas por (source,action,target) com contadores hitting/missing e by-design e nao perde informacao (GUITreeTransitions preservadas por aresta e re-apontadas corretamente no rebuild); a roleta de prioridades de State.pickAction e aritmeticamente correta e detecta filtro instavel; widgets do StateKey sao ordenados (Arrays.sort em NamingResult) antes do binarySearch de containsTarget; a re-resolucao de acoes no rebuild (Model.rebuild->state.getAction(widget,type)) usa o XPathName pos-renomeacao da MESMA arvore, entao acoes nao apontam para widget errado; equals/hashCode de State/StateTransition/ModelAction/StateKey sao consistentes entre si; markVisited tem sanity-checks fortes; addStateTransition mantem os 3 indices (edges, out, in) em sincronia com checks; Utils.removeFromMapMap/Set sao null-safe nos fluxos atuais de remocao; a ordem de re-adicao cronologica no rebuild deixa fv/lv corretos; ActionType usa ranges de ordinal consistentes com a declaracao do enum; StateActionDiffer faz o merge ordenado corretamente; enums/contadores auxiliares (EnumCounters, ActionCounters) sem defeito; comportamento ao atingir maxStatesPerActivity e parar refinamento silenciosamente (by-design, mas sem log de saturacao agregado).


**Cobertura de leitura:** Lidos integralmente (28 arquivos do escopo): ape/model/{Action,ActionCounters,ActionType,ActivityNode,Crash,CrashAction,EnumCounters,FuzzAction,Graph,GraphElement,GraphListener,Model,ModelAction,ScrollType,StartAction,State,StateActionDiffer,StateKey,StateTransition,StateTransitionVisitType,TreeActions}.java e ape/model/xpathaction/{XPathAction,XPathActionController,XPathActionReader,XPathActionSequence,XPathlet,XPathletReader}.java. Lidos parcialmente para verificacao de call-sites: agent/StatefulAgent.java (trechos 370-400, 500-700, 1195-1230, 1650-1695), agent/SataAgent.java (150-175, 440-510, 580-785), naming/NamingFactory.java (255-300, 1150-1210), naming/Naming.java (60-110, 400-420), naming/NamerFactory.java (140-170), tree/GUITree.java (pickNodes), utils/Utils.java (helpers de mapa), utils/Config.java (grep de caps).


**Limitações declaradas:** Nao executei mvn/testes nem rodei em dispositivo; magnitudes de inflacao (visitedCount de arestas e ActivityNode) nao foram quantificadas em traces reais — a mecanica foi confirmada por leitura de codigo e call-sites, nao por reproducao. StatefulAgent/SataAgent/NamingFactory foram lidos apenas nos trechos relevantes (fora do escopo formal), entao consumidores adicionais dos contadores podem existir. O sub-caso de crash do finding 4 (RuntimeException 'unvisited state has non-empty transitions') e o finding 5 (Graph.contains) tem gatilhos plausiveis mas nao rastreados ate um disparo concreto em trace. Nao verifiquei consistencia compareTo/equals de todas as subclasses de Name (K36 cobre IndexName).


### C — A.4 Pacotes ape/tree/ + ape/events/

Escopo auditado: os 6 arquivos de ape/tree/ e os 11 de ape/events/ lidos integralmente, com rastreamento dos call sites em MonkeySourceApe, ApeAgent, StatefulAgent, Naming/Namelet/TextNamer/NamerFactory, Model, InputValueGenerator e Config para confirmar disparo.

ACHADOS NOVOS MAIS GRAVES: (1) GUITreeNode.clearChildren itera NodeList viva do DOM e remove so metade dos filhos — checkAndRemoveWebView deixa 'widgets fantasma' que o naming (que percorre o DOM, nao a arvore logica) transforma em acoes reais do modelo; (2) isPassword nunca e capturado do AccessibilityNodeInfo (setIsPassword tem zero call sites), matando a deteccao de senha prioridade-1 do InputValueGenerator e reforçando as paredes de login (K57); (3) setText nao sincroniza @text no DOM enquanto computeAndSetImageText (default on) muta o texto apos a criacao do Element, e como releaseLoadedData roda a cada acao, o mesmo GUITree nomeia diferente no documento vivo vs reconstruido — refinamento por XPath [@text=...] inconsistente; (4) fallback de generateClickEventAt clica no centro da tela quando os bounds do widget nao intersectam a area visivel, registrando no modelo uma acao que nao aconteceu; (5) o gate de input usa isEditText() exato ('android.widget.EditText'), excluindo AutoCompleteTextView (caixas de busca) que o proprio GUITreeBuilder reconhece; (6) drag/pinch gravam float[] cru no JSON → replay de FuzzAction quebrado; (7) checkAndRemoveWebView conta todos os descendentes onde o original contava so acionaveis (>64 nos totais → WebView inteiro descartado); (8) supressao de texto de EditText no TextNamer e codigo morto → texto digitado fragmenta estados.

CONFIRMACOES DE CATALOGO: K03 (contains index==-1) e K33/K34 (pinch nunca enfileirado + precedencia) continuam presentes; quantifiquei que ~15% das iteracoes de fuzz (3/20 slots) nao produzem evento algum e que o fix do pinch exige tambem corrigir a validacao <4 (deveria ser >=6, par) e os 2 slots null do array.

SAUDAVEL: ApeClickEvent/ApeKeyEvent/ApeTrackballEvent/ApeRotationEvent/ApeAppSwitchEvent geram e enfileiram MonkeyEvents corretamente (down/wait/up coerentes; trackball serializa JSON certo); ApeDragEvent.generateMonkeyEvents e correto para >=2 pontos; o throttle propaga consistentemente (FuzzAction por sub-evento; GUITreeAction→Model.resolveModelAction); generateClickEventAt clica no centro da INTERSECCAO widget×viewport (correto quando ha interseccao); addChild faz o append DOM correto e o patchGUITree (propagacao de clickable para filhos) e coerente; GUITreeWidgetDiffer faz merge correto de arrays ordenados (binarySearch valido pois Naming ordena names); fillNode captura os atributos principais fielmente (exceto isPassword); GUITreeTransition/GUITreeAction sao holders simples sem defeito; nenhum problema de concorrencia encontrado (uso single-thread consistente; os HashMaps estaticos de cache do GUITreeBuilder so sao tocados pela thread de eventos).


**Cobertura de leitura:** Integralmente lidos: tree/GUITreeBuilder.java (673), tree/GUITree.java (352), tree/GUITreeNode.java (650), tree/GUITreeAction.java (68), tree/GUITreeTransition.java (135), tree/GUITreeWidgetDiffer.java (119); events/AbstractApeEvent.java, ApeEvent.java, ApeEvents.java, ApeClickEvent.java, ApeDragEvent.java, ApeKeyEvent.java, ApeAppSwitchEvent.java, ApeRotationEvent.java, ApeFuzzer.java, ApePinchOrZoomEvent.java, ApeTrackballEvent.java (11/11). Parcialmente (rastreamento de call sites): MonkeySourceApe.java, ape/agent/ApeAgent.java, ape/agent/StatefulAgent.java, ape/naming/Naming.java, Namelet.java, TextNamer.java, NamerFactory/NamingFactory (grep), ape/utils/InputValueGenerator.java (integral), ape/utils/Config.java (flags do escopo), ape/model/Model.java e ActionFilter.java (trechos).


**Limitações declaradas:** Sem execucao em dispositivo/emulador e sem mvn test: frequencia em runtime do fallback de clique no centro da tela, da divergencia @text vivo/reconstruido e do meio-descarte de WebView nao foi quantificada em traces (mecanismos confirmados apenas por leitura de codigo). Nao comparei com o APE upstream da ETH para decidir se checkAndRemoveWebView/getDescendantCount foi mudanca deliberada. Comportamento exato do org.json do Android para put(String, float[]) confirmado por conhecimento da implementacao, nao por teste executado. Reducer/ e a cadeia completa do ReplayAgent nao foram lidos integralmente.


### C — A.5 Pacote ape/utils/

Auditoria read-only do pacote ape/utils (16 arquivos, todos lidos integralmente) com rastreio dos call sites em StatefulAgent/SataAgent/ApeAgent/MonkeySourceApe/Monkey/StateKey/GUITree e validação contra o fixture real test-apks/cryptoapp.apk.json.

NOVOS (destaques): (1) StringCache.nextString sorteia nextInt(size) antes do check de lista vazia — crash IllegalArgumentException no caminho de input quando nenhum texto foi cacheado ainda; (2) RandomHelper usa ThreadLocalRandom não-semeável em 34 call sites de decisão (incl. a roleta randomPickWithPriority), então a seed -s do Monkey é ignorada e nenhum run é reproduzível (RNG misto: egreedy usa o Random semeado); (3) mopStrictPackageMatch segue inalcançável em produção — StatefulAgent chama load(path) 1-arg, então JSON stale/errado (classe do skew K01) é aceito em silêncio apesar do CLAUDE.md prometer rejeição por valores de runtime; (4) o rollup por Activity do UICoverageTracker (fix gh15 A-4/K29) é write-only: getActivityCoverageGap/activityRollup/getTotalElements/getTotalInteractions têm zero callers — o invariante 'eviction não perde cobertura' não é observável e o reporting agregado está morto; (5) colisão do sentinela "": widgets com idName="" entram no widgetData sob chave vazia (21 no cryptoapp) e todo nó de runtime sem resourceId casa com essa entrada arbitrária — risco de boost espúrio e input tipado contaminado; (6) generateInputText (T1.3) usa lookup exato sem containment e activity via getTopActivityClassName — dado K53 (id exato quase nunca casa), o typed input é quase inerte; (7) matchKeywords classifica 'account'→NUMBER, 'security…'→URL, '…tel…'→PHONE por substring; (8) budget por activity congelado no primeiro registro; (9) parsing numérico de Config engole NumberFormatException silenciosamente.

CONFIRMADOS DO CATÁLOGO (com ceticismo, no código atual): K02 (last-write-wins, incl. merge do #OptionsMenu no namespace da activity), K20 (chave WTG com sufixo # vs query base — janelas do fixture confirmam), K37 (maxStringPieceLength morto), K58 (screenshot+XML por passo ainda default true), K46/K47/K48 presentes como descritos. Fixes verificados como realmente aplicados no master: K22 (fall-through para +100), K25 (normalizeEventType nos dois lados), K26 (chave xpath|TYPE), K29-LRU (cap 2000 + eviction), K09 (componentPercentage default 0.0 independente de mopDataPath).

SAUDÁVEL: MopScorer é null-safe em todas as entradas e O(1) no menu-gateway; parser MopData tolera campos ausentes/null sem NPE (optStringOrNull/optBooleanOrNull corretos), sentinela complete funciona, containment ≤2 implementado no agente; TypedInputGenerator é sólido (composites de password cobertos); ComponentInfo é imutável e defensivo; SystemBroadcastCatalog degrada graciosamente para catálogo vazio; Utils sem defeitos (helpers de mapa correctos; putIfAbsent/addList ok); XPathBuilder ok; llmPercentage corretamente clampado; randomPickWithPriority correto para prioridades positivas; todas as flags do CLAUDE.md existem no código e (exceto K37) todas as flags de Config têm uso real.


**Cobertura de leitura:** Integralmente lidos (16/16 do escopo): ActivityBudgetTracker.java, ComponentInfo.java, Config.java, InputValueGenerator.java, Logger.java, MopData.java, MopScorer.java, PriorityObject.java, RandomHelper.java, StringCache.java, SystemBroadcastCatalog.java, TypedInputGenerator.java, UICoverageTracker.java, Utils.java, XPathBuilder.java. Trechos rastreados fora do escopo: StatefulAgent.java (construtor, updateStateInternal, moveForward, passe de boost 1340-1510), SataAgent.java (budget/egreedy/dynamicEpsilon/roleta), ApeAgent.java (checkInput/generateInputText), MonkeySourceApe.java (getRandom/getTopActivityClassName), Monkey.java (seed), StateKey.java, GUITree.java; fixture test-apks/cryptoapp.apk.json inspecionado programaticamente.


**Limitações declaradas:** Sem execução em dispositivo/emulador e sem mvn: gatilhos dinâmicos (ex.: stringList vazia no primeiro input; frequência de nós sem resourceId casando a entrada \"\") não foram medidos, apenas rastreados no código e no fixture. Apenas 1 JSON de corpus disponível localmente (cryptoapp) — a afirmação de que existe widget idName=\"\" flagged em algum APK do corpus de 169 não pôde ser verificada (por isso finding 5 é suspeita). Não auditei o produtor (rv-android/gator) nem os traces cmpmop originais; estatísticas citadas (K53, K57, K58) vêm do catálogo e foram usadas só para dimensionar impacto, não como prova.


### C — A.6 Raiz Monkey + ape/

Auditoria read-only da orquestracao principal do APE-RV (Monkey entrypoint, MonkeySourceApe, fila de eventos, MonkeyUtils e os 13 arquivos da raiz do pacote ape). Todos os arquivos do escopo foram lidos integralmente e as suspeitas rastreadas ate call sites (incluindo ApeAgent/SataAgent/StatefulAgent em trechos, e o comando gerado pelo aperv-tool no rvsec).

ACHADOS MAIS GRAVES (todos novos vs catalogo K01-K71): (1) waitForActivity sem timeout — se a activity esperada nunca chega ao topo (crash-no-launch com mKillProcessAfterError, trampoline, race com GrantPermissionsActivity), a corrida inteira degenera em throttles de 100ms ate o fim do orcamento; candidato a explicar parte dos runs <=2-states do K57. (2) totalBadStates>100 acumulativo em ApeAgent lanca StopTestingException e encerra a corrida 'limpa e silenciosamente' antes do tempo, sem reset — o modo continuo protege contra mAbort mas nao contra isso. (3) getFocusedStack retorna null/lanca NPE conforme a versao do dumpsys e SataAgent desreferencia sem checagem — NPE mata o processo. (4) tearDown nao esta em finally: qualquer RuntimeException do caminho quente (K03/K05/K06, achado 3) sai via exit(1) perdendo model, coverage e logs bufferizados — corrompe a medicao do que ja foi explorado. (5) SecurityException em MonkeyActivityEvent encerra a corrida mesmo em --running-minutes e o aperv-tool nao passa --ignore-security-exceptions. (6) checkPackage e dead code: topComp (tasks) e GUITree (janela ativa) nunca sao validados entre si — arvores de overlay podem ser atribuidas a activity errada no modelo.

SAUDAVEL: a ponte Agent→fila e single-threaded e integra — todo evento construido em generateEventsForAction* e de fato enfileirado via addEvent (com log produce/consume/drop simetrico; a unica excecao conhecida e o K33 em ApeFuzzer, fora deste escopo); nao ha perda nem reordenacao na MonkeyEventQueue (throttle=0, addLast trivial). O tratamento de crash/ANR em modo continuo esta correto: appCrashed/appNotResponding registram no historico do agente, matam o processo do app e o loop ignora mAbort, permitindo restart — crashes nao encerram o experimento. StopTestingException e tratada limpa (clearEvent + tearDown completo). clearPackage/grantRuntimePermissions e o interceptor de GrantPermissionsActivity funcionam no fluxo normal; generateActivityEvents tem heuristica razoavel de clear forcado quando o timestamp nao avanca. O parsing de argumentos de Monkey esta correto para o comando que o aperv-tool gera (-p, --running-minutes, --ape). Screenshots vao para threads de ImageWriterQueue com flush em tearDown e tolerancia a bitmap null. checkInteractive acorda a tela via keyevent 26. A deteccao de fora-do-app (checkAppActivity) recupera corretamente no caso comum (onActivityBlocked + clearEvent + startRandomMainApp), exceto no wedge do achado 1.

Prioridade de correcao sugerida: achados 1 e 2 (perda mensuravel de orcamento de exploracao), depois 4 (preservacao de artefatos), 3 e 5 (robustez de terminacao).


**Cobertura de leitura:** Lidos integralmente: MonkeySourceApe.java (1327 linhas), Monkey.java (1607), MonkeyEventQueue.java (55), MonkeyUtils.java (113), e os 13 arquivos de ape/*: Agent.java, AndroidDevice.java (672), ActionFilter.java, BaseActionFilter.java, OnlyAddedUnsaturatedActionFilter.java, Subsequence.java, SubsequenceFilter.java, NodeVisitor.java, ImageWriterQueue.java, BadStateException.java, NoValidActionException.java, StopTestingException.java, TrivialStateException.java. Lidos parcialmente para verificacao de call sites/disparo: ApeAgent.java (100-428), SataAgent.java e StatefulAgent.java (trechos 400-1170), MonkeyActivityEvent.java (integral), Config.java (trecho), rvsec aperv-tool tool.py (trecho _build_main_command).


**Limitações declaradas:** Nao rodei mvn nem testes (proibido) e nao executei nada em dispositivo: nao pude confirmar em runtime (a) a frequencia real do wedge de waitForActivity e do estouro de totalBadStates nos traces do cmpmop (exigiria grep nos .trace do rv-android, fora do repo ape), (b) o formato exato do `dumpsys activity a` na API do emulador RVSec (determina se getFocusedStack retorna null, lanca NPE ou funciona), (c) se startActivityAsUser como shell realmente lanca SecurityException em alguma app do corpus. SataAgent/StatefulAgent/Model/naming foram lidos apenas nos trechos necessarios para rastrear disparos — bugs internos deles ficaram fora desta auditoria (cobertos por K03-K12/K20-K29). Nao verifiquei se o ADBKeyboard esta instalado na imagem do emulador (muda qual caminho de entrada de texto e usado em doInput).


### C — A.7 ape/llm/ + reducer/ (varredura rápida)

Varredura rapida do pacote ape.llm (9 arquivos) + reducer (1 arquivo), todos lidos integralmente, com rastreamento dos call sites no caminho quente (StatefulAgent:165 instancia LlmRouter so quando llmUrl!=null; SataAgent:330-361 chama selectAction nos hooks new-state/stagnation/random; texto digitado flui via node.setInputText -> MonkeySourceApe:854/1244).

SAUDAVEL (importante para a sintese): (1) LlmRouter.selectAction cumpre o contrato de nunca lancar — todos os sub-passos tem try/catch e o loop do Monkey esta protegido; (2) o fix K31 esta presente (screenshot null -> breaker.recordFailure); (3) LlmCircuitBreaker e uma maquina de estados correta e sincronizada (CLOSED/OPEN/HALF_OPEN, half-open failure re-abre com timestamp novo); (4) CoordinateNormalizer clampa corretamente a [0,dim-1]; (5) ImageProcessor guarda null/zero e preserva aspect ratio; (6) ApePromptBuilder e integralmente defensivo (safe* helpers, nunca lanca) e as 5 variantes de prompt sao coerentes; (7) SglangClient trata arguments como objeto OU string JSON e expande o formato de array \"x\":[x,y] do Qwen3-VL; (8) shouldRouteRandom nao consome o RNG do Monkey quando llmPercentage=0 (nao perturba braços sem LLM); (9) buildParsedAction sem coordenadas cai em (0,0) que e rejeitado pelo boundary-check — sem tap acidental; (10) as regexes de fix de JSON malformado ('\"x\": 352, 782' etc.) nao colidem com chaves legitimas como \"max\".

ACHADOS (8, todos novos, nenhum bloqueante): os mais relevantes sao (a) o fallback de screenshot via androidx.test que e codigo morto comprovado (jar so tem classes.dex, sem androidx) combinado com a assinatura SurfaceControl removida em API 29 — em Q o braco LLM degradaria silenciosamente para SATA via breaker; (b) fixMalformedJson/findMatchingBrace sem awareness de literais de string, capazes de corromper o argumento text ou truncar o JSON no fallback nao-nativo (~50% das respostas); (c) llmTimeoutMs nao e um deadline total da chamada HTTP (read timeout por operacao), permitindo bloqueio do loop single-threaded muito alem de 15s com servidor gotejante — agrava o mecanismo de K30; (d) o catch-all de selectAction e o unico caminho de falha que nao penaliza o breaker (mesma classe do K31 pre-fix, hoje sem rota de disparo conhecida). No reducer: begin nunca avanca (crashLogs cumulativos em multiplos crashes) e mensagens de log copy-paste — impacto restrito pois reducer/ nao e compilado pelo Maven nem entra no ape-rv.jar (confirmado). Dead code menor: JSON_INLINE_PATTERN nunca usado; bestTolerance dead store; retry de long_click sem filtro de ActionType.

Nenhum achado do escopo coincide com itens do catalogo K01-K71 (K30/K31 tangenciam LlmRouter mas tratam de frequencia de chamadas e do fix ja aplicado, respectivamente).


**Cobertura de leitura:** Lidos integralmente: ape/llm/ApePromptBuilder.java, CoordinateNormalizer.java, ImageProcessor.java, LlmCircuitBreaker.java, LlmException.java, LlmRouter.java, ScreenshotCapture.java, SglangClient.java, ToolCallParser.java; reducer/ape/Reducer.java. Lidos parcialmente para verificacao de call sites: agent/SataAgent.java (linhas 320-361), agent/StatefulAgent.java (grep 140/165), tree/GUITreeNode.java (470-495), utils/Config.java (greps de flags llm*), pom.xml, listagem do target/ape-rv.jar.


**Limitações declaradas:** Sem dispositivo/emulador: a falha do SurfaceControl.screenshot em API 29+ e inferida da evolucao da API hidden do Android, nao testada em runtime (por isso suspeita). O comportamento de org.json do Android com chaves duplicadas/trailing garbage (relevante para fixMalformedJson) nao foi executado. Nao rodei mvn (proibido) nem os testes existentes do pacote llm. A questao de o variant default ape_current nao incluir buildWidgetMetadata (T1.1 so em v13/v17) foi observada mas nao reportada como defeito por estar fora do foco crash/NPE/parsing/timeout e possivelmente ser intencional por desenho de experimento.


### C — A.8 Frente 2 — schema <apk>.json

FRENTE 2 — fidelidade schema<->parser do <apk>.json. Metodo: leitura integral de MopData.java (844 l), cryptoapp.apk.json (2220 l) e MopScorer.java; rastreio dos call sites quentes (StatefulAgent passe de boost/containment, ApeAgent fuzz tipado T1.3, ApePromptBuilder, GUITreeBuilder:587 origem do resourceId); survey python recursivo de uniao/frequencia de chaves sobre 169 JSONs legados (data/apks) + 12 exemplares unicos novos (results/*/instrumented_apks) + fixture.

TABELA campo JSON -> status no parser (schema novo):
- package, mainActivity -> consumido (so p/ sanity check T1.7, morto no master)
- complete -> consumido (sentinela; AUSENTE em 169/169 data/apks e 7/12 results -> arquivo inteiro rejeitado)
- reachability[].methods.{signature,reachesTarget,directlyReachesTarget} -> consumido; 'reachable','name','componentType','isMain' -> guardados, nao usados no scoring
- reachesMop/directlyReachesMop/mopMethods (vocabulario legado) -> IGNORADOS (169 exemplares)
- windows[].{id,type,name,isMain} -> consumido; widgets[].id -> guardado, nunca usado p/ matching
- widgets[].{idName,type,text,hint,inputType,entries,prompt,spinnerMode,contentDescription,tooltipText} -> consumidos; os 4 ultimos presentes so em 3/12 exemplares novos (ausencia -> null -> sufixo omitido: gracioso)
- listeners[].{eventType,handler} -> consumido; handlerReachesTarget/handlerDirectlyReachesTarget -> esperado-mas-SEMPRE-ausente (0x no corpus, fallback OK)
- transitions[].* -> consumido; so type=='click' alimenta WTG (select/touch/item_click excluidos)
- components.* -> consumido integralmente; permission/readPermission/writePermission/intentFilters.data ausentes nos legados -> defaults null/empty (gracioso)
Nao ha campo do schema NOVO que o parser ignore silenciosamente.

ACHADOS PRINCIPAIS (novos): (1) widgets de janelas DIALOG indexados pela classe do dialog — chave que nunca casa com a activity de runtime; ~184 widgets flagged em 17 apps estruturalmente fora do alcance do scorer (+500/+300 impossiveis); (2) idName=\"\" e armazenado como chave e casa com todo no de runtime sem resource-id (getViewIdResourceName null -> \"\"), com guardas isEmpty inconsistentes entre MopScorer.score/ApeAgent (sem guarda) e scoreWtg/PromptBuilder (com guarda); nos exemplares novos 6/6 widgets flagged de id vazio sao clobberados por LWW; (3) Spinner: consumer pergunta 'itemSelected', producer emite 'select' — token irreconciliavel pela normalizacao; (4) uma JSONException em 1 elemento descarta o arquivo inteiro; (5) toda falha de load colapsa em null + warn -> braco sata_mop roda como SATA puro sem sinalizacao (mecanismo que escondeu K01); (6) mopStrictPackageMatch segue morto no master apesar de K24 constar como corrigido.

SAUDAVEL: parser 100% opt-based (sem NPE em campo ausente/null; optStringOrNull/optBooleanOrNull consistentes); sentinela e rejeicao do vocabulario legado testadas (MopDataTest:359-376); normalizeEventType resolve o casing (K25) para os tokens que existem; containment B3 presente e limitado a ±2 niveis; scoreWtg e PromptBuilder guardam shortId vazio; leitura UTF-8 correta; 170/170 arquivos do corpus parseiam sem excecao; extractShortId casa corretamente idName estatico bare com 'pkg:id/name' de runtime quando o id existe; todos os campos emitidos pelo producer novo sao consumidos.


**Cobertura de leitura:** Lidos integralmente: /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/src/main/java/com/android/commands/monkey/ape/utils/MopData.java (844 linhas), /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/test-apks/cryptoapp.apk.json (2220 linhas, em 2 paginas), /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/src/main/java/com/android/commands/monkey/ape/utils/MopScorer.java (157 linhas). Lidos parcialmente (trechos relevantes + grep): StatefulAgent.java (passe MOP/WTG/coverage 1340-1520, load :162, triggering :995-1120), ApeAgent.java (:180-230), ApePromptBuilder.java (:442-580), GUITreeBuilder.java/GUITreeNode.java (origem resourceId/className), aperv-tool tool.py (resolucao do JSON). Corpus: 169 JSONs de rvsec/rv-android/data/apks + 12 exemplares unicos de results/*/instrumented_apks processados via python3 (uniao recursiva de chaves, frequencias, simulacao da semantica do parser).


**Limitações declaradas:** Nao executei mvn nem rodei o APE em dispositivo — os achados de runtime (chave DIALOG vs activity, match \"\" para nos sem id) foram rastreados por codigo + corpus, nao observados em trace ao vivo. Nao inspecionei o producer (gator/RvsecAnalysisClient) alem do necessario para confirmar vocabulario/frequencias — a semantica exata do name de janelas DIALOG foi inferida dos JSONs reais. Nao verifiquei se algum worktree (mop-fairtest) ja corrige K24/K02; a auditoria cobre apenas o master. A comparacao 'campo consumido' vale para o schema novo (gh60); nao validei versoes intermediarias do producer entre 2026-03 e o atual.


### C — A.9 Frente 3 — log .trace

FRENTE 3 — suficiencia do .trace para diagnostico pos-hoc (master f70f986).

Emissao: Logger (stdout, prefixo [APE], PrintStream sincronizado — sem interleaving intra-linha) + produce/consume.log (ApeRRFormatter, com clockTime, agentTimestamp e inputText, flush por acao) + action-history.log/sataGraph.* no tearDown.

RESPOSTAS: (Q1) Candidatas NAO-escolhidas SAO logadas a cada passo (SataAgent.printStrategy→State.printActions, com P pos-boost) — corrige parcialmente K60 — mas sem decomposicao mop/wtg/cov/menu por candidata, sem visitedCount e sem step-id: flip de argmax NAO e reconstruivel. (Q2) NAO da para distinguir 'por causa do boost': decision_source so assume SATA/LLM/Budget (enum MOP/Coverage/WTG/Menu/Fuzz/Component morto); a sub-estrategia (buffer/backtrack/greedy/egreedy) so existe em linha solta sem chave, join posicional via bracket 'begin/end step [N]'. (Q3) Cobertura intra-tela: reconstruivel apenas por stitching fragil (Create state [W=][A=] + candidatas + linha condicional de Coverage boost com denominadores inconsistentes); sem dump final (getters implementados com zero call sites). (Q4) aperv nao loga NADA sobre violacoes MOP; cadeia JavaMOP→logcat RVSEC→contagem e externa ao repo (limitacao); do lado do trace falta wall-clock no [APE-STEP] para join com timestamps do logcat (produce.log e a unica ponte). Riscos externos tipicos: buffer logcat, chatty rate-limit colapsando linhas identicas (violacoes repetidas!), truncamento ~4KB.

ACHADO MAIS GRAVE (novo): [APE-STEP] e emitido antes de checkRestart/checkInput (ApeAgent:325) — a cada restart (default a cada 100-300 passos + restarts de estabilidade) a linha registra acao nunca executada, e o widget ja foi marcado interagido/visitado (contamina cobertura e boost, nao so o log). Bonus fora-de-log: StatefulAgent.checkFuzzing(ModelAction) e overload morto (dispatch estatico usa a versao base) — guard anti-fuzz em activities novas nunca roda.

PROPOSTA MINIMA (5 mudancas, caminho quente ~0 custo): (1) [APE-STEP]: acrescentar clock=%d e strategy=%s (SataEventType setado junto do decisionSource) — StatefulAgent:1266. (2) Substituicao por restart: 1 linha '[APE-STEP-SUBST] step=N replaced_by=%s' em ApeAgent.checkRestart:249/259. (3) Candidatas: incluir [VC=%d][MOP=%d][WTG=%d][COV=%d][MENU=%d] em ModelAction.resolvedInfo (linha 140) — reaproveita printActions, zero linhas novas. (4) MopData.load (linha 240): acrescentar package=, parsedWidgets= (pre-colisao), droppedNullId=, collided= (2 contadores no parseWindows) e passar expectedPackage real no load. (5) tearDown: dump '[APE-COV-FINAL] activity=%s gap=%.2f widgets=%d' via getActivityCoverageGap ja existente (3 linhas) + 'Create state' com new=true|false (Model:467).

SAUDAVEL: linha '[APE-RV] MOP boost' incondicional com boosted/total/maxBoost/containment (permite medir substrato por tela); brackets de passo com Elapsed e memoria; EGreedy value/epsilon logados; GSTG statistics por passo (states/edges/unvisited); MopData.load falha ruidosamente (arquivo/JSON/sentinela); produce.log com inputText e clock por acao; [APE-LLM-TEL] com latencia; tearDown dump completo (activities, naming, counters). NamingFactory loga refinamentos e blacklists de forma rastreavel (fragmentacao K27 e diagnosticavel no trace).


**Cobertura de leitura:** Lidos integralmente: Logger.java, StatefulAgent.java, SataAgent.java, ApeAgent.java, RandomAgent.java, MonkeySourceApe.java, MopScorer.java, UICoverageTracker.java, ActivityBudgetTracker.java, ModelAction.java, Action.java, Model.java, State.java, Graph.java, NamingFactory.java, MopData.java, ApeRRFormatter.java. Parcial (grep de emissores): LlmRouter.java, Config.java.


**Limitações declaradas:** Nao verificado: (a) cadeia JavaMOP→logcat RVSEC/RVSEC-COV→contagem (fora deste repo, em rv-android/aperv-tool) — dedup, rate-limit do chatty e buffer do logcat sao risco plausivel mas nao rastreavel aqui; (b) comportamento de flush do stdout sob kill -9 no fim dos 300s (perda de cauda do .trace) — nao testado em dispositivo; (c) LlmRouter.java e ReplayAgent.java nao lidos integralmente (fora do nucleo do escopo); (d) nenhum .trace real foi parseado nesta auditoria (nao havia arquivos no repo) — as conclusoes sobre parsers derivam do formato emitido no codigo; (e) nao rodei mvn/testes (proibido).


### C — A.10 Frente 4 — corretude da change (worktree)

VEREDICTO POR ALEGACAO (worktree ape-mop-fairtest, 110 testes das classes alteradas passam; mvn compile OK):

#1 Parser fidelity — CORRIGE. parseWindows agora retem o flag mais forte por colisao (mopRank, ordem-independente), nao armazena idName vazio (contado em droppedFlaggedNoId, logado) e re-chaveia wtgTransitions por base activity nas duas pontas, com precompute do menu re-apontado (K02/K20 enderecados; testes pairwise nos dois sentidos). Residuos: colisao flagged-vs-flagged de mesmo rank ainda perde um widget (inerente ao chaveamento por shortId), comentario obsoleto '+100 fallback' (K45), teste de colisao tripla ausente (K44).

#2 Boost discriminativo — PARCIAL. Remocao do +100 e completa e correta (K10/K22 resolvidos; sem referencias mortas). O short-circuit, porem, (a) e sombreado por EARLY_STAGE: alvos MOP unvisited-by-name continuam consumidos pela roleta de K12 antes do ramo epsilon-greedy — o argmax so cobre a janela residual; (b) nao chama checkDisableRestart e markVisited precede checkRestart, entao um restart no mesmo passo queima permanentemente o one-shot do alvo; (c) e ainda precedido por Back/Menu-unvisited (primeira visita a tela via esse ramo pressiona Back). Ordem dos 3 short-circuits: Back/Menu > MOP > (guard form via exclusao) — deterministica e comentada, mas o guard form e ineficaz (ver #5).

#3 decision_source — PARCIAL. Implementado como largest-boost em EARLY_STAGE/EPSILON_GREEDY com tie MOP>WTG>Menu>Coverage, honestamente documentado como nao-contrafactual. Mas: sub-ramos path-based de EARLY_STAGE e os short-circuits Back/Menu mislabelam (correlacao, K42); formBoost fica fora da atribuicao (mecanismo form invisivel); sem strategy=/clock= no [APE-STEP]; substituicao por restart (achado mais grave da Frente 3) segue nao logada e contaminando a linha.

#4 UICOV dump — CORRIGE (K59 fechado). Read-only verificado, byType e Locale.ROOT corretos, mopReach via predicado. Omissoes: estados LRU-evictados e activityRollup fora do dump; sem agregado por activity.

#5 FormCompletion — NAO CORRIGE / INTRODUZ-REGRESSAO. (i) BLOQUEANTE: o fill deterministico e codigo morto — moveForward() anula newState antes de checkInput, inFormCompletionContext() e sempre false; o toss 0.8 legado permanece. (ii) O predicado 'unfilled' nunca converge (inputText e transiente por captura; K39 confirmado estaticamente): contexto de form permanente, submit excluido do short-circuit para sempre. (iii) INV-FORM-06 e derrotado por EARLY_STAGE-roleta e pelo tiebreak por priority de greedyPickLeastVisited — o submit (mop500+W_SUBMIT100) continua sendo clicado antes dos campos; W_SUBMIT piora marginalmente vs master. (iv) Submit-candidate arbitrario em empates de containment (pode ser scroll) e lone-Button ignora texto (K40). So o boost W_FILL na roleta opera como projetado.

FRENTE 3: Q1 nao fechada (sem decomposicao por candidata). Q2 parcial (atribuicao sim, sub-estrategia/clock/subst nao). Q3 majoritariamente fechada pelo UICOV dump. Q4 nao enderecada. Itens 1,2,3,5 da proposta minima nao implementados; item 4 parcial (so droppedFlaggedNoId; load segue 1-arg). checkFuzzing overload morto intocado.

SAUDAVEL: testes novos bem construidos (JSON sintetico via parser real; Unsafe para State); resetBoosts/priority re-derivados a cada passo (sem acumulacao); dump comprovadamente sem mutacao; ordem MOP-pass -> form-pass garante mopBoost populado; exclusao por identidade de objeto e estavel dentro do State.


**Cobertura de leitura:** Lidos integralmente (worktree): FormCompletion.java, SataAgent.java, ModelAction.java, MopData.java, MopScorer.java, UICoverageTracker.java, SataAgentMopShortCircuitTest.java, FormCompletionTest.java (parcial-quase-total), diff completo master->worktree de src/main; os 4 design.md + tasks.md + proposal/spec de form-completion, mop-discriminative-boost, exploration-observability, mop-parser-fidelity. Lidos parcialmente (trechos relevantes): StatefulAgent.java (640-720, 1100-1550, greps de call-sites), ApeAgent.java (120-360), State.java (70-300), ActionFilter.java, Config.java, GUITreeNode.java (isEditText/inputText), openspec/specs/mop-guidance/spec.md (greps). Executado: mvn test das 6 classes alteradas (110/110 pass).


**Limitações declaradas:** Sem execucao em dispositivo/emulador: os achados de comportamento (fill morto, nao-convergencia, submit-before-fill, queima por restart) foram verificados por rastreamento estatico do caminho de execucao, nao por trace real. Nao rodei a suite completa (so as classes alteradas). O comportamento de accessibility className de Compose/AndroidX (F-K40) e afirmado por conhecimento da plataforma, nao testado. Nao auditei RandomAgent/ReplayAgent quanto a interacao com o form-pass (herdado de StatefulAgent) nem o LlmRouter. Cadeia JavaMOP->logcat (metrica mop-violacoes) permanece externa ao repo, avaliada apenas indiretamente.


### C — A.11 Frente 4 — testes e specs da change (worktree)

MVN TEST (worktree ape-mop-fairtest): 381 run / 0 failures / 0 errors / 15 skipped (25 classes; surefire agregado). Bate com a alegacao de tasks 6.6. `mvn package` tambem passa (d8 ok, warnings pre-existentes).

AVALIACAO DOS TESTES: nao sao tautologicos no parser — MopDataTest (+6) e MopScorerTest (+2) passam JSON sintetico pelo parser REAL e verificam getWidget/getWtgTransitions/scoreWtg fim-a-fim (INV-MOP-19 nos 2 sentidos, INV-MOP-20 com contador e activityHasMop, WTG base-keying fonte+alvo, integracao parser->scorer). UICoverageTrackerTest (+7) cobre bem formato, byType, mopReach, 1-linha-por-estado e INV-COV-07 (read-only) via captura de stdout. Ja FormCompletionTest (3 testes) cobre so o ramo mopBoost e nulls, e SataAgentMopShortCircuitTest cobre so o ranking puro de pickBestMopTarget — o gating unvisited (INV-SEL-MOP-01) e o wiring real do guard INV-FORM-06 tem cobertura zero, embora fossem host-testaveis (resolveAt publico, buildEmptyNode). ModelActionTest: acessor trivial, adequado.

REQUIREMENT->STATUS: [#0 loader INV-MOP-19/20] impl+testado, mas premissa '"" inalcancavel' e falsa (achado 4). [#0 WTG INV-WTG-04/05] impl+testado, precompute re-apontado, regressao do gateway verde. [#2 scorer discriminativo] impl ok; mopWeightActivity/INV-MOP-07 ausentes de src/ (grep gate confirmado). [#2 short-circuit INV-SEL-MOP-01/02] implementado conforme spec, porem sombreado pelo EARLY_STAGE (achado 2) e gating nao testado. [#3 atribuicao] regra e tie-precedence conforme spec; contaminacao Menu nos short-circuits unvisited (achado 7); zero teste unitario. [#3/INV-SEL-04] linha unica preservada, ganhou form=%d. [#4 UICOV/INV-COV-07] fiel ao spec, bem testado. [#1 form-completion] INV-FORM-01/02/04/05 ok; INV-FORM-03 vacuo (achado 6); INV-FORM-06 furado no EARLY_STAGE (achado 1) e nunca-liberado por nao-convergencia (achado 3); deteccao inerte em AndroidX/Compose (achado 5). Tasks device (6.2-6.4, 4.2-4.3) honestamente abertas; 2.2/#2 e 3.4/#1 marcadas [x] com testes prometidos ausentes (achado 8).

SAUDAVEL: parser fidelity e o dump UICOV sao as duas partes solidas da mudanca — corrigem K02/K20/K59 de fato, com testes reais e sem regressao (suite toda verde). A remocao do fallback +100 esta completa e coerente no codigo. O risco concentrado esta na interacao #1 x #2 x cadeia SATA: o mecanismo central do fair-test (short-circuit + guard de formulario) opera quase todo fora do caminho de selecao dominante.


**Cobertura de leitura:** Lidos integralmente: FormCompletion.java, FormCompletionTest.java, SataAgentMopShortCircuitTest.java, MopScorer.java, diffs completos de src/main e src/test (ApeAgent, SataAgent, StatefulAgent, ModelAction, Config, MopData, MopScorer, UICoverageTracker, ModelActionTest, MopDataTest, MopScorerTest, UICoverageTrackerTest), proposal.md+design.md+tasks.md+specs/*/spec.md das 4 changes (mop-parser-fidelity, mop-discriminative-boost, form-completion, exploration-observability). Lidos por regiao: SataAgent (cadeia de selecao 300-540, 610-660, 780-900, 1040-1130, tearDown), StatefulAgent (129-205, 1266-1580), MopData (precompute/getWidget/extractShortId), GUITreeNode (isEditText/inputText), ActionFilter, Logger, openspec/specs/{mop-guidance,action-selection,ui-coverage}.


**Limitações declaradas:** Sem execucao em device/emulador: as validacoes deferidas (form-completion 6.2-6.4; exploration-observability 4.2-4.3) nao foram rodadas, entao os efeitos probabilisticos (frequencia real de EARLY_STAGE vs EPSILON_GREEDY neste build, taxa de submit prematuro, comportamento do inputText no fluxo real) sao inferidos do codigo + estatisticas documentadas do cmpmop, nao medidos. `openspec validate --strict` nao foi executado. Nao verifiquei o produtor (rvsec-gator) nem os JSONs reais dos 19 APKs contra o parser novo. A analise de sombreamento do short-circuit depende de actionDiffer/useActionDiffer default (nao rastreei o valor de useActionDiffer em runtime).


## Anexo D — Catálogo de achados já documentados usado para dedup (K01-K71)

Compilado na fase 1 a partir de `20260622_investigacao_mop.md`, `analise_claude_sonnet5.md` e `20260621_plano_correcao...md`. Referenciado pelas colunas Status/CONHECIDO acima.

```

[K01] docker/rvandroid/Dockerfile:13 — a imagem Docker nunca compila o ape e assa o jar legado commitado (c5d76943, parser `reachesMop`) contra JSONs `reachesTarget`, zerando o boost em 0/147.153 avaliações e invalidando o experimento de junho (fonte: plano_correcao)
[K02] MopData.java:308-320 — PARSER-DROP: `parseWindows` guarda widgets num Map<idName> última-escrita-vence, descartando 45% (1.165/2.578) dos widgets flagged antes do scoring (labnex/duress perdem 100%) (fonte: investigacao_mop)
[K03] GUITree.java:284 — TREE-01: `Arrays.binarySearch` testado com `index == -1` em vez de `index < 0`, indexando `currentNodes` com ponto de inserção negativo arbitrário → risco de ArrayIndexOutOfBoundsException no caminho quente de `Model.contains` (fonte: analise_sonnet5)
[K04] Naming.java:252-254 — NAM-01: `hasChild()` invertido (retorna true quando NÃO há filhos), dormant hoje mas corrompe refinamento se `activityManagerType=activity` (fonte: analise_sonnet5)
[K05] MonkeySourceApe.java:792 — MSA-01: se `updateState` retornar null, NPE não tratada mata a thread de eventos do Monkey (handler só captura StopTestingException) (fonte: analise_sonnet5)
[K06] Namelet.java:156-162 — NAM-02: `filter()` engole XPathExpressionException e retorna null, e `Naming.select` (Naming.java:456-457) não checa null → NPE potencial no caminho quente de naming (fonte: analise_sonnet5)
[K07] Graph.java:1287-1290 — GRAPH-01: auto-atribuição em `rebuildHistory` (`edge.firstVisitTimestamp = fv` reatribui o valor antigo), deixando timestamps obsoletos após todo refinamento de naming (fonte: analise_sonnet5)
[K08] SataAgent.java:~269 — SATA-01 (suspeita): BFS de `checkBackTrack` checa `!visited.contains(state)` mas marca `state` em vez de `target` como visitado — padrão clássico de BFS incorreto (fonte: analise_sonnet5)
[K09] Config.java:169-170 — confound A-3: `componentPercentage` default derivado de `mopDataPath` (0.05 vs 0.0) fez os braços sata×sata_mop diferirem em duas variáveis (548 vs 45 traces com triggering) [corrigido gh15] (fonte: plano_correcao)
[K10] StatefulAgent.java:1383 — MEC-UNIF: ~73% dos boosts não-zero são o +100 uniforme de activity (`activityHasMop`), aplicado a todos os widgets-alvo — não re-ranqueia nada (fonte: investigacao_mop)
[K11] StatefulAgent.java:1447 — MEC-TIE: coverage boost +100 tem magnitude igual ao MOP +100 e premia o oposto (widget não-visitado); co-disparam 100/100 em 16% dos passos boostáveis (fonte: investigacao_mop)
[K12] SataAgent.java:414-435 — o boost é consumido por roleta/desempate (randomPickWithPriority; greedyPickLeastVisited só desempata igual-visita), não argmax — sinal fraco é diluído mesmo disparando em 97,5% das decisões (fonte: investigacao_mop)
[K13] <apk>.json (producer/gator) — colapso do substrato: só 19/169 APKs têm ≥1 widget estaticamente discriminativo; o plano previa ~98 (errado por ~5×) (fonte: investigacao_mop)
[K14] MopData.java:281-282 — o join exato `bySignature` handler↔reachesTarget acerta só 0,43% (4.938/1.150.487 listeners); `handlerReachesTarget` é emitido 0× pelo producer (fonte: investigacao_mop)
[K15] RvsecAnalysisClient.java:277-286 — filtro `isAppClass`/`codePackage` descarta da serialização `reachability[]` os handlers R8-renomeados fora do pacote (80,6% dos handlers), quebrando o join (fonte: investigacao_mop)
[K16] ReachabilityEnricher.java:71 — `enrichWidget()` é stub (`return EMPTY;`), zero call-sites: o producer nunca emite reachability por-handler (fonte: plano_correcao)
[K17] app.passwordstore (Compose) — apps Jetpack Compose expõem 0 listeners na árvore (código em `$$ExternalSyntheticLambda`), tornando steering widget-level via listener impossível por construção (fonte: investigacao_mop)
[K18] ApeAgent.java:184-196 — UI-FORM: não existe SET_TEXT; texto só entra via MODEL_CLICK probabilístico (`toss(inputRate)`) no EditText clicado, sem sequência preencher-todos→submeter → P(form completo) ínfima (fonte: investigacao_mop)
[K19] ape.properties/cmpmop — discrepância não explicada: taxa efetiva de preenchimento de EditText ~42% vs `inputRate=0.8` configurado (fonte: investigacao_mop)
[K20] MopData.java:468 vs MopScorer.java:84 — WTG-KEY: `wtgTransitions` keyed por nome-de-janela `#`-sufixado mas a query usa base-activity → ~34 steering-edges válidos silenciados (fonte: investigacao_mop)
[K21] SataAgent.java:224 — A-5: `logActionSelected` sobrescreve `decision_source` para SATA sempre (0 MOP/Coverage/WTG em 132.552 passos); early-returns de LLM/componente/budget contornavam a atribuição [corrigido gh15] (fonte: investigacao_mop)
[K22] MopScorer.java:40-51 — B4: `return 0` precoce quando widget resolvido sem flag impede alcançar o fallback `activityHasMop→+100` [corrigido gh15] (fonte: plano_correcao)
[K23] MopData.java:620-623 — B3: lookup exato em dois níveis sem reconciliação pai/filho — static flaga id do pai, runtime clica o filho → miss [corrigido gh15, containment ≤2] (fonte: plano_correcao)
[K24] MopData.java:148-150 — B9: `mopStrictPackageMatch` era código morto (load 1-arg → `load(path,null,null)`, checagem estrita nunca disparava) [corrigido gh15] (fonte: plano_correcao)
[K25] MopScorer.java:138-143 — B6: mismatch de `eventType` camelCase (consumer) vs snake_case (producer), latente e mascarado pelo fallback OR-agregado [corrigido gh15] (fonte: plano_correcao)
[K26] UICoverageTracker.java:191-199 — B5: chave de cobertura por `toXPath()` colapsa tipos de ação distintos no mesmo target [corrigido gh15, key xpath|TYPE] (fonte: plano_correcao)
[K27] StateKey.java:45-62 — fragmentação: `stateData` keyado por StateKey que inclui `naming` → uma Activity fragmenta em ~22 (máx 84) mapas de cobertura, inflando o "gap" (fonte: plano_correcao)
[K28] UICoverageTracker.java:65-103 — B-Cov: widget dinâmico registrado via `recordInteraction` é esquecido quando some de `actions` na re-registração (fonte: plano_correcao)
[K29] UICoverageTracker.java (stateData) — sem poda/evicção: crescimento monotônico → risco latente de OOM [corrigido gh15, LRU+rollup] (fonte: plano_correcao)
[K30] LlmRouter/Config — braço LLM regride por latência: `llmPercentage` agressivo gasta 46–49% do orçamento de 300s em chamadas (~2× menos ações), não por qualidade (match 84%) (fonte: plano_correcao)
[K31] LlmRouter.java:245-249 — screenshot null (janelas secure) não chamava `breaker.recordFailure()` → retentativa a cada passo; 3 apps 100% null viraram "SATA disfarçado" [corrigido gh15] (fonte: plano_correcao)
[K32] CLAUDE.md:133 — doc obsoleta: `llmMaxCalls` citado mas removido do código em gh12 (`e2d9f49`) [linha removida via gh15] (fonte: plano_correcao)
[K33] ApeFuzzer.java:167-192 — FUZZ-01: evento pinch/zoom construído mas nunca enfileirado (`events.add` ausente) — feature morta silenciosa (fonte: analise_sonnet5)
[K34] ApeFuzzer.java:173 — FUZZ-02: bug de precedência de operador no dimensionamento do array, mascarado por FUZZ-01 (fonte: analise_sonnet5)
[K35] MonkeySourceApe.java:959-965 — MSA-02 (suspeita): `stopTopActivity` mata `getRunningAppProcesses().get(0)` sem garantia de que seja o processo do app-alvo (fonte: analise_sonnet5)
[K36] IndexNamer.java — NAM-03: `IndexName` sem `equals()`/`hashCode()` — qualquer Set/Map keyado por equals trataria índices iguais como distintos (fonte: analise_sonnet5)
[K37] Config.java — CFG-01: flag `maxStringPieceLength` definida mas sem nenhum uso — código morto (fonte: analise_sonnet5)
[K38] openspec/specs/mop-guidance/spec.md — spec NÃO sincronizada com o worktree mop-fairtest: ainda documenta `mopWeightActivity`, o fallback +100 e INV-MOP-07 como vigentes — contradiz o código novo (fonte: analise_sonnet5)
[K39] FormCompletion.java — premissa de convergência (GUITree do passo seguinte reflete o texto digitado; identidade do widget estável) não verificada em dispositivo; zero cobertura automatizada do laço de preenchimento → risco de re-boost sem progresso (fonte: analise_sonnet5)
[K40] FormCompletion.java (selectSubmitCandidate) — heurística de submit erra dos dois lados: falso-positivo (Button não-relacionado na tela) e falso-negativo (Compose/AndroidX sem "Button" no className) (fonte: analise_sonnet5)
[K41] SataAgent.java (attributeDecisionSource) — zero teste unitário cobre a lógica de atribuição de fonte de decisão (fonte: analise_sonnet5)
[K42] SataAgent.java (EARLY_STAGE) — risco correlação≠causalidade: `decision_source=MOP` pode ser atribuído a ação cujo boost coincidentemente é o maior mas que não foi escolhida por causa dele (fonte: analise_sonnet5)
[K43] SataAgent/StatefulAgent — acoplamento frágil: `selectSubmitCandidate` recomputado 2× por passo, seguro hoje só porque é puro — edição futura pode quebrar sem aviso (fonte: analise_sonnet5)
[K44] MopDataTest — gap de teste: falta caso de colisão tripla (direct/transitive/unflagged no mesmo idName, embaralhado) (fonte: analise_sonnet5)
[K45] MopData.java:329 — comentário viola P4: descreve `activityHasMop` como substrato do fallback +100 que já não existe pós-#2 (fonte: analise_sonnet5)
[K46] MopData.java:621-659 — `precomputeMopOptionsMenus` deriva a chave por substring-stripping ad hoc em vez do helper `baseActivity()`; quebra silenciosa se o nome de janela contiver `#` anterior (fonte: analise_sonnet5)
[K47] MopData.java:~689-693 — `extractShortId` confla "sem resourceId" e "resourceId malformado" no mesmo sentinela `""`; o contador `droppedFlaggedNoId` herda a conflação (fonte: analise_sonnet5)
[K48] MopData.java (baseActivity) — `indexOf('#')` sem validação: mistruncamento teórico se um nome de classe de activity contiver `#` literal (fonte: analise_sonnet5)
[K49] StatefulAgent.java (passe de boost) — magnitude não endereçada: o boost MOP soma sobre uma `priority` SATA já alta (unvisited +20, aliased, edges) — proporção do sinal não tratada (fonte: analise_sonnet5)
[K50] MopScorer (pós-#2) — activity cujos widgets flagged foram todos descartados no parse pontua idêntico a activity sem MOP; nenhum teste verifica se o sinal restante basta para influenciar seleção (fonte: analise_sonnet5)
[K51] <apk>.json (12 APKs) — DATA0: `windows[]` populado mas `widgets[]` genuinamente vazio → 0 widgets carregados (falha do analisador estático upstream) (fonte: investigacao_mop)
[K52] <apk>.json — 99,03% dos widgets carregados são present-but-unflagged: só podem receber o +100 uniforme ou 0 (fonte: investigacao_mop)
[K53] traces cmpmop — dos boosts discriminativos, +500 é 100% resgatado por containment e +300 68%: o resource-id de runtime quase nunca casa o idName estático (fonte: investigacao_mop)
[K54] traces cmpmop — cobertura intra-tela mediana 0,667 e telas MOP-bearing piores (0,567); MOP-on não melhora (fonte: investigacao_mop)
[K55] traces cmpmop — só ~32,5% das telas MOP alcançáveis são alcançadas em 300s; telas profundas (loki, tubular) tardias ou perdidas (fonte: investigacao_mop)
[K56] traces cmpmop — ~26% do orçamento de ações desperdiçado em BACK+MENU, idêntico entre braços (fonte: investigacao_mop)
[K57] traces cmpmop — ~16% dos runs travados em loops de login/permission-wall (≤2 states, 0 edges novos; p90 tail=71%) (fonte: investigacao_mop)
[K58] Config (takeScreenshotForEveryStep/saveGUITreeToXmlEveryStep) — screenshot+XML por passo custam 20–40% do throughput de passos por run (fonte: investigacao_mop)
[K59] UICoverageTracker.java — OBS-UICOV: sem método de dump de cobertura UI no trace (só `coverage=` por ação) [corrigido no worktree, #4] (fonte: investigacao_mop)
[K60] [APE-STEP] (formato) — limitação: loga só a prioridade da ação escolhida, não das candidatas → flip de argmax não reconstruível por passo (fonte: investigacao_mop)
[K61] traces cmpmop — divergência sata_mop×sata fica dentro de ~1–2pp do baseline de RNG do mesmo braço e o sinal inverte por critério de match: nenhum steering robusto acima do ruído (fonte: investigacao_mop)
[K62] rv-android/modules/aperv-tool/.../ape-rv.jar — binário commitado git-tracked com drift de três vias (commitado 237019 B ≠ working-tree 236967 B ≠ jars A/B do host) [deletado via gh71] (fonte: plano_correcao)
[K63] docker/rvandroid/Dockerfile:25-26 — há gate de frescor para o dexlib2 mas nenhum equivalente para o `ape-rv.jar` (fonte: plano_correcao)
[K64] docker-compose.*.yml + calibration_orchestrator.py:245 — ~10 composes fazem bind-mount `:ro` de jar do host por cima do jar assado: workaround manual sistemático da obsolescência (fonte: plano_correcao)
[K65] aperv-tool/.../aperv/.gitignore — vazio (0 B) com jar force-added; doc do módulo afirma "gitignored" — errada [corrigido via gh71] (fonte: plano_correcao)
[K66] ape/pom.xml:163-185 — cópia do jar para o aperv-tool roda só em `mvn install` (fase install); `mvn package` não dispara — handoff manual sem gate (fonte: plano_correcao)
[K67] target/ape-rv.jar — sem build-stamp (sem BuildConfig/git.properties/constante de versão): o skew de jar ficou invisível até inspeção manual do dex (fonte: plano_correcao)
[K68] package_detector (rvsec) — sem detecção de obfuscação e com mis-picks conhecidos de pacote de biblioteca (≥8 casos), contaminando `reachability[]` e o join do consumer (fonte: plano_correcao)
[K69] ReachabilityEngine.java:79 — recall do SPARK é FN real: o set transitivo não tem a rede de segurança do bytecode-scan (só o direto tem) — handler com aresta app→lib cega fica fora do set (fonte: plano_correcao)
[K70] corpus 169 — 65 APKs com target mas sem hit direto; ~73% deles têm handler ausente da reachability (maioria lib/não-alcançável, não ofuscação) (fonte: plano_correcao)
[K71] traces gh15/cmpmop — `maxBoost>0` em apenas ~3,2% das decisões (4.282/133.192) mesmo com jar correto; discriminativos +300/+500 ≈1% das decisões (fonte: investigacao_mop)

FORMATO DO TRACE:
- Arquivo `.trace` semi-estruturado; linhas relevantes têm prefixo `[APE] *** INFO *** ...`.
- `Create state g0s0[...]Activity@hash@Naming[k]@[W=3][A=5]` — novo state abstrato; W=widgets, A=ações.
- `[APE-STEP] step=N activity=A state=S action=... decision_source=SATA priority=252 mop=100 wtg=0 coverage=100 menu=0` — uma linha por ação e

```
