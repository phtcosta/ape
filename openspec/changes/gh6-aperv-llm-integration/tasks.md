<!-- ================================================================
     SUBAGENT DISPATCH STRATEGY
     ================================================================
     The main agent (orchestrator) should keep its context clean by
     dispatching implementation work to subagents. Each group below
     marks which tasks can be dispatched and which need orchestration.

     Parallelism:
       - Groups 1, 2 are INDEPENDENT → dispatch as 2 parallel subagents
       - Group 3 depends on Group 2 (uses SglangClient.Message type)
       - Group 4 depends on Groups 2, 3 (uses all infra + prompt builder)
       - Group 5 depends on Group 4 (hooks LlmRouter into agents)
       - Group 6 depends on Groups 2-5 (unit tests for new code)
       - Group 7 depends on Group 6 (integration tests with real data)
       - Group 8 is final verification — must run after all groups

     Critical path: 2 → 3 → 4 → 5 → 6 → 7 → 8

     Subagent dispatch:
       - Groups 1, 2: each a standalone subagent (parallel, isolated)
       - Groups 3, 4, 5: sequential subagents (each depends on previous)
       - Group 6: one subagent per test class (6.2-6.8 parallelizable)
       - Group 7: one subagent (integration tests, requires SGLang)
       - Group 8: orchestrator runs directly (verification, docs, review)

     NOTE: MOP scoring and LLM are COMPOSABLE, not exclusive.
     adjustActionsByGUITree() runs FIRST (base priority + MOP boosts),
     then LLM hooks run with the already-MOP-boosted action list.
     The LLM also receives [DM]/[M] markers in the prompt.
     ================================================================ -->

## 1. Configuration: MOP Weight Revert + LLM Config Keys

<!-- DISPATCH: standalone subagent, parallel with Group 2 -->

- [ ] 1.1 Revert MOP weight defaults in `Config.java`: `mopWeightDirect` 100→500, `mopWeightTransitive` 60→300, `mopWeightActivity` 20→100 (ref: `specs/mop-guidance/spec.md`)
- [ ] 1.2 Update documentation comments in `MopScorer.java` to reflect v1 defaults (500/300/100)
- [ ] 1.3 Add LLM config keys in `Config.java` (ref: `specs/llm-infrastructure/spec.md` — LLM Configuration Keys):
  - `llmUrl` (String, null) — SGLang base URL; null disables all LLM features
  - `llmOnNewState` (boolean, true) — enable new-state LLM mode
  - `llmOnStagnation` (boolean, true) — enable stagnation LLM mode
  - `llmModel` (String, "default") — model name for SGLang
  - `llmTemperature` (double, 0.3) — sampling temperature
  - `llmTopP` (double, 0.6) — nucleus sampling threshold
  - `llmTopK` (int, 50) — top-k sampling
  - `llmTimeoutMs` (int, 15000) — HTTP timeout in milliseconds
  - `llmMaxCalls` (int, 200) — max LLM calls per session
- [ ] 1.4 Verify `mvn compile` succeeds with Config changes

## 2. LLM Infrastructure: Copy rvsmart Classes (Gson→org.json conversion)

<!-- DISPATCH: standalone subagent, parallel with Group 1.
     Source: rvsec/rvsec-android/rvsmart/src/main/java/br/unb/cic/rvsmart/llm/
     Target: src/main/java/com/android/commands/monkey/ape/llm/
     Backup originals from rvsmart to backup/ before converting. -->

