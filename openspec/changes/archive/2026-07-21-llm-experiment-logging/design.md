# Design — LLM Experiment Logging

## Context

All three fixes are observability additions to the LLM path. None change action selection, routing decisions, or the LLM request payload. Output goes to stdout via `ape.utils.Logger` → the run's `.trace` (there is no dedicated LLM log file; lines are interleaved and tag-prefixed). The dependent variable for Phase 5 (coverage, MOP) stays in `.logcat`; this change makes the `.trace` self-describing (fix #1) and per-decision joinable (fix #2), and makes failure modes countable (fix #3).

## Fix #1 — `[APE-LLM-CONFIG]` manifest

**Where:** `LlmRouter` constructor (LlmRouter.java:88–104). It builds the `SglangClient` with `Config.llmUrl/llmModel/llmTemperature/llmTopP/llmTopK`, a hardcoded `1024` for `max_tokens` (line 96), and `Config.llmTimeoutMs` (line 97), then wires tools schema, breaker, screenshot, image processor, parser, and prompt builder (98–103). The manifest is emitted at the end of the constructor, after all wiring.

**Why not reuse `Config.printConfigurations()`:** the dump is unusable as the arm record for three reasons. (1) It echoes raw property strings, not effective values — `llmPercentage` is clamped to `[0,1]` in its `Config` field initializer (Config.java:204–205), so a run with `ape.llmPercentage=1.5` dumps `1.5` while running at `1.0`. (2) It runs only at `tearDown()`, so a run killed at the platform time limit records nothing. (3) The router-hardcoded `max_tokens=1024` is not a config key and never appears. (Note: the dump does echo defaulted keys — the two-arg getters write defaults back into the map — so "only explicitly-set keys" is NOT the problem; raw-vs-effective, timing, and the hardcoded value are.)

**Mechanism:** immediately after construction wiring, emit
```
[APE-LLM-CONFIG] model=<m> temperature=<t> top_p=<p> top_k=<k> max_tokens=<mt> timeout_ms=<ms> prompt_variant=<v> llm_percentage=<pct> on_new_state=<b> on_stagnation=<b> stagnation_threshold=<n> url=<u>
```
`max_tokens` is the literal `1024` the router passes (hoisted to a named local so the manifest and the client stay in sync). `timeout_ms` is `Config.llmTimeoutMs` — it shapes the `timeout` failure-cause rate, so an arm cannot be interpreted without it. `prompt_variant` comes from `ApePromptBuilder.getPromptVariant()` (a static read of `Config.llmPromptVariant`, resolvable at construction). `stagnation_threshold` is `Config.graphStableRestartThreshold` — the stagnation hook fires at `threshold / 2`, so this knob gates stagnation-mode LLM call frequency across arms. All values are `static final` `Config` fields (initialized after the properties files load) or construction-time constants; nothing is lazy or mutated later, so every field is resolved at emission time.

**Exactly once:** the router is constructed once per run — the only production site is StatefulAgent.java:181, reached via `Monkey` → `MonkeySourceApe` → `ApeAgent.createAgent` (one-shot; crash recovery rebuilds the model, not the agent) — and only when `Config.llmUrl != null` (INV-RTR-01). Unit tests construct routers directly and will emit the line in test output; harmless, but tests asserting on `Logger` output must account for it.

**Server-model acknowledgement (`[APE-LLM-CONFIG-ACK]`):** the manifest records the model the run *requested*; the ACK records what the server *served*. `parseResponse()` additionally extracts the OpenAI envelope's top-level `model` field into `ChatResponse` (nullable; absence is not a parse failure). At the `chat()`-success site, a boolean latch on the router emits `[APE-LLM-CONFIG-ACK] server_model=<m>` (`unknown` when the field is absent) once per run, on the first successful response (INV-RTR-12). A run with zero successful calls emits no ACK — that absence plus the cause counters is itself diagnostic.

## Step join key on per-call lines

`[APE-LLM-TEL]` and `[APE-LLM-ERROR]` gain `step=<N>`, closing the three-way deterministic join `[APE-LLM-TEL] step=N` ↔ `[APE-STEP] step=N` ↔ `[APE-OUTCOME] step=N` — per-call cost (tokens, latency) and failure cause become attributable to the decision and its outcome without line-order reconstruction. Mechanism: `selectAction()` gains a `step` parameter; the three `SataAgent` call sites (SataAgent.java:424/438/448) pass `getTimestamp()` — the same value the step's `[APE-STEP]` line will print, since the agent timestamp is constant across one selection (ApeAgent.java:336). `LlmRouter` stamps the parameter on its TEL/ERROR emissions and holds no agent reference. The breaker-OPEN line stays step-free (it is episode-scoped, not step-scoped).

