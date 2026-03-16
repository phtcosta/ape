<!-- Subagent dispatch hints:
     - Groups 1, 2 are independent and can run in parallel.
     - Group 3 depends on Group 2 (uses SglangClient.Message type).
     - Group 4 depends on Groups 2, 3 (uses all infra + prompt builder).
     - Group 5 depends on Group 4 (hooks LlmRouter into StatefulAgent/SataAgent).
     - Group 6 depends on Groups 2, 3, 4, 5 (unit tests for all new code).
     - Group 7 is final verification — must run after all other groups.
     - Critical path: 2 -> 3 -> 4 -> 5 -> 6 -> 7. -->

## 1. Configuration: MOP Weight Revert + LLM Config Keys

- [ ] 1.1 Revert MOP weight defaults in `src/main/java/com/android/commands/monkey/ape/utils/Config.java`: `mopWeightDirect` 100→500, `mopWeightTransitive` 60→300, `mopWeightActivity` 20→100 (ref: `specs/mop-guidance/spec.md`)
- [ ] 1.2 Update documentation comments in `src/main/java/com/android/commands/monkey/ape/utils/MopScorer.java` to reflect v1 defaults (500/300/100)
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

- [ ] 2.1 Create package `src/main/java/com/android/commands/monkey/ape/llm/`
- [ ] 2.2 Copy+convert `SglangClient.java` from rvsmart: rename package to `com.android.commands.monkey.ape.llm` AND convert all Gson usage (`JsonObject`, `JsonArray`, `Gson.toJson()`/`fromJson()`) to org.json (`JSONObject`, `JSONArray`, `.toString()`/`new JSONObject()`) (ref: `specs/llm-infrastructure/spec.md` — SglangClient requirement)
- [ ] 2.3 Copy `ScreenshotCapture.java`, rename package (no Gson — copy as-is) (ref: `specs/llm-infrastructure/spec.md` — ScreenshotCapture requirement)
- [ ] 2.4 Copy `ImageProcessor.java`, rename package (no Gson — copy as-is) (ref: `specs/llm-infrastructure/spec.md` — ImageProcessor requirement)
- [ ] 2.5 Copy+convert `ToolCallParser.java` from rvsmart: rename package AND convert Gson to org.json (ref: `specs/llm-infrastructure/spec.md` — ToolCallParser requirement)
- [ ] 2.6 Copy `CoordinateNormalizer.java`, rename package (no Gson — copy as-is) (ref: `specs/llm-infrastructure/spec.md` — CoordinateNormalizer requirement)
- [ ] 2.7 Copy `LlmCircuitBreaker.java`, rename package (no Gson — copy as-is) (ref: `specs/llm-infrastructure/spec.md` — LlmCircuitBreaker requirement)
- [ ] 2.8 Copy `LlmException.java`, rename package (simple RuntimeException subclass — copy as-is) (ref: `specs/llm-infrastructure/spec.md`)
- [ ] 2.9 Verify `mvn compile` succeeds with all 7 copied classes (no new Maven dependency needed — org.json is available in Android runtime)

## 3. Prompt Builder: ApePromptBuilder

- [ ] 3.1 Create `src/main/java/com/android/commands/monkey/ape/llm/ApePromptBuilder.java` with `build(GUITree, State, List<ModelAction>, MopData, String base64Image, List<ActionHistoryEntry> recentActions)` method (ref: `specs/llm-prompt/spec.md`)
- [ ] 3.2 Implement system message with **dynamic tool schema**: `click(x, y)`, `long_click(x, y)`, `back()` always present; `type_text(x, y, text)` included only when the actions list contains at least one input-capable widget (EditText, SearchView, AutoCompleteTextView). Include MOP explanation: "Elements marked [DM] or [M] reach operations monitored by runtime verification specifications. Prefer exploring these when they haven't been visited yet." Use [DM] (direct monitored) and [M] (transitive monitored) compact notation matching rvsmart V17. Include type_text hints in RULES: "Use type_text for input fields with valid data (email: user@example.com, password: Test1234!, domain: example.com, search: relevant term)." (ref: `specs/llm-prompt/spec.md` — System Message requirement, Dynamic Tool Schema)
- [ ] 3.3 Implement widget list generation: `[i] Class "text" @(normX,normY) [DM] (v:N)`. Coordinates in [0,1000) Qwen normalized space (convert device pixel center via `normX=(centerX/deviceWidth)*1000`). Compact format: `(v:N)` for visited count, `(key)` for non-target actions. (ref: `specs/llm-prompt/spec.md` — Widget List Generation requirement)
- [ ] 3.4 Implement MOP marker annotation using `MopData.getWidget()` and `activityHasMop()` (ref: `specs/llm-prompt/spec.md` — MOP Marker Annotation requirement)
- [ ] 3.5 Implement action history section: last 3-5 executed actions with results, format: `- CLICK [i] Widget "text" → result` (ref: `specs/llm-prompt/spec.md` — Action History requirement)
- [ ] 3.6 Implement exploration context section (visit count, new state indicator, MOP action count) (ref: `specs/llm-prompt/spec.md` — Exploration Context requirement)
- [ ] 3.7 Verify `mvn compile` succeeds with ApePromptBuilder

