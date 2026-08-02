# Tasks — rearch-05-thin-python-arms

Cross-repo change: all edits land in rv-android (`modules/aperv-tool/`); the ape jar is **not** modified (presets/fail-fast/echo are stage-2 deliverables, already deployed). Constraints from `design.md` apply to every group: the 29 arm names are frozen (INV-APV-42), the regeneration diff must be empty after every arm-editing task (INV-APV-44), and no task may add any `RUN_START` read-back (INV-APV-43, owner D1). Group order is dependency order; groups 3–6 each end by re-running the migration diff.

## 1. Preconditions, inventory, and baseline capture (BEFORE any edit)

- [ ] 1.1 Verify the stage-2 dependency: the ape-rv.jar deployed in `modules/aperv-tool/src/aperv_tool/tools/aperv/` resolves presets, fail-fast rejects an unknown key, and emits `RUN_START` (one smoke run on the RVSec AVD; record jar sha256 and the ape repo commit it was built from). If any piece is missing, STOP — this change is blocked on `rearch-02-runspec`
- [ ] 1.2 Confirm the rearch-02 resolution holds in the deployed jar (design Open Question 1, RESOLVED): `ape.apePureMode` aborts as a retired key and an unknown `--ape` value aborts — confirming `ape_pure` and `bfs` are retired variants (deleted in group 3; owner ratified at artifact approval)
- [ ] 1.3 Record the status of open rv-android changes touching the same arms (`gh88-cal-llm-control`, `gh90-e3-decisive-run-setup`); rebase this change on whatever is merged — never edit arms owned by an in-flight change
- [ ] 1.4 Re-verify the inventory at HEAD: 29 arms in `get_variants()`, 52 pairs in `APERV_PROPERTY_MAPPING`, 18 `ARM_DEFINING_KEYS`, 11 `LLM_ARM_KEYS`, 18-key `_APE_PURE_ARM_FLAGS`; correct the design's numbers if the tree moved
- [ ] 1.5 Sweep every mapped `ape.*` key against the deployed jar's accepted-key vocabulary (RunSpec/Feature tables, not the old `Config`); record the dead-key list (expected: `ape.mopWeightActivity`, `ape.apePureMode`; possibly more post-stage-2/4)
- [ ] 1.6 Write `tests/migration/capture_arm_baseline.py` and generate `tests/migration/arm_effective_baseline.json` covering all 29 arms' effective configurations from the **unmodified** tool.py; commit both
- [ ] 1.7 Write `tests/migration/test_arm_regeneration_diff.py` (per-arm empty-diff assertion against the baseline; not-yet-migrated arms compare via their current expansion so the test is green from day one)
- [ ] 1.8 Run `/sdd-test-run modules/aperv-tool` (suite + migration test green pre-change)

## 2. Properties writer and configure() (mechanism before arms)

- [ ] 2.1 Restate `_push_properties()` per design D4: `ape.preset` line first, `ape.mopDataPath` when pushed, then override pass-through only; `ConfigurationError` on an unmapped override key; bool serialization unchanged
- [ ] 2.2 Extend `configure()` validation: `preset` present and non-empty, `overrides` a dict (keep strategy validation as-is)
- [ ] 2.3 Update/restate the properties-writer and configure tests (`TestPushProperties`, `TestArmProperties`, `TestConfigure`) for the new output contract; keep seed-not-in-properties and lowercase-bool assertions
- [ ] 2.4 Run `/sdd-test-run modules/aperv-tool`

## 3. Arm re-expression — baseline arms

- [ ] 3.1 Re-express `default`, `sata`, `random` as `preset="aperv"` + empty/near-empty overrides (derive deltas as `effective(arm) − effective(preset)`, per design D3)
- [ ] 3.2 Delete the retired variants `ape_pure` and `bfs` (design D2/Open Question 1 resolution); record both as documented removals in the arm report
- [ ] 3.3 Re-run the regeneration diff — must be empty for all migrated arms, with the two retirements listed as documented removals
- [ ] 3.4 Run `/sdd-test-run modules/aperv-tool`

## 4. Arm re-expression — MOP arms

- [ ] 4.1 Re-express `sata_mop_widget` (+ `sata_mop` alias, same object) as `preset="mop"`, `mop_data` kept top-level
- [ ] 4.2 Re-express `sata_mop_activity` and `sata_mop_act_frontier` as `preset="mop"` + reach deltas
- [ ] 4.3 Re-run the regeneration diff — must be empty for all 29 arms
- [ ] 4.4 Run `/sdd-test-run modules/aperv-tool`

## 5. Arm re-expression — LLM arms

