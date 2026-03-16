## Context

APE-RV's exploration engine uses SATA (epsilon-greedy + graph navigation) with MOP-guided priority boosts (Phase 3). Experiment exp1+exp2 (169 APKs) showed that MOP v1 weights (500/300/100) outperform v2 (100/60/20), but 47% of APKs still exhibit deterministic exploration because SATA is deterministic once all actions are visited. This design integrates LLM (Qwen3-VL via SGLang) at two decision points to break deterministic patterns while preserving the proven SATA+MOP foundation. Ref: [proposal.md](proposal.md), [phtcosta/ape#6](https://github.com/phtcosta/ape/issues/6).

The rvsmart project (`rvsec-android/rvsmart/src/.../llm/`) already has a mature LLM infrastructure (~1543 LOC) that handles SGLang communication, screenshot capture, image processing, tool-call parsing, coordinate normalization, and circuit breaker fault tolerance. This design reuses those 7 classes via direct copy with package rename, following the P1 simplicity principle (no shared library for 2 consumers). `SglangClient` and `ToolCallParser` are converted from Gson to org.json during copy — APE-RV already uses org.json in 24 files, so this adds zero new Maven dependencies.

The prompt design incorporates lessons learned from rvsmart V17 and rvagent v17: dual-mode input (screenshot + structured widget list), action history to prevent amnesia, MOP markers for monitored operations, dialog handling instructions, and Qwen3-VL coordinate conventions ([0, 1000) normalized space).

## Architecture

