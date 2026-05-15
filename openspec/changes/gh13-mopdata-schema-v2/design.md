## Context

The upstream change `gh57-static-analysis-overhaul` in `rvsec/rv-android` is the single producer of the static-analysis JSON consumed by `MopData.load`. The producer-side artifacts (proposal, design, tasks) live at `…/workspace-rv/rvsec/rv-android/openspec/changes/gh57-static-analysis-overhaul/`. Two of the producer-side changes flow through to `MopData.parseWidget` and require parser work:

1. **Four new widget XML attributes** added by `RvsecAnalysisClient.enrichFromXml`: `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`. Nullable strings.
2. **OPTIONSMENU widgets populated with recursive `items[]`** — inflated menu items from `FixpointSolver.doMenuInflate` (gh57 D7) and programmatic items from `MenuExtractor` (gh57 item 5). Submenu items appear under an `items[]` child of their parent menu-item widget; the producer guarantees `idName` uniqueness within a window.

A third producer-side change — `windows[]` populated even when `transitions[]` is empty (gh57 Group 2 partial-JSON path) — needs no parser work: `MopData.hasWtgData()` already covers that path.

Per project principle P3, this change targets the post-gh57 shape only. No v1/v2 branching; no `schemaVersion` field is consulted. The pre-gh57 JSONs (158 files in `…/APKS_JCA_analise_estatica_soot/`) are replaced by the gh57 ground-truth re-run.

## Architecture

