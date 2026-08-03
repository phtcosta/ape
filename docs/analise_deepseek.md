# Investigação Completa do APE-RV — Código, Dados Estáticos, Observabilidade e Change em Andamento

**Modelo:** DeepSeek V4 Flash Free · **Data:** 2026-07-02
**Base:** master (commit `f70f986`) + worktree `ape-mop-fairtest` (não commitado)
**Método:** 8 agentes paralelos lendo arquivos completos por pacote, 1 agente de schema JSON, 1 agente de logging, 1 agente de avaliação do worktree. Achados cruzados e deduplicados contra `docs/analise_claude_sonnet5.md`, `docs/analise_gemini.md`, `docs/20260622_investigacao_mop.md` e `docs/analise_claude_fable5.md`.

---

## 1. Catálogo de Bugs/Anomalias no Código (Frente 1)

### 1.1 `ape/agent/` — Núcleo de decisão

| ID | Arquivo:Linha | Defeito | Sev | Conf | Métrica | Status |
|---|---|---|---|---|---|---|
| AGT-01 | `SataAgent.java:269,271` | BFS `checkBackTrack` verifica/adiciona `state` (já processado) em vez de `target` — nunca explora além do 1º salto. Backtracking é sempre falso | **Bloqueante** | Alta | UI-A, MOP-C | K08/SATA-01 (confirmado) |
| AGT-02 | `SataAgent.java:444` | Validação do buffer usa `!=` (referência) em vez de `!equals()` — após refinamento do modelo, objeto de ação semanticamente igual mas diferente é tratado como inválido, descartando buffer | Alta | Alta | UI-A, MOP-C | **NOVO** |
| AGT-03 | `StatefulAgent.java:1345,1356-1357` | `priority += 10` com comentário "make it weaker" — adicionar prioridade FORTALECE a ação na roleta. Weak transitions (flaky) são priorizadas sobre fortes | Alta | Alta | UI-A | **NOVO** |
| AGT-04 | `StatefulAgent.java:1089-1092` | `dispatchTrigger()` deriva package de componente por `substring(0, lastDot)` — subpacotes como `com.example.app.ui.MyActivity` produzem package `com.example.app.ui` em vez de `com.example.app`. Intents não resolvem | Alta | Média | MOP-C | **NOVO** |
| AGT-05 | `ReplayAgent.java:87` | `resolveName()` chama `nextInt(nodeList.getLength())` — se NodeList vazio, `nextInt(0)` lança `IllegalArgumentException` | Alta | Alta | G | **NOVO** |
| AGT-06 | `StatefulAgent.java:123,1667,1684` | `ActionCounters` declarado mas `logEvent()` está comentado — `print()` no teardown sempre imprime zeros | Média | Alta | G | **NOVO** |
| AGT-07 | `StatefulAgent.java:412-426` | `clearBuffer()` chamado mesmo quando relocação bem-sucedida — ações restantes do buffer descartadas desnecessariamente | Média | Alta | UI-A, MOP-C | **NOVO** |
| AGT-08 | `SataAgent.java:416,422` | `getBackAction().isValid()` e `getMenuAction().isValid()` sem null-check — se retornarem null, NPE | Média | Média | G | **NOVO** |
| AGT-09 | `StatefulAgent.java:634-635` | `buildState()` pode retornar null e linha seguinte chama `newState.isTrivialState()` sem null-check — NPE | Média | Média | G | **NOVO** |
| AGT-10 | `StatefulAgent.java:1528-1530` | `getThrottleForNewAction` usa `!=` (referência) para validar ação — após refinamento, a referência muda e `IllegalStateException("Oops")` crasha | Média | Média | G | **NOVO** |
| AGT-11 | `StatefulAgent.java:653,658-659` | Duplo `markVisited`: primeiro incondicional, depois condicional (já visitado → nunca executa). Código morto ou intenção ambígua | Média | Alta | G | **NOVO** |
| AGT-12 | `ApeAgent.java:203-215` | `generateInputText` afetado pelo PARSER-DROP — lookup por idName em MopData.getWidget() encontra widget errado (colisão/sobrescrita), typed input degrada silenciosamente para heurístico | Média | Alta | MOP-C | Novo ângulo sobre K02 |
| AGT-13 | `StatefulAgent.java:1044-1059` | `triggerMopComponent` avança cursor mesmo em falha — componentes não-exportados com SecurityException nunca são retentados | Média | Média | MOP-C | **NOVO** |
| AGT-14 | `SataAgent.java:298-301` | `checkFuzzing`: BACK actions em activity sub-visitada desabilitam fuzzing, mas MENU não — inconsistência de controle | Média | Média | UI-A | **NOVO** |
| AGT-15 | `StatefulAgent.java:869-901` | `recoverCurrentState` não valida null de `modelAction.getState()` — se action sem estado, `currentState` fica null, NPE depois | Média | Média | UI-A | **NOVO** |
| AGT-16 | `ReplayAgent.java:77-84` | `onBufferLoss()`/`onRefillBuffer()` lançam `RuntimeException("Not implemented")` — qualquer buffer loss crasha replay | Média | Alta | G | **NOVO** |

