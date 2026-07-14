# Análise adversarial independente — 4 OpenSpec changes + cobertura UI/MOP do APE-RV

**Modelo:** Claude Opus 4.8 (1M) · **Data:** 2026-07-07 · **Worktree:** `ape-mop-fairtest` (branch `mop-fairtest`, HEAD `c6c5d1f`)
**Método:** 6 subagentes adversariais em paralelo (4 changes + 2 empíricos), cada afirmação conferida contra o código RAW e contra 657 traces/logcats de `cmpft2`. Nada foi implementado. Toda referência `arquivo:linha` abaixo foi verificada; onde a fonte está fora deste worktree (docs `rvsec/rv-android`) marco **NÃO VERIFICÁVEL AQUI**.

---

## 1. Sumário executivo

### Veredito por change

| Change | Veredito | Achado dominante |
|---|---|---|
| `back-menu-pick-cap` | **NÃO-FUNCIONA como especificado** | BLOCKER de eficácia: o cap só cobre o canal epsilon-greedy, mas com o default `useActionDiffer=true` os BACK/MENU são consumidos **antes**, no EARLY_STAGE (`findGreedyActionForward:1227` / `findGreedyActionBackward:1278`), sem cap nem contabilização. O alvo "25,3%→<15%" não é mecanicamente sustentado (BACK, a metade maior, fica intacto). |
| `sibling-state-depriority` | **PRECISA-CORREÇÃO (leve)** | Sem BLOCKER; código e APIs corretos. MAJOR de validade: a evidência citada (`liveStates≥10`, tracker LRU) **não mede o gatilho** (`Model.getActivityNode().getStates().size()`, não-evictado). Calibração ancorada na métrica errada. |
| `foreign-activity-guard` | **PRECISA-CORREÇÃO** | Sem BLOCKER; estruturalmente correto e MOP-independente. MAJOR: `checkAppActivity` roda **antes**, sem whitelist, e reinicia sobre `packageinstaller`/`permissioncontroller` → INV-EXPL-21 ("modelado como in-package") não é durável; o guard só cobre a janela TOCTOU estreita. Narrativa superdimensionada. |
| `activity-frontier` | **PRECISA-CORREÇÃO** | Sem BLOCKER (o histórico `decision_source=Component` foi resolvido pela task 3.6). 3 MAJORs: (1) contradição INV-CT-03 não reconciliada em main; (2) arm-neutrality com default-ON confunde o fair-test; (3) colisão de código nos mesmos métodos das outras changes. **Além disso** (síntese Parte B): a Lever A (frontier boost) provavelmente é **inerte** neste corpus porque cavalga o WTG pass, cujo substrato está quase vazio (WTG dirige 0,09% das decisões). |

### Diagnóstico central da baixa cobertura

**A baixa cobertura NÃO é causada pelo steering MOP** — MOP dirige apenas **1,03%** das decisões e produz `maxBoost>0` em só **9,3% dos runs**; é inerte, como já registrado. As 4 changes mexem em *scoring de ação* e *seleção*, atacando efeitos secundários. Os drivers reais de perda de orçamento, por impacto agregado medido, são **majoritariamente não endereçados** por nenhuma das 4 changes:

1. **`waitForIdle(1000, 10000)` — 10.702 s totais** (~5,4% do budget médio; cauda até **72%** num único run). `MonkeySourceApe.java:480`. **Maior custo agregado.** Nenhuma change toca.
2. **Tempestades de restart + confusão activity/janela** — 10.038 restarts, **42% dos runs ≥10**; caso `org.fossify.messages`: 101–103 restarts, **4–5 steps por run**. `MonkeySourceApe.java:1174`. Nenhuma change toca (a `foreign-activity-guard` mitiga só de raspão).
3. **Substrato do produtor de análise estática** — 83% de widgets descartados por `id` ausente (`libchecker`: 660/793); `widgets=0` em **32,6%** dos traces; funil MOP 65%→18%→**9,5%** dos apps. Nenhuma change toca (é produtor-side).
4. **Fragmentação de naming em UIs sem `resource-id`** (Compose/AndroidX) — `Naming[N]` mediana 23, **max 695**; states/activity mediana 10, **max 64**. `sibling-state-depriority` ataca o *sintoma* (re-picks), não a causa.
5. **Navegação rasa** — activities/run **mediana 2**, 42% dos runs presos em 1 activity. `activity-frontier` Lever B ataca isso, mas é arm-gated e depende de manifest parseado.
6. **25,3% do budget em BACK/MENU** — `back-menu-pick-cap` ataca, mas com o BLOCKER acima é inerte.
7. **Vazamento p/ pacote estrangeiro** — 39,6% dos runs. `foreign-activity-guard` ataca, mas só na janela TOCTOU.
8. **Forms sem submit detectável** — `submit=none` em **75,6%** das linhas de FORM boost. Fora de escopo (login) mas afeta cobertura profunda.
9. **Crashes fatais que zeram runs** — 7 runs, incluindo `redreader` (fail-closed MOP, 900 s/0 steps — ver §4).