```
analysis.json (post-gh57)
   │
   │  reachability: [...]               ── Pass 1 (unchanged)
   │  windows: [                        ── Pass 2 (EXTENDED)
   │    { type: "ACTIVITY", widgets: [
   │        { idName: "spinner_cipher",
   │          prompt: "Choose cipher",        ← NEW
   │          spinnerMode: "dropdown",        ← NEW
   │          contentDescription: null,       ← NEW
   │          tooltipText: null,              ← NEW
   │          listeners: [...] } ] },
   │    { type: "OPTIONSMENU", widgets: [
   │        { idName: "menu_settings", listeners: [...],
   │          items: [                        ← NEW (recursive)
   │            { idName: "menu_pref_a", listeners: [...] },
   │            { idName: "menu_pref_b", listeners: [...] } ] } ] }
   │  ]
   │  transitions: [...]                ── Pass 3 (unchanged)
   │  components: {...}                  ── Pass 4 (unchanged)
   │
   ▼
MopData.load(path)
   │
   ├── parseReachability (Pass 1)       — unchanged
   ├── parseWindows / parseWindow       — Pass 2; default-skips unknown root fields
   │     │
   │     └── parseWidget (RECURSIVE, depth ≤ 8)
   │           ├── idName               → key in widgets map
   │           ├── prompt               → WidgetMopFlags.prompt              ← NEW
   │           ├── spinnerMode          → WidgetMopFlags.spinnerMode         ← NEW
   │           ├── contentDescription   → WidgetMopFlags.contentDescription  ← NEW
   │           ├── tooltipText          → WidgetMopFlags.tooltipText         ← NEW
   │           ├── listeners[]          → directMop / transitiveMop (unchanged)
   │           └── items[]              → recurse, merge into same widgets map ← NEW
   │
   ├── parseTransitions (Pass 3)        — unchanged
   └── parseComponents (Pass 4)         — unchanged
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `MopData.parseWidget` (extended) | Parses one widget object; reads four new metadata fields; recurses into `items[]` | `JsonReader`, `bySignature`, `widgets` map, depth | mutates `widgets` map |
| `MopData.WidgetMopFlags` (extended) | Per-widget MOP flags + four nullable static-analysis attributes | — | POJO read by `MopScorer` and (future) LLM prompt builder |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| `MopData — Static Analysis JSON Loader` (scenario: widget XML attributes) | `MopData.parseWidget` cases for `prompt` / `spinnerMode` / `contentDescription` / `tooltipText` | `MopDataTest.testParsesFourNewWidgetAttributes` |
| `MopData — Static Analysis JSON Loader` (scenario: null defaults) | `parseWidget` initializes the four String locals to `null`; `nextNull()` consumed when JSON value is null | `MopDataTest.testNewWidgetFieldsNullWhenAbsent` |
| `MopData — Static Analysis JSON Loader` (scenario: recursive items[]) | `parseWidget` case `"items"` calls `parseWidget` for each array element, sharing the same `widgets` map | `MopDataTest.testRecursiveOptionsMenuItems` |
| `MopData — Static Analysis JSON Loader` (scenario: submenu handler MOP propagation) | Submenu items go through the same `listeners[]` cross-reference path as top-level items | `MopDataTest.testSubmenuHandlerReachesMop` |
| INV-MOP-07 (depth cap) | Counter parameter incremented per recursion; > 8 raises `IOException` with diagnostic | `MopDataTest.testRecursionDepthCapped` |
| INV-MOP-08 (flatten submenu) | `parseWidget` recursion shares the enclosing window's `widgets` map | `MopDataTest.testRecursiveOptionsMenuItems` |

## Goals / Non-Goals

**Goals:**
- Parse the four new widget metadata attributes into `WidgetMopFlags`.
- Discover handlers attached to OPTIONSMENU submenu items so `MopScorer` can boost their actions.
- Keep `parseWidget` simple (P1) — reuse the same function for recursion rather than introducing a sibling parser.

**Non-Goals:**
- Backward compatibility with pre-gh57 JSON files (P3).
- Wiring the four new metadata fields into `ApePromptBuilder` / LLM context — out of scope; surfaced for a future change.
- Tracking parent/child relationships between menu items on the consumer side — `MopScorer` looks widgets up by `(activity, shortId)` only, so flattening is correct.
- Modifying `MopScorer` — `hasWtgData()` already handles `windows[]` populated + `transitions: []`.

## Decisions

### D1 — Four new widget attributes live directly on `WidgetMopFlags`

**Choice:** Add `public String prompt`, `spinnerMode`, `contentDescription`, `tooltipText` directly on `WidgetMopFlags`. Default `null`.

**Why:** P1 (simplicity). `WidgetMopFlags` is already a public mutable POJO; callers read it directly. A separate `WidgetMetadata` class would force two-step lookups. The four fields are semantically widget-level static-analysis output, parallel to `directMop` / `transitiveMop`.

**Alternative considered:** A `WidgetStaticInfo` sidecar map keyed by `idName`. Rejected — adds indirection consumers must thread through. None of the four fields participates in MOP scoring (yet); they are exposed for future LLM prompt and component-triggering use.

### D2 — Recursive `items[]` reuses `parseWidget`

**Choice:** In `parseWidget`, a new `case "items":` calls `parseWidget` for each array element, passing the same `widgets` map and `bySignature` table, with a depth counter incremented on entry.

**Why:** Submenu items have the same JSON shape as their parent menu items (`idName`, `listeners`, optionally nested `items`). Reusing the same function means a single maintenance point and natural handling of arbitrary depth.

**Alternative considered:** Iterative walk with an explicit stack. Rejected — gratuitous given the producer emits ≤ 2 levels and the depth bound is 8.

### D3 — Flatten submenu items into the enclosing window's `widgets` map

**Choice:** Recursive children land in the same `Map<String, WidgetMopFlags>` as their parent menu item, keyed by `idName`.

**Why:** `MopScorer` looks widgets up by `(activity, shortId)` — there is no parent/child notion on the consumer side. Flattening matches how the scorer queries.

The producer guarantees `idName` uniqueness within a window (gh57 D7: "the id spaces are disjoint by construction"). If a future producer change introduces a collision, the last-wins behavior of `Map.put` makes the regression observable in tests.

### D4 — Depth cap of 8 with `IOException` on overflow

**Choice:** Recursion depth is tracked in a method parameter (`int depth`). Entering `parseWidget` with `depth > 8` throws `IOException("items[] recursion depth exceeded")`, which is caught by the existing `try/catch` in `MopData.load` and results in `null` return + `WARN` log.

**Why:** Defense in depth. Android's default stack on older OS versions (Marshmallow → Q, per CLAUDE.md) is small; an unbounded recursion on a malformed JSON would crash the Monkey process. The producer emits ≤ 2 levels, so 8 is generous.

**Alternative considered:** No cap, trust the producer. Rejected — `MopData.load` already catches `Exception` and returns `null` rather than propagating, so a hard error is the symmetric handling for stack overflow risk.

### D5 — No `schemaVersion` consultation

**Choice:** The parser does not read or branch on `schemaVersion`. Unknown root fields fall into the existing `default: skipValue()` branch of `parseWindows`.

**Why:** P3. There is only one schema (the current one). The producer emits `schemaVersion` for its own diagnostics; consumers do not need it.

## API Design

### `WidgetMopFlags` (extended)

```java
public static class WidgetMopFlags {
    public boolean directMop;
    public boolean transitiveMop;
    public String prompt;               // null when absent
    public String spinnerMode;          // null when absent
    public String contentDescription;   // null when absent
    public String tooltipText;          // null when absent
}
```

### `parseWidget` (extended signature)

```java
private static void parseWidget(JsonReader reader,
                                Map<String, boolean[]> bySignature,
                                Map<String, WidgetMopFlags> widgets,
                                int depth)
        throws IOException;
