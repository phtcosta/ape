## ADDED Requirements

### Requirement: Deterministic Dead-Pair Ban

`LlmRouter` SHALL maintain a per-run, in-memory record of **dead pairs** — LLM decisions that already executed in this run without producing a new state — and SHALL refuse to return a result that resolves to a dead pair. Measured motivation: 25.6% of LLM calls (10,081/39,341) re-emit an already-executed (state, coordinate) pair, and those repeats produced **0 new states in 10,081 attempts** (Wilson CI [0.00–0.04]); the anti-repetition prompt instruction exists and is ignored. Banning by subtraction (removing the option) is the externally validated mechanism (Guardian: 36% repetition persists under instruction); the detection is deterministic in the harness — the model is never asked to self-reflect.

**Ban keys** (the anchor is always the stable pair, NEVER a list index):
- an `llm_tap` result is keyed by `(state.getStateKey(), pixelX, pixelY)` — exact emitted-coordinate equality;
- a `matched` result is keyed by `(state.getStateKey(), widget stable id, eventType)`, where the widget stable id is **the action `Name`'s XPath** (`Name.toXPath()`) — the same widget identity used by `UICoverageTracker.widgetId` and by the MOP revisit cap. It is **not** a per-node identity: `GUITreeNode` exposes no XPath of its own, and a `Name` is an abstraction that may resolve to several nodes.

**Granularity of the `matched` key, stated because it bounds what a ban means.** Since the key is `Name`-level, **banning one pair withdraws that action from every node the `Name` resolves to in that state**. Measured on the calibration corpus: 16.3% of targeted steps (23,441 of 144,174) resolve more than one node. This is accepted rather than avoided — a node-derived alternative such as `(activity, className, resourceId, actionType)` is coarser still, colliding on at least 18.3% of anchors and on 36.3% of those whose `resourceId` is empty, which is 57.6% of clicks — but it constrains interpretation: a dead-pair count is a count of **abstract** pairs, and SHALL NOT be reported as a count of physical widgets withdrawn.

**Death rule (k=5, with input-capable widgets exempt):** a pair becomes dead after **five** executions whose recorded outcome has `new_state=false`. The threshold is uniform across every widget class the ban covers — there is no per-class *threshold* variation. An execution with `new_state=true` does not count toward death, and does not decrement or reset the accumulated count either: the counter only ever grows, and it counts unproductive executions of that exact pair.

**Input-capable targets are exempt at any strike count.** A `matched` pair whose resolved target is input-capable SHALL never become dead, however many unproductive executions it accumulates. Input-capable means the widget class is one of `android.widget.EditText`, `android.widget.AutoCompleteTextView`, `android.widget.SearchView`, `androidx.appcompat.widget.SearchView` — the set the prompt builder already uses to decide whether to offer `type_text` (`ApePromptBuilder.INPUT_CLASS_NAMES`). The ban SHALL NOT carry a second, independently-maintained list of input classes: one definition of "input-capable" serves the prompt, the fixTextEdit conversion, and this exemption.

The exemption is realized by **not recording strikes** for an input-capable target, rather than by filtering at the ban check. The consequences are deliberate and are what the scenarios assert: an exempt pair never enters the dead-pair record at all, so its ban key can never be consulted and found dead; and because the exemption keys on the *widget*, not the event type, it covers the text-entry action that the fixTextEdit conversion ("Coordinate Mapping") produces from a click on that same widget — the two mechanisms act on one widget set, not two.

**The exemption applies to `matched` results only, and this is a property of the evidence, not an oversight.** An `llm_tap` result is an off-tree tap with no matched widget: it carries `matched_class=none` in 1,033 of 1,033 corpus occurrences, so no widget class is knowable at its ban key. No class-based exemption of any design can reach the `llm_tap` half of the ban.

Why five, and what the exemption costs — measured on the 84 `cal_a1` runs of the calibration corpus (`experimento-cal/iter0`), which is the same 70% LLM configuration the decisive run's LLM arm uses.

**The sweep must be read on the keys the ban actually uses.** An earlier reading of this measurement keyed *both* result types by `(state, pixel)`, because the recorded trace does not carry the widget XPath. Only `llm_tap` uses that key, and `llm_tap` is 15.9% of the decision stream; the other 84.1% is `matched`, which ships with the looser `Name`-level key above and therefore bans more. Both columns below come from the same replay:

Every share below is computed against a denominator of **6,500 LLM decisions** — the reconstructed decision stream of the 84 `cal_a1` traces (80 main runs at `timeout=300` plus 4 smoke runs at `timeout=90`). Main-only the denominator is 6,440, which moves the k=5 share to 27.8% and leaves it inside the ceiling.

| k | refused, `(state,pixel)` key | share | **refused, shipped keys** | **share** | new states lost |
|---|---|---|---|---|---|
| 1 | 3,161 | 48.6% | 3,847 | **59.2%** | 44 → 80 |
| 2 | 2,283 | 35.1% | 2,975 | **45.8%** | 16 → 37 |
| 3 | 1,813 | 27.9% | 2,441 | **37.6%** | 8 → 24 |
| **5** | 1,305 | 20.1% | **1,788** | **27.5%** | 5 → **9** |
| 12 | 604 | 9.3% | 881 | 13.6% | 1 → 2 |

