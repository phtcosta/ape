# Delta: Exploration Engine (isolate-guidance-groups)

## Purpose

This delta brings the exploration engine's requirements in line with the step as a sequence of owners
(`decision-pipeline`) and with the group mounting rule (`guidance-groups`):
- **The restart is a guard stage.** It runs before any selection, so a restart no longer replaces an action
  already chosen, and the step records `dec.src="restart"` with its kind.
- **The LLM hooks consult declared gates, not the SATA buffer.** A new state reached mid-path goes to the LLM, and
  the buffer is abandoned without weakening.
- **The fuzz toss comes from its own stream, every step.**
- **ADDONS read sites read the plan.** `MODEL_MENU`, the dynamic epsilon and the two event-generation guards are
  ADDONS members, so their sites read the plan instead of `Config` (design D-B6). Otherwise the `base` cell would
  still carry them.

## Invariants

- **INV-EXPL-31**: Each of `State` (menu action), `SataAgent.egreedy` (dynamic epsilon) and `MonkeySourceApe` (the
  foreign-activity and tree/package guards) SHALL decide through `RunContext.current().spec().has(Feature.X)`.
  None of them SHALL read the corresponding `Config` field. On a plan without the `ADDONS` group, each of these
  mechanisms SHALL behave as upstream APE.
- **INV-EXPL-32**: For an agent with a decision pipeline (`SataAgent`), no restart SHALL replace a selected action
  after selection, and every restart SHALL be a step whose owner is `restart` (`decision-pipeline` INV-DP-14). The
  `Restart` stage is mounted if and only if `ape.checkRestart` is `true`. `RandomAgent` and `ReplayAgent` have no
  pipeline and keep the post-selection `checkRestart` wrapping unchanged (design D-B14.12).

## MODIFIED Requirements

### Requirement: OptionsMenu Systematic Exploration (MODEL_MENU)

Every `State` object SHALL hold a `menuAction` field of type `ModelAction(this, ActionType.MODEL_MENU)`, initialised in the `State` constructor immediately after `backAction`. The field SHALL be exposed via `State.getMenuAction()` and SHALL be non-null for the life of the state. This mirrors the `backAction` / `getBackAction()` pattern exactly.

Inclusion of the `menuAction` in the state's **selectable** action set is gated by the plan feature `MODEL_MENU` (key `ape.modelMenuEnabled`, default `true`), a member of the ADDONS group (design D-B6, INV-GRP-06). `State` SHALL read `RunContext.current().spec().has(Feature.MODEL_MENU)` and SHALL NOT read `Config.modelMenuEnabled` (`model/State.java:78` before this change). Otherwise a cell without ADDONS would still carry the menu action. When the plan carries `MODEL_MENU` (the ADDONS block sets `ape.modelMenuEnabled=true`), the `menuAction` SHALL be included in the array returned by `State.getActions()`, exactly as before this change. When the plan does not carry it (ADDONS off, as in the `base` and `mop` cells, or `ape.modelMenuEnabled=false`), the `menuAction` SHALL NOT be included in `State.getActions()` and the agent SHALL never select `MODEL_MENU`; the field SHALL still be constructed and returned by `State.getMenuAction()` (so `INV-EXPL-06` holds). This reproduces upstream APE, which has no model-level options-menu action.

`MonkeySourceApe.generateEventsForActionInternal()` SHALL handle `MODEL_MENU` in its switch statement by calling `generateKeyMenuEvent()`. No target widget node is required or inspected.

`MonkeySourceApe.validateResolvedAction()` SHALL return `true` for `MODEL_MENU` without calling any widget validator (same pattern as `MODEL_BACK`).

#### Scenario: State constructor initialises menuAction
- **WHEN** a new `State` is constructed for any `StateKey`
- **THEN** `state.getMenuAction()` MUST return a non-null `ModelAction` whose `getType()` returns `ActionType.MODEL_MENU`
- **AND** when the plan carries `MODEL_MENU` (e.g. the `as` cell) the `menuAction` MUST be included in the actions array returned by `state.getActions()`

#### Scenario: MODEL_MENU excluded from selection when modelMenuEnabled is false
- **WHEN** the plan does not carry `MODEL_MENU` (the `base` cell, with ADDONS off, or `ape.modelMenuEnabled=false`) and a new `State` is constructed
- **THEN** `state.getMenuAction()` MUST still return a non-null `ModelAction` of type `MODEL_MENU`
- **AND** `state.getActions()` MUST NOT contain the `menuAction`
- **AND** the agent MUST never select `MODEL_MENU` for that state

