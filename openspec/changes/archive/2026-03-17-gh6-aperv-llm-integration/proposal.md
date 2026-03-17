## Why

Experiment exp1+exp2 (169 APKs, 5 variants) showed that MOP-guided scoring works but 47% of APKs exhibit deterministic exploration — identical traces across repetitions because SATA is deterministic once all actions are visited. Integrating LLM (Qwen3-VL via SGLang) into the exploration loop can break these deterministic patterns by making semantically informed action choices at two key decision points: first visit to a new state, and early stagnation detection. Additionally, the experiment proved that MOP v1 weights (500/300/100) outperform v2 (100/60/20) by +1.00pp method coverage (p=0.031) and +3 violation types, so the defaults must be reverted. Ref: [phtcosta/ape#6](https://github.com/phtcosta/ape/issues/6).

## What Changes

- **Revert MOP weight defaults** from v2 (100/60/20) to v1 (500/300/100) in `Config.java` and `MopScorer` documentation
- **Add LLM infrastructure package** (`ape/llm/`): copy 7 classes from rvsmart (`SglangClient`, `ScreenshotCapture`, `ImageProcessor`, `ToolCallParser`, `CoordinateNormalizer`, `LlmCircuitBreaker`, `LlmException`) with package rename; `SglangClient` and `ToolCallParser` converted from Gson to org.json (already available in Android runtime, already used by 24 files in APE-RV)
- **Add LLM routing logic**: new `LlmRouter` class implementing 2 modes (new-state, stagnation) integrated into `SataAgent`
- **Add APE-specific prompt builder**: new `ApePromptBuilder` class that converts GUITree widget list + MOP markers + action history (last 3-5 actions) + per-action visited counts into multimodal LLM prompts; system message includes MOP explanation
- **Add LLM config keys** in `Config.java`: `llmUrl`, `llmOnNewState`, `llmOnStagnation`, `llmModel`, `llmTemperature`, `llmTopP`, `llmTopK`, `llmTimeoutMs`, `llmMaxCalls`
- **Fix visitedCount bug**: capture `isNewState` flag before `markVisited()` in `StatefulAgent.updateStateInternal()` so new-state detection is accurate

## Capabilities

### New Capabilities

- `llm-infrastructure`: HTTP client for SGLang/OpenAI-compatible endpoints, screenshot capture via SurfaceControl, image processing (PNG→JPEG+base64), tool-call response parsing with 3-level fallback, coordinate normalization, circuit breaker for fault tolerance, and typed LlmException; all JSON handling uses org.json (no new dependency)
- `llm-routing`: Two-mode LLM integration into the exploration loop — new-state mode (first visit to each non-trivial state, triggered before SATA chain, guarded by buffer-empty and actions>2) and stagnation mode (triggered once when `graphStableCounter == threshold/2`, single shot per stagnation phase) — with fallback to SATA when LLM is unavailable, times out, or `llmMaxCalls` budget is exhausted
- `llm-prompt`: APE-specific prompt construction that includes screenshot, structured widget list with per-action visited counts and input hints, MOP reachability markers (indicating monitored operations), and last 3-5 action history with results; tool schema exposes `click(x, y)`, `long_click(x, y)`, `type_text(x, y, text)`, and `back()` (no scroll — it doesn't benefit from LLM semantic understanding); coordinate mapping uses bounds containment first, Euclidean distance as fallback

### Modified Capabilities

- `mop-guidance`: Revert default weight values from v2 (100/60/20) to v1 (500/300/100) based on experimental evidence; no structural changes to scoring logic
- `exploration`: Capture `isNewState` flag before `markVisited()` in `updateStateInternal()`; add `_llmRouter` field and action history ring buffer to `StatefulAgent`; add 2 LLM hooks to `SataAgent` guarded by buffer-empty and actions>2 (new-state before SATA chain, stagnation at `graphStableCounter == threshold/2`)

## Impact

**APE-RV Java (this repo)**:
- `ape/utils/Config.java` — revert 3 MOP weight defaults + add 9 LLM config keys
- `ape/utils/MopScorer.java` — update documentation comments only (logic unchanged)
- `ape/agent/StatefulAgent.java` — capture `isNewState` flag before `markVisited()` in `updateStateInternal()`; add `_llmRouter` field
- `ape/agent/SataAgent.java` — 2 hooks: new-state LLM in `selectNewActionNonnull()`, stagnation LLM in stagnation check
- New package `ape/llm/` — 9 new classes (~1300 LOC total: 7 copied from rvsmart with Gson→org.json conversion + 2 new APE-specific classes: `LlmRouter`, `ApePromptBuilder`)

**Runtime dependency**:
- SGLang server at `10.0.2.2:30000` (emulator→host) or `host.docker.internal:30000` (Docker); LLM features degrade gracefully when server is unavailable (circuit breaker → SATA fallback)

**Build**:
- No new Maven dependency needed; org.json is available in Android runtime and already used throughout the codebase; HTTP uses `java.net.HttpURLConnection` (Android SDK built-in)

**Cross-repo (separate change)**:
- `aperv-tool/tool.py` in rv-android will need new variants (`sata_llm`, `sata_mop_llm`) — tracked separately