### 1.2 `ape/naming/` — Abstração/Refinamento (inovação central)

| ID | Arquivo:Linha | Defeito | Sev | Conf | Métrica | Status |
|---|---|---|---|---|---|---|
| NAM-04 | `Naming.java:438` | `Collections.binarySearch(namelets, n, comparator) == -1` — `binarySearch` retorna qualquer valor negativo quando não encontra (não só -1). Se insertion point ≠ 0, o elemento tratado como "encontrado" quando não está; namelet errado selecionado → StateKey errado → CEGAR corrompido (falsos não-determinismos ou refinamento perdido) | **Crítico** | Alta | UI-A, MOP-C, MOP-V | **NOVO** |
| NAM-05 | `NamingFactory.java:280` | Guarda `maxGUITreesPerState` copiado de `maxStatesPerActivity` (linha 276) sem alterar a variável: testa `an.getStates().size()` em vez de `state.getGUITrees().size()`. Flag é **dead config** — nenhum limite real de GUITrees por estado (alimenta o OOM conhecido) | Alta | Alta | G | **NOVO** |
| NAM-06 | `Naming.java:489-490` | `finally` dereferencia `results` (null se `namingInternal()` lançou RuntimeException) → NPE mascara a exceção original no funil único de abstração | Alta | Alta | G | **NOVO** |
| NAM-07 | `NamerType.java:43-49` | `complementOf(EnumSet<NamerType> set)` ignora completamente o parâmetro `set` — sempre retorna o conjunto completo de todos os `NamerType`. Validação "lattice incomplete" é vacuamente verdadeira | Média | Alta | G | **NOVO** |
| NAM-08 | `Naming.java:203-210` | `Edge.equals()` usa `==` para `from` (Namelet) e `Objects.equals` para `to`. `from` tem `equals()` próprio mas é ignorado — dois Namelets semanticamente iguais mas não-idênticos criam entradas duplicadas no mapa `children` | Média | Alta | UI-A | **NOVO** |
| NAM-09 | `NamingFactory.java:800-823` | `isIsomorphic()` compara `t1.getText().equals(t2.getText())` e `getContentDesc()` sem null-check — NPE se algum GUITreeNode tem texto/content-desc nulo | Média | Alta | G | **NOVO** |
| NAM-10 | `StateNamingManager.java:58-68` | `getNaming()` loop `while(true)` sem detecção de ciclo — se o grafo de refinamento tiver ciclo, hang infinito | Média | Alta | UI-A | **NOVO** |
| NAM-11 | `Naming.java:252-254` | `hasChild()` invertido (retorna true quando NÃO há filhos) — `AbstractNamingManager.isLeaf` herda e fica correto por acidente (dupla inversão). `StateNamingManager` sobrescreve. Perigoso se usado diretamente | Média | Alta | UI-A | NAM-01 (confirmado, severidade rebaixada) |
| NAM-12 | `Namelet.java:156-162` + `Naming.java:456-457` | `filter()` engole XPathExpressionException → retorna null → `Naming.select()` dereferencia sem check → NPE | Média | Alta | G | NAM-02 (confirmado) |
| NAM-13 | `IndexNamer.java` | `IndexName` sem `equals()`/`hashCode()` — diferente de todos os outros Name (TextName, TypeName, etc.) que sobrescrevem | Média | Alta | G | NAM-03 (confirmado) |
| NAM-14 | `NamingFactory.java:1170-1171` | `actionRefinement()` testa `>=` enquanto `checkActionRefinement()` testa `>` no mesmo limiar — inconsistência de um passo no bloqueio do refinamento | Baixa | Alta | UI-A | **NOVO** |
| NAM-15 | `NamingFactory.java:1086-1092` | `getMaxStatesForRefinementThreshold()` com `Math.min(8, ...)` — threshold ou é 8 ou 2, sem meio termo | Baixa | Alta | UI-A | **NOVO** |
| NAM-16 | `AssertStatesFewerThan.java:49` | Log reporta `trees.size()` em vez de `newStates.size()` — diagnóstico enganoso | Baixa | Alta | G | **NOVO** |
| NAM-17 | `Naming.java:489` | Log usa `getNameSize()` para ambos os placeholders "names" e "nodes" — nodes reportado como names | Baixa | Alta | G | **NOVO** |

### 1.3 `ape/model/` — Grafo de Exploração