- The refused block is nearly unproductive at every threshold, so the death rule is not a close call on evidence; the choice of k is about how much of the arm's decision stream the ban is allowed to take over.
- k=1 maximizes raw net gain but refuses **59.2%** of the LLM's answers under the shipped keys. At that rate the LLM arm's behavior is substantially the SATA fallback's, and the arm stops being interpretable as "the LLM exploring" — which is the only thing the decisive run exists to measure. **The threshold is an experimental-validity choice, not only an optimization**, and the criterion it enforces is *refusal below 30%*. Under the shipped keys k=3 sits at 37.6% and fails that criterion; **k=5 sits at 27.5% and meets it**, at the cost of 9 new states lost instead of 8.
- **The table's shares are measured without the input-capable exemption, so 27.5% is now an upper bound.** Exempting a class can only remove pairs from the refused block, never add to it, so the shipped rule refuses *at most* 27.5% and the sub-30% criterion is met a fortiori. **The exact post-exemption share was not re-derived from the corpus** — the bound is what the criterion needs, and the sweep was not re-run for a number that could only move in the safe direction. A reader wanting the realized share must take it from the decisive run's own telemetry, where the `dead_pair` summary counter reports it directly.
- **k=5 raises the floor under every class the exception list named except `EditText`, which keeps its exemption by name.** The list this rule replaces would have granted `Switch`/`CheckBox`/`RadioButton` a threshold of k=2; k=5 grants them 5, so each is protected at least as well without a class test. Those classes are not rare — at k=1 they account for 81 of 2,586 banned pairs (3.1%) in the calibration corpus, with `Spinner` and `CheckedTextView` adding another 25 — so this is dominance by arithmetic, not by rarity. `EditText` is the one member the arithmetic does not cover: the list exempted it outright, and no finite k reproduces an exemption, which is why it survives as the input-capable carve-out above.
- The uniform threshold also reaches cases no class-name enumeration can: N-ary selection widgets (`Spinner`, where one coordinate opens many options — 10 banned pairs at k=1, dropping to 2 by k=3 and fewer still at k=5), Material/AppCompat subclasses whose simple names differ from the base classes (`SwitchCompat`, `MaterialCheckBox` — the corpus does show AndroidX simple names such as `LinearLayoutCompat`, `FloatingActionButton` and `CardView` reaching the tree, so the divergence is real), and Compose trees that expose no meaningful widget class name at all. The input-capable exemption is a class-name test and inherits exactly this limitation: an input widget whose class name is not one of the four is banned like any other.

**Scope limit (stated because it bounds what this mechanism can be credited with):** the ban check runs *after* `mapToModelAction` returns — the screenshot, the HTTP call and the parse have already completed. The ban changes which action executes; it does NOT reduce the LLM arm's per-step latency cost. In the calibration corpus that cost is 35% of a 300 s run's budget, and it is the dominant reason the LLM arm executes 0.622× the steps and discovers 0.729× the distinct states of the algorithmic arm on the same wall clock. This mechanism raises yield per decision; it does not address throughput, and it is not expected to close that gap on its own.

**Outcome feedback:** `StatefulAgent` SHALL report the outcome of each executed LLM-originated decision to the router at the point where `new_state` is computed for the `[APE-OUTCOME]` line, using the same single-shot buffered-decision discipline that guards `[APE-OUTCOME]` emission. Only LLM-originated decisions feed the ban record; SATA-selected actions are never banned.

**Ban check site and behavior:** in `selectAction()`, after `mapToModelAction` returns a `matched` or `llm_tap` result and before that result is returned, the router SHALL compute the result's ban key; when the key is dead, `selectAction()` SHALL return null — the SATA fallback, the same caller-visible path as `no_match`. The banned decision SHALL emit `[APE-LLM-TEL] result=no_match reason=dead_pair` and increment both `noMatchCount` and a `dead_pair` overlay counter reported on the summary line. The ban SHALL NOT call `breaker.recordFailure()` — the LLM pipeline succeeded; only its answer was refused — so a ban streak can never open the circuit breaker.

**Memory scope:** the ban record is per-run and in-memory only; no persistence, no cross-run state.

