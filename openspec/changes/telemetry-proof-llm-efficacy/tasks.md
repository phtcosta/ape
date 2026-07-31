# Tasks: telemetry-proof-llm-efficacy

Group order is a dependency and risk order (decided 2026-07-29, source of record `rvsec/rv-android/docs/20260729_propostas_melhorias_e3.md` §0): B1 and the LLM-path fixes are what the decisive run's LLM arm cannot be interpreted without, so they come first. **There is no deadline** — every group here is to be implemented, including group 16 (A3), which an earlier revision named the designated cut on a schedule that no longer exists.

## 1. B1 — deterministic dead-pair ban (~15 LOC + tests)

- [x] 1.1 `LlmRouter`: add the per-run in-memory dead-pair map — key `(stateKey, pixelX, pixelY)` for `llm_tap`, `(stateKey, Name.toXPath(), eventType)` for `matched`; never a list index (INV-RTR-15). **`GUITreeNode` has no `toXPath()`** — the only one is on `Name` (`Name.java:22`, `AbstractName.java:55`), which is what `UICoverageTracker.widgetId` (`:240-255`) and `SataAgent.mopPickKey` (`:815-827`) already use. The key is therefore abstraction-level: one ban covers every node the `Name` resolves to (16.3% of targeted steps resolve more than one)
- [x] 1.2 `LlmRouter.selectAction()` result path (after `mapToModelAction`, before return): compute the result's ban key; if dead → return null (SATA fallback, same path as `no_match`), emit `[APE-LLM-TEL] result=no_match reason=dead_pair`, increment `noMatchCount` + new `dead_pair` overlay counter; add `dead_pair=<N>` to the summary line. The ban MUST NOT call `breaker.recordFailure()` (INV-RTR-16)
- [x] 1.3 Death rule: **k=5** — five executions with `new_state=false` kill the pair, the same threshold for every widget class the ban covers; a `new_state=true` execution neither counts toward death nor resets the accumulated count (k **re-selected** by measurement 2026-07-31 on the shipped keys, design D1: k=3 refuses 37.6% and breaks the 30% ceiling; k=5 refuses 27.5%)
- [x] 1.3b **Input-capable exemption** (author ratification 2026-07-31, design D1): a `matched` pair whose resolved target is input-capable never becomes dead, at any strike count. Implement by **not recording the strike** — the pair never enters the map — so there is no filter at the ban check and no state kept for exempt pairs. Reuse `ApePromptBuilder.INPUT_CLASS_NAMES` / `isInputClass(GUITreeNode)` (`ApePromptBuilder.java:31-36`, `:783`); do NOT introduce a second list of input classes (P1). The exemption keys on the widget, not the event type, so it covers the text-entry action group 4 converts a click into. It has no `llm_tap` counterpart — `matched_class=none` in 1,033 of 1,033 corpus occurrences means no class is knowable there
- [x] 1.4 `StatefulAgent`: at the `[APE-OUTCOME]` computation point, feed the outcome (`new_state` bit + ban key material) of LLM-originated decisions back to the router, reusing the existing single-shot buffered-decision discipline
- [x] 1.5 JVM unit tests: key identity (state+coordinate, state+`Name` xpath+eventType); survives 4 dead executions and dies on the 5th; threshold uniform across the covered classes (Switch/Spinner/Button all die at k=5); **`EditText` and the other three input-capable classes survive six dead executions and never enter the map** (task 1.3b); **the exemption follows the widget through the group-4 text-entry conversion**; an `llm_tap` is banned with no exemption consulted; `new_state=true` neither kills nor resets; breaker untouched by a ban; per-run reset; **a banned `Name` that resolves to several nodes is banned for all of them** (the abstraction-level semantics of INV-RTR-15, asserted rather than discovered later)

## 2. B6(i) — ActionType filter in the containment pass (~10 LOC)

- [x] 2.1 `LlmRouter.mapToModelAction` containment loop (`LlmRouter.java:601-622`; today only `preferLongClick` at `:611`): when `actionType=="click"`, require `ActionType.MODEL_CLICK` (INV-RTR-17)
- [x] 2.2 Apply the same filter in the Euclidean fallback loop (`:651-677`)
- [x] 2.3 JVM unit test: a `click` answer over a point contained only by a `MODEL_LONG_CLICK` action does not return it (falls to snap/off-tree)

