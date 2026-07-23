# Delta: llm-routing — llm-native-toolcall-repair

## Purpose

`LlmRouter` consumes three values that were hard-coded at their use sites: `max_tokens` (a hoisted local `1024` in the constructor, shared by the `[APE-LLM-CONFIG]` manifest and the `SglangClient` request body), the boundary reject bands (`0.05` / `0.94` in `mapToModelAction`), and the euclidean snap-tolerance floor (`50.0` in the same method). The J1 handoff (§2) requires these to be configuration-exposed **without changing any value**: the P4 no_match decomposition showed the snapping tolerance recovers at most 0.80% of no_match even at τ=200 px, 91% of `boundary` rejections happen in the edge bands the tolerance test never reaches (a policy lever, not a tolerance one), and `max_tokens` is not causal (`tokens_out` ≈ 25 ≪ 1024). Exposure exists so a future probe — e.g. relaxing the band policy — needs an `ape.properties` edit, not a jar rebuild; the Fase-B B1 arm runs all defaults so the experimental contrast isolates the J1a parser fix.

This delta rewires the three use sites to the new `Config` keys (`ape.llmMaxTokens`, `ape.llmSnapTolerancePx`, `ape.llmBoundaryTopPct`, `ape.llmBoundaryBottomPct`, specified in the `llm-infrastructure` capability). At defaults, every observable behavior — request body, manifest line, boundary rejects, tolerance matches — is bit-identical to the hard-coded version. No telemetry field, emitter site, or firing event changes anywhere in this change; the J1a parser unification surfaces in this capability only distributionally, through the existing `repair=` field (INV-RTR-13) now also appearing on native-path decisions, and through `reason=degenerate` mass migrating to `matched`/`llm_tap`/`boundary` outcomes.

## Invariants

- **INV-RTR-14** (new): The boundary reject bands, the euclidean snap-tolerance floor, and the request `max_tokens` SHALL be read from `Config` (`llmBoundaryTopPct`, `llmBoundaryBottomPct`, `llmSnapTolerancePx`, `llmMaxTokens`) at their existing use sites, with defaults `0.05` / `0.94` / `50` / `1024` such that a run with none of the keys configured is behaviorally identical to the pre-change hard-coded constants — same boundary rejects, same tolerance matches, same request body, same `[APE-LLM-CONFIG]` manifest values. The `[APE-LLM-TEL]` grammar and all emitter sites SHALL be unchanged by this change.

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
- **AND** the `[APE-LLM-CONFIG]` line SHALL carry `max_tokens=1024` — byte-identical to the pre-change hard-coded value

#### Scenario: max_tokens configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmMaxTokens=2048`
- **THEN** the request body and the `[APE-LLM-CONFIG]` line SHALL both carry `max_tokens=2048`

---

### Requirement: Coordinate-to-ModelAction Mapping

`LlmRouter.mapToModelAction(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions, State state, int deviceWidth, int deviceHeight)` SHALL map LLM output coordinates to a `ModelAction` for the current state, returning a matched widget action, a synthesized off-tree `LlmTapAction`, or null.

**Boundary reject**: Before coordinate matching, if `pixelY < deviceHeight * Config.llmBoundaryTopPct` (default `0.05` — status bar, and any degenerate `(0,0)` emission) or `pixelY > deviceHeight * Config.llmBoundaryBottomPct` (default `0.94` — navigation bar), `mapToModelAction` SHALL return null and log the boundary reject. This prevents the LLM from tapping system UI, and no off-tree tap is synthesized for these coordinates. The band fractions are configuration-exposed (J1b) with defaults reproducing the previous hard-coded `0.05`/`0.94` (INV-RTR-14).

**Special action types**:
- If `actionType` equals `"back"`, the state's `backAction` SHALL be returned directly without coordinate matching.
- If `actionType` equals `"long_click"`, coordinate matching SHALL proceed normally (bounds containment → Euclidean fallback), but if a matching widget has a MODEL_LONG_CLICK action available, that action SHALL be preferred. If only MODEL_CLICK is available, it SHALL be returned as fallback.
- If `actionType` equals `"type_text"`, only actions targeting input-capable widgets (EditText, SearchView, AutoCompleteTextView) SHALL be considered for matching. When a match is found, the caller SHALL call `action.getResolvedNode().setInputText(text)` to inject the LLM-provided text into APE's existing input event generation pipeline.

**Bounds containment (primary matching strategy)**: For each action where `action.requireTarget() == true` AND `action.isValid() == true` AND `action.getResolvedNode() != null`, check if `(pixelX, pixelY)` falls within the node's `getBoundsInScreen()` rectangle. If exactly one action's bounds contain the point, return that action. If multiple actions' bounds contain the point, return the one with the smallest area (most specific widget).

