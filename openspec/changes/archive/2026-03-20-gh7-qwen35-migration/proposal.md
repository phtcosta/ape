# Proposal: gh7 — Migrate LLM from Qwen3-VL-4B-Instruct to Qwen3.5-4B

**Date**: 2026-03-20
**Track**: FF SDD
**GitHub Issue**: [#7](https://github.com/phtcosta/ape/issues/7)

---

## Why

SGLang v0.5.9 (`lmsysorg/sglang:latest`, image 2026-02-23) has a regression that breaks multimodal processing for Qwen3-VL-4B-Instruct — images arrive corrupted to the model. The exp3 experiment (507 tasks, 169 APKs, 2026-03-17) ran with this broken version, contaminating results: 37.3% no_match rate and LLM variant worse than baseline (27.60% vs 28.35% method coverage, p=0.014).

Qwen3.5-4B (unified multimodal, no "-VL" suffix) works on SGLang v0.5.9 and was validated in pre-validation (468 screenshots, 2060 widgets, 28 apps): 59.4% center hit (vs 57.7% Qwen3-VL in Dec/2025), 81.8% bounds hit, 89.4% tool call rate, ~2.0s average latency.

Investigation: `rv-android/openspec/changes/gh43-aperv-llm-validation/exploration-sglang-qwen35.md`

## What Changes

- **SglangClient**: add `chat_template_kwargs: {"enable_thinking": false}` to request body; new config `ape.llmEnableThinking` (default: false). Qwen3.5-4B has thinking mode ON by default; without disabling, latency rises to 5-13s and accuracy drops.
- **ToolCallParser**: handle Qwen3.5-4B malformed coordinate format where `"x"` arrives as a comma-separated string `"498, 549"` (from `qwen3_coder` tool-call-parser). Without this fix, tool call rate drops from 85% to 30%.
- **ImageProcessor**: add raw mode (no resize) controlled by `ape.llmImageResize` (default: false). Raw mode outperformed max_edge by +12.8pp in pre-validation and eliminates the 3-space coordinate conversion problem.
- **ApePromptBuilder**: system message adds a `"Screen: WxH pixels."` line with actual device dimensions for VLM spatial context. Coordinate space remains [0, 1000) normalized.
- **Config.java**: two new flags — `llmEnableThinking` (boolean, default false), `llmImageResize` (boolean, default false).
- **docker-compose.sglang.yml**: already updated in `a4454c6` — pinned to v0.5.9 with Qwen3.5-4B configuration.

## Capabilities

### New Capabilities

_(none — no new capability specs needed)_

### Modified Capabilities

- `llm-infrastructure`: SglangClient adds `chat_template_kwargs` parameter to request body; ImageProcessor adds raw mode bypass; ToolCallParser handles comma-separated string coordinate format; two new Config keys.
- `llm-prompt`: ApePromptBuilder system message includes actual device dimensions when raw mode is active.

## Impact

| Area | Impact |
|------|--------|
| **Affected files** | `SglangClient.java`, `ToolCallParser.java`, `ImageProcessor.java`, `ApePromptBuilder.java`, `Config.java` |
| **Dependencies** | No new dependencies — uses existing `org.json`, `android.graphics` |
| **External systems** | SGLang server must load Qwen3.5-4B with `--tool-call-parser qwen3_coder --reasoning-parser qwen3 --attention-backend triton` |
| **Downstream** | rv-android `aperv-tool` config needs new keys: `ape.llmEnableThinking=false`, `ape.llmImageResize=false` |
| **Testing** | Unit tests for new parsing logic + raw mode; smoke test with cryptoapp on device |
| **Risk** | Low — changes are additive (new config keys with defaults matching new behavior), existing behavior preserved when flags toggled |
