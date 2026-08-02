# Design: rearch-06-memory-surgical

## Context

Stage 6 of 7 of the "Disposable Run Kernel" re-architecture (`docs/analise_fable-selecao.md` rev. 3, Sec. 6.7, Sec. 10 item 6; roadmap `docs/plans/20260802_rearchitecture_roadmap.md`). The scope is exactly the three verified, unequivocal retention defects — V12, V11, V24 — and nothing speculative: no bound or eviction on `Graph`, `treeHistory`, or naming structures (Sec. 6.7 defers those behind a heap profile; Sec. 7 rejects day-1 LRU bounds), and no OOM handling change (`OutOfMemoryError` remains process death + task FAILED in the supervisor).

Current state, verified at HEAD `5dcf225` in this design session (file:line re-checked, not copied from the report):

1. **V12 — per-node naming cache never cleared.** `GUITreeBuilder` holds three static memoization caches: `namingToGUITreeCache` and `namingToGUITreeNodesCache` keyed `Naming → GUITree → value` (`GUITreeBuilder.java:670-671`), and `namingToGUITreeNodeCache` keyed `Naming → GUITreeNode → Name` (`:693`, filled by `getNodeName`, `:695-705`). `release(GUITree removed)` (`:707-715`) removes entries from the two per-tree caches **only under `removed.getCurrentNaming()`** and never touches the per-node cache at all. Two distinct leaks follow: (a) every `(naming, node, Name)` entry lives for the whole run even after its tree is released; (b) entries created under non-current namings (refinement probes call `getStateKey`/`getNodeName` with candidate namings) survive release even in the two per-tree caches. A third defect compounds them: `checkAndRefreshNewState` calls the release cycle (`StatefulAgent.java:685-690`) and **then** computes `isTopNamingEquivalent(removed, last)` (`:692`, method at `:713-718`), which calls `GUITreeBuilder.getStateKey(topNaming, removed)` and **re-inserts** a cache entry for the just-released tree.
2. **V11 — diagnostic history retains full trees.** `Model.actionHistory` (`Model.java:136-137`, carrying the in-code `TODO: may be the cause of OOM`) is an unbounded `List<ActionRecord>` appended once per executed action (`MonkeySourceApe.java:1032`) plus once per crash (`ApeAgent.java:155-162`). `ActionRecord` (`Model.java:62-95`) holds `Action` + `GUITreeAction`; `GUITreeAction` (`src/main/java/com/android/commands/monkey/ape/tree/GUITreeAction.java:29-31` — note: `ape.tree`, not `ape.model`) holds `GUITree` + `GUITreeNode`; `GUITree` holds the entire `rootNode` subtree (`GUITree.java:66`). Nothing removes or clears records. Every step therefore pins one full GUI tree for the rest of the run, and — after a model rebuild — the retained `Action` references additionally pin removed states of the pre-rebuild model until the remap loop (`StatefulAgent.java:302-316`) rewrites them.
3. **V24 — independent retainer in `ModelAction`.** Resolution fields `resolvedNodes`/`resolvedNode`/`resolvedGUITreeAction`/`resolvedTree` (`ModelAction.java:83-87`) are written by `resolveAt` (`:228-249`, which also allocates a fresh `GUITreeAction` per resolve). Resolve sites: `State.resolveAction` (`State.java:425-438`), invoked for **every** action of the current state every step via `validateAllNewActions` (`StatefulAgent.java:1468-1473`, called at `:733`) and per-pick (`SataAgent.java:429`, `StatefulAgent.java:1458`); plus the teardown re-resolve `ActionRecord.resolveModelAction` (`Model.java:78-94`). References are only ever overwritten, never cleared — so an action resolved against a tree the model later releases keeps that tree reachable even though it left `State.treeHistory`.
4. **The release chain to imitate.** `State.removeLastLastGUITree` (`State.java:556-561`) → `GUITreeBuilder.release(removed)` + `model.release(removed)` (`Model.java:567-569`, delegating to `namingManager.release`). Call sites: `StatefulAgent.java:628-633` (`refreshNewState`, reached only from `ReplayAgent.java:111`) and `:685-690` (`checkAndRefreshNewState`, the SATA-path instability recheck). Report Sec. 3.2 confirms this chain **does** release correctly for what it covers — it is the model for the fixes, not a defect.

