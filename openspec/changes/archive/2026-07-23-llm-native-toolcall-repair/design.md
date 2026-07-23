## Context

The J1 handoff (`rvsec/rv-android/calibracao/j1_handoff.md`, authoritative) localizes 99.96% of all `degenerate` no_match to the native tool-call path: 7,506 of 12,966 native calls collapse to `(0,0)` because the native argument extractor never runs the coordinate repair the XML path has. The loss happens in two stages, both currently invisible:

1. `SglangClient.parseResponse` (`src/main/java/com/android/commands/monkey/ape/llm/SglangClient.java:254-263`): when the envelope's `arguments` is a JSON **string** (the standard OpenAI encoding SGLang uses) and that string is malformed for org.json (dominant Qwen3-VL form: `{"x": 616, 891}` — missing `"y"` key), the `catch (Exception ignored)` swallows it and the `ToolCall` is built with an **empty map**. The malformed string is destroyed here — nothing downstream can repair what no longer exists.
2. `ToolCallParser.parse` Level 1 (`src/main/java/com/android/commands/monkey/ape/llm/ToolCallParser.java:85-88`): the native branch goes straight to `buildParsedAction(name, args, "none")`, bypassing `fixMalformedJson` and `lastResortIntScan` entirely. Missing keys default to `0` via `getIntArg` → `ParsedAction(x=0, y=0)` → boundary reject at `pixelY=0` → `[APE-LLM-TEL] result=no_match reason=degenerate`. The same happens for a *valid* JSON map carrying the quoted-collapsed form `{"x": "616, 891"}`: `Integer.parseInt("616, 891")` fails → default 0.

The XML path (Levels 2-3) routes everything through `parseJsonString` → `fixMalformedJson` (quoted_xy → array_xy → missing_y → cosmetic) with `lastResortIntScan` in the catch — and degenerates 3/41,522 (0.007%).

Hard constraints (handoff §3): the `[APE-LLM-TEL]` grammar (d90c1f4) is frozen — new fields additive only; snapping tolerance `max(50, min(w,h)/2)`, boundary bands 5%/94%, and `max_tokens=1024` keep their values (J1b/J1c expose them as config, defaults identical); `llm_tap` and the algorithmic fallback turn are preserved; the emitters at `LlmRouter.java:130-142, 388-391, 493-523, 719-737` keep firing on the same events. `ApePromptBuilder` is out of scope — this is a parser bug, not a prompt problem.

**Interaction with the open change `llm-toolcall-parse-recovery`** (commit bca8fc3, implemented, awaiting device gate + archive): that change added the quoted_xy fix, int-scan, and repair/repaired telemetry that this change reuses. Its delta specs modify the same `ToolCallParser — 3-Level Fallback Parser` and `LLM Telemetry Logging` requirements. This change's deltas are therefore written **on top of** the parse-recovery delta text, and `llm-toolcall-parse-recovery` MUST be synced/archived before this change is archived (archive ordering).

## Architecture

```
SGLang response envelope
        │
        ▼
SglangClient.parseResponse
        │  arguments string kept RAW on the ToolCall          ◄── new: rawArguments
        │  (parsed map still built best-effort, as today)
        ▼
ToolCallParser.parse
        │
        ├─ Level 1 (native): rebuild the XML-path intermediate  ◄── new: unification
        │     {"name": <quoted name>, "arguments": <rawArguments>}
        │     └─► parseJsonString ──► fixMalformedJson ──► org.json ──► buildParsedAction
        │                              │ (quoted_xy/array_xy/missing_y/cosmetic)
        │                              └─ catch ──► lastResortIntScan (click/long_click)
        │     null? ─► fallback: buildParsedAction(name, argsMap, "none")   (today's path)
        │
        ├─ Level 2 (XML <tool_call>): unchanged — same parseJsonString
        └─ Level 3 (inline JSON): unchanged — same parseJsonString
        │
        ▼
LlmRouter.selectAction — unchanged pipeline; repair= TEL field (INV-RTR-13) now
also populated by native-path repairs
LlmRouter.mapToModelAction — boundary bands + snap floor read from Config      ◄── J1b
LlmRouter constructor — max_tokens read from Config                            ◄── J1c
```

### Key Components