```
StatefulAgent
  ├── updateStateInternal()
  │     └── [NEW] capture isNewState = (visitedCount == 0) BEFORE markVisited()
  │
  ├── adjustActionsByGUITree()
  │     ├── base priority (existing)
  │     └── MOP scoring (existing, weights reverted to v1)
  │
  ├── [NEW] LlmRouter (injected at construction when Config.llmUrl != null)
  │     ├── shouldRouteNewState(boolean isNewState) → bool
  │     ├── shouldRouteStagnation(int graphStableCounter) → bool
  │     └── selectAction(GUITree, State, List<ModelAction>, MopData, List<ActionHistoryEntry>) → LlmActionResult|null
  │           ├── ScreenshotCapture.capture()
  │           ├── ImageProcessor.processScreenshot()
  │           ├── ApePromptBuilder.build()
  │           ├── SglangClient.chat(messages)
  │           ├── ToolCallParser.parse(response)
  │           ├── CoordinateNormalizer.normalize()
  │           └── mapToModelAction(x, y, actionType, text, actions)
  │
  └── selectNewActionNonnull() [SataAgent]
        ├── [NEW] LLM new-state check (isNewState → before SATA chain)
        ├── SATA chain (existing: buffer → ABA → trivial → greedy)
        └── epsilon-greedy fallback (existing, unchanged)

checkStable() / onGraphStable()
  └── [NEW] LLM stagnation check (graphStableCounter > threshold/2 → before restart)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `LlmRouter` | Routing decisions for 2 LLM modes; owns SglangClient + circuit breaker lifecycle; enforces call budget | GUITree, State, actions, MopData, history, config flags | `LlmActionResult` or null (SATA fallback) |
| `ApePromptBuilder` | Converts APE data structures to multimodal LLM prompt with action history, MOP markers, and coordinate context | GUITree, State, MopData, base64Image, recentActions | `List<SglangClient.Message>` |
| `SglangClient` | HTTP client for SGLang OpenAI-compatible endpoint (org.json for JSON) | Messages (text + base64 image) | ChatResponse (content + tool calls) |
| `ScreenshotCapture` | Screenshot via SurfaceControl reflection | display width/height | PNG byte array or null |
| `ImageProcessor` | PNG → JPEG resize (max 1000px edge) + base64 encode | PNG bytes | base64 string |
| `ToolCallParser` | 3-level fallback parser (native/XML/JSON) with Qwen3-VL fixes | ChatResponse | ParsedAction (actionType, x, y, text) |
| `CoordinateNormalizer` | Qwen3-VL [0,1000) → device pixel conversion | Qwen coords + device dims | pixel coords |
| `LlmCircuitBreaker` | 3 failures → 60s block, half-open retry | success/failure signals | shouldAttempt() boolean |
| `LlmException` | Typed exception for internal SglangClient error handling | — | RuntimeException subclass |

## Mapping: Spec → Implementation → Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| llm-infrastructure: SGLang HTTP client (org.json) | `ape/llm/SglangClient.java` | Manual: SGLang responds to chat request |
| llm-infrastructure: Screenshot capture | `ape/llm/ScreenshotCapture.java` | Manual: PNG bytes non-null on emulator |
| llm-infrastructure: Image processing | `ape/llm/ImageProcessor.java` | Manual: base64 output ≤ expected size |
| llm-infrastructure: Tool call parsing (org.json) | `ape/llm/ToolCallParser.java` | Manual: parse native/XML/JSON responses |
| llm-infrastructure: Coordinate normalization | `ape/llm/CoordinateNormalizer.java` | Manual: pixel coords within device bounds |
| llm-infrastructure: Circuit breaker | `ape/llm/LlmCircuitBreaker.java` | Manual: breaker trips after 3 failures |
| llm-infrastructure: LLM exception type | `ape/llm/LlmException.java` | N/A (internal to SglangClient) |
| llm-routing: New-state mode | `LlmRouter.shouldRouteNewState()` + `SataAgent.selectNewActionNonnull()` | Manual: LLM called on first state visit |
| llm-routing: Stagnation mode | `LlmRouter.shouldRouteStagnation()` + stagnation check | Manual: LLM called when graphStableCounter > threshold/2 |
| llm-prompt: GUITree → prompt | `ape/llm/ApePromptBuilder.java` | Manual: prompt includes widget list + screenshot + history |
| llm-prompt: Coordinate → ModelAction mapping | `LlmRouter.mapToModelAction()` | Manual: bounds containment + Euclidean fallback verified in logs |
| mop-guidance: Weight revert v2→v1 | `ape/utils/Config.java` lines 128-130 | Manual: MOP boosts are 500/300/100 |

## Goals / Non-Goals

**Goals:**
- Break deterministic exploration in the 47% of APKs with identical traces
- Preserve SATA+MOP as the dominant strategy (LLM is punctual, not continuous)
- Revert MOP weights to experimentally proven v1 values (500/300/100)
- Expose calibrable parameters (11 APE + 3 MOP + 9 LLM) for Optuna optimization
- Graceful degradation: LLM unavailable → pure SATA+MOP behavior (identical to current)

**Goals (secondary):**
- Enable semantic clicks on dynamic elements invisible to UIAutomator (WebView content, custom views, canvas elements) — the LLM sees the screenshot and can identify visual elements that have no accessibility node

**Non-Goals:**
- LLM as primary exploration strategy (rvsmart already does this)
- Multi-turn LLM conversations or memory across steps
- Training or fine-tuning the LLM
- aperv-tool Python changes (separate change in rv-android)
- Calibration infrastructure (separate Optuna work)

## Decisions

### D1: Copy rvsmart classes vs. shared library

**Decision**: Copy 7 classes from `rvsmart/llm/` to `ape/llm/` with package rename (`br.unb.cic.rvsmart.llm` → `com.android.commands.monkey.ape.llm`). Convert `SglangClient` and `ToolCallParser` from Gson to org.json during copy.

**Rationale**: Only 2 consumers exist (rvsmart and ape-rv). A shared library would require a new Maven module, complicate the d8 build pipeline, and add a runtime dependency. The 7 classes are stable (no changes planned) and total ~1000 LOC. P1 (simplicity) favors copy over abstraction.

### D2: LLM selects action vs. LLM boosts priorities

**Decision**: LLM **selects** a specific action (returns ModelAction or null), it does NOT modify priority scores.

**Rationale**: MOP already handles priority boosting well. LLM's strength is visual understanding — it sees the screenshot and picks the most promising UI element. Translating visual reasoning into numeric priority deltas would lose information. When LLM selects, SATA priorities are irrelevant for that step; when LLM declines (null), SATA+MOP priorities apply unchanged.

### D3: Two modes instead of always-on

**Decision**: LLM is invoked only in 2 specific situations: new state (first visit) and stagnation (exploration stuck). Each mode is independently toggleable.

**Rationale**: LLM calls cost ~3-5 seconds each. Always-on would add 30-50 minutes to a 10-minute run. The 2 modes target the highest-value decision points: (1) first impression of a new screen where visual understanding matters most, (2) breaking out of stagnation where SATA's determinism is the problem. Expected cost: ~60-130 calls per 10-minute run → +3-11 minutes overhead. The original epsilon-LLM mode (5% random trigger) was removed because it wastes calls uniformly rather than concentrating them where stagnation evidence exists — LLMDroid (FSE 2025) proved that coverage-triggered guidance is significantly more efficient than probabilistic triggering.

### D4: LLM hook placement

**Decision**:
- **New-state**: At the top of `selectNewActionNonnull()`, before the SATA strategy chain, guarded by the `_isNewState` flag (captured before `markVisited()` in `updateStateInternal()`)
- **Stagnation**: In the stability check path, when `graphStableCounter > graphStableRestartThreshold / 2`, before the exploration reaches the restart threshold

**Rationale**: Follows the MOP integration precedent — minimal changes to existing control flow, guarded by config flags, null return falls through to existing behavior. The `isNewState` flag fix addresses a bug where `state.getVisitedCount() == 0` would never be true at the point of the hook because `markVisited()` has already been called.

### D5: Coordinate → ModelAction mapping strategy

**Decision**: Two-phase matching: (1) bounds containment — if LLM pixel coordinates fall within a widget's bounds rectangle, select it directly; if multiple widgets contain the point, select the smallest (most specific). (2) Euclidean distance fallback — if no bounds contain the point, find nearest widget within a proportional tolerance of `max(50, min(nodeWidth, nodeHeight) / 2)` pixels.

**Rationale**: Qwen3-VL has ~84% coordinate accuracy. Bounds containment is the most natural match — the LLM clicked "inside" the widget. Euclidean distance handles near-misses. Proportional tolerance ensures large widgets (buttons) accept more imprecision than small icons. When no match is found, falling back to SATA is safer than injecting a raw click that the Model cannot track.

### D6: MOP markers in LLM prompt

**Decision**: Include MOP reachability flags in the widget description sent to LLM using compact notation: `[DM]` for direct, `[M]` for transitive. System message explains: "Elements marked [DM] or [M] reach operations monitored by runtime verification specifications."

**Rationale**: Gives the LLM information about which widgets lead to code paths with monitored operations. The compact [DM]/[M] notation matches rvsmart V17's proven format. When static analysis data is not available (no MopData), markers are simply omitted.

### D7: org.json instead of Gson

**Decision**: Convert `SglangClient` and `ToolCallParser` from Gson to org.json when copying from rvsmart. No new Maven dependency.

**Rationale**: APE-RV already uses `org.json` extensively (24 files). The Android runtime provides `org.json` classes via `app_process`. Adding Gson would require solving JAR packaging in the d8 pipeline (shade plugin or separate push), risk version conflicts with Android's bundled Gson, and add ~250KB. The Gson→org.json conversion is straightforward (~200 lines, 1:1 API mapping: `JsonObject` → `JSONObject`, `Gson.toJson()` → `.toString()`, `Gson.fromJson()` → `new JSONObject()`).

### D8: type_text support

**Decision**: Include `type_text(x, y, text)` in the LLM tool schema. When the LLM suggests typing text, `mapToModelAction` finds the nearest input-capable widget (EditText, SearchView). The text parameter is available for the existing APE fuzzing/input mechanism.

**Rationale**: Many apps require text input (login, search, forms) to advance to deeper screens. Without type_text, the LLM can only click on input fields without generating meaningful text. Both rvsmart and rvagent include type_text as a core tool. The implementation reuses APE's existing input handling — the LLM provides the text content, APE handles the mechanical input event generation.

## API Design

### `LlmRouter()`

No-arg constructor. Created in `StatefulAgent` constructor when `Config.llmUrl != null`. Internally creates and wires all infrastructure components: `SglangClient` (with `Config.llmUrl`, `Config.llmModel`, `Config.llmTemperature`, `Config.llmTopP`, `Config.llmTopK`, maxTokens 1024, `Config.llmTimeoutMs`), `LlmCircuitBreaker` (default thresholds), `ScreenshotCapture`, `ImageProcessor`, `ToolCallParser`, `ApePromptBuilder`. All fields stored as final. `callCount` initialized to 0.

### `LlmRouter.shouldRouteNewState(boolean isNewState) → boolean`

**Precondition**: `Config.llmOnNewState == true`
**Returns**: `true` if `isNewState` is `true` AND circuit breaker allows attempt AND `callCount < Config.llmMaxCalls`.

### `LlmRouter.shouldRouteStagnation(int graphStableCounter) → boolean`

**Precondition**: `Config.llmOnStagnation == true`
**Returns**: `true` if `graphStableCounter > Config.graphStableRestartThreshold / 2` AND circuit breaker allows attempt AND `callCount < Config.llmMaxCalls`.

### `LlmRouter.selectAction(GUITree tree, State state, List<ModelAction> actions, MopData mopData, List<ActionHistoryEntry> recentActions) → LlmActionResult|null`

Returns an `LlmActionResult` which can be either a matched `ModelAction` (widget found in UIAutomator dump) or a **raw click** with device pixel coordinates (for dynamic elements invisible to UIAutomator — WebView content, custom views, canvas elements). Returns null only on pipeline failure.

**`LlmActionResult`**:
```java
class LlmActionResult {
    ModelAction modelAction;  // non-null if widget found in current state
    int pixelX, pixelY;       // device pixel coords (always set for click/type_text)
    String actionType;        // "click", "type_text", "back"
    String text;              // for type_text

