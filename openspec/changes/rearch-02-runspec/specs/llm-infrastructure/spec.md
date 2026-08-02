# Delta Specification: llm-infrastructure (rearch-02-runspec)

Only the kill-switch-registry registration clause changes: the `apePureMode` registry no longer exists (see the `scoring-pipeline` delta of this change), so the LLM keys are re-grounded on the run-spec `Feature` model. LLM behavior, defaults, and clamping are unchanged.

## MODIFIED Requirements

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
| `ape.llmBoundaryTopPct` | double | `0.05` | Top boundary reject band as a fraction of screen height (J1b; policy lever, default not swept) |
| `ape.llmBoundaryBottomPct` | double | `0.94` | Bottom boundary reject band as a fraction of screen height (J1b; policy lever, default not swept) |

The four J1b/J1c keys carry no clamping logic (researcher-facing knobs, like `llmTimeoutMs`/`llmTopK`); their defaults SHALL reproduce the previously hard-coded behavior bit-for-bit. In the run-spec `Feature` model, `ape.llmUrl` is the activation key of the `LLM` feature and every other key in this table is a sub-parameter owned by the `LLM` feature family: when `LLM` is absent from the resolved plan, `LlmParams` is null, no LLM object is constructed, and an explicitly-set LLM sub-parameter is accepted only at its neutral value (INV-RUN-05 of `run-spec`) — an explicitly enabled mode (`ape.llmOnNewState=true`, `ape.llmOnStagnation=true`, or `ape.llmPercentage>0`) without `ape.llmUrl` aborts resolution as a missing dependency.

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

- **WHEN** `ape.properties` contains none of the four J1b/J1c keys
- **THEN** `Config.llmMaxTokens` SHALL be `1024`, `Config.llmSnapTolerancePx` SHALL be `50`, `Config.llmBoundaryTopPct` SHALL be `0.05`, and `Config.llmBoundaryBottomPct` SHALL be `0.94`
- **AND** router behavior (request `max_tokens`, boundary rejects, euclidean tolerance) SHALL be identical to the pre-delta hard-coded constants

#### Scenario: J1b/J1c keys configurable without rebuild

- **WHEN** `ape.properties` contains `ape.llmMaxTokens=2048` and `ape.llmSnapTolerancePx=80`
- **THEN** `Config.llmMaxTokens` SHALL be `2048` and `Config.llmSnapTolerancePx` SHALL be `80`
- **AND** the values SHALL flow into the request body / euclidean tolerance without any code change

#### Scenario: LLM sub-parameters owned by the Feature model

- **WHEN** the run-spec key-ownership totality test runs
- **THEN** `llmMaxTokens`, `llmSnapTolerancePx`, `llmBoundaryTopPct`, and `llmBoundaryBottomPct` SHALL each be owned by the `LLM` feature as sub-parameters
- **AND** with `LLM` absent from the plan, none of them SHALL parameterize any constructed mechanism

#### Scenario: LLM mode without a URL aborts

- **WHEN** `ape.properties` contains `ape.llmOnStagnation=true` and no `ape.llmUrl`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `LLM` (instead of silently loading a mode that can never fire)
