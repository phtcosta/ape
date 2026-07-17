# Verificação rigorosa de consistência — change `refinement-crash-recovery`

**Data**: 2026-07-17
**Alvo**: `openspec/changes/refinement-crash-recovery/` (proposal.md, design.md, tasks.md, specs/exploration/spec.md, specs/model/spec.md)
**Método**: verificação adversarial em 3 frentes paralelas — (1) cada afirmação sobre sítios de código conferida contra o fonte real; (2) IDs de invariante, colisões e estrutura OpenSpec; (3) auditoria profunda da única premissa de design da qual todo o fix F-C depende.
**Status da change**: 0/21 tarefas, nenhuma implementação escrita. Todos os defeitos abaixo são corrigíveis nos artefatos, antes de qualquer código.

---

## 1. Veredito executivo

| Dimensão | Resultado |
|---|---|
| Estrutura OpenSpec (schema `sdd-full`, artefatos, `validate --strict`) | **PASSA** — 20/20 itens strict-valid |
| IDs de invariante (INV-EXPL-29/30, INV-MODEL-15) | **PASSA** — livres, contíguos, sem colisão |
| Fidelidade do MODIFIED (nada perdido) | **PASSA** — 3 cenários e narrativa preservados verbatim |
| Afirmações sobre sítios de código | **7 verdadeiras, 3 falsas, 3 com desvio de linha** |
| Mecanismo de design do F-C | **BLOQUEADOR** — premissa falsa; implementar como escrito troca crash por corrupção silenciosa |

A change está bem construída no plano formal e a maior parte do diagnóstico forense se confirmou no código. O problema é substantivo, não formal: **o fix F-C, implementado exatamente como o design manda, não conserta o defeito — ele o esconde e piora**. Isso precisa ser resolvido nos artefatos antes do Grupo 4.

---

## 2. BLOQUEADOR — a premissa central do F-C é falsa

### 2.1 O que o design afirma

`design.md`, decisão **D3**, última frase:

> "The unresolved action is naturally excluded from selection: only valid actions pass the action filters, and validity requires successful resolution."

E a seção **API Design**:

> `StatefulAgent.validateNewAction(State state, ModelAction action) -> void`
> Postcondition: action resolved, or **left unresolved** with `[APE-RV] Rebind failure: <descriptor>` logged once…

O plano é: capturar a `IllegalStateException`, deixar a ação "não-resolvida", e confiar que os filtros a excluem sozinhos. **Nenhuma dessas duas premissas se sustenta.**

### 2.2 Por que é falsa — cadeia de evidência

**(a) `valid` é um campo pegajoso, sem reset por passo.**
`Action.java:63-67`:
```java
private final ActionType type;
private boolean enabled = true;
private boolean valid;          // sem inicializador => default false
private int priority;
private int throttle;
```
Repare que `enabled` recebe `= true` explícito e `valid` não — o default `false` é deliberado. Mas isso só protege a *primeira* validação. Os únicos escritores são `StatefulAgent.java:1334` (`setValid(true)`) e `1338` (`setValid(false)`). **Não existe reset por passo.** Uma vez `true`, permanece `true` até que alguma `validateNewAction` futura o derrube.

**(b) Ações sobrevivem ao rebuild por identidade, carregando `valid=true`.**
`Graph.getOrCreateState` (`Graph.java:249-252`) interna estados por `StateKey`:
```java
State state = keyToState.get(stateKey);
if (state == null) {
    state = State.buildState(stateKey);
```
`Model.update(GUITree)` (`Model.java:433-438`) devolve o **mesmo objeto** salvo se estiver stale:
```java
public State update(GUITree tree) {
    State state = tree.getCurrentState();
    if (isStale(state)) { return getState(tree); }
    return state;      // mesmo State, mesmo ModelAction[], valid=true preservado
}
```
`ModelAction`s são construídas **só** no construtor de `State` (`State.java:60-74`, `buildActions:248-254`). O `Model.rebuild` (`Model.java:192-214`) remove apenas os estados cuja *nomeação* mudou. **Um estado que sobrevive ao refinamento mantém a identidade das suas ações e o `valid=true` delas.** E esse é precisamente o regime em que `pickNodes` estoura: o `State` sobreviveu, mas os nomes da árvore re-abstraída já não contêm o target da ação.

