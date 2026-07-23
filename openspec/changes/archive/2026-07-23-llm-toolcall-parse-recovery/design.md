# Design: llm-toolcall-parse-recovery

## Context

The `cmp_llm_20260721` smoke gate dropped 7/47 LLM calls with `[APE-LLM-ERROR] cause=parse`, all the
same recoverable malformation: both coordinates collapsed into an unterminated string under `"x"`
(`{"x": "500, 527}` — opening quote, closing quote optional). `ToolCallParser.fixMalformedJson`
(`ToolCallParser.java:129`) already repairs the bare sibling (`{"x": 540, 399}`) via
`FIX_MISSING_Y_KEY` (`:37`), but that pattern needs a digit after `"x":`; the leading quote defeats
it, so `new JSONObject(...)` (`:158`) throws `Unterminated string`, `parseJsonString` returns null
(`:193`), and the step falls back to SATA. The proposal fixes the malformation and — critically —
keeps the recovery observable so raw tool-call fidelity (base 4B vs fine-tuned v2) survives the
hardening.

Two capabilities change: `llm-infrastructure` (`ToolCallParser` — the fix + a repair-form label on
`ParsedAction`) and `llm-routing` (`LlmRouter` — surface the label as `[APE-LLM-TEL] repair=<form>`
and a `repaired=<N>` summary counter). No config flags. JVM-testable; no device dependency for the
unit layer. The jar change forces image rebuild + bytecode re-audit + re-smoke before campaign use.

## Architecture

