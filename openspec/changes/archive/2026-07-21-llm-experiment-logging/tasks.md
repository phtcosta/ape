## Tasks

### Group 1: Fix #1 — `[APE-LLM-CONFIG]` manifest (LlmRouter)

- [x] **T1**: In `LlmRouter` constructor (LlmRouter.java:88–104), hoist the hardcoded `1024` `max_tokens` into a named local/field so the manifest and the `SglangClient` share one value.
- [x] **T2**: At the end of the constructor, emit one `[APE-LLM-CONFIG] model=… temperature=… top_p=… top_k=… max_tokens=… timeout_ms=… prompt_variant=… llm_percentage=… on_new_state=… on_stagnation=… stagnation_threshold=… url=…` line reading the effective values (`Config.llmModel/llmTemperature/llmTopP/llmTopK`, the `max_tokens` local, `Config.llmTimeoutMs`, `ApePromptBuilder.getPromptVariant()`, `Config.llmPercentage`, `Config.llmOnNewState`, `Config.llmOnStagnation`, `Config.graphStableRestartThreshold`, `Config.llmUrl`). Satisfies INV-RTR-10.
- [x] **T3**: Fix the stale comment at ApePromptBuilder.java:44 — the config key is `ape.llmPromptVariant`, not `ape.llm.prompt_variant` (P4).

### Group 2: Fix #3 — Failure-cause classification (SglangClient + LlmException)

- [x] **T4**: Add a numeric `statusCode` field (with accessor) to `LlmException`; set it in the `sendRequest()` non-200 branch (SglangClient.java:147–148). Message-string interpolation alone cannot support `http_<status>` classification.
- [x] **T5**: In `SglangClient.sendRequest()` (129–170), catch `java.net.SocketTimeoutException` **before** the generic `IOException`, wrapping it as an `LlmException` tagged `timeout`. Keep the generic `IOException` branch as `connection`; keep the `LlmException` rethrow-intact branch (161–162) so tagged exceptions reach `chat()` unwrapped.
- [x] **T6**: In `SglangClient.chat()` (62–70): reset `lastErrorCause` at entry; in the existing `catch (Exception e)`, classify into `TIMEOUT`, `HTTP` + status, `CONNECTION`, or `PARSE` (envelope), store in `lastErrorCause`, and still `return null` (INV-LLM-01 preserved). Add a `getLastErrorCause()` accessor. Satisfies INV-LLM-08.
- [x] **T7**: In `parseResponse()` (179–237), extract the envelope's top-level `model` field into `ChatResponse` (nullable field + accessor; absence is not a parse failure).

### Group 3: Fix #3 + joins — counters, error/breaker lines, step key, ACK (LlmRouter)

- [x] **T8**: Replace the single `nullCount` field (LlmRouter.java:71) with `timeoutCount`, `httpErrorCount`, `connErrorCount`, `parseErrorCount`, `imageErrorCount`, `internalErrorCount` (keep `screenshotFailedCount`). Site mapping: 264 → `screenshotFailedCount` only (drop the former double-increment into an aggregate); 273 → `imageErrorCount`; 300 → read `client.getLastErrorCause()` (the ONLY site that may read it) → `timeoutCount`/`httpErrorCount`/`connErrorCount`/`parseErrorCount`; 315 → `parseErrorCount` (router-attributed, do NOT read the client seam); 434 → `internalErrorCount`.
- [x] **T9**: Add a `step` parameter to `selectAction()` (LlmRouter.java:221); pass `getTimestamp()` at the three `SataAgent` call sites (424/438/448); stamp `step=<N>` on every `[APE-LLM-TEL]` emission.
- [x] **T10**: Emit `[APE-LLM-ERROR] step=<step> cause=<cause> detail=<message>` at sites 272/299/314/433. The screenshot site keeps its existing `[APE-RV] LLM screenshot failed` line and emits no `[APE-LLM-ERROR]`.
- [x] **T11**: Emit `[APE-RV] LLM circuit breaker OPEN, skipping (trips=<N>)` at the **first** breaker-caused predicate decline of each open episode (latch reset when the breaker leaves OPEN). Use the side-effect-free `breaker.isOpen()` for the check — never a second `shouldAttempt()` call (OPEN→HALF_OPEN side effect) — and distinguish breaker-caused declines from other false predicate conditions.
- [x] **T12**: At the `chat()`-success site (~295–308), emit `[APE-LLM-CONFIG-ACK] server_model=<ChatResponse.model, or "unknown">` once per run via a boolean latch, on the first successful response. Satisfies INV-RTR-12.
- [x] **T13**: Update `printSummary()` (614–635): replace `null=<N>` with `timeout=<N> http_error=<N> conn_error=<N> parse_error=<N> image_error=<N> internal_error=<N>`; keep `screenshot_failed` and `breaker_trips`; the `decisions` denominator becomes the named-counter sum (value-identical to the old `+ nullCount`). Satisfies INV-RTR-11.