**(c) Ao estourar, o `resolvedNode` fica STALE — não nulo.**
`State.resolveAction` (`State.java:388-401`):
```java
GUITreeNode[] nodes = latest.pickNodes(action);   // 397: ESTOURA AQUI
GUITreeNode node = RandomHelper.randomPick(Arrays.asList(nodes));
action.resolveAt(agent.getTimestamp(), throttle, latest, node, nodes);  // 399: nunca alcançado
```
`GUITree.pickNodes:165-169` lança antes de `resolveAt` rodar. Logo `resovledTimestamp`, `resolvedNode` e `resolvedGUITreeAction` **retêm os valores do passo anterior**. `isResolvedAt(timestamp)` devolve `false` para o timestamp corrente (bom), mas `resolvedNode` continua **não-nulo e legível** (péssimo).

**(d) Nenhum `ActionFilter` consulta resolução.**
`ActionFilter.java`:
```java
ActionFilter VALID          = ... return action.isValid();
ActionFilter ENABLED_VALID  = ... return action.isEnabled() && action.isValid();
ActionFilter ENABLED_VALID_UNVISITED = ... action.isEnabled() && action.isValid() && action.isUnvisited();
ActionFilter ENABLED_VALID_UNSATURATED = ... action.isEnabled() && action.isValid() && !action.isSaturated();
```
Todos gateiam em `isValid()` e nada mais.

**(e) A guarda que existe em `adjustActionsByGUITree` não filtra seleção — filtra pontuação.**
`StatefulAgent.java:1427-1445`:
```java
int basePriority = getActionBasePriority(action.getType()) << 3;
action.setPriority(basePriority);          // JÁ POSITIVA (MODEL_CLICK => 32)
...
if (!action.isResolvedAt(timestamp)) {
    continue;                              // pula só o REFINAMENTO de prioridade
}
```
O `continue` acontece **depois** de a prioridade-base positiva já estar setada. A ação sai do passo `valid=true`, `enabled=true`, prioridade ≥ 8 — candidata viva na roleta (`countActionPriority` inclusive *lança* se prioridade ≤ 0, ou seja, prioridade positiva é exatamente o que a torna selecionável).

**(f) O dispatch também não re-checa.**
`resolveNewAction:1355-1357` só assegura `newGUITreeAction != null` — satisfeito pelo `GUITreeAction` **stale**. Depois, `MonkeySourceApe.java:927-928`:
```java
GUITreeNode node = action.getResolvedNode();
generateClickEventAt(action.getResolvedNode().getBoundsInScreen(), CLICK_WAIT_TIME);
```
Sem null-check, sem freshness-check.

### 2.3 Consequência concreta

Implementando D3 literalmente (catch + `return null`, sem `setValid(false)`), a ação continua selecionável e despachável **no mesmo passo**. Caminho a caminho:

| Caminho de seleção | Exclui a ação? |
|---|---|
| `greedyPickLeastVisited(cappedFilter, …)` (`SataAgent:597`) | **NÃO** — base `ENABLED_VALID`; pior: uma ação não-resolvida costuma ser *least-visited*, virando **atrator forte** |
| Roleta `randomlyPickAction(random, cappedFilter)` (`SataAgent:606`) | **NÃO** — prioridade-base positiva a mantém na roda |
| Curto-circuitos BACK/MENU, `randomlyPickUnvisitedAction` | **NÃO** — só `isValid()` |
| `LlmRouter.selectAction(…, newState.getActions(), …)` (`LlmRouter:335,504,531,554`) | **NÃO** — gateia só `!a.isValid()`; lê `getResolvedNode()` stale **para dentro do prompt** e pode devolvê-la |
| `selectUnvisitedMopTarget` | incidentalmente seguro — `MopWidgetPass:47` pula não-resolvidas, `mopBoost` fica 0 |
| `handleNullAction` → `validatedActionFilter` (`StatefulAgent:159`) | seguro — re-invoca `validateNewAction`, que devolve null sob o patch |
| Fuzzing (`ApeAgent:180-184`) | irrelevante — `FuzzAction` não tem target |