**Ordering preconditions (hard).** This change is specified against the post-stage-2/post-stage-4 codebase:

- `rearch-02-runspec` removes `saveGraph`/`readGraph`/`sataModel.obj`. Until then, `Model` (and `ActionRecord` inside it) is serialized to disk; changing `ActionRecord`'s shape while that path lives would churn a protocol that is being deleted anyway.
- `rearch-04-step-ndjson-telemetry` deletes `action-history.log`, i.e. `Model.saveActionHistory` (`Model.java:97-124`) and `StatefulAgent.saveActionHistory` (`:1850-1852`) — the **only** consumer that re-resolves every deep record through the rich `GUITreeAction` (`resolveModelAction` needs `guiAction.getGUITree()` + `tree.pickNodes`).

If either stage has not landed when this change is applied, group 3 (V11) is blocked — task 1.1 checks this explicitly. V12 and V24 do not depend on stages 2/4.

Constraints: R1–R9 (report Sec. 2); report Sec. 9 test 10 ("memory semantics: any new bound passes action-sequence parity + refinement invariants") is this change's acceptance family; `rearch-01-parity-oracle` supplies the neutrality evidence; P1/P3/P4 project principles.

## Caller Audit — V11 (`Model.actionHistory` / `ActionRecord`)

Enumerated by grep over `src/main/java` + `reducer/` at HEAD; every reader classified. **This table is the condition the proposal attached to the V11/V24 fixes.**

| # | Site | What it reads | Classification | Disposition at stage 6 |
|---|---|---|---|---|
| A1 | `Model.saveActionHistory` (`Model.java:97-124`), sole caller `StatefulAgent.saveActionHistory` (`:1850-1852`, teardown) | Every record; re-resolves via `resolveModelAction` → needs rich `guiAction` (tree + `pickNodes`) | Diagnostic (writes `action-history.log`) | **Deleted by rearch-04.** No consumer remains at stage 6 |
| A2 | `StatefulAgent.updateModel` remap loop (`:302-316`) | Every record's `modelAction` + `guiAction`; rewrites `modelAction` via `newModel.update(ModelAction, GUITreeAction)` (`Model.java:422-449`) after a rebuild | Semantic **in service of A1/A3 only** — it keeps records' model references non-stale for later readers | With snapshot records there is nothing to remap; the loop and `Model.updateActionHistory` (`:163-165`) are deleted (P3). The retained rich recovery point (A3) is remapped instead |
| A3 | `StatefulAgent.recoverCurrentState` (`:968-1001`), called at the top of every step (`updateStateInternal` `:743`) when `currentState == null` | Backward scan for the most recent model-action record; consumes its **rich** `modelAction` (`getState()`) and `guiAction` (`getGUITree()`), aborting if a later record `canStartApp()` | **SEMANTIC** — restores `currentState`/`currentAction`/`currentGUITree`/`currentGUITreeAction` after a state loss | The one live semantic consumer. It only ever needs the **most recent** model-action record. The design narrows V11 accordingly: rich retention shrinks to a single recovery point (decision D2), the deep list becomes snapshots |
| A4 | `StatefulAgent._actionHistory` (`:167`, window maintenance `:1779-1781`), writer `recordActionHistory` (`:1721`) | — | **Different structure**: the LLM prompt window (`ApePromptBuilder.ActionHistoryEntry`, capped at 5) | Out of scope; named here only to prevent confusion with `Model.actionHistory` |
| A5 | `reducer/ape/Reducer.java` (`:139-160` reads `model.getActionHistory()`, re-resolves records) | Rich records from a deserialized `sataModel.obj` | Host-side crash-minimization tool | **Not a runtime consumer**: `reducer/` is outside the Maven build (`src/main/java` is the only source root → not in `target/ape-rv.jar`), and its input artifact `sataModel.obj` is deleted by rearch-02. Dead tooling at stage 6; flagged, not modified |
| A6 | Writers: `Model.appendToActionHistory` (`:167-174`), `MonkeySourceApe:1032`, `ApeAgent:161` (crash records, `guiAction == null`) | — | Producers | Signatures unchanged (`Agent.appendToActionHistory`, `Agent.java:50`); the append site is where the snapshot is taken (the resolved objects are valid there — same step) |

