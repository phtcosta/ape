# Delta: LLM Infrastructure (llm-coordinate-single-base)

## Purpose

`CoordinateNormalizer` today converts in one direction only: from the model's [0,1000) answer to a device pixel. The opposite conversion, from a widget's pixel centre to the [0,1000) value the prompt shows, was written inline four times. Three copies are in `ApePromptBuilder` (the shared line formatter, the `v13` builder, the `v17` builder) and one is in `StatefulAgent.recordActionHistory`. The copies differ in arithmetic order and, more importantly, in the divisor they were handed: the prompt builder used the active window's root node, the history used the display. That disagreement is the defect `llm-coordinate-single-base` repairs (see the `llm-prompt` delta).

This delta gives the forward conversion one home, next to its inverse, so the two directions are defined together and can be tested against each other. The inverse is unchanged. The forward method is pure integer arithmetic with no Android dependency, like the inverse, so its contract is checkable in a plain JVM unit test.

The same change re-derives the boundary band in the display base (`llm-routing` INV-RTR-22). Two of its configuration keys move. `ape.llmBoundaryTopPct` changes its default from `0.05` to `0.035`, the status bar over the display height. `ape.llmBoundaryBottomPct` is retired, because on the display it has no value that does what its description says.

## Invariants

- **INV-LLM-13**: For every display dimension `d` with `0 < d < 2000` and every pixel `p` with `0 <= p < d`, the round trip `normalize(toNormalized(p))` on one axis SHALL return a pixel `q` with `p - 2 <= q <= p`. Both directions truncate, so the loss is always toward the origin and below `d/1000 + 1` px. This bound is what lets a coordinate copied from the prompt land inside the widget it names.

## MODIFIED Requirements

### Requirement: CoordinateNormalizer — Qwen Coordinates to Device Pixels

`CoordinateNormalizer.normalize(int qwenX, int qwenY, int deviceWidth, int deviceHeight)` SHALL convert coordinates from Qwen3-VL's normalized [0, 1000) space to device pixel coordinates using the formula:

```
pixelX = clamp((int)((qwenX / 1000.0) * deviceWidth), 0, deviceWidth - 1)
pixelY = clamp((int)((qwenY / 1000.0) * deviceHeight), 0, deviceHeight - 1)
```

The method SHALL return an `int[2]` array containing `[pixelX, pixelY]`.

`CoordinateNormalizer.toNormalized(int pixelX, int pixelY, int width, int height)` SHALL be the forward conversion, from a device pixel to Qwen3-VL's [0, 1000) space:

```
normX = clamp((int)(pixelX * 1000.0 / width),  0, 999)
normY = clamp((int)(pixelY * 1000.0 / height), 0, 999)
```