## Fix #2 — `[APE-OUTCOME]` attribution

**The correlation problem:** `[APE-STEP]` is emitted in `resolveNewAction()` (StatefulAgent.java:1365–1375, model-action branch) at selection time with `step = getTimestamp()`. The agent timestamp increments exactly once per step at update-wrapper entry (ApeAgent.java:336), so by the time the resulting transition is recorded during the next step's `updateGraph()` (`model.addTransition` at StatefulAgent.java:981), `getTimestamp()` already reads N+1. A naive "emit outcome with current `getTimestamp()`" would key the outcome to the wrong step, breaking the join — the buffered selection step is genuinely required. (The `Graph` keeps an independent timestamp incremented per transition; the design uses only the agent timestamp.)

**Chosen mechanism — buffer the selection step, with four guards.** When the model-action `[APE-STEP]` line is emitted, buffer `lastDecisionStep = getTimestamp()` and `lastDecisionAction = newAction` (inside the same `stepTelemetryEnabled` block). The non-model `[APE-STEP]` branch (StatefulAgent.java:1377–1389, event-level actions) **clears** the buffer — non-model actions produce no owned transition, and `recoverCurrentState()` (StatefulAgent.java:934–967) can later re-install the last history action as `currentAction`, which would resurrect a stale buffer. Emission happens in `updateGraph()` immediately after `model.addTransition(...)` returns — placing it inside `Model`/`Graph` is forbidden, because the refinement rebuild replay re-records transitions through the `Graph.addTransition(GUITreeTransition)` overload (Model.java:283 → Graph.java:420) and must not emit. Guards:

1. **Non-null transition** — `addTransition` returns null on the run's first step, after restarts, and on the stale-ephemeral drop (Graph.java:398–399, 452–461); unguarded field reads would NPE on step 1 of every run.
2. **Reference match** — emit only when `lastDecisionAction == currentAction`. Recovery installs an older action object; non-model interludes leave a foreign `currentAction`. A mismatch means the transition does not belong to the buffered decision.
3. **Single-shot consumption** — clear the buffer on emission. The `BadStateException` retry (ApeAgent.java:343–351) re-enters `updateStateInternal` in the same step (timestamp not re-incremented) and runs `addTransition` again for the same action; without consumption this emits a duplicate `[APE-OUTCOME]` with the same `step`. Recovery re-records can duplicate the same way. "Overwritten at the next selection" alone is insufficient.
4. **Refinement remap** — `preEvolveModel()` runs before `updateGraph()` (StatefulAgent.java:711 vs 717) and, on refinement, `updateModel()` replaces `currentAction` with the rebuilt model's object (`currentAction = model.update(...)`, StatefulAgent.java:242–243). `updateModel()` must remap `lastDecisionAction` through the same mapping, or the reference guard silently drops the outcome on every refinement step — a systematic bias against exactly the non-deterministic steps the experiment cares about.

