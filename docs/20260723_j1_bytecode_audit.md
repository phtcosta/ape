# J1 bytecode diff audit — `llm-native-toolcall-repair`

**Date:** 2026-07-23
**Change:** `openspec/changes/llm-native-toolcall-repair` (J1a parser fix + J1b/J1c config exposure)
**Purpose:** confirm the rebuilt `ape-rv.jar` confines all bytecode changes to the native tool-call path
+ the four exposed config keys, and that the frozen `[APE-LLM-TEL]` telemetry emitters are untouched
(rvsec handoff §5, gate G2 deliverable). Unblocks Fase B: `calb` bind-mounts this jar `:ro`.

## Artifacts

| Jar | sha256 | size |
|-----|--------|------|
| **pre-change** (baseline, commit `bca8fc3` working tree) | `c0defaab2d86057a5306353152a39d81122afbd618261fabc8595a784f869db1` | 271 773 B |
| **post-change** (`target/ape-rv.jar`, final) | `f671db48384172ad6eb5dee888d98ce9dea483fe1de76e46a8b0277c38627d59` | 272 037 B |

> The delivered jar is the final build after the code-review NT-01 follow-up (a doc comment in
> `ToolCallParser.parse` + one characterization test; no bytecode-logic change). The re-run diff below
> is identical to the pre-NT-01 build (`8f9bef45…`): the same 10 classes differ and `parse` is still
> 71→128 units (the comment is debug-line-only and normalized away).

Each jar contains a single `classes.dex` (d8 / Dalvik). Method: `dexdump -d classes.dex` (build-tools
35.0.1) on both, split per class descriptor, normalized to remove pool-renumbering noise
(`// <kind>@hhhh` index comments, absolute file offsets, raw-byte columns, and the `source_file_idx`
string-pool index that shifts by one when the pool grows), then class-by-class diff.

## Result 1 — change set confined to the four target class families

After normalization, **exactly 10 of 353 classes differ**, all in the four intended families:

```
ape.llm.LlmRouter
ape.llm.SglangClient            ape.llm.SglangClient$ChatResponse   (debug-line only)
ape.llm.SglangClient$ToolCall   ape.llm.SglangClient$ContentPart    (debug-line only)
                                ape.llm.SglangClient$Message        (debug-line only)
ape.llm.ToolCallParser          ape.llm.ToolCallParser$ParsedAction (debug-line only)
                                ape.llm.ToolCallParser$FixResult    (debug-line only)
ape.utils.Config
```

The remaining 343 classes are byte-identical (their earlier apparent diffs were solely the global
`source_file_idx` / pool-index renumbering caused by the new `Config` strings — e.g. `ApeRRFormatter`
differed only by `source_file_idx : 692 → 693`).

The inner classes marked *(debug-line only)* carry **no bytecode change** — their only diff is the
`0xNNNN line=NNN` debug position table shifted by the 7 source lines added to `SglangClient.java`
(the `rawArguments` field + comment + 3-arg constructor). Example (`SglangClient$ContentPart`): every
hunk is `line=391 → line=398` (+7), no instruction change.

## Result 2 — TEL emitter sites unchanged (frozen grammar, d90c1f4)

`LlmRouter` per-method `insns size` (16-bit code units), old vs new:

| Method | old | new | Δ | note |
|--------|-----|-----|---|------|
| `<init>` | 276 | 276 | 0 | `[APE-LLM-CONFIG]` manifest emit lives here; `1024` literal → `sget Config.llmMaxTokens` is the same code-unit size, so identical size — only the operand *source* moved |
| **`selectAction`** | **1845** | **1845** | **0** | the entire `[APE-LLM-TEL]` / `[APE-LLM-ERROR]` / `[APE-LLM-RESPONSE]` / `[APE-LLM-PROMPT]` / `repair=` / `reason=` emitter block — **byte-identical** |
| `printSummary` | 305 | 305 | 0 | the `[APE-RV] LLM Summary` / `repaired=` emitter — byte-identical |
| `mapToModelAction` | 660 | 657 | −3 | the **only** LlmRouter method whose bytecode changed |

The three intended edits are the sole instruction-level changes in `LlmRouter`:

```
<init>            0030: const/16 v12, #int 1024         →  sget v12, Config.llmMaxTokens:I
mapToModelAction  const-wide v11, #double 0.05          →  sget-wide v11, Config.llmBoundaryTopPct:D
mapToModelAction  const-wide v11, #double 0.94          →  sget-wide v11, Config.llmBoundaryBottomPct:D
mapToModelAction  const-wide 50.0 (euclidean floor)     →  sget Config.llmSnapTolerancePx (int→double)
```

(Register renumbering and the −3 code-unit delta in `mapToModelAction` are the natural consequence of
replacing wide double literals with `sget-wide`; no control-flow change.)

TEL emitter string literals are present and identical in both jars:
`APE-LLM-TEL` (1/1), `APE-LLM-ERROR` (1/1), `APE-LLM-CONFIG` (2/2), `repair=` (1/1), `reason=` (1/1).

## Result 3 — ToolCallParser reused the shared pipeline, did not modify it (design D3)

`ToolCallParser` per-method `insns size`, old vs new — **only `parse()` changed**:

| Method | old | new | Δ |
|--------|-----|-----|---|
| **`parse`** | 71 | 128 | +57 (Level-1 unification: rebuild `{"name":…,"arguments":<raw>}` → `parseJsonString`, map fallback) |
| `parseJsonString` | 188 | 188 | 0 |
| `fixMalformedJson` | 140 | 140 | 0 |
| `lastResortIntScan` | 89 | 89 | 0 |
| `buildParsedAction` | 58 | 58 | 0 |
| `parseXml` | 28 | 28 | 0 |
| `parseJsonInline` | 43 | 43 | 0 |

The repair pipeline (`fixMalformedJson` → `parseJsonString` → `lastResortIntScan`) is byte-identical:
the native path now *invokes* it, exactly as the XML path does — no behavioral fork, satisfying the
never-worse guarantee (INV-LLM-10) at the bytecode level.

`SglangClient` gained the `rawArguments` capture in `parseResponse` + the 3-arg `ToolCall` constructor;
`ToolCall` gained the field + `getRawArguments()`. `Config` gained the four `public static final`
fields + the four `rvExemptReasons` entries (INV-ARCH-06). No other class in the dex differs.

## Conclusion

The bytecode change set is **confined to `ape/llm/{SglangClient,ToolCallParser,LlmRouter}*` +
`ape/utils/Config`**, the `[APE-LLM-TEL]`/`[APE-LLM-ERROR]`/`[APE-LLM-CONFIG]`/summary emitters are
**byte-identical**, and the shared repair pipeline was reused unchanged. The fix surfaces downstream
only distributionally, through the existing `repair=` field now appearing on native-path decisions
(INV-RTR-13/D6). Consistent with the anti-starvation constraint (handoff §4): no telemetry grammar
change, no emitter move.

**Deliverable for gate G2:** `target/ape-rv.jar`,
sha256 `f671db48384172ad6eb5dee888d98ce9dea483fe1de76e46a8b0277c38627d59`.
