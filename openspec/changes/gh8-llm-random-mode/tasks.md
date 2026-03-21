# gh8: Add Probabilistic LLM Routing Mode — Tasks

## 1. Configuration

- [ ] 1.1 Add `llmPercentage` (double, default `0.02`) to `Config.java`
  - File: `src/main/java/com/android/commands/monkey/ape/utils/Config.java`
  - Add after existing LLM config keys (line 142)

## 2. LlmRouter — Random field + shouldRouteRandom

- [ ] 2.1 Add `Random` field to `LlmRouter`:
  - Add `private final Random random` field
  - Update constructor to accept `Random random` parameter
  - Store in field for use by `shouldRouteRandom()`
- [ ] 2.2 Add `shouldRouteRandom()` method:
  - `Config.llmPercentage > 0.0 && random.nextDouble() < Config.llmPercentage && breaker.shouldAttempt() && callCount < Config.llmMaxCalls`
- [ ] 2.3 Update `StatefulAgent` to pass `ape.getRandom()` to `LlmRouter` constructor
  - File: `src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java`
  - Find `new LlmRouter()` call, add the seeded Random parameter
- [ ] 2.4 Add unit tests for `shouldRouteRandom()`:
  - `testShouldRouteRandomDisabled`: verify returns false when llmPercentage=0.0
  - `testShouldRouteRandomBudgetExhausted`: verify returns false when callCount >= maxCalls
  - `testShouldRouteRandomAlwaysWhen099`: verify returns true when llmPercentage=0.99 with seeded Random
  - Update existing `LlmRouterTest` to pass `Random` in constructor
- [ ] 2.5 Run `/sdd-test-run`

## 3. SataAgent — Random Hook

- [ ] 3.1 Add random routing check in `SataAgent.selectNewActionNonnull()`:
  - After the stagnation hook (line 324), before SATA strategies (line 325)
  - Same guard conditions: `actionBufferSize() == 0 && newState.getActions().size() > 2`
  - `_llmRouter != null && _llmRouter.shouldRouteRandom()`
  - Call `_llmRouter.selectAction(...)` with mode `"random"`
  - If LLM returns non-null, return it (bypasses SATA strategies)
- [ ] 3.2 Run `/sdd-test-run` — all existing tests pass

## 4. Documentation and Verification

- [ ] 4.1 Update CLAUDE.md: add `llmPercentage` to Config flags table
- [ ] 4.2 Run `mvn compile` — no errors
- [ ] 4.3 Run `/sdd-test-run` — all tests pass
- [ ] 4.4 Run `/sdd-verify`
- [ ] 4.5 Run `/sdd-code-reviewer`

## Acceptance Criteria

- [ ] `Config.llmPercentage` defaults to `0.02` when not set in ape.properties
- [ ] `shouldRouteRandom()` returns false when `llmPercentage=0.0`
- [ ] `shouldRouteRandom()` returns false when call budget exhausted
- [ ] `shouldRouteRandom()` returns false when circuit breaker is open
- [ ] Random hook fires AFTER new-state and stagnation hooks (priority preserved)
- [ ] Random hook uses mode label `"random"` in telemetry
- [ ] Setting `llmPercentage=0.7` routes ~70% of non-event steps to LLM
- [ ] Coin flip is reproducible with `--seed` (uses Monkey-seeded Random, not ThreadLocalRandom)
- [ ] All existing tests pass (no regressions)
- [ ] CLAUDE.md documents the new flag