#### Scenario: MODEL_MENU event generation
- **WHEN** `MonkeySourceApe.generateEventsForActionInternal()` is called with a `ModelAction` whose type is `MODEL_MENU`
- **THEN** `generateKeyMenuEvent()` SHALL be called
- **AND** no target `GUITreeNode` SHALL be required or consulted

#### Scenario: MODEL_MENU validation always passes
- **WHEN** `MonkeySourceApe.validateResolvedAction()` is called with a `ModelAction` of type `MODEL_MENU`
- **THEN** the method SHALL return `true`
- **AND** no widget validator (`validateClickAction`, `validateScrollAction`) SHALL be invoked

---

### Requirement: SataAgent — Forced App Restart on Graph Stability

`SataAgent` (via its superclass `StatefulAgent`) monitors whether the exploration graph has stopped growing. The counter `graphStableCounter` is incremented each step in which no new `State` or `StateTransition` is added to the `Graph`. When `graphStableCounter` exceeds `ape.graphStableRestartThreshold` (default: `100`) in step 2's `checkStable`, `onGraphStable` SHALL raise the restart flag with kind `graph` (`requestRestart()`) and the counter MUST be reset to zero immediately. This prevents the exploration from getting stuck in a saturated region of the state space. The state and activity stability checks raise the same flag with kinds `state` and `activity`. The activity threshold is `ape.activityStableRestartThreshold` when the plan carries the ADDONS member `ACTIVITY_STABLE_RESTART`, and otherwise the upstream `Integer.MAX_VALUE` (design D-B5).

The restart itself is selected by the `Restart` guard stage of the decision pipeline (`decision-pipeline` "Restart Guard Stage"). That stage runs after `Budget` and before any LLM, MOP or SATA stage, and it selects an `EVENT_RESTART` or `EVENT_CLEAN_RESTART` start action. It is mounted if and only if `ape.checkRestart` is `true` (BASE, jar default `true`; design D-B14.12), and it replaces, for an agent with a pipeline, the post-selection `checkRestart` that used to substitute the start action for the action already chosen. `RandomAgent` and `ReplayAgent` have no pipeline and keep that post-selection wrapping unchanged. The step's record carries `dec.src="restart"` and `dec.restart` set to the kind, and `dec.a` names the start action that ran. A restart preempts the step. It never replaces a selected action, so no selected model action is lost to it.

#### Scenario: Graph stable for threshold steps

- **WHEN** `graphStableCounter` reaches `100` (the value of `ape.graphStableRestartThreshold`) consecutive steps without any change to the `Graph`
- **THEN** `StatefulAgent.onGraphStable(100)` SHALL be called on the active agent
- **AND** `SataAgent.onGraphStable()` SHALL return `true`, indicating a restart should be performed
- **AND** the `Restart` stage SHALL select a start action (`EVENT_RESTART`, or `EVENT_CLEAN_RESTART` with probability 0.2) before any LLM or SATA stage is evaluated, with `dec.src="restart"` and `dec.restart="graph"`
- **AND** `graphStableCounter` SHALL be reset to `0`

#### Scenario: Graph grows; counter resets

- **WHEN** a new `State` is discovered on step N (making `graphStableCounter = 0` at step N-1 become irrelevant)
- **THEN** `graphStableCounter` SHALL be reset to `0`
- **AND** the restart SHALL NOT be triggered regardless of the previous counter value

#### Scenario: RandomAgent ignores graph stability

- **WHEN** `RandomAgent` is the active strategy
- **AND** `graphStableCounter` reaches `100`
- **THEN** `RandomAgent.onGraphStable(100)` SHALL return `false`
- **AND** no forced restart SHALL be initiated by the graph-stability path

---

### Requirement: Fuzzing Integration

When `ape.doFuzzing=false`, the fuzzing path is disabled and the agent never injects random `FUZZ` actions outside the structured exploration loop. When `ape.doFuzzing=true` (the BASE feature `FUZZING`), at each step the agent MAY inject a random `FuzzAction` with probability `ape.fuzzingRate` (default: `0.02`, i.e., 2% of steps). The toss SHALL come from `StepRng`'s own `fuzz` stream (`decision-pipeline` INV-DP-13), not from `RandomHelper`. It is drawn at step open on **every** step, whether or not fuzzing is enabled and whatever the step's owner, so that enabling fuzzing or changing its rate never shifts a helper-stream draw. This changes the helper-stream sequence relative to `eea2b1a9`, where `MonkeySourceApe` drew `RandomHelper.toss(fuzzingRate)` only when `doFuzzing` was true. The design declares the change, and the goldens are recaptured. The injection happens when the toss holds, `ape.doFuzzing` is true and `Agent.canFuzzing()` admits; it is checked in `MonkeySourceApe` after the step's action events are generated. Fuzzing MUST only activate after the app's activity has been visited at least `ape.fuzzingActivityVisitThreshold` (default: `10`) times, preventing fuzzing from disrupting early exploration.

