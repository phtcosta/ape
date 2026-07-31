# Design: telemetry-proof-llm-efficacy

## Context

Source of record: `rvsec/rv-android/docs/20260729_propostas_melhorias_e3.md` §0 — every decision below was fixed by the project author on 2026-07-29, **except the items tagged `2026-07-31`, which are decided here on evidence the adversarial verification produced after that session**: D11 (O4) is new, D1's threshold was re-selected, and D9 withdraws the mechanism the record named for A10. Those three are flagged at their own headers and are the ones needing the author's ratification; everything else records a decision already made. This design resolves the implementation details the record left open. Hard window: implemented + tested by Friday 09:00 (max Saturday 09:00); the jar feeds the decisive run that decides whether the LLM stays in the design. Task order is the deadline-driven priority order; A3 is the designated cut.

Current state (verified against this worktree, file:line):

1. **B1 site**: `LlmRouter.selectAction()` classifies the `mapToModelAction` result (`LlmRouter.java:567-694` is the mapper) and returns `matched`/`llm_tap`/`no_match`. Nothing remembers that a (state, coordinate) or (state, element, eventType) pair already executed without producing a new state — 25.6% of calls (10,081/39,341) re-emit such a pair, 0/10,081 new states.
2. **B6(i)**: the containment pass (`LlmRouter.java:601-622`) filters by `type_text`-input-capability and `preferLongClick` (`:611`) only — a `click` answer can match a `MODEL_LONG_CLICK` or scroll action. Measured: `click` executes CLICK only 80.9% of the time. The Euclidean fallback (`:651-677`) has the same gap.
3. **B6(iii)**: the tools schema is built once in the constructor (`LlmRouter.java:120` `client.setTools(buildToolsSchema())`, builder at `:150-167`) and always contains `type_text`, while the system message includes `type_text` conditionally on `hasInputField` (`ApePromptBuilder.java:133`, variant emitters at `:205-207`, `:223-225`, `:240-242`, `:284-286`, `:300-302`). The model is offered a tool the prompt says does not exist.
4. **B6(iv)**: an LLM `click` that resolves to an EditText returns the bare click; measured `type_text≈0` collapse. EditText is the type with the best grounding (93.1%).
5. **N1**: `safeGetDisplayText` (`ApePromptBuilder.java:830-839`) falls back text → content-desc, but when both are empty the element line renders `""` with no identifier at all (the short resource-id is never shown). 35.8% of grounding tests had no identifier: hit 33.1% without vs 71.4% with; ImageView 0/210 is a prompt bug.
6. **B4**: the snap loop measures Euclidean distance to the widget **centre** (`LlmRouter.java:659-676`) with `tolerance = max(floor, min(w,h)/2)` (`:666-669`) — a 1080×150 bar only snaps within ~75 px of its centre; ~450 px of its edge cannot snap. `ape.llmSnapTolerancePx` (`Config.java:223`) stays; the 150 px raise is an rv-android decision gated on B1.
7. **A4**: `[APE-STEP]` emission at `StatefulAgent.java:1396-1404` and `[APE-OUTCOME]` at `:1007-1010` carry no MOP-screen bit; `MopData.activityHasMop` (`MopData.java:975-977`) is O(1) over a pre-computed set.
8. **A5**: the pick sites are `SataAgent.java:575-587` (unvisited MOP short-circuit), `:1544-1552` (0-step short-circuit), `:607` (epsilon-greedy roulette), `:1558` (EARLY_STAGE roulette), `:460-483` (launcher), LLM hooks `:422-453` — but the channel is only recoverable by regex over free-text log lines.
9. **A6**: `MopFrontierPass.java:79` accumulates into the same `wtgBoost` that `WtgPass.java:60` and `FrontierPass.java:75` write → `decision_source=WTG` conflates MOP-frontier with generic WTG; values 400/600 in the corpus prove stacking.
10. **A8**: `ModelAction.resolvedInfo` (`ModelAction.java:136-144`) interpolates raw node text into `[APE-STEP]`; 752/166,359 lines are broken by `\n` (bias non-uniform across arms, 32–116 per arm). The prompt metadata path already flattens (`capMeta`, `ApePromptBuilder.java:510-516`); the element-line display text and `[APE-STEP]` do not.
11. **A7**: `LlmRouter.java:320-332` records a breaker failure on null screenshot with no `[APE-LLM-ERROR]` (intentional per the comment at `:328-329`); `ScreenshotCapture.java:40-57` collapses FLAG_SECURE/reflection/permission into one null (note: `OutOfMemoryError` is an `Error` and escapes the `catch (Exception)` — it is NOT conflated, do not claim it is). Evidence: 147 events in 4 APKs, 100% of 57 breaker trips co-located → the breaker silently disables the LLM arm on FLAG_SECURE apps.
12. **A10**: the only dump caller is `SataAgent.tearDown()` (`SataAgent.java:283-292`), and it is the **last** instruction of the whole chain — `StatefulAgent.tearDown()` (`:1695-1704`) runs `llmSummary → superTearDown → saveGraph → saveActionHistory → actionCounters → activityNodes → namingDump → modelCounters`, then `printCounters()`, then the dump. **338 of 800 `aperv` runs (42.3%) lost it**, and **330 of those 338 end on `Save graph data to /sdcard/sata-…`** (`StatefulAgent.java:1732`) — cut *during* the `/sdcard` serialization, three steps before the dump. Three facts fix the mechanism: `safeStep` catches `Throwable` per step and logs `tearDown step failed`, which appears in **0 of 880 traces** (so nothing threw); `Logger` writes only to `System.out`, and the trace is the host-side `adb` stdout opened by `aperv-tool` (`tool.py:1115-1120`); and on timeout the harness SIGKILLs the `adb` process (`command.py:203-205` → `os.kill(SIGKILL)`) and closes that file. Teardown overruns on surviving runs pile against a ceiling of 12,991 ms, the signature of the 315 s `adb` budget (`tool.py:990-991`). **A shutdown hook cannot help: the sink is gone before any device-side signal is sent.**
13. **B7(i)**: `SataAgent.java:436` and `LlmRouter.java:224-228` use exact equality `graphStableCounter == graphStableRestartThreshold / 2` over a counter reset on every new edge (`StatefulAgent.java:1334-1345`) — a 1-step window per stagnation episode, virtually never firing.
14. **A3**: boosts are already materialized per `ModelAction`; the counterfactual must cover the 4 pick sites and must not perturb the seeded RNG stream (INV-EXPL-14 — determinism of the seeded run).
15. **K10**: `CLAUDE.md:128` says `activityTriggerEnabled` default `false`; `Config.java:165` has default `true`.