**O resultado líquido do fix, como desenhado: o run deixa de crashar e passa a tocar coordenadas obsoletas de uma tela anterior, além de envenenar os prompts do LLM com nós stale.** Trocamos uma `IllegalStateException` ruidosa (que ao menos é honesta) por corrupção silenciosa exatamente dos dados de exploração que o experimento cmpv2 pretende medir. Para uma change cujo propósito declarado é *desbloquear a validade do cmpv2*, isso é uma inversão de valor.

Um risco secundário, correlato: `ApeAgent.checkInput:188` chama `node.isEditText()` sobre um `resolvedNode` possivelmente stale/nulo. Mesma classe de problema.

### 2.4 Correção proposta (pequena no código, cirúrgica nos artefatos)

O código do fix é trivial — o catch precisa **invalidar** a ação, espelhando o ramo de rejeição já existente em `StatefulAgent.java:1337-1339`:

```java
protected ModelAction validateNewAction(ModelAction action) {
    if (action == null) { return null; }
    try {
        action = newState.resolveAction(this, action, getThrottleForNewAction(newState, action));
    } catch (IllegalStateException e) {
        rebindFailureCount++;
        Logger.wformat("[APE-RV] Rebind failure: %s (%s)", action, e.getMessage());
        action.setValid(false);   // <-- ESSENCIAL: único sinal que todo filtro honra
        return null;
    }
    if (ape.validateResolvedAction(action)) {
        action.setValid(true);
        return action;
    }
    Logger.wformat("Mark an action (%s) invalid", action);
    action.setValid(false);
    return null;
}
```

`setValid(false)` é o **único** sinal que todos os filtros respeitam, e é o que faz o cenário de spec *"unresolved action is not selected"* passar de asserção sem mecanismo a comportamento real.

**Edições necessárias nos artefatos:**

- **design.md / D3** — reescrever a última frase. De *"naturally excluded from selection"* para: a ação é **explicitamente invalidada** (`setValid(false)`), porque `valid` é pegajoso entre passos e as ações sobrevivem ao rebuild por identidade de `StateKey`; nenhum filtro consulta o estado de resolução, e o `resolvedNode` fica stale (não nulo) quando `resolveAction` estoura. Registrar a alternativa rejeitada (deixar não-resolvida) **com o motivo**: gera taps em coordenadas obsoletas.
- **design.md / API Design** — postcondition passa a "action resolved, or **marked invalid** and left out of the candidate set"; corrigir a assinatura (§3.1).
- **design.md / Error Handling** — a linha `Cannot find widget` em `validateNewAction` muda de "action stays unresolved; count" para "action invalidated; count".
- **design.md / Risks** — o risco *"Unresolved actions accumulate after heavy refinement, shrinking the candidate set"* fica **mais forte** com a correção (a ação some do conjunto de fato, e não volta até uma re-validação futura). Continua aceitável — é a semântica correta, e `rebindFailureCount` a torna mensurável — mas o texto deve refletir que o encolhimento é agora real, não hipotético.
- **specs/exploration/spec.md** — no requirement *Post-Refinement Action Revalidation Tolerance*, trocar "The failing action SHALL be left unresolved (an unresolved action is not admissible for selection)" por "The failing action SHALL be marked invalid (`setValid(false)`), which excludes it from every action filter". O cenário *"unresolved action is not selected"* passa a ter mecanismo.
- **tasks.md / 4.1 e 4.2** — o teste RED deve assertar `isValid() == false` após a falha de rebind (não apenas "fica não-resolvida"), e o 4.2 deve mencionar `setValid(false)` explicitamente.

---

## 3. Afirmações falsas sobre o código

### 3.1 Assinatura de `validateNewAction` está errada (MAJOR)

Design (API Design, tabela Key Components, Mapping) afirma:
> `StatefulAgent.validateNewAction(State state, ModelAction action) -> void`

Real (`StatefulAgent.java:1328-1340`) — **um argumento, retorna `ModelAction`**:
```java
protected ModelAction validateNewAction(ModelAction action) {
    if (action == null) { return null; }
    ...
    action = newState.resolveAction(this, action, getThrottleForNewAction(newState, action));
    if (ape.validateResolvedAction(action)) {
        action.setValid(true);
        return action;
    }
    Logger.wformat("Mark an action (%s) invalid", action);
    action.setValid(false);
    return null;
}
```
O método começa em **1328**; **1332 é uma linha dentro dele** (a chamada a `resolveAction`) — a referência do design ao "sítio 1332" aponta certo para o ponto de guarda, mas erra o método.

