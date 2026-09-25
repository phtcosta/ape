## Why

On a screen the accessibility tree does not describe — a LibGDX or other OpenGL game canvas, a camera preview, any custom-drawn surface — the model offers no widget action, only `MODEL_BACK` and `MODEL_MENU`. The shared LLM precondition `LlmGate.allows` (`agent/pipeline/LlmGate.java:52-54`) requires more than two actions, so on those screens the LLM is never consulted, although it is the one component that sees the screen (it receives the screenshot) and the only one with an action that does not need a widget: the off-tree coordinate tap `MODEL_LLM_TAP`. The precondition was written under "two actions or fewer means nothing to decide", which is true for SATA and false for a vision model.

The effect was measured by the Study 03 replication session on campaign E5 (jar `e93dea86`, 60 APKs × 3 prompts × 2 models, 600 s): 12 of 360 tasks have `counters.llm.calls = 0` — `com.serwylo.retrowars_70` and `com.shatteredpixel.shatteredpixeldungeon_896`, every prompt, both models — with zero transport or screenshot errors. The retrowars trace holds one abstract state for the whole task (`AndroidLauncher@…@[W=1][A=2]`) and 399 steps of alternating `MODEL_BACK`/`MODEL_MENU`, all decided by SATA. Across the 163-APK corpus three applications are LibGDX (the two above and `com.serwylo.beatgame_35`); in `e5m1`, 5.9–6.5 % of each arm's steps fall on states the gate closes by size. On those applications the LLM arm is by construction identical to the arm without LLM, which dilutes the E6 contrast symmetrically.

The configuration already carries a seam built for this case and never wired: `ape.llmPercentageNoSubstrate`, the LLM random-routing rate for "a widgetless substrate (Compose, canvas)" (mop-reach-strategies F′). Every campaign arm pushes its `-1` sentinel and rv-android's `tool.py` already maps it (`llm_percentage_no_substrate`). The seam was defined per application from static analysis (`MopData.isWidgetlessSubstrate()`, since deleted); what the evidence calls for is a per-screen fact the explorer knows at run time.

## What Changes

- **Opaque screen, defined at run time.** A step's state is *opaque* when it has at least one model action and none requires a target — the state offers only `MODEL_BACK`/`MODEL_MENU`. (An empty action list is excluded: `CoordinateMapper.map` discards any answer on it.) The predicate reads the abstract state only; it does not consult widget classes, static analysis or the screenshot.
- **`ape.llmPercentageNoSubstrate` gets its consumer and keeps its owner.** It stays a sub-parameter of `LLM_RANDOM` with neutral `-1`; no feature is added. **BREAKING (semantics of an unread key):** `-1` changes meaning from "inherit `llmPercentage`" to "opaque routing off"; `>= 0` on a plan carrying `LLM_RANDOM` turns it on. Because the key had no reader and its owner is unchanged, every run under `-1` — which is every existing arm — keeps its behavior **and its plan digest**: `RunSpec.planValues` already hashes the key on every LLM arm, so moving it to a new feature would have changed the digest even with the mechanism off.
- **`LlmGate.allows` opens on opaque steps when opaque routing is on**, for all three LLM stages (`LlmNewState`, `LlmStagnation`, `LlmRandom`). The action-buffer conjunct is kept. `LlmRandom` uses the key's value as its rate on opaque steps and `llmPercentage` elsewhere; a zero rate draws no coin, so `0` means "new-state and stagnation calls only".
- **RNG neutrality when off.** With opaque routing off, the agent generator's draw sequence is identical to jar `e93dea86` under the same seed: the coin stays behind the gate. A permanent test guards it.
- **No routing change.** With no widget on the screen, `CoordinateMapper.map` already turns a `click`/`long_click` answer into an `LlmTapAction`; nothing in the mapper, the prompt or the dead-pair ban changes.
- **Telemetry.** Step records carry `dec.opaque:1` when the step's state is opaque (every arm, omitted otherwise), and `RUN_END.counters` carries `restarts`, so the effect of off-tree taps on the SATA restart cadence (each new-coordinate tap is a `NEW_ACTION` edge and resets `graphStableCounter`) is measurable.
- **Removed:** the `Config.llmPercentageNoSubstrate` static field and its clamp helper (the plan owns the key), and the INV-RTR-09 "no consumer" seam requirement with its guard test.
- **Documentation:** the `LlmGate.allows` javadoc says "three actions or fewer" while the code blocks two or fewer; it is rewritten to state the new rule.

Declared limitation: the boundary bands of `CoordinateMapper.map` (top 5 %, bottom 6 % of the screen) still reject answers on opaque steps, so game controls in those bands stay unreachable; the loss is countable from the trace (`reason:"boundary"` on `dec.opaque` steps) for a follow-up.

Out of scope: a pixel-based state signal for opaque screens (it would change the abstraction, and `LlmNewState` therefore still fires at most once per opaque state), any change to how taps reset `graphStableCounter`, and the campaign rate — a harness choice passed through `tool.py`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `llm-routing`: the shared precondition gains the opaque-screen clause; probabilistic routing gains the per-step rate choice and the zero-rate no-draw rule; the llmPercentageNoSubstrate seam requirement is replaced by an opaque-screen routing requirement.
- `exploration`: the `LlmNewState` hook requirement restates the shared precondition and its "trivial state" scenario.
- `event-sink`: step records gain `dec.opaque`; `RUN_END.counters` gains `restarts`.

## Impact

- **Jar code:** `agent/pipeline/LlmGate.java`, `LlmNewStateStage.java`, `LlmStagnationStage.java`, `LlmRandomStage.java`, `DecisionPipeline.java` (derives opaque routing and injects the flag and rate at assembly); `utils/Config.java` (field and clamp deleted); `runtime/` unchanged; `telemetry/StepRecord`/`NdjsonSink`/`RunCounters` and the agent site that opens a step record; `agent/ApeAgent.requestRestart` (restart count).
- **Tests:** `LlmRandomStageTest` (the INV-RTR-09 no-consumer test is replaced), `ConfigTest` (clamp test removed), `RunSpecResolveTest` (a golden digest for the `llm`/`llm_mop` presets at `-1`), new gate/neutrality tests.
- **Presets:** unchanged — every preset keeps `ape.llmPercentageNoSubstrate=-1`.
- **rv-android (`aperv-tool`):** no code change; `tool.py:198` already maps the key, and the key's type (`DOUBLE`) and declared default (`-1.0`) that `tests/migration/test_jar_tables.py:86-87` pins are unchanged, as is its `inert` status on a non-LLM plan (`tests/test_runspec.py:65`). `openspec/specs/aperv/spec.md:760` ("`LLM_RANDOM` sub-parameter") stays correct; only the description of `-1` needs a follow-up there. An arm that wants the mechanism sets `llm_percentage_no_substrate >= 0`.
- **Studies:** E5 data is untouched. At `-1` the new jar equals `e93dea86` in plan digest and decisions, so E5b (on `e93dea86`) can select the configuration E6 runs on the new jar; E5c compares `-1` with a positive value on the new jar. The jar's commit and sha256 are handed to the Study 03 session once built.