    boolean isModelAction()   // modelAction != null — tracked by Model
    boolean isRawClick()      // modelAction == null — dynamic element, raw touch event
}
```

**Flow**:
1. Check budget: `callCount < Config.llmMaxCalls` (else return null)
2. `screenshot.capture(width, height)` → PNG bytes (null → return null)
3. `imageProcessor.processScreenshot(pngBytes)` → base64 JPEG
4. `ApePromptBuilder.build(tree, state, actions, mopData, base64Image, recentActions)` → messages
5. `client.chat(messages)` → ChatResponse (IOException → `breaker.recordFailure()`, return null)
6. `ToolCallParser.parse(response)` → ParsedAction (null → `breaker.recordFailure()`, return null)
7. `CoordinateNormalizer.normalize(x, y, deviceWidth, deviceHeight)` → pixel coords
8. `mapToModelAction(pixelX, pixelY, parsedAction.actionType, parsedAction.text, actions)` → ModelAction|null
9. `callCount++`; `breaker.recordSuccess()`
10. If ModelAction found → return `LlmActionResult(modelAction, pixelX, pixelY, actionType, text)`
11. If no match (dynamic element) → return `LlmActionResult(null, pixelX, pixelY, actionType, text)` — caller executes raw click via MonkeyTouchEvent
12. Memory cleanup in `finally`: null out pngBytes, base64Image, messages

**Raw click execution**: When `isRawClick()`, the caller injects a `MonkeyTouchEvent` at the device pixel coordinates. The action is NOT tracked as a `StateTransition` in the Model, but its effect (screen change, new GUITree) is captured in the next exploration cycle. This is analogous to APE's existing fuzzing mechanism which also injects untracked raw events.

### `ApePromptBuilder.build(...)` → `List<SglangClient.Message>`

Builds a multimodal message list:

**System message** — compact (~120 tokens, modeled on rvsmart V13 which proved effective):
```
You are an Android UI testing agent exploring an app.
DIALOG: If permission/error dialog visible, dismiss it first (click Allow/OK).
PRIORITY: [DM]/[M] elements > unvisited (v:0) > visited.
AVOID: status bar (top), navigation bar (bottom).
RULES: Don't click same position twice. Use type_text for input fields.
Tools (coordinates in [0,1000) normalized space):
  click(x, y) — tap element
  type_text(x, y, text) — type into field
  back() — press back
