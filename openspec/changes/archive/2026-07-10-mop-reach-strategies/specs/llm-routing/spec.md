# llm-routing — delta: mop-reach-strategies

## Purpose

Ship the F′ seam only: a config flag that round-2 adaptive LLM routing will read to raise the LLM percentage on widgetless-substrate apps. This delta adds the flag and its load semantics; it consumes nothing and changes no routing behaviour. `LlmRouter` is untouched. The classifier the flag pairs with (`MopData.isWidgetlessSubstrate()`) is added in the `mop-guidance` delta of this change.

## ADDED Requirements

### Requirement: Config — llmPercentageNoSubstrate seam

`Config.llmPercentageNoSubstrate` (double) SHALL be declared in `Config.java` and loaded via `ape.llmPercentageNoSubstrate`, default `-1`. The value `-1` is a sentinel meaning "inherit `Config.llmPercentage`" — it does NOT mean a routing percentage of −1. The flag SHALL be registered in the `apePureMode` RV-flag registry (INV-ARCH-06 of `scoring-pipeline`) as an **exempt** RV flag — consistent with the other LLM sampling params (`llmModel`, `llmPromptVariant`, `llmTemperature`, …), whose off-value shape does not fit the boolean/weight buckets. It is inert in the `ape_pure` arm regardless of its value because `apePureMode` forces the LLM masters off (`llmOnNewState`/`llmOnStagnation → false`, `llmPercentage → 0`) and leaves `llmUrl` unset.

- The `-1` sentinel SHALL be exempt from the `[0.0, 1.0]` clamp applied to `llmPercentage` (INV-RTR-08): clamping would collapse the sentinel to `0.0`. When the configured value is `-1`, it SHALL pass through unclamped.
- When the configured value is `>= 0`, it SHALL be clamped to `[0.0, 1.0]` exactly like `llmPercentage`.

This change adds NO consumer of `llmPercentageNoSubstrate`. `LlmRouter`, `shouldRouteRandom()`, the routing predicates, and all telemetry SHALL be unchanged; routing SHALL continue to use `Config.llmPercentage` unconditionally. The seam exists so round-2 adaptive routing (F′) can, without a protocol change, read `isWidgetlessSubstrate()` at load and substitute this percentage for widgetless apps.

- **INV-RTR-09**: In this change `llmPercentageNoSubstrate` SHALL have no effect on any routing decision; it SHALL be loaded and exposed only. Its `-1` default SHALL pass through the clamp unchanged; a configured value `>= 0` SHALL be clamped to `[0.0, 1.0]`.

#### Scenario: default sentinel not clamped
- **WHEN** `ape.properties` does not set `ape.llmPercentageNoSubstrate`
- **THEN** `Config.llmPercentageNoSubstrate` SHALL equal `-1` (not clamped to `0.0`)

#### Scenario: configured value clamped
- **WHEN** `ape.properties` sets `ape.llmPercentageNoSubstrate=1.5`
- **THEN** `Config.llmPercentageNoSubstrate` SHALL be `1.0`

#### Scenario: no routing behaviour change
- **WHEN** `ape.llmPercentageNoSubstrate=0.7` and an app is widgetless
- **THEN** `shouldRouteRandom()` SHALL still use `Config.llmPercentage` (unchanged); the no-substrate value SHALL NOT be consumed