#### Scenario: Fuzzing disabled

- **WHEN** `ape.doFuzzing=false`
- **THEN** `Agent.canFuzzing()` SHALL return `false` at every step
- **AND** no `FuzzAction` SHALL be injected into the event queue
- **AND** the fuzz toss SHALL still be drawn from the `fuzz` stream on every step, and no `RandomHelper` draw SHALL be made for fuzzing

#### Scenario: Fuzzing fires at expected rate

- **WHEN** `ape.doFuzzing=true` and `ape.fuzzingRate=0.02`
- **AND** the current activity's visit count exceeds `ape.fuzzingActivityVisitThreshold` (default: `10`)
- **THEN** across a large number of steps, approximately 2% of steps SHALL inject a `FuzzAction`
- **AND** the remaining 98% of steps SHALL proceed through the normal action-selection path

---

### Requirement: StatefulAgent — Action History Ring Buffer

`StatefulAgent` SHALL maintain a ring buffer of the last 5 selected model actions with their results. When a model action is selected, before it is dispatched, an `ActionHistoryEntry` SHALL be appended to the buffer with an empty result. The entry's result SHALL be the outcome of that entry's own action, filled by the next state update before any LLM stage of that step runs, so that no prompt pairs an action with another action's outcome.

**`ActionHistoryEntry`** is a simple data class:

| Field | Type | Description |
|-------|------|-------------|
| `actionType` | `String` | "click", "long_click", "type_text", "back", "menu" |
| `widgetClass` | `String` | Widget class simple name (e.g., "Button", "EditText"), null for back, menu and an off-tree LLM tap |
| `widgetText` | `String` | Widget text or content-description, truncated to 50 chars |
| `bounds` | `Rect` | The executed widget's bounds in screen **pixels**: a copy of the resolved node's `getBoundsInScreen()`; for an off-tree `MODEL_LLM_TAP`, the one-pixel rectangle `[x,y][x+1,y+1]` at `LlmTapAction.getPixelX()`/`getPixelY()`; null for back and menu |
| `typedText` | `String` | For type_text: the text that was typed; null otherwise |
| `result` | `String` | "same", "new screen", "previous screen"; empty until the next state update fills it |