**Audit verdict (V11):** one semantic consumer exists (A3) and it needs exactly one record deep. The proposal's condition — "no semantic path (rebuild/replay) depends on the rich objects" — holds for the **deep** history and fails only for the 1-record tail. The change proceeds **narrowed**, not dropped: snapshots for the list, a dedicated rich recovery point for A3 (D2). This is the "narrows the change" branch, exercised deliberately.

## Caller Audit — V24 (`ModelAction` resolved objects)

All readers of `getResolvedGUITreeAction()`/`getResolvedGUITree()`/`getResolvedNode()`/`getResolvedNodes()`/`resolvedSaturation`, classified by *when* they read relative to the last `resolveAt`:

| # | Site(s) | Guard | Classification |
|---|---|---|---|
| B1 | `adjustActionsByGUITree` (`StatefulAgent.java:1587-1590`); scoring passes `MopWidgetPass:47-49`, `WtgPass:52-54`, `FrontierPass:67-69`, `MopFrontierPass:72-73`, `CoveragePass:44`, `MopScorer:155-158`; `FormCompletion:47-50,:103-106` | `isResolvedAt(timestamp)` | Same-step by construction — a stale resolve is already skipped at HEAD |
| B2 | Event generation `MonkeySourceApe:649,:742,:950-958`; selection/emission `StatefulAgent:1482` (`newGUITreeAction` snapshot), `:1590-1602`, `isTopLeftClick:1641-1650`, `getThrottleForNewAction:1678`, `patchedField:1161`, `:1745`; `LlmRouter:492-538,:678-734,:814`; `ApePromptBuilder:810` (try/catch); `MopScorer.targetWidgetClass:209` (null-safe) | none (implicit) | Same-step: all operate on the current state's actions between `validateAllNewActions` (`:733`) and the end of the step, or on the just-picked action at execution |
| B3 | Refinement: `checkAndRefineOverAbstractedState` sort + `model.actionRefinement(action)` (`StatefulAgent:890-928`) → `NamingFactory.actionRefinement` (`:1158-1195`, reads `getResolvedNodes`/`getResolvedNode`) | none | Same-step: operates on `newState` actions resolved this step at `:733`. Ordering proof: `buildAndValidateNewState` (`:730-735`) runs `preCheckTrivialNewState` (the tree-releasing recheck, `:732`) **before** `validateAllNewActions` (`:733`), so refinement never sees references into a tree released this step |
| B4 | Cross-step: `isSaturated()` (`ModelAction.java:154-159`) / `getResolvedSaturation()` read by `ActionFilter:62`, `OnlyAddedUnsaturatedActionFilter:32`, `StateActionDiffer:74-99`, `SataAgent:358,:376`, `State.isSaturated` (`State.java:544-554`) | none | **SEMANTIC and cross-step** — but it reads the scalar `resolvedSaturation` (float), never the references. The V24 fix MUST preserve this field |
| B5 | `Model.update(ModelAction)` single-arg (`Model.java:451-476`, reads `getResolvedNode` on a stale-state action); `ModelAction.isOverAbstracted` (`:161-169`, throws if `resolvedNodes == null`) | — | **Dead code at HEAD**: zero callers for both (grep, whole repo). Not a constraint |
| B6 | `ActionRecord.resolveModelAction` (`Model.java:78-94`) — teardown re-resolve, **writes** the fields | — | Dies with rearch-04 (A1); at stage 6 the only writers are the live resolve sites |
| B7 | `ModelAction.toString`/`resolvedInfo` (`:175-188`), `toJSONObject` (`:333-355`) | null-safe | Diagnostic rendering; tolerates cleared references by construction |
| B8 | `ReplayAgent.java:111` → `refreshNewState` (`StatefulAgent:601-643`) release site `:628-633` | — | Replay path only; same release-cycle hook covers it. Replay presets are outside the parity-oracle preset set; noted, no extra work |