### Group 4: Fix #2 — `[APE-OUTCOME]` attribution (StatefulAgent)

- [x] **T14**: Add `lastDecisionStep` (int) and `lastDecisionAction` (ModelAction) buffer fields. In the model-action `[APE-STEP]` block (1365–1375), inside the existing `if (Config.stepTelemetryEnabled)`, set `lastDecisionStep = getTimestamp()` and `lastDecisionAction = newAction`. In the non-model branch (1377–1389), clear the buffer.
- [x] **T15**: In `updateGraph()` immediately after `model.addTransition(...)` (StatefulAgent.java:981), inside an `if (Config.stepTelemetryEnabled)` guard, emit `[APE-OUTCOME] step=<lastDecisionStep> decision_source=<currentAction.getDecisionSource().name()> new_state=<_isNewState> target_state=<newState.getStateKey()> activity_changed=<!transition.isSameActivity()>` ONLY when: the returned `StateTransition` is non-null, a decision is buffered, and `lastDecisionAction == currentAction`. Clear the buffer on emission (single-shot; guards the BadStateException retry and recovery re-record duplicate paths). Satisfies INV-ARCH-08/09.
- [x] **T16**: In `updateModel()` (StatefulAgent.java:237–256), remap `lastDecisionAction` through the same `model.update(...)` mapping applied to `currentAction`, so refinement steps keep their outcome attribution.

### Group 5: Build + validate

- [x] **T17**: `mvn package` — build must succeed, `target/ape-rv.jar` produced; `mvn test` — existing LlmRouter/SglangClient tests updated for the new counters, `selectAction` signature, manifest/ACK lines where they assert on Logger output.
- [ ] **T18** _(BLOCKED — needs emulator + SGLang/GPU; deferred per user constraint)_: Standalone run against `test-apks/cryptoapp.apk` with `ape.llmUrl` set: confirm exactly one `[APE-LLM-CONFIG]` line with all twelve fields and exactly one `[APE-LLM-CONFIG-ACK]` line after the first successful call; confirm every `[APE-LLM-TEL]` line carries a `step` matching an `[APE-STEP]` line; confirm `[APE-STEP]`/`[APE-OUTCOME]` join by `step` with no duplicate `[APE-OUTCOME]` step values and every `[APE-OUTCOME]` step present among `[APE-STEP]` steps; stop the SGLang server mid-run and confirm `[APE-LLM-ERROR] step=<N> cause=…` lines + per-cause summary breakdown consistent with the decision-ratio denominator.
- [ ] **T19** _(BLOCKED — needs emulator; deferred per user constraint)_: Run with `ape.apePureMode=true`: confirm zero `[APE-OUTCOME]` and zero `[APE-STEP]` lines (parity).

### Group 6: Sync specs

- [x] **T20**: On archive, sync the four delta specs to `openspec/specs/llm-routing/`, `openspec/specs/llm-infrastructure/`, `openspec/specs/scoring-pipeline/`, `openspec/specs/action-selection/` (via `/opsx:archive`). During the merge, place INV-RTR-10/11/12 into the llm-routing `## Invariants` section and INV-LLM-08 into the llm-infrastructure `## Invariants` section (the deltas carry them inline in the requirement bodies; the main specs keep invariants in a top-level section — do not let them drop).
