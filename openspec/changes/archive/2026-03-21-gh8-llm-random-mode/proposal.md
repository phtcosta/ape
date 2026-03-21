# Proposal: gh8 — Add Probabilistic LLM Routing Mode

**Date**: 2026-03-21
**Track**: FF SDD
**GitHub Issue**: [#8](https://github.com/phtcosta/ape/issues/8)

---

## Why

APE-RV's LLM integration has two event-driven routing modes: new-state (fires once per unseen state) and stagnation (fires at half the restart threshold). There is no way to use the LLM on a configurable percentage of all steps.

For the prompt variant experiments (rv-android gh43), we need to control LLM usage percentage to compare with rv-agent (~70% LLM) and rvsmart's PROBABILISTIC strategy. A percentage-based mode also provides a conservative production option (2-5% LLM) where the LLM occasionally assists without dominating.

Both rv-agent (`random.random() < llm_probability`, default 0.7) and rvsmart (`RoutingManager.PROBABILISTIC` with `llmRatio`) already implement this pattern as a simple Bernoulli coin flip per iteration.

## What Changes

- **Config.java**: new flag `ape.llmPercentage` (double, 0.0-0.99, default 0.02 = 2%)
- **LlmRouter.java**: new method `shouldRouteRandom()` — returns true with probability `llmPercentage`, subject to circuit breaker and call budget; new constructor parameter `Random` (seeded by Monkey framework for reproducibility)
- **SataAgent.java**: add random routing check after the existing stagnation hook (~5 lines); pass `getRandom()` to LlmRouter constructor
- Priority order: new-state > stagnation > **random** > SATA algorithm

## Capabilities

### New Capabilities

_(none — no new spec needed; this extends the existing llm-routing capability)_

### Modified Capabilities

- `llm-routing`: adds a third routing predicate (`shouldRouteRandom`) and a new config key
- `llm-infrastructure`: adds `llmPercentage` to the Config key table

## Impact

| Area | Impact |
|------|--------|
| **Affected files** | `Config.java`, `LlmRouter.java`, `SataAgent.java` |
| **Dependencies** | None — uses the Monkey-seeded `java.util.Random` instance |
| **Downstream** | rv-android `aperv-tool` can pass `ape.llmPercentage=0.7` for experiments |
| **Testing** | Unit tests for shouldRouteRandom; existing routing tests unaffected |
| **Risk** | Low — additive change, default 0.02 adds ~6 LLM calls per 10-min run; set 0.0 to disable |