Corolário: o estado é implícito via o campo `newState`, não parâmetro. E `validateAllNewActions` (1342-1347) **descarta o retorno**:
```java
protected void validateAllNewActions() {
    Utils.assertNotNull(newState);
    for (ModelAction action : newState.getActions()) {
        validateNewAction(action);      // retorno descartado
    }
}
```
Ou seja, os efeitos colaterais (`setValid`, `resolveAt`) são o contrato inteiro nesse caminho — o que reforça §2.4.

**Correção**: atualizar assinatura em design.md (API Design + tabela Key Components + Mapping table).

### 3.2 "current state" deveria ser `newState` (MINOR, mas nas duas specs)

Ambos os deltas dizem que `validateAllNewActions` re-resolve "every action of the **current** state". O código itera `newState.getActions()`. `newState` e "estado corrente do agente" são conceitos distintos no `StatefulAgent` — a imprecisão importa num spec que descreve o comportamento pós-rebuild.

**Correção**: trocar por `newState` em `specs/exploration/spec.md` (Purpose + requirement) e no INV-EXPL-30.

### 3.3 D4 se apoia em um sítio que não existe (MAJOR)

Design **D4**:
> "Other `State.resolveAction` callers (`resolveNewAction`/`adjustActionsByGUITree`, `StatefulAgent.java:1454`) are deliberately left unguarded until F-D's maskan run shows whether they ever fire."

`StatefulAgent.java:1454` é:
```java
List<GUITreeNode> nodes = newGUITree.getNodes(action.getTarget());
```
— um cálculo de prioridade dentro de `adjustActionsByGUITree`. **Não é uma chamada a `State.resolveAction`.** E `resolveNewAction` (1349) chama `adjustActionsByGUITree()` (1351) e `selectNewActionNonnull()` (1352) — nenhum dos dois chama `State.resolveAction`.

**Fato verificado: 1332 é o único sítio de chamada de `State.resolveAction` em todo o `StatefulAgent`.**

Isso não invalida o F-C — ao contrário, **fortalece** a escolha do ponto de guarda (é o único ponto que existe). O que cai é o trade-off: não há "outros callers deixados sem guarda", logo não há decisão a tomar. A segunda Open Question ("Whether `adjustActionsByGUITree` ever fires the same failure in practice") fica **sem objeto** — `adjustActionsByGUITree` não pode disparar essa falha, porque não resolve nada; ele *pula* ações não-resolvidas (1440-1445).

**Correção**: reescrever D4 como "1332 é o único call site — guarda ali é exaustiva por construção" e **remover** a segunda Open Question. Reter, porém, o espírito do D7 (o gate maskan pode revelar um terminador em *outro* sítio, ex. `markVisited` sobre estado removido) — essa parte continua válida e é a única fonte de verdade sobre a identidade do killer.

### 3.4 `Model.java` — atribuições de linha imprecisas (MINOR/MAJOR misto)

- `saveActionHistory(File, List<ActionRecord>)` está em **96-112**, não 95-112.
- `Model.java:87` **não** está em `saveActionHistory`. É `GUITreeNode[] nodes = tree.pickNodes(modelAction);` dentro de `ActionRecord.resolveModelAction()` (77-93). A cadeia que o design descreve (`Model.java:87` → `GUITree.pickNodes` → `GUITree.java:168`) está **correta**; apenas não é o corpo de `saveActionHistory`.
- **"GUI action should not be null."** está em **`Model.java:81`**, dentro de `resolveModelAction` — não em `saveActionHistory`.
- **"Cannot find widget" não existe em `Model.java`.** Vive em `GUITree.java` nas linhas 140, 154, 168, 186. Só chega a `saveActionHistory` transitivamente.