## 4. LLM Routing: LlmRouter

- [ ] 4.1 Create `src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java` with constructor wiring all infrastructure components. Use `Config.llmModel` for model name, `Config.llmTopP` / `Config.llmTopK` for sampling parameters. Initialize `callCount = 0`. (ref: `specs/llm-routing/spec.md` — LlmRouter Lifecycle requirement)
- [ ] 4.2 Implement 2 routing predicates with circuit breaker and budget checks (ref: `specs/llm-routing/spec.md`):
  - `shouldRouteNewState(boolean isNewState)` — returns true if isNewState AND Config.llmOnNewState AND breaker allows AND callCount < Config.llmMaxCalls
  - `shouldRouteStagnation(int graphStableCounter)` — returns true if graphStableCounter > graphStableRestartThreshold/2 AND Config.llmOnStagnation AND breaker allows AND callCount < Config.llmMaxCalls
- [ ] 4.3 Implement `selectAction()` pipeline returning `LlmActionResult`: screenshot → image processing → prompt → chat → parse → normalize → mapToModelAction. If ModelAction found → `LlmActionResult(modelAction, ...)`. If no match (dynamic element) → `LlmActionResult(null, pixelX, pixelY, ...)` for raw click via MonkeyTouchEvent. Memory cleanup in finally block. Increment callCount. (ref: `specs/llm-routing/spec.md` — Action Selection Pipeline requirement)
- [ ] 4.4 Implement `mapToModelAction(int, int, String, String, List<ModelAction>)` with improved mapping (ref: `specs/llm-routing/spec.md` — Coordinate-to-ModelAction Mapping requirement):
  - **Bounds containment first**: if (pixelX, pixelY) falls within a widget's bounds, select it directly
  - **Euclidean distance as fallback**: only if coords don't fall within any widget bounds
  - **Proportional tolerance**: `max(50, min(nodeWidth, nodeHeight) / 2)` instead of fixed 50px
  - **Boundary reject**: if pixelY < deviceHeight*0.05 or pixelY > deviceHeight*0.94, return null (system UI rejection)
  - **back handling**: if actionType="back", return backAction directly
  - **long_click handling**: if actionType="long_click", prefer MODEL_LONG_CLICK action for matched widget; fall back to MODEL_CLICK if unavailable
  - **type_text handling**: if actionType="type_text", find nearest EditText/input action; call `resolvedNode.setInputText(text)` to inject LLM-provided text into APE's input event pipeline
- [ ] 4.5 Implement LLM telemetry: (a) per-call structured log `[APE-RV] LLM iter=N mode=X tokens_in=N tokens_out=N time_ms=N result=X` — extract tokens from ChatResponse.usage (prompt_tokens, completion_tokens); (b) cumulative counters in LlmRouter (totalCalls, tokensIn, tokensOut, totalTimeMs, modelActions, rawClicks, nulls, breakerTrips, callsByMode); (c) summary printed at tearDown; (d) decision ratio LLM vs SATA. (ref: `specs/llm-routing/spec.md` — LLM Telemetry Logging requirement)
- [ ] 4.6 Verify `mvn compile` succeeds with LlmRouter

## 5. Agent Integration: Hook LlmRouter into StatefulAgent and SataAgent