**Audit verdict (V24):** no consumer reads the resolved *references* of an action after the owning state's resolution cycle has moved on, except through agent-held field snapshots (`currentGUITreeAction` etc.), which are independent copies remapped by `updateModel` (`:264-290`) and unaffected by clearing the `ModelAction` fields. In particular, **no path reads references into a released tree** (B3's ordering proof closes the only candidate window). The scalar `resolvedSaturation` is the single cross-step semantic output and is preserved. The V24 fix is therefore safe exactly in the form D3 gives it: clear references **when their tree is released**, never the scalar.

## Architecture

No new components, no new subsystems (P1). Three local mechanisms:

```
release cycle (existing, per removed GUITree)
  State.removeLastLastGUITree
    └── GUITreeBuilder.release(removed)     ← V12: now sweeps all 3 static caches, all namings
    └── Model.release(removed)
          ├── namingManager.release(removed)      (unchanged)
          └── [V24] sweep removed.getCurrentState().getActions():
                     action.releaseResolved(removed)

append path (existing, per executed action)
  Model.appendToActionHistory
    ├── [V11] actionHistory.add(snapshot record — primitives/strings only)
    └── [V11] recovery point update (rich, depth 1) — feeds recoverCurrentState
```

### Key Components

| Component | Change | Input | Output |
|-----------|--------|-------|--------|
| `GUITreeBuilder` (caches + `release`) | V12: `namingToGUITreeNodeCache` re-keyed per tree; `release` sweeps all three caches under every naming | released `GUITree` | caches free of the tree |
| `StatefulAgent.checkAndRefreshNewState` | V12: top-naming equivalence computed **before** the release cycle (kills the re-insertion at `:692`) | — | release is the last touch of the removed tree |
| `Model.ActionRecord` | V11: becomes an identifier/snapshot record — no `Action`, no `GUITreeAction` | append-time resolved data | ~O(100 B) record |
| `Model` (recovery point) | V11: rich `(ModelAction, GUITreeAction)` pair of depth 1 + blocked flag, maintained on append | appended actions | what `recoverCurrentState` needs |
| `StatefulAgent.updateModel` / `recoverCurrentState` | V11: remap loop over the list deleted; recovery point remapped/read instead | rebuild events | equivalence with the HEAD backward scan |
| `ModelAction.releaseResolved(GUITree)` | V24: clears the 4 reference fields + invalidates the resolve timestamp iff `resolvedTree == released`; preserves `resolvedSaturation` | released tree | no dangling tree pin |
| `Model.release(GUITree)` | V24: adds the owning-state action sweep | released tree | both existing call sites covered with no new wiring |

## Mapping: Spec → Implementation → Test

| Requirement / Invariant | Implementation | Test |
|-------------------------|----------------|------|
| ui-tree "GUITree Release Clears Static Naming Caches" / INV-TREE-13 | `GUITreeBuilder.release` + re-keyed node cache; recheck reorder | JVM unit: release empties all three cache slices, all namings; released tree never re-cached; parity goldens unchanged |
| model "Diagnostic Action History Holds Snapshots" / INV-MODEL-18 | `Model.ActionRecord` snapshot shape; `appendToActionHistory` | JVM unit: appended record holds no `Action`/`GUITreeAction`/`GUITree`/`GUITreeNode` reference |
| model "Current-State Recovery Point" (same requirement) | recovery point machine in `Model` + `recoverCurrentState` | JVM unit: append-sequence equivalence with the HEAD backward scan (cases in D2) |
| model "ModelAction Resolved-Object Release on Tree Release" / INV-MODEL-19 | `ModelAction.releaseResolved` + `Model.release` sweep | JVM unit: refs cleared, saturation preserved, `isResolvedAt` false, other-tree refs untouched |
| model "Retention Fixes Are Decision-Neutral" / INV-MODEL-20 | (property of all three fixes) | rearch-01 parity goldens re-run after **each** fix group; report Sec. 9 test 10 |
| Heap effect observed (no gate) | — | Before/after `dumpsys meminfo` procedure on a 600 s standalone run (D5) |

## Goals / Non-Goals

**Goals:**