`StatefulAgent` SHALL NOT normalize and SHALL NOT read `AndroidDevice.getDisplayBounds()` for the history. `ApePromptBuilder` derives the displayed coordinate at build time (the centre of the bounds' part inside the display) with the dimensions the decision was captured at (`llm-prompt` "Action History"), so the list and the history of one prompt share one base.

An action with no resolved node SHALL record what ran: a `MODEL_LLM_TAP` records `click`, or `long_click` when `LlmTapAction.isLongClick()` is true, with the one-pixel rectangle at the tap's own pixel, and no class or text; a `MODEL_MENU` press records `menu` with no bounds. A node action whose bounds cannot be read records null bounds, never a `(0,0)` placeholder.

For a node action, `widgetClass` SHALL be produced by the helper the element list uses for the class simple name, and `widgetText` by the text-then-content-description steps of the list's identifier fallback (`llm-prompt` "Widget List Generation", INV-PRM-05), not by reading `getClassName()` or `getText()` directly. The list's `id=<shortId>` step is not applied (`pending.md` P2).

**Result determination**: In `updateStateInternal()`, after the new state is resolved and `_lastState`/`_stateBeforeLast` have moved, and before `resolveNewAction()` runs, the last entry of the buffer, if its result is still empty, receives the outcome of its action. At that point `newState` is the screen that action produced, `_lastState` the state it was taken from, and `_stateBeforeLast` the state before that:
- If `newState == _lastState` → `"same"`
- If `newState == _stateBeforeLast` → `"previous screen"`
- Else → `"new screen"`

The entry is immutable: the agent replaces it with a copy carrying the result. A filled result is never overwritten. When the step after an entry's action selects no model action, nothing is appended, and the next update finds the last entry already filled, so an outcome is never assigned to an action it does not belong to.

**Restart**: A restart is selected by the `Restart` guard stage before any model action is selected (`decision-pipeline` INV-DP-14). A restart step therefore appends no entry and removes none: the start action is not a model action, and no model action was recorded for that step. The post-selection removal that `StatefulAgent.checkRestart` performed no longer exists, and `startNewEpisode` removes nothing on any path.

The buffer is passed to `ApePromptBuilder.build()` via `LlmEngine.selectAction()` as the `recentActions` parameter.

#### Scenario: Action history populated after action

- **WHEN** a click action on Button "Login" is selected
- **THEN** an `ActionHistoryEntry` SHALL be added with `actionType="click"`, `widgetClass="Button"`, `widgetText="Login"` and an empty result
- **AND WHEN** the next state update resolves a state different from the state the click was taken from and from the state before that
- **THEN** that entry's result SHALL be `"new screen"` before any LLM stage of that step runs

#### Scenario: Ring buffer evicts oldest entry

- **WHEN** the action history buffer has 5 entries
- **AND** a new model action is selected
- **THEN** the oldest entry SHALL be removed
- **AND** the new entry SHALL be appended
- **AND** the buffer size SHALL remain 5

#### Scenario: Action returns to previous screen

- **WHEN** a back action is selected from state S2, which was reached from state S1
- **AND** the next state update resolves S1
- **THEN** the back action's `ActionHistoryEntry.result` SHALL be `"previous screen"`

#### Scenario: each entry carries its own action's outcome
- **WHEN** click A1 is selected from state S1 and the next update resolves a new state S2
- **AND** click A2 is selected from S2 and the next update resolves S2 again
- **THEN** the prompt built at that update SHALL show `A1 → new screen` and `A2 → same`
- **AND** A2's entry SHALL NOT read `new screen`, the outcome of A1

#### Scenario: a step without a model action does not shift an outcome
- **WHEN** click A1 is selected and the next update fills its result
- **AND** that step selects an action that is not a model action, so no entry is appended
- **AND** the update after it resolves a different state
- **THEN** A1's result SHALL keep the value filled first

#### Scenario: a model action replaced by a restart leaves no entry
- **WHEN** click A1 is selected and the next update fills its result
- **AND** on the next step the `Restart` guard selects a start action before any model action is selected
- **THEN** no entry SHALL be appended for that step, so no entry for a replaced action can exist
- **AND** A1's entry SHALL remain the last entry, with the result filled first

#### Scenario: a restart that replaces no model action removes nothing
- **WHEN** click A1 is selected and the next update fills its result
- **AND** the `Restart` guard takes the following step with a start action
- **THEN** A1's entry SHALL remain, with its result, and the buffer SHALL be unchanged

#### Scenario: entry carries the pixel bounds
- **WHEN** a click on a widget with bounds `[0,105][1080,252]` is selected
- **THEN** the entry SHALL carry the bounds `[0,105][1080,252]`, a copy rather than the node's own `Rect`
- **AND** no display dimension SHALL have been read to produce it

#### Scenario: off-tree LLM tap recorded at its pixel
- **WHEN** a `MODEL_LLM_TAP` at pixel `(600, 900)` with `isLongClick()` false is selected
- **THEN** the entry SHALL carry `actionType="click"`, the bounds `[600,900][601,901]`, and no widget class

#### Scenario: menu press recorded as menu
- **WHEN** a `MODEL_MENU` action is selected
- **THEN** the entry SHALL carry `actionType="menu"`, no bounds and no widget class

#### Scenario: icon with only a content-description
- **WHEN** a click is selected on a node of class `android.widget.ImageButton` with empty text and content-description `"Settings"`
- **THEN** the entry SHALL carry `widgetClass="ImageButton"` and `widgetText="Settings"`

---

### Requirement: SataAgent — LLM New-State Hook

LLM new-state routing SHALL be the `LlmNewState` decision stage, the first stage after the `Budget` and `Restart` guards. It is assembled only when the plan carries `LLM_NEW_STATE`. `SataAgent.selectNewActionNonnull()` SHALL contain no inline LLM block: it delegates to `DecisionPipeline.decide()`, and the stage occupies the ladder position the inline block occupied (before stagnation, coin, launcher, trigger, MOP coin and SATA; the per-cell goldens gate the behaviour).

The stage checks the new-state trigger (`_isNewState`) first, with no coin (D-A17). It then evaluates the gate chain that all three LLM stages share (`llm-routing` "Declared LLM Gates"): `MinActionsGate(ape.llmGateMinActions)`, default 3, which skips trivial states such as permission dialogs with two actions or fewer; then `BreakerGate`. The action buffer is **not** a gate (D-A6, P-4). A new state reached while a SATA path is buffered goes to the LLM, and when the LLM takes the step the buffer is abandoned without `weakenStateTransition`, with `dec.buf="llm"` (`decision-pipeline` INV-DP-15). On a non-null engine result the stage returns `Select` (stamping `DecisionSource.LLM`/`PickChannel.LLM`, and resolving a synthesized `MODEL_LLM_TAP` against the state — unchanged accept semantics); on null it returns `Continue` and the remaining pipeline decides (structural fallback).

When the plan has no LLM feature, the stage does not exist and selection cost is zero.

#### Scenario: LLM provides action on new state

- **WHEN** the pipeline reaches `LlmNewState` with `actions.size() >= 3`, `_isNewState` true, breaker allowing
- **AND** the engine returns a non-null `ModelAction`
- **THEN** the action SHALL decide the step (hard preemption — no later stage evaluated)

#### Scenario: LLM returns null, SATA takes over

- **WHEN** the engine returns `null`
- **THEN** the stage SHALL return `Continue`, with `dec.own.fb` naming the reason
- **AND** the launcher, trigger, MOP-coin and SATA stages SHALL evaluate normally

#### Scenario: LLM skipped — buffer has pending navigation

- **WHEN** the action buffer holds two planned transitions (multi-step navigation in progress), the state is new with 5 actions, the breaker allows, and the LLM returns an accepted action
- **THEN** the LLM SHALL be called and its action SHALL decide the step: the buffer is no longer a gate
- **AND** the buffer SHALL be abandoned without `weakenStateTransition`, and the step record SHALL carry `dec.buf="llm"`
- **AND** the header is kept because `openspec archive` matches scenarios by name, although the LLM is no longer skipped

#### Scenario: LLM skipped — trivial state

- **WHEN** `actions.size() <= 2` (e.g., permission dialog), with `ape.llmGateMinActions` at its default `3`
- **THEN** `MinActionsGate` SHALL refuse, the LLM stages SHALL return `Continue` with `dec.own.g="min_actions"`, and the pipeline SHALL handle the state directly

#### Scenario: LLM disabled, zero overhead

- **WHEN** the plan carries no LLM feature
- **THEN** no LLM stage SHALL exist, no `u_L` SHALL be drawn, and the pipeline SHALL be structurally identical to the corresponding non-LLM cell's (`as_mop_llm` without LLM is `as_mop`)
- **AND** the decision sequence SHALL equal that cell's golden under the same seed

---

### Requirement: SataAgent — LLM Stagnation Hook

LLM stagnation routing SHALL be the `LlmStagnation` decision stage, assembled after `LlmNewState` and before `LlmCoin` when the plan carries `LLM_STAGNATION`. The trigger is at-or-past the midpoint with the episode's single shot unspent: `graphStableCounter >= graphStableRestartThreshold / 2` AND the stage's per-episode fired flag is armed. The trigger consumes no coin (D-A17). When it holds, the same shared gate chain applies (`MinActionsGate`, then `BreakerGate`; `llm-routing` "Declared LLM Gates"). The action buffer is not a gate, and an accepted answer abandons a non-empty buffer without weakening (`decision-pipeline` INV-DP-15). A gate refusal leaves the flag armed, because no call was made.

**Episode-state ownership (moved out of `StatefulAgent`):** the fired flag SHALL be a private field of the `LlmStagnation` stage. It is burned inside `decide()` whenever the trigger fires and the gates admit the call — whatever the LLM answers (a null result is a failed attempt, not an unused one; the restart at the full threshold is what follows if the stagnation persists) — and re-armed by the stage's `onStateTransition` hook on `NEW_ACTION`/`NEW_ACTION_TARGET` edges (a new edge ends the episode). The stage's own reset of `graphStableCounter` after a successful escape does not re-arm the flag: no new edge was observed, so it is the same episode.

**One scenario below is a replacement, not a carry-forward.** `"Stagnation hook fires only once per phase"` asserted in the main spec that the hook fires *only* at `graphStableCounter == threshold/2` exactly — 49 and 51 both declined by the equality check. This change replaces that trigger with at-or-past the midpoint guarded by the episode flag, so the old body is contradicted rather than forgotten: 51 with the flag armed now fires. What the scenario's name asserts — one firing per episode — is what survives, and it survives structurally (the flag is burned in `decide()`, not re-derived from the counter), so the scenario is restated under its own header with the new mechanism as its body. The counter-jump case the old equality check would have missed is covered by `llm-routing :: Stagnation LLM Mode :: "midpoint skipped by a counter jump still fires"`.

`graphStableCounter` remains a `StatefulAgent`/`RunContext` field — it is shared exploration state consumed by the stability restart (`onGraphStable` raises the flag at `counter > threshold`, and the `Restart` guard selects the restart), which this stage does not modify. On an accepted escape the stage SHALL reset the counter to 0 through the `StepContext`'s single declared write method (unblocking exploration, unchanged semantics).

#### Scenario: LLM breaks stagnation (single-shot at midpoint)

- **WHEN** `graphStableCounter` reaches `graphStableRestartThreshold / 2` with the episode flag armed, the shared precondition holds, and the breaker allows
- **AND** the engine returns a non-null `ModelAction`
- **THEN** the action SHALL decide the step, the flag SHALL be burned, `graphStableCounter` SHALL be reset to `0`
- **AND** `requestRestart()` SHALL NOT be called

#### Scenario: Stagnation hook fires only once per phase

- **WHEN** `graphStableCounter` is `49` with `graphStableRestartThreshold=100` (below the midpoint) and the episode flag is armed
- **THEN** the stage SHALL NOT fire
- **WHEN** the counter then reaches `50` and the stage fires, burning the flag
- **THEN** every later step of the same episode — `51`, `52`, and every counter value up to the restart threshold — SHALL NOT fire again, whatever the counter reads
- **AND** the single shot SHALL be spent by the firing itself, not by the counter leaving the midpoint (the pre-change equality check declined `51` because `51 != 50`; the flag declines it because the episode is over)

#### Scenario: LLM fails at midpoint, stagnation continues to restart

- **WHEN** the trigger fires and the engine returns `null`
- **THEN** the flag SHALL be burned and the stage SHALL return `Continue`
- **AND** the counter SHALL continue incrementing and the stage SHALL NOT fire again this episode
- **AND** if `graphStableCounter` eventually exceeds `graphStableRestartThreshold`, `requestRestart()` SHALL be called and the `Restart` guard SHALL select the restart on the next step (INV-EXPL-09)

#### Scenario: new edge re-arms the episode through the stage hook

- **WHEN** the flag is burned and a later transition records a `NEW_ACTION` edge
- **THEN** `onStateTransition` SHALL re-arm the flag inside the stage
- **AND** a later stagnation reaching the midpoint SHALL fire again

#### Scenario: no stagnation state remains in StatefulAgent

- **WHEN** the change is complete
- **THEN** `StatefulAgent` SHALL declare no LLM-stagnation fired flag
- **AND** the only stagnation-episode state SHALL live in the `LlmStagnation` stage

---

### Requirement: Dynamic Epsilon Decay

`SataAgent.egreedy()` SHALL compute a coverage-adaptive epsilon when the plan carries `DYNAMIC_EPSILON` (key `ape.dynamicEpsilon`). That feature is a member of the ADDONS group, so it is absent from the `base` and `mop` cells (design D-B1). The epsilon value SHALL be interpolated between `Config.minEpsilon` and `Config.maxEpsilon` based on the current state's UI coverage gap:

```
epsilon = minEpsilon + (maxEpsilon - minEpsilon) * coverageGap
```

Where `coverageGap` is obtained from `UICoverageTracker.getCoverageGap(currentState)`. The SataAgent accesses the tracker via the `getCoverageTracker()` accessor inherited from `StatefulAgent`.

**Scope note**: `egreedy()` is called from `selectNewActionEpsilonGreedyRandomly()`, which is one of the last steps in the SATA selection chain. Dynamic epsilon has incremental impact — it improves decisions that reach epsilon-greedy, but does not affect decisions resolved earlier (LLM hooks, buffer, early-stage, trivial activity).

When the plan does not carry `DYNAMIC_EPSILON`, upstream APE's behavior SHALL be preserved: epsilon is the fixed value `defaultEpsilon`.

#### Scenario: New state (high gap)
- **WHEN** `egreedy()` is called in a state with coverageGap=1.0 and maxEpsilon=0.15, minEpsilon=0.02
- **THEN** the effective epsilon SHALL be 0.15

#### Scenario: Fully explored state
- **WHEN** `egreedy()` is called in a state with coverageGap=0.0
- **THEN** the effective epsilon SHALL be 0.02 (minEpsilon)

#### Scenario: Dynamic epsilon disabled
- **WHEN** the plan does not carry `DYNAMIC_EPSILON` (the `base` cell, or `ape.dynamicEpsilon=false`)
- **THEN** `egreedy()` SHALL use the fixed `defaultEpsilon` value (`0.05`)

---

### Requirement: Foreign-Activity Guard in Event Generation

When the plan carries `FOREIGN_ACTIVITY_GUARD` (key `ape.foreignActivityGuard`, a member of the ADDONS group) and the freshly fetched top activity component is non-null, `generateEvents` SHALL evaluate the pure decision `shouldModel(pkg, filterAccepts, systemWhitelist)` before calling `mAgent.updateState`, where `pkg` is `topComp.getPackageName()`, `filterAccepts` is `MonkeyUtils.getPackageFilter().isPackageValid(pkg)` (the same predicate the active backstop `checkAppActivity` gates on), and `systemWhitelist` is the fixed set {`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`} (the Google-image package is required because the RVSec AVD runs a Google emulator image whose runtime-permission UI is `com.google.android.permissioncontroller`; the AOSP entry alone would never match). `com.android.systemui` is NOT whitelisted — `checkAppActivity` already treats it as invalid and restarts on it, so the guard SHALL BACK out of it. When the decision is negative, the source SHALL enqueue exactly one BACK key event plus the standard throttle and return without invoking `updateState` — the foreign screen SHALL NOT be abstracted into a `State`, SHALL NOT be registered in UI-coverage tracking, and SHALL NOT contribute actions.

The decision SHALL be based exclusively on the component's package name (the task's applicationId), never on activity class-name prefixes. A null top component SHALL bypass the guard (existing START/ACTIVATE handling proceeds). A null package SHALL be treated as modelable (uncheckable — defer to the existing paths). `MonkeySourceApe` SHALL read the feature from the plan (`spec().has(Feature.FOREIGN_ACTIVITY_GUARD)`) and SHALL NOT read `Config.foreignActivityGuard` (design D-B6, INV-GRP-06). When the plan does not carry it (ADDONS off, as in the `base` and `mop` cells, or the key false), event generation SHALL be identical to the pre-guard specification. Each deflection adds one to `RUN_END.counters.guardBacks`.