Ponto crítico que os artefatos **acertam implicitamente mas não explicitam** — vale registrar, porque é o que torna o F-B necessário: o `catch (IOException)` em `Model.java:108` **não captura** essas falhas. Ambas são `IllegalStateException`, lançadas de dentro do `try`, e escapam de `saveActionHistory` sem tratamento, abortando o passo 4 do teardown:
```java
96    public static void saveActionHistory(File file, List<ActionRecord> actionHistory) {
97        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
98            for (ActionRecord record : actionHistory) {
99                Action action = record.modelAction;
...
102                if (action.isModelAction()) { record.resolveModelAction(); }   // <-- ISE escapa
105                ApeRRFormatter.startLogAction(pw, action, clockTime, timestamp);
106                ApeRRFormatter.endLogAction(pw, action, timestamp);
107            }
108        } catch (IOException e) {                                              // <-- não pega ISE
109            e.printStackTrace();
110            Logger.wformat("Fail to save action history into %s.", actionHistory);
111        }
112    }
```

**Correção**: ajustar as linhas em design.md/tasks.md (3.2 diz `Model.java:95-112` → 96-112); atribuir as duas mensagens de exceção a `resolveModelAction`/`GUITree`, não a `Model.saveActionHistory`; e adicionar ao delta `model` a frase de que o `catch (IOException)` existente não intercepta as `IllegalStateException` — é isso que justifica a guarda por-registro (e valida D5, que escolhe `RuntimeException`, corretamente).

### 3.5 Desvios de linha menores

| Referência no artefato | Real |
|---|---|
| `Monkey.java:616-620` (catch em `main`) | bloco vai até **621** (`System.exit(1)`) |
| `State.resolveAction:397` | método declarado em **388**; 397 é a chamada a `pickNodes` (a referência aponta o sítio certo, apenas não o método) |
| `Model.java:95-112` | **96-112** |

---

## 4. O que se confirmou verbatim

O diagnóstico forense é, na maior parte, sólido. Confirmados exatamente como descritos:

**`Monkey.java:775-786`** — inclusive o risco de ordenação (restore de rotação em 780 **antes** do teardown em 784; um throw em 780 pula o teardown inteiro):
```java
775        try {
776            crashedAtCycle = runMonkeyCycles();
777        } finally {
778            // Release the rotation lock if it's still held and restore the
779            // original orientation.
780            new MonkeyRotationEvent(Surface.ROTATION_0, false).injectEvent(mWm, mAm, mVerbose);
781            // INV-EXPL-16: flush APE state (model, coverage, traces) even when runMonkeyCycles
782            // throws — otherwise a crash mid-run silently loses every result of the run.
783            if (this.mEventSource instanceof MonkeySourceApe) {
784                ((MonkeySourceApe) this.mEventSource).tearDown();
785            }
786        }
```

**`MonkeySourceApe.java:221-231`** — `disconnect()` é o passo 1, sem guarda, e lança de fato (`MonkeySourceApe.java:213-216`: `if (!mHandlerThread.isAlive()) throw new IllegalStateException("Already disconnected!")`). Um throw ali mata todos os passos seguintes, incluindo `mAgent.tearDown()` que persiste o modelo.

**`StatefulAgent.java:1644-1653`** — exatamente os oito passos listados, na ordem descrita, todos sem guarda:
```java
1644    public void tearDown() {
1645        if (_llmRouter != null) _llmRouter.printSummary();
1646        super.tearDown();
1647        saveGraph();
1648        saveActionHistory();
1649        actionCounters.print();
1650        getGraph().printActivityNodes();
1651        model.getNamingManager().dump();
1652        model.printCounters();
1653    }
```

**`Naming.java:496-503`** — o NPE de mascaramento é real. `results` é declarada em 481, populada do cache em 483 (nula no cache-miss) e reatribuída em 493; se `namingInternal` estoura em 493, o `finally` desreferencia `results` nula em 501 e **substitui a exceção original**:
```java
496        } catch (RuntimeException e) {
497            e.printStackTrace();
498            throw e;
499        } finally {
500            long end = SystemClock.elapsedRealtimeNanos();
501            Logger.dformat("Create %d names for %d nodes in %d ms for tree %s by %s [...].", results.getNameSize(),
502                    results.getNameSize(), TimeUnit.NANOSECONDS.toMillis(end - begin), tree, this, updateNodeName);
503        }
```
`Naming.java:517` confirmado: `throw new IllegalStateException("A node has no namelets.");` (com ponto final; há um irmão `"A node has no namelet."` em 522 — cuidado ao grepar).

