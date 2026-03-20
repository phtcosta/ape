# gh7: Migrate LLM from Qwen3-VL-4B-Instruct to Qwen3.5-4B — Tasks

## 1. Configuration

- [x] 1.1 Add `llmEnableThinking` (boolean, default `false`) and `llmImageResize` (boolean, default `false`) to `Config.java`
  - File: `src/main/java/com/android/commands/monkey/ape/utils/Config.java`
  - Add after line 142 (existing `llmMaxCalls`)

## 2. SglangClient — Thinking Mode + Comma-Separated String Fix

- [x] 2.1 Add `enableThinking` field to `SglangClient`:
  - Add `private final boolean enableThinking` field
  - Update constructor to accept `enableThinking` parameter (8th param)
  - In `buildRequestBody()`: when `enableThinking` is `false`, add `"chat_template_kwargs": {"enable_thinking": false}` to the JSON body
- [x] 2.2 Handle comma-separated string coordinate format in `SglangClient.putPrimitive()`:
  - The fix goes in `putPrimitive()`, not `parseArgsObject()` — when `parseArgsObject()` iterates
    keys and calls `putPrimitive(result, key, val)`, the `val` for `"x"` arrives as a Java String
    `"498, 549"`. The `putPrimitive` currently stores it as-is (String). The fix: when `key` is `"x"`
    and `val` is a String containing a comma, split on comma, parse both parts as doubles, and also
    put `"y"` if not already present.
  - This handles `"x": "498, 549"` from `qwen3_coder` tool-call-parser via native tool calls
- [x] 2.3 Update existing `SglangClientTest.makeClient()` to pass 8th param (`false`):
  - All existing tests instantiate via `makeClient()` — update that single factory method
- [x] 2.4 Add new unit tests for SglangClient changes:
  - `testBuildRequestBodyThinkingDisabled`: verify `chat_template_kwargs` present
  - `testBuildRequestBodyThinkingEnabled`: verify `chat_template_kwargs` absent
  - `testParseArgsObjectCommaSeparatedString`: verify `"x": "498, 549"` parsed as x=498, y=549
  - `testParseArgsObjectCommaSeparatedStringWithSpaces`: verify whitespace handling
- [x] 2.5 Run `/sdd-test-run`

## 3. ToolCallParser — Comma-Separated String Fix

- [x] 3.1 Add `FIX_STRING_COORDS` regex pattern to `ToolCallParser.fixMalformedJson()`:
  - Pattern: `"x":\s*"\s*(\d+)\s*,\s*(\d+)\s*"` → `"x": $1, "y": $2`
  - Apply before existing fix patterns (string must be converted to numbers before other fixes run)
- [x] 3.2 Add unit tests for ToolCallParser comma-separated string fix:
  - `testFixCommaSeparatedStringCoords`: `"x": "498, 549"` → `"x": 498, "y": 549`
  - `testFixCommaSeparatedStringWithSpaces`: `"x": " 498 , 549 "` → `"x": 498, "y": 549`
  - `testFixCommaSeparatedStringPreservesOtherFixes`: existing malformed JSON fixes still work
  - `testParseNativeToolCallWithStringCoords`: end-to-end parse with native tool call containing string coords
- [x] 3.3 Run `/sdd-test-run`

## 4. ImageProcessor — Raw Mode

- [x] 4.1 Update `ImageProcessor.processScreenshot()` signature to accept `boolean resize`:
  - When `resize` is `false`: skip resize step, compress to JPEG at original resolution
  - When `resize` is `true`: existing behavior (resize to max-edge 1000px)
  - File: `src/main/java/com/android/commands/monkey/ape/llm/ImageProcessor.java`
- [x] 4.2 Update existing `ImageProcessorTest` @Ignore tests to use 2-param signature:
  - `processScreenshot(pngBytes)` → `processScreenshot(pngBytes, true)` in all 4 @Ignore tests
- [x] 4.3 Run `/sdd-test-run`

## 5. ApePromptBuilder — Screen Dimensions in System Message

- [x] 5.1 Update `ApePromptBuilder.buildSystemMessage()` to accept `int deviceWidth, int deviceHeight`:
  - Add `"Screen: <W>x<H> pixels.\n"` after the first line ("You are an Android UI testing agent...")
  - Coordinate space in tools remains `[0, 1000) normalized space`
  - Update the `build()` method to pass device dimensions to `buildSystemMessage()`
  - File: `src/main/java/com/android/commands/monkey/ape/llm/ApePromptBuilder.java`
- [x] 5.2 Add unit test for screen dimensions in system message:
  - `testSystemMessageContainsScreenDimensions`: verify `"Screen: 1080x1920 pixels."` present in output
- [x] 5.3 Run `/sdd-test-run`

## 6. Wire Changes in LlmRouter

- [x] 6.1 Update `LlmRouter()` constructor to pass `Config.llmEnableThinking` as 8th param to `new SglangClient(...)`
  - File: `src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java`, line 79-86
- [x] 6.2 Update `LlmRouter.selectAction()` to pass `Config.llmImageResize` to `imageProcessor.processScreenshot(pngBytes, Config.llmImageResize)`
  - File: `src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java`, line 239
  - Verify `imageProcessor` is a field of LlmRouter (not instantiated locally) — if local, the fix is at the call site only
- [x] 6.3 Update CLAUDE.md: add `llmEnableThinking` and `llmImageResize` to Config flags table

## 7. Build and Verification

- [x] 7.1 Run `mvn compile` — verify no compilation errors
- [x] 7.2 Run `/sdd-test-run` — all tests pass (including updated existing tests)
- [x] 7.3 Run `/sdd-qa-lint-fix`
- [x] 7.4 Run `/sdd-verify`
- [x] 7.5 Run `/sdd-code-reviewer`
- [x] 7.6 Package `ape-rv.jar` for Docker image rebuild
  - Copy compiled jar to the location referenced by rv-android Docker build
  - Note: Docker image rebuild (`phtcosta/rvandroid:X.X.X`) happens in rv-android, not in this repo

## 8. Downstream (out of scope — tracked in rv-android gh43 task 0A.5)

These tasks are NOT part of this APE change but are required for end-to-end integration:

- rv-android `aperv-tool` Python config must generate `ape.llmEnableThinking=false` and `ape.llmImageResize=false` in `ape.properties`
- Docker image `phtcosta/rvandroid` must be rebuilt with the new `ape-rv.jar`
- Smoke test on device: run cryptoapp with Qwen3.5-4B SGLang and verify tool calls work end-to-end
