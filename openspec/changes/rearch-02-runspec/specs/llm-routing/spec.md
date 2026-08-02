# Delta Specification: llm-routing (rearch-02-runspec)

Only the kill-switch-registry registration clause changes: the `apePureMode` registry and the `ape_pure` arm mechanism no longer exist (see the `scoring-pipeline` delta of this change). The seam's load/clamp semantics are unchanged.

## MODIFIED Requirements

### Requirement: Config — llmPercentageNoSubstrate seam

`Config.llmPercentageNoSubstrate` (double) SHALL be declared in `Config.java` and loaded via `ape.llmPercentageNoSubstrate`, default `-1`. The value `-1` is a sentinel meaning "inherit `Config.llmPercentage`" — it does NOT mean a routing percentage of −1. In the run-spec `Feature` model the key is a sub-parameter owned by the `LLM` feature with declared neutral value `-1`: every current baseline arm pushes `-1` explicitly (including non-LLM arms), which resolution accepts as an inert key (INV-RUN-05 of `run-spec`); an explicit value `>= 0` on a plan without the `LLM` feature aborts resolution as a missing dependency.

- The `-1` sentinel SHALL be exempt from the `[0.0, 1.0]` clamp applied to `llmPercentage` (INV-RTR-08): clamping would collapse the sentinel to `0.0`. When the configured value is `-1`, it SHALL pass through unclamped.
- When the configured value is `>= 0` (on an LLM plan), it SHALL be clamped to `[0.0, 1.0]` exactly like `llmPercentage`.

This capability still has NO consumer of `llmPercentageNoSubstrate`. `LlmRouter`, `shouldRouteRandom()`, the routing predicates, and all telemetry SHALL be unchanged; routing SHALL continue to use `Config.llmPercentage` unconditionally. The seam exists so round-2 adaptive routing (F′) can, without a protocol change, read `isWidgetlessSubstrate()` at load and substitute this percentage for widgetless apps.

- **INV-RTR-09**: `llmPercentageNoSubstrate` SHALL have no effect on any routing decision; it SHALL be loaded and exposed only. Its `-1` default SHALL pass through the clamp unchanged; a configured value `>= 0` SHALL be clamped to `[0.0, 1.0]`.

#### Scenario: default sentinel not clamped
- **WHEN** `ape.properties` does not set `ape.llmPercentageNoSubstrate`
- **THEN** `Config.llmPercentageNoSubstrate` SHALL equal `-1` (not clamped to `0.0`)

#### Scenario: configured value clamped
- **WHEN** an LLM arm's `ape.properties` sets `ape.llmPercentageNoSubstrate=1.5`
- **THEN** `Config.llmPercentageNoSubstrate` SHALL be `1.0`

#### Scenario: no routing behaviour change
- **WHEN** `ape.llmPercentageNoSubstrate=0.7` on an LLM arm and an app is widgetless
- **THEN** `shouldRouteRandom()` SHALL still use `Config.llmPercentage` (unchanged); the no-substrate value SHALL NOT be consumed

#### Scenario: explicit sentinel on a non-LLM arm is inert, not an error
- **WHEN** a non-LLM arm's `ape.properties` contains `ape.llmPercentageNoSubstrate=-1` (as every current baseline arm does) and no `ape.llmUrl`
- **THEN** resolution SHALL succeed and the key SHALL be listed as `inert` in the `RUN_START` echo