Respond with one JSON: {"name": "<action>", "arguments": {<args>}}
```

Design rationale: rvsmart V13 (~120 tokens) performs well with a compact system message. The compact format saves ~300 tokens per call vs verbose V17-style reasoning steps, reducing inference latency by ~0.5-1s. All essential info (dialog handling, priority, coordinate convention, tools, response format) fits in ~120 tokens.

**User message** — two content parts, compact text:
1. Image: base64 JPEG data URI (`data:image/jpeg;base64,...`)
2. Text: compact widget list + history + context

**Widget list** — center coords in Qwen [0,1000) normalized space (same space as LLM response):
```
Screen "MainActivity":
[0] BACK (key)
[1] MENU (key)
[2] Button "Encrypt" @(185,117) [DM] (v:0)
[3] EditText "Password" @(208,169) (v:3)
[4] TextView "Help" @(231,219) [M] (v:1)
```

Format: `[index] WidgetClass "text" @(normX,normY) [MOP] (v:N)`. Coordinates are in Qwen3-VL [0,1000) normalized space — the SAME space the LLM responds in. This is critical: rvagent converts device pixels → [0,1000) before building the prompt, ensuring input/output coordinate consistency. rvsmart has a mismatch (device pixels in prompt, [0,1000) in response) which we explicitly avoid.

Conversion: `normX = (int)((devicePixelX / deviceWidth) * 1000)`, `normY = (int)((devicePixelY / deviceHeight) * 1000)`.

**Action history** (last 3-5, with normalized coords and result — balanced detail):
```
Recent:
- click @(208,169) EditText "Password" → same
- type_text @(208,169) "test@mail.com" → same
- click @(185,117) Button "Login" → new screen
- back → previous screen
```

Each entry: action type + coords (in [0,1000) space) + widget context + result. Coords in the same space as the widget list orient the LLM about spatial history. type_text includes the text that was typed. Result (same/new screen/previous screen/no effect) informs whether to retry.

**Context** (one line):
```
NEW state. 2/4 MOP.
```
Or: `Visited 5x.`

### Concrete Example: DNS Hero App (type_text use case)

This illustrates why type_text is critical. The DNS Hero app shows a domain input field — SATA would generate random fuzzing text, but the LLM understands the visual context ("Type a domain here:") and generates a semantically valid input.

**Screenshot**: DNS Hero main screen with domain input field and search icon.

**Prompt user message text** (coords already in [0,1000) normalized space):
```
Screen "LoadingActivity":
[0] BACK (key)
[1] Button "PREFERENCES" @(828,70) (v:0)
[2] EditText "Domain" @(500,487) (v:0)
[3] ImageButton @(854,486) (v:0)

