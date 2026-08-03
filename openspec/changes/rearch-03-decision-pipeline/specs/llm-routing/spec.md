## REMOVED Requirements

### Requirement: LlmRouter Lifecycle

**Reason**: `LlmRouter` is dismantled (P3 — complete deletion, no delegating facade). The 996-LOC class combined trigger predicates, orchestration, HTTP wiring, coordinate mapping, ban state, and telemetry (verified V6: 19 responsibilities in `selectAction` alone, `LlmRouter.java:327-612`), while its trigger *ordering* lived in `SataAgent` (V3) and its precondition was triplicated there (V2). Its responsibilities are redistributed: trigger predicates and episode state → the three LLM `DecisionStage`s (`decision-pipeline` capability); orchestration → `LlmEngine`; transport + breaker → `LlmClient` (`llm-infrastructure` capability); capture/encode → `ScreenshotStep`; mapping/ban → `CoordinateMapper`; counters and line emission → `LlmTelemetry`. The lifecycle contract is replaced by "LLM Unit Lifecycle and Ownership" below. Every behavioral clause of the removed requirement (per-request tool schema selection, shared `max_tokens`, `[APE-LLM-CONFIG]` manifest, final wiring, `totalCalls`) survives verbatim in the replacement requirements — the deletion is of the class, not of any behavior.

## ADDED Requirements

### Requirement: LLM Unit Lifecycle and Ownership

When the plan carries the LLM feature, `RunContext` SHALL construct and own the LLM units exactly once at bootstrap, wired from the plan's `LlmParams` (never from static `Config`):

- `LlmClient` — transport + circuit breaker as one unit (`llm-infrastructure` capability): base URL, model, `temperature`, `top_p`, `top_k`, `max_tokens` (default `1024`), `timeout_ms`.
- `ScreenshotStep` — `ScreenshotCapture` + `ImageProcessor` + device-dimension determination.
- `ApePromptBuilder` — unchanged, variant from `LlmParams`.
- `ToolCallParser` — unchanged.
- `CoordinateMapper` — coordinate normalization, boundary bands (`llmBoundaryTopPct`/`llmBoundaryBottomPct`), snap tolerance floor (`llmSnapTolerancePx`), back/long-click preference, `fixTextEdit`, and the dead-pair ban record.
- `LlmTelemetry` — all LLM counters, latches, and `[APE-LLM-*]` line emission; prints the summary at teardown.
- `LlmEngine` — the thin orchestrator of the Action Selection Pipeline over the units above.

The `max_tokens` value SHALL be read once from `LlmParams` and shared by the request body and the `[APE-LLM-CONFIG]` manifest, so the manifest always reports the value actually sent. The manifest line SHALL be emitted once at `LlmClient` construction, with the same fields as before the restructuring.

**Per-request tool schema:** the two schema constants (with and without `type_text`) SHALL be built once at construction and the appropriate one passed to each `chat()` invocation by the same `hasInputField` predicate the system message uses (prompt/wire coherence, INV-LLM-11) — unchanged behavior, now owned by `LlmClient`/`LlmEngine`.

When the plan does NOT carry the LLM feature, none of these units SHALL be constructed and no LLM stage SHALL exist in the decision pipeline (feature absent = stage absent, `decision-pipeline` INV-DP-03; replaces the null `_llmRouter` convention of INV-RTR-01).

All unit references SHALL be final and reused for the entire run; all unit state (counters, latches, ban record, breaker state) is per-run and dies with the process.

#### Scenario: LLM feature in the plan constructs the units once
- **WHEN** the resolved plan carries the LLM feature with `url=http://10.0.2.2:30000/v1`
- **THEN** `RunContext` SHALL construct `LlmClient`, `ScreenshotStep`, `ApePromptBuilder`, `ToolCallParser`, `CoordinateMapper`, `LlmTelemetry`, and `LlmEngine` exactly once
- **AND** one `[APE-LLM-CONFIG]` manifest line SHALL be emitted with the effective values

#### Scenario: LLM feature absent constructs nothing
- **WHEN** the resolved plan does not carry the LLM feature
- **THEN** no LLM unit SHALL be constructed and no LLM stage SHALL be assembled
- **AND** zero LLM-related trace lines SHALL be emitted for the run

#### Scenario: units read the plan, not Config
- **WHEN** any LLM unit needs a sampling, timeout, boundary, or tolerance parameter during the run
- **THEN** it SHALL read the value injected from `LlmParams` at construction
- **AND** no LLM unit SHALL read static `Config` after bootstrap

---

### Requirement: Declared LLM Fallback