- Close V12: a released `GUITree` leaves every static `GUITreeBuilder` cache, under every naming, in the same release cycle — and is never re-inserted afterwards.
- Close V11: the unbounded diagnostic history stops retaining trees/actions; per-record cost drops from "one full GUI tree" to ~O(100 B) of primitives/strings; state recovery keeps working via a depth-1 rich recovery point.
- Close V24: a `ModelAction` never keeps a released tree reachable through its resolution fields; lifetime of the resolved references is bounded by "last resolve, while its tree is alive".
- Decision neutrality, evidenced: same seed ⇒ identical action sequence before/after each fix (rearch-01 goldens).
- Observed (not gated) heap improvement on a 600 s device run.

**Non-Goals (each with the reason, report-grounded):**

- **No bounds/eviction on `Graph`'s 17 collection fields** (`Graph.java:98-130`, verified count) — eviction in the exploration graph changes scientific behavior; gated on a heap profile by retention root over 600 s runs (Sec. 6.7, Sec. 7).
- **No bound on `State.treeHistory`** — same gate. `removeLastLastGUITree` stays an instability-recheck mechanism, not a retention policy (Sec. 3.2).
- **No eviction in naming/refinement structures** (`NamingFactory`, `naming.release` internals) — same gate; `maxGUITreesPerState` remains a refinement suppressor that frees nothing (Sec. 3.2), unchanged here.
- **No OOM catch, no serialization on a dying heap** — OOM remains process death; the supervisor's per-task retry is the recovery mechanism (Sec. 6.7). The `saveGraph` path that would have tempted a "heroic save" is already deleted by rearch-02.
- **No bound on the snapshot list itself** — it is O(steps) × O(100 B) ≈ hundreds of KB per 600 s run; bounding it now would be a speculative bound of exactly the class Sec. 7 rejects.
- **No change to `actionBuffer`, `_actionHistory` (LLM window, already capped at 5), or agent state fields.**

## Decisions

### D1 — V12 mechanism: re-key per tree, sweep all namings, release last

The node cache becomes `Naming → GUITree → (GUITreeNode → Name)`. `getNodeName(naming, tree, node)` already receives the tree (`GUITreeBuilder.java:695`), so the extra level costs one map hop and no signature change. `release(removed)` then removes the `removed` slice from **all three** caches under **every** naming (iterate the outer maps; the active-naming population is the small refinement lattice), unconditionally — before the existing `currentNaming == null` early return (`:709-711`), which today would skip cache cleanup entirely for a tree without a naming. `naming.release(removed)` keeps its current guard and scope (naming-internal caches are stage-6-out-of-scope structures).

The re-insertion defect: `checkAndRefreshNewState` computes `isTopNamingEquivalent(removed, last)` **after** releasing (`StatefulAgent.java:689` then `:692`), and `getStateKey(topNaming, removed)` re-caches the released tree. Fix by reordering: compute the equivalence result before invoking the release cycle, then release. The computation is pure (state-key construction + equality), so the reorder is observationally neutral; the parity goldens assert it.

*Alternatives rejected*: (a) sweeping the existing flat node map by node identity — `GUITreeNode` has no back-reference to its tree (verified: no such field), so the sweep would be O(total cache) per release or need a new back-pointer field on every node; (b) `WeakHashMap` — release timing becomes GC-dependent, which is unmeasurable and hostile to the parity/heap-measurement methodology.

*Neutrality argument*: all three caches memoize pure functions of immutable snapshot data (`Naming.getName(tree, node)`; `State.buildStateKey(naming, activity, names)`). Removing an entry can only force recomputation of the identical value. The only behavioral hazard is the re-insertion ordering, handled above.

### D2 — V11 mechanism: snapshot records + depth-1 rich recovery point

**Snapshot shape.** `ActionRecord` keeps `clockTimestamp` (long) and `agentTimestamp` (int) and replaces the two object references with primitives/strings captured at append time (the resolved objects are valid there — same step): action type name; state identifier (`State.getGraphId()`-style string; null for non-model actions); target `Name` as XPath string (null for targetless); GUITree id and tree timestamp (ints); throttle (int). This is the information `action-history.log` used to convey minus the re-resolution — sufficient for post-hoc debugging of a run and for any future NDJSON export, with zero object retention. After rearch-02 nothing serializes the record; `Serializable` is dropped with it (P3).