**Conclusão-síntese:** as 4 changes são defensáveis e de baixo blast-radius, mas têm **teto baixo** porque combatem efeitos secundários. Os maiores ganhos (itens 1, 2, 3) são triviais-a-médios e ficaram de fora.

---

## 2. Parte A — análise por change

### 2.1 `back-menu-pick-cap` — **BLOCKER de eficácia**

**Consistência (PASS):** flags/defaults/log/INV batem em proposal↔design↔spec↔tasks. `ape.backMenuPickCap=3`, `<=0` ilimitado, idênticos (proposal:13, design:17, tasks:1.1, `action-selection/spec:12`). INV-SEL-NAV-01..04 todos com task. O delta `mop-guidance` é **MODIFIED** e **copia o bloco inteiro** do requisito "MopScorer — OPTIONSMENU-Aware Menu Boost" (main spec 301-318), com os 2 cenários verbatim + gate `menuPickEligible` — formato correto. O delta `action-selection` é ADDED (requisito novo), não exige cópia.

**Fatos vs código (PASS):** todas as citações conferem — BACK/MENU target-less (`State.java:63-66`), short-circuits (`SataAgent.java:468-476`, `477-485`), least-visited (`:511`), roleta (`:519`), `mopPickKey` retorna null p/ target-less (`:589-591`), menu-boost gh13 (`StatefulAgent.java:1429-1440`, dentro de `if(_mopData!=null)` :1402), `mopWeightOpenMenu=250` (`Config.java:134`), `handleNullAction` uncapado (`:1612-1621`).

**BLOCKER — o cap é largamente inerte no default.** O cap intercepta só `selectNewActionEpsilonGreedyRandomly` (`:467`), que roda **depois** do EARLY_STAGE forward (`:431`) e backward (`:441`). Com `ape.useActionDiffer=true` (default, `Config.java:87`):
- `getGreedyActions`→`actionDiffer.getUnsaturated` (`:752`); quando `from==null` ou activity muda → `to.getUnsaturatedActions()` (`StateActionDiffer.java:51-56`) → `collectActions(ENABLED_VALID_UNSATURATED)`.
- `ENABLED_VALID_UNSATURATED` = `isEnabled && isValid && !isSaturated`, **sem `requireTarget`** (`ActionFilter.java:58-64`) → **inclui BACK e MENU**.
- `findGreedyActionForward` faz `randomPickWithPriority(candidates)` (`:1227`) — roleta que já inclui o +250 do menu-boost — e retorna no EARLY_STAGE.
- BACK unvisited é escolhido diretamente em `findGreedyActionBackward:1278` (`ENABLED_VALID_UNVISITED.include(next.getBackAction())`).

Nenhum desses dois sítios incrementa `backMenuPicks` nem consulta `menuPickEligible`. Logo o cap não bounda os picks dominantes e o gate de boost fica inerte no braço MOP (o +250 não é suprimido em `:1227`). É **o mesmo shadowing** que o próprio change set documentou para o MOP short-circuit (`action-selection` main spec:147: "EARLY_STAGE at 57.6% of decisions"). A análise causal do proposal (atribui a `SataAgent.java:467-485`) **aponta para o canal errado**.
→ **Correção:** estender cap+recording a `findGreedyActionForward:1227` e `findGreedyActionBackward:1278` (ou filtrar target-less capados de `candidates`), OU rebaixar o alvo ao subconjunto epsilon-greedy realmente coberto e **medir empiricamente** que a métrica se move antes de arquivar.

**MAJOR — assimetria de braço.** O cap (em `SataAgent`) é arm-neutral, mas o gate do boost vive em `if(_mopData!=null)` e o MENU só atinge o cap quando boostado (só no braço MOP). Logo cap+gate incidem preferencialmente no braço `sata_mop`, podendo **blunt o gateway gh13 T1.2** que existe justamente para alcançar MOP via menu. Documentar e validar `cov_mop` por braço.