## 3. B6(iii) — per-request tool schema (~10–20 LOC, touches SglangClient)

- [x] 3.1 `SglangClient`: replace the constructor-era `setTools(JSONArray)` single-schema path with a per-invocation `chat(messages, tools)` parameter; delete the run-wide field (P3, INV-LLM-11)
- [x] 3.2 `LlmRouter`: build the two schema constants once (with/without `type_text`, from the existing `buildToolsSchema`, `LlmRouter.java:150-167`); remove `client.setTools(...)` from the constructor (`:120`); pass the schema matching `hasInputField(actions)` on each call — the same predicate the system message uses (`ApePromptBuilder.java:133`)
- [x] 3.3 JVM unit tests: request body tools == supplied schema; screen without input fields → no `type_text` on the wire; prompt/wire coherence (schema with `type_text` iff system message lists it)

## 4. B6(iv) — fixTextEdit (Guardian mechanism)

- [x] 4.1 `LlmRouter.mapToModelAction`: when `click`/`long_click` resolves (containment or snap) to an input-capable widget, do NOT return the bare click — convert to a text-entry action on that widget, text generated by APE's existing typed-input path (where = LLM coordinate, what = harness; no second LLM call)
- [x] 4.2 JVM unit test where mockable: click-on-EditText yields a text-entry action, not a bare click; `type_text` answers unaffected

## 5. N1 — identifiers in prompt element lines (~1–5 LOC)

- [x] 5.1 `ApePromptBuilder`: extend the `safeGetDisplayText` fallback (`:830-839`) so that when text AND content-description are empty it renders the short resource-id (`id=<shortId>`); never an empty `""` identifier for a node that has any of the three (INV-PRM-05). The element lines that consume it are at `:381`, `:618` and `:688` — note `:464-469` is `buildWidgetMetadata` (the MOP marker suffix), a different function
- [x] 5.2 JVM unit tests: ImageView with only content-desc renders it; only resource-id renders `id=...`; all-empty node omits the identifier without breaking the line format

## 6. B4 — edge-based snapping (~6–10 LOC)

- [ ] 6.1 `LlmRouter.mapToModelAction` snap loop (`LlmRouter.java:659-676`): replace centre distance with point-to-rectangle distance (clamped `dx`/`dy`, `hypot`); tolerance formula `max(floor, min(w,h)/2)` (`:666-669`) unchanged (INV-RTR-18). Note: `ape.llmSnapTolerancePx` (`Config.java:223`) stays as-is — raising it to 150 is an rv-android config decision gated on B1, out of this change
- [ ] 6.2 JVM unit test: point `(540,180)` 20 px above the top edge of a bar at `[0,200,1080,350]` snaps (edge distance 20 ≤ tolerance 75); the same point fails centre distance — the bar's centre is `(540,275)`, so the centre distance is **95 px** > 75 — regression-locks the geometry

## 7. A4 — serialize mop_reach (~3 LOC)

- [ ] 7.1 `[APE-STEP]` emission (`StatefulAgent.java:1396-1404`): add `activity_has_mop=0|1` via `MopData.activityHasMop` (`MopData.java:975-977`, O(1)); `0` when `MopData` is null (INV-SEL-06)
- [ ] 7.2 `[APE-OUTCOME]` emission (`StatefulAgent.java:1007-1010`): add `activity_has_mop` for the **target** activity

## 8. A5 — pick_channel field (~10 LOC)

- [ ] 8.1 `ModelAction`: add `pickChannel` provenance (like `decisionSource`); set at the pick sites — `SataAgent.java:575-587` (`short_circuit_unvisited`), `:1544-1552` (`short_circuit_0step`), `:607` (`roulette_greedy`), `:1558` (`roulette_early`), `:460-483` (`launcher`), LLM hooks `:422-453` (`llm`), buffer path (`buffer`), every other path (`sata_other`) (INV-SEL-05)
- [ ] 8.2 Serialize `pick_channel=<value>` on every `[APE-STEP]` line

## 9. A6 — un-alias wtgBoost (~15 LOC)