The first deflection of each distinct foreign package SHALL log `[APE-RV] Foreign activity: pkg=<pkg> -> BACK`; subsequent deflections of the same package SHALL NOT log (throttle — a persistent foreign screen must not spam the trace). The dead `checkPackage(ComponentName, AccessibilityNodeInfo)` helper SHALL be deleted (P3 — it has no callers and the guard supersedes its intent).

- **INV-EXPL-20**: No `State` or UI-coverage registration SHALL ever originate from a screen whose package fails the guard decision.
- **INV-EXPL-21**: Screens of the system-interaction whitelist packages SHALL NOT be backed out by the guard (the guard SHALL be a no-op for them). This is a guard-local guarantee only: it does not assert durable in-package modeling — `checkAppActivity`, which this change does not touch, may still restart over these packages on a later cycle.
- **INV-EXPL-22**: The plan feature `FOREIGN_ACTIVITY_GUARD` SHALL gate the entire guard block; with the flag false, event generation SHALL follow the pre-guard path (no BACK deflections, no guard log lines). This is a reasoning invariant over the flag placement, not a pure-seam matrix case — `shouldModel` has no flag parameter.

#### Scenario: launcher screen deflected
- **WHEN** the app under test is `com.example.app`, a click lands the device on `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`, and `generateEvents` runs with the guard enabled
- **THEN** one BACK key event SHALL be enqueued, `updateState` SHALL NOT be called, and (on first occurrence) the trace SHALL contain `[APE-RV] Foreign activity: pkg=com.google.android.apps.nexuslauncher -> BACK`