NEW state. 0/3 MOP.
```

Device 1080x1794. Conversions: PREFERENCES center (894,126) → (894/1080*1000, 126/1794*1000) = (828,70). Domain center (540,874) → (500,487). Search icon center (922,873) → (854,486).

**Expected LLM response** (in the same [0,1000) space as the prompt):
```json
{"name": "type_text", "arguments": {"x": 500, "y": 487, "text": "google.com"}}
```

The LLM understands that "Domain" expects a valid domain name, not random characters. After typing, a follow-up call would click the search icon [3] to trigger the DNS lookup — advancing the app to new screens that SATA alone might never reach.

### `LlmRouter.mapToModelAction(int pixelX, int pixelY, String actionType, String text, List<ModelAction> actions) → ModelAction|null`

**Phase 1 — Bounds containment**: If (pixelX, pixelY) falls within a widget's bounds, select it. If multiple widgets contain the point, select smallest area.

**Phase 2 — Euclidean distance fallback**: If no bounds contain the point, find nearest widget within `max(50, min(width, height) / 2)` pixel tolerance.

**Special types**: `"back"` → return backAction directly. `"type_text"` → filter to input-capable widgets, store text.

## Data Flow

```
Step N in exploration loop
  │
  ├─ GUITree captured (AccessibilityNodeInfo)
  ├─ State resolved (NamingFactory)
  ├─ [NEW] _isNewState = (newState.getVisitedCount() == 0)  ← BEFORE markVisited
  ├─ getGraph().markVisited(newState, ts)                     ← increments visitedCount
  ├─ adjustActionsByGUITree() → base priorities + MOP boosts
  │
  ├─ selectNewActionNonnull() [SataAgent]
  │   │
  │   ├─ [MODE 1: New State] if _isNewState && llmOnNewState
  │   │   └─ LlmRouter.selectAction(tree, state, actions, mopData, recentActions)
  │   │       ├─ capture screenshot → PNG
  │   │       ├─ process → JPEG base64
  │   │       ├─ build prompt (widgets + [DM]/[M] markers + history + image)
  │   │       ├─ SGLang HTTP POST → ChatResponse
  │   │       ├─ parse tool call → (actionType, x, y, text)
  │   │       ├─ normalize Qwen [0,1000) coords → device pixels
  │   │       └─ map to nearest ModelAction (bounds containment → Euclidean fallback)
  │   │   If non-null → return (skip SATA chain)
  │   │   If null → fall through to SATA
  │   │
  │   ├─ SATA chain: buffer → ABA → trivial → backward
  │   │
  │   ├─ selectNewActionEpsilonGreedyRandomly()
  │   │   ├─ check unvisited BACK/MENU
  │   │   └─ epsilon-greedy: 95% least-visited / 5% random (unchanged)
  │   │
  │   └─ handleNullAction() (emergency)
  │
  ├─ [MODE 2: Stagnation] checkStable() / onGraphStable()
  │   ├─ if graphStableCounter > threshold/2 && llmOnStagnation
  │   │   └─ LlmRouter.selectAction(...)
  │   │   If non-null → use action, reset graphStableCounter
  │   │   If null → continue stagnation logic
  │   ├─ if graphStableCounter > threshold → requestRestart() (existing)
  │   └─ else → continue
  │
  ├─ Record action in history ring buffer (for next prompt)
  └─ Execute chosen action → capture next GUITree → loop