**Euclidean distance (fallback matching)**: If no action's bounds contain the point, compute the Euclidean distance from `(pixelX, pixelY)` to the center of each valid action's resolved node bounds. Return the action with the minimum distance if that distance is within a proportional tolerance: `max(Config.llmSnapTolerancePx, min(nodeWidth, nodeHeight) / 2)` pixels (floor default `50`, configuration-exposed per J1b with the identical default; the lever was analyzed and discarded — the default is not swept). This ensures larger widgets accept more coordinate imprecision.

**Off-tree coordinate tap (dynamic element)**: If no action's bounds contain the point AND no action is within Euclidean tolerance AND `actionType` is `"click"` or `"long_click"`, `mapToModelAction` SHALL return `new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType))` — a targetless `MODEL_LLM_TAP` action carrying the LLM coordinate. Because the boundary reject runs first, a coordinate reaching this point is guaranteed in-bounds and non-degenerate. For any other `actionType` (e.g. `type_text`), `mapToModelAction` SHALL return null. This is the mechanism by which APE acts on elements invisible to UIAutomator (game canvas, custom view, Compose-without-semantics).

#### Scenario: LLM says "back"

- **WHEN** `mapToModelAction(0, 0, "back", null, actions)` is called
- **THEN** `state.getBackAction()` SHALL be returned
- **AND** no coordinate matching SHALL be performed

#### Scenario: Click coordinates inside button bounds

- **WHEN** `mapToModelAction(200, 230, "click", null, actions)` is called
- **AND** action A has resolved node bounds `[100, 200, 300, 250]` (contains point)
- **AND** action B has resolved node bounds `[0, 0, 480, 800]` (also contains point, but larger)
- **THEN** action A SHALL be returned (smallest area containing the point)

#### Scenario: Click near a button (fallback to Euclidean distance)

- **WHEN** `mapToModelAction(310, 230, "click", null, actions)` is called
- **AND** no action's bounds contain `(310, 230)`
- **AND** action A has bounds center at `(200, 225)`, node size 200x50, tolerance = max(50, 25) = 50px, distance ~112px
- **AND** action B has bounds center at `(320, 240)`, node size 100x40, tolerance = max(50, 20) = 50px, distance ~14px
- **THEN** action B SHALL be returned (within tolerance, closest)

#### Scenario: Off-tree click builds an LlmTapAction

- **WHEN** `mapToModelAction(600, 900, "click", null, actions, state, 1080, 1794)` is called
- **AND** no action's bounds contain `(600, 900)`
- **AND** the nearest action's bounds center is `(300, 400)` (distance ~583px, beyond any tolerance)
- **THEN** `mapToModelAction` SHALL return a new `LlmTapAction` of type `MODEL_LLM_TAP` with `pixelX=600`, `pixelY=900`, `longClick=false`
- **AND** the action's `requireTarget()` SHALL be `false` and `getTarget()` SHALL be `null`

#### Scenario: Off-tree long_click builds a long-press tap

- **WHEN** `mapToModelAction(600, 900, "long_click", null, actions, state, 1080, 1794)` is called
- **AND** no widget contains the point and none is within tolerance
- **THEN** `mapToModelAction` SHALL return an `LlmTapAction` with `longClick=true`

#### Scenario: Off-tree type_text stays no_match

- **WHEN** `mapToModelAction(600, 900, "type_text", "hello", actions, state, 1080, 1794)` is called
- **AND** no input-capable widget contains or is near the point
- **THEN** `mapToModelAction` SHALL return null (no off-tree tap is synthesized for text input)

#### Scenario: type_text targets EditText

- **WHEN** `mapToModelAction(225, 325, "type_text", "user@example.com", actions)` is called
- **AND** action C is an EditText at bounds `[50, 300, 400, 350]` (contains point)
- **THEN** action C SHALL be returned
- **AND** the caller SHALL call `action.getResolvedNode().setInputText("user@example.com")` to inject the text

#### Scenario: long_click targets widget

- **WHEN** `mapToModelAction(200, 230, "long_click", null, actions)` is called
- **AND** action A has resolved node bounds `[100, 200, 300, 250]` (contains point) with actionType MODEL_LONG_CLICK
- **AND** action B has bounds `[100, 200, 300, 250]` (same widget) with actionType MODEL_CLICK
- **THEN** action A (MODEL_LONG_CLICK) SHALL be returned (preferred over MODEL_CLICK)