Constraints: P1 (no new subsystems — every item is a local fix or a field), P3 (the constructor-time `setTools` path is replaced, not shimmed), P4 (comments describe the new current state), no logcat reads/writes, no manual emulator management ever (smoke runs go through rv-platform on the rv-android side).

## Architecture

No new components. One new in-router data structure (the dead-pair map), one new `ModelAction` field (`mopFrontierBoost`), one new `ModelAction` provenance value, four new `[APE-STEP]` fields (`activity_has_mop`, `pick_channel`, `mop_frontier`, `patched`), one new `GUITreeNode` bit (O4), and one reordering of the teardown chain (A10 — no shutdown hook; see D9).

### Key Components

| Component | Change |
|-----------|--------|
| `LlmRouter` | B1 dead-pair map + ban check after `mapToModelAction`; B6(i) ActionType filter; B4 edge distance; B6(iv) click-on-EditText → text entry; B6(iii) per-request schema; B7(i) re-arm trigger; A7 `[APE-LLM-ERROR] cause=screenshot`; A8 `[APE-LLM-PROMPT]` element-text sanitization (via prompt builder) |
| `SglangClient` | B6(iii) tools supplied per `chat()` invocation |
| `ScreenshotCapture` | A7 failure-stage cause seam |
| `ApePromptBuilder` | N1 identifier fallback in element lines; A8 flatten `\n` in display text |
| `StatefulAgent` | A4 `activity_has_mop` on `[APE-STEP]`/`[APE-OUTCOME]`; A5/A3 field plumbing; B1 outcome feedback to the router |
| `SataAgent` | A5 `pick_channel` at the pick sites; B7(i) trigger condition; A3 counterfactual at the 4 sites |
| `ModelAction` | A6 `mopFrontierBoost` field + `MopFrontier` decision source; A8 `resolvedInfo` newline flattening; A5 `pickChannel` provenance |
| `MopFrontierPass` | A6 writes `mopFrontierBoost` instead of accumulating into `wtgBoost` |
| `UICoverageTracker` / `SataAgent` / `StatefulAgent` | A10 dump hoisted ahead of the model serialization, via an overridable step invoked before `saveGraph` (D9) — no shutdown hook |
| `GUITreeNode` | O4 `patchedClickable` bit set by `patchGUITree`, read by the `[APE-STEP]` emitter |
| `CLAUDE.md` | K10 `activityTriggerEnabled` default corrected to `true` |

## Decisions

### D1 — B1 ban design (FIXED by the author; details resolved here)