```
ChatResponse ──> ToolCallParser.parse()
                    ├─ L1 native toolCalls ──────────────> buildParsedAction(name,args, NONE)
                    ├─ L2 <tool_call> XML ─┐
                    ├─ L3 inline JSON ─────┴> parseJsonString(json)
                    │        ├─ fixMalformedJson(json) -> FixResult{json, form}
                    │        │      order: quoted_xy > array_xy > missing_y > (leading zero, brace close: cosmetic, form none)
                    │        ├─ new JSONObject(fixed)  ── ok ─> buildParsedAction(name,args, form)
                    │        └─ on JSONException, if the object names a tap action (click/long_click):
                    │               lastResortIntScan(json) -> buildParsedAction(name,{x,y}, INT_SCAN)
                    └─ returns ParsedAction{...,repairForm} or null
                                 │
LlmRouter.selectAction() ────────┘
     parsed.getRepairForm() != "none"
        ├─ [APE-LLM-TEL] ... repair=<form>       (one build site, LlmRouter.java:488)
        └─ repairedCount++                        (summary: LlmRouter.java:708)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `ToolCallParser.fixMalformedJson` | Apply ordered pre-parse fixes; report which structural fix fired | `String json` | `FixResult{json, form}` |
| `ToolCallParser.lastResortIntScan` | When a still-unparseable object names a tap action (`click`/`long_click`), take the first two standalone ints in the `arguments` region as `(x,y)`; internally guarded, returns null on any failure | `String json` | `ParsedAction(int_scan)` or null |
| `ToolCallParser.ParsedAction` | Carry action + coords + `repairForm` label | fields | immutable value |
| `LlmRouter` (TEL build) | Append `repair=<form>` when label ≠ none | `ParsedAction` | `[APE-LLM-TEL]` line |
| `LlmRouter` (summary) | Count `repairedCount`; emit `repaired=<N>` | counters | `LLM Summary` line |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|-------------------------|----------------|------|
| `llm-infrastructure` ToolCallParser quoted-collapsed-XY fix | `FIX_QUOTED_XY` pattern + first in `fixMalformedJson` | `testFix_quotedXY_openQuote`, `testFix_quotedXY_closedQuote` |
| ToolCallParser last-resort int extraction | `lastResortIntScan` in `parseJsonString` catch | `testLastResort_intScan`, `testLastResort_gateExcludesUnadvertisedAction` |
| INV-LLM-04 (never throw / null only on total failure) | try/catch in `parseJsonString`; best-effort recovery | `testGarbage_returnsNull`, `testAllLevelsFail` |
| INV-LLM-09 (one repair label, precedence) | `FixResult.form`; `buildParsedAction(..., form)` | `testRepairForm_precedence`, `testNative_formNone` |
| Bare `missing_y` unregressed | ordering: quoted fix leaves bare form untouched | `testFix_bareMissingY_stillMissingY` |
| `llm-routing` `[APE-LLM-TEL] repair=<form>` | append in TEL builder `LlmRouter.java:~497` | device re-smoke gate (trace grep) |
| INV-RTR-13 (repair additive, once, no cause change) | `repairedCount++` guarded by `!"none".equals(form)` | device re-smoke gate |
| `repaired=<N>` summary field | `LLM Summary` builder `LlmRouter.java:~708` | device re-smoke gate |

## Goals / Non-Goals

**Goals:**
- Recover the observed 7/7 `quoted_xy` malformation and future coordinate malformations (last resort).
- Keep raw tool-call fidelity measurable after hardening via `repair=<form>` + `repaired=<N>`.
- Zero new config flags; preserve `parse()` never-throw / null-on-total-failure contract.

**Non-Goals:**
- Changing the `cause=parse` semantics — it still fires only on genuine unrecoverable failure.
- Repairing non-coordinate malformations (e.g. wrong action names, missing `arguments`).
- Any change to coordinate normalization, mapping, or the routing decision logic.
- Deciding the base×v2 measurement question — this change *preserves* both signals; which is the
  headline endpoint is out of scope (see proposal "Experiment integrity").

## Decisions

**D1 — Label lives on `ParsedAction`, not on the parser instance.** The repair form travels with the
action it describes, so `LlmRouter` reads `parsed.getRepairForm()` at the single TEL build site with
no ordering coupling to the parser. Alternative (parser keeps `lastRepairForm`, router queries after
`parse()`) was rejected: it reintroduces a stale-state seam of exactly the kind INV-LLM-08 exists to
prevent on `SglangClient.getLastErrorCause()`.

**D2 — `fixMalformedJson` returns `FixResult{json, form}` instead of `String`.** The form must be
known at the point a fix fires; diffing original-vs-fixed afterward is fragile (multiple fixes,
whitespace). Per P3 (no backward compat) the three existing tests that call `fixMalformedJson(...)`
and assert on the returned string are updated to read `.json`. The static method stays pure
(no side effects), so it remains JVM-unit-testable.

**D3 — Precedence `int_scan > quoted_xy > array_xy > missing_y > none`.** The label names the
coordinate-structure rewrite that made the coordinates extractable. `leading_zero` and brace-close
are cosmetic and never labeled: empirically (org.json 20180813 probe), `{"confidence": .91}` parses
fine RAW — org.json's lenient tokenizer already coerces `.91` — so `FIX_LEADING_ZERO` never rescues
a parse and never touches a coordinate; a `leading_zero` label would be unreachable-or-meaningless,
and it is therefore excluded from the repair vocabulary (INV-LLM-09). `int_scan` is naturally
exclusive (only reached after regex fixes fail to parse), so precedence only arbitrates the regex
forms; in practice at most one coordinate-structure fix fires per string.

**D4 — Quoted fix ordered before the missing-"y" fix.** `FIX_QUOTED_XY` consumes the quote so the
value becomes bare `540, 399`; running it first means the leading quote no longer defeats
`FIX_MISSING_Y_KEY`, and after the rewrite the char following `"x": 540, ` is `"` (of `"y"`), not a
digit, so `FIX_MISSING_Y_KEY` does not re-fire (empirically verified on all three quoted variants,
including the no-space form `{"x":"820,590}}` observed in traffic). The regex is not redundant with
the last-resort scan: the closed-quote variant `{"x": "820, 590"}` is *valid JSON* (a string value),
never throws, and therefore never reaches the catch — the regex is the only recovery path for it
(without it, the coordinates silently degrade to `(0,0)` and the decision is discarded as
`no_match reason=degenerate`). Also verified: the pattern does not fire on quoted single integers
(`{"x": "500", "y": "527"}`) nor inside escaped `text` values.

