# Tasks: llm-tap-display-bounds

## 1. clipToDisplay in LlmTapAction (TDD RED→GREEN)

- [x] 1.1 RED: extend `LlmTapActionTest` with failing cases: `clipToDisplay(new Rect(0,0,1080,1920))`
      for pixel `(424,1618)` (the gate-dropped IME-band coordinate) returns the non-empty rect
      `(424,1618,425,1619)` containing the pixel; interior pixel `(540,957)` likewise; pixel
      `(1080,500)` (exclusive right edge) returns `null`; `clipToDisplay(null)` returns `null`
- [x] 1.2 GREEN: implement `LlmTapAction.clipToDisplay(Rect)` (defensive copy of displayBounds,
      `intersect(toInjectableRect())` → clipped rect or null)
- [x] 1.3 Run `mvn test` — full suite green (baseline 651/0/19 + new cases)

## 2. Explicit-bounds guard overload + dispatch (mechanical)

- [x] 2.1 Split `generateClickEventAt(Rect, long, ClickPoint)` into a delegating 3-arg method and a
      4-arg overload `(Rect, long, ClickPoint, Rect bounds)` holding the existing body (both drop
      branches unchanged, using the passed bounds)
- [x] 2.2 `MODEL_LLM_TAP` case: pass `tap.clipToDisplay(AndroidDevice.getDisplayBounds())` as the
      explicit bounds; update the case comment to the display-domain semantics (INV-EXPL-30)
- [x] 2.3 Run `mvn test` — full suite green; `mvn package` → `target/ape-rv.jar`

## 3. On-device gate (fresh-install LLM-tap regime, thumbkey)

- [x] 3.1 Start SGLang (`phtcosta/aperv-qwen3vl-4b-v2-merged`); emulator RVSec;
      `adb push target/ape-rv.jar`; `pm clear com.dessalines.thumbkey.debug` + foreground-first
      `monkey -c LAUNCHER`; APE 2 min with `llmPercentage=0.7, llmTemperature=0,
      llmPromptVariant=v13, defaultGUIThrottle=200`
- [x] 3.2 Gate: at least one `MODEL_LLM_TAP` with `y ≥ 1487` (in-display, below the app window)
      has NO `off-screen action dropped` line AND the following step's state differs (pre-change
      baseline: 6/7 such taps dropped); any out-of-display coordinate still drops
      → (916,1628) y=1628 injected, state @1108807347→@-1652681782; 0 drops / 5 taps
- [x] 3.3 Gate: no new drop pattern for node actions (MODEL_CLICK behavior unchanged); no
      `IllegalStateException`/`Internal error`; run reaches its time budget
      → 0 exceptions, MODEL_CLICK 17/38, 96 steps / 120.3s full budget
- [x] 3.4 Record gate evidence (trace excerpts) in `verification.md`; bring SGLang down

## 4. Close-out

- [x] 4.1 Final `mvn test`; `openspec validate llm-tap-display-bounds --strict`
- [x] 4.2 Archive with `openspec archive llm-tap-display-bounds --skip-specs` + manual delta-sync:
      amended INV-EXPL-30 prose into `openspec/specs/exploration/spec.md ## Invariants` and the two
      MODIFIED requirement bodies/scenarios
- [x] 4.3 Commit (local); update memory (`cmpm-forensics-llmtap-noop-100pct` residual-debt note →
      closed) and MEMORY.md index