Fields: `step` = buffered selection step; `decision_source` = `currentAction.getDecisionSource().name()` (enum on `ModelAction`, populated on every selection path); `new_state` = `_isNewState` (set at StatefulAgent.java:714, before `updateGraph()` at 717 — refers to this transition's target); `target_state` = `newState.getStateKey()`; `activity_changed` = negation of the returned `StateTransition.isSameActivity()` (null-safe once guard 1 holds).

**Semantic caveat (documented in the spec):** fuzz piggyback events and bad-state `EVENT_ACTIVATE` interludes can execute after the selected action and before the next model update, so `target_state`/`new_state` reflect "state reached by the step", not "immediate post-action state". The `step` join stays exact.

**Rejected alternatives:**
- *Stamp the selection step onto `ModelAction`/`StateTransition`* — cleaner join key but adds a field to a core model class for pure telemetry. Rejected under P1 (the buffer needs the four guards either way; the stamp only replaces guard 2).
- *Key the outcome by `(source_state, action)` instead of `step`* — ambiguous when the same action repeats from the same state. Rejected.

**Gating:** buffer writes/clears and the `[APE-OUTCOME]` emission live inside `if (Config.stepTelemetryEnabled)` blocks. `apePureMode` forces `stepTelemetryEnabled=false` before `Config` field initializers run (Config.java:41–43 → `forceApePureModeInto`, key listed in `rvForcedOffValues()`), so pure mode emits zero `[APE-STEP]` and zero `[APE-OUTCOME]` lines (INV-ARCH-01 parity; forcing per the kill-switch requirement / INV-ARCH-06).

**Unpaired `[APE-STEP]`:** if a selected action produces no recorded transition (restart, first step, refinement discard), no `[APE-OUTCOME]` is emitted for it; the buffer is cleared or overwritten. Offline analysis treats an `[APE-STEP]` without a paired `[APE-OUTCOME]` as "selected, no clean transition" (INV-ARCH-09) — informative, not an error.

**Join-key dependency:** the `[APE-STEP] step=<N>` field was never pinned by a main spec (`action-selection` only mentioned `step#` in a scenario bullet). The action-selection delta defines the field name, source (`getTimestamp()` at selection), and per-run uniqueness.

## Fix #3 — Failure-cause classification

**The collapse point:** `SglangClient.chat()` (SglangClient.java:62–70) wraps the whole pipeline in `try { ... } catch (Exception e) { return null; }`. `sendRequest()` already distinguishes a non-200 `LlmException` (thrown at :147–148, but the status only lives in the message string) from a wrapped `IOException` (:163–164, with an `LlmException` rethrow-intact branch at :161–162), and `SocketTimeoutException` (a subclass of `IOException`) falls into the generic branch — timeout and connection-refused are indistinguishable, and everything collapses to null in `chat()`.

**Two failure layers — the causes do NOT all come from the client.** The client observes transport/HTTP/envelope failures (`parseResponse` at SglangClient.java:179–237 parses the OpenAI envelope inside `chat()`). But tool-call extraction (`ToolCallParser.parse`, never throws, returns null) runs in `LlmRouter` **after** `chat()` returned non-null — at that point the client's last-error seam is stale from some earlier call. Likewise, `ImageProcessor` failure (LlmRouter.java:270–275) and the router's catch-all (:432–435) never invoke `chat()`. Reading `getLastErrorCause()` anywhere except immediately after a `chat()` null return would misattribute; the design therefore splits attribution:

| Site (LlmRouter) | Failure | Attribution | Counter |
|------------------|---------|-------------|---------|
| :256–267 | screenshot null | router (existing line + counter; no `[APE-LLM-ERROR]`) | `screenshotFailedCount` |
| :270–275 | image processing null | router: `cause=image` | `imageErrorCount` |
| :295–302 | `chat()` returned null | **client seam**: `getLastErrorCause()` → `timeout` / `http_<status>` / `connection` / `parse` (envelope) | `timeoutCount` / `httpErrorCount` / `connErrorCount` / `parseErrorCount` |
| :310–317 | tool-call extraction null | router: `cause=parse` | `parseErrorCount` |
| :432–435 | unexpected exception | router: `cause=internal` | `internalErrorCount` |

The `chat()`-null site is the **only** reader of `getLastErrorCause()`, and `chat()` resets the field at entry of every invocation so staleness is structurally impossible (INV-LLM-08).

**Client mechanism (INV-LLM-01 preserved):**
1. In `sendRequest()`, catch `SocketTimeoutException` **before** the generic `IOException`, wrapping it as an `LlmException` tagged `timeout`; the generic branch tags `connection`. Add a numeric `statusCode` field to `LlmException` for the non-200 branch — without it `http_<status>` would require parsing the message string. The existing `catch (LlmException e) { throw e; }` rethrow-intact branches (sendRequest :161–162, parseResponse) already deliver tagged exceptions to `chat()`'s catch unwrapped.
2. In `chat()`, reset `lastErrorCause` at entry; the single `catch (Exception e)` classifies `e` (tagged `LlmException` → its cause; anything else during response handling → `parse`), stores the cause, and still returns null. `getLastErrorCause()` exposes it. No signature or null-contract change.

**Counter bookkeeping (INV-RTR-11):** the retired `nullCount` was incremented at all five abandoned-attempt sites, with `screenshotFailedCount` additionally incremented at the screenshot site (a subset, per the old summary semantics). The new scheme increments exactly one cause counter per abandoned attempt (screenshot increments `screenshotFailedCount` only), so the seven counters partition the retired `nullCount` and `decisions = matched + llm_tap + no_match + Σ(causes)` is value-identical to the old `matched + llm_tap + no_match + null`. `no_match` outcomes also return null from `selectAction()` but emit an `[APE-LLM-TEL]` line and stay in `noMatchCount` — they are outside the cause sum, which is why INV-RTR-11 is scoped to "attempts abandoned before the mapping step", not "attempts that returned null".

**Circuit-breaker-OPEN line:** the telemetry spec already requires `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` but no code emits it. Emitting on **every** declined predicate call would spam: at `llmPercentage=0.7` with a 60s open window and ~1–2 steps/s, one episode yields tens of identical lines. The delta requires emission at the **first** breaker-caused decline of each open episode (a latch reset when the breaker leaves OPEN). The check must use the side-effect-free `isOpen()` — a second `shouldAttempt()` call would trigger the OPEN→HALF_OPEN probe transition — and must distinguish breaker-caused declines from predicates returning false for other reasons (mode disabled, coin flip, stagnation not reached). `getTripCount()` supplies `<N>`.

**Summary:** `printSummary()` (LlmRouter.java:614–635) replaces `null=<N>` with `timeout=<N> http_error=<N> conn_error=<N> parse_error=<N> image_error=<N> internal_error=<N>`; keeps `screenshot_failed` and `breaker_trips`; the `decisions` denominator (currently `matchedCount + llmTapCount + noMatchCount + nullCount` at :620) becomes the named-counter sum, identical in value.

## Mapping table

| Change | File | Anchor |
|--------|------|--------|
| `[APE-LLM-CONFIG]` emission + named `max_tokens` local | `LlmRouter.java` | constructor, 88–104 |
| `[APE-LLM-CONFIG-ACK]` latch at first successful response | `LlmRouter.java` | `chat()`-success site, ~295–308 |
| `step` parameter on `selectAction()`; stamped on TEL/ERROR | `LlmRouter.java` + `SataAgent.java` | selectAction 221; call sites 424/438/448 |
| Discriminated counters replace `nullCount` | `LlmRouter.java` | field 71; sites 264/273/300/315/434 |
| `[APE-LLM-ERROR]` at failure sites (not screenshot) | `LlmRouter.java` | 272/299/314/433 |
| Breaker-OPEN line, once per episode, side-effect-free check | `LlmRouter.java` | predicates 171–200 + a latch |
| Summary per-cause breakdown | `LlmRouter.java` | `printSummary()` 614–635 |
| `SocketTimeoutException` before `IOException`; cause tags | `SglangClient.java` | `sendRequest()` 129–170 |
| Reset + classification + `getLastErrorCause()` | `SglangClient.java` | `chat()` 62–70 |
| Envelope `model` extracted into `ChatResponse` | `SglangClient.java` | `parseResponse()` 179–237; `ChatResponse` 352–370 |
| `statusCode` field | `LlmException.java` | whole class (6–15) |
| Buffer write at model `[APE-STEP]`; clear at non-model branch | `StatefulAgent.java` | 1365–1375 / 1377–1389 |
| `[APE-OUTCOME]` after `addTransition`, guards 1–3 | `StatefulAgent.java` | `updateGraph()` 969–984 |
| Buffer remap on refinement | `StatefulAgent.java` | `updateModel()` 237–256 |
| Stale prompt-variant key name in comment (`ape.llm.prompt_variant` → `ape.llmPromptVariant`) | `ApePromptBuilder.java` | :44 (P4 current-state comment) |

## Error handling & invariants

- INV-LLM-01 preserved: `chat()` still never throws and still returns null; classification is inside the existing catch; the cause is reset per invocation (INV-LLM-08).
- INV-RTR-02 preserved: `selectAction()` still returns null on failure; the error lines/counters are additive.
- INV-RTR-11: the seven cause counters partition the abandoned-before-mapping null returns; `no_match` is excluded by construction (it emits `[APE-LLM-TEL]`).
- INV-ARCH-08 / INV-ARCH-01: `[APE-OUTCOME]` gated by `stepTelemetryEnabled`; pure mode emits zero lines (forcing per the kill-switch requirement / INV-ARCH-06).
- INV-ARCH-09: reference guard + single-shot consumption + refinement remap + non-model buffer clear make the join exact — every `[APE-OUTCOME]` pairs with the same-step `[APE-STEP]`, at most one outcome per step.

## Validation

No unit-test harness runs on-device paths; validation is: (1) `mvn package` builds; (2) a standalone `--ape sata` run against `test-apks/cryptoapp.apk` with `ape.llmUrl` set produces exactly one `[APE-LLM-CONFIG]` line carrying all twelve fields and exactly one `[APE-LLM-CONFIG-ACK]` line after the first successful call; every `[APE-LLM-TEL]` line carries a `step` that matches an `[APE-STEP]` line; `[APE-STEP]`/`[APE-OUTCOME]` lines join by `step` with no duplicate `[APE-OUTCOME]` step values and every `[APE-OUTCOME]` step present among `[APE-STEP]` steps; stopping the SGLang server mid-run produces `[APE-LLM-ERROR] step=<N> cause=connection` (or `timeout`) lines and a summary whose cause counters sum consistently with the decision ratio denominator; (3) a run with `ape.apePureMode=true` produces zero `[APE-OUTCOME]` and zero `[APE-STEP]` lines.
