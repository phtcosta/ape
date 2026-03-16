<!-- Subagent dispatch hints:
     - Groups 1, 2 are independent and can run in parallel.
     - Group 3 depends on Group 2 (uses SglangClient.Message type).
     - Group 4 depends on Groups 2, 3 (uses all infra + prompt builder).
     - Group 5 depends on Group 4 (hooks LlmRouter into StatefulAgent/SataAgent).
     - Group 6 is final verification — must run after all other groups.
     - Critical path: 2 -> 3 -> 4 -> 5 -> 6. -->

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
- [ ] 3.2 Implement system message with tool schema declaration: `click(x, y)`, `type_text(x, y, text)`, `back()` — no scroll, no long_click. Include MOP explanation: "Elements marked [DM] or [M] reach operations monitored by runtime verification specifications. Prefer exploring these when they haven't been visited yet." Use [DM] (direct monitored) and [M] (transitive monitored) compact notation matching rvsmart V17. (ref: `specs/llm-prompt/spec.md` — System Message requirement)
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
  - **back handling**: if actionType="back", return backAction directly
  - **type_text handling**: if actionType="type_text", find nearest EditText/input action; text parameter available for input generation
- [ ] 4.5 Implement LLM telemetry: (a) per-call structured log `[APE-RV] LLM iter=N mode=X tokens_in=N tokens_out=N time_ms=N result=X` — extract tokens from ChatResponse.usage (prompt_tokens, completion_tokens); (b) cumulative counters in LlmRouter (totalCalls, tokensIn, tokensOut, totalTimeMs, modelActions, rawClicks, nulls, breakerTrips, callsByMode); (c) summary printed at tearDown; (d) decision ratio LLM vs SATA. (ref: `specs/llm-routing/spec.md` — LLM Telemetry Logging requirement)
- [ ] 4.6 Verify `mvn compile` succeeds with LlmRouter

## 5. Agent Integration: Hook LlmRouter into StatefulAgent and SataAgent

- [ ] 5.1 In `StatefulAgent`: add `_llmRouter` field (instantiate `LlmRouter` when `Config.llmUrl != null`, null otherwise). Add `_isNewState` field. In `updateStateInternal()`, capture `_isNewState = (newState.getVisitedCount() == 0)` BEFORE `getGraph().markVisited(newState, ts)` at line 618. Add action history tracking (ring buffer of last 5 actions with results). (ref: `specs/llm-routing/spec.md` — INV-RTR-01)
- [ ] 5.2 Hook new-state LLM mode at the top of `SataAgent.selectNewActionNonnull()`: check `_llmRouter != null && _llmRouter.shouldRouteNewState(_isNewState)`, call `selectAction()`, return if non-null (ref: `specs/llm-routing/spec.md` — New-State LLM Mode requirement)
- [ ] 5.3 Hook stagnation LLM mode: when `graphStableCounter > graphStableRestartThreshold / 2`, try LLM via `_llmRouter.shouldRouteStagnation(graphStableCounter)` + `selectAction()`. If LLM returns action, use it and reset graphStableCounter. If null, continue with normal stagnation/restart logic. (ref: `specs/llm-routing/spec.md` — Stagnation LLM Mode requirement)
- [ ] 5.4 Verify `mvn compile` succeeds with all agent integration hooks

## 6. Build, Documentation, and Verification

- [ ] 6.1 Run `mvn package` — verify `target/ape-rv.jar` is produced successfully (d8 converts all classes; no new Maven dependency)
- [ ] 6.2 Update `CLAUDE.md`: add `ape/llm/` to package map, add LLM config keys to configuration table, update project overview to mention LLM integration, update architecture diagram
- [ ] 6.3 Run `/sdd-qa-lint-fix` on new files in `ape/llm/` package
- [ ] 6.4 Run `/sdd-verify`
- [ ] 6.5 Invoke `/sdd-code-reviewer` via Skill tool