- [ ] 9.1 `ModelAction`: add `mopFrontierBoost` field (get/set, reset with the other boosts)
- [ ] 9.2 `MopFrontierPass.java:79`: write `mopFrontierBoost` (accumulating) instead of `wtgBoost`; `setPriority` increment unchanged (INV-MFP-02, INV-ARCH-10)
- [ ] 9.3 `SataAgent.attributeByLargestBoost` (`SataAgent.java:252-276`): include `mopFrontierBoost` with new `DecisionSource.MopFrontier`; tie precedence `MOP > MopFrontier > WTG > Menu > Form > Coverage`
- [ ] 9.4 Serialize `mop_frontier=<N>` on `[APE-STEP]`; `wtg=` reverts to WTG-family only
- [ ] 9.5 JVM unit tests: MopFrontierPass writes only its own field; co-applying passes decompose (wtg=400, mop_frontier=200 for the 600 stack); attribution precedence

## 10. A8 — escape newlines in [APE-STEP] (~5–10 LOC)

- [ ] 10.1 `ModelAction.resolvedInfo` (`ModelAction.java:136-144`): flatten `\n`/`\r` in the interpolated node text (fixes `[APE-STEP]` for all emitters) (INV-SEL-07)
- [ ] 10.2 `ApePromptBuilder` element-line display text: same flattening (the `capMeta` metadata path already does it) — makes `[APE-LLM-PROMPT]` per-element parseable
- [ ] 10.3 JVM unit tests: node text with `\n` yields single-line `resolvedInfo`; element line flattened

## 11. A7 (jar part) — screenshot-failure telemetry (1–10 LOC)

- [ ] 11.1 `LlmRouter.java:320-332`: emit `[APE-LLM-ERROR] step=<N> cause=screenshot activity=<current activity> detail=<stage>` at the null-capture branch (removing the intentional silence noted at `:328-329`); breaker semantics and `screenshotFailedCount` unchanged (INV-RTR-20)
- [ ] 11.2 `ScreenshotCapture` (`:40-57`): add the failure-stage seam (`surface_control` | `uiautomation`), reset per `capture()` (INV-LLM-12). Do NOT claim OutOfMemoryError is conflated — it is an `Error` and escapes
- [ ] 11.3 JVM unit test on the seam reset semantics (pure logic)

## 12. A10 — hoist the coverage dump ahead of the model serialization (~10 LOC)

**Revised 2026-07-31 (design D9): the shutdown hook is withdrawn.** It recovers zero on the measured failure path — the trace is the host's `adb` stdout, SIGKILLed and closed by the harness before any device-side signal is delivered, so the hook's output has nowhere to land. Ordering is the mechanism that works: 330 of the 338 lossy runs are cut inside `saveGraph`, three steps before the dump.

- [ ] 12.1 `StatefulAgent.tearDown()`: add an overridable protected step (default no-op) invoked **immediately before** `safeStep("saveGraph", …)` at `:1699`; `SataAgent` overrides it with the `getCoverageTracker().dump(...)` call currently at `:290-291`, keeping its `mopReach` predicate (which only `SataAgent` can supply). Remove the call from its old position — one call site, no dual path (P3) (INV-COV-10). Note the chain is `llmSummary → superTearDown → saveGraph → …` (`:1694-1704`), so this step lands **third, not first** — "before the model serialization" is the property that recovers the 333/338 and the one INV-COV-10 states
- [ ] 12.2 No idempotence flag: with a single call site there is nothing to guard. Do NOT port the atomic once-per-run flag from the withdrawn design
- [ ] 12.3 JVM unit test: the teardown chain invokes the dump before `saveGraph`; a subclass that does not override emits nothing and the chain still completes

## 13. O4 — `patched=<bool>` on `[APE-STEP]` (~10 LOC)

- [ ] 13.1 `GUITreeNode`: add a `patchedClickable` boolean, default `false`, set to `true` at the two `patchGUITree` mutation sites (`GUITreeBuilder.java:286` for a child made clickable, `:295` for a parent that loses clickability). Never inferred at emission time — post-patch attributes are indistinguishable from native ones by construction (INV-SEL-10)
- [ ] 13.2 `[APE-STEP]` emission: add `patched=0|1` from the action's resolved node, for actions with a target only; targetless actions (`MODEL_BACK`, `MODEL_MENU`, `MODEL_LLM_TAP`) omit the field
- [ ] 13.3 JVM unit tests: a child made clickable by the patch reports 1; a natively clickable node reports 0; the demoted parent reports 1; a targetless action emits no `patched` field