Observação de qualidade que o D6 não menciona: o `Logger.dformat` em 501-502 passa `results.getNameSize()` **duas vezes** — para `%d names` e `%d nodes`. Ao mover a linha para o caminho de sucesso (task 1.5), vale corrigir o segundo argumento para a contagem de nós, ou o diagnóstico continua mentindo. Não é bloqueador; é o momento oportuno.

**`checkNonDeterministicTransitions` (`StatefulAgent.java:752-768`)** — exato, incluindo a sequência `updateModel(newModel)` → `validateAllNewActions()`.

**`GUITree.java:164-168`** — exato:
```java
164    public GUITreeNode[] pickNodes(ModelAction action) {
165        int index = Arrays.binarySearch(currentNames, action.getTarget());
166        if (index < 0) {
167            printGUITree();
168            throw new IllegalStateException("Cannot find widget " + action.getTarget());
```

**`saveGraph()`** (1674+) já tem guardas de `IOException` por escritor (sataModel.obj 1686, sataGraph.dot 1695, sataGraph.vis.js 1704) — o design acerta ao dizer "existing IOException guards kept". Nota: essas guardas **não** cobrem `RuntimeException` vinda de `graph.printDot/printVis`, o que é justamente o que o `safeStep` do Grupo 1 passa a cobrir.

**Convenções**: `safeStep` **não existe** em lugar nenhum (zero ocorrências) — é criação nova, como o design assume. O tag `[APE-RV]` **existe** (44 ocorrências, via `Logger.wformat`/`iformat`/`println`). A linha `LLM Summary` em formato key=value **existe** (`LlmRouter.java:622-632`), então as novas linhas de telemetria (`ActionHistory total=/skipped=`, `RebindFailures total=`) seguem convenção estabelecida e o parsing do rv-android as pega.

---

## 5. Dimensão spec/OpenSpec — tudo limpo

- **INV-EXPL-16**: restatement é fiel palavra-por-palavra. Original (`specs/exploration/spec.md:63`): *"`tearDown()` SHALL run on every termination path of the exploration loop, normal or abnormal."* O delta reproduz idêntico, trocando só o ponto final por vírgula antes da extensão.
- **INV-EXPL-29 / INV-EXPL-30**: **livres**. Grep sobre todo `openspec/` (specs + change aberta + archive) só encontra os IDs dentro desta change. Maior INV-EXPL em uso antes: **28**. Numeração contígua e correta. (Nota lateral: INV-EXPL-07 e 08 não existem em lugar nenhum — lacuna pré-existente, não introduzida aqui.)
- **INV-MODEL-15**: **livre**. Maior em uso antes: **14** (consistente com 12/13/14 terem vindo de `llm-coordinate-tap`).
- **MODIFIED "Output Persistence on Termination"**: header existe verbatim em `specs/exploration/spec.md:374`. Diff normalizado (whitespace colapsado): **nenhum bloco original perdido**. O parágrafo narrativo de abertura e os **3** cenários pré-existentes (`Normal termination with defaults`, `saveObjModel disabled`, `abnormal termination still persists outputs`) foram carregados verbatim, com todos os bullets WHEN/THEN/AND. O delta acrescenta 2 parágrafos e 4 cenários. Como MODIFIED substitui por inteiro, essa fidelidade era o risco principal — e passou.
- **ADDED**: nem `Post-Refinement Action Revalidation Tolerance` nem `Tolerant Action-History Persistence` existem em qualquer spec. Vizinhos mais próximos são distintos: `StatefulAgent — Action History Ring Buffer` (buffer em memória para prompts LLM, não persistência) e `Model Serialization on Normal Termination` (cobre `sataModel.obj`, não `action-history.log`). Sem sombreamento.
- **Changes abertas conflitantes**: **nenhuma**. `refinement-crash-recovery` é a única não-arquivada. Superfície de conflito zero.
- **Schema `sdd-full`** (`openspec/schemas/sdd-full/schema.yaml`): exige `proposal` → `specs` → `design` → `tasks`. Todos presentes, mais `.openspec.yaml`. Completo.
- **Contagem de tasks**: 21 (6+3+2+3+7), bate com `openspec list` (0/21).

Saídas verbatim:
```
$ openspec validate refinement-crash-recovery --strict
Change 'refinement-crash-recovery' is valid
EXIT=0

$ openspec list
Changes:
  refinement-crash-recovery     0/21 tasks    5m ago

$ openspec validate --all --strict
Totals: 20 passed, 0 failed (20 items)
```