It SHALL return an `int[2]` array containing `[normX, normY]`. It is the only implementation of this conversion in the jar. Every [0, 1000) coordinate shown to the model is computed by it (`llm-prompt` INV-PRM-06), and no other class SHALL carry its own copy of the arithmetic. The prompt passes only pixels inside the display (the centre of a widget's visible part, `llm-prompt` "Single Coordinate Base"), so the clamp is defensive: it maps a pixel outside the display to the nearest edge value instead of producing 1000 or more. `width` and `height` SHALL be positive; the caller passes the dimensions `ScreenshotStep.deviceDimensions` returned, which are positive by contract.

The two methods are inverses up to truncation (INV-LLM-13). They SHALL be called with the same `{width, height}` within one decision, because that pair is what makes the model's answer and the prompt's coordinates the same space.

Reference: Qwen3-VL coordinate convention — https://github.com/QwenLM/Qwen3-VL/issues/1486

#### Scenario: Center of 1080x1920 display

- **WHEN** `normalize(500, 500, 1080, 1920)` is called
- **THEN** the returned array SHALL be `[540, 960]`

#### Scenario: Edge clamping

- **WHEN** `normalize(1050, -10, 1080, 1920)` is called
- **THEN** `pixelX` SHALL be clamped to `1079` (deviceWidth - 1)
- **AND** `pixelY` SHALL be clamped to `0`

#### Scenario: forward conversion on the campaign display
- **WHEN** `toNormalized(540, 178, 1080, 1794)` is called
- **THEN** the returned array SHALL be `[500, 99]`

#### Scenario: forward conversion clamps a pixel past the display edge
- **WHEN** `toNormalized(1080, 1857, 1080, 1794)` is called
- **THEN** the returned array SHALL be `[999, 999]`

#### Scenario: round trip stays within two pixels toward the origin
- **WHEN** for every `p` in `[0, 1794)` the value `toNormalized(0, p, 1080, 1794)[1]` is passed back through `normalize(0, n, 1080, 1794)[1]`
- **THEN** every result `q` SHALL satisfy `p - 2 <= q <= p`

#### Scenario: Zero coordinates

- **WHEN** `normalize(0, 0, 1080, 1920)` is called
- **THEN** the returned array SHALL be `[0, 0]`

---

### Requirement: LLM Configuration Keys

`Config.java` SHALL declare the following `public static final` fields for LLM configuration, loaded from `ape.properties` at class-loading time:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ape.llmUrl` | String | `null` | SGLang base URL (null = LLM disabled) |
| `ape.llmOnNewState` | boolean | `true` | Enable new-state LLM mode |
| `ape.llmOnStagnation` | boolean | `true` | Enable stagnation LLM mode |
| `ape.llmModel` | String | `"default"` | Model name for SGLang server |
| `ape.llmTemperature` | double | `0.3` | LLM sampling temperature |
| `ape.llmTopP` | double | `0.6` | Nucleus sampling threshold |
| `ape.llmTopK` | int | `50` | Top-k sampling |
| `ape.llmTimeoutMs` | int | `15000` | HTTP timeout in milliseconds |
| `ape.llmPercentage` | double | `0.02` | Probability of routing to LLM on each step (0.0 = disabled, 0.7 = 70%, 0.99 = nearly every step) |
| `ape.llmMaxTokens` | int | `1024` | `max_tokens` for the chat completion request (J1c; default = the previously hard-coded value; not causal for truncation — observed `tokens_out` ≈ 25) |
| `ape.llmSnapTolerancePx` | int | `50` | Floor of the euclidean snap tolerance `max(floor, min(w,h)/2)` in `mapToModelAction` (J1b; lever analyzed and discarded — default not swept) |
| `ape.llmBoundaryTopPct` | double | `0.035` | Status-bar boundary reject band as a fraction of the display height, the `deviceHeight` of the decision (J1b; `0.035` = the campaign AVD's 63 px status bar over its 1794 px display, so a degenerate `(0,0)` is still rejected; set it for a device of another density) |

The three J1b/J1c keys carry no clamping logic (researcher-facing knobs, like `llmTimeoutMs`/`llmTopK`). `ape.llmMaxTokens` and `ape.llmSnapTolerancePx` reproduce the previously hard-coded behavior bit-for-bit. `ape.llmBoundaryTopPct` does not: its old default `0.05` and the bottom band `ape.llmBoundaryBottomPct=0.94` were fractions of a 1920-high screen applied to the 1794-high display, and `llm-coordinate-single-base` re-derived the band in the display base (`llm-routing` INV-RTR-22). `ape.llmBoundaryBottomPct` is retired: on the display every value below 1.0 rejects app content and every value from 1.0 up never fires, so a properties file that sets it aborts resolution with that reason (`run-spec`, "Total Fail-Fast Validation"). In the run-spec `Feature` model, `ape.llmUrl` is the activation key of the `LLM` feature and every other key in this table is a sub-parameter owned by the `LLM` feature family: when `LLM` is absent from the resolved plan, `LlmParams` is null, no LLM object is constructed, and an explicitly-set LLM sub-parameter is accepted only at its neutral value (INV-RUN-05 of `run-spec`) — an explicitly enabled mode (`ape.llmOnNewState=true`, `ape.llmOnStagnation=true`, or `ape.llmPercentage>0`) without `ape.llmUrl` aborts resolution as a missing dependency.

When `Config.llmPercentage` is `0.0`, no random LLM calls SHALL occur — only new-state and stagnation modes apply. When `Config.llmPercentage` is `0.02` (default), approximately 2% of non-event steps SHALL attempt LLM calls.

When `Config.llmUrl` is `null`, all LLM features SHALL be disabled and no LLM-related objects SHALL be instantiated.

#### Scenario: LLM disabled by default

- **WHEN** `ape.properties` does not contain `ape.llmUrl`
- **THEN** `Config.llmUrl` SHALL be `null`
- **AND** no `SglangClient`, `LlmRouter`, or other LLM objects SHALL be created

#### Scenario: LLM enabled with URL

- **WHEN** `ape.properties` contains `ape.llmUrl=http://10.0.2.2:30000/v1`
- **THEN** `Config.llmUrl` SHALL equal `"http://10.0.2.2:30000/v1"`
- **AND** `StatefulAgent` SHALL instantiate `LlmRouter` with the configured URL

#### Scenario: Individual modes toggled

- **WHEN** `ape.properties` contains `ape.llmUrl=http://10.0.2.2:30000/v1` and `ape.llmOnNewState=false`
- **THEN** the new-state LLM mode SHALL be disabled
- **AND** the stagnation mode SHALL remain enabled (per its default)

#### Scenario: Custom sampling parameters

- **WHEN** `ape.properties` contains `ape.llmTopP=0.9` and `ape.llmTopK=100`
- **THEN** `Config.llmTopP` SHALL equal `0.9`
- **AND** `Config.llmTopK` SHALL equal `100`
- **AND** `SglangClient` SHALL use these values in the request body

#### Scenario: Default 2% random routing

- **WHEN** `ape.properties` does not contain `ape.llmPercentage`
- **THEN** `Config.llmPercentage` SHALL be `0.02`
- **AND** approximately 2% of non-event steps SHALL attempt LLM calls

#### Scenario: Random routing disabled

- **WHEN** `ape.properties` contains `ape.llmPercentage=0.0`
- **THEN** `Config.llmPercentage` SHALL be `0.0`
- **AND** `LlmRouter.shouldRouteRandom()` SHALL always return `false`

#### Scenario: High percentage for experiments

- **WHEN** `ape.properties` contains `ape.llmPercentage=0.7`
- **THEN** `Config.llmPercentage` SHALL be `0.7`

#### Scenario: J1b/J1c defaults reproduce the hard-coded values

- **WHEN** `ape.properties` contains none of the three J1b/J1c keys
- **THEN** `Config.llmMaxTokens` SHALL be `1024`, `Config.llmSnapTolerancePx` SHALL be `50`, and `Config.llmBoundaryTopPct` SHALL be `0.035`
- **AND** request `max_tokens` and the euclidean tolerance SHALL be identical to the pre-J1 hard-coded constants
- **AND** the boundary reject SHALL NOT reproduce the hard-coded `0.05`/`0.94`: it SHALL be the status-bar band of `llm-routing` INV-RTR-22, with no lower band (`llm-coordinate-single-base`)

#### Scenario: J1b/J1c keys configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmMaxTokens=2048` and `ape.llmSnapTolerancePx=80`
- **THEN** `Config.llmMaxTokens` SHALL be `2048` and `Config.llmSnapTolerancePx` SHALL be `80`
- **AND** the values SHALL flow into the request body / euclidean tolerance without any code change

#### Scenario: New keys classified in the apePureMode registry

- **WHEN** the J1b/J1c keys are looked for in the `apePureMode` kill-switch registry
- **THEN** no such registry SHALL exist — `rvForcedOffValues`, `rvUnsetKeys` and `rvExemptReasons` are deleted along with the mechanism they served, and INV-ARCH-06 is dissolved (`scoring-pipeline` capability)
- **AND** the classification this scenario asked for SHALL instead be made by the `Feature` model, where the compiler can check it rather than a string literal naming a field (next scenario)

#### Scenario: LLM sub-parameters owned by the Feature model

- **WHEN** the run-spec key-ownership totality test runs
- **THEN** `llmMaxTokens`, `llmSnapTolerancePx`, and `llmBoundaryTopPct` SHALL each be owned by the `LLM` feature as sub-parameters
- **AND** `llmBoundaryBottomPct` SHALL be owned by no feature and SHALL appear among the retired keys
- **AND** with `LLM` absent from the plan, none of them SHALL parameterize any constructed mechanism

#### Scenario: LLM mode without a URL aborts

- **WHEN** `ape.properties` contains `ape.llmOnStagnation=true` and no `ape.llmUrl`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `LLM` (instead of silently loading a mode that can never fire)