The LLM fallback SHALL be declared by the plan (the configured base: `mop` or `aperv`) and realized structurally by the decision pipeline: an LLM stage returns `Continue` on every decline, failure, refused answer, or breaker denial, and the remainder of the assembled pipeline decides the step (`decision-pipeline` INV-DP-11). There SHALL be no in-step retry, no substitute selection inside an LLM stage, and no exception path. The `decision_source` of a fallback-decided step SHALL be the deciding stage's source, never `LLM`.

#### Scenario: timeout falls back to the declared base
- **WHEN** an `llm_mop` run's LLM call times out at a step where the launcher is at its firing point with an eligible candidate
- **THEN** the step SHALL be decided by the `MopLauncher` stage with `decision_source=Component`

#### Scenario: decline on an aperv-based LLM arm falls to SATA
- **WHEN** an `llm` (aperv-base) run's LLM answer is refused (dead pair) on a step
- **THEN** the step SHALL be decided by the `SataChain` stage
- **AND** the refused answer SHALL still be telemetered as `result=no_match reason=dead_pair`

## MODIFIED Requirements

### Requirement: New-State LLM Mode

When the plan enables the LLM new-state mode, the `LlmNewState` stage SHALL be assembled into the decision pipeline ahead of the stagnation, random, launcher, trigger, and SATA stages. Its `decide()` SHALL: (1) check the shared LLM precondition — action buffer empty AND the state has more than 2 actions — through the single `LlmGate` helper (the precondition exists in exactly one place; the pre-change triplication is deleted); (2) check the new-state trigger — `_isNewState` captured before `markVisited()` (unchanged capture semantics) — and the `LlmClient.allows()` breaker gate; (3) when all hold, invoke `LlmEngine.selectAction(..., "new-state", step)`; (4) on a non-null result, stamp `DecisionSource.LLM`/`PickChannel.LLM` (and resolve a synthesized `MODEL_LLM_TAP` against the state, unchanged) and return `Select`; otherwise return `Continue`.

The stage SHALL run after `adjustActionsByGUITree()` has assigned priorities (unchanged, INV-EXPL-11) and before any SATA rung.

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
- **THEN** all three SHALL consult the same `LlmGate` helper (buffer-empty ∧ actions > 2)
- **AND** no stage SHALL carry its own copy of the precondition expression

---

### Requirement: Stagnation LLM Mode

When the plan enables the LLM stagnation mode, the `LlmStagnation` stage SHALL be assembled after `LlmNewState` and before `LlmRandom`. The stage SHALL be consulted at most once per stagnation episode: the trigger is `graphStableCounter >= threshold / 2` AND the stage's per-episode fired flag is still armed (the pure predicate `stagnationMidpointReached`, owned by the stage). The fired flag SHALL be **owned by the stage** and SHALL be:

- burned inside `decide()` whenever the trigger fires — whatever the LLM answers (a null result is a failed attempt, not an unused one; the restart at the full threshold follows if stagnation persists);
- re-armed by the stage's `onStateTransition(edge)` hook on `NEW_ACTION`/`NEW_ACTION_TARGET` edges (a new edge ends the episode — the same event that resets `graphStableCounter`).

`graphStableCounter` itself remains agent-owned shared exploration state (the forced-restart mechanism consumes it independently); on an accepted escape the stage SHALL reset it to 0 through the `StepContext`'s single declared write method. The restart mechanism is unchanged: `onGraphStable()` still restarts at `counter > threshold`.

#### Scenario: LLM provides escape action at or past the stagnation midpoint
- **WHEN** `graphStableCounter` reaches `threshold / 2` (or any greater value with the flag armed), the shared precondition holds, and the breaker allows
- **THEN** the stage SHALL burn the flag and invoke `LlmEngine.selectAction(..., "stagnation", step)`
- **AND** on a non-null result it SHALL reset `graphStableCounter` to 0 and return `Select` with `decision_source=LLM`

#### Scenario: hook fires once per episode even when the LLM fails
- **WHEN** the trigger fires and the LLM returns null
- **THEN** the stage SHALL return `Continue` with the flag burned
- **AND** the stage SHALL NOT fire again until a new edge re-arms it
- **AND** if `graphStableCounter` eventually exceeds the full threshold, `requestRestart()` SHALL be called (existing behavior)

#### Scenario: new edge re-arms the episode via the transition hook
- **WHEN** the flag is burned and a later step records a `NEW_ACTION` edge
- **THEN** the stage's `onStateTransition` SHALL re-arm the flag
- **AND** a subsequent stagnation reaching the midpoint SHALL fire again (new episode)

#### Scenario: midpoint skipped by a counter jump still fires
- **WHEN** the counter passes from below the midpoint to above it without equaling `threshold / 2` at any check
- **THEN** the stage SHALL still fire at the first check where `graphStableCounter >= threshold / 2`

#### Scenario: Stagnation mode disabled
- **WHEN** the plan does not enable the stagnation mode
- **THEN** the `LlmStagnation` stage SHALL NOT exist in the pipeline
- **AND** the restart behavior SHALL proceed unchanged

