## Why

The hybrid tool-call parser has two response paths with asymmetric robustness. The XML `<tool_call>` path runs the coordinate repair pipeline (`fixMalformedJson`: quoted_xy / array_xy / missing_y, plus the int-scan last resort) and degenerates 3 times in 41,522 calls (0.007%). The native `tool_calls` path never reaches that pipeline: `SglangClient.parseResponse` silently drops a malformed `arguments` string (`catch (Exception ignored)` → empty map), and `ToolCallParser.parse` Level 1 feeds the resulting map straight to `buildParsedAction`, where a missing or unparseable coordinate defaults to `(0,0)`. Measured over the 543-trace `cmp_llm_20260721` base run (Qwen3-VL-4B stock): 7,506 of 12,966 native-path calls (57.9%) collapse to `(0,0)` → `result=no_match reason=degenerate` — 99.96% of ALL degenerate no_match (7,506/7,509). The model is not failing; the ape's native argument extractor discards ~58% of native decisions, masking the base model's true decision on ~24% of responses. This blocks the rvsec calibration loop at the Fase-A→B boundary (gate G2).

Authoritative spec: `rvsec/rv-android/calibracao/j1_handoff.md` (J1a/J1b/J1c). Evidence: `calibracao/nomatch_decomposition.md` §2.1, `calibracao/nomatch_calls.csv`.

## What Changes

- **J1a (primary — parser fix, "unify" option)**: route native `tool_calls[].arguments` through the exact same JSON string intermediate + repair pipeline the XML path uses.
  - `SglangClient.ToolCall` gains a `rawArguments` field carrying the arguments exactly as they appeared in the response envelope (the raw string when `arguments` is a JSON string — kept even when unparseable; the object's `toString()` when it is a JSON object).
  - `ToolCallParser.parse` Level 1 builds the same `{"name":..., "arguments":...}` intermediate the XML path emits and parses it via the shared `parseJsonString` (fixMalformedJson → org.json → int-scan last resort), so native malformations get the same `repair=` treatment as XML ones. If the repaired parse still fails, fall back to the current map-based `buildParsedAction` (never worse than today).
- **J1b (expose-as-property, values unchanged)**: `ape.llmSnapTolerancePx` (euclidean snap floor, default `50`) and `ape.llmBoundaryTopPct` / `ape.llmBoundaryBottomPct` (boundary reject bands, defaults `0.05` / `0.94`) in `Config`, consumed by `LlmRouter.mapToModelAction`. Defaults reproduce today's hard-coded behavior bit-for-bit; the levers were analyzed and discarded (P4 §3, §7) and are exposed only so a future probe needs no jar rebuild.
- **J1c (expose-as-property, value unchanged)**: `ape.llmMaxTokens` (default `1024`) replacing the hoisted local in the `LlmRouter` constructor; still flows into both the `[APE-LLM-CONFIG]` manifest and the `SglangClient` request body from one value.
- New Config flags registered as **exempt** in the `apePureMode` RV-flag registry (INV-ARCH-06): LLM sub-params, inert when the LLM masters are forced off.
- **Telemetry grammar unchanged** (hard constraint, d90c1f4): `[APE-LLM-TEL]` field set, `[APE-LLM-ERROR]`, `[APE-LLM-CONFIG]`, summary line, emitter sites and firing events all stay as-is. The only observable telemetry delta is distributional: native-path calls now carry `repair=` tags and `matched`/`llm_tap` outcomes instead of `reason=degenerate`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `llm-infrastructure`: `SglangClient` ToolCall contract gains raw-arguments preservation (malformed native arguments are no longer silently dropped to an empty map); `ToolCallParser` Level 1 (native) SHALL run the same repair pipeline as Levels 2-3, with repair-form labeling; new Config keys `ape.llmMaxTokens`, `ape.llmSnapTolerancePx`, `ape.llmBoundaryTopPct`, `ape.llmBoundaryBottomPct` join the LLM configuration table (defaults identical to the current hard-coded values).
- `llm-routing`: `LlmRouter` constructor reads `max_tokens` from Config (same effective value, same manifest field); `mapToModelAction` boundary bands and euclidean snap floor read from Config (same effective values). No routing behavior change at defaults.

## Impact

- **Components**: `ape.llm.SglangClient` (ToolCall + parseResponse), `ape.llm.ToolCallParser` (Level 1 unification), `ape.llm.LlmRouter` (constructor + mapToModelAction constants), `ape.utils.Config` (4 new keys + rvExemptReasons registry entries).
- **Not touched**: `ApePromptBuilder` (this is a parser bug, not a prompt problem), snapping tolerance formula, boundary band values, `max_tokens` value, `[APE-LLM-TEL]` grammar, `llm_tap` synthesis, algorithmic fallback turn.
- **Downstream consumers**: rvsec calibration pipeline (`calibracao/decompose_nomatch.py`, `experimento-20260721/scripts/analyze_cmpv2_llm.py`) — protected by the grammar constraint; Fase B (`calb`) consumes the rebuilt `ape-rv.jar` via `:ro` bind-mount and needs back: jar path, sha256, bytecode diff audit confined to the native path + property plumbing.
- **Success criterion (anti-starvation, handoff §4)**: this change is promoted on NET coverage in Fase B, never on `degenerate`↓ alone. Offline acceptance: re-parse of post-fix traces shows `degenerate` ≈ 0 and native-path calls carrying `repair=`/`matched` like the XML path.
