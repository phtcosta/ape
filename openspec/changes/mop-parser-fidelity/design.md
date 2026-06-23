## Context

`MopData` parses the static-analysis JSON into the widget→MOP-flag map that `MopScorer` reads. The parse step `MopData.parseWindows` (`MopData.java:308-320`) stores each base activity's widgets in a `Map<String idName, Widget>` and overwrites on duplicate keys (last-write-wins), and it stores empty-`idName` widgets under the `""` key. The cmpmop measurement (169 JCA APKs, `docs/20260622_investigacao_mop.md` §1/§3) shows this discards 1,165 / 2,578 flagged widgets across 12 of the 19 substrate APKs, demoting their `+500`/`+300` boosts to the uniform `+100` activity fallback before `MopScorer` runs. A second defect in the same parser (`MopData.java:460-478`) keys the WTG convenience view by the full window name (e.g. `MainActivity#OptionsMenu`) while the runtime consumer (`StatefulAgent` WTG pass) queries by base activity (`newState.getActivity()` = `MainActivity`), so menu-sourced steering edges are never found.

This change is consumer-side only; the rvsec-gator JSON contract is unchanged. It precedes change #2 (discriminative boost), which depends on the restored substrate.

## Architecture

```
static_analysis.json
        │  MopData.load → parseWindows (Pass 2)        ← THIS CHANGE
        ▼
  widgetData: Map<baseActivity, Map<shortId, Widget>>  ← retention rule (collision / empty-id)
  wtgTransitions: Map<baseActivity, List<WtgTransition>> ← base-activity keying (W)
        │  getWidget(activity, shortId) / getWtgTransitions(activity)   (unchanged signatures)
        ▼
  MopScorer.score / scoreWtg  →  StatefulAgent.adjustActionsByGUITree   (unchanged)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `MopData.parseWindows` | Build the widget map with collision/empty-id retention | `windows[]` JSON, `bySignature` | `widgetData` map |
| `MopData.mopRank` (new, private) | Rank a widget's MOP strength for collision resolution | `Widget` | `int` (2=direct, 1=transitive, 0=none) |
| `MopData` WTG view construction (`:460-478`) | Build `wtgTransitions` keyed/targeted by base activity | `transitions[]`, `windowsById` | `wtgTransitions` map |
| `MopData.getWidget` / `getWtgTransitions` | Lookup by base activity + shortId | `activity`, `shortId` | `Widget` / `List<WtgTransition>` (unchanged) |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| `mop-guidance` MODIFIED "MopData — Static Analysis JSON Loader" (collision retention) | `parseWindows` collision branch + `mopRank` | `MopDataTest` collision case (synthetic JSON, two same-`idName` widgets) |
| INV-MOP-09 (strongest-flag-wins) | `parseWindows` only overwrites when `mopRank(incoming) > mopRank(resident)` | `MopDataTest` flagged-not-overwritten |
| INV-MOP-10 (empty-id not bucketed) | `parseWindows` skips `idName.isEmpty()`, increments `droppedFlaggedNoId` | `MopDataTest` empty-id absent + counter |
| `wtg-navigation` MODIFIED "MopData — WTG Parsing (Pass 3)" (base-activity keying) | `wtgTransitions.put(baseActivity(source.name), …)`, `WtgTransition(targetActivity = baseActivity(target.name))` | `MopDataTest`/`MopScorerTest` menu-transition reachable by base activity |
| INV-WTG-04 (base-activity key + target) | `baseActivity()` applied to source key and stored target | `MopScorerTest` scoreWtg fires for `#OptionsMenu`-sourced edge queried by base activity |

## Goals / Non-Goals

**Goals:**
- A flagged widget is never overwritten by an unflagged sibling sharing its `shortId` within a base activity.
- Empty-`idName` widgets no longer pollute a shared `""` bucket; their flagged drops are counted and logged.
- WTG steering edges sourced from menu/fragment windows are reachable when the runtime is on the base activity.
- Byte-identical behavior where no collisions, empty ids, or `#`-suffixed WTG windows exist.

