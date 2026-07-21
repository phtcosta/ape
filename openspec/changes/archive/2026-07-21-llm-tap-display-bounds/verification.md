# Verification: llm-tap-display-bounds

## On-device gate (Group 3)

**Regime**: thumbkey fresh-install LLM-tap. Emulator `emulator-5554` (AVD RVSec),
SGLang `phtcosta/aperv-qwen3vl-4b-v2-merged` on `localhost:30000`, `ape.properties`:
`llmUrl=http://10.0.2.2:30000/v1`, `llmPercentage=0.7`, `llmTemperature=0`,
`llmPromptVariant=v13`, `defaultGUIThrottle=200`.

**Setup** (per the mandatory order): `mvn package` → `adb push target/ape-rv.jar
/data/local/tmp/`; `adb shell pm clear com.dessalines.thumbkey.debug` (fresh-install →
sparse tree → coordinate taps); `adb shell monkey -p ... -c android.intent.category.LAUNCHER 1`
(foreground-first — APE does not launch the app itself); then APE `--running-minutes 2 --ape sata`.

Trace: `gate_run.trace` (9,359 lines, exit 0, elapsed 120,338 ms = full 2-min budget, 96 steps).

### Criterion (i) — IME-band tap injects and changes state

Pre-change baseline (`archive/2026-07-20-llm-tap-injection/verification.md`): 6/7 thumbkey taps
dropped, all `y ≥ 1487` (root-node bounds rejected them). This run:

```
step=1 ... state=...MainActivity@1108807347...@[W=4] action=...MODEL_LLM_TAP...@(916,1628) decision_source=LLM
[APE] New  action: ...MODEL_LLM_TAP...@(916,1628)          # registered, NO off-screen drop line
step=2 ... state=...MainActivity@-1652681782...@[W=2]      # state CHANGED after the tap
```

The tap at **(916, 1628)** — `y = 1628 ≥ 1487`, inside the 1080×1920 display but below the app
window — injected (no `[APE-RV] off-screen action dropped` line) and the following step's state
differs (`@1108807347` → `@-1652681782`). This is the exact class of tap the pre-change guard
dropped.

### Criterion — drops only for out-of-display

`grep -c 'off-screen action dropped'` → **0** across the entire run. All 5 `MODEL_LLM_TAP`
dispatches landed inside the display:

```
(916,1628)  (540,897)  (613,938)  (540,627)  (540,1011)   # steps 1, 3, 17, 22, 48
```

No genuinely out-of-display coordinate was produced by the LLM this run, so the on-device drop
path was not exercised; it is covered by unit test `clipToDisplayRejectsOutOfDisplayPixel`
(pixel `(1080,500)` at the exclusive right edge → `null` → drop) and the spec scenario
"out-of-display coordinate tap is dropped".

### Criteria (ii)/(iii) — no crash, node actions unchanged, budget reached

- `grep -c IllegalStateException` → **0**; `grep -c 'Internal error'` → **0**.
- Node actions unaffected: `UICOV-ACT ... MODEL_CLICK:17/38, MODEL_LONG_CLICK:2/2,
  MODEL_SCROLL_*:1/1` — normal interaction, no new drop pattern.
- Run reached its time budget: 96 steps, 120.3 s, clean Monkey teardown (`## Network stats:
  elapsed time=120338ms`).

**Gate: PASS.** The display-bounds validity domain lets IME/cross-window taps inject; node-action
behavior and the single guard/drop-log path are unchanged.

## JVM suite

`mvn test` → **656 run, 0 failures, 19 skipped** (baseline 651 + 5 new `clipToDisplay` cases).
`mvn package` → `target/ape-rv.jar` built.
