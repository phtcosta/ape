## Context

`MopData` parses the static-analysis JSON into the widget→MOP-flag map that `MopScorer` reads. The parse step `MopData.parseWindows` (widget-store loop `MopData.java:326-349`) stores each base activity's widgets in a `Map<String idName, Widget>` and overwrites on duplicate keys (last-write-wins), and it stores empty-`idName` widgets under the `""` key. The cmpmop measurement (169 JCA APKs, `docs/20260622_investigacao_mop.md` §1/§3) shows this discards 1,165 / 2,578 flagged widgets across 12 of the 19 substrate APKs, demoting their `+500`/`+300` boosts (to the activity-level fallback, which change #2 later removes) before `MopScorer` runs. A second defect in the same parser (the WTG convenience view built inside `parseTransitions`, `MopData.java:496-520`) keys the view by the full window name (e.g. `MainActivity#OptionsMenu`) while the runtime consumer (`StatefulAgent` WTG pass) queries by base activity (`newState.getActivity()` = `MainActivity`), so menu-sourced steering edges are never found. Realigning the key to the base activity also requires re-pointing the OPTIONSMENU-gateway precompute (`MopData.precomputeMopOptionsMenus`, `:621-659`), the view's other consumer, to the base key — otherwise it would lose the gh13 menu-gateway boost (see D3a).

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
| `MopData` WTG view construction (`:496-520`, inside `parseTransitions`) | Build `wtgTransitions` keyed/targeted by base activity | `transitions[]`, `windowsById` | `wtgTransitions` map |
| `MopData.precomputeMopOptionsMenus` (`:621-659`) | Query the base-keyed view for the OPTIONSMENU-gateway set | `windows`, `wtgTransitions`, `mopActivities` | `Set<baseActivity>` |
| `MopData` DIALOG re-key post-pass (new, in `load()` after `parseTransitions`, `~:211`) | Merge DIALOG-window widgets into the host activity's map via WTG edges | `windows`, `transitions[]` | `widgetData[host]` updated |
| `MopData.getWidget` / `getWtgTransitions` | Lookup by base activity + shortId | `activity`, `shortId` | `Widget` / `List<WtgTransition>` (unchanged) |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| `mop-guidance` MODIFIED "MopData — Static Analysis JSON Loader" (collision retention) | `parseWindows` collision branch + `mopRank` | `MopDataTest` collision case (synthetic JSON, two same-`idName` widgets) |
| INV-MOP-19 (strongest-flag-wins) | `parseWindows` only overwrites when `mopRank(incoming) > mopRank(resident)` | `MopDataTest` flagged-not-overwritten |
| INV-MOP-20 (empty-id not bucketed) | `parseWindows` skips `idName.isEmpty()`, increments `droppedFlaggedNoId` | `MopDataTest` empty-id absent + counter |
| `wtg-navigation` MODIFIED "MopData — WTG Parsing (Pass 3)" (base-activity keying) | `wtgTransitions.put(baseActivity(source.name), …)`, `WtgTransition(targetActivity = baseActivity(target.name))` | `MopDataTest`/`MopScorerTest` menu-transition reachable by base activity |
| INV-WTG-04 (base-activity key + target) | `baseActivity()` applied to source key and stored target | `MopScorerTest` scoreWtg fires for `#OptionsMenu`-sourced edge queried by base activity |
| INV-WTG-05 (all consumers query by base activity) | `precomputeMopOptionsMenus` queries `wtgTransitions.get(activity)` (base) not `get(w.name)` (`:644`) | `MopDataTest.testActivitiesWithMopOptionsMenuPrecomputed` (gateway "C" still qualifies) |
| `mop-guidance` ADDED "MopData — DIALOG Window Re-Keying to Host Activity" / INV-MOP-25 | `load()` post-pass between `:209` and `:219`: for each `type==DIALOG` window, find an incoming transition, merge the parsed `widgetData[dialogClass]` entries into `widgetData[host]` via `mopRank` (move-not-copy), and promote `host` into `mopActivities` on a flagged merge (D6) | `MopDataTest` dialog re-key: widget resolvable via host; collision keeps strongest; host `activityHasMop` true; dialog-class key removed; orphan dialog counted on `[APE-RV]` line & not resolvable |

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

**D2 — Empty `idName` = do not bucket, count and log (vs. attribute matching).** `extractShortId(resourceID)` returns `""` for any runtime node without a resource id, so the `""` map key IS reachable at runtime — but a single `""` bucket collapses every id-less widget of an activity together, so a `getWidget(activity, "")` hit would return an arbitrary one and bucketing risks overwriting a flagged widget with an unflagged sibling. The fidelity-preserving choice is therefore to skip storage, increment `droppedFlaggedNoId`, and log the per-load total, rather than store under the colliding key. Matching these widgets by class/text/bounds is deferred (P1) — it is a new matcher, not a fidelity fix.