- **Keys** (fixed): `llm_tap` result → `(state.getStateKey(), pixelX, pixelY)` with exact coordinate equality — the measured bucket D is exact-coordinate repetition (spatial collapse: `x∈{499,500}` is 36.7% of emissions). `matched` result → `(state.getStateKey(), widget-stable-id, eventType)` where the widget-stable-id is **`Name.toXPath()`** — the XPath of the action's abstract `Name`, which is the identity `UICoverageTracker.widgetId` (`UICoverageTracker.java:240-250`) and `SataAgent.mopPickKey` (`:815-827`) already use. **Never a list index** — index anchoring is the autopsy-catalogued bug class.

  *Correction, 2026-07-31*: earlier drafts of this line said the id "reuses the existing convention `GUITreeNode.toXPath()`". **`GUITreeNode` has no `toXPath` method** (`grep -c toXPath GUITreeNode.java` → 0); the only `toXPath()` in the tree is on `Name` (`Name.java:22`, `AbstractName.java:55`). The distinction is not cosmetic and must be stated, because it is what makes the ban coarser than a physical widget: a `Name` is an abstraction that resolves to `RN` nodes (`ModelAction.resolvedNodes`, `:136-143`), and **16.3% of targeted steps have `RN>1`** (23,441 of 144,174). **Banning one pair therefore withdraws the action from all `RN` widgets that share the `Name` in that state.** This is accepted for the decisive run — the alternative anchors were reviewed and none is safer (a node-derived key `(activity, className, resourceId, actionType)` is *coarser* still, colliding on ≥18.3% of anchors and 36.3% of those whose `resourceId` is empty, which is 57.6% of clicks) — but it is a known cost, not an invisible one, and the falsification gate must be read with it in mind.
- **Death rule** (author-fixed range k=2–3; **k=5 selected on re-measurement, 2026-07-31**): a pair dies after **five** executions whose recorded outcome has `new_state=false`, uniform across widget classes, with no exemption list. A `new_state=true` execution neither counts toward death nor resets the accumulated count.

  The selection replaces the earlier "one-strike with typed exceptions (Switch/CheckBox/RadioButton k=2, EditText never)" reading of the same author decision. It was made by sweeping k against the 84 `cal_a1` runs of `experimento-cal/iter0` — the same 70% LLM configuration the decisive run's LLM arm uses — reconstructing each run's LLM decision stream from the `[APE-STEP]` / `[APE-LLM-TEL]` / `[APE-OUTCOME]` join and replaying the ban rule over it.

  **The first sweep answered the wrong question, and the correction is the reason k moved.** It keyed *both* result types by `(state, pixel)`, because the trace does not carry the widget XPath. But only the `llm_tap` half of the ban uses that key — and `llm_tap` is **15.9%** of the decision stream. The other **84.1%** (`matched`) ships with the `Name`-level key above, which is looser and therefore bans *more*. Re-running the sweep with each half on the key it will actually use:

  | k | refused, swept key `(state,pixel)` | share | **refused, shipped keys** | **share** | new states lost (swept → shipped) |
  |---|---|---|---|---|---|
  | 1 | 3,161 | 48.6% | 3,847 | **59.2%** | 44 → 80 |
  | 2 | 2,283 | 35.1% | 2,975 | **45.8%** | 16 → 37 |
  | 3 | 1,813 | 27.9% | 2,441 | **37.6%** | 8 → 24 |
  | **5** | 1,305 | 20.1% | **1,788** | **27.5%** | 5 → **9** |
  | 12 | 604 | 9.3% | 881 | 13.6% | 1 → 2 |

  The swept-key column reproduces the original sweep cell for cell, which is what establishes that the sweep itself was executed correctly — it was simply run on a partition of the space that 84.1% of the ban does not use. Under the shipped keys, **k=3 refuses 37.6%**, which violates the ceiling that was the stated reason for preferring it over k=1 (see reason 1 below); **k=5 refuses 27.5%** and restores it, at the cost of 9 new states lost instead of 8.

  *Denominator note*: **the table's denominator is 6,500 decisions**, and it is only reachable with the 4 `cal_a1` smoke runs included (84 traces = 80 main at `timeout=300` + 4 smoke at `timeout=90`). The main-only denominator is 6,440. Holding the shipped-key numerator at 1,788, the main-only share is 27.8% rather than 27.5% — a shift of 0.3 pp that leaves k=5 inside the 30% ceiling and changes no conclusion. (A main-only *numerator* was not re-derived; the figure above is the bound obtained by holding it fixed, which is the conservative direction.) The denominator label is corrected here because every share in the table is computed against it.

  Two reasons, one evidential and one about the experiment:

  1. The refused block is nearly unproductive at every k, so evidence alone would pick k=1. What k actually trades is *how much of the arm's decision stream the ban takes over*. At k=1 the ban refuses **59.2%** of the LLM's answers under the shipped keys and the arm's behavior becomes substantially the SATA fallback's — the decisive run could then no longer separate "the ban helped" from "SATA did the work", and the arm stops meaning "the LLM exploring". **This ceiling — refusal under 30% — is the binding criterion, and it is what moved k from 3 to 5**: under the key the ban actually uses, k=3 sits at 37.6% and fails it, while k=5 sits at 27.5% and meets it. Note the direction of the earlier error: a looser key was flagged in the original text as making the shares "a floor, not a ceiling", which is conservative for *"is the ban productive?"* but **anti-conservative for the question that selected k** — "does the ban take over the arm?" The 9.7 pp gap between 27.9% and 37.6% is the size of that under-report.
  2. A uniform k=5 **strictly dominates** the exception list, so the list is redundant by arithmetic rather than by rarity. The list would have granted `Switch`/`CheckBox`/`RadioButton` a threshold of k=2; uniform k=5 grants them 5. Every widget it protected ends up at least as protected without it. Those classes are *not* rare — at k=1 they account for **81 of 2,586** banned pairs (3.1%), with `Spinner` (10) and `CheckedTextView` (15) adding more — which is why "the list is inert" would have been the wrong argument; the right one is that k=5 already exceeds what the list offered. Dropping it additionally covers what no class-name enumeration can reach: `Spinner` and other N-ary selectors (one coordinate, many options), Material/AppCompat subclasses whose simple names differ from the base classes (the corpus shows AndroidX simple names such as `LinearLayoutCompat`, `FloatingActionButton`, `CardView` reaching the tree, so the divergence is real), and Compose trees with no meaningful class name. It also could never have protected `llm_tap`, which carries `matched_class=none` in 1,033 of 1,033 corpus occurrences. Dropping it is simpler (P1) and strictly broader.

  The measurement's own limits, recorded so the number is not over-read. **The key mismatch is now corrected rather than caveated** — the shipped-keys column above measures each half of the ban on the key it will use, so the "floor, not ceiling" hedge of the original text no longer applies to it. Two limits remain, and they point in opposite directions. (i) It is a **static counterfactual over a recorded trace**: once a real ban fires the run diverges, so these figures bound the disruption rather than predict the post-ban run. (ii) The `Name` carried in the trace is itself contaminated by the clickability patch (`[@clickable=…]`) and by index reassignment (`[@index=N]`), so the same physical widget's key can shift between steps and **scatter** its strikes, firing the ban later than the replay suggests. The replay uses the `Name` as recorded and therefore already contains whatever scatter the corpus exhibits; the sign of the residual is not determined. Net: **27.5% at k=5 is a corpus-grounded bound on disruption, not a prediction**, and the falsification gate remains the real check.