#### Scenario: permission dialog not deflected by the guard
- **WHEN** the top component's package is `com.google.android.permissioncontroller` or `com.android.permissioncontroller` (runtime permission dialog; the Google package is the one seen on the RVSec Google-image AVD)
- **THEN** the guard SHALL be a no-op for that cycle and `updateState` SHALL proceed normally (a guard-local no-op — this does not guarantee the screen survives `checkAppActivity` on a later cycle)

#### Scenario: divergent class namespace is not misflagged
- **WHEN** the app under test has applicationId `info.metadude.android.fosdem.schedule` and the top component is `info.metadude.android.fosdem.schedule/nerd.tuxmobil.fahrplan.congress.MainActivity`
- **THEN** the caller SHALL derive `pkg` from `topComp.getPackageName()` (the applicationId `info.metadude.android.fosdem.schedule`), never from the activity class prefix `nerd.tuxmobil.*`, so `filterAccepts` is true and the screen is modeled normally (a caller-level `getPackageName()` assertion, not a `shouldModel`-seam matrix case)

#### Scenario: guard disabled
- **WHEN** the plan does not carry `FOREIGN_ACTIVITY_GUARD` (the `base` cell, or `ape.foreignActivityGuard=false` with ADDONS on) and a foreign screen reaches `generateEvents`
- **THEN** the screen SHALL be modeled exactly as before this change (leak-and-restart behavior), with no guard log line

