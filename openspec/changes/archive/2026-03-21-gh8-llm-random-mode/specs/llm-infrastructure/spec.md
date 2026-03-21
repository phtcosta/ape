## Purpose

Delta spec for `llm-infrastructure` to add the `llmPercentage` configuration key.

---

## MODIFIED Requirements

### Requirement: LLM Configuration Keys

`Config.java` SHALL declare the following additional field:

| Key | Type | Default | Min | Max | Description |
|-----|------|---------|-----|-----|-------------|
| `ape.llmPercentage` | double | `0.02` | `0.0` | `0.99` | Probability of routing to LLM on each step (0.0 = disabled, 0.7 = 70%, 0.99 = nearly every step) |

When `Config.llmPercentage` is `0.0`, no random LLM calls SHALL occur — only new-state and stagnation modes apply.

When `Config.llmPercentage` is `0.02` (default), approximately 2% of non-event steps SHALL attempt LLM calls — a conservative production mode.

#### Scenario: Default 2% routing

- **WHEN** `ape.properties` does not contain `ape.llmPercentage`
- **THEN** `Config.llmPercentage` SHALL be `0.02`
- **AND** approximately 2% of non-event steps SHALL attempt LLM calls

#### Scenario: Disabled

- **WHEN** `ape.properties` contains `ape.llmPercentage=0.0`
- **THEN** `Config.llmPercentage` SHALL be `0.0`
- **AND** `LlmRouter.shouldRouteRandom()` SHALL always return `false`

#### Scenario: High percentage for experiments

- **WHEN** `ape.properties` contains `ape.llmPercentage=0.7`
- **THEN** `Config.llmPercentage` SHALL be `0.7`
- **AND** the user SHOULD also increase `ape.llmMaxCalls` to avoid early budget exhaustion