- **Feedback path**: the router cannot see outcomes; `StatefulAgent` already computes `new_state` at the `[APE-OUTCOME]` emission point with the buffered decision. When the buffered decision originated from the LLM, `StatefulAgent` calls `LlmRouter.recordLlmOutcome(...)` with the ban key material and the `new_state` bit. This reuses the existing outcome buffer discipline (single-shot, reference-checked) — no new outcome tracking is invented.
- **Ban check site**: in `selectAction()`, after `mapToModelAction` returns and before the result is returned (the result path, after step 9 of the Action Selection Pipeline). A banned result returns null → SATA fallback, the same caller-visible path as `no_match`.
- **Telemetry**: the banned decision emits `[APE-LLM-TEL] result=no_match reason=dead_pair` (the `reason` field already discriminates `no_match` causes) and a `dead_pair=<N>` overlay counter on the summary line — bucket D becomes countable from the summary alone, which the falsification gate requires.
- **Never feeds the breaker** (fixed): the ban is a successful LLM pipeline whose answer we refuse — `breaker.recordSuccess()` still runs; `recordFailure()` never. A ban storm must not disable the LLM.
- **Memory**: per-run, in-memory `HashMap` in the router; no persistence, no cross-run state, no size cap needed (bounded by executed LLM decisions per run).
- **Falsification gate** (fixed): bucket D must fall to ≈0 in the decisive-run telemetry BEFORE any new-state gain is credited to B1; if D≈0 and new states do not rise, B1 is judged ineffective. The projected uplift — per-decision yield ≈11.4% → ≈14.7%, i.e. **+28.9% relative** — is a projection [P], not a promise.

### D2 — A3 RNG isolation (mandatory dedicated test)

The counterfactual recomputes the pick with `mopBoost` and `mopFrontierBoost` zeroed. The two short-circuit sites are deterministic — recomputation is a pure function. The two roulette sites consume the seeded stream; the decision record says "clone the Random", and the invariant it protects is: **the live seeded stream advances exactly as it would without A3** (INV-EXPL-14). `java.util.Random` does not expose its seed, so the clone is realized as common-random-number replay: the factual roulette records the draw(s) it consumed; the counterfactual roulette replays the recorded draw as a *fraction of total weight* (`f = r / totalWeight`, counterfactual pick at `f × cfTotalWeight`) — zero additional draws from the live stream, and the counterfactual answers "same random point, different weights", which is exactly the common-random-numbers contrast. (A serialization-based `Random` clone is the fallback if a site turns out to consume draws in a form the fraction replay cannot express.) Perturbing the stream would be a silent bug of the autopsy-catalogued class — hence the dedicated test: same seed with A3 on/off must produce the identical action sequence.

