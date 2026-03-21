# gh8: Add Probabilistic LLM Routing Mode — Tasks

## 1. Configuration

- [x] 1.1 Add `llmPercentage` (double, default `0.02`) to `Config.java`

## 2. LlmRouter — Random field + shouldRouteRandom

- [x] 2.1 Add `Random` field to `LlmRouter` + update constructor
- [x] 2.2 Add `shouldRouteRandom()` method
- [x] 2.3 Update `StatefulAgent` to pass `ape.getRandom()` to `LlmRouter` constructor
- [x] 2.4 Add unit tests for `shouldRouteRandom()` + update existing tests for new constructor
- [x] 2.5 Run `/sdd-test-run` — 149 tests pass

## 3. SataAgent — Random Hook

- [x] 3.1 Add random routing check in `SataAgent.selectNewActionNonnull()` after stagnation hook
- [x] 3.2 Run `/sdd-test-run` — all existing tests pass

## 4. Documentation and Verification

- [x] 4.1 Update CLAUDE.md: add `llmPercentage` to Config flags table
- [x] 4.2 Run `mvn compile` — no errors
- [x] 4.3 Run `/sdd-test-run` — 149 tests pass, 0 failures
- [ ] 4.4 Run `/sdd-verify`
- [ ] 4.5 Run `/sdd-code-reviewer`

## Acceptance Criteria

- [x] `Config.llmPercentage` defaults to `0.02` when not set in ape.properties
- [x] `shouldRouteRandom()` returns false when `llmPercentage=0.0` (short-circuit on > 0.0 check)
- [x] `shouldRouteRandom()` returns false when call budget exhausted
- [x] `shouldRouteRandom()` returns false when circuit breaker is open
- [x] Random hook fires AFTER new-state and stagnation hooks (priority preserved)
- [x] Random hook uses mode label `"random"` in telemetry
- [x] Setting `llmPercentage=0.7` routes ~70% of non-event steps to LLM
- [x] Coin flip is reproducible with `--seed` (uses Monkey-seeded Random, not ThreadLocalRandom)
- [x] All existing tests pass (no regressions) — 149 tests, 0 failures
- [x] CLAUDE.md documents the new flag
