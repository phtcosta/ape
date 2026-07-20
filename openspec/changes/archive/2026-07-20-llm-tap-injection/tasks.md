# Tasks: llm-tap-injection

## 1. A1 — Injectable tap rect (TDD RED→GREEN)

- [x] 1.1 RED: extend `LlmTapActionTest` with failing cases: `toInjectableRect()` returns
      `(x, y, x+1, y+1)`, is non-empty, center truncates to `(x, y)`; guard-arithmetic simulation —
      interior pixel survives `visibleBounds.intersect` + `contains`, edge pixel
      (`x == visibleBounds.right`) fails `intersect`
- [x] 1.2 GREEN: implement `LlmTapAction.toInjectableRect()`
- [x] 1.3 GREEN: switch the `MODEL_LLM_TAP` case in `MonkeySourceApe` to
      `tap.toInjectableRect()` (remove the inline zero-area `Rect` construction)
- [x] 1.4 Run `mvn test` — full suite green (baseline 645/0/19 + new cases)

## 2. A2 — Stale ephemeral guard in Graph.addTransition (TDD RED→GREEN)

- [x] 2.1 RED: extend `GraphEphemeralActionTest` with failing cases: `addTransition` with an
      ephemeral action anchored to a different state returns `null`, emits the
      `[APE-RV] stale ephemeral edge dropped` line, does not throw; non-ephemeral mismatch still
      throws `IllegalStateException`
- [x] 2.2 GREEN: implement the guard at the top of `Graph.addTransition`
      (`action.isEphemeral() && !source.equals(action.getState())` → log + return null)
- [x] 2.3 Verify null-tolerance of consumers: `checkNonDeterministicTransitions` early-return;
      confirm `StatefulAgent.java:468` (`currentStateTransition.isSameActivity()`) is not newly
      reachable with null (buffer flow) — document the check result in the task commit message
- [x] 2.4 Run `mvn test` — full suite green

## 3. Build + on-device gate (fresh-install LLM-tap regime)

- [x] 3.1 `mvn package` → `target/ape-rv.jar` (d8 required)
- [x] 3.2 Start SGLang (v2 model `phtcosta/aperv-qwen3vl-4b-v2-merged`) and the RVSec emulator;
      `adb shell pm clear <pkg>` + foreground-first `monkey -c LAUNCHER` + APE with
      `llmPercentage=0.7, llmTemperature=0, llmPromptVariant=v13`
- [x] 3.3 Gate A1: at least one `MODEL_LLM_TAP` step with in-bounds coordinate has NO paired
      `off-screen action dropped` line AND the following step's GUITree/state differs (tap acted);
      out-of-bounds taps (if any) still produce the drop line
- [x] 3.4 Gate A2: run reaches its time budget with no `IllegalStateException` from
      `StateTransition.<init>`; if a refinement coincides with a pending tap, the
      `stale ephemeral edge dropped` line appears instead of a crash (absence of the coincidence is
      acceptable; the unit test carries the invariant)
- [x] 3.5 Record gate evidence (trace excerpts) in the change's `verification.md`

## 4. Close-out

- [x] 4.1 Run /sdd-qa-lint-fix on touched files; final `mvn test`
- [x] 4.2 `openspec validate llm-tap-injection --strict`
- [x] 4.3 Archive with `openspec archive llm-tap-injection --skip-specs` + manual delta-sync of
      `## Invariants` prose (INV-EXPL-19 amended wording, INV-EXPL-30, INV-MODEL-17) into
      `openspec/specs/{exploration,model}/spec.md`
- [x] 4.4 Update memory (`cmpm-forensics-llmtap-noop-100pct` → fix status) and MEMORY.md index