**Non-Goals:**
- Recovering empty-`idName` widgets by matching on class/text/bounds (a separate, harder matcher — out of scope, P1).
- Changing `MopScorer` scoring weights or the `+100` activity fallback (that is change #2).
- Any rvsec-gator / JSON-contract change (`static-analysis-entrypoints` untouched).
- Merging per-event-type flag maps across colliding widgets (negligible loss; P1).

## Decisions

**D1 — Collision resolution = strongest-MOP-flag-wins (vs. merge or `List<Widget>`).** On `shortId` collision keep the widget with the higher `mopRank` (`direct > transitive > unflagged`); on a tie keep the resident. Rationale: the scorer needs only one flagged widget under a `shortId` for the runtime match to earn its boost; storing a list or merging per-event-type maps adds structure the scorer does not consume (P1). The rare loss of a second widget's event-type nuance is immaterial — colliding same-`shortId` widgets are typically the same logical control across fragments.

**D2 — Empty `idName` = do not bucket, count and log (vs. attribute matching).** `extractShortId(resourceID)` never yields `""` for a runtime widget that has a resource id, so the `""` bucket is unreachable by `getWidget`; bucketing only risks overwriting and hides the loss. Skip storage, increment `droppedFlaggedNoId`, and log the per-load total. Matching these widgets by class/text/bounds is deferred (P1) — it is a new matcher, not a fidelity fix.

**D3 — W: base-activity the WTG source key AND the stored target (vs. base-activity only the source, or fix at query time).** `activityHasMop` and the runtime consumer both key by base activity; the WTG view must align on both ends. Keying the source by `baseActivity(source.name)` makes menu-window edges (`MainActivity#OptionsMenu`) reachable when on `MainActivity`; storing `baseActivity(target.name)` makes `activityHasMop(targetActivity)` resolve. Doing it at storage (one source of truth) is simpler than base-activity-izing at every `scoreWtg` query.

**D4 — `mopRank` is a private static helper in `MopData`.** Single use site (the collision branch); no public surface.

## API Design

### `private static int mopRank(Widget w)`
- **Returns** `2` if `w.directMop`, else `1` if `w.transitiveMop`, else `0`. Pure; null-free (callers pass non-null).

### `parseWindows(...)` — widget storage loop (modified)
- **Precondition:** `w.widgets` parsed; `w.name != null`; `activity = baseActivity(w.name)`.
- **Postcondition:** for each widget `wd`: if `wd.directMop || wd.transitiveMop` then `mopActivities.add(activity)` (unchanged); if `wd.idName == null || wd.idName.isEmpty()` then it is NOT stored and `droppedFlaggedNoId` is incremented when flagged; else `widgets.put(wd.idName, wd)` happens only when no resident exists or `mopRank(wd) > mopRank(resident)`.
- **Error behavior:** none (no new exceptions; parser remains null-safe per INV-MOP-01).

### WTG view construction (`:460-478`, modified)
- **Postcondition:** for each `click` event, append `new WtgTransition(widgetName, widgetClass, baseActivity(target.name))` to `wtgTransitions.get(baseActivity(source.name))`.
- Lookups `getWtgTransitions(activity)` and `getWidget(activity, …)` keep their signatures; the only change is the keys stored.

### Observability
- `MopData` gains a private `int droppedFlaggedNoId` accumulated during `parseWindows`; at end of `load()` a single `Logger` line reports `[APE-RV] MopData: dropped <N> flagged widgets with no resource id`.

## Data Flow

JSON → `parseWindows` builds `widgetData`/`wtgTransitions` with base-activity keys and strongest-flag widgets → `getWidget`/`getWtgTransitions` (unchanged) → `MopScorer.score`/`scoreWtg` → `StatefulAgent`. No runtime path beyond the map contents changes.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| Malformed/missing JSON | `MopData.load` | Existing: catch, return `null` (INV-MOP-01) | unchanged |
| Widget with `idName == ""` | `parseWindows` | Skip storage, count, log | widget contributes only via `activityHasMop` (+100) |
| Duplicate `shortId` | `parseWindows` | Keep strongest-MOP widget | flagged widget retained |

## Risks / Trade-offs

- **[Empty-id widgets still unscorable at the widget level]** → accepted and made visible via the drop counter; affects labnex/duress (no resource ids). Not fixable by id; deferred.
- **[Base-activity WTG keying merges menu and base transitions under one key]** → intended: when on the base activity (menu open or not) all those widgets are candidates; `activityHasMop` is already base-keyed.
- **[Menu scenario behavior change in `wtg-navigation` spec]** → the existing `#OptionsMenu`-keyed scenario is updated; this is a correction of a latent bug (consumer never queried that key), not a regression.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | Collision: flagged widget not overwritten by unflagged sibling | `MopDataTest` synthetic JSON, two widgets same `idName` | ~2 |
| Unit | Empty-id: not stored under `""`, `droppedFlaggedNoId` counts flagged | `MopDataTest` synthetic JSON | ~2 |
| Unit | WTG base-activity keying: `#OptionsMenu`-sourced edge reachable via base activity; target resolves `activityHasMop` | `MopDataTest`/`MopScorerTest` | ~2 |
| Unit (regression) | No-collision / no-empty-id JSON yields identical map | existing `MopDataTest` cases stay green | — |

Tests load synthetic JSON through the real parser (as the gh13 parser tests do — `MopDataTest` §15), since the collision/empty-id logic lives in `parseWindows` and is bypassed by `MopData.forTest`.

## Open Questions

- None blocking. The exact synthetic-JSON fixture form follows the existing `MopDataTest`/`MopScorerTest` JSON-string pattern.