| ID | Arquivo:Linha | Defeito | Sev | Conf | Métrica | Status |
|---|---|---|---|---|---|---|
| GRAPH-02 | `Graph.java:1293` | **`visitedCount++` incondicional em `rebuildHistory()`** — `markVisited()` já conta na re-adição de cada transição. O loop sobre `treeTransitionHistory` adiciona de novo sobre o histórico inteiro. A cada rebuild (todo refinamento), visitedCount dobra ou mais (nunca reseta). Consumidores: filtros que testam `visitedCount<3`, greedyPickLeastVisited, isSaturated. **Telas refinadas (as mais complexas, tipicamente com MOP) ficam artificialmente quentes e são despriorizadas** | **Alta** | Alta | UI-A, MOP-C | **NOVO** |
| GRAPH-03 | `Graph.java:346-360` | `getGUITrees()` iterator: quando um state tem `treeHistory` vazia, o iterator retorna `false` prematuramente — states subsequentes com árvores reais são permanentemente saltados. Árvores truncadas silenciosamente em replay logging, serialização, relatórios | Alta | Alta | UI-A | **NOVO** |
| GRAPH-04 | `ModelAction.java:186-189` | `resolveAt()` lê `visitedCount` antes de `markVisited()` incrementá-lo — saturation fica 0.5 aquém por visita. Ação considerada insaturada por uma visita extra | Média | Alta | UI-A | **NOVO** |
| GRAPH-05 | `State.java:464-485` | `getSaturation()` (média de TODAS as ações) vs `isSaturated()` (só ENABLED_VALID) — inconsistentes. getSaturation sub-representa progresso quando há ações disabled | Média | Alta | UI-A | **NOVO** |
| GRAPH-06 | `State.java:313-339` | `State.append()` verifica se tree já está associada a `this` mas não a OUTRO state — se tree foi reassociada (rebuild de merge), state anterior fica com dangling reference a tree cujo `getCurrentState()` aponta para outro | Média | Alta | UI-A, MOP-C | **NOVO** |
| GRAPH-07 | `StateTransition.java:79-81` | `getLastGUITreeTransition()` acessa `treeTransitions.get(size-1)` sem null-check — se `append()` nunca foi chamado, NPE que propaga até `Model.update()` | Média | Alta | MOP-V | **NOVO** |
| GRAPH-08 | `StateTransition.java:107-112` | `isStrong()` requer `strength >= 2` — uma transição com 2 hits e 1 miss (funciona 2/3 das vezes) é considerada fraca. BFS de planejamento evita essas arestas, reduzindo alcance de navegação | Média | Alta | UI-A, MOP-C | **NOVO** |
| GRAPH-09 | `Graph.java:1287-1291` | Self-assignment em `rebuildHistory`: `edge.firstVisitTimestamp = fv` reatribui o valor lido (`fv`) em vez de `tt.getTimestamp()` | Média | Alta | UI-A | GRAPH-01 (confirmado) |

### 1.4 `ape/tree/` + `ape/events/` — Captura e Execução

| ID | Arquivo:Linha | Defeito | Sev | Conf | Métrica | Status |
|---|---|---|---|---|---|---|
| TREE-01 | `GUITree.java:284` | `Arrays.binarySearch` comparado com `index == -1` — `binarySearch` retorna `-(insertion point)-1` quando não encontra, que é sempre ≤ -1, só `== -1` quando insertion point=0. Para outros valores, cai em `currentNodes[index]` com índice negativo → `ArrayIndexOutOfBoundsException` no caminho de `Model.contains()` executado a cada passo | Alta | Alta | UI-A (crash) | K03 (confirmado) |
| FUZZ-01 | `ApeFuzzer.java:167-192` | `generatePinchOrZoomEvent()` constrói `PointF[]` mas **nunca chama `events.add()`** — código de pinch/zoom é silenciosamente morto | Alta | Alta | UI-A | K33 (confirmado) |
| FUZZ-02 | `ApeFuzzer.java:173` | Precedência de operador: `4 + count << 1` → Java resolve como `4 + (count << 1)` = `4 + 2*count`. Intenção era `(4 + count) << 1`. Array de 6+2*count slots escritos em array de 4+2*count → ArrayIndexOutOfBoundsException (se FUZZ-01 fosse corrigido) | Alta | Alta | UI-A | K34 (confirmado) |
| EVT-01 | `MonkeySourceApe.java:359` | Fallback de clique: bounds sem interseção com tela → clica no centro da tela, **creditado como a ação original** no modelo. Arestas falsas, cobertura creditada errada | Média | Alta | UI-A | **NOVO** |
| EVT-02 | `GUITreeBuilder.java:582-606` | `fillNode()` nunca captura `isPassword()` (zero call sites para `setIsPassword`) — detecção de campo de senha no `InputValueGenerator` é código morto | Média | Alta | UI-A | **NOVO** |
| EVT-03 | `ApePinchOrZoomEvent.java:42` | Guarda `points.length < 4` — evento válido precisa de ≥6 elementos; guarda é muito permissivo e pode produzir evento malformado (se FUZZ-01 for corrigido) | Média | Alta | MOP-C | **NOVO** |
| EVT-04 | `GUITreeBuilder.java:465` | `checkAndRemoveWebView` conta TODOS os nós descendentes (não só actionáveis) — com default threshold=64, quase todo WebView real excede → conteúdo web descartado | Média | Média | UI-A | **NOVO** |
| EVT-05 | `GUITreeNode.java:199` | `isEditText` usa igualdade exata com `android.widget.EditText` — `AppCompatEditText`, `TextInputEditText`, `AutoCompleteTextView` nunca recebem texto | Média | Alta | UI-A | **NOVO** |
| EVT-06 | `GUITreeBuilder.java:433-441` | Nós null (`getChild(i)` retorna null) saltados silenciosamente — elementos UI podem sumir sem aviso | Baixa | Alta | UI-A | **NOVO** |