**D3 — W: base-activity the WTG source key AND the stored target (vs. base-activity only the source, or fix at query time).** `activityHasMop` and the runtime consumer both key by base activity; the WTG view must align on both ends. Keying the source by `baseActivity(source.name)` makes menu-window edges (`MainActivity#OptionsMenu`) reachable when on `MainActivity`; storing `baseActivity(target.name)` makes `activityHasMop(targetActivity)` resolve. Doing it at storage (one source of truth) is simpler than base-activity-izing at every `scoreWtg` query.

**D3a — Re-point the OPTIONSMENU-gateway precompute to the base key.** `MopData.precomputeMopOptionsMenus` (`MopData.java:621-659`) is a *second* consumer of `wtgTransitions`, and it currently looks the view up by the full `#OptionsMenu` window name. Once D3 collapses the source key to the base activity, that lookup would return `null` and the gh13 menu-gateway boost (`activityHasMopOptionsMenu` → `scoreOpenMenu`, +`mopWeightOpenMenu`) would silently stop firing. The precompute already computes the base activity locally (`activity = w.name` truncated at `#`); the fix is to query `wtgTransitions.get(activity)` instead of `get(w.name)`. Consequence: the gateway test widens from "a *menu-item* click navigates to a MOP activity" to "*any* base-activity click edge navigates to a MOP activity" — a deliberate over-approximation that never misses a real gateway and, at worst, applies the open-menu boost where a non-menu widget also reaches the MOP path. Keeping a separate suffix-keyed index just to preserve that distinction is more machinery for a heuristic boost (P1); the existing test `MopDataTest.testActivitiesWithMopOptionsMenuPrecomputed` already pins the recovered behavior.

**D4 — `mopRank` is a private static helper in `MopData`.** Single use site (the collision branch); no public surface.

**D5 — DIALOG windows re-keyed to the host activity via WTG edges (vs. leaving them under the dialog-class key).** A DIALOG window's `name` is the dialog class (`android.app.AlertDialog`), which never equals `newState.getActivity()` at runtime, so its widgets' MOP flags were structurally unreachable for scoring (~86 flagged widgets across 5/169 apps). The same JSON's `transitions[]` carry the activity→dialog edges, so after transitions are parsed, `load()` re-keys each DIALOG window to the `baseActivity(source.name)` of an incoming transition and merges its widgets into that activity's map under the same `mopRank` collision policy (INV-MOP-19). Orphan dialogs (no incoming transition) stay under the dialog-class key and are counted. Consumer-side only; no producer/JSON change.

Implementation constraints (verified against `MopData.load` after change-A drift, 2026-07-05):
- **Ordering.** The pass runs after `parseTransitions` (`:209`) and **before `precomputeMopOptionsMenus` (`:219`)** and before the change-A status line (`:251`) — both read `mopActivities`/`widgetData`, which this pass mutates. The `MopData` constructor *aliases* these maps (no copy), so mutating the locals is sufficient, but the ordering vs. the precompute/status-line reads is what matters.
- **Merge source = the parsed `widgetData[baseActivity(dialogWindow.name)]` map**, not the raw `Window.widgets`, so the Pass-2 empty-id filter (`:332-339`) is inherited rather than re-implemented (A1).
- **Move, not copy — `widgetData` only.** Remove the dialog-class key entry from `widgetData` after merging so `countWidgets`/`countFlagged` on the change-A status line do not double-count the same widgets (A2). Do **not** apply this move to `mopActivities` (next bullet).
- **Retain the dialog-class entry in `mopActivities` (asymmetric to the `widgetData` move).** Pass 2 already added `baseActivity(dialogWindow.name)` to `mopActivities` (`:330`), and every WTG click edge *into* the dialog stores `targetActivity == baseActivity(dialogWindow.name)` (`:506`). The gateway precompute's condition 2 tests `mopActivities.contains(t.targetActivity)` (`:646`), so this entry is precisely what makes an activity that navigates to a MOP-bearing dialog qualify as a menu-gateway. Removing it for A2-style symmetry would silently disable that detection (the D3a footgun). `getWidget` is never queried with a dialog class, but the WTG gateway view is — so the move is safe for `widgetData` and unsafe for `mopActivities`. D6's host promotion is *additive*, not a replacement for this entry.
- **Orphan diagnostic on a separate `[APE-RV]` line**, never the `[APE-MOP-DATA]` status line — that line's exact field set is owned by `experiment-validity` / INV-MOP-21 (F2; see Cross-change reconciliation).

**D6 — a reachable dialog's flagged widgets promote the host into `mopActivities` (vs. `getWidget`-resolvable only).** When the re-key merges a flagged dialog widget into `widgetData[host]`, the pass also adds `host` to `mopActivities`. Rationale: `parseWindows` maintains the invariant "an activity with a flagged widget is in `mopActivities`" (`:330`); the re-key must preserve it, otherwise `getWidget(host, id)` returns a flagged widget while `activityHasMop(host)` is `false` — an internal inconsistency that also hides dialog-only MOP substrate from the gateway precompute (condition 2 tests `mopActivities.contains(target)`). INV-MOP-25 is widened from "widgets queryable under host" to "…and the host's MOP-activity status reflects them". Scope note: this only *adds* activities that genuinely hold a flagged widget post-merge; it never flags an activity with no flagged widget. The dialog window's own `mopActivities` entry is retained independently (see the "Retain the dialog-class entry" constraint under D5) — D6 adds the host on top; it does not move the dialog class off.