- [ ] 2.1 Create package `src/main/java/com/android/commands/monkey/ape/llm/`
- [ ] 2.2 Copy+convert `SglangClient.java`: rename package AND convert Gson→org.json (`JsonObject`→`JSONObject`, `JsonArray`→`JSONArray`, `Gson.toJson()`→`.toString()`, `Gson.fromJson()`→`new JSONObject()`) (ref: `specs/llm-infrastructure/spec.md`)
- [ ] 2.3 Copy `ScreenshotCapture.java`, rename package (no Gson — as-is)
- [ ] 2.4 Copy `ImageProcessor.java`, rename package (no Gson — as-is)
- [ ] 2.5 Copy+convert `ToolCallParser.java`: rename package AND convert Gson→org.json
- [ ] 2.6 Copy `CoordinateNormalizer.java`, rename package (no Gson — as-is)
- [ ] 2.7 Copy `LlmCircuitBreaker.java`, rename package (no Gson — as-is)
- [ ] 2.8 Copy `LlmException.java`, rename package (as-is)
- [ ] 2.9 Verify `mvn compile` succeeds with all 7 copied classes

## 3. Prompt Builder: ApePromptBuilder

<!-- DISPATCH: subagent, sequential after Group 2 (uses SglangClient.Message) -->

- [ ] 3.1 Create `ApePromptBuilder.java` with `build(GUITree, State, List<ModelAction>, MopData, String base64Image, List<ActionHistoryEntry> recentActions)` (ref: `specs/llm-prompt/spec.md`)
- [ ] 3.2 Implement system message with **dynamic tool schema**: `click(x,y)`, `long_click(x,y)`, `back()` always; `type_text(x,y,text)` only when input widgets present. Include MOP explanation and type_text hints in RULES.
- [ ] 3.3 Implement widget list: `[i] Class "text" @(normX,normY) [DM] (v:N)`. For input widgets with hint: `hint="..."` (truncated 30 chars). Coordinates in [0,1000) Qwen space.
- [ ] 3.4 Implement MOP marker annotation using `MopData.getWidget()` and `activityHasMop()`
- [ ] 3.5 Implement action history section (last 3-5 actions with results)
- [ ] 3.6 Implement exploration context (visit count, new state, MOP ratio)
- [ ] 3.7 Verify `mvn compile`

## 4. LLM Routing: LlmRouter

<!-- DISPATCH: subagent, sequential after Group 3 (uses all infra + prompt builder) -->

- [ ] 4.1 Create `LlmRouter.java` wiring all infrastructure. `callCount = 0`. (ref: `specs/llm-routing/spec.md`)
- [ ] 4.2 Implement 2 routing predicates:
  - `shouldRouteNewState(boolean isNewState)` — isNewState AND llmOnNewState AND breaker AND budget
  - `shouldRouteStagnation(int graphStableCounter)` — counter **==** threshold/2 (equality, single shot) AND llmOnStagnation AND breaker AND budget
- [ ] 4.3 Implement `selectAction()` → `ModelAction|null`: callCount++ at start → screenshot → image → prompt → chat → parse → normalize → mapToModelAction. For type_text: `resolvedNode.setInputText(text)` before returning. No match → log coords, return null. Memory cleanup in finally.
- [ ] 4.4 Implement `mapToModelAction()`: bounds containment → Euclidean fallback → boundary reject (top 5%, bottom 6%) → back/long_click/type_text handling
- [ ] 4.5 Implement telemetry: per-call log, cumulative counters (matched, noMatch, nulls, breakerTrips), summary at tearDown, decision ratio
- [ ] 4.6 Verify `mvn compile`

## 5. Agent Integration: Hook LlmRouter into StatefulAgent and SataAgent

<!-- DISPATCH: subagent, sequential after Group 4.
     NOTE: MOP+LLM compose — adjustActionsByGUITree() runs BEFORE LLM hooks.
     The LLM receives already-MOP-boosted priorities + [DM]/[M] in prompt. -->

- [ ] 5.1 In `StatefulAgent`: add `_llmRouter` (null when `Config.llmUrl == null`), `_isNewState`, `_lastState`, `_stateBeforeLast`, `ActionHistoryEntry` data class, ring buffer (max 5). In `updateStateInternal()`: shift history, capture `_isNewState = (visitedCount == 0)` BEFORE `markVisited()`. In `tearDown()`: `_llmRouter.printSummary()`.
- [ ] 5.2 Hook new-state at top of `SataAgent.selectNewActionNonnull()`, guarded by `actionBuffer.isEmpty() && actions.size() > 2 && _llmRouter != null && shouldRouteNewState(_isNewState)`. Return ModelAction if non-null.
- [ ] 5.3 Hook stagnation after new-state, same guards + `graphStableCounter == threshold/2`. Reset counter to 0 on success. `onGraphStable()` restart NOT modified.
- [ ] 5.4 Verify `mvn compile`