---

### Requirement: Tree/Package Guard in Event Generation

When the plan carries `TREE_PACKAGE_GUARD` (key `ape.treePackageGuard`, a member of the ADDONS group) and the freshly fetched top activity component is non-null, `generateEvents` SHALL evaluate the pure decision `shouldRefetch(topPkg, treePkg, systemWhitelist)` before calling `mAgent.updateState`, where `topPkg` is `topComp.getPackageName()`, `treePkg` is `info.getPackageName()` (the accessibility root's owning package, converted null-safely to a String), and `systemWhitelist` is the `SYSTEM_INTERACTION_PACKAGES` set defined by `foreign-activity-guard` ({`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`}). `shouldRefetch` SHALL return true (mismatch) only when `treePkg` is non-null, differs from `topPkg`, and is not in the whitelist.

When `shouldRefetch` is true and at least one refetch iteration remains (`repeat > 0` inside the existing `while (repeat-- > 0)` loop), the source SHALL `continue` — re-fetching both `topComp` and `info` on the next loop iteration — and SHALL NOT invoke `updateState` on the current pair: the mismatched screen SHALL NOT be abstracted into a `State`, SHALL NOT be registered in UI-coverage tracking, and SHALL NOT be built into a `GUITree`. When `shouldRefetch` is true but the refetch iterations are exhausted (`repeat == 0`), the source SHALL fall through and model the pair exactly as today (fail-open) — the guard SHALL NOT deadlock capture on a persistent mismatch.

The decision SHALL be based exclusively on package names (`topComp.getPackageName()` and `info.getPackageName()`), never on activity class-name prefixes. A null top component SHALL bypass the guard (existing null / START handling proceeds). A null tree package SHALL be treated as a match (uncheckable — defer to the existing paths, model normally). A tree owned by a whitelist package SHALL NOT be treated as a mismatch (a permission dialog legitimately owning the tree over the app is modeled, not re-fetched). `MonkeySourceApe` SHALL read the feature from the plan (`spec().has(Feature.TREE_PACKAGE_GUARD)`) and SHALL NOT read `Config.treePackageGuard` (design D-B6, INV-GRP-06). When the plan does not carry it (ADDONS off, or the key false), event generation SHALL be identical to the pre-guard specification.

The first mismatch of each distinct `top→tree` package pair SHALL log `[APE-RV] Tree/package mismatch: top=<pkg> tree=<pkg> -> refetch`; subsequent mismatches of the same pair SHALL NOT log (throttle — a persistent mismatch across the refetch iterations must not spam the trace). The guard SHALL be inserted after `foreign-activity-guard`'s top-component foreign check and before `updateState`, and SHALL reuse that change's whitelist set rather than redefining it.

- **INV-EXPL-26**: While at least one refetch iteration remains, no `State`, `GUITree`, or UI-coverage registration SHALL originate from a `(topComp, tree)` pair whose tree package fails the guard decision (mismatched and not whitelisted).
- **INV-EXPL-27**: On refetch-iteration exhaustion the guard SHALL fail open — a still-mismatched pair SHALL be modeled exactly as the pre-guard path; the guard SHALL NOT prevent capture indefinitely. (Reasoning invariant over the loop-exhaustion branch, not a pure-seam matrix case — `shouldRefetch` does not see `repeat`.)
- **INV-EXPL-28**: The plan feature `TREE_PACKAGE_GUARD` SHALL gate the entire guard block; with the flag false, event generation SHALL follow the pre-guard path (no refetch `continue`, no guard log lines). (Reasoning invariant over the flag placement — `shouldRefetch` has no flag parameter.)

#### Scenario: launcher tree under an in-package MainActivity is re-fetched
- **WHEN** the app under test is `com.example.app`, `topComp` reports `com.example.app/.MainActivity` but the accessibility root's `getPackageName()` is `com.google.android.apps.nexuslauncher` (a relaunch frame not yet painted), the guard is enabled, and at least one refetch iteration remains
- **THEN** `updateState` SHALL NOT be called on this pair, the loop SHALL `continue` to re-fetch both `topComp` and `info`, and (on first occurrence) the trace SHALL contain `[APE-RV] Tree/package mismatch: top=com.example.app tree=com.google.android.apps.nexuslauncher -> refetch`

#### Scenario: whitelisted permission dialog tree is not a mismatch
- **WHEN** `topComp` reports the app under test but the accessibility root's `getPackageName()` is `com.google.android.permissioncontroller` or `com.android.permissioncontroller` (a runtime-permission dialog legitimately owning the tree over the app)
- **THEN** `shouldRefetch` SHALL return false, the guard SHALL NOT re-fetch, and `updateState` SHALL model the pair normally

#### Scenario: persistent mismatch fails open on exhaustion
- **WHEN** the tree package differs from `topComp`'s package on every refetch iteration and the last iteration is reached (`repeat == 0`)
- **THEN** the guard SHALL NOT `continue` past loop exhaustion, and the pair SHALL be modeled via `updateState` exactly as the pre-guard behavior (fail-open — capture is not deadlocked)

#### Scenario: guard disabled
- **WHEN** the plan does not carry `TREE_PACKAGE_GUARD` (the `base` cell, or `ape.treePackageGuard=false` with ADDONS on) and a `(topComp, tree)` pair with a foreign tree reaches `generateEvents`
- **THEN** the pair SHALL be modeled exactly as before this change (unchecked pairing), with no refetch `continue` and no guard log line
