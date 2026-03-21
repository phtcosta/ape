## Purpose

Delta spec for `llm-routing` to add a probabilistic routing mode. When `Config.llmPercentage` is greater than 0.0, LlmRouter provides a third routing predicate (`shouldRouteRandom`) that fires with the configured probability on each step where neither new-state nor stagnation triggered.

---

## ADDED Requirements

### Requirement: Probabilistic LLM Routing

`LlmRouter.shouldRouteRandom()` SHALL return `true` when `random.nextDouble() < Config.llmPercentage`, where `random` is the Monkey-seeded `java.util.Random` instance injected via the LlmRouter constructor. This ensures reproducible coin flips when the `--seed` CLI flag is set. The method is also subject to the same guards as existing routing predicates: circuit breaker must allow attempts AND call budget must not be exhausted.

When `Config.llmPercentage` is `0.0`, the method SHALL always return `false` (short-circuit, no random call).

`SataAgent.selectNewActionNonnull()` SHALL check `shouldRouteRandom()` after the stagnation hook and before SATA algorithmic strategies. The check SHALL use the same guard conditions as existing hooks: `actionBufferSize() == 0` AND `newState.getActions().size() > 2`.

When the random hook fires and LLM returns a non-null action, the telemetry mode label SHALL be `"random"`.

#### Scenario: Default 2% routing

- **WHEN** `Config.llmPercentage` is `0.02` (default)
- **AND** neither new-state nor stagnation triggered on this step
- **AND** `random.nextDouble()` returns a value < 0.02 (where `random` is the Monkey-seeded Random)
- **AND** the circuit breaker allows attempts
- **AND** `callCount < Config.llmMaxCalls`
- **THEN** `shouldRouteRandom()` SHALL return `true`
- **AND** the LLM call SHALL use mode `"random"` for telemetry

#### Scenario: Disabled

- **WHEN** `Config.llmPercentage` is `0.0`
- **THEN** `shouldRouteRandom()` SHALL always return `false`

#### Scenario: High percentage (70%)

- **WHEN** `Config.llmPercentage` is `0.7`
- **AND** neither new-state nor stagnation triggered
- **THEN** `shouldRouteRandom()` SHALL return `true` approximately 70% of the time

#### Scenario: Budget exhaustion

- **WHEN** `Config.llmPercentage` is `0.7`
- **AND** `callCount >= Config.llmMaxCalls`
- **THEN** `shouldRouteRandom()` SHALL return `false`

#### Scenario: Priority order preserved

- **WHEN** `isNewState` is `true` and `Config.llmOnNewState` is `true`
- **AND** `Config.llmPercentage` is `0.7`
- **THEN** the new-state hook SHALL fire (not the random hook)
- **AND** only one LLM call SHALL be made for that step
