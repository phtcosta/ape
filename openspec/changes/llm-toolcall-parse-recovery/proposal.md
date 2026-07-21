# Proposal: llm-toolcall-parse-recovery

## Why

The smoke gate of campaign `cmp_llm_20260721` (image `rvandroid:0.9.3`, jar `d90c1f4`, base model
`Qwen/Qwen3-VL-4B-Instruct`, temperature 0) dropped **7 of 47 LLM calls (~15%)** with
`[APE-LLM-ERROR] cause=parse detail=no tool call extracted`. Inspecting the raw
`[APE-LLM-RESPONSE]` lines, all 7 failures are the **same recoverable malformation** across three
apps (thumbkey 2, petals 4, libchecker 1): the model collapses both coordinates into a single
**open, unterminated string** under the `"x"` key —

```
{"name": "click", "arguments": {"x": "500, 527}}
{"name": "click", "arguments": {"x": "820, 590}}   (closing quote sometimes present, sometimes not)
```

`ToolCallParser.fixMalformedJson` already recovers the *bare* sibling of this form
(`{"x": 932, 71}` → `{"x": 932, "y": 71}`, via `FIX_MISSING_Y_KEY`), but that pattern requires a
digit immediately after `"x":`; here a quote arrives first, so no fix applies and
`new JSONObject(...)` throws `Unterminated string` → `parse()` returns null → the step silently
falls back to SATA/Coverage. The model's intent (`x=500, y=527`) is fully present and legible; the
parser simply does not recover this variant.

Two facts make this worth fixing at the gate rather than after the campaign: (1) a JSON syntax slip
is not "the LLM failed to pick a target", so silently dropping 15% of the treatment to SATA
confounds the base×v2 comparison the campaign measures; (2) the parse-recovery must not erase the
fidelity signal — the base 4B emits malformed tool calls and the v2 fine-tune should emit fewer, so
"the model needed a repair" must remain observable in the trace after recovery.

## What Changes

- `ToolCallParser` recovers the quoted-collapsed-XY malformation: a `"x"` value that is an open or
  closed string containing two integers (`"x": "500, 527` / `"x": "500, 527"`) is rewritten to
  `"x": 500, "y": 527` before `org.json` sees the string, ordered before the existing
  `FIX_MISSING_Y_KEY`. This closes the observed 7/7 gap.
- `ToolCallParser` adds a last-resort recovery, form-independent: when all regex fixes still leave
  an unparseable object that nonetheless names a tap action (`click`/`long_click`), the first two
  standalone integers in the `arguments` region are taken as `(x, y)`. This covers future
  malformations not yet seen without a per-form regex. The gate excludes `scroll`/`type_text`/
  `back`: a synthesized scroll would execute as a wrong-gesture tap (the router has no scroll
  dispatch and the toolset never offers scroll), a text-less `type_text` is a wasted step, and
  `back` has no coordinate semantics — for those, the SATA fallback on `cause=parse` is the better
  outcome.
- `ToolCallParser` records **which repair, if any, produced a successful parse**, so the recovery is
  no longer silent. A clean tool call reports no repair; a recovered one names its repair form.
- `LlmRouter` emits the repair as a distinct, joinable telemetry marker on the successful decision
  (`[APE-LLM-TEL] ... repair=<form>`) and counts repaired decisions in the aggregate `LLM Summary`.
  This keeps raw tool-call fidelity — "clean vs repaired" — reconstructable from the trace after the
  parser is hardened, so the base×v2 fidelity contrast is preserved as a first-class sub-metric.
- No new config flags. No change to the `null`-return contract of `parse()` (INV-LLM-04) or to
  `[APE-LLM-ERROR] cause=parse` (which still fires only when recovery genuinely fails).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `llm-infrastructure`: `ToolCallParser — 3-Level Fallback Parser` — the malformed-JSON fix list
  gains the quoted-collapsed-XY rule and the form-independent last-resort integer extraction; the
  parser exposes the repair form applied to a successful parse. INV-LLM-04 is amended to state that
  the added fixes preserve the never-throw / null-on-total-failure contract.
- `llm-routing`: `LLM Telemetry Logging` — the per-decision `[APE-LLM-TEL]` line gains an optional
  `repair=<form>` field (present only when a repair produced the action), and the aggregate
  `LLM Summary` gains a `repaired=<N>` counter. This is additive; existing fields and the
  `cause=parse` attribution are unchanged.

## Impact

- `src/main/java/com/android/commands/monkey/ape/llm/ToolCallParser.java` — new fix pattern, last
  resort extraction, and a repair-form accessor on `ParsedAction` (or a parser query).
- `src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java` — read the repair form on a
  successful parse, append `repair=<form>` to `[APE-LLM-TEL]`, increment `repairedCount`, extend
  the `LLM Summary` line.
- Tests: `ToolCallParserTest` — regression cases built from the real raw responses (open-quote,
  closed-quote, bare already-passing, array already-passing, last-resort fallback). JVM-only, no
  device. Existing 656/0/19 baseline must stay green.
- **Experiment integrity**: the fix changes `ape-rv.jar`, so image `rvandroid:0.9.3` must be
  rebuilt and bytecode-re-audited, and the 4-app smoke must be re-run to confirm the 7 `cause=parse`
  drops disappear, no new drops appear, and `repair=` markers are emitted. The campaign depends on
  the audited jar — do not deploy until re-audit + re-smoke pass.