| Component | File | Change |
|---|---|---|
| `SglangClient.ToolCall` | `ape/llm/SglangClient.java` | new final `rawArguments` field + getter; constructor `(name, args, rawArguments)` |
| `SglangClient.parseResponse` | `ape/llm/SglangClient.java` | capture raw arguments (string as-is; object via `toString()`); keep best-effort map |
| `ToolCallParser.parse` Level 1 | `ape/llm/ToolCallParser.java` | route raw through shared `parseJsonString`; map-based fallback |
| `LlmRouter` constructor | `ape/llm/LlmRouter.java` | `int maxTokens = Config.llmMaxTokens` (was local `1024`) |
| `LlmRouter.mapToModelAction` | `ape/llm/LlmRouter.java` | bands `Config.llmBoundaryTopPct`/`llmBoundaryBottomPct` (were `0.05`/`0.94`); snap floor `Config.llmSnapTolerancePx` (was `50.0`) |
| `Config` | `ape/utils/Config.java` | 4 new keys + `rvExemptReasons` entries (INV-ARCH-06) |

## Mapping: Spec → Implementation → Test

| Spec item | Implementation | Test |
|---|---|---|
| INV-LLM-10 raw preservation | `SglangClient.parseResponse` / `ToolCall` | `SglangClientTest`: malformed args string → `rawArguments` non-null, verbatim |
| Native path runs shared repair (ToolCallParser req.) | `ToolCallParser.parse` Level 1 | `ToolCallParserTest`: native missing_y/quoted_xy/array_xy/int-scan cases → repaired coords + label |
| Native fallback never-worse | `ToolCallParser.parse` Level 1 | `ToolCallParserTest`: raw null / unrecoverable → today's map behavior |
| repair= on native decisions (INV-RTR-13 unchanged) | already in `LlmRouter.selectAction:508-511` | `LlmRouterTest`: native repaired call → TEL carries `repair=`, `repairedCount++` |
| J1b/J1c keys + defaults | `Config.java` | `ConfigTest`: defaults 1024/50/0.05/0.94 |
| INV-ARCH-06 registry | `Config.rvExemptReasons` | `ApePureModeKillSwitchTest`: 4 new exempt entries |
| Bands/floor/maxTokens from Config, default-identical | `LlmRouter` | `LlmRouterTest`: boundary reject and euclidean tolerance behavior identical at defaults; manifest carries `max_tokens=1024` |

## Goals / Non-Goals

**Goals**
- Eliminate the native-path repair gap: a native malformed `arguments` gets the identical repair treatment an XML one gets (J1a).
- Make the repair observable on the native path through the existing `repair=` field — no grammar change.
- Expose `max_tokens`, snap-tolerance floor, and boundary bands as `ape.properties` keys with defaults reproducing today's behavior bit-for-bit (J1b/J1c).
- Keep the fix confined to `ape/llm/*` + `Config` so the bytecode diff audit (handoff §5) is small and legible.

**Non-Goals**
- No prompt changes (`ApePromptBuilder` untouched).
- No change to snapping tolerance formula, band values, or `max_tokens` value (levers analyzed and discarded, P4 §3/§7).
- No reduction of no_match beyond the parser fix (anti-starvation: promotion is decided by NET coverage in Fase B, not by `degenerate`↓).
- No new telemetry fields, no emitter moves.
- No change to Levels 2-3, `llm_tap` synthesis, or the algorithmic fallback turn.

## Decisions

**D1 — Unify (handoff option a), not force-XML (option b).** Native responses carry `content=""` — the tool call exists *only* in the `tool_calls` envelope. "Force-XML" would mean either (i) ignoring `response.getToolCalls()`, which discards the entire native-path population (12,966 calls in the base run, of which 4,355 matched + 741 llm_tap are working today), since an empty content leaves Levels 2-3 nothing to parse; or (ii) dropping the `tools` request parameter, which the `llm-infrastructure` spec documents as **required** for Qwen3-VL to produce structured output on multimodal input (without it the model returns empty content). Both variants of (b) are behavior changes far larger than the bug. Unify strictly adds repair to an existing path: every input that parses today parses identically tomorrow.