---

### Requirement: Probabilistic LLM Routing

When the plan enables probabilistic routing (`llm.percentage > 0`), the `LlmRandom` stage SHALL be assembled after `LlmStagnation` and before `MopLauncher`. Its trigger SHALL be `random.nextDouble() < percentage`, drawn from the run's seeded RNG (`RunContext`), evaluated only after the shared `LlmGate` precondition holds, followed by the `LlmClient.allows()` breaker gate — the same conjunct order and short-circuiting as before the restructuring, so the seeded draw sequence is unchanged (`decision-pipeline` INV-DP-10). When `llm.percentage` is `0.0` the stage SHALL NOT exist (no draw is ever consumed — identical to the pre-change short-circuit).

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
- **WHEN** two builds (pre-pipeline and pipeline) run the same preset, seed, and fixtures
- **THEN** the sequence of `nextDouble()` draws consumed by probabilistic routing SHALL be identical

---

### Requirement: Action Selection Pipeline

`LlmEngine.selectAction(GUITree tree, State state, List<ModelAction> actions, String mode, int step)` SHALL run the LLM decision pipeline over the decomposed units and return `ModelAction` or `null`. The step semantics are **unchanged** from the pre-decomposition `LlmRouter.selectAction`; only the owner of each step changes:

1. `LlmTelemetry` counts the attempt (`totalCalls++`, per INV-RTR-07).
2. `ScreenshotStep` determines device dimensions and captures the screenshot. Null capture → breaker failure recorded via `LlmClient`, `screenshot_failed` counter + `[APE-LLM-ERROR] cause=screenshot` with activity and failing stage via `LlmTelemetry`, return null.
3. `ScreenshotStep` resizes and base64-encodes. Null → `image` cause, return null.
4. `ApePromptBuilder.build(...)` builds the messages; `LlmTelemetry` logs `[APE-LLM-PROMPT]`.
5. `LlmClient.chat(messages, tools)` — the tools schema chosen by the same `hasInputField` predicate the prompt used (INV-LLM-11). Null → breaker failure, cause read once from the client's error seam (INV-LLM-08), cause counters + `[APE-LLM-ERROR]` via `LlmTelemetry`, return null.
6. `LlmTelemetry` emits the once-per-run `[APE-LLM-CONFIG-ACK]` on the first successful response (INV-RTR-12) and logs `[APE-LLM-RESPONSE]`.
7. `ToolCallParser.parse(response)` — including the raw-arguments repair pipeline for native tool-call malformations (`SglangClient.ToolCall.rawArguments` → Level 1 → shared `parseJsonString`), surfacing through the existing `repair=` field (INV-LLM-10, INV-RTR-14). Null → `parse` cause (client seam NOT consulted), return null.
8. `CoordinateMapper` normalizes coordinates, applies the boundary bands, maps to a `ModelAction` (containment → snap tolerance → off-tree `LlmTapAction` synthesis → `fixTextEdit` conversion → back/long-click preference), and applies the dead-pair ban check (a banned answer is a refused answer: same caller-visible path as `no_match`, breaker still records success — INV-RTR-15/16).
9. `LlmClient` records breaker success; `LlmTelemetry` accounts tokens/latency, classifies the outcome (`matched`/`llm_tap`/`no_match` + `reason`), computes/receives the nearest-widget fields, and emits `[APE-LLM-TEL]` with the same field set as before.
10. The engine returns the mapped action or null. It SHALL never throw (INV-RTR-02): an unexpected exception is the `internal` cause. Large temporaries SHALL be nulled in a `finally` block (INV-RTR-06).

**type_text handling**: unchanged — when the match is an input-capable widget and text is present, `setInputText(text)` is applied before returning.

**Known defect preserved, deliberately.** "Unchanged" here includes a defect measured at 28 of 1,233 LLM responses (2.3%): a `type_text` answer can execute a `MODEL_LONG_CLICK`. The containment pass restricts the candidate's `ActionType` only when the tool is `"click"` (`LlmRouter.java:689`), and `fixTextEdit` returns the match untouched for any tool that is neither `click` nor `long_click` (`:807`), so the long-click preference can win on a `type_text` answer. This stage is behavior-neutral by contract (parity-gated, R8): `CoordinateMapper` SHALL reproduce this path exactly, defect included. The fix is **out of scope here** and belongs to a separate change against `CoordinateMapper`, whose slicing is precisely what makes it testable in a JVM unit. Recording it is mandatory: a silently inherited defect in a newly extracted unit is indistinguishable from a slicing regression when the parity oracle later disagrees.

The dead-pair outcome feedback SHALL continue to flow from the `[APE-OUTCOME]` join-buffer site in `StatefulAgent` into the ban record — now `CoordinateMapper.recordLlmOutcome(...)` reached through `RunContext`'s LLM units — with unchanged key material and strike semantics.