## 6. Unit Tests

<!-- DISPATCH: one subagent per test class (6.2-6.8 parallelizable after 6.1).
     Test fixtures at: src/test/resources/fixtures/cryptoapp/
     All tests use synthetic/mock data — no external dependencies. -->

- [ ] 6.1 Add JUnit dependency to `pom.xml` (test scope), create `src/test/java/com/android/commands/monkey/ape/llm/`
- [ ] 6.2 `ToolCallParserTest` (~10 cases): native format, XML tag, inline JSON, Qwen3-VL fixes (missing y, array, leading zero, truncated), type_text, long_click, all-fail→null
- [ ] 6.3 `CoordinateNormalizerTest` (~5 cases): center, edge clamping, zero, various dimensions
- [ ] 6.4 `LlmCircuitBreakerTest` (~6 cases): CLOSED→OPEN, OPEN→HALF_OPEN, HALF_OPEN→CLOSED, HALF_OPEN→OPEN, success resets, concurrent access
- [ ] 6.5 `ApePromptBuilderTest` (~10 cases): widget list format, MOP markers, hint for input widgets, coordinates, history, text truncation, null MopData, empty history, dynamic tool schema (type_text present/absent), exploration context
- [ ] 6.6 `LlmRouterTest` (~12 cases): boundary reject (status bar, nav bar), bounds containment, Euclidean fallback, smallest-area tiebreaker, back shortcut, type_text+setInputText, long_click, no match→null, budget exhausted→null, callCount on all attempts, shouldRouteStagnation equality semantics
- [ ] 6.7 `ImageProcessorTest` (~3 cases): large resize, small no-resize, null→null
- [ ] 6.8 `SglangClientTest` (~4 cases): request JSON format, multimodal message, null response, timeout
- [ ] 6.9 Verify `mvn test` — all unit tests pass

**Unit test count: ~50 cases across 7 test classes.**

## 7. Integration Tests with Real Data (cryptoapp tuples)

<!-- DISPATCH: one subagent. Uses real cryptoapp screenshots + UIAutomator dumps.
     Fixtures: src/test/resources/fixtures/cryptoapp/ (5 tuples + MOP JSON)
     SGLang tests gated by env var SGLANG_URL (skipped when not set).

     Tuples (selected for diversity):
       001 — MainActivity, 3 nav buttons, no input
       004 — MessageDigest, algorithm dropdown (13 items), popup
       010 — MessageDigest, EditText focused, keyboard open, type_text
       015 — CryptographyActivity, AES Encrypt, 2 EditTexts, tabs, radio
       020 — CryptographyActivity, KeyPair dropdown (RSA/DSA/EC/DH)

     MOP data: src/test/resources/fixtures/cryptoapp/cryptoapp.apk.json -->

### 7.1 Offline Tests (no SGLang required — run in `mvn test`)

- [ ] 7.1.1 `PromptIntegrationTest` — load each of the 5 tuples (PNG + UIAutomator XML + MOP JSON), construct prompt via ApePromptBuilder, validate:
  - All interactive widgets from UIAutomator appear in widget list
  - [DM]/[M] markers present for MOP widgets (buttonGenerateHash, executeButton, etc.)
  - Hint included for EditTexts with hint text
  - type_text in tool schema when EditText present (tuples 010, 015), absent when not (001, 004, 020)
  - Coordinates in [0,1000) range and consistent with UIAutomator bounds
  - Prompt token count ≤ 2000 tokens (system + user combined, estimated by chars/4)
  - Activity name in `Screen "..."` header matches state JSON