**Consumers after stages 2+4** (audit table A): the deep list has **zero** readers. It is retained as cheap in-memory diagnostics per the proposal ("IDs + minimal snapshot"), not because a reader requires it.

**Recovery point.** `recoverCurrentState` (A3) is served by a dedicated depth-1 structure maintained in `Model.appendToActionHistory`: a rich pair `(ModelAction, GUITreeAction)` plus a `recoveryBlocked` flag. Update rules on append — checked in the same precedence as the HEAD scan (`canStartApp` before `isModelAction`, `StatefulAgent.java:978-987`):

1. appended action `canStartApp()` → `recoveryBlocked = true`;
2. else if `isModelAction()` → recovery point = the rich pair, `recoveryBlocked = false`;
3. else (fuzz, crash, other events) → no-op.

`recoverCurrentState` recovers iff not blocked and a point exists. **Equivalence with the backward scan**: the scan walks from the end, returns on the first `canStartApp` record, recovers from the first model-action record, and skips everything else — which is precisely "the most recent model-action record, unless a `canStartApp` record is more recent". The three-rule machine maintains exactly that predicate incrementally; the equivalence cases (`[model]`, `[model, start, fuzz]`, `[start, model, fuzz]`, `[fuzz-only]`, empty) become unit tests.

**Rebuild remap.** The loop at `StatefulAgent.java:302-316` and `Model.updateActionHistory` (`:163-165`) are deleted (P3): snapshot records hold no model references, so there is nothing to go stale. The recovery point is remapped in `updateModel` exactly as the agent's own field pairs already are (`model.update(modelAction, guiAction)`, the `:264-290` pattern) — the rich pair survives refinement the same way `currentAction`/`currentGUITreeAction` do.

*Alternative rejected*: keeping rich records for a fixed window (say, last N) — N is a speculative bound with no consumer defining it; the audit gives the exact requirement (depth 1 + a flag) and the machine meets it with no tuning knob.

**Conditionality resolution.** The proposal made this fix conditional on the caller audit. The audit found one semantic consumer; the design narrows the change to keep it working. If implementation-time re-verification (task 1.2) finds a *new* consumer of the deep records, group 3 stops and this design must be amended first — that instruction is written into the tasks.

### D3 — V24 mechanism: release resolved references with their tree

`ModelAction.releaseResolved(GUITree released)`: iff `resolvedTree == released`, clear `resolvedTree`, `resolvedGUITreeAction`, `resolvedNode`, `resolvedNodes` and set the resolve timestamp to `-1` (so `isResolvedAt` is false for every timestamp, forcing a fresh resolve before any guarded use). **`resolvedSaturation` is preserved** — it is the one cross-step semantic scalar (audit B4); clearing it would change `isSaturated()` and with it SATA decisions. Priority/boost/provenance fields untouched.

Trigger: `Model.release(GUITree removed)` (`Model.java:567-569`) — already called by both release call sites (`StatefulAgent:628-633`, `:685-690`) — additionally sweeps `removed.getCurrentState().getActions()` (null-guarded) calling `releaseResolved(removed)`. No new call-site wiring; the sweep is in the same cycle as V12, which is what the report asks ("same cycle as `release()`").

**"Last resolve" lifecycle, stated precisely against the resolve sites:** an action's resolved references are written at `State.resolveAction` (mass per-step + per-pick) and consumed (audit B1–B3) only until the step's selection/refinement completes; the step-N+1 outcome path uses the agent's own field snapshots, not the action's fields. Overwrite-on-re-resolve already bounds retention to the last resolve; what remained unbounded was the *tree's* lifetime under that last resolve. After this fix the rule is: **resolved references live exactly as long as both (a) no newer resolve replaced them and (b) their tree is still owned by its state.** While (b) holds, the references retain nothing the state's `treeHistory` does not already retain — which is why no per-step clearing is needed or wanted.

*Alternative rejected*: clearing all of the previous state's actions at each step boundary — larger blast radius (touches `toString` diagnostics, null-safe readers, and the B2 set mid-flight), and closes no additional retention while the tree is alive in `treeHistory` anyway. Also rejected: `WeakReference` fields (GC nondeterminism, same reason as D1).