---

## 6. Risco de processo — os invariantes não vão sincronizar sozinhos

A extensão do INV-EXPL-16 e os três invariantes novos (INV-EXPL-29, INV-EXPL-30, INV-MODEL-15) vivem na seção de prosa `## Invariants` dos deltas, **fora de qualquer bloco ADDED/MODIFIED/REMOVED**. O `openspec archive` só reescreve conteúdo sob esses headers — logo **não** vai atualizar a linha 63 de `specs/exploration/spec.md`, e a cláusula *"SHALL NOT replace the in-flight exception"* se perde silenciosamente.

Isso **não é defeito do delta**: é o padrão estabelecido no repositório (`archive/2026-07-07-experiment-validity/specs/exploration/spec.md:62` usou a mesma seção `## Invariants`, e o INV-EXPL-16 chegou à spec principal) — mas chegou lá por **delta-sync manual**, que a memória do projeto registra como a prática corrente (`mop-fairtest-11-archived-specs-repaired`, `mop-census-launcher-implemented`).

**Ação**: garantir que o passo de archive desta change inclua o sync manual dos invariantes, ou usar `openspec archive --skip-specs` + sync manual, como nas changes anteriores. Vale registrar isso como uma task explícita no Grupo 5 (ver §7).

---

## 7. Sugestões consolidadas, por prioridade

### P0 — bloqueadores (antes de escrever qualquer código do Grupo 4)

1. **Reescrever D3** com o mecanismo correto: a ação falha de rebind é **invalidada** (`setValid(false)`), não "deixada não-resolvida". Documentar as 3 razões (campo `valid` pegajoso; ações sobrevivem ao rebuild por identidade de `StateKey`; nenhum filtro consulta resolução, e `resolvedNode` fica stale ao estourar) e registrar a alternativa rejeitada com o motivo: *deixar não-resolvida produz taps em coordenadas obsoletas e prompts LLM envenenados*.
2. **Corrigir API Design** — assinatura real `validateNewAction(ModelAction) -> ModelAction`; postcondition "marked invalid", não "left unresolved".
3. **Corrigir `specs/exploration/spec.md`** — requirement *Post-Refinement Action Revalidation Tolerance*: "SHALL be marked invalid (`setValid(false)`), which excludes it from every action filter". Isso dá mecanismo ao cenário *"unresolved action is not selected"*.
4. **Corrigir tasks 4.1/4.2** — o RED assere `isValid() == false` pós-falha; o GREEN menciona `setValid(false)`.

### P1 — afirmações falsas / imprecisões que enganam o implementador

5. **Reescrever D4**: 1332 é o **único** call site de `State.resolveAction` em `StatefulAgent` — guarda ali é exaustiva por construção. Remover o trade-off inexistente.
6. **Remover a 2ª Open Question** (`adjustActionsByGUITree` disparar a mesma falha) — sem objeto; ele pula não-resolvidas (1440-1445), não resolve nada. Manter a 1ª (identidade do terminador via gate maskan) e o D7 inteiro.
7. **Corrigir atribuições em `Model.java`**: `saveActionHistory` = 96-112; `Model.java:87`/`:81` pertencem a `resolveModelAction`; "Cannot find widget" mora em `GUITree.java:140/154/168/186`.
8. **Acrescentar ao delta `model`** a frase que justifica o F-B: o `catch (IOException)` de `Model.java:108` **não** intercepta as `IllegalStateException` de `resolveModelAction`, que escapam do método e abortam o teardown. (Isso valida D5 e deveria estar escrito.)
9. **Trocar "current state" por `newState`** nos dois deltas e no INV-EXPL-30.

### P2 — precisão e oportunidade