**MINORs:** (a) a fiação do filtro capado em `:511` precisa preservar o 2º arg `submitExcluded` (INV-FORM-06) — não explicitado; (b) o gate de menu-boost não recebe INV id; (c) "priority 8→258" — a base 8 não é verificável no código.

### 2.2 `sibling-state-depriority` — **PRECISA-CORREÇÃO (leve)**

**Consistência (PASS):** `ape.siblingStatePenalty=24`, `0=disabled`, idêntico (proposal:13, design:17/52, spec:12, tasks:1.1); log idêntico; INV-COV-10/11/12 todos com task. É **ADDED Requirement** (não MODIFIED) → não há bloco a copiar; INV-COV-10..12 não colidem com INV-COV-01..09 já em main.

**Fatos vs código (PASS — incomum: 100% das citações corretas):** `adjustActionsByGUITree:1344`; passes MOP (1402-1441), WTG (1443-1467), coverage (1474-1497), form (1501-1521). Todas as APIs existem: `Graph.getActivityNode` (`Graph.java:205`, retorna null→"0 siblings"), `ActivityNode.getStates()` (`:71`, backing `HashSet` :34 → argumento "ordem instável, use contagem" procede), `maxStatesPerActivity=10` (`Config.java:109`), `getMopBoost`/`getWtgBoost` (`ModelAction.java:212/216`), `hasActivityInteraction` (`UICoverageTracker.java:260`). Aritmética 32→8→floor 1 confere. **Ordenação correta por construção:** o passe novo lê `mopBoost`/`wtgBoost` populados nos passes anteriores; `resetBoosts()` no topo do loop (`:1347`) evita valores stale. **Disjunção verificada:** coverage boost aplica em `!interacted` (`:1487`), penalty em `interacted` → **uma ação nunca recebe ambos**.

**MAJOR (validade experimental) — premissa↔gatilho desalinhados.** O "Why" ancora em "34% dos rollups terminam com `liveStates≥10` (UICOV-ACT)", mas `liveStates` conta fragmentos **vivos no tracker (LRU/bounded)** ao passo que o **gatilho** usa `Model.getActivityNode().getStates().size()` (**não-evictado**, cresce persistente). São populações diferentes (`Model.states ≥ liveStates`): o passe dispara **pelo menos tão frequentemente quanto — provavelmente mais** — inclusive em atividades com estados **legitimamente distintos** (wizard de 11 telas). A justificativa real e defensável já está no design (Decisão 1: ">10 estados ⇒ refinamento já bloqueado em `NamingFactory.java:276,1176`"), mas a calibração (24) foi escolhida contra a métrica errada, e o smoke (tasks 4.3) compara contra o trace que reporta `liveStates`.
→ **Correção:** reconciliar — medir/reportar `getStates().size()` por atividade — ou reescrever o "Why" para apoiar a change no argumento de refinamento-bloqueado.

**MINORs:** (a) proposal:24/:32 stale — `coverage-boost-activity-scope` **já arquivada** (`archive/2026-07-07-...`), dependência satisfeita; (b) design:87 justifica a interação com form-completion pela "floor 1", mas o que a torna inócua é `W_FILL=150 ≫ 24` (`FormCompletion.java:25`); (c) a change é **MOP-amplificadora** (alvos MOP ficam imunes a um penalty que atinge vizinhos não-MOP) — não MOP-neutra; documentar para a decomposição do efeito MOP; (d) a metade "frontier" da INV-COV-11 só é exercida se `activity-frontier` for mergeada (co-dependência não declarada).

### 2.3 `foreign-activity-guard` — **PRECISA-CORREÇÃO**

**Consistência (PASS):** whitelist `{packageinstaller, permissioncontroller}`, `systemui` excluído, INV-EXPL-20/21/22 únicos no repo, log idêntico, todos os INV com task. É ADDED Requirement (não MODIFIED) → sem bloco a copiar.

**Fatos vs código (PASS):** sítio em `generateEvents` (`MonkeySourceApe.java:788-798`); **`mAgent.updateState` tem call-site ÚNICO (`:798`)** → INV-EXPL-20 estruturalmente sólido. TOCTOU **real e confirmado**: `getNextEvent` chama `checkAppActivity` (`:1310`) antes de `generateEvents` (`:1314`), cada um refazendo `getTopActivityComponentName()` independentemente (`:1185` vs `:789`). O State deriva atividade de `topComp` (`model.getState(topComp,...)`, `StatefulAgent.java:889`), então guardar por `topComp.getPackageName()` é consistente. `checkPackage:910-922` é dead code (sem callers) — remoção P3 correta. `isPackageValid`≡`checkEnteringPackage` com `-p` presente (`MonkeyUtils.java:70-96`), e APE sempre roda com `-p`.

