## Why

The jar parses the full static-analysis JSON on-device: call-graph-heavy files of 1.5–48 MB, of which the explorer consumes only a small, fixed projection (flagged widgets, WTG edges, components) — verified as V22/T7. This produces the `too-large` degradation class (arms silently running without MOP guidance when the JSON is oversized or absent, V21) and a per-run parse cost on the emulator. The report's resolution (Kimi's proposal, adopted in Sec. 6.6): derive a compact, explorer-shaped artifact **on the host**, and make `MopData` consume only that.

This change is **stage 7 of 7** of the re-architecture selected in `docs/analise_fable-selecao.md` (rev. 3, Sec. 6.6, Sec. 10).

## What Changes

- New host-side generator (rv-android, at instrumentation/preparation time) deriving a compact artifact (~1–5 MB) from the static-analysis JSON: flagged widgets, WTG, components — only what the explorer reads.
- **BREAKING (cross-repo, wire format)**: `MopData` consumes the derived format; the on-device parser for the full call-graph JSON is deleted. The `Target`→`MOP` vocabulary boundary (gh13 D7) moves to the host-side generator.
- The fail-fast plan validation from stage 2 covers the artifact: a plan with the MOP feature and a missing/unreadable artifact aborts before step 1 (kills the silent "arm ran without MOP" degradation, V21) — replacing the current warn-and-continue.
- `MopData` load status remains an explicit trace record (`MOP_DATA`, from stage 4).
- Push path in `aperv-tool` switches to the derived artifact; the full JSON stays host-side for analysis.

## Capabilities

### New Capabilities

_None — the artifact format is a requirement change of existing capabilities._

### Modified Capabilities

- `mop-guidance`: `MopData` input contract becomes the derived compact format; absent-artifact behavior becomes fail-fast when the MOP feature is in the plan.
- `static-analysis-entrypoints`: source-of-truth JSON remains host-side; requirements for the derived projection (what must be included for widgets/WTG/components).
- `aperv-tool`: generates (or invokes generation of) and pushes the derived artifact instead of the full JSON.

## Impact

- **Python/rv-android**: generator + push path in `aperv-tool`; instrumentation pipeline gains a derivation step.
- **Java**: `MopData` parser rewritten for the compact format (smaller, no `catch(OutOfMemoryError)` path needed — V19).
- **Depends on**: `rearch-02-runspec` (fail-fast plan validation), `rearch-04-step-ndjson-telemetry` (`MOP_DATA` record), `rearch-05-thin-python-arms` (touching the same `tool.py` push path).
- **Frozen metrics untouched** (R9): *MOP coverage* stays defined over `directly_reaches_mop`; the derivation must preserve exactly the sets the definitions depend on.
- Grounding: report Sec. 6.6 (last row), verified V21/V22, Ling T7, Kimi compact-artifact proposal (Sec. 4).