10. **Linhas**: `Monkey.java:616-621`; `State.resolveAction` declarado em 388.
11. **Task 1.5 — corrigir o argumento duplicado** do `Logger.dformat` (`results.getNameSize()` passado para `%d names` **e** `%d nodes`) ao mover a linha para o caminho de sucesso. Ou o diagnóstico continua reportando errado.
12. **Atualizar a seção Risks**: "Unresolved actions accumulate… shrinking the candidate set" passa de hipotético a real com a correção P0 — o texto deve dizer isso, e apontar `rebindFailureCount` como a métrica que o torna mensurável.
13. **Task nova no Grupo 5** — sync manual dos invariantes no archive (INV-EXPL-16 estendido + 29/30 + INV-MODEL-15), conforme §6.
14. **Considerar (fora de escopo, registrar como débito)**: `MonkeySourceApe:928` e `ApeAgent.checkInput:188` desreferenciam `resolvedNode` sem checar frescor. Um `assert isResolvedAt(timestamp)` no dispatch seria defesa em profundidade contra toda essa classe de bug. Não fazer nesta change (P1 do projeto: sem features especulativas), mas o gate maskan do Grupo 2 pode dar evidência a favor.

### Observação sobre o que **não** mudar

O Grupo 1 (F-A + fix do `Naming`), o Grupo 2 (gate F-D) e o Grupo 3 (F-B) estão **corretos como escritos**, modulo as correções de linha. A ordenação D7 (desmascarar → capturar verdade-terreno → só então tolerar) é metodologicamente sólida e é exatamente o que deveria ser feito: a identidade do terminador in-loop é **inferência**, e o F-C está desenhado sobre essa inferência. O gate 2.2/2.3 é o que a valida ou refuta. Não pule o Grupo 2.

---

## 8. Resumo tabular das 13 verificações de código

| # | Afirmação | Veredito |
|---|---|---|
| 1 | `Monkey.java:775-786` finally, 2 statements, rotação antes do teardown | **VERDADEIRA** (exata) |
| 2 | `Monkey.java:616-620` catch(Throwable) em `main` | VERDADEIRA (bloco vai até 621) |
| 3 | `MonkeySourceApe.java:221-231` tearDown, `disconnect()` passo 1, lança | **VERDADEIRA** (exata) |
| 4 | `StatefulAgent.java:1644-1653` tearDown, 8 passos sem guarda | **VERDADEIRA** (exata) |
| 5 | `Naming.java:496-503` NPE no finally; `:517` "A node has no namelets" | **VERDADEIRA** (exata) |
| 6 | `Model.java:87`, `:95-112`, mensagens de exceção | **PARCIALMENTE FALSA** — ver §3.4 |
| 7 | `validateNewAction(State, ModelAction) -> void` em 1332 | **FALSA** — 1 arg, retorna ModelAction, método em 1328 |
| 8 | `StatefulAgent.java:1454` chama `State.resolveAction` | **FALSA** — é cálculo de prioridade; 1332 é o único call site |
| 9 | `StatefulAgent.java:752-768` checkNonDeterministicTransitions | **VERDADEIRA** (exata) |
| 10 | `State.resolveAction:397` → `GUITree.java:168` "Cannot find widget" | VERDADEIRA (método em 388) |
| 11 | `saveGraph()` já tem guardas de IOException | **VERDADEIRA** |
| 12 | `safeStep` inexistente; `[APE-RV]` e `LLM Summary` são convenção | **VERDADEIRA** |
| 13 | "unresolved action naturally excluded; validity requires resolution" | **FALSA — BLOQUEADOR**, ver §2 |

---

## 9. Conclusão

A change é formalmente impecável e o trabalho forense por trás dela se sustenta: 7 dos 13 sítios de código foram confirmados verbatim, incluindo todos os quatro sítios de mascaramento HIGH, que são reais e valem o fix. Os Grupos 1-3 podem ser implementados como estão.

O problema está concentrado no F-C. A frase que fecha o D3 — *"naturally excluded from selection"* — é uma inferência plausível que o código refuta em quatro pontos independentes. Implementada literalmente, ela converteria uma exceção honesta em taps silenciosos sobre coordenadas de telas anteriores, justo no braço LLM cuja validade a change existe para restaurar. A correção é de uma linha (`action.setValid(false)`); o custo de não a encontrar seria um experimento cmpv2 que roda até o fim e mede lixo — falha muito mais cara do que a truncagem que estamos consertando, porque não se anuncia.

Recomendação: aplicar P0 e P1 nos artefatos, rodar `openspec validate --strict` de novo, e então seguir a ordenação D7 sem atalhos.

---

**Caminho completo deste relatório:**

```
/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/docs/20260717_verificacao_consistencia_refinement_crash_recovery.md
```