#### Scenario: Full pipeline success
- **WHEN** `selectAction()` is called with a valid GUITree and the server is responsive, and the LLM returns `click` at coordinates mapping into a widget's bounds
- **THEN** that `ModelAction` SHALL be returned, the telemetry line SHALL carry `result=matched`, and the breaker SHALL record success

#### Scenario: Screenshot capture fails trips the breaker
- **WHEN** `ScreenshotStep` returns null (secure window)
- **THEN** the engine SHALL return null with no HTTP request made
- **AND** the breaker SHALL record a failure and `screenshot_failed` SHALL be counted with its `[APE-LLM-ERROR] cause=screenshot` line

#### Scenario: Repaired native tool call keeps the repair telemetry
- **WHEN** the model returns a malformed native `tool_calls` arguments string that `ToolCallParser` recovers via the raw-arguments repair pipeline and the coordinate resolves to a widget
- **THEN** the `[APE-LLM-TEL]` line SHALL carry `repair=<form>` and the decision SHALL count under both `matched` and `repaired` (INV-LLM-10, INV-RTR-14 unchanged)

#### Scenario: Banned answer leaves through the no_match path
- **WHEN** the mapped action's ban key has reached the strike threshold
- **THEN** the engine SHALL return null with `result=no_match reason=dead_pair` telemetry
- **AND** `LlmClient` SHALL still record success (a refused answer is not a pipeline failure)

#### Scenario: Engine never throws
- **WHEN** any unexpected exception occurs inside the engine
- **THEN** the engine SHALL catch it, count `internal`, emit `[APE-LLM-ERROR] cause=internal`, and return null

---

### Requirement: Deterministic Dead-Pair Ban

`CoordinateMapper` SHALL maintain a per-run, in-memory record of **dead pairs** — LLM decisions that already executed in this run without producing a new state — and SHALL refuse to return a result that resolves to a dead pair. Measured motivation: 25.6% of LLM calls (10,081/39,341) re-emit an already-executed (state, coordinate) pair, and those repeats produced **0 new states in 10,081 attempts** (Wilson CI [0.00–0.04]); the anti-repetition prompt instruction exists and is ignored. Banning by subtraction (removing the option) is the externally validated mechanism (Guardian: 36% repetition persists under instruction); the detection is deterministic in the harness — the model is never asked to self-reflect.

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

**Outcome feedback:** `StatefulAgent` SHALL report the outcome of each executed LLM-originated decision to `CoordinateMapper` — reached through `RunContext`'s LLM units, via `recordLlmOutcome(...)` — at the point where `new_state` is computed for the `[APE-OUTCOME]` line, using the same single-shot buffered-decision discipline that guards `[APE-OUTCOME]` emission. The key material and strike semantics are unchanged by the decomposition; only the owner of the record moves. Only LLM-originated decisions feed the ban record; SATA-selected actions are never banned.

**Ban check site and behavior:** the check lives in `CoordinateMapper`, at the end of its mapping step inside `LlmEngine.selectAction()` (step 8 of the Action Selection Pipeline): once a `matched` or `llm_tap` result is mapped and before it is handed back, `CoordinateMapper` SHALL compute the result's ban key; when the key is dead, the engine SHALL return null — the declared fallback, the same caller-visible path as `no_match`, decided by the remainder of the assembled pipeline (`decision-pipeline` INV-DP-11). `LlmTelemetry` SHALL emit `[APE-LLM-TEL] result=no_match reason=dead_pair` for the banned decision and increment both `noMatchCount` and a `dead_pair` overlay counter reported on the summary line. The ban SHALL NOT record a breaker failure through `LlmClient` — the LLM pipeline succeeded; only its answer was refused — so a ban streak can never open the circuit breaker.

**Memory scope:** the ban record is per-run and in-memory only; no persistence, no cross-run state.

**Falsification gate (protocol, recorded here because it defines B1's success criterion):** bucket D (dead-pair repeats) MUST fall to ≈0 in the decisive-run telemetry BEFORE any new-state gain is credited to this mechanism; if bucket D ≈ 0 and new states do not rise, the ban is judged ineffective.

#### Scenario: repeated dead llm_tap is banned

- **WHEN** an `llm_tap` at `(500, 499)` on state S1 executed earlier in the run and its recorded outcome had `new_state=false`
- **AND** a later `LlmEngine.selectAction()` on state S1 resolves to an `llm_tap` at the same `(500, 499)`
- **THEN** the engine SHALL return null and the remainder of the pipeline SHALL decide the step
- **AND** the `[APE-LLM-TEL]` line SHALL carry `result=no_match reason=dead_pair`
- **AND** no breaker failure SHALL be recorded through `LlmClient` for this decision

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
