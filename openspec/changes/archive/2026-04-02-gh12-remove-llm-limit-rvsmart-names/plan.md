# Plan: Remove llmMaxCalls limit + rvsmart references

GitHub Issue: #12

## Problem 1: llmMaxCalls silently stops LLM mid-run

`LlmRouter` checks `callCount < Config.llmMaxCalls` (default 200) in 4 places. With 70% LLM rate and 600s timeout, the limit is hit ~halfway through the run. The rest executes as pure SATA, defeating the purpose of LLM guidance.

Fix: Remove `llmMaxCalls` from Config, remove `callCount` field and all 4 checks from LlmRouter, remove budget exhaustion log.

## Problem 2: rvsmart in prompt variant names

`rvsmart_v13` and `rvsmart_v17` carry another tool's name. Rename to `prompt_v13` and `prompt_v17` everywhere.

## Files to modify

### APE-RV (ape repo)
- `Config.java`: remove `llmMaxCalls`, rename `llmPromptVariant` default if needed
- `LlmRouter.java`: remove `callCount`, `llmMaxCalls` checks in `shouldRouteNewState()`, `shouldRouteStagnation()`, `shouldRouteRandom()`, `selectAction()`; remove budget exhaustion log
- `ApePromptBuilder.java`: rename `rvsmart_v13` → `prompt_v13`, `rvsmart_v17` → `prompt_v17`

### aperv-tool (rv-android repo)
- `tool.py`: rename variant keys `sata_mop_llm_rvsmart_v13` → `sata_mop_llm_prompt_v13` (and v17)

### Compose/batch files (rv-android repo)
- Any compose files referencing `rvsmart_v13`/`rvsmart_v17`
- Any batch/filter files
