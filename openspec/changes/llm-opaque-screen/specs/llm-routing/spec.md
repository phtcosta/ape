## Purpose

This delta extends LLM routing to the screens the accessibility tree does not describe. On a game canvas, a camera preview or any custom-drawn surface the abstract state offers no widget action — only `MODEL_BACK` and `MODEL_MENU` — and the shared precondition `LlmGate.allows` has so far required more than two actions, so the LLM was never consulted there. Yet the LLM is the one decision-maker that sees the screen (it receives the screenshot), and the off-tree coordinate tap `MODEL_LLM_TAP` is the one action that needs no widget. On the Study 03 E5 campaign this closed the gate on 100 % of the steps of two LibGDX games (12 of 360 tasks with zero LLM calls), making the LLM arm identical to the arm without LLM on those applications.

The change introduces one run-time fact, the **opaque step** — the step's state offers at least one action and none of them requires a target — and gives the formerly unread `ape.llmPercentageNoSubstrate` its consumer. The key stays where the plan already declares it, a sub-parameter of `LLM_RANDOM` with neutral value `-1`; no feature is added. **Opaque routing is on** when the plan carries `LLM_RANDOM` and the key is `>= 0`. With it on, the shared precondition also holds on opaque steps, for all three LLM stages, and `LlmRandom` uses the key's value as its rate there. With it off — the `-1` every existing arm pushes — the precondition, the stages, the agent generator's draw sequence, and the plan itself (effective parameters, `features`, `digest`) are exactly those of jar `e93dea86`. Keeping the key's owner is what makes the last clause hold: `RunSpec.planValues` includes the keys of every active feature, so on every LLM arm `ape.llmPercentageNoSubstrate=-1` is already inside the plan digest; moving it to a new feature absent at `-1` would drop it from the digest of every LLM arm even with the mechanism off.

Opaque is not dynamic, and the gate needs both. Measured on campaign E5c, most opaque steps are screens the model cannot act on — stuck progress dialogs, text dialogs, an ad activity, camera previews that take no touch, Compose splash screens — and only full-screen drawn surfaces (the LibGDX games) are content the model can play. The tree APE reads separates them: a drawn surface that takes touch reaches it as a large, label-less, focusable or clickable leaf of the generic class `android.view.View`, because no surface class overrides `getAccessibilityClassName`; a dialog is TextViews and a ProgressBar, a listener-less camera surface is absent, a Compose splash is a chain of non-focusable views under `ComposeView`. The gate therefore opens, with opaque routing on, only on an **opaque dynamic step**: an opaque step whose tree holds such a **dynamic region**. Opaque steps without one stay with SATA exactly as with the key at `-1`.