**D2 — Preserve the raw arguments string on `ToolCall`.** The repair pipeline operates on JSON *strings*; the malformation is destroyed at `parseResponse` when the failed parse leaves an empty map. `rawArguments` carries the arguments exactly as the envelope had them: the string itself when `arguments` is a JSON string (kept even when — especially when — unparseable), `argsObj.toString()` when it arrived as a JSON object. The parsed-map field stays as today (best-effort), because it feeds the never-worse fallback and existing tests/constructors. The 2-arg constructor is kept delegating with `rawArguments=null` (used by existing tests; a null raw simply short-circuits to the fallback).

**D3 — Level 1 rebuilds the XML-path intermediate and calls `parseJsonString`.** The intermediate is `{"name": <JSONObject.quote(name)>, "arguments": <rawArguments>}` — the same shape Level 2 extracts from `<tool_call>` tags — so the native path exercises literally the same code: `fixMalformedJson` (labels per INV-LLM-09), org.json parse, `lastResortIntScan` in the catch (gated to click/long_click, INV-LLM-04 internally guarded). `JSONObject.quote` is used for the name because it re-enters a JSON context (defensive; in practice the name is one of the four schema tools). If `parseJsonString` returns null (unrecoverable raw, or `rawArguments == null`), Level 1 falls back to `buildParsedAction(name, argsMap, "none")` — exactly today's behavior, guaranteeing the change can only add recoveries, never lose any.

**D4 — Accept the truthful relabeling of native array coordinates.** Today a native `{"x": [540, 399]}` is silently expanded by `SglangClient.parseArgsObject` and reaches TEL as a clean parse (`repair` absent). Under D3 the raw string hits `FIX_ARRAY_COORDS` first and the decision carries `repair=array_xy`. This is a distributional change within the existing closed vocabulary, not a grammar change — and it is the handoff's acceptance criterion ("the native path now carries repair= tags like the XML path"). `parseArgsObject`'s expansion is kept: it serves the fallback map.

**D5 — Config keys without clamps.** `ape.llmMaxTokens` (int, 1024), `ape.llmSnapTolerancePx` (int, 50), `ape.llmBoundaryTopPct` (double, 0.05), `ape.llmBoundaryBottomPct` (double, 0.94). No clamping logic (P1): these are researcher-facing knobs like `llmTimeoutMs`/`llmTopK`, which ship unclamped; B1 runs all four at defaults. All four register in `rvExemptReasons` as LLM sub-params ("inert when the LLM masters are forced off"), satisfying the `ApePureModeKillSwitchTest` completeness guard (INV-ARCH-06). The `[APE-LLM-CONFIG]` manifest keeps its `max_tokens=` field, now sourced from `Config.llmMaxTokens` through the same hoisted local — at default the emitted line is byte-identical.

**D6 — No new TEL fields.** The native-path fix surfaces exclusively through existing fields: `repair=` (INV-RTR-13, already emitted at `LlmRouter.java:508-511` from `ParsedAction.getRepairForm()` — the router does not know or care which level produced the action) and the `result`/`reason` distribution. Zero changes to telemetry emission code.

## API Design

```java
// SglangClient.ToolCall
public ToolCall(String name, Map<String, Object> arguments)                       // kept: raw = null
public ToolCall(String name, Map<String, Object> arguments, String rawArguments)  // new
public String getRawArguments()  // envelope-verbatim arguments; null when unavailable
```
- Precondition: none (all args nullable as today).
- Postcondition: `getArguments()` never null (empty map default, unchanged); `getRawArguments()` is the envelope string verbatim, `toString()` of the object form, or null.

```java
// ToolCallParser.parse — signature unchanged; Level 1 behavior:
// 1. raw = tc.getRawArguments()
// 2. if raw != null:
//      ParsedAction a = parseJsonString("{\"name\":" + JSONObject.quote(tc.getName())
//                                        + ",\"arguments\":" + raw + "}");
//      if (a != null) return a;                    // repaired or clean, label per INV-LLM-09
// 3. return buildParsedAction(tc.getName(), tc.getArguments(), "none");   // today's path
```
- Error behavior: `parseJsonString` never throws (INV-LLM-04); step 3 never throws. `parse()` contract unchanged.