**D5 — Last resort gated on a tap action name (`click`/`long_click`).** `lastResortIntScan` runs
only when the still-unparseable object names `click` or `long_click`, avoiding turning arbitrary
integer-bearing garbage into a spurious action. Fewer than two integers → null (no action). The
gate is deliberately narrower than the parser's full action vocabulary, on three verified grounds:
(1) the advertised toolset (`LlmRouter.buildTools`, `:149-156`) is `click`/`long_click`/`type_text`/
`back` — `scroll` is never offered to the model, and the router has no scroll dispatch: a parsed
`scroll` falls through the generic bounds pass and *executes a tap* on whatever widget contains the
coordinate (`LlmRouter.java:587-608`), so an int-scan-synthesized `scroll` would manufacture a
wrong gesture; (2) a `type_text` recovered without its `text` (int-scan cannot recover strings) is
a wasted step — the `:463` null-text guard skips input — strictly worse than the SATA fallback the
step gets on `cause=parse`; (3) `back` ignores coordinates entirely (`:563-569`), so the ≥2-int
precondition is incoherent for it, and an unparseable `back` losing to `cause=parse` costs nothing.
The scan itself: first two *standalone* integer runs of 1–4 digits (`(?<!\d)\d{1,4}(?!\d)`) after
the `"arguments"` token — the length bound keeps `Integer.parseInt` from overflowing on degenerate
digit runs and skips over-long non-coordinate numbers; the whole method body is wrapped in its own
try/catch → null, because it executes inside `parseJsonString`'s catch where no outer handler
protects INV-LLM-04.
The net generalizes to the documented sibling corruptions catalogued in the reference parsers with
**no per-form production regex** — each fails `new JSONObject(...)` and reaches the catch
(empirically verified, org.json 20180813): double-colon `{"x":": 541, ...}` and trailing-quote
`{"x": 200", ...}` (`Expected a ',' or '}'`; vision doc 012, rv-agent P0b/P0c), equals-sign
`{"x": = 265, ...}` (`Missing value`; rv-agent P4, vLLM backend), quoted-array `{"x": [499", "499"]}`
(rv-agent P3), and truncated-array `{"x": [500, 563}`. Three regression tests pin the coverage:
`testLastResort_intScan` (equals-sign body), `testLastResort_doubleColon`, `testLastResort_trailingQuote`;
all use `x`-first bodies so the first-two-ints order maps to the intended `(x, y)` (int-scan is
form-independent and does NOT reorder by key, so a `"y"`-first body would recover swapped
coordinates — a known limitation, not exercised as a blessed case). A fourth test
(`testLastResort_gateExcludesUnadvertisedAction`) pins the gate boundary: an unparseable `scroll`
body with two legible ints returns null. None of these forms was observed in the `cmp_llm_20260721`
traffic (only `quoted_xy` was; the census also confirms `click` is the only tool name the model
ever emitted there), so they get no dedicated fix — the int-scan net is the P1-simple design that
already catches them.

## API Design

### `static FixResult fixMalformedJson(String json)`
- **Post:** returns `{json: fixed, form}` where `form ∈ {none, quoted_xy, array_xy, missing_y}` — the highest-precedence coordinate-structure fix that altered the string, `none` when none did. Leading-zero and brace-close still run but are cosmetic and never labeled (see D3: `FIX_LEADING_ZERO` cannot rescue a parse and never touches a coordinate). Never throws.
- Applies fixes in order: `FIX_QUOTED_XY` → `FIX_ARRAY_COORDS` → `FIX_MISSING_Y_KEY` → `FIX_LEADING_ZERO` → brace balancing.

### `FIX_QUOTED_XY`
- Pattern: `"\"x\":\\s*\"(\\d+)\\s*,\\s*(\\d+)\"?"` → replacement `"\"x\": $1, \"y\": $2"`.
- Matches opening quote, two integers, optional closing quote. Anchored on `"x":` so it never touches `"text"` or other string values. Covers `{"x": "500, 527}` and `{"x": "820, 590"}`.

### `ParsedAction` (add field)
- New immutable field `String repairForm` (default `"none"`), getter `getRepairForm()`. Constructor gains the label; native path passes `"none"`.

### `private ParsedAction lastResortIntScan(String json)`
- **Pre:** called only from `parseJsonString`'s catch, i.e. regex fixes did not yield a parseable object. No outer handler protects this call — the method guards itself.
- **Post:** if `json` names `click` or `long_click` and the region after the `"arguments"` token contains ≥2 standalone 1–4-digit integers, returns a `ParsedAction(action, int1, int2, null, null, "int_scan")` (6-arg constructor: text=null, direction=null, repairForm="int_scan"); else null. The entire body is wrapped in its own try/catch → null, so it never throws (INV-LLM-04).