- [ ] 5.1 In `StatefulAgent`: add `_llmRouter` field (instantiate `LlmRouter` when `Config.llmUrl != null`, null otherwise). Add `_isNewState`, `_lastState`, `_stateBeforeLast` fields. In `updateStateInternal()`, shift state history (`_stateBeforeLast = _lastState`, `_lastState = currentState`), then capture `_isNewState = (newState.getVisitedCount() == 0)` BEFORE `getGraph().markVisited(newState, ts)`. Add `ActionHistoryEntry` data class and action history ring buffer (max 5). Result determination: `newState == _lastState` → "same"; `newState == _stateBeforeLast` → "previous screen"; else → "new screen". Add `_llmRouter.printSummary()` call in `tearDown()`. (ref: `specs/exploration/spec.md` — StatefulAgent LLM Router Integration, isNewState Capture, Action History Ring Buffer requirements)
- [ ] 5.2 Hook new-state LLM mode at the top of `SataAgent.selectNewActionNonnull()`: check `_llmRouter != null && _llmRouter.shouldRouteNewState(_isNewState)`, call `selectAction()`, return if non-null. When `LlmActionResult.text != null` and action targets input widget, call `resolvedNode.setInputText(text)`. (ref: `specs/exploration/spec.md` — SataAgent LLM New-State Hook; `specs/llm-routing/spec.md` — New-State LLM Mode)
- [ ] 5.3 Hook stagnation LLM mode in `SataAgent.selectNewActionNonnull()`, after the new-state check (5.2) and before the SATA chain: when `graphStableCounter > graphStableRestartThreshold / 2`, try LLM via `_llmRouter.shouldRouteStagnation(graphStableCounter)` + `selectAction()`. If LLM returns action, reset graphStableCounter to 0 and return the action. If null, fall through to SATA chain. Note: `graphStableCounter` is a `protected` field in `StatefulAgent`, accessible from `SataAgent`. `StatefulAgent.onGraphStable()` restart logic is NOT modified. (ref: `specs/exploration/spec.md` — SataAgent LLM Stagnation Hook; `specs/llm-routing/spec.md` — Stagnation LLM Mode)
- [ ] 5.4 Verify `mvn compile` succeeds with all agent integration hooks

## 6. Unit Tests for New Code

- [ ] 6.1 Add JUnit dependency to `pom.xml` (test scope only) and create test directory `src/test/java/com/android/commands/monkey/ape/llm/`
- [ ] 6.2 Write `ToolCallParserTest`: native format, XML tag format, inline JSON, Qwen3-VL malformed JSON fixes (missing y, array format, missing leading zero, truncated JSON), type_text extraction, long_click extraction, all-fail returns null
- [ ] 6.3 Write `CoordinateNormalizerTest`: center of display, edge clamping (negative, >1000), zero coords, various device dimensions
- [ ] 6.4 Write `LlmCircuitBreakerTest`: CLOSED→OPEN after 3 failures, OPEN→HALF_OPEN after timeout, HALF_OPEN→CLOSED on success, HALF_OPEN→OPEN on failure, success resets from any state
- [ ] 6.5 Write `ApePromptBuilderTest`: widget list format with MOP markers, coordinate normalization, action history with results, text truncation, null MopData, empty history omitted, long_click in tool schema, **dynamic tool schema (type_text present when EditText in actions, absent when no input widgets)**, type_text hints in system message
- [ ] 6.6 Write `LlmRouterTest`: boundary reject (status bar top 5%, nav bar bottom 6%), bounds containment matching, Euclidean distance fallback, smallest-area tiebreaker, back action shortcut, type_text with setInputText, long_click action type, no match returns raw click, budget exhausted returns null
- [ ] 6.7 Write `ImageProcessorTest`: large image resize (longest edge ≤ 1000), small image no resize, null input returns null
- [ ] 6.8 Write `SglangClientTest`: request JSON format, null response handling, timeout handling
- [ ] 6.9 Verify `mvn test` passes with all unit tests

## 7. Build, Documentation, and Verification

- [ ] 7.1 Run `mvn package` — verify `target/ape-rv.jar` is produced successfully (d8 converts all classes; no new Maven dependency)
- [ ] 7.2 Update `CLAUDE.md`: add `ape/llm/` to package map, add LLM config keys to configuration table, update project overview to mention LLM integration, update architecture diagram
- [ ] 7.3 Run `/sdd-qa-lint-fix` on new files in `ape/llm/` package
- [ ] 7.4 Run `/sdd-verify`
- [ ] 7.5 Invoke `/sdd-code-reviewer` via Skill tool