Honest caveat (goes in the spec too): the counterfactual is 1-step myopic — it establishes the divergence point, not the cumulative effect (which is the MOP-off arm's job, rv-android side).

### D3 — B6(iii) per-request schema approach

`SglangClient.chat(messages)` grows a per-invocation tools parameter (`chat(messages, tools)`); the constructor-time `setTools` single-schema path is **removed** (P3 — no dual path). `LlmRouter` builds two schema constants once (with and without `type_text`) and passes the one matching `ApePromptBuilder.hasInputField(actions)` for the current screen — the same predicate the system message already uses, so prompt and wire can never disagree (this coherence is the new invariant). No per-call JSON rebuilding beyond selecting the constant.

### D4 — B6(iv) fixTextEdit mechanism

Guardian's finding (fixed by the author): banning by subtraction beats instructing. The subtraction is implemented at the mapping result, not in the prompt: when the LLM answers `click`/`long_click` and the containment/snap match resolves to an input-capable widget (EditText, SearchView, AutoCompleteTextView), the router does NOT return the bare click — it converts the decision into a text-entry action on that widget: the **where** comes from the LLM coordinate (the model's best grounding, 93.1% on EditText), the **what** is generated by APE's existing typed-input generation (the same path a SATA-selected input action uses — no second LLM call, no new generator). The prompt keeps offering `type_text` when input fields exist; a well-behaved `type_text` answer is unaffected.

### D5 — A6 de-aliasing: own field, own decision source

`ModelAction` gains `mopFrontierBoost` (int, default 0); `MopFrontierPass` writes it (`setPriority` increment unchanged — the steering mechanism stays; only the telemetry field moves). `attributeByLargestBoost` (`SataAgent.java:252-276`) includes it with its own `DecisionSource.MopFrontier`; tie precedence becomes `MOP > MopFrontier > WTG > Menu > Form > Coverage` (MopFrontier sits next to MOP because it is a MOP mechanism — the whole point of A6 is that it must not launder as WTG). `[APE-STEP]` gains `mop_frontier=<N>`; the `wtg=` field goes back to meaning WTG-family only.

### D6 — A5 pick_channel enum totality

The fixed values are `short_circuit_unvisited | short_circuit_0step | roulette_greedy | roulette_early | launcher | llm | buffer` (decision record). These cover the instrumented pick sites, but `[APE-STEP]` lines also arise from channels outside that list (least-visited scan, graph navigation, back-tracking, budget/trivial paths). To keep the field total on every line without inventing per-branch labels the analysis does not need, every other channel emits `pick_channel=sata_other`. (Resolved here; flagged as an addition to the enum in the decision record.)

### D7 — A8 sanitization sites