### 1.5 `ape/utils/` — Config, MOP, Coverage

| ID | Arquivo:Linha | Defeito | Sev | Conf | Métrica | Status |
|---|---|---|---|---|---|---|
| MOP-01 | `MopData.java:308-320` | `widgets.put(wd.idName, wd)` em `LinkedHashMap` com última-escrita-vence. Widgets de mesmo `idName` na mesma activity se sobrescrevem (incluindo `idName=""` que colapsa todos num bucket). **45% dos widgets flagged perdidos antes do scoring** em 12 dos 19 APKs com substrato | Alta | Alta | MOP-C | PARSER-DROP (confirmado) |
| MOP-02 | `MopScorer.java:51-53` | `if (data.activityHasMop(activity)) return Config.mopWeightActivity` — fallback +100 uniforme para TODOS os widgets da activity. Não re-ranqueia (73% dos boosts) | Média | Alta | MOP-C, UI-A | MEC-UNIF (confirmado) |
| MOP-03 | `MopData.java:468` vs `MopScorer.java:84` | WTG-KEY: `wtgTransitions` keyado por `source.name` (nome completo da janela, ex. `MainActivity#OptionsMenu`), consultado por `baseActivity()` (ex. `MainActivity`) — menu-originated transitions NUNCA encontradas | Média | Alta | MOP-C | WTG-KEY (confirmado) |
| MOP-04 | `UICoverageTracker.java:260` | `activityRollup` é write-only: `getActivityCoverageGap` tem zero call sites de produção. States evictados do LRU voltam com gap=1.0, coverage boost re-dispara em widgets já testados | Média | Alta | UI-A | **NOVO** |
| MOP-05 | `StringCache.java:108` | `nextString()` chama `nextInt(size)` **antes** de verificar lista vazia → `IllegalArgumentException` se nenhum texto cacheado | Média | Alta | UI-A | **NOVO** |
| MOP-06 | `RandomHelper.java:27` | `ThreadLocalRandom` não-semeável em 34 call sites de decisão (incluindo roleta) — RNG do `egreedy()` usa Random semeado do Monkey. Seed `-s` ignorada pela maior parte do agente. Nenhum run é reproduzível | Média | Alta | G | **NOVO** |
| MOP-07 | `InputValueGenerator.java:141` | `matchKeywords` substring: 'account'→PHONE (via 'count'), 'security'→URL (via 'uri'), 'tel'→PHONE — input errado | Baixa | Alta | UI-A | **NOVO** |
| MOP-08 | `Config.java:105` | `maxStringPieceLength` definido mas sem call sites — dead config | Baixa | Alta | G | CFG-01 (confirmado) |

### 1.6 Raiz `com.android.commands.monkey` + `ape/`

| ID | Arquivo:Linha | Defeito | Sev | Conf | Métrica | Status |
|---|---|---|---|---|---|---|
| MSA-03 | `MonkeySourceApe.java:180-184` | Array `mImageWriters` alocado com `Config.imageWriterCount` mas inicializado com loop hardcoded `i<3` — config é dead flag; valor ≠3 crasha (NPE em `nextImageWriter()`) | Média | Alta | G | **NOVO** |
| MSA-04 | `MonkeySourceApe.java:1283-1299` | `getNextEvent()` só captura `StopTestingException`. Qualquer RuntimeException (NPE de ActionFilter, ArrayIndexOutOfBounds da GUITree, ClassCastException) propaga e mata o loop de eventos do Monkey — teste inteiro abortado | Média | Alta | UI-A, MOP-C | **NOVO** |
| MSA-05 | `MonkeySourceApe.java:959-965` | `stopTopActivity()` usa `processes.get(0)` — primeiro processo da lista `getRunningAppProcesses()`, que tem ordenação arbitrária. Pode matar `system_server`, `com.android.systemui` ou qualquer outro processo em vez do app-alvo | Média | Alta | G | **NOVO** (confirmado) |
| MSA-06 | `ImageWriterQueue.java:44-56,99-101` | Thread nunca sai em `InterruptedException` (faz `continue`). `tearDown()` não interrompe threads. Threads vazam a cada ciclo de conexão (3 por ciclo) | Média | Alta | G | **NOVO** |
| MSA-07 | `SataAgent.java:647,652,656` | Acesso a `stack.getTasks().get(0).getActivities()` sem bounds check — se focusedStack retornar stack sem tasks, IndexOutOfBoundsException | Média | Média | G | **NOVO** |
| MSA-08 | `MonkeySourceApe.java:598-600` | `validateBounds()` dereferencia `action.getResolvedNode()` sem null-check — se ação refinada perdeu o nó, NPE | Média | Média | G | **NOVO** |
| MSA-09 | `AndroidDevice.java:59-77` | Campos estáticos mutáveis sem sincronização: `blacklistPermissions` (HashSet), `inputMethodPackages`, `useADBKeyboard` — sem happens-before garantido | Média | Alta | G | **NOVO** |
| MSA-10 | `OnlyAddedUnsaturatedActionFilter.java:38-39` | Comentário "Include BACK" em código que retorna `false` (exclui) para ações sem target — comentário é o oposto do comportamento | Baixa | Alta | G | **NOVO** |

