# Design: gh7 — Migrate LLM from Qwen3-VL-4B-Instruct to Qwen3.5-4B

**Date**: 2026-03-20
**Track**: FF SDD
**GitHub Issue**: [#7](https://github.com/phtcosta/ape/issues/7)

---

## Context

SGLang v0.5.9 broke multimodal for Qwen3-VL-4B-Instruct. Qwen3.5-4B is the replacement model, validated with 59.4% center hit rate on 468 screenshots. Three infrastructure changes are required: disable thinking mode, handle new coordinate malformation format, and support raw image mode.

The changes are contained within the `ape.llm` package (5 files). No architectural changes — same pipeline, same coordinate space, same fallback behavior. Two new Config flags control the new behavior with defaults matching Qwen3.5-4B requirements.

Reference: `rv-android/openspec/changes/gh43-aperv-llm-validation/exploration-sglang-qwen35.md`

---

## Goals / Non-Goals

**Goals:**
1. Make APE-RV work with Qwen3.5-4B on SGLang v0.5.9
2. Disable thinking mode by default (latency 2s vs 5-13s)
3. Handle `qwen3_coder` coordinate string format
4. Support raw image mode (no resize) for +12.8pp accuracy improvement
5. Maintain backward compatibility: all changes are opt-in via Config flags

**Non-Goals:**
- Prompt variant comparison (that is gh43 Track A Phase 2, branch `gh43-prompt-variants`)
- Enhanced telemetry logging (gh43 Track A Phase 2)
- Changing the coordinate normalization space (remains [0, 1000))
- Supporting other VLM models (single model focus)

---

## Architecture

No architectural changes. The existing pipeline remains:

```
ScreenshotCapture → ImageProcessor → ApePromptBuilder → SglangClient → ToolCallParser → CoordinateNormalizer → LlmRouter.mapToModelAction()
```

Changes are localized to 5 pipeline components + Config:

```
Config.java           ← 2 new flags: llmEnableThinking, llmImageResize
    ↓
SglangClient.java     ← adds chat_template_kwargs to request body
ImageProcessor.java   ← adds raw mode bypass (skip resize)
ToolCallParser.java   ← adds comma-separated string coordinate fix
ApePromptBuilder.java ← adds screen dimensions line to system message
```

### Key Components

| Component | Responsibility | Change |
|-----------|---------------|--------|
| `Config.llmEnableThinking` | Control thinking mode | New flag, default `false` |
| `Config.llmImageResize` | Control image resize | New flag, default `false` |
| `SglangClient.buildRequestBody()` | Build JSON request | Add `chat_template_kwargs` when thinking disabled |
| `SglangClient` constructor | Accept enableThinking param | New constructor parameter |
| `ImageProcessor.processScreenshot()` | Compress screenshot | Accept resize boolean, skip resize when false |
| `ToolCallParser.fixMalformedJson()` | Fix Qwen coordinate quirks | Add comma-separated string pattern |
| `SglangClient.putPrimitive()` | Store arg key/value pairs | Handle string-typed x with comma-separated coords |
| `ToolCallParser.buildParsedAction()` | Extract x,y from args | Unchanged (receives already-fixed values) |
| `ApePromptBuilder.buildSystemMessage()` | Generate system prompt | Add `"Screen: WxH pixels."` line with device dimensions |
| `LlmRouter` constructor | Wire Config flags | Pass `Config.llmEnableThinking` to SglangClient |
| `LlmRouter.selectAction()` | Run LLM pipeline | Pass `Config.llmImageResize` to ImageProcessor call |

---

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| SglangClient: chat_template_kwargs | `SglangClient.buildRequestBody()` | `SglangClientTest.testBuildRequestBodyThinkingDisabled` |
| SglangClient: enableThinking constructor | `SglangClient(...)` new param | `SglangClientTest.testBuildRequestBodyThinkingEnabled` |
| ImageProcessor: raw mode | `ImageProcessor.processScreenshot(bytes, resize)` | `ImageProcessorTest.testRawModeNoResize` |
| ToolCallParser: comma-separated string | `ToolCallParser.fixMalformedJson()` | `ToolCallParserTest.testFixCommaSeparatedStringCoords` |
| SglangClient: comma-separated string native | `SglangClient.putPrimitive()` | `SglangClientTest.testParseArgsObjectCommaSeparatedString` |
| ToolCallParser: comma-separated string in JSON text | `ToolCallParser.fixMalformedJson()` | `ToolCallParserTest.testFixCommaSeparatedStringCoords` |
| ApePromptBuilder: screen dimensions | `ApePromptBuilder.buildSystemMessage()` | `ApePromptBuilderTest.testSystemMessageContainsScreenDimensions` |
| Config: llmEnableThinking | `Config.java` | Tested via SglangClient integration |
| Config: llmImageResize | `Config.java` | Tested via ImageProcessor integration |
| INV-LLM-03: raw mode conditional | `ImageProcessor.processScreenshot(bytes, false)` | `ImageProcessorTest.testRawModePreservesDimensions` (skipped: Android runtime) |

---

## Decisions

### D1: Constructor parameter vs setter for enableThinking

**Decision**: Add `enableThinking` as a constructor parameter to `SglangClient`.

**Rationale**: Thinking mode is a session-level configuration, not something that changes per-request. Constructor injection is simpler than a setter and makes the field final. The constructor already has 7 parameters; adding one more is acceptable for a configuration class.

**Alternative**: Add a `setEnableThinking(boolean)` method. Rejected: adds mutable state unnecessarily.

### D2: ImageProcessor method signature change

**Decision**: Add a `resize` boolean parameter to `processScreenshot()`.

**Rationale**: The simplest change — one boolean parameter. The caller (`LlmRouter`) reads `Config.llmImageResize` and passes it through. No need for an overloaded method since the old single-param version would be dead code (P3: no backward compatibility shims).

**Alternative**: Read `Config.llmImageResize` inside `ImageProcessor`. Rejected: couples ImageProcessor to Config directly, making it harder to test.

### D3: Coordinate string fix location

**Decision**: Handle comma-separated string format in both `fixMalformedJson()` (for XML/inline JSON parsing) and `putPrimitive()` in `SglangClient` (for native tool calls).

**Rationale**: The `qwen3_coder` parser may produce the comma-separated format in either native tool_calls or in text content. Both paths need the fix. The `fixMalformedJson()` regex handles it in JSON strings; `putPrimitive()` handles it when the value arrives as a Java String via `parseArgsObject()` iteration — when `key` is `"x"` and `val` is a String containing a comma, split and store as separate x/y doubles.

### D4: Add screen dimensions to system message, keep [0, 1000) coordinates

**Decision**: Add a `"Screen: WxH pixels."` line to the system message with the actual device dimensions. Keep tool coordinates in [0, 1000) normalized space.

**Rationale**: Pre-validation used prompts with explicit screen dimensions ("Screen is 1080x1920 pixels") and achieved 59.4% center hit. The dimensions give the VLM spatial context for grounding without changing the coordinate convention. The coordinate conversion formula `pixel = int((qwen / 1000) * device_dim)` works correctly in both image modes. The `buildSystemMessage()` method already has access to `deviceWidth`/`deviceHeight` via the `build()` caller.

**Alternative**: Change to pixel coordinates (0-1080, 0-1920) in the prompt. Rejected: would require changing `CoordinateNormalizer`, `mapToModelAction()`, and the widget list format — a much larger change.

---

## API Design

### `SglangClient(baseUrl, model, temperature, topP, topK, maxTokens, timeoutMs, enableThinking)`

New parameter: `enableThinking` (boolean).
- When `false`: `buildRequestBody()` includes `"chat_template_kwargs": {"enable_thinking": false}`
- When `true`: field omitted from request body

### `ImageProcessor.processScreenshot(byte[] pngBytes, boolean resize)`

New parameter: `resize` (boolean).
- When `true`: existing behavior (resize to max-edge 1000px)
- When `false`: skip resize, compress to JPEG at original resolution
- Precondition: `pngBytes` non-null and non-empty (returns null otherwise)
- Postcondition: base64-encoded JPEG string

### `ToolCallParser.fixMalformedJson(String json)` — updated

New regex pattern added:
```java
// Pattern 4: "x": "498, 549"  →  "x": 498, "y": 549  (comma-separated string from qwen3_coder)
private static final Pattern FIX_STRING_COORDS =
        Pattern.compile("\"x\":\\s*\"\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\"");
```

Applied before existing patterns in the fix chain.

### `ApePromptBuilder.buildSystemMessage(boolean includeTypeText, int deviceWidth, int deviceHeight)`

Updated signature: accepts device dimensions.
- Inserts `"Screen: <W>x<H> pixels."` after the first line of the system message
- Coordinate space in tool descriptions remains `[0, 1000) normalized space`
- `deviceWidth`/`deviceHeight` are sourced from the `build()` method which already computes them from the GUITree root node

### `SglangClient.putPrimitive()` — updated

When storing tool call argument values, if `key` is `"x"` and `val` is a String containing a comma, split on comma and use as (x, y). This is where native tool call arguments arrive after `parseArgsObject()` iterates keys:

```java
// In putPrimitive: handle "x": "498, 549"
if ("x".equals(key) && val instanceof String) {
    String s = ((String) val).trim();
    if (s.contains(",")) {
        String[] parts = s.split(",");
        result.put("x", Double.parseDouble(parts[0].trim()));
        if (!result.containsKey("y") && parts.length >= 2) {
            result.put("y", Double.parseDouble(parts[1].trim()));
        }
        return;
    }
}
```

---

## Data Flow

```
1. LlmRouter.selectAction() called
2. ScreenshotCapture.capture() → PNG bytes
3. ImageProcessor.processScreenshot(pngBytes, Config.llmImageResize) → base64 JPEG
   - If llmImageResize=false: no resize, JPEG at 1080x1920
   - If llmImageResize=true: resize to max-edge 1000px
4. ApePromptBuilder.build(..., base64Image, ...) → messages
   - System message with [0,1000) coordinate space (unchanged)
   - User message with screenshot + widget list
5. SglangClient.chat(messages) → ChatResponse
   - Request includes chat_template_kwargs when enableThinking=false
   - Response parsed: tool_calls or text content
6. ToolCallParser.parse(response) → ParsedAction
   - Handles comma-separated string format from qwen3_coder
   - Falls back through native → XML → inline JSON
7. CoordinateNormalizer.normalize(action.x, action.y, ...) → pixel coords
8. LlmRouter.mapToModelAction(action, pixelCoords, ...) → ModelAction
```

---

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `chat_template_kwargs` rejected by SGLang | Older SGLang version | SglangClient returns null on non-200 | SATA fallback via LlmRouter |
| Comma-separated string parse failure | Unexpected string format | `getIntArg` returns defaultValue (0) | Falls through to coordinate (0,0) → likely no_match → SATA |
| Raw image too large for model context | 1080x1920 JPEG > token limit | Model returns truncated/empty response | ToolCallParser returns null → SATA |
| `enableThinking` ignored by non-Qwen model | Different model loaded | No impact — field is model-specific | Works normally, field ignored |

---

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Raw mode images may exceed SGLang context window | Pre-validation confirmed 1080x1920 JPEG works within 8192-token context |
| `chat_template_kwargs` is not part of OpenAI spec | It is an SGLang extension, documented in SGLang/Qwen3.5 docs; non-SGLang servers ignore unknown fields |
| Comma-separated string fix may match unrelated string patterns | Pattern is specific: only matches `"x": "digits, digits"` — unlikely false positive |
| Constructor/signature changes break existing code and tests | Single call site in LlmRouter constructor; existing `SglangClientTest` and `ImageProcessorTest` updated in same change |

---

## Testing Strategy

| Layer | What | How | Count |
|-------|------|-----|-------|
| Unit | `SglangClient.buildRequestBody()` with enableThinking true/false | Assert JSON contains/omits `chat_template_kwargs` | 2 |
| Unit | `SglangClient.putPrimitive()` with comma-separated string x | Known input/output pairs | 2 |
| Unit | `ToolCallParser.fixMalformedJson()` comma-separated string | Known input/output pairs | 3 |
| Unit | `ToolCallParser.parse()` end-to-end with string coords | Full parse pipeline | 1 |
| Unit | `ApePromptBuilder.buildSystemMessage()` screen dimensions | Assert `"Screen: 1080x1920 pixels."` present | 1 |
| Unit | `ImageProcessor.calculateResizedDimensions()` (existing) | Unchanged tests still pass | existing |
| Unit | Existing `SglangClientTest` / `ImageProcessorTest` | Update constructor calls and method signatures | update only |
| Integration | Smoke test: cryptoapp on device with Qwen3.5-4B | Run 1 min, verify tool calls succeed | 1 |
| **Total** | | | ~10 new + existing updated |

---

## Open Questions

_(none — all design decisions resolved based on pre-validation results)_