#### Scenario: Boundary reject — status bar

- **WHEN** `mapToModelAction(540, 50, "click", null, actions, state, 1080, 1920)` is called on a 1080x1920 device
- **AND** `pixelY (50) < deviceHeight * Config.llmBoundaryTopPct (96 at the 0.05 default)`
- **THEN** `mapToModelAction` SHALL return null
- **AND** no `LlmTapAction` SHALL be constructed
- **AND** the boundary reject SHALL be logged

#### Scenario: Boundary reject — navigation bar

- **WHEN** `mapToModelAction(540, 1850, "click", null, actions)` is called on a 1080x1920 device
- **AND** `pixelY (1850) > deviceHeight * Config.llmBoundaryBottomPct (1804.8 at the 0.94 default)`
- **THEN** `mapToModelAction` SHALL return null

#### Scenario: Default bands and floor reproduce pre-change behavior

- **WHEN** `ape.properties` contains none of `ape.llmBoundaryTopPct`, `ape.llmBoundaryBottomPct`, `ape.llmSnapTolerancePx`
- **THEN** every boundary-reject decision and every euclidean tolerance match SHALL be identical to the hard-coded `0.05` / `0.94` / `50.0` behavior (INV-RTR-14)

#### Scenario: Band configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmBoundaryBottomPct=0.98`
- **THEN** a `pixelY=1850` click on a 1080x1920 device SHALL pass the boundary check (1850 < 1881.6) and proceed to coordinate matching

---

### Requirement: Effective LLM Config Manifest

`LlmRouter` SHALL emit exactly one `[APE-LLM-CONFIG]` line at construction time, recording the **effective** LLM configuration actually in use for the run. The values SHALL be read from the parameters passed to the `SglangClient` / router (the effective values), NOT from the `ape.properties` `configurations` map. The config dump (`Config.printConfigurations`) is insufficient for arm attribution: it echoes the raw property string rather than the effective value (e.g. `llmPercentage` is clamped to `[0,1]` in its field initializer — the dump would show `1.5`, the manifest shows the effective `1.0`), and it runs only at `tearDown()` and is lost when the platform kills the process at the time limit. The manifest line makes each run's `.trace` self-describing from its first seconds, independent of how the run ends.

The line SHALL carry the following fields:

| Field | Source |
|-------|--------|
| `model` | effective model name passed to `SglangClient` |
| `temperature` | effective `temperature` |
| `top_p` | effective `top_p` |
| `top_k` | effective `top_k` |
| `max_tokens` | effective `max_tokens` — `Config.llmMaxTokens` (default `1024`), the same value placed in the request body |
| `timeout_ms` | effective `Config.llmTimeoutMs` passed to `SglangClient` (connect and read timeout; shapes the `timeout` failure-cause rate across arms) |
| `prompt_variant` | `ApePromptBuilder.getPromptVariant()` |
| `llm_percentage` | `Config.llmPercentage` (post-clamp, per INV-RTR-08; the clamp lives in the `Config` field initializer, so any read is post-clamp) |
| `on_new_state` | `Config.llmOnNewState` |
| `on_stagnation` | `Config.llmOnStagnation` |
| `stagnation_threshold` | `Config.graphStableRestartThreshold` (the stagnation hook fires at `threshold / 2`; config-settable, gates stagnation-mode LLM call frequency) |
| `url` | `Config.llmUrl` |

Emission is governed by INV-RTR-10. The field set is unchanged by this change; only the `max_tokens` source moves from a router-hardcoded literal to `Config.llmMaxTokens` (INV-RTR-14 — identical value at default).

#### Scenario: Manifest records defaults not present in ape.properties

- **WHEN** a run starts with `ape.llmUrl` set but `ape.llmTemperature` / `ape.llmTopP` / `ape.llmTopK` / `ape.llmMaxTokens` left at their code defaults
- **THEN** exactly one `[APE-LLM-CONFIG]` line SHALL be emitted
- **AND** it SHALL carry the effective default values (e.g. `temperature=0.3`, `max_tokens=1024`)

#### Scenario: Manifest records effective clamped percentage

- **WHEN** a run starts with `ape.llmPercentage=1.5` in `ape.properties`
- **THEN** the `[APE-LLM-CONFIG]` line SHALL carry `llm_percentage=1.0` (the effective post-clamp value), even though the config dump would echo the raw `1.5`

#### Scenario: Manifest tracks a configured max_tokens

- **WHEN** a run starts with `ape.llmMaxTokens=2048`
- **THEN** the `[APE-LLM-CONFIG]` line SHALL carry `max_tokens=2048`, matching the request body
