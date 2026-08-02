## Why

The "Disposable Run Kernel" re-architecture (selected in `docs/analise_fable-selecao.md` rev. 3, Sec. 6) will restructure the configuration authority (stage 2) and the action-selection precedence ladder (stage 3) of a live research instrument. Comparability with the phase-2 grid (21,681 tasks + calibration) requires that the restructured code reproduce the current decisions exactly under the same seeds (report Sec. 9.9). Today no such oracle exists: the precedence semantics live implicitly in the textual order of `SataAgent.selectNewActionNonnull` (`SataAgent.java:449-589`, verified V1) and nothing would detect a regression introduced by the migration.

This change is **stage 1 of 7** (report Sec. 10) and is the gate for everything that follows: `rearch-02-runspec` and `rearch-03-decision-pipeline` may only land when their behavior is validated against the goldens captured here.

## What Changes

- New pure-JVM golden-capture harness: for each target preset (`aperv`, `mop`, `llm`, `llm_mop`, as currently expressed by the corresponding `tool.py` arm property sets), record the deterministic action-selection sequence produced by the current code under fixed seeds against synthetic/recorded GUITree fixtures.
- Preemption golden fixtures (report Sec. 9.4): synthetic states that simultaneously qualify for LLM, MOP launcher, component trigger, and the SATA chain, confirming the hard-precedence order verified in V1/V4 — including the undocumented interaction 3.3-1 (a step preempted by the LLM does **not** advance the launcher cadence counter) captured as *current* behavior, so that stage 3 must make it an explicit, decided behavior.
- Deterministic LLM stubbing: the LLM presets are captured with a stubbed `LlmRouter`/HTTP layer (scripted accept/decline/timeout responses), since goldens cannot depend on a live SGLang server.
- Golden artifacts stored in the test tree with a documented regeneration procedure (regeneration is a deliberate act, never automatic).
- **No production behavior change.** Test infrastructure only.

## Capabilities

### New Capabilities

- `parity-oracle`: golden capture and replay-comparison of per-preset action-selection sequences under fixed seeds; preemption-order golden; regeneration procedure and its constraints.

### Modified Capabilities

_None — no production requirement changes._

## Impact

- **Test tree only** (`src/test/java/`): new fixtures, golden files, harness classes; runs under `mvn test` on the JVM (no device required).
- **Gate relationship**: `rearch-02-runspec` and `rearch-03-decision-pipeline` cite the oracle as their acceptance gate (report Sec. 10, Sec. 11 risk table); `rearch-06-memory-surgical` additionally uses the goldens as its decision-neutrality evidence after each retention change (its INV-MODEL-20, report Sec. 9 test 10). Those three are the dependents; no change depends on this one's artifacts at runtime.
- **Zero changes** to `src/main/java/`, `ape.properties` surface, CLI surface, or the Python side (rv-android untouched).
- Grounding: report Sec. 3.1 V1/V2/V4 (verified ladder), Sec. 3.3-1 (launcher cadence under LLM preemption), Sec. 9.4/9.9 (architectural tests), Sec. 10 stage 1.