**MAJOR — whitelist inerte e INV-EXPL-21 não durável.** `checkAppActivity` roda **primeiro**, **não consulta a whitelist** (só trata `systemui/RecentsActivity` em `:1220`), e sobre pacote foreign fora de `waitForActivity` faz `startRandomMainApp()` (restart, `:1229-1231`). Esta change **não toca** `checkAppActivity`. Consequências: (1) quando `checkAppActivity` enfileira, `hasEvent()` fica true e o guard **nem é chamado** (`:1312`) — o guard só dispara na janela TOCTOU estreita; (2) para `permissioncontroller`/`packageinstaller`, mesmo modelados uma vez, o ciclo seguinte reinicia sobre eles → "modelado como in-package" (INV-EXPL-21) **não se sustenta >1 ciclo**; (3) o auto-grant (`Monkey.java:405`) casa só `com.android.packageinstaller`, **não** `permissioncontroller` (API 29+) → a "correção do gap de permissão" (proposal:12) não se concretiza sem alterar `checkAppActivity`.
→ **Correção:** rebaixar a narrativa (guard = só janela TOCTOU + prevenção da única `updateState`), OU estender a whitelist a `checkAppActivity` (registrar como dependência), OU remover a promessa de "grant flows working".

**MOP-independente (PASS):** usa `MonkeyUtils.getPackageFilter()` (do `-p`), sem acoplamento a `MopData`/`mopDataPath`. Limpo para fair-test.

**MINORs:** (a) deriva de linhas no design (`:794`/`:797` reais são `796`/`798`); (b) o BACK do guard é `generateKeyBackEvent` cru → **não é contado** pelo cap da `back-menu-pick-cap` nem entra no `[APE-STEP]`; (c) o `return` pula `notifyActionConsumed`/`appendToActionHistory` (benigno, mas não documentado); (d) cenário "divergent namespace" admitidamente não-testável no seam puro.

### 2.4 `activity-frontier` — **PRECISA-CORREÇÃO**

**BLOCKER histórico RESOLVIDO:** o else-branch de `resolveNewAction` emite hoje `SATA.name()` hardcoded (`StatefulAgent.java:1308-1316`); a task 3.6 exige ensiná-lo a emitir `Component` para `EVENT_TRIGGER_ACTIVITY`. Caminho alcançável (`Action.isModelAction()` :133-134; `EVENT_TRIGGER_ACTIVITY` antes de `MODEL_BACK` → `false`); `DecisionSource.Component` existe (`ModelAction.java:43`). Com a task 3.6, a atribuição é satisfazível. **Confirmado que o antigo BLOCKER não reincide.**

**Fatos vs código (PASS):** WTG pass real (`StatefulAgent.java:1442-1467`, `setWtgBoost` :1458); `MopScorer.scoreWtg` (`:108-118`); gate stagnação idêntico ao LLM hook (`SataAgent.java:393-402`, once-per-episode via `==` + reset :400); template `EVENT_RESTART` em `generateEventsForActionInternal:841`; `ActionType` predicados (`:22-63`) — inserir antes de `MODEL_BACK` zera ambos os predicados; enumeração exported/deep-link do manifest via `ComponentInfo` (`:21-35`), imune a `reachesTarget`; base CLICK=32, `mopWeightWtg=200` (`Config.java:171`) posiciona 200 entre 32 e 300/500. Numeração INV-WTG-06/07, INV-CT-05..08 **sem colisão** (04/05 e CT-04 já em main, changes arquivadas). Delta `exploration` MODIFIED **copia o bloco inteiro** de "ActionType Classification".

**MAJOR (1) — contradição INV-CT-03 não reconciliada.** `component-triggering/spec.md:38` (main) diz "INV-CT-03: Only BroadcastReceivers and Services SHALL be triggered. Activities... excluded". O delta é ADDED e declara em prosa que "supersedes the activity clause", mas INV-CT-03 vive numa seção bare `## Invariants` (não endereçável por MODIFIED) e **não há task** que reescreva o texto (contraste: para INV-EXPL-05 criaram a task 4.5). Após archive, main terá "Activities are excluded" **e** "Stagnation launches activities" — auto-contradição que `validate --strict` não detecta.
→ **Correção:** adicionar task espelhando a 4.5, reescrevendo a cláusula de atividade de INV-CT-03.