Flattening happens where widget-derived text enters a telemetry line: `ModelAction.resolvedInfo` (`ModelAction.java:136-144`) flattens `\n`/`\r` to spaces — this fixes `[APE-STEP]` (which interpolates the action's string form) for every emitter at once; `ApePromptBuilder`'s element-line display text gets the same flattening (the `capMeta` metadata path already does it), which makes `[APE-LLM-PROMPT]`'s `user_text` per-element lines parseable. The prompt's own multi-line structure is intentional and unchanged — only embedded widget text is flattened.

### D8 — A7 cause discrimination boundaries

`ScreenshotCapture` gains a last-failure-cause seam (same pattern as `SglangClient.getLastErrorCause`: reset per `capture()` invocation, read by the router at the failure point) naming the failing stage — `surface_control` (reflection path returned null/threw) vs `uiautomation` (fallback also failed). FLAG_SECURE is not directly observable (the API returns null without saying why); the spec claims only what the code can know: the failing stage plus the activity name, which — joined with the FLAG_SECURE APK list offline — is sufficient for the C5 axis. `OutOfMemoryError` escapes (it is an `Error`); the spec does not claim it is conflated.

### D9 — A10: dump ordering, not a shutdown hook (REVISED 2026-07-31)

**The original design was a shutdown hook. It is withdrawn: on the failure path that actually loses the dumps, it recovers zero.** The honesty boundary it drew — "orderly termination (SIGTERM) yes, SIGKILL no" — is drawn on the wrong axis. The binding constraint is not which signal reaches the JVM; it is that **the sink is already closed**. `Logger` writes only to `System.out`; the trace file is the host's `adb` stdout, opened by `aperv-tool` (`tool.py:1115-1120`); on timeout the harness SIGKILLs the `adb` process (`command.py:203-205`) and the enclosing `with open(...)` closes the file. Whatever the device process writes afterwards — hook or not, SIGTERM or not — cannot reach the trace. Confirmed empirically: the `.logcat` sibling of a lossy run contains **0** lines matching `APE`, so there is no second sink to fall back on.

**The mechanism that does work is ordering.** The dump is currently the last instruction of the teardown chain and `saveGraph` is the third; 330 of the 338 lossy runs are cut inside `saveGraph`, and 3 more are cut after it but before the dump. Emitting the dump **first** — before `safeStep("saveGraph", …)` at `StatefulAgent.java:1699` — recovers **333 of 338 (98.5%)**. The 5 it does not recover never reached teardown at all (they are exactly the 5 LLM-arm runs with `n_summary_lines==0`), and no teardown-side mechanism can reach those.

Implementation note, because it is not literally one line: the dump's predicate argument (`mopReach`) is supplied by `SataAgent` (`:289-291`), while the chain lives in `StatefulAgent.tearDown()`. The hoist is realized as an overridable step of the chain invoked immediately before `saveGraph` (`StatefulAgent` calls a protected no-op that `SataAgent` overrides with its dump call), not by moving the `SataAgent` line upward into a class that cannot see it.

**"Before the model serialization", not "first in the chain".** The chain is `llmSummary → superTearDown → saveGraph → …` (`StatefulAgent.java:1694-1704`), so the hoisted step lands third, not first. That is the property that recovers the 333/338 — the losses are inside `saveGraph` — and it is the property the unit test and smoke gate (e) actually assert. The requirement and INV-COV-10 are stated on that boundary rather than on chain position, so nothing claims a guarantee that nothing checks.

**Partial dumps are now expected and must be tolerated.** 3 of the 462 runs that *do* dump are truncated mid-`UICOV-ACT`. Hoisting the dump does not make its emission atomic — it only moves it before the expensive write. Any consumer (the rv-android-side parser) SHALL treat a truncated final line as a partial dump, not as a corrupt run.

**Idempotence is no longer needed** and is dropped: with a single call site there is no second path to guard, so the atomic once-per-run flag of the withdrawn design goes with it (P1, P3 — no shim for a mechanism that no longer exists). The dump remains read-only (INV-COV-07).

*Left open, deliberately*: a hook that writes to a **device-side** sink (a file next to `sataModel.obj`, pulled by the harness, or `android.util.Log`) would survive the host-side capture ending, and is the only design that could reach the remaining 1.5%. It changes the artifact contract and the harness's pull path, so it is **not** in this change.

### D10 — B7(i) re-arm semantics

Condition becomes `graphStableCounter >= threshold/2` guarded by a per-episode fired flag; the flag re-arms when the counter resets to 0 (new edge — the existing reset at `StatefulAgent.java:1334-1345`). Fires exactly once per stagnation episode — the original single-shot intent, now reachable. Both sites (`SataAgent.java:436` predicate and `LlmRouter.shouldRouteStagnation`) move to the same semantics.

### D11 — O4: where the `patched` bit is set and what it may claim (ADDED 2026-07-31)

`patchGUITree` (`GUITreeBuilder.java:262-304`) mutates exactly two node fields — `clickable` (`:286`, and `:295` for the parent that loses it) and `index` (`:289`) — plus the DOM mirror, never the `AccessibilityNodeInfo`. `GUITreeNode` gains a `patchedClickable` boolean, set at the same two sites, default `false`. The `[APE-STEP]` emitter reads it from the action's resolved node and emits `patched=0|1`.

Three boundaries, stated so the field is not over-read:

- **It records the node's provenance, not the action's causality.** `patched=1` means "this node's clickability came from the patch", which for a `MODEL_CLICK` does imply the action would not exist without it (`ActionPatchName.buildActions` derives `MODEL_CLICK` from `clickable || checkable`). For scroll or long-click actions on the same node the implication does not hold, so offline analysis must condition on `MODEL_CLICK`.
- **`RN>1` applies here too**: the bit describes the *resolved* node the line prints, and a `Name` may resolve to several. The field is exact for the printed node and a sample for the others.
- **It does not make the patch a controllable factor.** `Config.patchGUITree` (`:90`, default `true`) is absent from `APERV_PROPERTY_MAPPING` and from the `apePureMode` kill-switch registry, so no arm can toggle it — a fact independently confirmed on the rv-android side, where `_push_properties()` silently drops unmapped keys. O4 characterizes an invariant of the substrate; it does not turn it into a variable. Making it one is out of scope here and would be an rv-android change.

Why it is worth 10 LOC now: without the bit the quantity is only reachable through a `(class, resource-id)` **type** proxy, and that proxy does not yield an interval. **36.0% is a point estimate at type level, and 19.4% rests on a different denominator — no lower bound above 0 is derivable from this corpus**, so `[19.4%; 36.0%]` must not be read as a confidence interval or as a range the true value is known to lie in. The estimate is additionally conditional on the patch log being complete — 64 of the 800 `aperv` runs carry 6,561 `MODEL_CLICK` steps and **zero** `Patching child node` lines, which is enough to move the type-level estimate by +6.3 pp if those runs simply lost their log lines. One bit removes both the proxy and the condition.

## Mapping: Spec → Implementation → Test

| Requirement / Invariant | Implementation | Test |
|-------------|---------------|------|
| INV-RTR-15/16 dead-pair ban (B1) | `LlmRouter` ban map + check; `StatefulAgent` feedback | JVM unit: key identity, survives 4 dead executions and dies on the 5th, threshold uniform across widget classes, `new_state=true` neither kills nor resets, breaker untouched; smoke gate (b) |
| INV-RTR-17 ActionType coherence (B6(i)) | `mapToModelAction` containment + euclidean filters | JVM unit: click answer never returns non-CLICK action |
| INV-LLM-11 per-request tools (B6(iii)) | `SglangClient.chat(messages, tools)`; `LlmRouter` schema selection | JVM unit: request body tools == supplied schema; no input field → no type_text |
| fixTextEdit (B6(iv)) | `mapToModelAction` click-on-input conversion | JVM unit where mockable; smoke observation |
| INV-PRM-05 identifier presence (N1) | `ApePromptBuilder` element line fallback | JVM unit: empty text+desc → short id rendered |
| INV-RTR-18 edge snapping (B4) | `mapToModelAction` euclidean loop | JVM unit: point 20 px from a wide bar's edge snaps; centre-distance would not |
| INV-SEL-06 activity_has_mop (A4) | `StatefulAgent` two emission sites | Smoke gate (c) |
| INV-SEL-05 pick_channel (A5) | pick sites + `ModelAction.pickChannel` | Smoke gate (c) |
| INV-ARCH-10 mopFrontierBoost (A6) | `ModelAction`, `MopFrontierPass`, `attributeByLargestBoost` | JVM unit: no wtgBoost write from MopFrontierPass; attribution precedence |
| INV-SEL-07 single-line [APE-STEP] (A8) | `ModelAction.resolvedInfo`, prompt display text | JVM unit: text with `\n` yields one-line resolvedInfo; smoke gate (c) zero broken lines |
| INV-RTR-20 screenshot error line (A7) | `LlmRouter:320-332`, `ScreenshotCapture` seam | Smoke gate (d) on freeotpplus |
| INV-COV-10 dump ordering (A10) | dump hoisted ahead of `saveGraph` via the overridable pre-`saveGraph` teardown step | JVM unit: the chain calls the dump before `saveGraph`; smoke gate (e): dump precedes `Save graph data` in the trace |
| INV-SEL-10 patch provenance (O4) | `GUITreeNode.patchedClickable` set in `patchGUITree`; `[APE-STEP]` field | JVM unit: patched child reports 1, natively clickable node reports 0, parent demoted at `:295` reports 1; smoke gate (c) |
| INV-RTR-19 stagnation re-arm (B7(i)) | trigger condition + fired flag | JVM unit on the predicate (pure logic) |
| INV-SEL-08/09 counterfactual (A3) | 4 pick sites, draw replay | Dedicated JVM test: seed-identical sequence with A3 on/off; smoke gate (f) |
| K10 doc | `CLAUDE.md:128` | review |

## Goals / Non-Goals

**Goals:** close the three broken evidential links per step (A4, A5, A6, A8, A3); stop the measured 25.6% dead-call waste with a falsifiable mechanism (B1); make the LLM action space coherent (B6(i)(iii)(iv), N1, B4); make failure modes visible instead of silent (A7, A10, B7(i)) — all in one rebuild before the decisive run.

**Non-Goals:**
- No `scroll` tool (B6(ii) rejected for now).
- No LLM-as-boost composition (B8 deferred — changes the nature of RQ-C3).
- No WTG pathfinding (B9/N7 deferred; substrate shown absent in this corpus).
- No snap-tolerance raise (B3 is an rv-android config change gated on B1).
- No cross-run ban memory, no ban persistence, no ban of SATA-selected actions — B1 is strictly an LLM-answer filter.
- No change to `rvsec-gator` (standing rule).
- The MOP-off arm definition, `bitbanana` subset membership, and the decisive-run LLM dose are rv-android decisions, out of this change.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Banned dead pair | `LlmRouter.selectAction` | Return null, `result=no_match reason=dead_pair`, `recordSuccess()` | SATA fallback (existing path) |
| Screenshot null | `LlmRouter` step 2 | Existing null return + breaker failure, now plus `[APE-LLM-ERROR] cause=screenshot activity=<current>` | Breaker opens as before |
| Host capture closes mid-dump | harness SIGKILL of `adb` | Nothing in-process can prevent it; the dump runs early enough that it is usually complete | Partial dump (truncated final line) is valid output — consumers tolerate it (D9) |
| Counterfactual recomputation failure | A3 sites | Catch-all: emit `cf_changed=0` variantless rather than crash selection | Selection unaffected (factual pick already made) |

## Risks / Trade-offs

- [B1 bans a pair that would have worked on the k-th try] → measured directly rather than argued: at k=5 under the shipped keys the ban would have refused 1,788 decisions in the calibration corpus, of which 9 (0.50%) had actually produced a new state. k=5 replaces the class-based exception list without weakening it — the list granted the stateful classes k=2 and the uniform threshold grants them 5. The falsification gate catches a wrong call cheaply.
- [B1's ban is coarser than a physical widget] → the shipped `matched` key is `Name`-level, and 16.3% of targeted steps resolve a `Name` to more than one node, so one ban withdraws the action from all of them. Accepted with eyes open (D1): every alternative anchor reviewed is coarser still, and the per-run scope bounds the damage to one run. What it forbids is reading a ban count as a widget count.
- [B1 is credited with an effect it cannot produce] → the ban runs *after* the LLM call completes, so it saves no latency. In the calibration corpus the LLM arm spends 35% of a 300 s budget waiting on the model, executes 0.622× the algorithmic arm's steps, and finds 0.729× its distinct states (paired: loses on 67 of 80 APK×rep pairs, median Δ −7 states). Per *step*, the two arms are near-equal (≈11.9% vs ≈12.5% new-state rate) — the LLM arm's deficit is throughput, not decision quality. B1 raises per-decision yield (≈11.4% → ≈14.7% projected, moving the LLM above the SATA fallback's 12.5%) and is projected to close roughly half the state gap, **not** all of it. Any claim that B1 makes the LLM arm competitive on wall clock is unsupported by this measurement, and the sister change's analysis stratifies states-per-step precisely so the two explanations stay separable.
- [B6(iv) converts a click the model truly meant as focus-only] → the generated text path is the same one SATA uses on that widget; a focus-only intent on an input field has no exploration value the text entry does not also produce.
- [A4's `activity_has_mop` is constant 1 in part of the corpus, so the field cannot discriminate there] → measured on the 181-APK corpus: in ~30% of Compose apps every activity carries `reachesTarget=true`, because transitive reachability saturates through the Compose recomposition machinery (96.1% of `@Composable` methods reach a JCA target, against 28.8% of non-composable ones). The field is still correct and still required — it is what makes the saturation visible instead of invisible — but a null Δ in that stratum is absence of contrast in the instrument, not evidence against the hypothesis. The analysis stratifies by UI toolkit; the detector and the full measurement are recorded in `rvsec/rv-android/docs/20260730_compose_gator_substrato_estatico.md` §4-§5.2, and the stratification is pre-registered in the sister change's design.
- [A6 changes `decision_source` distributions mid-experiment-series] → intended: the old WTG counts were conflated; offline analysis notes the label change at this jar version (jar provenance stamp).
- [A3 touches all 4 pick sites days before the deadline] → it is the designated cut; the dedicated RNG test is the merge gate; if it slips, everything else ships without it.
- [Hoisting the dump delays `saveGraph` and loses the model file instead] → the dump is ~31 lines per run (mean: 12,623 `UICOV` + 1,855 `UICOV-ACT` over 462 runs) against a `/sdcard` object serialization; it displaces `saveGraph` by a negligible margin. The trade is also asymmetric in the right direction: `sataModel.obj` has no consumer in the analysis path, the coverage dump does.
- [A10's remaining 1.5% is read as a defect of the fix] → the 5 unrecovered runs never reached teardown at all. No teardown-side mechanism reaches them, and the spec says so rather than promising resilience it cannot deliver.

## Testing Strategy

| Layer | What | How |
|-------|------|-----|
| JVM unit | All pure-logic rows in the mapping table (ban map, filters, snapping, schema selection, resolvedInfo, attribution, stagnation predicate, A3 replay) | `mvn test`, existing suite conventions; Android-gated classes stay device-covered |
| Build | `mvn package` green; jar provenance stamp updated | Verification group |
| Real smoke | 2–3 APKs × ~5 min via rv-platform (rv-android side, real SGLang server) — gates (a)–(f) in tasks.md; **no manual emulator management ever** | Verification group |

## Open Questions

Three items in this change are **not** in the source of record and await the author's ratification. None blocks starting implementation — each is written down and internally coherent — but each is a place where this change decided something §0 did not:

- **D1's threshold.** §0 fixes B1 as "one-strike com exceções por tipo (Switch/CheckBox/Radio cap k=2–3; EditText isento)". This change ships k=5 uniform with no exemption list, on a re-measurement over the shipped keys. The uniform threshold dominates the list for the stateful trio (k=2 granted → 5 granted), but **it does not cover EditText, which §0 exempted by name and which k=5 bans**. The criterion invoked to move k — refusal under 30% — is stated in this design, not in §0.
- **D9's mechanism.** §0 names "shutdown hook idempotente"; this change withdraws it and replaces it with teardown reordering, and deletes the idempotence flag. The evidence that the hook recovers zero is mechanical and checkable, and §0's own figure for this item (165/880) is superseded. What is a scoping call rather than a refutation: a **device-side sink** would deliver the guarantee §0 asked for and is deliberately out of budget (see D9's closing note).
- **D11 (O4)** has no §0 entry at all; it is decided here on the verification's evidence.

The decisive-run LLM dose is an rv-android decision (settled there as `llm_percentage=0.7`, sister change design D8) and does not gate this change.