### `LlmRouter` (telemetry)
- After `parsed = parser.parse(response)` (non-null): in the TEL builder append `" repair="+form` when `!"none".equals(parsed.getRepairForm())`; `if (!"none"...) repairedCount++`. Summary line adds `" repaired=" + repairedCount` after `no_match=`. `repairedCount` is NOT added to `failureCount`/`decisions`.

## Data Flow

Malformed content → `parse()` picks a level → `parseJsonString(json)` → `fixMalformedJson` returns
`{fixed, form}` → `new JSONObject(fixed)` succeeds → `buildParsedAction(name, args, form)` →
`ParsedAction` with label → `LlmRouter` maps coords, builds one `[APE-LLM-TEL]` line, appends
`repair=<form>` and bumps `repairedCount` when label≠none → `LLM Summary` emits `repaired=<N>`. If
`new JSONObject` throws, `lastResortIntScan` may still produce a labelled `int_scan` action;
otherwise `parseJsonString` returns null → `parse()` null → existing `cause=parse` path unchanged.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `JSONException` (unterminated string) | `new JSONObject(fixed)` when a fix missed | caught in `parseJsonString` | `lastResortIntScan`; else null |
| No gated action name (`click`/`long_click`) / <2 standalone ints | `lastResortIntScan` | return null | `parse()` returns null → `cause=parse` fires (genuine failure) |
| Any exception inside `lastResortIntScan` | regex/parse on garbage | own try/catch → null | INV-LLM-04 holds (it runs inside the outer catch, otherwise unprotected) |
| Multiple fixes match | `fixMalformedJson` | precedence D3 picks one label | deterministic; verified no double-apply (D4) |
| Regex touches non-coord string | `FIX_QUOTED_XY` | anchored on `"x":`, requires `"\d+,\d+` | verified: escaped `text` values and quoted single integers unaffected |

## Risks / Trade-offs

- [Crediting the model for wrong syntax] → `repair=<form>` + `repaired=<N>` keep the malformation
  countable, so the fidelity contrast is preserved, not erased (the whole point of the tag).
- [Combined `{"x": "500, 527", "y": 690}` form] → not observed in the smoke corpus. Empirical facts
  (probed): the RAW string is *valid JSON* (x is a string), so today it parses clean and taps at
  `(0, 690)` (the string x coerces to the 0 default). With `FIX_QUOTED_XY` the rewrite produces a
  duplicate `"y"` key, and org.json behavior is version-divergent: Android framework org.json is
  last-wins → `(500, 690)`; the JVM test jar (20180813) throws `Duplicate key "y"` → the catch →
  int-scan → `(500, 527)`. Every outcome is non-throwing to the caller and no worse than today's
  `(0, 690)`; no JVM test pins this form (version-sensitive) and no dedicated fix is added (P1).
- [Changing `fixMalformedJson` return type breaks 3 tests] → tests updated in the same change (P3).
- [Jar change invalidates the audited image] → tasks include rebuild + bytecode re-audit + re-smoke
  gate before any campaign deploy.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | quoted_xy open/closed/no-space, bare-still-missing_y, array unaffected, int_scan (equals-sign body), precedence, native=none + clean XML/type_text/long_click=none, garbage→null | `ToolCallParserTest` (JVM, from real raw responses) | ~10 new |
| Unit (generalization + gate) | documented siblings (double-colon, trailing-quote) recovered via int-scan with no new regex (D5) — each verified to throw `new JSONObject(...)` so it exercises the catch; gate boundary: unparseable `scroll` body with 2 ints → null | `ToolCallParserTest` (JVM) | 3 new |
| Unit (regression) | existing 12 parser tests + the 3 `fixMalformedJson` tests updated to `.json` | `ToolCallParserTest` | 656/0/19 baseline stays green |
| Device gate | 4-app re-smoke: 7 `cause=parse` drops → 0; `repair=quoted_xy` emitted; clean decisions carry no `repair=` field; no new drops; `repaired=` in summary | rebuilt `rvandroid:0.9.3` + trace grep | gate in tasks |

## Open Questions

- Should the combined `{"x": "500, 527", "y": 690}` form (closing quote *and* a real `y`) be
  detected to avoid the duplicate-key `y` divergence documented in Risks? Not in the smoke corpus;
  deferred unless a later trace shows it.
