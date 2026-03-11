# Specification: MOP-Guided Action Scoring

## Status

**Not yet implemented.** This capability is planned for Phase 3 of APE-RV development.

## Planned Purpose

This specification will describe the MOP-guided action scoring system (`sata_mop` variant) that loads static reachability analysis data produced by rv-android's REACH pipeline and applies a priority boost to widget-targeted actions whose handlers reach monitored API methods.

When Phase 3 is implemented, this document will specify:
- `MopData` class: JSON loader that cross-references `windows[]` and `reachability[]` arrays
- `MopScorer` class: priority boost computation (+500 direct, +300 transitive, +100 activity-level)
- `ape.mopDataPath` configuration flag
- Integration point in `StatefulAgent.adjustActionsByGUITree()`

Implementation will be tracked via the corresponding OpenSpec change artifact under `openspec/changes/`.