**Falsification gate (protocol, recorded here because it defines B1's success criterion):** bucket D (dead-pair repeats) MUST fall to ≈0 in the decisive-run telemetry BEFORE any new-state gain is credited to this mechanism; if bucket D ≈ 0 and new states do not rise, the ban is judged ineffective.

#### Scenario: repeated dead llm_tap is banned

- **WHEN** an `llm_tap` at `(500, 499)` on state S1 executed earlier in the run and its recorded outcome had `new_state=false`
- **AND** a later `selectAction()` on state S1 resolves to an `llm_tap` at the same `(500, 499)`
- **THEN** `selectAction()` SHALL return null (SATA fallback)
- **AND** the `[APE-LLM-TEL]` line SHALL carry `result=no_match reason=dead_pair`
- **AND** `breaker.recordFailure()` SHALL NOT be called for this decision

#### Scenario: pair survives four dead executions and dies on the fifth

- **WHEN** a `matched` click on Button "Help" (state S2) executes four times with `new_state=false`
- **THEN** the pair (S2, that action `Name`'s XPath, click) SHALL NOT yet be dead, and a fifth LLM answer resolving to it SHALL be returned normally
- **AND** after that fifth execution also records `new_state=false` the pair SHALL be dead
- **AND** the next LLM answer resolving to it SHALL be banned

#### Scenario: threshold is uniform across the widget classes the ban covers

- **WHEN** one pair targets a `Switch`, another a `Spinner`, and another a plain `Button`, and each executes five times with `new_state=false`
- **THEN** all three pairs SHALL be dead at the same threshold
- **AND** no covered widget class SHALL receive a threshold other than five

#### Scenario: an input-capable target is never dead

- **WHEN** a `matched` pair targets an `android.widget.EditText` and executes six times with `new_state=false` — one more than the threshold
- **THEN** the pair SHALL NOT be dead, and a subsequent LLM answer resolving to it SHALL be returned normally
- **AND** the pair SHALL NOT appear in the dead-pair record at all, because no strike was ever recorded for it
- **AND** the same SHALL hold for `android.widget.AutoCompleteTextView`, `android.widget.SearchView` and `androidx.appcompat.widget.SearchView`

#### Scenario: the exemption follows the widget through the fixTextEdit conversion

- **WHEN** an LLM `click` resolves to a `SearchView` and the fixTextEdit conversion ("Coordinate Mapping") turns it into a text-entry action on that widget
- **THEN** the resulting text-entry decision SHALL also be exempt, because the exemption keys on the target widget rather than on the event type
- **AND** repeated unproductive executions of it SHALL never produce `reason=dead_pair`

#### Scenario: an off-tree tap cannot be exempted

- **WHEN** an `llm_tap` result is banned after five unproductive executions at the same coordinate on the same state
- **THEN** the ban SHALL apply regardless of what is rendered at that coordinate
- **AND** no input-capable exemption SHALL be consulted, because an `llm_tap` has no matched widget and therefore no widget class

#### Scenario: a ban withdraws the action from every node the Name resolves to

- **WHEN** a `matched` pair dies whose action `Name` resolves to three nodes in that state
- **THEN** subsequent LLM answers resolving to that `Name` and event type SHALL be banned regardless of which of the three nodes the coordinate falls in
- **AND** the ban count SHALL be reported as one dead pair, never as three widgets withdrawn

#### Scenario: productive execution does not count toward death

- **WHEN** a pair executes with `new_state=false`, then with `new_state=true`, then with `new_state=false`
- **THEN** the pair's accumulated dead-execution count SHALL be 2 (the productive execution neither counts nor resets the count)
- **AND** the pair SHALL NOT yet be dead

#### Scenario: ban is per-run

- **WHEN** a new run starts on the same APK
- **THEN** the ban record SHALL be empty

## MODIFIED Requirements

### Requirement: LlmRouter Lifecycle

`LlmRouter` SHALL be constructed in `StatefulAgent`'s constructor when `Config.llmUrl` is non-null. The constructor SHALL create and wire all infrastructure components:
- `SglangClient` with `Config.llmUrl`, `Config.llmModel`, `Config.llmTemperature`, `Config.llmTopP`, `Config.llmTopK`, `Config.llmMaxTokens` (default `1024`), `Config.llmTimeoutMs`
- `LlmCircuitBreaker` with default thresholds (3 failures, 60s recovery)
- `ScreenshotCapture` (no-arg constructor)
- `ImageProcessor` (no-arg constructor)
- `ToolCallParser` (no-arg constructor)
- `CoordinateNormalizer` (static utility, no instantiation)
- `ApePromptBuilder` (no-arg constructor)

The `max_tokens` value SHALL be read once from `Config.llmMaxTokens` and shared by the request body and the `[APE-LLM-CONFIG]` manifest, so the manifest always reports the value actually sent.

**Per-request tool schema:** the constructor SHALL NOT install a single run-wide tools schema on the client. Instead, `LlmRouter` SHALL build two schema constants once at construction — one with and one without the `type_text` tool — and SHALL pass the appropriate one to each `chat()` invocation: the schema **with** `type_text` when the current screen's action list contains at least one input-capable widget (the same `hasInputField` predicate the system message already uses — `llm-prompt` "System Message", Dynamic tool schema), the schema **without** it otherwise. This closes the measured incoherence where the wire schema always advertised `type_text` while the system message conditionally omitted it — the model was offered a tool the prompt said did not exist.

All fields SHALL be final. The router instance SHALL be reused for the entire exploration session. A `totalCalls` field SHALL be initialized to 0 and incremented at the start of each `selectAction()` invocation (per INV-RTR-07).

#### Scenario: LLM URL configured

- **WHEN** `Config.llmUrl` equals `"http://10.0.2.2:30000/v1"`
- **THEN** `StatefulAgent` SHALL create a `LlmRouter` instance
- **AND** `_llmRouter` SHALL be non-null for the session

#### Scenario: LLM URL not configured

- **WHEN** `Config.llmUrl` is null
- **THEN** `StatefulAgent` SHALL set `_llmRouter` to null
- **AND** no LLM infrastructure objects SHALL be created

#### Scenario: max_tokens sourced from Config at default

- **WHEN** `ape.properties` does not contain `ape.llmMaxTokens`
- **THEN** the request body SHALL carry `max_tokens=1024`
- **AND** the `[APE-LLM-CONFIG]` line SHALL carry `max_tokens=1024`

#### Scenario: max_tokens configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmMaxTokens=2048`
- **THEN** the request body and the `[APE-LLM-CONFIG]` line SHALL both carry `max_tokens=2048`

#### Scenario: screen with an input field advertises type_text on the wire

- **WHEN** `selectAction()` runs on a screen whose action list contains an EditText
- **THEN** the request body's `tools` array SHALL contain `type_text`
- **AND** the system message of the same request SHALL list `type_text` (prompt/wire coherence)

#### Scenario: screen without input fields omits type_text on the wire

- **WHEN** `selectAction()` runs on a screen with no input-capable widget
- **THEN** the request body's `tools` array SHALL NOT contain `type_text`
- **AND** the system message of the same request SHALL NOT list `type_text`

---

### Requirement: Stagnation LLM Mode

When `Config.llmOnStagnation` is `true` and the current stagnation episode has reached its midpoint, the LLM router SHALL be consulted exactly once per stagnation episode to attempt breaking out of stagnation.

The trigger condition is: `graphStableCounter >= Config.graphStableRestartThreshold / 2` AND the hook has not yet fired in the current stagnation episode. A per-episode fired flag SHALL be set when the hook fires and SHALL re-arm (clear) when `graphStableCounter` resets to 0 (a new edge was observed — the existing reset in `StatefulAgent.onVisitStateTransition`). This replaces the previous exact-equality check (`graphStableCounter == threshold / 2`), which — because the counter resets on every new edge — was a 1-step window per episode that virtually never fired: any step in which the counter jumped past or was reset before the exact midpoint silently lost the episode's only chance.

The restart mechanism is unchanged: `StatefulAgent.onGraphStable()` still handles the restart at `counter > threshold`; the LLM hook does not modify it.

#### Scenario: LLM provides escape action at or past the stagnation midpoint

- **WHEN** `graphStableCounter` reaches `graphStableRestartThreshold / 2` (or any greater value with the episode flag still armed)
- **AND** `Config.llmOnStagnation` is `true`
- **AND** `_llmRouter` is non-null and the circuit breaker allows
- **THEN** `_llmRouter.selectAction(...)` SHALL be called and the episode's fired flag SHALL be set
- **AND** if the result is non-null, the action SHALL be used and `graphStableCounter` SHALL be reset to 0

#### Scenario: hook fires once per episode even when the LLM fails

- **WHEN** the hook fires at the midpoint and the LLM returns null
- **THEN** the counter continues incrementing and the hook SHALL NOT fire again in this episode (flag set)
- **AND** if `graphStableCounter` eventually reaches `graphStableRestartThreshold`, `requestRestart()` SHALL be called (existing behavior)

#### Scenario: new edge re-arms the episode

- **WHEN** the hook has fired, and a later step discovers a new edge (counter resets to 0)
- **AND** the graph then stagnates again up to the midpoint
- **THEN** the hook SHALL fire again (new episode)

#### Scenario: midpoint skipped by a counter jump still fires

- **WHEN** the counter passes from below the midpoint to above it without ever equaling `threshold / 2` at check time
- **THEN** the hook SHALL still fire at the first check where `graphStableCounter >= threshold / 2` (the `>=` closes the 1-step window defect)

#### Scenario: Stagnation mode disabled

- **WHEN** `Config.llmOnStagnation` is `false`
- **THEN** the LLM SHALL NOT be consulted regardless of counter value
- **AND** the existing restart behavior SHALL proceed unchanged

---

### Requirement: Coordinate-to-ModelAction Mapping

`LlmRouter.mapToModelAction(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions, State state, int deviceWidth, int deviceHeight)` SHALL map LLM output coordinates to a `ModelAction` for the current state, returning a matched widget action, a synthesized off-tree `LlmTapAction`, or null.

**Boundary reject**: Before coordinate matching, if `pixelY < deviceHeight * Config.llmBoundaryTopPct` (default `0.05` — status bar, and any degenerate `(0,0)` emission) or `pixelY > deviceHeight * Config.llmBoundaryBottomPct` (default `0.94` — navigation bar), `mapToModelAction` SHALL return null and log the boundary reject. This prevents the LLM from tapping system UI, and no off-tree tap is synthesized for these coordinates. The band fractions are configuration-exposed (J1b) with defaults reproducing the previous hard-coded `0.05`/`0.94` (INV-RTR-14).

**ActionType filter (both matching passes)**: the tool the model called SHALL constrain the `ActionType` of the matched action, in the containment pass AND the Euclidean fallback:
- `"click"` SHALL match only `MODEL_CLICK` actions (measured defect this closes: a `click` answer executed CLICK only 80.9% of the time — the rest matched long-clicks, scrolls, or other types sharing the widget's bounds);
- `"long_click"` SHALL prefer `MODEL_LONG_CLICK`; when no `MODEL_LONG_CLICK` matches, `MODEL_CLICK` on the same coordinates MAY be returned as fallback (unchanged);
- `"type_text"` SHALL consider only actions targeting input-capable widgets (EditText, SearchView, AutoCompleteTextView) (unchanged).

**Special action types**:
- If `actionType` equals `"back"`, the state's `backAction` SHALL be returned directly without coordinate matching.
- If `actionType` equals `"type_text"` and a match is found, the caller SHALL call `action.getResolvedNode().setInputText(text)` to inject the LLM-provided text into APE's existing input event generation pipeline.

**fixTextEdit (input-widget click conversion)**: when `actionType` is `"click"` or `"long_click"` and the matched widget (containment or snap) is input-capable (EditText, SearchView, AutoCompleteTextView), `mapToModelAction` SHALL NOT return the bare click. It SHALL convert the decision into a text-entry action on that widget: the target comes from the LLM coordinate (the *where*), and the text is generated by APE's existing typed-input generation path — the same generator a SATA-selected input action uses (the *what*); no second LLM call is made. This removes the bare click on input widgets from the LLM's effective action space (banning by subtraction — the mechanism that outperforms prompt instruction), attacking the measured `type_text≈0` collapse. EditText is the widget class with the model's best grounding (93.1%), so the *where* is trustworthy; only the *what* was missing. The input-capable set used here is the same one that exempts these widgets from the dead-pair ban ("Deterministic Dead-Pair Ban") — a decision that resolves to an input widget is transformed rather than refused, and is never withdrawn from the action space by repetition.

**Bounds containment (primary matching strategy)**: For each action passing the ActionType filter where `action.requireTarget() == true` AND `action.isValid() == true` AND `action.getResolvedNode() != null`, check if `(pixelX, pixelY)` falls within the node's `getBoundsInScreen()` rectangle. If exactly one action's bounds contain the point, return that action. If multiple actions' bounds contain the point, return the one with the smallest area (most specific widget).

**Edge-based distance (fallback matching)**: If no action's bounds contain the point, compute the **point-to-rectangle distance** from `(pixelX, pixelY)` to each candidate's resolved bounds: `dx = max(bounds.left − pixelX, 0, pixelX − bounds.right)`, `dy = max(bounds.top − pixelY, 0, pixelY − bounds.bottom)`, `dist = hypot(dx, dy)` (zero when the point is inside). Return the action with the minimum distance if that distance is within the proportional tolerance `max(Config.llmSnapTolerancePx, min(nodeWidth, nodeHeight) / 2)` pixels (floor default `50`, configuration-exposed per J1b). This replaces centre-distance, whose geometry punished elongated widgets: on a 1080×150 bar, only points within ~75 px of the **centre** could snap, leaving ~450 px of the bar's own edge unsnappable — a tap 20 px outside a wide widget failed while being visually on target. The `ape.llmSnapTolerancePx` default is unchanged; raising it (to ~150) is an rv-android configuration decision gated on the dead-pair ban.

**Off-tree coordinate tap (dynamic element)**: If no action's bounds contain the point AND no action is within edge-distance tolerance AND `actionType` is `"click"` or `"long_click"`, `mapToModelAction` SHALL return `new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType))` — a targetless `MODEL_LLM_TAP` action carrying the LLM coordinate. Because the boundary reject runs first, a coordinate reaching this point is guaranteed in-bounds and non-degenerate. For any other `actionType` (e.g. `type_text`), `mapToModelAction` SHALL return null. This is the mechanism by which APE acts on elements invisible to UIAutomator (game canvas, custom view, Compose-without-semantics).

#### Scenario: LLM says "back"

- **WHEN** `mapToModelAction(0, 0, "back", null, actions)` is called
- **THEN** `state.getBackAction()` SHALL be returned
- **AND** no coordinate matching SHALL be performed

#### Scenario: click answer only matches MODEL_CLICK

- **WHEN** `mapToModelAction(200, 230, "click", null, actions)` is called
- **AND** the only action whose bounds contain the point is a `MODEL_LONG_CLICK`
- **THEN** that action SHALL NOT be returned by the containment pass
- **AND** matching SHALL proceed to the edge-distance fallback over `MODEL_CLICK` candidates (or off-tree synthesis)

#### Scenario: Click coordinates inside button bounds

- **WHEN** `mapToModelAction(200, 230, "click", null, actions)` is called
- **AND** MODEL_CLICK action A has resolved node bounds `[100, 200, 300, 250]` (contains point)
- **AND** MODEL_CLICK action B has resolved node bounds `[0, 0, 480, 800]` (also contains point, but larger)
- **THEN** action A SHALL be returned (smallest area containing the point)

#### Scenario: edge snap on an elongated bar

- **WHEN** `mapToModelAction(540, 180, "click", null, actions)` is called
- **AND** a MODEL_CLICK bar has bounds `[0, 200, 1080, 350]`, whose centre is `(540, 275)` — the point is 20 px above the bar's top edge but 95 px from its centre
- **THEN** the point-to-rectangle distance SHALL be 20 (dx=0, dy=20)
- **AND** with tolerance `max(50, 75) = 75` the bar SHALL be snapped and returned
- **AND** the retired centre-distance rule would have rejected it (95 > 75), which is the regression this locks

#### Scenario: click on an EditText becomes text entry

- **WHEN** `mapToModelAction(225, 325, "click", null, actions)` is called
- **AND** the containing widget is an EditText
- **THEN** the bare click SHALL NOT be returned
- **AND** the decision SHALL become a text-entry action on that EditText whose text comes from APE's typed-input generation path

#### Scenario: type_text targets EditText

- **WHEN** `mapToModelAction(225, 325, "type_text", "user@example.com", actions)` is called
- **AND** action C is an EditText at bounds `[50, 300, 400, 350]` (contains point)
- **THEN** action C SHALL be returned
- **AND** the caller SHALL call `action.getResolvedNode().setInputText("user@example.com")`

#### Scenario: long_click prefers MODEL_LONG_CLICK

- **WHEN** `mapToModelAction(200, 230, "long_click", null, actions)` is called
- **AND** the same widget offers MODEL_LONG_CLICK and MODEL_CLICK actions containing the point
- **THEN** the MODEL_LONG_CLICK action SHALL be returned

#### Scenario: Off-tree click builds an LlmTapAction

- **WHEN** `mapToModelAction(600, 900, "click", null, actions, state, 1080, 1794)` is called
- **AND** no MODEL_CLICK action contains the point and none is within edge-distance tolerance
- **THEN** `mapToModelAction` SHALL return a new `LlmTapAction` of type `MODEL_LLM_TAP` with `pixelX=600`, `pixelY=900`, `longClick=false`

#### Scenario: Off-tree type_text stays no_match

- **WHEN** `mapToModelAction(600, 900, "type_text", "hello", actions, state, 1080, 1794)` is called
- **AND** no input-capable widget contains or is near the point
- **THEN** `mapToModelAction` SHALL return null (no off-tree tap is synthesized for text input)

#### Scenario: Boundary reject — status bar

- **WHEN** `mapToModelAction(540, 50, "click", null, actions, state, 1080, 1920)` is called on a 1080x1920 device
- **AND** `pixelY (50) < deviceHeight * Config.llmBoundaryTopPct (96 at the 0.05 default)`
- **THEN** `mapToModelAction` SHALL return null
- **AND** no `LlmTapAction` SHALL be constructed

#### Scenario: Boundary reject — navigation bar

- **WHEN** `mapToModelAction(540, 1850, "click", null, actions)` is called on a 1080x1920 device
- **AND** `pixelY (1850) > deviceHeight * Config.llmBoundaryBottomPct (1804.8 at the 0.94 default)`
- **THEN** `mapToModelAction` SHALL return null

#### Scenario: Band configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmBoundaryBottomPct=0.98`
- **THEN** a `pixelY=1850` click on a 1080x1920 device SHALL pass the boundary check (1850 < 1881.6) and proceed to coordinate matching

---

### Requirement: LLM Telemetry Logging

`LlmRouter` SHALL log structured per-decision telemetry on an `[APE-LLM-TEL]` line and an aggregate `[APE-RV] LLM Summary` line.

**Per-decision structured log** (parseable, follows the `[RVTRACK:LLM]` pattern from rvsmart/rvagent):

| Field | Values |
|-------|--------|
| `step` | agent exploration step of the routing attempt — the same value the step's `[APE-STEP]` line carries, supplied by the calling agent (`getTimestamp()` at selection) |
| `mode` | `new-state`, `stagnation`, `random` |
| `result` | `matched`, `llm_tap`, `no_match` |
| `reason` | emitted only on `no_match`, immediately after `result=`: `degenerate` (parsed coordinate `(0,0)`), `boundary` (status/nav band), or `dead_pair` (the mapped result resolved to a banned dead pair — "Deterministic Dead-Pair Ban") |
| `repair` | emitted only when the decision's `ParsedAction` carries a repair-form label other than `none` (per `llm-infrastructure` INV-LLM-09): `missing_y`, `array_xy`, `quoted_xy`, or `int_scan`. Absent on a clean parse. |
| `tokens_in/out` | From `ChatResponse.usage.prompt_tokens` / `completion_tokens` (0 if unavailable) |
| `time_ms` | Wall clock milliseconds for the full pipeline (screenshot → response) |

The `step` field makes the LLM call telemetry joinable to the decision and its outcome on one key: `[APE-LLM-TEL] step=N` ↔ `[APE-STEP] step=N` ↔ `[APE-OUTCOME] step=N` (the latter per the scoring-pipeline capability). The value is passed into `selectAction()` by the caller; `LlmRouter` does not read agent state itself.

| `result` | Meaning |
|----------|---------|
| `matched` | LLM coordinate resolved to a widget in the `GUITree` |
| `llm_tap` | LLM coordinate matched no widget; an off-tree `MODEL_LLM_TAP` was synthesized and dispatched |
| `no_match` | LLM coordinate was discarded; accompanied by `reason=degenerate`, `reason=boundary`, or `reason=dead_pair` |

The `repair` field is orthogonal to `result`: a repaired tool call yields a normal `matched`, `llm_tap`, or `no_match` outcome and additionally carries `repair=<form>`.

Routing attempts abandoned before the mapping step (null screenshot, image-processing failure, HTTP/timeout/connection failure, parse failure, unexpected internal error) do not emit an `[APE-LLM-TEL]` line; they are counted in the aggregate summary only. Each abandoned attempt SHALL emit one `[APE-LLM-ERROR]` line naming its cause — **including the screenshot-capture failure**: the previous silent-screenshot exception is removed, because the silence hid the dominant LLM-arm failure mode (147 screenshot failures concentrated in 4 FLAG_SECURE APKs, co-located with 100% of the 57 breaker trips — the breaker silently disabled the LLM with no attributable line in the trace). The screenshot failure's error line SHALL be `[APE-LLM-ERROR] step=<N> cause=screenshot activity=<current activity> detail=<stage>`, where `<stage>` is read from the `ScreenshotCapture` failure-cause seam (`llm-infrastructure` capability) and `<current activity>` names the foreground activity so per-app FLAG_SECURE degradation is countable offline. The existing `[APE-RV] LLM screenshot capture failed` line MAY remain alongside it.

**Discriminated failure causes:** the previously-collapsed `null` outcome SHALL be attributed to exactly one named cause. `LlmRouter` SHALL maintain a separate counter per cause in place of the single opaque `nullCount`:

| Cause | Meaning | Counter |
|-------|---------|---------|
| `timeout` | connect/read timeout (`SocketTimeoutException`) | `timeoutCount` |
| `http_<status>` | server returned a non-200 HTTP status | `httpErrorCount` |
| `connection` | other I/O failure reaching the server | `connErrorCount` |
| `parse` | response received but unusable: OpenAI envelope not parseable (client-side) or no tool call extractable from a successful response (router-side) | `parseErrorCount` |
| `image` | `ImageProcessor` returned null while preparing the screenshot payload | `imageErrorCount` |
| `internal` | unexpected exception caught by the `selectAction()` catch-all | `internalErrorCount` |
| `screenshot` | `ScreenshotCapture` returned null (e.g. FLAG_SECURE) | `screenshotFailedCount` (unchanged) |

A `parse` failure is the residual after `ToolCallParser` recovery: it is counted only when `parse()` returns null despite the quoted-collapsed-XY fix and last-resort integer extraction (per the `llm-infrastructure` capability). A recovered tool call is not a `parse` failure — it is a successful decision carrying `repair=<form>`.

**Cause attribution SHALL follow the failure point.** When `chat()` returns null, the cause SHALL be read from `SglangClient.getLastErrorCause()` — the only site where that seam MAY be consulted (the client resets it per invocation; at any other site its value belongs to an earlier call and is stale). Failures occurring after `chat()` returns non-null SHALL be attributed by `LlmRouter` directly, without consulting `getLastErrorCause()`: tool-call extraction failure (`ToolCallParser.parse()` returning null) is `parse`, an `ImageProcessor` null result is `image`, and an unexpected exception in the routing pipeline is `internal`. The screenshot cause is attributed at the capture site, with its stage detail read from the `ScreenshotCapture` seam.

At the failure point, `LlmRouter` SHALL emit `[APE-LLM-ERROR] step=<N> cause=<cause> detail=<message>`, where `step` is the same join key carried by `[APE-LLM-TEL]` — a failed attempt is thereby attributable to the step whose selection it interrupted. The `null` return contract (INV-RTR-02) is unchanged; only the attribution is added.

**Prompt-log parseability:** the `[APE-LLM-PROMPT]` lines (system/user_text dumps emitted by the router) SHALL be per-element parseable: widget-derived text interpolated into the prompt's element lines SHALL have `\n`/`\r` flattened to spaces by the prompt builder (the metadata path already does this via `capMeta`; the element-line display text gets the same treatment). The prompt's own intentional multi-line structure is unchanged.

**Additional event logs:**

| Event | Log Format |
|-------|-----------|
| Circuit breaker blocked | `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` |
| Pipeline step failed | `[APE-LLM-ERROR] step=<N> cause=<cause> detail=<message>` |

The circuit-breaker-blocked line SHALL be emitted at the **first** routing-predicate decline of each open episode (the moment a predicate declines a call because the breaker does not allow the attempt); subsequent declines within the same open episode SHALL NOT re-emit it. The emission check SHALL use a side-effect-free breaker state query (`isOpen()`), never a second `shouldAttempt()` call, and SHALL distinguish breaker-caused declines from predicates returning false for other reasons (mode disabled, coin flip, stagnation not reached).

**Aggregate summary** (printed at `StatefulAgent.tearDown()`):
```
[APE-RV] LLM Summary calls=<N> tokens_in=<N> tokens_out=<N> time_ms=<N> matched=<N> llm_tap=<N> no_match=<N> dead_pair=<N> repaired=<N> timeout=<N> http_error=<N> conn_error=<N> parse_error=<N> image_error=<N> internal_error=<N> screenshot_failed=<N> breaker_trips=<N>
[APE-RV] Decision ratio: LLM=<N>/<total> (<pct>%), SATA=<N>/<total> (<pct>%)
```

The `decisions` denominator for the `[APE-RV] LLM Decision ratio` line SHALL be `matched + llm_tap + no_match + (timeout + http_error + conn_error + parse_error + image_error + internal_error + screenshot_failed)`. The seven cause counters form a partition of the retired `null` count; `dead_pair` and `repaired` are overlay counters, NOT part of the denominator:

- `dead_pair=<N>` counts decisions banned by the dead-pair ban; each is already counted under `no_match`. It is maintained separately so bucket D — the falsification gate of the ban — is countable from the summary line alone.
- `repaired=<N>` (`repairedCount`) counts successful decisions whose tool call required a `ToolCallParser` repair (repair-form label other than `none`); each is already counted under exactly one of `matched` / `llm_tap` / `no_match`.

`llmTapCount` (`llm_tap=<N>`) counts synthesized off-tree taps. `screenshot_failed` counts the routing attempts abandoned because `ScreenshotCapture` returned null; it is a peer cause counter, maintained separately so per-app degradation of the LLM arm to SATA is countable post-hoc from the summary line alone.

#### Scenario: Matched widget logged

- **WHEN** `selectAction()` returns a widget `ModelAction` of type `MODEL_CLICK`
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `result=matched`

#### Scenario: LLM call telemetry joins step and outcome on one key

- **WHEN** the new-state hook routes to the LLM at step 42, the call succeeds and the mapped action executes producing a recorded transition
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `step=42`
- **AND** the `[APE-STEP]` and `[APE-OUTCOME]` lines of the same decision SHALL carry `step=42`

#### Scenario: dead-pair ban visible in TEL and summary

- **WHEN** two decisions in a run are banned by the dead-pair ban and one is discarded for a boundary coordinate
- **THEN** the two banned decisions' `[APE-LLM-TEL]` lines SHALL carry `result=no_match reason=dead_pair`
- **AND** the summary SHALL report `no_match=3 dead_pair=2`

#### Scenario: Screenshot failure emits an attributable error line

- **WHEN** `ScreenshotCapture.capture()` returns null on a FLAG_SECURE window while activity `org.fedorahosted.freeotp.MainActivity` is foreground
- **THEN** one `[APE-LLM-ERROR] step=<N> cause=screenshot activity=org.fedorahosted.freeotp.MainActivity detail=<stage>` line SHALL be emitted
- **AND** `screenshotFailedCount` SHALL be incremented
- **AND** `breaker.recordFailure()` SHALL be called (unchanged breaker semantics)

#### Scenario: Repaired tool call logged and counted

- **WHEN** at step 61 the model returns `{"name": "click", "arguments": {"x": "500, 527}}`, `ToolCallParser` recovers it via the quoted-collapsed-XY fix, and the resulting coordinate resolves to a widget
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `step=61 result=matched repair=quoted_xy`
- **AND** the aggregate summary SHALL count the decision under both `matched=<N>` and `repaired=<N>`

#### Scenario: no_match reason separated

- **WHEN** one decision is discarded for a `(0,0)` coordinate and another for a navigation-band coordinate
- **THEN** the first `[APE-LLM-TEL]` line SHALL carry `result=no_match reason=degenerate`
- **AND** the second SHALL carry `result=no_match reason=boundary`

#### Scenario: Timeout and HTTP failure discriminated

- **WHEN** one routing attempt fails because the read times out and a later attempt fails because the server returns HTTP 500
- **THEN** the first SHALL emit `[APE-LLM-ERROR] cause=timeout ...` and increment `timeoutCount`
- **AND** the second SHALL emit `[APE-LLM-ERROR] cause=http_500 ...` and increment `httpErrorCount`
- **AND** the summary SHALL report `timeout=1 http_error=1`

#### Scenario: Router-side parse failure attributed without the client seam

- **WHEN** `chat()` returns a non-null response but `ToolCallParser.parse()` extracts no tool call even after the quoted-collapsed-XY fix and last-resort integer extraction
- **THEN** `[APE-LLM-ERROR] cause=parse ...` SHALL be emitted and `parseErrorCount` incremented
- **AND** `getLastErrorCause()` SHALL NOT be consulted (the HTTP call succeeded; its value is stale)

#### Scenario: Circuit breaker event logged once per open episode

- **WHEN** the breaker trips to OPEN with 2 trips recorded and routing predicates subsequently decline 5 calls during the same open window
- **THEN** exactly one `[APE-RV] LLM circuit breaker OPEN, skipping (trips=2)` line SHALL be emitted, at the first declined call
- **AND** no LLM HTTP call SHALL be made while the breaker is OPEN

#### Scenario: Stagnation mode triggered

- **WHEN** `shouldRouteStagnation(150)` is called with `graphStableRestartThreshold = 200` and the episode flag armed
- **THEN** a log entry SHALL be emitted: `[APE-RV] LLM mode=stagnation, state=MainActivity#abc123`

#### Scenario: widget text with a newline does not break the prompt log

- **WHEN** an element's widget text contains `"line1\nline2"` and the `[APE-LLM-PROMPT] user_text=` dump is emitted
- **THEN** the element's line in the dump SHALL contain `"line1 line2"` (flattened)
- **AND** the number of element lines SHALL equal the number of elements

---

### Requirement: Action Selection Pipeline

`LlmRouter.selectAction(GUITree tree, State state, List<ModelAction> actions, MopData mopData, List<ActionHistoryEntry> recentActions)` SHALL return `ModelAction` or `null`. Pipeline:

1. `totalCalls++` — counts this attempt regardless of outcome (per INV-RTR-07).
2. `ScreenshotCapture.capture(deviceWidth, deviceHeight)` → PNG bytes. If null → `breaker.recordFailure()`, log `[APE-RV] LLM screenshot capture failed, skipping LLM step`, emit `[APE-LLM-ERROR] step=<N> cause=screenshot activity=<current activity> detail=<stage>` (INV-RTR-20), return null. A persistent null-capture condition therefore opens the breaker and halts retries for the recovery window.
4. `ImageProcessor.processScreenshot(pngBytes)` → base64 JPEG. If null → return null.
5. `ApePromptBuilder.build(tree, state, actions, mopData, base64Image, recentActions)` → messages.
6. `SglangClient.chat(messages, tools)` → `ChatResponse`, where `tools` is the per-request schema selected by `hasInputField(actions)` ("LlmRouter Lifecycle"). If IOException → `breaker.recordFailure()`, return null.
7. `ToolCallParser.parse(response)` → `ParsedAction`. If null → `breaker.recordFailure()`, return null.
8. `CoordinateNormalizer.normalize(parsedAction.x, parsedAction.y, deviceWidth, deviceHeight)` → pixel coords.
9. `mapToModelAction(pixelX, pixelY, parsedAction.actionType, parsedAction.text, actions, state, deviceWidth, deviceHeight)` → matched widget `ModelAction`, synthesized `LlmTapAction`, or null.
10. **Dead-pair ban check** (new): when step 9 returned a `matched` or `llm_tap` result, compute that result's ban key and consult the per-run dead-pair record ("Deterministic Dead-Pair Ban"). A dead key SHALL convert the outcome to `no_match` with `reason=dead_pair` and return null. The check runs here — after the mapping, before the return — so a banned decision is a *refused answer*, not a failed pipeline.
11. `breaker.recordSuccess()` — including for a banned decision (INV-RTR-16: the pipeline succeeded; only its answer was refused).
12. Outcome classification and return:
    - A **matched widget** `ModelAction` → `result=matched`, return it.
    - A synthesized `LlmTapAction` (type `MODEL_LLM_TAP`, off-tree case) → `result=llm_tap`, increment `llmTapCount`, return it.
    - `null` → `result=no_match`, return null (SATA fallback). The `no_match` telemetry SHALL carry exactly one `reason` from the fixed set `degenerate` (parsed coordinate `(0,0)`), `boundary` (status/navigation band), or `dead_pair` (the mapped result resolved to a banned dead pair). The previous binary rule — `degenerate` when the coordinate is `(0,0)`, else `boundary` — is replaced, because it cannot express the third cause.

**type_text handling**: When `mapToModelAction` finds a matching input widget and `parsedAction.text` is non-null, `selectAction()` calls `match.getResolvedNode().setInputText(text)` before returning. The caller receives a ready-to-execute ModelAction. A `type_text` action that matches no input widget returns null (`no_match`) — it is NOT converted to an off-tree tap, because a raw coordinate has no node to receive the text.

**Memory cleanup**: Steps 3-9 SHALL be wrapped in a `try-finally` block that nulls out `pngBytes`, `base64Image`, and `messages`.

**Error behavior**: Any step failure → log warning, record circuit breaker failure for network-related failures AND for a null screenshot, return null (SATA fallback). A dead-pair ban is NOT a step failure.

#### Scenario: Full pipeline success

- **WHEN** `selectAction()` is called with a valid GUITree and SGLang is responsive
- **AND** the LLM returns `click` at normalized coordinates `(450, 300)`
- **AND** the nearest ModelAction's GUITreeNode bounds contain the pixel coordinates
- **THEN** that ModelAction SHALL be returned
- **AND** the telemetry line SHALL carry `result=matched`
- **AND** `breaker.recordSuccess()` SHALL be called

#### Scenario: Off-tree element becomes a coordinate tap

- **WHEN** the LLM pipeline succeeds with a `click` at in-bounds pixel `(600, 900)` on a 1080x1794 device
- **AND** `mapToModelAction()` finds no widget containing the point and none within Euclidean tolerance
- **THEN** `selectAction()` SHALL return an `LlmTapAction` of type `MODEL_LLM_TAP` carrying `(600, 900)`

#### Scenario: banned result is refused at step 10, not failed

- **WHEN** step 9 returns a `matched` result whose ban key is already dead in this run
- **THEN** `selectAction()` SHALL return null with `result=no_match reason=dead_pair`
- **AND** `breaker.recordSuccess()` SHALL still be called
- **AND** `breaker.recordFailure()` SHALL NOT be called

#### Scenario: no_match reason is always one of three

- **WHEN** any decision in a run ends as `result=no_match`
- **THEN** its `[APE-LLM-TEL]` line SHALL carry exactly one `reason` from `degenerate`, `boundary`, `dead_pair`

## Invariants

- **INV-RTR-15**: A dead pair — `(stateKey, pixelX, pixelY)` for `llm_tap`, `(stateKey, Name.toXPath(), eventType)` for `matched` — SHALL never be returned by `selectAction()` again within the same run once dead per the death rule (**five** executions with `new_state=false`, the same threshold for every widget class the ban covers; a `new_state=true` execution neither counts toward nor resets the accumulated count). A `matched` pair whose resolved target is input-capable per `ApePromptBuilder.INPUT_CLASS_NAMES` SHALL never become dead at any strike count, and SHALL never be entered into the record; the exemption keys on the widget, not the event type, and has no counterpart on the `llm_tap` side, where no widget class exists. The ban anchor SHALL be the stable pair, never a list index. The `matched` anchor is `Name`-level and therefore abstract: one dead pair may cover several physical nodes, and ban counts SHALL NOT be reported as widget counts. The record is per-run and in-memory.
- **INV-RTR-16**: A dead-pair ban SHALL NOT invoke `breaker.recordFailure()` and SHALL NOT contribute to opening the circuit breaker; the banned decision records pipeline success and returns null through the `no_match` path with `reason=dead_pair`.
- **INV-RTR-17**: The tool name the model called SHALL constrain the matched `ActionType` in both matching passes: a `click` answer SHALL only ever return a `MODEL_CLICK` widget action (or an off-tree `MODEL_LLM_TAP`); a `type_text` answer SHALL only ever return an input-capable widget action; `long_click` MAY fall back from `MODEL_LONG_CLICK` to `MODEL_CLICK`.
- **INV-RTR-18**: The snap fallback SHALL measure point-to-rectangle (edge) distance with clamped `dx`/`dy`; a point inside a candidate's bounds has distance 0, and a point within tolerance of a widget's edge SHALL be snappable regardless of the widget's aspect ratio. The tolerance formula and its configuration keys are unchanged.
- **INV-RTR-19**: The stagnation LLM hook SHALL fire exactly once per stagnation episode: it fires at the first check where `graphStableCounter >= graphStableRestartThreshold / 2` with the episode flag armed, sets the flag, and the flag re-arms only when `graphStableCounter` resets to 0.
- **INV-RTR-20**: Every routing attempt abandoned at screenshot capture SHALL emit exactly one `[APE-LLM-ERROR] cause=screenshot` line carrying the foreground activity, and SHALL still emit no `[APE-LLM-TEL]` line; `screenshotFailedCount` remains its only cause counter (the INV-RTR-11 partition is unchanged).