*Neutrality argument*: by audit B1–B3, no reader consumes references into a released tree — guarded readers already skip on the timestamp, unguarded readers are same-step and ordered after re-resolution (`:732` before `:733`), and dead code (B5) is the only path that could ever have seen them. Clearing what nothing reads changes no decision; the parity goldens assert it.

### D4 — Neutrality evidence: parity goldens after each fix, not a feature flag

The fixes are unconditional defect repairs, not features — no config flag, no kill-switch entry (R-rules; the `apePureMode` registry is not extended). Neutrality is evidenced by construction arguments (D1–D3) **plus** the rearch-01 parity oracle: the per-preset golden decision sequences captured at stage 1 must be byte-identical after each fix group lands (tasks 2.x/3.x/4.x each end with a parity run). This realizes report Sec. 9 test 10 for the only retention changes this stage makes.

### D5 — Heap observation procedure (measurement only, no CI gate)

Success is observed, not gated (Sec. 6.7 keeps profiling-driven work for later; this procedure also produces the baseline that later profiling would use):

1. Build the pre-fix jar (commit before group 2) and the post-fix jar (after group 4).
2. For each jar: `scripts/run_emulator.sh` (RVSec AVD); `adb push target/ape-rv.jar /data/local/tmp/`; `adb install test-apks/cryptoapp.apk`; run the standalone SATA command from `CLAUDE.md` with `--running-minutes 10` (600 s).
3. Every 60 s, record `adb shell dumpsys meminfo <monkey pid>` — the Dalvik "Heap Alloc"/"Heap Size" series (pid via `adb shell ps | grep app_process`, the Monkey `app_process` instance).
4. Optionally at 600 s, `adb shell am dumpheap <pid> /data/local/tmp/ape-heap.hprof` and pull for retention-root inspection (best effort — heap dump of a shell-uid `app_process` may be unavailable on the AVD image; the meminfo series is the primary observation).
5. Record both series and the end-of-run delta in the change's verification notes. Expected shape: reduced end-of-run heap and slope; **no threshold is promised** — the deliverable is the comparison itself.

Same seed for both runs where the harness allows, so the explored sequence is comparable.

### D6 — What this change deliberately leaves in place

`Graph`'s collections, `treeHistory`, naming caches inside `Naming`/`NamingFactory`, `actionBuffer`, and every `maxStatesPerActivity`/`maxGUITreesPerState` semantic — all unchanged (see Non-Goals). The known-issue note in `CLAUDE.md` ("OutOfMemoryError possible due to keeping all GUITrees in memory") is updated to reflect the narrowed current state (P4), not removed: trees are still retained by design in `treeHistory`.

## API Design

### `GUITreeBuilder.release(removed: GUITree) -> void` (extended contract)

- *Pre*: `removed` has been detached from its state's `treeHistory` (existing call discipline).
- *Post*: no static `GUITreeBuilder` cache contains an entry keyed by `removed` (any naming) or by any `GUITreeNode` of `removed`; `naming.release(removed)` invoked iff `removed.getCurrentNaming() != null`.
- *Errors*: none added; sweep over empty caches is a no-op.

### `Model.ActionRecord` (new shape)

`{ clockTimestamp: long, agentTimestamp: int, actionType: String, stateId: String?, targetXPath: String?, treeId: int, treeTimestamp: int, throttle: int }` — construction only in `appendToActionHistory`; no methods that resolve or reach model objects; `resolveModelAction` deleted with its last consumer (precondition: rearch-04 landed).

### `Model.appendToActionHistory(clockTimestamp: long, action: Action, agentTimestamp: int) -> void` (same signature)

- *Post*: one snapshot record appended; recovery point updated per D2 rules 1–3.
- *Errors*: none added; a model action with a null `resolvedGUITreeAction` at append remains the existing `IllegalStateException` class of bug (unchanged surface).

### `Model.getRecoveryPoint()` / recovery flag (accessor names final at implementation)

- Consumed only by `StatefulAgent.recoverCurrentState`; remapped in `StatefulAgent.updateModel` via the existing `model.update(ModelAction, GUITreeAction)`.

