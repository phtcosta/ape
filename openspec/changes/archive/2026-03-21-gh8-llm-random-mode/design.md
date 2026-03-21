# Design: gh8 — Add Probabilistic LLM Routing Mode

**Date**: 2026-03-21
**Track**: FF SDD
**GitHub Issue**: [#8](https://github.com/phtcosta/ape/issues/8)

---

## Context

APE-RV's LLM routing has two event-driven modes (new-state and stagnation). Both rv-agent and rvsmart implement probabilistic routing where each step has a configurable chance of using the LLM. This change adds the same capability to APE-RV.

Reference implementations:
- rvsmart `RoutingManager.java`: `random.nextDouble() < llmRatio`
- rv-agent `routing_manager.py`: `random.random() < llm_probability` (default 0.7)

---

## Goals / Non-Goals

**Goals:**
1. Add configurable percentage-based LLM routing (0.0-0.99)
2. Preserve existing priority: new-state > stagnation > random > SATA
3. Default 0.02 (2%) — conservative production mode (~6 extra LLM calls per 10-min run)
4. Reproducible coin flip via Monkey-seeded Random

**Non-Goals:**
- Implementing rvsmart's full RoutingManager (ARRIVAL_FIRST, NEW_SCREEN_ONLY strategies)
- Changing the existing new-state or stagnation modes
- Adding per-screen or per-activity routing logic

---

## Architecture

No architectural changes. The random check is a third predicate in `LlmRouter`, hooked into `SataAgent.selectNewActionNonnull()` after the stagnation check.

```
SataAgent.selectNewActionNonnull():
  1. new-state hook     → LlmRouter.shouldRouteNewState()
  2. stagnation hook    → LlmRouter.shouldRouteStagnation()
  3. NEW: random hook   → LlmRouter.shouldRouteRandom()
  4. SATA strategies    → selectNewActionFromBuffer(), etc.
```

### Key Components

| Component | Responsibility | Change |
|-----------|---------------|--------|
| `Config.llmPercentage` | Control random routing probability | New flag, default 0.02 |
| `LlmRouter.shouldRouteRandom()` | Coin flip with probability check | New method |
| `SataAgent.selectNewActionNonnull()` | Add random hook after stagnation | ~5 lines |

---

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| shouldRouteRandom probability | `LlmRouter.shouldRouteRandom()` | `LlmRouterTest.testShouldRouteRandom*` |
| Config.llmPercentage | `Config.java` | Tested via LlmRouter integration |
| Priority ordering | `SataAgent.selectNewActionNonnull()` | Verified by existing new-state/stagnation tests unchanged |
| Budget enforcement | `shouldRouteRandom()` checks `callCount < llmMaxCalls` | `LlmRouterTest.testShouldRouteRandomBudgetExhausted` |

---

## Decisions

### D1: Use Monkey-seeded Random (not RandomHelper/ThreadLocalRandom)

**Decision**: Pass the Monkey-seeded `Random` instance to `LlmRouter` via a new constructor parameter. `StatefulAgent` passes `ape.getRandom()` (which is `new Random(mSeed)` from the Monkey framework).

**Rationale**: `RandomHelper.getRandom()` returns `ThreadLocalRandom.current()` which does NOT support `setSeed()` — coin flips would not be reproducible with `--seed`. The agent's `getRandom()` returns the Monkey-seeded Random, ensuring deterministic behavior when a seed is set. This matches rvsmart's approach (constructor-injected `Random`).

### D2: Check order — random after stagnation

**Decision**: The random check comes after both new-state and stagnation hooks.

**Rationale**: new-state is the highest-value intervention (first visit to a state). Stagnation is targeted (fires at a specific counter value). Random is the lowest-priority LLM trigger — it fills the gaps between the targeted modes.

### D3: Default 0.02 (2% — conservative production mode)

**Decision**: Default `llmPercentage` to 0.02.

**Rationale**: 2% adds occasional LLM diversity without dominating exploration. With ~300 steps per 10-min run, that's ~6 random LLM calls — negligible overhead. When `llmUrl` is null, LlmRouter is never instantiated, so the percentage is irrelevant. The value 0.0 explicitly disables random routing for users who want only new-state + stagnation modes.

---

## API Design

### `LlmRouter` constructor change

New parameter: `Random random` (seeded by Monkey framework).
```java
public LlmRouter(Random random) {
    this.random = random;
    // ... existing initialization
}
```

`StatefulAgent` passes `ape.getRandom()` at construction time.

### `LlmRouter.shouldRouteRandom()`

```java
public boolean shouldRouteRandom() {
    return Config.llmPercentage > 0.0
            && random.nextDouble() < Config.llmPercentage
            && breaker.shouldAttempt()
            && callCount < Config.llmMaxCalls;
}
```

Same 4-condition pattern as existing predicates: feature flag + decision logic + breaker + budget. Uses the injected seeded `Random` for reproducibility.

---

## Error Handling

| Error | Strategy |
|-------|----------|
| `llmPercentage` out of range | Config.getDouble returns raw value; values > 1.0 effectively mean 100%; values < 0.0 mean 0% (nextDouble is always >= 0) |
| Budget exhausted during high-percentage run | `shouldRouteRandom()` returns false; SATA takes over transparently |

---

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| High percentage (0.7) exhausts 200-call budget early | User should increase `llmMaxCalls` when using high percentages |
| Random calls on non-actionable states | Same guard conditions as existing hooks: buffer empty + ≥3 actions |

---

## Testing Strategy

| Layer | What | How | Count |
|-------|------|-----|-------|
| Unit | `shouldRouteRandom()` returns false when 0.0 | Direct call | 1 |
| Unit | `shouldRouteRandom()` respects budget | Set callCount >= maxCalls, verify false | 1 |
| Unit | `shouldRouteRandom()` returns true at 0.99 | Config override + seeded Random always < 0.99 | 1 |
| Unit | Existing shouldRouteNewState/Stagnation unchanged | Existing tests still pass | existing |
| **Total** | | | ~3 new + existing |