- [ ] 7.1.2 `CoordinateMapIntegrationTest` — for each tuple, simulate LLM responses targeting known widget centers, validate mapToModelAction returns the correct widget:
  - Tuple 001: click center of "CIPHER" button → matches buttonCipher
  - Tuple 004: click center of "MD5" list item → matches MD5 TextView
  - Tuple 010: click center of "GENERATE HASH" → matches buttonGenerateHash
  - Tuple 015: click center of "EXECUTE" → matches executeButton
  - Tuple 015: type_text on inputEditText center → matches + setInputText called
  - Tuple 020: click center of "DSA" → matches DSA CheckedTextView
  - Boundary reject: click at (540, 30) on 1080x1920 → null (status bar)
- [ ] 7.1.3 `ImageProcessorIntegrationTest` — load each tuple PNG, process via ImageProcessor, validate:
  - Output is valid base64 string
  - Decoded JPEG has longest edge ≤ 1000px
  - Aspect ratio preserved

### 7.2 Live SGLang Tests (gated by `SGLANG_URL` env var — skipped in CI)

- [ ] 7.2.1 `SglangLiveTest` — for each of the 5 tuples, send real prompt to SGLang and measure:
  - **Parseable response**: ToolCallParser extracts a valid ParsedAction (not null)
  - **Valid action type**: actionType is one of click/long_click/type_text/back
  - **Coordinates in range**: x ∈ [0,1000), y ∈ [0,1000)
  - **type_text quality**: for tuples 010/015, if LLM chooses type_text, text is non-empty and looks semantically valid (not random chars)
  - **Latency**: per-call ≤ 10s (p95)
  - **Token counts**: prompt_tokens and completion_tokens extracted from response

### 7.3 Acceptance Criteria

| Metric | Threshold | Measured On |
|--------|-----------|-------------|
| Prompt construction | 5/5 tuples produce valid 2-message prompt | Offline (7.1.1) |
| Widget coverage | 100% interactive widgets from UIAutomator in widget list | Offline (7.1.1) |
| MOP annotation | [DM]/[M] present on all MOP widgets when MopData loaded | Offline (7.1.1) |
| Prompt size | ≤ 2000 tokens (estimated) per prompt | Offline (7.1.1) |
| Coordinate hit rate | ≥ 6/7 known-center clicks map to correct widget | Offline (7.1.2) |
| Image processing | 5/5 tuples produce valid base64 JPEG ≤ 1000px | Offline (7.1.3) |
| Parseable response | ≥ 4/5 tuples get valid ParsedAction from SGLang | Live (7.2.1) |
| type_text quality | ≥ 1/2 type_text responses have semantic text | Live (7.2.1) |
| Latency p95 | ≤ 10s per call | Live (7.2.1) |

## 8. Build, Documentation, Smoke Test, and Verification

<!-- DISPATCH: orchestrator runs directly (final gate). -->

- [ ] 8.1 `mvn package` — verify `target/ape-rv.jar` produced (d8 converts all classes)
- [ ] 8.2 Update `CLAUDE.md`: add `ape/llm/` to package map, LLM config keys, architecture diagram, integration test instructions
- [ ] 8.3 `/sdd-qa-lint-fix` on `ape/llm/`
- [ ] 8.4 **Standalone smoke test** (on emulator, no rv-platform):
  - 8.4.1 Without LLM: `adb shell ... --ape sata` on cryptoapp for 1 min → no regression, zero `[APE-RV] LLM` log lines
  - 8.4.2 With LLM: `adb shell ... --ape sata` + `ape.llmUrl=http://10.0.2.2:30000/v1` on cryptoapp for 5 min → `[APE-RV] LLM` entries in log, MOP boosts visible, LLM summary at tearDown
  - 8.4.3 Without SGLang running: same config as 8.4.2 but no server → 3 failures, circuit breaker OPEN, pure SATA continues
- [ ] 8.5 `/sdd-verify`
- [ ] 8.6 `/sdd-code-reviewer`