### 1.7 `reducer/` (não compila no Maven / não entra no jar)

| ID | Arquivo:Linha | Defeito | Sev | Conf | Métrica | Status |
|---|---|---|---|---|---|---|
| RED-01 | `Reducer.java:131-166` | `main()` usa path hardcoded `outputDir + "sataModel.obj"` — IllegalArgumentException em arquivo ausente; sem graceful recovery (utilitário standalone, aceitável) | Baixa | Média | G | **NOVO** |

---

## 2. Schema `<apk>.json` vs Parser (Frente 2)

### 2.1 Fidelidade campo-a-campo

100% dos campos do JSON são lidos pelo parser (todos usam `opt*` methods seguros). Campos ausentes no JSON mas esperados pelo parser (`handlerReachesTarget`/`handlerDirectlyReachesTarget`) são forward-compat (nunca emitidos pelo produtor atual — documentado).

### 2.2 Achados de schema

| ID | Arquivo:Linha | Defeito | Sev | Conf | Métrica | Status |
|---|---|---|---|---|---|---|
| JSON-01 | `MopData.java:246` | Array traversal com `getJSONObject(i)` lança `JSONException` se um elemento é malformado → a exceção propaga para o único `try` (linha 184-249) que cobre todos os 4 passes (reachability, windows, transitions, components). Um único elemento ruim descarta o **arquivo inteiro** sem degradação parcial | Média | Alta | MOP-C | **NOVO** |
| JSON-02 | `MopData.java:314` | `idName=""` passa pelo guarda `!= null` e é armazenado como chave → casa com todo widget runtime sem resource-id. Match espúrio bidirecional: flagged sem id é sobrescrito (perda); unflagged pode ganhar boost falso via containment se último da lista | Alta | Alta | MOP-C | **NOVO** (profundidade nova sobre PARSER-DROP) |
| JSON-03 | `MopData.java:303,460-468` | Janelas DIALOG têm `name` igual ao nome da classe do dialog (`android.app.AlertDialog`) — `baseActivity()` não reconhece isso como activity, WTG keyado por esse nome nunca casa com `newState.getActivity()` (que retorna a activity hospedeira). Widgets de dialog estruturalmente inalcançáveis para boost widget-level | Média | Alta | MOP-C | **NOVO** |
| JSON-04 | `MopScorer.java:139` (normalizeEventType) | Heurística de Spinner: código pergunta por `itemSelected`, produtor emite `select` no JSON (27 ocorrências no corpus `cryptoapp`). `normalizeEventType("select")` retorna `select` ≠ `itemselected` → nunca casa | Média | Alta | MOP-C | **NOVO** |
| JSON-05 | `StatefulAgent.java:162` | `MopData.load` 1-arg nunca usa `mopStrictPackageMatch` flag — K24 (guard contra JSON×APK divergente) é inalcançável em produção; qualquer divergência silenciosa passa | Média | Alta | G (validade experimental) | **NOVO** |
| JSON-06 | `MopData.java:246-249` | Load falho retorna `null` → agente roda como SATA puro sem nenhum aviso distinguível. Braço `sata_mop` experimentalmente indistinguível | Média | Alta | G (validade experimental) | **NOVO** |

### 2.3 Vocabulário Target vs MOP

Fronteira D7 está bem isolada no `MopData`. `*Target` aparece apenas: campos internos de `ReachabilityMethod.java`, `ComponentInfo.java`, e como strings de leitura do JSON. `MopScorer` e `StatefulAgent` consomem apenas `directMop`/`transitiveMop`/`activityHasMop`. Não há vazamento fora da camada de parse. **OK.**

---

## 3. Suficiência do Log `.trace` (Frente 3)

### 3.1 Gaps identificados