### `ModelAction.releaseResolved(released: GUITree) -> void`

- *Pre*: none (safe on unresolved actions).
- *Post*: if `resolvedTree == released` (reference identity): the four reference fields are null, resolve timestamp is `-1`, `isResolvedAt(t)` is false for all `t`; `resolvedSaturation` and all priority/boost/provenance fields unchanged. If `resolvedTree != released`: no-op.

### `Model.release(removed: GUITree) -> void` (extended)

- *Post*: existing `namingManager.release(removed)` behavior, plus `releaseResolved(removed)` invoked on every action of `removed.getCurrentState()` (no-op if the tree has no current state).

## Data Flow

Per released tree: `removeLastLastGUITree` → (reordered) top-naming equivalence computed → `GUITreeBuilder.release` sweeps the three caches → `Model.release` sweeps naming manager + owning state's actions. Per executed action: `appendToActionHistory` reads the action's (valid, same-step) resolved objects once, emits a primitive snapshot into the list, and updates the depth-1 recovery point. Per rebuild: `updateModel` remaps agent field pairs + the recovery point; the snapshot list is untouched (nothing in it can dangle).

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Recovery point absent (fresh run / blocked by start) | `recoverCurrentState` | Return without recovery — identical to the HEAD scan's empty/blocked outcomes | Next state build proceeds as entry state (existing path) |
| `removed.getCurrentState() == null` during V24 sweep | `Model.release` | Skip the action sweep (null-guard) | None needed — no actions can hold refs to a tree never appended to a state |
| Guarded reader hits a cleared resolve | any B1 site | Already handled at HEAD: `isResolvedAt` false → skip | Action re-resolved at its state's next visit |
| Unguarded null-safe reader hits a cleared resolve | B7 diagnostics | Existing null-safe rendering | n/a |
| Stage 2/4 not landed at apply time | task 1.1 | **Stop group 3 (V11)**; V12/V24 may proceed | Re-sequence per roadmap |

## Risks / Trade-offs

- **[A "surgical" eviction touches an unmapped semantic path]** → the audit tables above are re-verified by grep at implementation time (group 1 tasks); every fix group is conditional on its audit task; parity goldens after each group are the behavioral tripwire (report Sec. 11 row 4).
- **[Cache re-insertion after release reappears via a future caller]** → INV-TREE-13 states "release is the last touch"; the recheck reorder is the only current caller ordering to fix; the invariant makes any regression a spec violation, and the unit test pins it.
- **[Recovery-point machine diverges from the scan on an unconsidered sequence]** → the equivalence is over a 3-case append alphabet (`canStartApp` / model / other); unit tests enumerate the sequence classes incl. crash records (`guiAction == null` non-model appends are rule-3 no-ops, matching the scan's skip).
- **[Heap effect smaller than hoped]** → accepted: the deliverable is closing verified defects + the measurement; further reduction is explicitly profiling-gated future work (Sec. 6.7).
- **[Reducer tooling silently broken]** → it already is dead at stage 6 (input artifact removed by rearch-02, not built into the jar); flagged in the audit rather than repaired — repairing a tool for a deleted artifact would be scope growth (R4).

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (JVM, `mvn test`) | V12 cache sweep (all 3 caches, all namings; no re-insert; recompute-identical), V11 snapshot shape + recovery-point equivalence cases, V24 `releaseResolved` postconditions | rearch-01 fixture kit (synthetic trees/namings); no Android runtime | ~12–16 tests |
| Parity | Same seed ⇒ identical decision sequence after each fix group | rearch-01 golden suite, re-run per group (gate) | existing suite × 3 runs |
| Device (observational) | Heap before/after on 600 s standalone run | D5 procedure; results recorded, no gate | 2 runs |

## Open Questions

- None blocking. Two items intentionally deferred with owners: (a) any bound on `Graph`/`treeHistory`/naming waits for the D5 baseline + a retention-root profile (report Sec. 6.7); (b) if rearch-04's final artifact set ends up keeping any `actionHistory`-derived output (not currently planned), the snapshot fields in D2 are the negotiation surface — the field list, not the object graph, would grow.
