## Purpose

On windows where the screenshot capture returns null (secure/`FLAG_SECURE`-style windows, where `ScreenshotCapture` silently returns null), `LlmRouter.selectAction()` today returns null without recording a circuit-breaker failure (`LlmRouter.java:245-249`), so it retries the LLM on every step for the whole run — wasted budget on apps that are 100% null (e.g. `securecamera_30`, `passvault_36`, `notesr_59`), turning the LLM arm into "SATA in disguise". This delta makes a null screenshot trip the breaker and short-circuit, so persistent null capture opens the breaker and stops retrying. It also clamps `Config.llmPercentage` to `[0,1]` to reject malformed probabilities. (Note: the literal `FLAG_SECURE` does not appear in source; the null is the observable signal, not a named code path.)

## Data Contracts

### Error
- All errors are handled internally by `LlmRouter.selectAction()` — no exceptions propagate to `StatefulAgent`.
- Network errors, unparseable responses, AND a null screenshot SHALL trigger `LlmCircuitBreaker.recordFailure()`. A persistent null-screenshot condition therefore opens the breaker and stops further LLM attempts for the recovery window.

## Invariants

- **INV-RTR-08**: `Config.llmPercentage` SHALL be clamped to the closed interval `[0.0, 1.0]` at load time. A configured value `< 0` SHALL become `0.0`; a value `> 1` SHALL become `1.0`.

## MODIFIED Requirements

### Requirement: Action Selection Pipeline

`LlmRouter.selectAction(GUITree tree, State state, List<ModelAction> actions, MopData mopData, List<ActionHistoryEntry> recentActions)` SHALL return a `ModelAction` or `null` by executing the following pipeline. On a null screenshot the method SHALL record a circuit-breaker failure and short-circuit (the behavior changed by this delta):

1. `totalCalls++` — counts this attempt regardless of outcome (per INV-RTR-07).
2. `ScreenshotCapture.capture(deviceWidth, deviceHeight)` → PNG bytes. If null → `breaker.recordFailure()`, log `[APE-RV] LLM screenshot capture failed, skipping LLM step`, return null. A persistent null-capture condition therefore opens the breaker and halts retries for the recovery window.
4. `ImageProcessor.processScreenshot(pngBytes)` → base64 JPEG. If null → return null.
5. `ApePromptBuilder.build(tree, state, actions, mopData, base64Image, recentActions)` → messages.
6. `SglangClient.chat(messages)` → `ChatResponse`. If IOException → `breaker.recordFailure()`, return null.
7. `ToolCallParser.parse(response)` → `ParsedAction`. If null → `breaker.recordFailure()`, return null.
8. `CoordinateNormalizer.normalize(parsedAction.x, parsedAction.y, deviceWidth, deviceHeight)` → pixel coords.
9. `mapToModelAction(pixelX, pixelY, parsedAction.actionType, parsedAction.text, actions)` → ModelAction or null.
10. `breaker.recordSuccess()`.
11. If ModelAction found → log `[APE-RV] LLM selected: <action>`, return the ModelAction.
12. If no match → log `[APE-RV] LLM no match at (<pixelX>,<pixelY>), SATA fallback`, return null.

**type_text handling**: When `mapToModelAction` finds a matching input widget and `parsedAction.text` is non-null, `selectAction()` calls `match.getResolvedNode().setInputText(text)` before returning. The caller receives a ready-to-execute ModelAction.

**Memory cleanup**: Steps 3-9 SHALL be wrapped in a `try-finally` block that nulls out `pngBytes`, `base64Image`, and `messages`.

**Error behavior**: Any step failure → log warning, record circuit breaker failure for network-related failures AND for a null screenshot, return null (SATA fallback).

#### Scenario: Full pipeline success
- **WHEN** `selectAction()` is called with a valid GUITree and SGLang is responsive
- **AND** the LLM returns `click` at normalized coordinates `(450, 300)`
- **AND** the nearest ModelAction's GUITreeNode bounds contain the pixel coordinates
- **THEN** that ModelAction SHALL be returned
- **AND** `breaker.recordSuccess()` SHALL be called

#### Scenario: No matching ModelAction (dynamic element)
- **WHEN** the LLM pipeline succeeds but `mapToModelAction()` returns null
- **THEN** `selectAction()` SHALL return null (SATA fallback)
- **AND** `breaker.recordSuccess()` SHALL be called (LLM worked, just no match)

#### Scenario: Screenshot capture fails trips the breaker
- **WHEN** `ScreenshotCapture.capture()` returns null (secure window)
- **THEN** `selectAction()` SHALL return null immediately
- **AND** no HTTP request SHALL be made
- **AND** `breaker.recordFailure()` SHALL be called
- **AND** after the breaker's failure threshold of consecutive null captures, `shouldRoute*()` SHALL return false (breaker OPEN), stopping per-step retries

#### Scenario: SGLang timeout
- **WHEN** `SglangClient.chat()` throws IOException due to timeout
- **THEN** `breaker.recordFailure()` SHALL be called
- **AND** `selectAction()` SHALL return null

## ADDED Requirements

### Requirement: Config — llmPercentage clamping

`Config.llmPercentage` SHALL be clamped to `[0.0, 1.0]` at load (`Config.java:153`). This rejects malformed probabilities: a value `> 1.0` would otherwise make `shouldRouteRandom()` always fire (since `random.nextDouble() < 1.5` is always true), and a negative value is meaningless.

#### Scenario: Value above 1 is clamped
- **WHEN** `ape.properties` sets `ape.llmPercentage=1.5`
- **THEN** `Config.llmPercentage` SHALL be `1.0`

#### Scenario: Negative value is clamped
- **WHEN** `ape.properties` sets `ape.llmPercentage=-0.2`
- **THEN** `Config.llmPercentage` SHALL be `0.0`

#### Scenario: In-range value unchanged
- **WHEN** `ape.properties` sets `ape.llmPercentage=0.7`
- **THEN** `Config.llmPercentage` SHALL be `0.7`