```

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `IOException` / `SocketTimeoutException` | `SglangClient.chat()` — SGLang unreachable or slow | Log warning, `breaker.recordFailure()` | Return null → SATA fallback. After 3 failures, circuit breaker blocks for 60s. |
| `null` PNG bytes | `ScreenshotCapture.capture()` — SurfaceControl reflection fails | Log warning | Return null → SATA fallback. No circuit breaker trip (not a network error). |
| `null` ParsedAction | `ToolCallParser.parse()` — LLM response unparseable | Log warning, `breaker.recordFailure()` | Return null → SATA fallback. |
| No ModelAction within tolerance | `mapToModelAction()` — LLM targets dynamic element not in UIAutomator dump | Log info, return `LlmActionResult` with `isRawClick()=true` | Raw click executed via MonkeyTouchEvent; effect captured in next GUITree cycle. |
| `JSONException` | `SglangClient` — malformed JSON from SGLang | Caught inside SglangClient, returns null ChatResponse | Propagates as null → SATA fallback. |
| Circuit breaker OPEN | `LlmCircuitBreaker.shouldAttempt()` returns false | Skip LLM entirely | Pure SATA+MOP for 60s, then half-open probe. |
| Budget exhausted | `callCount >= Config.llmMaxCalls` | Skip LLM entirely | Pure SATA+MOP for remainder of session. |
| `OutOfMemoryError` | Accumulated screenshot bytes | `finally` block nulls pngBytes, base64Image, messages | Prevents accumulation; OOM triggers SATA fallback. |

## Risks / Trade-offs

**[Latency overhead]** → Each LLM call adds ~3-5s. With 2 modes active, expect +3-11 min on a 10-min run (~60-130 calls). Mitigation: circuit breaker + call budget + mode toggles.

**[Qwen3-VL coordinate accuracy ~84%]** → 16% of LLM actions may target wrong widget. Mitigation: bounds containment matching (primary) + Euclidean distance with proportional tolerance (fallback). When no match, SATA fallback (null return).

**[Memory pressure from screenshots]** → Each call produces ~500KB base64 data. Mitigation: explicit memory cleanup in finally block (null out pngBytes, base64Image, messages after each call).

**[Screenshot capture on API 29]** → SurfaceControl reflection may fail on some Android versions. Mitigation: UiAutomation fallback path exists in ScreenshotCapture. If both fail, LLM is skipped (null PNG → null action → SATA).

**[MOP weight revert breaks reproducibility of v2 experiments]** → Anyone running with default config will get v1 behavior. Mitigation: weights are configurable via `ape.properties`, so v2 can be restored per-experiment.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Build verification | All 9 new classes compile and d8 converts to Dalvik | `mvn package` succeeds | 1 check |
| Smoke test (no LLM) | APE-RV runs normally when `llmUrl` is null (no regression) | Run `--ape sata` on cryptoapp for 1 min | 1 run |
| Smoke test (LLM) | APE-RV invokes LLM on new states and during stagnation | Run `--ape sata` on cryptoapp for 5 min with SGLang running, check logs for `[APE-RV] LLM` entries | 1 run |
| Circuit breaker | LLM failures trigger breaker, SATA takes over | Run without SGLang, verify 3 failures → 60s block in logs | 1 run |
| MOP weight revert | MOP boosts are 500/300/100 | Run `sata_mop` on cryptoapp, check logs for `MOP boost ... maxBoost=500` | 1 run |
| Coordinate mapping | LLM coordinates map to correct ModelAction | Inspect logs for bounds containment and Euclidean matching entries | Manual inspection |
| Call budget | LLM stops after llmMaxCalls | Set `llmMaxCalls=10`, verify "budget exhausted" log appears | 1 run |

All tests are manual (on emulator) — no automated test suite exists in this project.

## LLM Telemetry and Metrics

LLM telemetry follows the `[RVTRACK:LLM]` pattern already established in rvsmart and rvagent, ensuring consistency across the RVSEC infrastructure.

### Per-Call Logging (structured, parseable)

Each LLM call emits a structured log line:
```
[APE-RV] LLM iter=47 mode=new-state tokens_in=1250 tokens_out=85 time_ms=3214 result=model_action widget=btn_encrypt
[APE-RV] LLM iter=52 mode=stagnation tokens_in=1180 tokens_out=92 time_ms=2890 result=raw_click coords=(540,874)
[APE-RV] LLM iter=60 mode=new-state tokens_in=1300 tokens_out=0 time_ms=15001 result=timeout
```

Fields: `iter` (step number), `mode` (new-state/stagnation), `tokens_in`/`tokens_out` (from SGLang response `usage` field), `time_ms` (wall clock), `result` (model_action/raw_click/null/timeout/breaker_open/budget_exhausted), optional `widget` or `coords`.

Token counts are extracted from the OpenAI-compatible response: `response.usage.prompt_tokens` and `response.usage.completion_tokens`.

### Aggregate Metrics (at tearDown)

`LlmRouter` maintains cumulative counters, printed during `StatefulAgent.tearDown()`:
```
[APE-RV] LLM Summary: calls=85/200 tokens_in=106250 tokens_out=7225 time_ms=272900 avg_ms=3211 model=62 raw=15 null=8 breaker_trips=1
[APE-RV] Decision ratio: LLM=77/600 (12.8%), SATA=523/600 (87.2%)
```

### What is NOT saved (by design)

- Raw prompts and responses — too large (~500KB+ per call with base64 images). A future debug flag could enable prompt/response saving for small runs, but this is out of scope for this change.
- Per-widget scoring details — already covered by MOP boost logging.

## Open Questions

1. **Screenshot resolution**: ScreenshotCapture in rvsmart uses configurable width/height. Should APE-RV use the device's native resolution or a fixed size? ImageProcessor resizes to max 1000px edge regardless, so native resolution is likely fine. rvagent optimizes to 704x1248 (multiple of 32 for Qwen3-VL) — consider if this improves accuracy.

2. **LLM model configuration**: The prompt is tuned for Qwen3-VL. If the model changes (e.g., to Gemma-3 or Fara-7B), the prompt and tool-call parsing may need adjustment. `Config.llmModel` allows changing the model name, but prompt/parser adaptations would require code changes. Defer model flexibility to a future change.

3. **type_text integration**: The LLM can suggest text via `type_text(x, y, "text")`, but the exact mechanism for injecting this text into APE's event system needs implementation. Options: (a) modify the selected ModelAction's input text, (b) use APE's existing fuzzing mechanism with the LLM-provided text, (c) generate a sequence of ApeKeyEvent characters. Option (b) is simplest.
