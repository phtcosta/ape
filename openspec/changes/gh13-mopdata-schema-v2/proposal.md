## Why

Upstream change `gh57-static-analysis-overhaul` in `rvsec/rv-android` reshapes the static-analysis JSON produced by `RvsecAnalysisClient` and explicitly defers the ape-repo `MopData.java` update out of its scope (gh57 tasks 7.3 / 7.4 / 7.5 / 7.7 — "owner will handle the ape repo update separately after gh57 archives"). The new JSON shape carries widget metadata and OPTIONSMENU items that the current `MopData.parseWidget` ignores — defeating `aperv:sata_mop` scoring on apps whose security-relevant behavior is reached through options-menu interactions (e.g. `cryptoapp` and the broader 380-APK ground-truth corpus from gh57). Tracked as GitHub issue #13.

Per project principle **P3 (no backward compatibility)**, this change replaces the parser — there is no v1/v2 split, only the post-gh57 schema. Legacy JSON files (the 158 pre-existing in `…/APKS_JCA_analise_estatica_soot/`) are obsoleted by the gh57 ground-truth re-run.

## What Changes

- Extend `MopData.WidgetMopFlags` with four nullable `String` fields parsed from the widget shape: `prompt`, `spinnerMode`, `contentDescription`, `tooltipText`. Each defaults to `null` when the JSON field is absent or `null`.
- Extend `MopData.parseWidget` to walk `items[]` recursively (depth cap 8) so OPTIONSMENU submenu items are merged into the enclosing window's widget map under their `idName`. Listener handlers on submenu items participate in MOP-reachability cross-referencing identically to top-level items.
- `MopScorer` is **not** modified. `hasWtgData()` already handles the `windows[]` populated + `transitions: []` case introduced by gh57's `--skip-wtg` / WTG-timeout decoupling.
- **BREAKING**: pre-gh57 JSON files are no longer a supported input. The producer is the source of truth; the parser targets the current shape only.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `mop-guidance`: the `MopData — Static Analysis JSON Loader` requirement is extended to parse four new optional widget XML attributes into `WidgetMopFlags` and to recurse into `items[]` for OPTIONSMENU submenu items.

## Impact

- `src/main/java/com/android/commands/monkey/ape/utils/MopData.java` — `parseWidget` parses four new fields and recurses into `items[]`; `WidgetMopFlags` gains four nullable `String` fields.
- `src/test/java/com/android/commands/monkey/ape/utils/MopDataTest.java` — new fixture-based tests for the four widget attributes, recursive submenu items, and null defaults.
- No build or dependency changes. No public method signatures change.
- Cross-repo coordination: validation requires a real JSON from the gh57 ground-truth re-run (380 originals at `/home/pedro/desenvolvimento/RV_ANDROID_NOVO/JOAO/APKs`). After this change merges, `ape-rv.jar` must be rebuilt and `ape.jar` refreshed (`cp target/ape-rv.jar ape.jar`) before the aperv smoke (gh57 task 9.5).