**MAJOR (2) — arm-neutrality com default-ON.** Lever A é gated por `_mopData!=null && hasWtgData()` (`:1443`); Lever B por `getMopData()!=null`. Ambos ativam **só** em arms com `mopDataPath`, e os defaults viram ON (`frontierBoostWeight=200`, `activityTriggerEnabled` flip para true, hoje `false` em `Config.java:140`). O arm MOP ganha profundidade que o baseline não tem → a diferença medida passa a incluir "frontier+launcher", não só MOP scoring. Divulgado honestamente (proposal:35), mas **default-ON torna o erro fácil numa corrida de validação**.
→ **Correção:** reforçar na task de smoke (5.3) e nos configs de experimento que arms não-MOP DEVEM setar `frontierBoostWeight=0 AND activityTriggerEnabled=false`; considerar default-OFF.

**MAJOR (3) — colisão de código.** Edita `adjustActionsByGUITree` (WTG pass 1442-1467), `SataAgent.selectNewActionNonnull` (launcher após LLM hooks), `resolveNewAction` (1308-1315), `buildTriggerTuples` (delete 1038-1040). Sobrepõe-se **no mesmo método** a `sibling-state-depriority` (coverage pass logo abaixo) e a `back-menu-pick-cap` (mesma cadeia de seleção). Exige merge sequencial + ordem determinística (ver §3).

**Síntese com Parte B — Lever A provavelmente inerte neste corpus.** A frontier boost cavalga o WTG pass, cujo substrato (`WtgTransition`) é raríssimo nos traces: **WTG dirige 0,09% das decisões** e `wtgBoost>0` é quase inexistente. Sem `getWtgTransitions` populado, `scoreWtg` não casa e o frontier nunca soma. **A Lever B (stagnation launcher) é o lever com chance real** de mover cobertura de atividade — mas é a que carrega o confound de arm-neutrality e depende de `components{}` parseado no manifest.

**MINORs:** `setWtgBoost` é overwrite (`ModelAction.java:218`) — acumular INV-WTG-07 exige read-modify-write **e** somar a `priority`; generics `List<ActivityInfo>` vs `List<ComponentInfo>` (usar `? extends`); código morto pós-delete não coberto pela task 3.5 (skip `!c.exported` :1043, ramo `dispatchTrigger` :1140-1142, javadoc INV-MOP-15 :1022-1030); test 19.6 vira falha dura após delete; rationale "unarchived mop-parser-fidelity" desatualizado.

---

## 3. Colisões e interações entre as 4 changes

Três das quatro tocam **`StatefulAgent.adjustActionsByGUITree`** e duas tocam a cadeia de seleção do `SataAgent`. As edições são **textualmente disjuntas** (merge resolvível), mas exigem **merge sequencial numa única worktree** e **ordem de passe determinística**. Ordenação final do método após as 4 changes:

```
adjustActionsByGUITree() (StatefulAgent.java:1344)
 1. loop base de prioridade                          1346-1400   (existente)
 2. MOP pass          if(_mopData!=null)             1402-1428   setMopBoost
 3. menu-boost        if(_mopData!=null)             1429-1440   ← back-menu-pick-cap: GATE por menuPickEligible
 4. WTG pass          if(_mopData!=null&&hasWtgData) 1443-1467   setWtgBoost ← activity-frontier: soma frontier
 5. coverage boost    if(coverageBoostWeight>0)      1474-1497   boost !interacted
 6. NEW sibling penalty  (~inserido 1497-1501)                   ← sibling-state-depriority
 7. form-completion                                  1501-1521   (existente)
```

**Restrição de ordem crítica:** o passe 6 (sibling) **precisa** rodar depois do passe 4 (WTG+frontier), porque isenta `wtgBoost>0`. Se um implementador inserir o sibling antes do WTG, os widgets frontier ainda não terão `wtgBoost` setado e serão **penalizados por engano** — anulando a Lever A. Nenhum artefato fixa essa restrição explicitamente; é uma **armadilha de composição** a documentar.

### Exemplo trabalhado (mesmo widget, braço MOP, atividade fragmentada >10 estados)

Widget CLICK **já interagido**, que abre uma atividade **não-visitada** via WTG, e é **MOP-reachable**:

| Passe | Operação | Prioridade | Boosts |
|---|---|---:|---|
| 1 base | CLICK visitado `4<<3` | 32 | — |
| 2 MOP | +300 (mopBoost) | 332 | mop=300 |
| 3 menu-boost | não é MENU → no-op | 332 | — |
| 4 WTG+frontier | +200 (WTG-MOP) +200 (frontier) | 732 | wtg=400 |
| 5 coverage | `interacted==true` → no-op | 732 | — |
| 6 sibling | isento (mop>0 **e** wtg>0) → sem −24 | 732 | — |
| 7 form | não é EditText → no-op | **732** | — |

**Composição correta:** o widget MOP+frontier domina a roleta; a isenção do sibling protege exatamente a esteira que se quer premiar. **Mas** — e aqui os defeitos de A convergem — essa prioridade 732 só importa se a **seleção** a consultar. No default `useActionDiffer=true`, a seleção passa por `findGreedyActionForward:1227` (`randomPickWithPriority`), que **usa** a prioridade (então o boost não é totalmente inerte no roulette), porém a **atribuição** é `SATA` (não MOP/WTG), o que explica o `decision_source=MOP=1%` subcontar a influência real. E o cap de BACK/MENU (change 1) **não** morde aqui porque este é um widget com target.

**Segundo exemplo — o caso adverso (MENU no braço MOP):** MENU target-less, atividade com OPTIONSMENU-gateway MOP. Passe 3 soma +250 (menuBoost). No default, esse MENU é elegível em `getUnsaturatedActions` (inclui target-less) e vence `randomPickWithPriority` no EARLY_STAGE (`:1227`) — **sem** consultar o cap nem o gate. Logo: (a) `back-menu-pick-cap` não contém este MENU (BLOCKER §2.1); (b) o gate de boost fica inerte; (c) `sibling-state-depriority` isenta target-less; (d) a assimetria de braço (§2.1 MAJOR) faz o MENU boostado ser o único a atingir o cap **quando** cair no epsilon-greedy. As 4 changes **compõem sem se anular**, mas a change 1 falha por conta do canal, não por conta das outras.

**Não há colisão de INV ids** entre as 4 (INV-SEL-NAV-* / INV-COV-10..12 / INV-EXPL-20..22 / INV-WTG-06,07+INV-CT-05..08). A única colisão real é **código no mesmo método** + a **restrição de ordem do passe 6**.

---

## 4. Parte B — bugs/anomalias, distribuição de cobertura, causas-raiz

Corpus: **657 runs** (219 apps × 3 reps), budget 300 s, arm `aperv:sata_mop`. 188.680 steps `[APE-STEP]`.

### 4.1 Anomalias ranqueadas por budget desperdiçado (com atribuição a `arquivo:linha`)

1. **`waitForIdle(1000, 10000)` bloqueia em apps animados — MAIOR custo.** `getRootInActiveWindowSlow()` chama `mUiAutomation.waitForIdle(1000, 1000*10)` a cada captura (`MonkeySourceApe.java:480`, verificado). **10.702 s totais**, 90 timeouts de 10 s. Pior: `com.dede.android_eggs` rep2 = **216.711 ms (72% do budget)** em 39 steps. Também caro (50–73 s/run): gauguin, photoprism, osmtracker, trail_sense, chess, aegis, wikipedia.
2. **Tempestades de restart + confusão activity/janela.** 10.038 restarts, **42% dos runs ≥10**. `org.fossify.messages`: 101–103 restarts, **4–5 steps/run** (~13 steps produtivos em 900 s). APE captura `MainActivity` mas a árvore de acessibilidade é a HOME do launcher → clica widgets `com.google.android.apps.nexuslauncher:id/...`. Nome de activity e janela em foreground inconsistentes. `MonkeySourceApe.java:1174`.
3. **Crashes fatais do APE (`[APE] Internal error`, `Monkey.java:617`) — 7 runs, 3 classes:**
   - **`StopTestingException` MOP fail-closed — `redreader` (3 runs, 0 steps, 900 s perdidos).** JSON de 48,3 MB rejeitado por `too-large` (`MopData.java:202`: `fileSize > maxMemory/6`, `PARSE_FOOTPRINT_FACTOR=6` :160, verificado) → `load` retorna null → `requireMopArm` (`StatefulAgent.java:183`, INV-MOP-22, verificado) **aborta o run inteiro em vez de degradar para SATA**. A interação too-large-guard × fail-closed **zera um app por ~0,3 MB acima do limite** e é um **defeito de validade do experimento** não coberto por nenhuma change.
   - **`StackOverflowError` — `com.ds.avare` (3 runs):** recursão em `GUITreeBuilder.buildNodeAndXmlFromNodeInfo` (`GUITreeBuilder.java:451`) em hierarquia profunda.
   - **`RuntimeException: An unvisited state has non-empty transitions` — `wikwok` (1 run):** `StatefulAgent.checkAndRefreshNewState` (`:617`).
