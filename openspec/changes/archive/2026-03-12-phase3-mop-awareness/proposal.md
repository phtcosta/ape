## Why

The `aperv:sata_mop` variant (introduced in Phase 4) is a placeholder — it behaves identically to `aperv:sata` because APE has no awareness of monitored operations (MOP specs). This change wires the static analysis JSON produced by the rv-android pipeline into APE's action priority system, so `sata_mop` actively steers exploration toward widgets that trigger spec-monitored methods. See [issue #4](https://github.com/phtcosta/ape/issues/4).

## What Changes

- **New class `MopData`** (`ape/utils/MopData.java`): parses the static analysis JSON (`windows[]` + `reachability[]`), cross-references widget listeners with MOP-reachable methods, and exposes a per-widget priority lookup.
- **New class `MopScorer`** (`ape/utils/MopScorer.java`): maps widget MOP reachability to integer priority boosts (+500 direct, +300 transitive, +100 activity-level).
- **Modified `Config.java`**: adds `ape.mopDataPath` config flag (null by default; set via `ape.properties` on device).
- **Modified `StatefulAgent.java`**: injects MOP scoring into `adjustActionsByGUITree()` after the base priority assignment; activates only when `mopData != null`.
- **Modified `aperv-tool/tool.py`** (rv-android repo): `sata_mop` variant pushes static analysis JSON to `/data/local/tmp/static_analysis.json` and adds `ape.mopDataPath=/data/local/tmp/static_analysis.json` to `ape.properties`.

## Capabilities

### New Capabilities

- `mop-guidance`: MOP-guided action scoring in the APE Java core — `MopData` loader, `MopScorer` priority boost, `Config.mopDataPath` flag, and `StatefulAgent` injection point.

### Modified Capabilities

- `exploration`: `StatefulAgent.adjustActionsByGUITree()` gains optional MOP boost pass; invariants updated to reflect that action priority may now exceed the base SATA heuristic range when `mopDataPath` is set.
- `aperv-tool`: `sata_mop` variant wired — pushes static analysis JSON and sets `ape.mopDataPath` in `ape.properties` before launching APE.

## Impact

- **FR03** from `docs/PRD.md` ("MOP-guided exploration via `sata_mop` variant") is satisfied by this change.
- `StatefulAgent` is the only agent affected — `SataAgent`, `RandomAgent`, `ReplayAgent` inherit from it but the MOP pass is gated on `mopData != null`, so all existing variants are unaffected.
- No new device permissions required — `MopData.load()` reads a file already pushed to `/data/local/tmp/` by ADB.
- `ape.jar` (builtin rv-android tool) is not affected — it runs the unmodified APE.
- The rv-android `aperv-tool` module requires a separate change to wire `sata_mop` (push JSON + set property).
