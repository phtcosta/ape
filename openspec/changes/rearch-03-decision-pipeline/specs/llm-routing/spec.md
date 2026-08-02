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