## API Design

### `private static int mopRank(Widget w)`
- **Returns** `2` if `w.directMop`, else `1` if `w.transitiveMop`, else `0`. Pure; null-free (callers pass non-null).

### `parseWindows(...)` — widget storage loop (modified)
- **Precondition:** `w.widgets` parsed; `w.name != null`; `activity = baseActivity(w.name)`.
- **Postcondition:** for each widget `wd`: if `wd.directMop || wd.transitiveMop` then `mopActivities.add(activity)` (unchanged); if `wd.idName == null || wd.idName.isEmpty()` then it is NOT stored and `droppedFlaggedNoId` is incremented when flagged; else `widgets.put(wd.idName, wd)` happens only when no resident exists or `mopRank(wd) > mopRank(resident)`.
- **Error behavior:** none (no new exceptions; parser remains null-safe per INV-MOP-01).

### WTG view construction (`:496-520`, inside `parseTransitions`, modified)
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
- **[OPTIONSMENU-gateway precompute would break without D3a]** → re-keying the source to the base activity orphans the precompute's `get(w.name)` lookup; D3a re-points it to the base key. The widened gateway test (any base-activity edge vs. menu-only) is a deliberate over-approximation — it never under-qualifies, and the worst case is a redundant open-menu boost. `MopDataTest.testActivitiesWithMopOptionsMenuPrecomputed` guards the recovered behavior.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | Collision: flagged widget not overwritten by unflagged sibling | `MopDataTest` synthetic JSON, two widgets same `idName` | ~2 |
| Unit | Empty-id: not stored under `""`, `droppedFlaggedNoId` counts flagged | `MopDataTest` synthetic JSON | ~2 |
| Unit | WTG base-activity keying: `#OptionsMenu`-sourced edge reachable via base activity; target resolves `activityHasMop` | `MopDataTest`/`MopScorerTest` | ~2 |
| Unit (regression) | No-collision / no-empty-id JSON yields identical map | existing `MopDataTest` cases stay green | — |

Tests load synthetic JSON through the real parser (as the gh13 parser tests do — `MopDataTest` §15), since the collision/empty-id logic lives in `parseWindows` and is bypassed by `MopData.forTest`.

## Cross-change reconciliation

gh13-mopdata-schema-v2 is archived (2026-06-23); the base `mop-guidance` spec is in its post-gh13/gh60 Target-vocabulary form (`reachesTarget`/`directlyReachesTarget`, INV-MOP-09..18). Two reconciliations apply:

- **Invariant numbering.** gh13 occupies `INV-MOP-07`..`INV-MOP-18`. This change numbers its new invariants `INV-MOP-19` (strongest-flag-wins), `INV-MOP-20` (empty-id not bucketed), and `INV-MOP-25` (DIALOG re-keying). Sibling in-flight changes hold 21/22 (experiment-validity), 23 (exploration-effectiveness), 24 (discriminative-boost) — no clash (verified 2026-07-05 across all change specs).
- **`[APE-MOP-DATA]` status line is owned by `experiment-validity` (INV-MOP-21).** That change fixed the single per-load status line to an exact field set (`package/windows/widgets/flagged/droppedNoId/transitions`), with tests asserting those substrings. This change's DIALOG pass (a) must not add fields to that line — its orphan-dialog count goes on a separate `[APE-RV]` line — and (b) mutates `widgetData`/`mopActivities` before the line is emitted, so `widgets`/`flagged` legitimately include merged dialog widgets. The cryptoapp fixture has no DIALOG windows, so change A's existing status-line assertions are unaffected; the interaction only surfaces on dialog-bearing corpora. Archive order is independent (both are consumer-side and touch disjoint field sets of the same log line).
- **Shared requirement, rebased.** This change MODIFIES `MopData — Static Analysis JSON Loader`. Its delta body reproduces the current gh60 base requirement (full typed-model list, per-event-type maps, gh60-D15 component fields, and all base scenarios preserved) and grafts on only the collision-retention paragraph (INV-MOP-19), the empty-id paragraph (INV-MOP-20), and their three scenarios — so archiving it does not revert gh13's vocabulary or gh60's typed-model content. The single-argument `MopData.load(path)` overload is deleted by `experiment-validity` (which owns the `MopData — Package / MainActivity Sanity Check` requirement); this change's loader body references only the 3-arg form, consistent with that deletion regardless of archive order.

## Open Questions

- None blocking. The exact synthetic-JSON fixture form follows the existing `MopDataTest`/`MopScorerTest` JSON-string pattern.