- [ ] 5.1 Re-express `sata_llm` (`preset="llm"`) and `sata_mop_llm` (`preset="llm_mop"`, empty overrides)
- [ ] 5.2 Re-express the six frozen gh43 arms as `preset="llm_mop"` + prompt/dose deltas (design D8: frozen means effective-config-frozen; any diff vs their old jar-default inheritance goes to the owner as a declared divergence, never silently absorbed)
- [ ] 5.3 Re-express `cal_a1`…`cal_a9` as `preset="llm_mop"` + frontier deltas + per-arm LLM deltas; carry the per-arm hypothesis comments (H1/H2/H3, control lineage) onto the new dicts
- [ ] 5.4 Re-run the regeneration diff — must be empty for all 29 arms
- [ ] 5.5 Run `/sdd-test-run modules/aperv-tool`

## 6. Arm re-expression — gh90 decisive-run arms

- [ ] 6.1 Re-express `mop_on_llm_off`, `mop_off_llm_off`, `mop_on_llm_70` per design D3(c)/(d); keep the INV-APV-29/30 rationale comments (MOP-off keeps `mop_data` and navigation) and the normative-name comment at the definition site
- [ ] 6.2 Keep `llm_snap_tolerance_px=150` as an ordinary `overrides` entry of `mop_on_llm_70`; keep `expected_jar_git_sha`/`expected_jar_sha256` Python-only (INV-APV-34 pairing untouched)
- [ ] 6.3 Re-run the regeneration diff — must be empty for all 29 arms
- [ ] 6.4 Run `/sdd-test-run modules/aperv-tool`

## 7. Dead keys and kill-switch duplication removal

- [ ] 7.1 Delete `mop_weight_activity` and `ape_pure_mode` from `APERV_PROPERTY_MAPPING`, plus any further dead entries found by the 1.5 sweep
- [ ] 7.2 Delete the substrate spread dicts: `_BASELINE_ARM_FLAGS`, `_APE_PURE_ARM_FLAGS` (the 18-key kill-switch mirror), `_MOP_SUBSTRATE`, `_LLM_FLAGS`, `_FRONTIER_SUBSTRATE`, `_MOP_OFF_OVERRIDES`, `_CAL_LLM_COMMON`
- [ ] 7.3 Re-run the regeneration diff — must be empty for all 29 arms
- [ ] 7.4 Run `/sdd-test-run modules/aperv-tool`

## 8. Guard retirement

- [ ] 8.1 Delete `ARM_DEFINING_KEYS`, `LLM_ARM_KEYS`, `_ARM_DEFINING_EXEMPT` from `tool.py`; update the module docstring and `get_variants()` docstring to the preset+overrides contract (current-state comments only, P4)
- [ ] 8.2 Retire the constant-vs-constant guard tests in `tests/test_aperv_tool.py`: `TestArmDefiningGuard`, the INV-APV-14 explicitness and table-pin tests in `TestFrozenArmVariants`, the INV-APV-26/27 tests, the cal-arm plan-table pins, and the gh90 expansion-diff tests; delete `_EXPECTED_ARM_DEFINING_MAPPING` and companions
- [ ] 8.3 Restate the surviving structural assertions in their trivial form: 29 frozen names present, `sata_mop is sata_mop_widget`, gh90 single-factor contrasts asserted directly on `overrides` dicts, snap-tolerance pairing (INV-APV-34) and provenance (INV-APV-33) untouched
- [ ] 8.4 Verify by grep that `tool.py` contains no `RUN_START` parsing and no echo-vs-intent logic (INV-APV-43, owner D1)
- [ ] 8.5 Update `modules/aperv-tool/docs/architecture.md` and rv-android's `openspec/specs/aperv/spec.md` counterpart (in that repo's workflow): arm-definition requirements restated, "Arm-Defining Flag Completeness (FR20)" removed with the substitute recorded
- [ ] 8.6 Run `/sdd-test-run modules/aperv-tool`

## 9. Final verification and owner sign-off

- [ ] 9.1 Final full regeneration diff over all migrated arms; produce the human-readable per-arm report (empty, or the owner-approved declared divergences with their new arm names, plus the two documented retirements `ape_pure`/`bfs`)
- [ ] 9.2 One smoke run per preset family (`sata`, `sata_mop_act_frontier`, `sata_llm`, `sata_mop_llm`) on the RVSec AVD: run completes, `RUN_START` first line reconstructs the arm, `ape.properties` on device matches the preset+deltas contract
- [ ] 9.3 Run `/sdd-qa-lint-fix modules/aperv-tool`
- [ ] 9.4 Run `/sdd-verify modules/aperv-tool`
- [ ] 9.5 Invoke `/sdd-code-reviewer` via Skill tool
- [ ] 9.6 Owner sign-off task: present the final diff report and the smoke evidence; on approval, delete `tests/migration/test_arm_regeneration_diff.py` and archive `arm_effective_baseline.json` + the final diff output under `modules/aperv-tool/docs/` as the migration record (INV-APV-44 — the check is one-time)
- [ ] 9.7 Run `/sdd-docs-sync modules/aperv-tool`