Downstream of the gate one rule changes, and only on opaque dynamic steps. With no widget on the screen, `CoordinateMapper.map` finds no containment and no snap candidate and synthesizes an `LlmTapAction` for a `click`/`long_click` answer — the mechanism already exercised on widget-rich screens (`llm_tap` is 11.3 % of E5's calls). The prompt already renders an empty element list as `(no elements)`. The dead-pair ban already keys a tap on its exact coordinate. What does change is the boundary bands: they are measured against the display size `Display.getSize()` reports, which already excludes the navigation bar, and the screenshot the model sees is cropped to that frame, so the bottom band keeps the model off no system bar — it removes the bottom 6 % of the app's own content. On an opaque dynamic step, with opaque routing on, the bands are not applied and an answer with either coordinate at `0` — the parser's default for a coordinate it could not read, which the top band used to catch — is rejected explicitly (INV-RTR-27). A device check found shatteredpixeldungeon's title-screen button, the screen's only way forward, wholly inside the bottom band.

Three consequences are stated rather than corrected here. The boundary bands of `CoordinateMapper.map` (`ape.llmBoundaryTopPct` 0.05, `ape.llmBoundaryBottomPct` 0.94) still reject a coordinate in the top 5 % or bottom 6 % of the frame on every step that is not opaque dynamic — ordinary screens lose bottom-navigation and footer buttons to them — and measuring them against the system bars actually present, on every step, would change every LLM arm already measured, so it is left to a follow-up; the `llm[]` sub-events carry `reason:"boundary"`, so the loss is countable per run. On an opaque screen the abstract state does not change when the canvas does, so `LlmNewState` fires at most once per opaque state and every tap is recorded as unproductive; and each tap at a new coordinate is a new ephemeral action whose `NEW_ACTION` edge resets `graphStableCounter`, delaying SATA's forced restart. The unchanging state would need a pixel-based state signal, which changes the abstraction; the counter reset is left as it is on widget screens and made measurable by the `restarts` counter (event-sink).

## Invariants

- **INV-RTR-21**: A step is **opaque** exactly when its state's `getActions()` is non-empty and no element of it returns `requireTarget() == true`. (Non-emptiness matches `CoordinateMapper.map`, which returns null for an empty action list before the tap path; a `State` always constructs `MODEL_BACK`, so the case is defensive, but a call whose answer is discarded by construction must not be made.) The predicate SHALL read the abstract state only — no widget class name, no static-analysis datum, no screenshot — and SHALL be a pure static method of `LlmGate` so the gate and the telemetry evaluate the same definition.
- **INV-RTR-22**: When opaque routing is off (the plan lacks `LLM_RANDOM`, or `ape.llmPercentageNoSubstrate` is `-1`), `LlmGate.allows` SHALL return exactly `actionBufferSize() == 0 && getActions().size() > 2`, and every LLM stage SHALL consume the agent generator's draws at exactly the points it did before this change: for the same seed and fixtures, the sequence of `nextDouble()` draws SHALL be identical to the pre-change build.
- **INV-RTR-23**: When opaque routing is on, `LlmGate.allows` SHALL return `actionBufferSize() == 0 && (getActions().size() > 2 || (opaque && dynamicRegion))`, where `dynamicRegion` is INV-RTR-26 evaluated on the step's `GUITree`. An opaque step without a dynamic region SHALL be closed exactly as with opaque routing off. A non-empty action buffer SHALL close the gate on an opaque dynamic step as on any other.
- **INV-RTR-24**: `LlmRandom` SHALL draw a coin on a step only when the gate allows it and the step's rate is strictly positive; the step's rate is the `ape.llmPercentageNoSubstrate` value on an opaque dynamic step (INV-RTR-23) with opaque routing on, and `ape.llmPercentage` otherwise.
- **INV-RTR-25**: `ape.llmPercentageNoSubstrate` SHALL remain a sub-parameter of `LLM_RANDOM` with neutral value `-1`, so that for any key set with the value `-1` the resolved `features`, effective parameters and plan `digest` equal those jar `e93dea86` resolves from the same keys. The dynamic-region threshold SHALL NOT be a plan key.
- **INV-RTR-26**: A `GUITree` has a **dynamic region** exactly when some node, not inside a subtree rooted at a node of class `androidx.compose.ui.platform.ComposeView` or `android.webkit.WebView`, has class `android.view.View`, no children, empty text and empty content description, is focusable, natively clickable (`isClickable()` and not `isPatchedClickable()` — clickability `GUITreeBuilder.patchGUITree` copied from a clickable container does not count) or long-clickable, and the intersection of its screen bounds with the root node's covers at least `0.5` of the root's area. A null tree, or a root with empty bounds, has none. The predicate SHALL be a pure static method of `LlmGate`, shared by the gate, `LlmRandom`'s rate and the telemetry (event-sink INV-SNK-17), and SHALL NOT match on any class name other than the four named here.
- **INV-RTR-27**: On a step where opaque routing is on and the step is opaque dynamic (INV-RTR-21 ∧ INV-RTR-26), the LLM stage that calls the engine SHALL pass `edgeBandsOff = true` through `LlmEngine.selectAction` to `CoordinateMapper.map`, which SHALL then not apply the `ape.llmBoundaryTopPct`/`ape.llmBoundaryBottomPct` bands and SHALL return null for a pixel with `pixelX == 0` or `pixelY == 0`, which `LlmEngine` SHALL record as `result:"no_match"`, `reason:"degenerate"`; every other rule of `map` SHALL be unchanged. On every other step — and on every step when opaque routing is off — `edgeBandsOff` SHALL be `false` and `map` SHALL behave exactly as before this change. The decision SHALL NOT be a plan key and SHALL NOT read the tree when opaque routing is off.

## MODIFIED Requirements

### Requirement: New-State LLM Mode

When the plan enables the LLM new-state mode, the `LlmNewState` stage SHALL be assembled into the decision pipeline ahead of the stagnation, random, launcher, trigger, and SATA stages. Its `decide()` SHALL: (1) check the shared LLM precondition through the single `LlmGate` helper (the precondition exists in exactly one place; the pre-change triplication is deleted) — action buffer empty AND either the state has more than 2 actions or, when opaque routing is on, the step is opaque and its tree has a dynamic region (INV-RTR-21/22/23/26); (2) check the new-state trigger — `_isNewState` captured before `markVisited()` (unchanged capture semantics) — and the `LlmClient.allows()` breaker gate; (3) when all hold, invoke `LlmEngine.selectAction(..., "new-state", step)`; (4) on a non-null result, stamp `DecisionSource.LLM`/`PickChannel.LLM` (and resolve a synthesized `MODEL_LLM_TAP` against the state, unchanged) and return `Select`; otherwise return `Continue`.

The stage SHALL run after `adjustActionsByGUITree()` has assigned priorities (unchanged, INV-EXPL-11) and before any SATA rung.

Whether opaque routing is on SHALL be injected into the stage at assembly (INV-DP-12); the stage SHALL NOT read `Config` or the `RunSpec` in `decide()`.

#### Scenario: First visit to new state with LLM enabled
- **WHEN** the pipeline reaches `LlmNewState` on a first-visit state with buffer empty and 3+ actions
- **AND** the breaker allows and `LlmEngine.selectAction(...)` returns a non-null `ModelAction`
- **THEN** the stage SHALL return `Select` with `decision_source=LLM` (later stages not evaluated)

#### Scenario: First visit but circuit breaker open
- **WHEN** the state is new but `LlmClient.allows()` returns false
- **THEN** the stage SHALL return `Continue` and no HTTP call SHALL be made

#### Scenario: Revisit of known state
- **WHEN** `_isNewState` is `false`
- **THEN** the stage SHALL return `Continue` regardless of other conditions

#### Scenario: LLM returns null on new state
- **WHEN** `LlmEngine.selectAction(...)` returns `null`
- **THEN** the stage SHALL return `Continue` and the remaining pipeline SHALL decide the step

#### Scenario: precondition evaluated in one place
- **WHEN** the three LLM stages evaluate their preconditions on a step
- **THEN** all three SHALL consult the same `LlmGate` helper (buffer-empty ∧ (actions > 2 ∨ (opaque-routing ∧ opaque ∧ dynamic-region)))
- **AND** no stage SHALL carry its own copy of the precondition expression

#### Scenario: first visit to an opaque dynamic state with opaque routing on
- **WHEN** opaque routing is on (`ape.llmPercentage=0.3`, `ape.llmPercentageNoSubstrate=0.3`) and the pipeline reaches `LlmNewState` on a first-visit state whose actions are `[MODEL_BACK, MODEL_MENU]` and whose tree is a root with one full-root, focusable, label-less `android.view.View` leaf, with the buffer empty and the breaker allowing
- **AND** the model answers `click` at a point no widget contains
- **THEN** the stage SHALL invoke the engine with mode `"new-state"` and return `Select` with the `LlmTapAction` the engine synthesized, resolved against the state

#### Scenario: first visit to an opaque state without a dynamic region
- **WHEN** opaque routing is on and the first-visit state's actions are `[MODEL_BACK, MODEL_MENU]`, but its tree is a dialog of TextViews and a `ProgressBar`
- **THEN** the stage SHALL return `Continue` without calling the engine

#### Scenario: first visit to an opaque state with opaque routing off
- **WHEN** opaque routing is off (`ape.llmPercentageNoSubstrate=-1`) and the state's actions are `[MODEL_BACK, MODEL_MENU]`
- **THEN** the stage SHALL return `Continue` without calling the engine, exactly as before this change

---

### Requirement: Probabilistic LLM Routing

When the plan enables probabilistic routing (`llm.percentage > 0`), the `LlmRandom` stage SHALL be assembled after `LlmStagnation` and before `MopLauncher`. Its trigger SHALL be `random.nextDouble() < rate`, evaluated only after the shared `LlmGate` precondition holds and only when `rate > 0`, followed by the `LlmClient.allows()` breaker gate — the same conjunct order and short-circuiting as before the restructuring, so the seeded draw sequence is unchanged (`decision-pipeline` INV-DP-10). When `llm.percentage` is `0.0` the stage SHALL NOT exist (no draw is ever consumed — identical to the pre-change short-circuit).

**The step's rate.** With opaque routing off the rate is `llm.percentage` on every step, and since that rate is the stage's positive assembly condition the `rate > 0` conjunct is always true — which is what keeps the draw sequence of such a plan identical to the pre-change build (INV-RTR-22). With opaque routing on the rate is the `ape.llmPercentageNoSubstrate` value on an opaque step and `llm.percentage` on every other step (INV-RTR-24). A zero opaque rate draws no coin on opaque steps: that value means the new-state and stagnation stages may consult the LLM on opaque screens while random routing does not. Both rates SHALL be injected at assembly (INV-DP-12).

**Which stream the coin comes from, stated because the run has two.** The draw SHALL come from the **agent's** generator — `ape.getRandom()`, Monkey's `mRandom`, which is what the pre-decomposition router was constructed with — reached through the stage's collaborator. It SHALL NOT come from `RunContext.rng()`. The two are seeded from one number but they are **different `Random` instances**: `RunContext`'s constructor seeds `RandomHelper` from the same value Monkey's own generator was built from, and `SataAgent` draws from both. Moving the coin from one to the other would therefore shift every later `RandomHelper` draw in an LLM arm — a real change in what a device does, and one the parity goldens cannot see, because the oracle's scripted LLM replaces the coin outright (INV-ORA-03). Behavior-neutrality here is a claim about the draw *sequence*, not only about the seed.

The redundant `percentage > 0` conjunct SHALL be dropped when the trigger moves into the stage: it is the stage's own assembly condition (INV-DP-03), so inside the stage it is necessarily true, and it was already the predicate's first conjunct — a zero rate drew no coin before and assembles no stage now, which is what makes the deletion draw-neutral.

When the stage fires and the engine returns a non-null action, the telemetry mode label SHALL be `"random"`.

#### Scenario: Default 2% routing
- **WHEN** `llm.percentage` is `0.02`, no earlier stage selected this step, the precondition holds, `random.nextDouble()` returns a value < 0.02, and the breaker allows
- **THEN** the stage SHALL invoke the engine with mode `"random"`

#### Scenario: Disabled
- **WHEN** `llm.percentage` is `0.0`
- **THEN** the `LlmRandom` stage SHALL NOT be assembled and no coin SHALL be drawn on any step

#### Scenario: Priority order preserved
- **WHEN** `_isNewState` is `true` and the new-state mode is enabled, with `llm.percentage = 0.7`
- **THEN** the `LlmNewState` stage SHALL decide the step (hard preemption)
- **AND** at most one LLM call SHALL be made for that step

#### Scenario: draw order preserved under the same seed
- **WHEN** two builds (pre-change and post-change) run the same preset, seed, and fixtures with `ape.llmPercentageNoSubstrate=-1`, over a fixture sequence that includes states whose actions are `[MODEL_BACK, MODEL_MENU]`
- **THEN** the sequence of `nextDouble()` draws consumed by probabilistic routing SHALL be identical

#### Scenario: High percentage (70%)
- **WHEN** `llm.percentage` is `0.7`, neither the new-state nor the stagnation stage decided the step, and the precondition holds
- **THEN** the `LlmRandom` stage's trigger SHALL hold on approximately 70 % of the steps that reach it
- **AND** the rate SHALL be the plan's value applied to a single draw, never a per-stage rescaling: the stage is assembled with `percentage` and evaluates `nextDouble() < percentage` once per reached step

#### Scenario: opaque dynamic step uses the opaque rate
- **WHEN** the plan carries `llm.percentage = 0.3` and `ape.llmPercentageNoSubstrate = 0.9`, the buffer is empty, the step's state offers `[MODEL_BACK, MODEL_MENU]` and its tree has a dynamic region
- **THEN** the stage SHALL draw one coin and fire when it is below `0.9`
- **AND** on the next step, whose state offers three widget actions, it SHALL draw one coin and fire when it is below `0.3`

#### Scenario: zero opaque rate draws no coin
- **WHEN** the plan carries `llm.percentage = 0.3` and `ape.llmPercentageNoSubstrate = 0`, and the step is opaque with the buffer empty
- **THEN** the stage SHALL return `Continue` without drawing from the agent generator

#### Scenario: opaque step without a dynamic region draws no coin
- **WHEN** the plan carries `llm.percentage = 0.3` and `ape.llmPercentageNoSubstrate = 0.9`, and the step is opaque with no dynamic region in its tree
- **THEN** the gate SHALL be closed and the stage SHALL return `Continue` without drawing from the agent generator

### Requirement: Coordinate-to-ModelAction Mapping

`CoordinateMapper.map(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions, State state, int deviceWidth, int deviceHeight, boolean edgeBandsOff)` SHALL map LLM output coordinates to a `ModelAction` for the current state, returning a matched widget action, a synthesized off-tree `LlmTapAction`, or null.

**Boundary reject**: With `edgeBandsOff == false`, before coordinate matching, if `pixelY < deviceHeight * ape.llmBoundaryTopPct` (default `0.05` — status bar, and any degenerate `(0,0)` emission) or `pixelY > deviceHeight * ape.llmBoundaryBottomPct` (default `0.94`), `map` SHALL return null and log the boundary reject, and no off-tree tap is synthesized for these coordinates. The band fractions are plan parameters (J1b) with defaults reproducing the previous hard-coded `0.05`/`0.94` (INV-RTR-14). `deviceHeight` is the display size `Display.getSize()` reports — the area available to the app, without the navigation bar — and the model's screenshot is cropped to it, so the bottom band keeps no tap off the navigation bar; it removes the bottom 6 % of the app's content.

**Opaque dynamic steps**: With `edgeBandsOff == true` — passed only on a step where opaque routing is on and the step is opaque dynamic (INV-RTR-27) — `map` SHALL NOT apply either band, and SHALL return null when `pixelX == 0` or `pixelY == 0`; the caller classifies that null as `degenerate`. `ToolCallParser` reads each coordinate with a default of `0` — a `click` whose `x` or `y` is missing or not a number (an empty `arguments`, a `coordinate` key, a nested object) parses as a coordinate `0` on that axis — so a zero on either axis means "no coordinate", not a point the model chose; with the bands in place the top band caught such answers, and without them the zero must be rejected by name. Every other rule below is unchanged.

**ActionType filter (both matching passes)**: the tool the model called SHALL constrain the `ActionType` of the matched action, in the containment pass AND the Euclidean fallback:
- `"click"` SHALL match only `MODEL_CLICK` actions (measured defect this closes: a `click` answer executed CLICK only 80.9% of the time — the rest matched long-clicks, scrolls, or other types sharing the widget's bounds);
- `"long_click"` SHALL prefer `MODEL_LONG_CLICK`; when no `MODEL_LONG_CLICK` matches, `MODEL_CLICK` on the same coordinates MAY be returned as fallback (unchanged);
- `"type_text"` SHALL consider only actions targeting input-capable widgets (EditText, SearchView, AutoCompleteTextView) (unchanged).

**Special action types**:
- If `actionType` equals `"back"`, the state's `backAction` SHALL be returned directly without coordinate matching.
- If `actionType` equals `"type_text"` and a match is found, the caller SHALL call `action.getResolvedNode().setInputText(text)` to inject the LLM-provided text into APE's existing input event generation pipeline.

**fixTextEdit (input-widget click conversion)**: when `actionType` is `"click"` or `"long_click"` and the matched widget (containment or snap) is input-capable (EditText, SearchView, AutoCompleteTextView), `map` SHALL NOT return the bare click. It SHALL convert the decision into a text-entry action on that widget: the target comes from the LLM coordinate (the *where*), and the text is generated by APE's existing typed-input generation path — the same generator a SATA-selected input action uses (the *what*); no second LLM call is made. This removes the bare click on input widgets from the LLM's effective action space (banning by subtraction — the mechanism that outperforms prompt instruction), attacking the measured `type_text≈0` collapse. EditText is the widget class with the model's best grounding (93.1%), so the *where* is trustworthy; only the *what* was missing. The input-capable set used here is the same one that exempts these widgets from the dead-pair ban ("Deterministic Dead-Pair Ban") — a decision that resolves to an input widget is transformed rather than refused, and is never withdrawn from the action space by repetition.

**Bounds containment (primary matching strategy)**: For each action passing the ActionType filter where `action.requireTarget() == true` AND `action.isValid() == true` AND `action.getResolvedNode() != null`, check if `(pixelX, pixelY)` falls within the node's `getBoundsInScreen()` rectangle. If exactly one action's bounds contain the point, return that action. If multiple actions' bounds contain the point, return the one with the smallest area (most specific widget).

**Edge-based distance (fallback matching)**: If no action's bounds contain the point, compute the **point-to-rectangle distance** from `(pixelX, pixelY)` to each candidate's resolved bounds: `dx = max(bounds.left − pixelX, 0, pixelX − bounds.right)`, `dy = max(bounds.top − pixelY, 0, pixelY − bounds.bottom)`, `dist = hypot(dx, dy)` (zero when the point is inside). Return the action with the minimum distance if that distance is within the proportional tolerance `max(Config.llmSnapTolerancePx, min(nodeWidth, nodeHeight) / 2)` pixels (floor default `50`, configuration-exposed per J1b). This replaces centre-distance, whose geometry punished elongated widgets: on a 1080×150 bar, only points within ~75 px of the **centre** could snap, leaving ~450 px of the bar's own edge unsnappable — a tap 20 px outside a wide widget failed while being visually on target. The `ape.llmSnapTolerancePx` default is unchanged; raising it (to ~150) is an rv-android configuration decision gated on the dead-pair ban.

**Off-tree coordinate tap (dynamic element)**: If no action's bounds contain the point AND no action is within edge-distance tolerance AND `actionType` is `"click"` or `"long_click"`, `map` SHALL return `new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType))` — a targetless `MODEL_LLM_TAP` action carrying the LLM coordinate. Because the boundary reject (or, with `edgeBandsOff`, the `(0, 0)` rejection) runs first and the normalizer clamps every answer into the frame, a coordinate reaching this point is guaranteed in-bounds and non-degenerate. For any other `actionType` (e.g. `type_text`), `map` SHALL return null. This is the mechanism by which APE acts on elements invisible to UIAutomator (game canvas, custom view, Compose-without-semantics).

#### Scenario: LLM says "back"

- **WHEN** `map(0, 0, "back", null, actions)` is called
- **THEN** `state.getBackAction()` SHALL be returned
- **AND** no coordinate matching SHALL be performed

#### Scenario: click answer only matches MODEL_CLICK

- **WHEN** `map(200, 230, "click", null, actions)` is called
- **AND** the only action whose bounds contain the point is a `MODEL_LONG_CLICK`
- **THEN** that action SHALL NOT be returned by the containment pass
- **AND** matching SHALL proceed to the edge-distance fallback over `MODEL_CLICK` candidates (or off-tree synthesis)

#### Scenario: Click coordinates inside button bounds

- **WHEN** `map(200, 230, "click", null, actions)` is called
- **AND** MODEL_CLICK action A has resolved node bounds `[100, 200, 300, 250]` (contains point)
- **AND** MODEL_CLICK action B has resolved node bounds `[0, 0, 480, 800]` (also contains point, but larger)
- **THEN** action A SHALL be returned (smallest area containing the point)

#### Scenario: edge snap on an elongated bar

- **WHEN** `map(540, 180, "click", null, actions)` is called
- **AND** a MODEL_CLICK bar has bounds `[0, 200, 1080, 350]`, whose centre is `(540, 275)` — the point is 20 px above the bar's top edge but 95 px from its centre
- **THEN** the point-to-rectangle distance SHALL be 20 (dx=0, dy=20)
- **AND** with tolerance `max(50, 75) = 75` the bar SHALL be snapped and returned
- **AND** the retired centre-distance rule would have rejected it (95 > 75), which is the regression this locks

#### Scenario: click on an EditText becomes text entry

- **WHEN** `map(225, 325, "click", null, actions)` is called
- **AND** the containing widget is an EditText
- **THEN** the bare click SHALL NOT be returned
- **AND** the decision SHALL become a text-entry action on that EditText whose text comes from APE's typed-input generation path

#### Scenario: type_text targets EditText

- **WHEN** `map(225, 325, "type_text", "user@example.com", actions)` is called
- **AND** action C is an EditText at bounds `[50, 300, 400, 350]` (contains point)
- **THEN** action C SHALL be returned
- **AND** the caller SHALL call `action.getResolvedNode().setInputText("user@example.com")`

#### Scenario: long_click prefers MODEL_LONG_CLICK

- **WHEN** `map(200, 230, "long_click", null, actions)` is called
- **AND** the same widget offers MODEL_LONG_CLICK and MODEL_CLICK actions containing the point
- **THEN** the MODEL_LONG_CLICK action SHALL be returned

#### Scenario: Off-tree click builds an LlmTapAction

- **WHEN** `map(600, 900, "click", null, actions, state, 1080, 1794)` is called
- **AND** no MODEL_CLICK action contains the point and none is within edge-distance tolerance
- **THEN** `map` SHALL return a new `LlmTapAction` of type `MODEL_LLM_TAP` with `pixelX=600`, `pixelY=900`, `longClick=false`

#### Scenario: Off-tree type_text stays no_match

- **WHEN** `map(600, 900, "type_text", "hello", actions, state, 1080, 1794)` is called
- **AND** no input-capable widget contains or is near the point
- **THEN** `map` SHALL return null (no off-tree tap is synthesized for text input)

#### Scenario: Boundary reject — status bar

- **WHEN** `map(540, 50, "click", null, actions, state, 1080, 1920)` is called on a 1080x1920 device
- **AND** `pixelY (50) < deviceHeight * Config.llmBoundaryTopPct (96 at the 0.05 default)`
- **THEN** `map` SHALL return null
- **AND** no `LlmTapAction` SHALL be constructed

#### Scenario: Boundary reject — navigation bar

- **WHEN** `map(540, 1850, "click", null, actions)` is called on a 1080x1920 device
- **AND** `pixelY (1850) > deviceHeight * Config.llmBoundaryBottomPct (1804.8 at the 0.94 default)`
- **THEN** `map` SHALL return null

#### Scenario: bands lifted on an opaque dynamic step

- **WHEN** `map(540, 1740, "click", null, [MODEL_BACK, MODEL_MENU], state, 1080, 1794, true)` is called
- **AND** `pixelY (1740) > deviceHeight * ape.llmBoundaryBottomPct (1686.4 at the 0.94 default)`
- **THEN** `map` SHALL return a new `LlmTapAction` with `pixelX=540`, `pixelY=1740`
- **AND** WHEN the same call is made with `edgeBandsOff == false` it SHALL return null (boundary reject)

#### Scenario: degenerate answer with bands lifted

- **WHEN** `map(0, 0, …, true)`, `map(540, 0, …, true)` or `map(0, 900, …, true)` is called with actions `[MODEL_BACK, MODEL_MENU]` on a 1080×1794 frame
- **THEN** `map` SHALL return null
- **AND** no `LlmTapAction` SHALL be constructed

#### Scenario: Band configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmBoundaryBottomPct=0.98`
- **THEN** a `pixelY=1850` click on a 1080x1920 device SHALL pass the boundary check (1850 < 1881.6) and proceed to coordinate matching

## REMOVED Requirements

### Requirement: Config — llmPercentageNoSubstrate seam

**Reason**: The seam gets its consumer. `ape.llmPercentageNoSubstrate` is no longer an exposed-only value whose `-1` means "inherit `llmPercentage`": it stays a sub-parameter of `LLM_RANDOM` with neutral `-1`, now meaning "opaque routing off", and is read through the `RunSpec` (see "Opaque-Screen LLM Routing"). INV-RTR-09 ("no effect on any routing decision") is withdrawn with it. `Config.llmPercentageNoSubstrate` and `Config.clampLlmPercentageNoSubstrate` SHALL be deleted, together with the `ConfigTest` clamp cases and the `LlmRandomStageTest` no-consumer guard; the clamp behavior they tested (a real negative collapses to `-1`, a value above `1` clamps to `1`) is kept by `RunSpec`'s normalization and re-stated in the new requirement.

**Migration**: none needed on the harness side. Every existing arm pushes `-1`, which remains the neutral value and now means "mechanism off" — the behavior those arms already had, because nothing read the key.

## ADDED Requirements

### Requirement: Opaque-Screen LLM Routing

**Opaque routing** SHALL let the three LLM stages consult the model on **opaque dynamic** steps — steps whose state offers at least one action and none requiring a target (INV-RTR-21) and whose tree holds a dynamic region (INV-RTR-26) — which the shared precondition otherwise closes by size. It is on exactly when the plan carries `LLM_RANDOM` and `ape.llmPercentageNoSubstrate >= 0`; the key's value is the `LlmRandom` rate on opaque steps (INV-RTR-24). No `Feature` is added: the key remains a sub-parameter of `LLM_RANDOM` (INV-RTR-25).

**Key semantics.** `RunSpec` SHALL normalize the key as before: a real negative collapses to the sentinel `-1`, a value above `1` clamps to `1`. `-1` SHALL mean "opaque routing off" — neither the gate, the stages, the draw sequence nor the plan digest differ from jar `e93dea86` (INV-RTR-22, INV-RTR-25). A value in `[0, 1]` on a plan carrying `LLM_RANDOM` SHALL turn it on, and is visible in `RUN_START.params` (the key is inside the plan because its owner is active). The existing run-spec rules are sufficient and unchanged: on a plan without `LLM_RANDOM` the key is accepted only at `-1` (reported `inert`), and any other value aborts with `missing_dependency` naming `LLM_RANDOM` (INV-RUN-05). Consequently opaque routing requires `ape.llmPercentage > 0`; `ape.llmPercentageNoSubstrate=0` is the setting that opens the gate for the new-state and stagnation stages without random routing on opaque steps.

**The opaque predicate.** `LlmGate.isOpaque(State)` SHALL return `true` when `state.getActions()` is non-empty and none of its elements has `requireTarget() == true`. It does not identify the kind of surface: a LibGDX canvas, a camera preview, a progress dialog and a splash screen are all opaque.

**The dynamic-region predicate.** `LlmGate.hasDynamicRegion(GUITree)` SHALL implement INV-RTR-26 in one pass over the step's tree that does not descend into `ComposeView` or `WebView` subtrees. Each clause excludes a case measured on a device: the class `android.view.View` is what a surface or custom view reports (no surface class overrides `getAccessibilityClassName`, so a list of surface class names would match nothing APE sees), while widgets report their own class; no children and no label exclude text and progress dialogs; focusable, natively clickable or long-clickable holds for a surface with an input listener and fails for a listener-less camera surface, which is absent from the compressed tree anyway, for a label-less child that is clickable only because APE's tree patching copied its container's clickability, and for a Compose splash; half the root's area excludes icons and small views; the `ComposeView` ancestor excludes Compose semantics nodes, which also report `android.view.View`; the `WebView` ancestor excludes web content, including ad pages. It is not specific to games: any drawn surface that takes touch and fills the screen qualifies. The area threshold `0.5` is a constant of `LlmGate` (INV-RTR-25).

**What the LLM can do on an opaque dynamic step.** The engine SHALL be invoked as before, with `edgeBandsOff = true` (INV-RTR-27). `CoordinateMapper.map` SHALL return an `LlmTapAction` for a `click`/`long_click` answer anywhere in the frame except a pixel with either coordinate `0` (no widget contains or snaps to the point), the targetless `MODEL_BACK` for a `back` answer, and null for `type_text` — the existing rules of "Coordinate-to-ModelAction Mapping" apart from the bands.

**Why the bands are lifted there.** The frame `CoordinateMapper.map` measures against is `Display.getSize()` — the area available to the app, without the navigation bar — and the model's screenshot is cropped to it, with every answer clamped inside it. The navigation bar lies outside that frame, so the bottom band keeps no tap off it; on an opaque dynamic step it only hides the bottom of the drawn surface, where game controls sit. The top band's other job, catching the answers whose coordinates the parser could not read (it defaults them to `0`), is kept as an explicit check on either axis. What is given up is the top band's cover of the status bar in a game that does not hide it.

**Declared limitation — boundary bands elsewhere.** On every step that is not opaque dynamic, the bands (`ape.llmBoundaryTopPct` 0.05, `ape.llmBoundaryBottomPct` 0.94) SHALL apply unchanged: an answer in the top 5 % or bottom 6 % of the frame is rejected as `no_match`/`boundary` before a tap is built, including a bottom-navigation or footer button on an ordinary screen. The loss is countable from the trace as `reason:"boundary"` sub-events.

**Data Contracts**

- Input: `ape.llmPercentageNoSubstrate: double` from the plan (`-1` or `[0, 1]` after normalization); `StepContext.newState().getActions()`; `StepContext.newGUITree()` (may be null); `StepContext.actionBufferSize()`.
- Output: the gate's boolean; `LlmRandom`'s per-step rate; the `edgeBandsOff` argument to `LlmEngine.selectAction`; the `dec.opaque` and `dec.dyn` flags on the step record (event-sink).
- Side-effects: LLM calls and agent-generator draws on opaque dynamic steps, only when opaque routing is on.
- Error: none added; a non-neutral value on a plan without `LLM_RANDOM` aborts through the existing sub-parameter rule.

#### Scenario: game canvas consulted with opaque routing on
- **WHEN** the plan is `llm` with `ape.llmPercentage=0.7` and `ape.llmPercentageNoSubstrate=0.7`, and every state of the run is `AndroidLauncher@…[W=1][A=2]` with actions `[MODEL_BACK, MODEL_MENU]` and a tree whose root `FrameLayout` holds one leaf `android.view.View`, focusable, label-less, with the root's bounds (the retrowars dump)
- **THEN** `LlmNewState` SHALL be consulted on the first visit, `LlmStagnation` at each episode's midpoint, and `LlmRandom` on roughly 70 % of the remaining buffer-empty steps
- **AND** each `click` answer with both coordinates non-zero SHALL be dispatched as an `LlmTapAction` at the answered pixel, including one in the bottom 6 % of the frame, counted under `llm_tap`

#### Scenario: opaque screens without a dynamic region stay with SATA
- **WHEN** opaque routing is on and an opaque step's tree is one of: a non-cancelable progress dialog (`alertTitle`, message TextViews, a `ProgressBar`), a text-only dialog, zxing's capture screen (a root with only the `zxing_status_view` TextView), a Compose splash (non-focusable `android.view.View` chain under `ComposeView`)
- **THEN** `LlmGate.hasDynamicRegion` SHALL return `false`, no LLM stage SHALL call the engine and `LlmRandom` SHALL draw no coin on that step

#### Scenario: dynamic region rejected clause by clause
- **WHEN** a tree's only candidate leaf is `android.view.View`, focusable, label-less and covers the whole root, but it is inside a `WebView` subtree, or inside a `ComposeView` subtree, or has non-empty content description, or is neither focusable, clickable nor long-clickable, or is clickable only through `patchGUITree` (its container is clickable) and neither focusable nor long-clickable, or covers 49 % of the root
- **THEN** `LlmGate.hasDynamicRegion` SHALL return `false`
- **AND** WHEN the same leaf covers exactly 50 % of the root and none of those holds, it SHALL return `true`

#### Scenario: no tree
- **WHEN** the step context carries no `GUITree` (a unit fixture, the oracle)
- **THEN** `LlmGate.hasDynamicRegion` SHALL return `false` and an opaque step SHALL be closed as without a dynamic region

#### Scenario: opaque routing off preserves the pre-change run and plan
- **WHEN** the same run is repeated with `ape.llmPercentageNoSubstrate=-1` and the same seed
- **THEN** no LLM call SHALL be made on any `[MODEL_BACK, MODEL_MENU]` state
- **AND** the action sequence SHALL equal the pre-change build's, except on a step whose tap answer the parser now recovers through the extended last-resort scan (`llm-infrastructure`, marked `repair:"int_scan"`), which `e93dea86` parsed as `(0,0)` and discarded
- **AND** `RUN_START.features`, `RUN_START.params` and the plan `digest` SHALL equal those of jar `e93dea86` for the same keys

#### Scenario: value without random routing
- **WHEN** an LLM arm sets `ape.llmPercentage=0` and `ape.llmPercentageNoSubstrate=0.5`
- **THEN** resolution SHALL abort with `missing_dependency` naming `LLM_RANDOM` (existing sub-parameter rule, unchanged)

#### Scenario: opaque predicate ignores non-actionable widgets
- **WHEN** a state's tree has one widget that yields no model action, and its actions are `[MODEL_BACK, MODEL_MENU]`
- **THEN** `LlmGate.isOpaque` SHALL return `true`
- **AND** WHEN a state offers `[MODEL_CLICK(button), MODEL_BACK]` with `ape.modelMenuEnabled=false`
- **THEN** `LlmGate.isOpaque` SHALL return `false`, and the gate SHALL stay closed by size as before

#### Scenario: empty action list is not opaque
- **WHEN** a state's `getActions()` is empty
- **THEN** `LlmGate.isOpaque` SHALL return `false` and no LLM stage SHALL call the engine on it

#### Scenario: tap in the bottom band is accepted on an opaque dynamic step
- **WHEN** opaque routing is on, the step is opaque with a dynamic region, and the model answers `click` at `pixelY = 0.97 × deviceHeight` (shatteredpixeldungeon's "Enter the Dungeon" button) with no widget there
- **THEN** `CoordinateMapper.map` SHALL return an `LlmTapAction` at that pixel, counted under `llm_tap`, and the step record SHALL carry `dec.opaque:1` and `dec.dyn:1`

#### Scenario: degenerate answer rejected on an opaque dynamic step
- **WHEN** opaque routing is on, the step is opaque with a dynamic region, and the parsed answer is `click` at `(0, 0)`, or has one coordinate at `0` (a response whose `y` the parser could not read)
- **THEN** `CoordinateMapper.map` SHALL return null and the sub-event SHALL carry `result:"no_match"`, `reason:"degenerate"`

#### Scenario: bands kept everywhere else
- **WHEN** the model answers `click` at `pixelY = 0.97 × deviceHeight` on a step that is not opaque (a widget screen the gate opened by size), or on any step with opaque routing off
- **THEN** `LlmEngine.selectAction` SHALL receive `edgeBandsOff = false` and the engine SHALL return null with `result:"no_match"`, `reason:"boundary"`, as before this change

#### Scenario: buffered navigation still closes the gate
- **WHEN** opaque routing is on, the step is opaque with a dynamic region, and the action buffer holds two pending actions
- **THEN** all three LLM stages SHALL return `Continue` and the `SataChain` buffer rung SHALL decide