```

Preconditions: `reader` positioned at the start of a widget object; `depth >= 0`.

Postconditions: `reader` advanced past the widget's closing `}`; `widgets` map contains zero or more new entries (one per `idName` encountered transitively).

Errors: `IOException` if `depth > 8`; `IOException` if JSON shape is malformed (propagated from `JsonReader`).

## Data Flow

```
parseWindow → parseWidget(depth=0)
                 │
                 ├── reads idName, listeners, prompt, spinnerMode, contentDescription, tooltipText
                 ├── computes directMop/transitiveMop from listeners[] × bySignature
                 ├── puts (idName, flags) into widgets map
                 │
                 └── case "items": for each child:
                        parseWidget(depth=1)
                            │
                            └── case "items": for each grandchild:
                                   parseWidget(depth=2)  ...  depth ≤ 8
```

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `IOException` (depth > 8) | `parseWidget` guard | propagate to `MopData.load` catch | `MopData.load` returns `null`, logs WARN |
| `IOException` (malformed JSON) | `JsonReader` | propagate to `MopData.load` catch | same as above |
| `NumberFormatException` etc. | `JsonReader.nextString` on unexpected type | propagate via `Exception` catch in `MopData.load` | same as above |

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Producer emits an unforeseen field name that we silently drop | `default: skipValue()` in every JsonReader loop catches unknown fields without crashing; if `aperv:sata_mop` smoke shows missing data, surface as a follow-up |
| `idName` collision between a menu item and a submenu item in the same window | Producer contract (gh57 D7) guarantees uniqueness; `Map.put` last-wins makes regressions observable in tests |
| Recursive `parseWidget` stack growth on malformed JSON | Hard depth cap of 8 with `IOException` |
| The four metadata fields accumulate kitchen-sinking pressure on `WidgetMopFlags` | Scope-locked to the v2 surface; future additions get their own change |
| Test fixtures drift from real producer output | Acceptance smoke after gh57 archives, using a real JSON from the 380-APK sweep |

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | Four new widget fields populated when JSON values are present | `MopDataTest.testParsesFourNewWidgetAttributes` | 1 |
| Unit | Four new widget fields are `null` when absent / explicit `null` | `MopDataTest.testNewWidgetFieldsNullWhenAbsent` | 1 |
| Unit | Recursive `items[]` flattens submenu into enclosing window's `widgets` map | `MopDataTest.testRecursiveOptionsMenuItems` | 1 |
| Unit | Submenu item handlers participate in MOP cross-reference | `MopDataTest.testSubmenuHandlerReachesMop` | 1 |
| Unit | Recursion depth > 8 results in `MopData.load` returning `null` | `MopDataTest.testRecursionDepthCapped` | 1 |
| Integration | Real post-gh57 JSON loads end-to-end | Deferred to acceptance after gh57 archives — smoke on `cryptoapp.apk` JSON + 3-APK aperv run (gh57 task 9.5) | 1 manual |

## Open Questions

- None blocking. The depth cap (8) is conservative; revisit if a future producer change emits deeper nesting (unlikely — Android menus are flat or 1-level submenus).