| ID | Gap | Gravidade | Fechado por worktree? |
|---|---|---|---|
| LOG-01 | Apenas a ação ESCOLHIDA é logada em `[APE-STEP]` — candidatas não-escolhidas não têm decomposição mop/wtg/cov/menu. Flip de argmax não é reconstruível | **Crítica** | Não |
| LOG-02 | `decision_source` = SATA em 100% dos passos no master — valores MOP/Coverage/WTG/Menu/Fuzz do enum nunca atribuídos | Alta | Parcial (#3 atribui, mas sub-ramos mislabelam — ver C6 abaixo) |
| LOG-03 | Boost aggregation: `boosted=3/12 maxBoost=500` — não dá para saber qual widget recebeu qual boost | Alta | Não (precisa de decomposição por candidata) |
| LOG-04 | UICoverageTracker sem dump — per-state coverage desaparece pós-run no master | Alta | Sim (#4 implementa dump no teardown) |
| LOG-05 | `[APE-STEP]` sem wall-clock — atribuir violação MOP (logcat timestamp) a um passo exige join de 3 arquivos via produce.log | Média | Não |
| LOG-06 | Estratégia (SataEventType) só em linha solta sem step-id — join posicional frágil | Média | Não |
| LOG-07 | Linhas de boost MOP/WTG/Coverage são condicionais a `boosted>0` (coverage 100% → emite nada) e têm denominadores inconsistentes (gap usa widgets registrados xpath\|TYPE; boosted/total usa alvos target) | Média | Não |
| LOG-08 | Ação substituída por restart é contabilizada no `[APE-STEP]` ANTES da substituição — passo fantasma infla contagem | Média | Não |
| LOG-09 | Per-state widget count jamais logado | Baixa | Não |
| LOG-10 | Impacto de component triggering invisível (só loga tentativa, não se mudou a tela) | Baixa | Não |

### 3.2 Proposta Mínima de Instrumentação (5 mudanças)

1. **`[APE-STEP]` enriquecido**: acrescentar `clock=%d strategy=%s` e incluir no log das candidatas (já emitidas por `printActions`) a decomposição `[VC=%d][MOP=%d][WTG=%d][COV=%d][MENU=%d]` — fecha LOG-01, LOG-02, LOG-05, LOG-06 sem linhas novas.

2. **`[APE-STEP-SUBST] step=N replaced_by=%s`** em `ApeAgent.checkRestart` — fecha LOG-08.

3. **`MopData.load`** logar `package=`, `parsedWidgets=` (pré-colisão), `droppedNullId=`, `collided=`, `expectedPackage=` — fecha JSON-05/JSON-06.

4. **`[APE-COV-FINAL] dump`** no tearDown com `activity=%s gap=%.2f widgets=%d` via `getActivityCoverageGap` — fecha LOG-04.

5. **`Create state` com `new=true|false`** em Model.java — fecha O2 (supercontagem de estados).

---

## 4. Avaliação da Mudança em Andamento — Worktree `mop-fairtest` (Frente 4)

**`git status`:** uncommitted, unbuilt, untested-on-device. All changes in `src/main/java/` + `src/test/java/`.

### 4.1 Veredicto por alegação

| # | Alegação | Veredito | Detalhes |
|---|---|---|---|
| **#0** | Parser fidelity: parar de descartar widgets flagged | **CORRIGE** | `mopRank` (direct > transitive > unflagged), ordem-independente. Rechaveia WTG por baseActivity. Testes fortes. Mas: `idName=""` drop (INV-MOP-20) elimina match para apps sem resource-id; comentário "+100 fallback substrate" obsoleto |
| **#1** | Form-fill → submit (FormCompletion.java) | **NÃO CORRIGE introduz regressões** | Três problemas: (C1) determimistic fill é código morto (`newState==null` quando `checkInput` roda), (C2) guard INV-FORM-06 derrotado pelo EARLY_STAGE, (C5) `isUnfilledEditText` nunca converge (inputText transiente) |
| **#2** | Boost discriminativo (remover +100, short-circuit MOP) | **PARCIAL** | Remoção do +100 correta e completa. Short-circuit sombreado pelo EARLY_STAGE (C3): opera em <1% das decisões; 99% passam pela roleta que o short-circuit pretendia substituir |
| **#3** | Telemetria `decision_source` | **PARCIAL** | Atribuição largest-boost com tie MOP>WTG>Menu>Coverage, honestamente documentada. Sub-ramos mislabelam (Back/Menu curto-circuitados), formBoost invisível, zero teste unitário |
| **#4** | Dump UICoverageTracker | **CORRIGE** | Read-only, um state por linha no teardown. Estados LRU-evictados não incluídos |
| **W** | WTG-KEY | **CORRIGE** | Rechaveia wtgTransitions e precomputeMopOptionsMenus — correção trivial no mesmo arquivo de #0 |

### 4.2 Problemas específicos na change

| ID | Onde (worktree) | Defeito | Sev | Métrica |
|---|---|---|---|---|
| C1 | `StatefulAgent.java:184` / `ApeAgent.java:192` | Fill determinístico é código morto: pipeline é `checkInput(checkFuzzing(checkRestart(updateStateInternal())))`; `updateStateInternal` chama `moveForward()` que anula `newState` antes de `checkInput` rodar. `inFormCompletionContext()` lê `newState==null` e retorna sempre false | **Bloqueante** | MOP-C |
| C2 | `SataAgent.java:497` + StatefulAgent priority | Guard INV-FORM-06 (submit não pode ser selecionado antes dos campos preenchidos) derrotado: EARLY_STAGE roleta o submit com prioridade ~752 vs ~302 por campo → P(submit vazio) ≈ 55-71% na 1ª visita; `greedyPickLeastVisited` desempata vc=0 por MAIOR priority → submit é o primeiro escolhido | Alta | MOP-V |
| C3 | `SataAgent.java:471-476` | Short-circuit MOP vive no ramo `EPSILON_GREEDY` (após BACK/MENU short-circuits), mas 57.6% das decisões no EARLY_STAGE consomem unvisited-by-name antes. Short-circuit opera em <1% — diluição original persiste | Alta | MOP-C |
| C5 | `FormCompletion.java:51` | `isUnfilledEditText` testa `getInputText()==null`. `inputText` é anotação transiente por captura (nunca copiada entre capturas, nunca derivada de `getText()` do Android). **Sempre true**: re-boost sem progresso, submit excluído do short-circuit para sempre | Alta | MOP-C |
| C6 | `SataAgent.java:241` | `decision_source` mislabela: sub-ramos path-based do EARLY_STAGE (ABA, refillBuffer, shortest-path) não consomem priority mas recebem o maior boost; Back/Menu-unvisited no EPSILON_GREEDY saem `decision_source=Menu` mesmo sem o boost ser relevante | Média | G |
| C7 | `SataAgent.java:244` | formBoost invisível na atribuição: não existe `DecisionSource.Form`. Campo escolhido por W_FILL=150 rotulado Coverage ou SATA — influência de #1 na seleção não mensurável | Média | G |
| C9 | `FormCompletion:83,112` + `GUITreeNode:199` | Submit heuristic: (a) desempate por mopBoost pode escolher MODEL_SCROLL; (b) lone-Button ignora texto ("Cancel"/"Delete" vira submit); (c) `isEditText` exato → zero em Compose/Material (AppCompatEditText não casa); (d) Compose sem "Button" → submit = none | Média | MOP-V |
| C10 | Spec `mop-guidance` não sincronizado | `mopWeightActivity`, +100 fallback, INV-MOP-07 ainda documentados como comportamento vigente — contradiz código novo. `opsx:sync` necessário | Média | G |

### 4.3 Pronta para o experimento de validação?

**Não.** C1 (fill determinístico morto), C3 (short-circuit sombreado), C2+C5 (guarda furado + não convergência) significam que o experimento de validação mediria de novo um tratamento ~inerte, repetindo o padrão do null do cmpmop. Os bloqueadores precisam ser corrigidos antes de qualquer fair-test.

O que está sólido: #0 (parser fidelity), #4 (UICoverage dump), W (WTG-KEY). Podem ir adiante independentemente.

---

## 5. Mapeamento ao Objetivo

| Métrica | Achados que afetam diretamente |
|---|---|
| **(a) Cobertura de UI** | AGT-01 (backtrack falho), AGT-02 (buffer descartado), AGT-03 (flaky priorizado), NAM-04 (binarySearch corrompe CEGAR), GRAPH-02 (visitedCount inflado desprioriza telas), GRAPH-03 (iterator perde árvores), TREE-01 (crash), FUZZ-01 (pinch morto), MOP-04 (coverage evictado re-zero), MOP-05 (StringCache crash), MSA-05 (uncaught ex mata loop), EVT-01 (clique centro), EVT-05 (editText exato perde AppCompat) |
| **(b) Cobertura MOP** | AGT-04 (dispatchTrigger package errado), AGT-12 (input degradado), MOP-01 (PARSER-DROP 45% perdidos), MOP-03 (WTG-KEY menu invisível), JSON-02 (idName="" match espúrio), JSON-03 (dialogs inalcançáveis), JSON-04 (Spinner eventType), C1/C2/C3/C5 (change), GRAPH-08 (isStrong conservador) |
| **(c) Violações MOP** | C2 (submit before fill impede fluxo), LOG-05 (sem wall-clock para join com logcat), LOG-10 (trigger impact invisível) |
| **Qualidade geral** | U2 (RNG não-semeável), R4 (tearDown sem finally), NAM-05/NAM-06/NAM-07/NAM-08, MSA-03/MSA-04/MSA-06 |

### Mecanismos que limitam artificialmente o que é contado/reportado (§6.2)

- **LOG-08** (A3/O1): Ação substituída por restart é creditada como executada — contagem inflada
- **GRAPH-02** (M1/M2): visitedCount inflado suprime re-exploração
- **MOP-04** (U3): coverage evictado perde-se e re-dispara — progresso real mascarado
- **NAM-05** (N2): maxGUITreesPerState é dead config — nenhum limite real
- **EVT-05** (T5): EditTexts AppCompat nunca recebem texto — cobertura de form artificialmente baixa
- **JSON-01**: parser all-or-nothing — um JSON parcialmente válido vira 0% carregado

---

## 6. Priorização (Impacto × Esforço)

### (i) Bloqueadores de validade experimental — corrigir PRIMEIRO

| # | Item | Fix mínimo | Experimento de validação |
|---|---|---|---|
| 1 | C1: fill determinístico morto | `inFormCompletionContext` ler `currentState` ou capturar contexto antes de `moveForward` | Teste host de `checkInput` com pipeline real; run curto contando `Input text` dentro de form context |
| 2 | C3: short-circuit MOP sombreado | Aplicar preferência MOP na roleta do EARLY_STAGE ou mover short-circuit para antes | Traces: fração de alvos MOP unvisited consumidos por EARLY_STAGE deve cair de ~99% |
| 3 | C5: convergência unfilled | Derivar 'filled' de `getText()` do GUITreeNode atual ou persistir por xpath entre capturas | Teste host: 2 capturas consecutivas, campo digitado não volta a unfilled |
| 4 | JSON-05/JSON-06: load silencioso | Passar package/mainActivity real no load; logar `package=/widgets=/dropped=`; fail-fast configurável | Inspeção de 1 linha do trace |
| 5 | GRAPH-02: visitedCount inflado | Remover `visitedCount++` incondicional de rebuildHistory (ou resetar antes do loop) | Teste host: rebuild 2× e assert visitedCount estável; comparar histograma de restarts num run |
| 6 | LOG-01/LOG-02/LOG-05/LOG-06: observabilidade | 5 mudanças da proposta mínima (§3.2) | Sem elas, pós-hoc do fair-test repete inferências não-causais |

### (ii) Débito técnico geral (independente de MOP)

Por impacto: NAM-04 (binarySearch em Naming.select — corrigir de `== -1` para `< 0`), TREE-01 (binarySearch em GUITree.contains — mesmo padrão), AGT-04 (dispatchTrigger package), AGT-05 (ReplayAgent crash), MSA-05 (catch-all em getNextEvent), MSA-03 (hardcoded loop), MSA-06 (thread leak), MOP-06 (RNG semeável), EVT-05 (isEditText abrangente), JSON-01 (degradação parcial), NAM-05 (guard maxGUITreesPerState), NAM-08 (Edge.equals), NAM-09 (isIsomorphic NPE).

### (iii) Lacunas de observabilidade

Proposta mínima §3.2 (5 itens) + DecisionSource.Form (C7) + trigger impact log (LOG-10) + per-state widget count (LOG-09). Externo ao repo: verificar rate-limit do chatty/logcat para violações repetidas.

### (iv) Propostas novas (ninguém tinha levantado antes)

1. **Re-chavear janelas DIALOG** à activity hospedeira via WTG edges já presentes no JSON (consumer-side) — desbloqueia 86 widgets flagged em dialogs (JSON-03).

2. **Política de match para `idName=""`**: em vez de dropar (change #0) ou casar espuriamente (master), casar `""` apenas quando `(activity, eventType, className)` for única — recupera labnex/duress sem ruído (JSON-02).

3. **Reordenar pipeline**: decidir restart ANTES de marcar visited/coverage (resolve LOG-08 na raiz).

4. **Semear RandomHelper com `-s` do Monkey**: pré-requisito barato para qualquer estudo de variância entre braços (MOP-06).

5. **Corrigir `isStrong`**: baixar threshold de 2 para 1 (uma transição com ≥1 hit e 0 miss já é forte) — expande navegação BFS (GRAPH-08).

---

## 7. Limitações

- **Sem execução em dispositivo**: todos os achados são de leitura de código. Frequências de disparo de NAM-04, AGT-04, MSA-05, MSA-07, MSA-08, JSON-01 não foram medidas empiricamente.
- **Corpus de JSON**: apenas o fixture `cryptoapp.apk.json` está neste repo. Achados sobre dialogs (JSON-03), idName="" (JSON-02) e eventType (JSON-04) usam dados desse exemplar + relatos do corpus cmpmop. O produtor (gator/RvsecAnalysisClient) não foi auditado.
- **Cadeia de violações MOP**: JavaMOP→logcat→contagem é externa ao repo `ape`. Riscos de chatty/buffer/rate-limit não verificáveis aqui.
- **Proveniência upstream**: não diffei contra o APE original ETH. AGT-03, EVT-01 podem ser herdados.
- **`mvn test` não executado no master**: test suite do worktree foi relatada (381/0/0/15) pelo agente de avaliação; não reproduzi independentemente.
- **`openspec validate --strict` não executado**.
