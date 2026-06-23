## Why

`MopData` discards 45% of the MOP-flagged widgets it parses before scoring ever runs. Widgets are stored in a per-base-activity `Map<idName, Widget>` with last-write-wins (`MopData.parseWindows`, `MopData.java:308-320`): widgets sharing an `idName` overwrite each other, and widgets with an empty `idName` all collapse into one `""` bucket. Measured on the cmpmop run (169 JCA APKs), this drops 1,165 / 2,578 flagged widgets across 12 of the 19 APKs that have a discriminative substrate, demoting their `+500`/`+300` per-widget boosts to the uniform `+100` activity fallback (two APKs lose 100% of per-widget granularity). This silently erodes the substrate that MOP guidance depends on, before any scoring or selection logic sees it. A related keying mismatch in the WTG pass silences valid navigation-steering edges in the same parser. (Context: `docs/20260622_investigacao_mop.md` §1/§3/§7-#0; verified in source.)

## What Changes

- **Widget collision** (`mop-guidance`): on `idName` collision within a base activity, retain the widget with the strongest MOP flag (`direct > transitive > unflagged`) instead of last-write-wins, so a flagged widget is never overwritten by an unflagged sibling. Recovers ~730 flagged widgets.
- **Empty `idName`** (`mop-guidance`): stop bucketing widgets whose `idName` is empty (the `""` key is unreachable at runtime — `extractShortId(resourceID)` never yields `""` for a real widget). Count the flagged-but-id-less widgets dropped and log the per-load total for observability. These ~435 widgets remain unaddressable by resource id by their nature; matching them by class/text is explicitly out of scope (P1).
- **WTG keying** (`wtg-navigation`, sub-fix W): key the `wtgTransitions` view by base activity (strip the `#`-suffix from the source window name) and store transition targets as base activities, so `MopScorer.scoreWtg`'s base-activity query (`shortId == widgetName && activityHasMop(targetActivity)`) matches. Recovers ~34 silenced steering edges across keepitup/sambalite/syncthingfork.
- No new configuration flags. Output is byte-identical where no `idName` collisions, empty ids, or `#`-suffixed WTG windows exist. Not **BREAKING** — recovers signal that was being discarded; no consumer API changes.

## Capabilities

### New Capabilities

(none — this corrects existing parsing behavior)

### Modified Capabilities

- `mop-guidance`: the "MopData — Static Analysis JSON Loader" requirement gains a deterministic widget-retention rule (strongest-MOP-flag wins on `idName` collision; empty-`idName` widgets are not bucketed and their flagged drops are counted/logged). Today's behavior (last-write-wins, empty-id collapse) is unspecified and lossy.
- `wtg-navigation`: the WTG parsing-pass requirement gains base-activity keying for both the `wtgTransitions` map key and the stored transition target, aligning the stored form with the base-activity query in `MopScorer.scoreWtg` and `activityHasMop`.

## Impact

- **Components:** `MopData.parseWindows` (`MopData.java:308-320`, widget map) and the WTG convenience-view construction (`MopData.java:460-478`). Consumers `MopScorer.score`/`scoreWtg` and `StatefulAgent.adjustActionsByGUITree` are unchanged — they read `getWidget`/`getWtgTransitions` exactly as today.
- **No producer change:** the rvsec-gator JSON contract (`static-analysis-entrypoints`) is untouched; this is a consumer-side parsing fix.
- **Sequencing:** precedes change #2 (discriminative boost) — #2 makes the `+500`/`+300` boost decisive, which requires the flagged widgets restored here; without #0, ~half are already overwritten before scoring.
- **Validation:** unit-testable via `MopData.forTest(...)` without a device (collision resolution, empty-id non-pollution + drop counter, base-activity WTG lookup); end-to-end in the 19-APK fair-test re-run (`docs/20260622_investigacao_mop.md` §7.5).
