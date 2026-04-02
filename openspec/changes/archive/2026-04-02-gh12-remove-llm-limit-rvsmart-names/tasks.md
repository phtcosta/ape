## 1. Remove llmMaxCalls (ape)

- [x] 1.1 Remove `llmMaxCalls` from `Config.java`
- [x] 1.2 Remove `callCount` field, all 4 budget checks, and budget exhaustion log from `LlmRouter.java`. Replaced `callCount` reference in telemetry with `totalCalls`.
- [ ] 1.3 Remove `llm_max_calls` from `APERV_PROPERTY_MAPPING` in aperv-tool `tool.py` and from all variant configs — SEPARATE CHANGE in rv-android

## 2. Rename rvsmart prompt variants (ape + rv-android)

- [x] 2.1 Rename `rvsmart_v13` → `v13` and `rvsmart_v17` → `v17` in `ApePromptBuilder.java` (constants, method names, string literals)
- [x] 2.2 No references in `LlmRouter.java`
- [ ] 2.3 Rename variant keys in aperv-tool `tool.py` — SEPARATE CHANGE in rv-android
- [ ] 2.4 Rename in compose files — SEPARATE CHANGE in rv-android

## 3. Verify (ape repo)

- [x] 3.1 `mvn clean package` — BUILD SUCCESS, 274 tests, 0 failures (removed budget exhaustion test)
- [x] 3.2 `grep -r rvsmart` in ape src/ — zero matches
- [x] 3.3 `grep -r llmMaxCalls` in ape src/ — zero matches