4. **Null-root storms** — 1.379 ocorrências, **472/657 runs (72%)** (`MonkeySourceApe.java:806`). Correlaciona com crash-na-launch (`transdroid` 135 null-roots; `possin` 94; app crasha → árvore null → re-emite "activate action" em loop).
5. **Vazamento p/ launcher** — `nexuslauncher` abstraído como estado em **113/657 runs (17%)**.
6. **Apps GL/fullscreen não exercitados** — `stardroid` (OpenGL): 7/12/16 steps, árvore vazia, cai em fuzzing e morre cedo.
7. **Exceções do app-sob-teste** (achados legítimos, não bug do APE): `newpipe.error.Error` (1.378), `WebView not attached` (10/15), `ActivityNotFoundException market://` (12). Úteis — exceto quando derrubam o app repetidamente (item 4).

### 4.2 Distribuição de cobertura

**Por tipo de ação (187.812 steps):** CLICK 55,9% · BACK 15,4% · MENU 9,9% · LONG_CLICK 6,7% · SCROLL 12,1%. **BACK+MENU = 25,3%** do budget em navegação/escape.

**Por `decision_source`:** SATA **78,9%** · Coverage 18,1% · Form 1,3% · **MOP 1,0%** · Budget 0,4% · WTG 0,09% · Menu 0,01%. Comportamento ≈ 79% SATA puro + 18% coverage-boost; **MOP/WTG/Menu praticamente inexistentes como steering**. Nenhum `EVENT_TRIGGER_*` no dataset (confirma `activityTriggerEnabled=false`).

**Por tela/activity (fragmentação):** activities/run **mediana 2** (p25 1, max 20); **42,4% dos runs em 1 única activity**; states/run mediana 27; **states-per-activity mediana 10, p75 22, max 64**; `Naming[N]` mediana 23, **max 695**. Ground-truth (`per_task.csv`, NÃO VERIFICÁVEL AQUI): cov_act mediana 66,7%, cov_method 33,6%, cov_mop 34,5%.

### 4.3 Causas-raiz (todas com evidência de trace)

- **(a) Substrato do produtor — dominante para MOP.** widgets>0 em 134 apps (65%) → flagged>0 em 38 (18%) → `mop>0` em 21 (9,5%). `libchecker` descarta **660/793 (83%)** widgets por id ausente; `widgets=0` em **32,6%** dos traces.
- **(b) MOP inerte no runtime.** `maxBoost>0` em 2,9% das linhas; `decision_source=MOP` 1,0%; dispara em 21/219 apps. Apps com MOP têm cov_act **menor** (56,9 vs 67,5) — MOP não eleva cobertura.
- **(c) Fragmentação de naming.** REFINE opera sobre `resource-id=""` → cai em type+index e diverge por widget (Compose/AndroidX): Naming até 695, states/activity até 64.
- **(d) Navegação rasa.** mediana 2 activities, 42% single-activity, sem `EVENT_TRIGGER_*`.
- **(e) Vazamento estrangeiro.** 39,6% dos runs saem para gms.ads/launcher/diálogos.
- **(f) Forms sem submit.** `submit=none` em **75,6%** das linhas FORM boost; `decision_source=Form` 1,3%; login-walls em cov_act baixíssima (`pachli` 0%).

### 4.4 Sugestões priorizadas (impacto × simplicidade)