## 14. B7(i) — make the stagnation trigger fire (~5 LOC)

- [ ] 14.1 Replace exact equality with `>=` + per-episode fired flag at both sites: `SataAgent.java:436` and `LlmRouter.shouldRouteStagnation` (`LlmRouter.java:224-228`); flag re-arms when `graphStableCounter` resets to 0 (`StatefulAgent.java:1334-1345`) (INV-RTR-19)
- [ ] 14.2 JVM unit test on the predicate: fires once per episode; midpoint jump-over still fires; re-arms after a new edge

## 15. K10 — docs fix

- [ ] 15.1 `CLAUDE.md` (~line 128): correct `activityTriggerEnabled` default to `true` (matches `Config.java:165`); state current behavior only, no history (P4)

## 16. A3 — per-step counterfactual (~50–80 LOC + dedicated test)

Last group by dependency, not optional: it touches all four pick sites and must not perturb the seeded RNG stream, so it lands once the sites are otherwise stable. Task 16.4's seed-identity test is its merge gate.

- [ ] 16.1 Implement `cf_action`/`cf_changed` at the 4 pick sites (two short-circuits `SataAgent.java:575-587`/`:1544-1552`, two roulettes `:607`/`:1558`): recompute the pick with `mopBoost`+`mopFrontierBoost` zeroed, candidates and other boosts unchanged (INV-SEL-08)
- [ ] 16.2 RNG isolation: roulette counterfactual replays the factual pick's recorded draw as a fraction of total weight — zero additional draws from the live seeded stream (INV-SEL-09, INV-EXPL-14); short-circuit counterfactuals are deterministic
- [ ] 16.3 Serialize `cf_action`/`cf_changed` on the four channels' `[APE-STEP]` lines only; recomputation failure → `cf_changed=0`, selection unaffected
- [ ] 16.4 **Dedicated mandatory test**: fixed seed, counterfactual on vs off → identical selected-action sequence; plus per-site divergence cases and the all-MOP-weights-zero ⇒ `cf_changed=0` invariant

## 17. Verification

No manual emulator management ever — all smoke runs go through rv-platform on the rv-android side (`uv run rv-platform run` / `rv-experiment run`); this repo builds the jar only.

- [ ] 17.1 Build: `mvn package` succeeds; jar provenance stamp updated (build capability contract)
- [ ] 17.2 JVM unit suite green: `mvn test` — all pure-logic tests from groups 1–16 (Android-gated classes stay device-covered; no fake coverage claims)
- [ ] 17.3 Run /rv-test-run (or `mvn test` directly) and /rv-code-reviewer on the change set where they add value
- [ ] 17.4 Real smoke via rv-platform (rv-android side, real SGLang server, 2–3 APKs × ~5 min), gates:
  - (a) MOP-off arm (mop_data present, MOP weights zeroed): `decision_source=MOP` count == 0 AND `mop=` field always 0
  - (b) B1 ban observed firing: a dead-pair re-emission produces `result=no_match reason=dead_pair` → SATA fallback; bucket D ≈ 0 in the smoke telemetry
  - (c) every `[APE-STEP]` line carries `activity_has_mop` and `pick_channel`; every line with a resolved target carries `patched` (O4); **zero** newline-broken lines (A8)
  - (d) `[APE-LLM-ERROR] cause=screenshot` appears on a FLAG_SECURE APK (freeotpplus)
  - (e) **the coverage dump precedes `Save graph data to …` in the trace of a normally-completing run** (A10). *Revised 2026-07-31*: the old wording — "the dump exists after a timeout-killed run" — is **unpassable by construction** with a stdout-emitting dump, because the harness closes the capture file before the device can write anything more (design D9). What is testable is the ordering, which is the property that recovers the 333/338; whether a given smoke run happens to be killed at the right instant is not something a gate can require
  - (f) if A3 landed: `cf_changed==0` on every line of the MOP-off arm
- [ ] 17.5 `openspec change validate telemetry-proof-llm-efficacy` clean; artifacts coherent with the implemented state