```java
// Config (new keys)
public static final int    llmMaxTokens        = Config.getInteger("ape.llmMaxTokens", 1024);
public static final int    llmSnapTolerancePx  = Config.getInteger("ape.llmSnapTolerancePx", 50);
public static final double llmBoundaryTopPct    = Config.getDouble("ape.llmBoundaryTopPct", 0.05);
public static final double llmBoundaryBottomPct = Config.getDouble("ape.llmBoundaryBottomPct", 0.94);
```
- `LlmRouter` consumption: constructor `int maxTokens = Config.llmMaxTokens;` (manifest + client share it, as today); `mapToModelAction` boundary check `pixelY < deviceHeight * Config.llmBoundaryTopPct || pixelY > deviceHeight * Config.llmBoundaryBottomPct`; euclidean `tolerance = Math.max((double) Config.llmSnapTolerancePx, Math.min(nodeWidth, nodeHeight) / 2.0)`.

## Data Flow

1. SGLang returns envelope with `tool_calls[0].function.arguments` = `"{\"x\": 616, 891}"` (malformed string).
2. `parseResponse`: map parse fails → empty map (as today); `rawArguments` keeps the string (new).
3. `ToolCallParser.parse` Level 1: intermediate `{"name":"click","arguments":{"x": 616, 891}}` → `fixMalformedJson` fires `FIX_MISSING_Y_KEY` → `{"x": 616, "y": 891}` → `ParsedAction(click, 616, 891, repair=missing_y)`.
4. `LlmRouter`: normalize → map → widget hit → `[APE-LLM-TEL] ... result=matched repair=missing_y` — same line grammar, formerly `result=no_match reason=degenerate`.

## Error Handling

| Error | Source | Strategy | Recovery |
|---|---|---|---|
| Raw arguments unparseable after all fixes | model emits garbage | `lastResortIntScan` (click/long_click only) | null → map fallback → today's behavior |
| `rawArguments == null` (2-arg constructor, tests) | legacy call sites | skip raw path | map fallback (today's behavior) |
| Malformed tool name breaks intermediate | model hallucination | `JSONObject.quote` + `parseJsonString`'s catch | null → map fallback |
| Exception anywhere in Level 1 raw path | any | `parseJsonString`/int-scan internally guarded (INV-LLM-04) | never propagates; fallback still runs |
| New Config keys absent from properties | normal runs | defaults 1024/50/0.05/0.94 | bit-identical behavior |

## Risks / Trade-offs

- [Native array form now labeled `array_xy`] → intended per handoff acceptance; documented in D4 so the rvsec consolidation reads the label shift as parser-visibility, not model drift.
- [Anti-starvation: ~7,5k former algorithmic turns become LLM taps, which historically yield fewer new states (~9% vs ~26%)] → out of scope for the code change by design; gate is Fase B NET `cov_mop` vs the winner-A arm re-run with the new jar (handoff §4). The fix lands regardless because it removes a parser confound.
- [Two open changes touch `ToolCallParser`] → this change builds on parse-recovery's committed code (bca8fc3) and its delta text; archive ordering: `llm-toolcall-parse-recovery` first.
- [Config-sourced constants in `mapToModelAction` hot loop] → static-final primitives, JIT-constant; no measurable cost.

## Testing Strategy

| Layer | Scope |
|---|---|
| Unit — `SglangClientTest` | `parseResponse` preserves raw arguments verbatim for: malformed string, valid string, object form; 2-arg `ToolCall` gives null raw |
| Unit — `ToolCallParserTest` | native Level 1: missing_y string → repaired `ParsedAction` + label; quoted_xy map-valid case → repaired; array object form → `array_xy`; unrecoverable + click → int-scan; unrecoverable + back → map fallback (no coords, no crash); raw=null → fallback identical to today; clean native → `repair=none` byte-identical result |
| Unit — `ConfigTest` / `ApePureModeKillSwitchTest` | 4 new keys defaults; exempt registry entries + reasons |
| Unit — `LlmRouterTest` | manifest `max_tokens=1024` at default; boundary/tolerance behavior identical at defaults; native repaired decision → TEL `repair=` + `repairedCount` |
| Offline acceptance (rvsec side, handoff §1) | re-parse post-fix traces with `decompose_nomatch.py`: `degenerate` ≈ 0, native path carries `repair=`/`matched` |
| Deliverables (handoff §5) | rebuilt jar path + sha256 + bytecode diff audit (javap) confined to `ape/llm/*` + `Config` |

## Open Questions

None — the handoff fixes scope, values, and acceptance; the implementation option (a-unify) is decided in D1.