| Prio | Ação | Impacto | Simplicidade | Coberto pelas 4 changes? |
|---|---|---|---|---|
| **P0** | **Parametrizar/baixar o teto de `waitForIdle`** (10 s→2 s) em `MonkeySourceApe.java:480`, com flag | **Altíssimo** (recupera até 72% do budget em apps animados; 10.702 s agregados) | Trivial (1 linha + flag) | **Não** |
| **P0** | **Degradar-para-SATA no too-large em vez de fail-closed**, ou elevar/parametrizar o cap `maxMemory/6` (`MopData.java:202` × `StatefulAgent.java:183`) — mas **preservar** INV-MOP-22 marcando o arm como degradado no trace | Alto (recupera runs 100%-perdidos: redreader 900 s) | Baixa | **Não** |
| **P1** | **Corrigir a confusão activity/janela** que gera tempestade de restart e vazamento (itens 4.1#2/#5): validar que a árvore de acessibilidade pertence ao pacote esperado antes de modelar — é o **produtor** da `foreign-activity-guard`, mas a raiz é o descasamento `topComp` × janela visível | Alto (42% dos runs) | Média | Parcial (só TOCTOU) |
| **P1** | **Produtor: extração de `id`** — 83% de widgets dropados por id ausente mata o MOP na origem; sem isso o scorer nunca terá substrato | Alto (para MOP/método) | Produtor-side (fora deste repo) | **Não** |
| **P2** | **Bound de fragmentação de naming** para widgets sem `resource-id` (Compose/AndroidX) | Médio-alto | Média (toca core naming) | Parcial (`sibling` ataca o sintoma) |
| **P2** | **Detecção de submit** para forms (75,6% sem submit) — mesmo fora do login, destrava telas profundas | Médio | Média | **Não** (login fora de escopo) |
| **P3** | As 4 changes atuais (após correções da Parte A) | Baixo-médio (teto limitado) | Já feitas | — |

**Para (i) cobertura de UI:** P0 waitForIdle + P1 confusão activity/janela dão o maior salto. **Para (ii) método/MOP:** P1 produtor de `id` + P0 too-large são pré-requisitos — sem substrato, nenhum scoring MOP importa. **Para (iii) mais erros MOP:** idem substrato + P2 forms (telas MOP-bearing frequentemente ficam atrás de forms).

---

## 5. Riscos, mitigação e o que eu faria diferente

**Riscos das 4 changes:**
- `back-menu-pick-cap`: arquivar acreditando que reduz o churn de 25%→15% quando o mecanismo é inerte no default → **métrica não se move, conclusão falsa**. Mitigação: gate empírico obrigatório (medir a métrica no smoke antes de arquivar) + estender ao EARLY_STAGE.
- `activity-frontier`: default-ON invalida silenciosamente o fair-test (confound frontier/launcher no arm MOP) + Lever A inerte por substrato WTG vazio. Mitigação: default-OFF + validar substrato WTG antes de contar com a Lever A.
- `foreign-activity-guard`: superestimar o ganho (só TOCTOU) e a durabilidade da whitelist. Mitigação: rebaixar narrativa ou estender `checkAppActivity`.
- `sibling-state-depriority`: calibração contra métrica errada. Mitigação: reconciliar evidência (`getStates().size()`).
- **Transversal:** merge sequencial obrigatório numa worktree + **restrição de ordem do passe 6 (sibling depois de WTG+frontier)** — se violada, anula a Lever A.

**O que eu faria diferente (mais simples e maior teto):**
1. **Trocar prioridade:** antes das 4 tweaks de scoring, aplicar P0 `waitForIdle` (1 linha) e P0 too-large→degrade — são os maiores ganhos de budget medidos e triviais.
2. **`back-menu-pick-cap`:** em vez de um cap no canal errado, atacar a raiz — o EARLY_STAGE `getUnsaturatedActions` inclui target-less; **excluir BACK/MENU visitados do pool de unsaturated** resolve o churn na origem, sem novo flag de cap.
3. **`activity-frontier`:** manter só a **Lever B** (stagnation launcher, o único lever com substrato real — manifest), default-OFF, e **abandonar a Lever A** enquanto o substrato WTG estiver vazio (0,09%). Menos código, sem confound de arm.
4. **Não medir cobertura MOP** enquanto o **produtor** dropa 83% dos widgets e 32% dos apps têm `widgets=0` — é gastar rigor de scoring sobre um substrato ausente (consistente com o histórico de build-skew).

**Sobre o audit prévio (`20260708_verificacao_consistencia_4changes.md`, NÃO VERIFICÁVEL AQUI):** confirmo sua conclusão de que o BLOCKER `decision_source=Component` foi **resolvido** (task 3.6). Trago sinal **novo** além dele: (a) o **BLOCKER de eficácia EARLY_STAGE** da `back-menu-pick-cap` (não é inconsistência de artefato, é o canal errado); (b) a **inércia da Lever A** por substrato WTG vazio (cruzamento Parte A×B); (c) os **defeitos empíricos** waitForIdle e fail-closed-redreader, que dominam a perda de budget e nenhuma change ataca.
