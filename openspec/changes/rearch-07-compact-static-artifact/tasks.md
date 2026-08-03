# Tasks: rearch-07-compact-static-artifact

**Worktree** (decided 2026-08-03): all 7 stages are implemented in a single git worktree on branch `rearch` (`git worktree add ../ape-rearch -b rearch`), merged into `master` only after stage 7 — this stage is the last, so its merge is the one that closes the branch. Setup, teardown and the `mvn install` caveat: `docs/20260803_procedimento_worktree_rearch.md`. Group 2's host-side generator lands in rv-android, which the ape worktree does NOT cover — it needs a matching branch there (procedure doc §4).

<!-- Subagent dispatch hints:
     - Group 1 (inventory ratification) must complete first — the schema is derived from it.
     - Group 2 (generator, rv-android) and Group 3 (jar fixture + new parser) can proceed in
       parallel after Group 1; both are gated by Group 4 (corpus equivalence) before any deletion.
     - Group 5 (jar cutover: delete old parser) and Group 6 (tool.py push switch) are the
       coordinated BREAKING pair — they land together (design D8), after Group 4 is green.
     - Critical path: 1 → 2 → 4 → 5/6 → 7 → 8.
     - Cross-repo: Groups 2, 6 edit rv-android (aperv-tool module only); Groups 3, 5 edit ape. -->

## 1. Consumption inventory ratification (read-only, gates the schema)

- [ ] 1.1 Re-run the caller audit of every `MopData` accessor in `src/main` and diff it against the inventory table in `design.md`; any new consumer added since this design was written amends the schema before anything else proceeds
- [ ] 1.2 Confirm zero production readers of the drop list: `WtgTransition.widgetClass`, `IntentFilter.data` (`DataSpec`), `ProviderInfo.readPermission`/`writePermission`, `Widget.id`/`text`/`type`, raw `listeners`, `targetMethods` beyond `isEmpty()`, `getReachability`/`getWindows`/`getWindow`/`getTransitions`/`isWidgetlessSubstrate`
- [ ] 1.3 Confirm the analysis/metrics side reads only the full JSON: grep rv-android (`rv-coverage`, `rv-platform`, consolidation scripts) for static-analysis JSON readers; assert none will resolve `*.mop.json` (R9 provenance argument, design D9)
- [ ] 1.4 **Pin the corpus** (design "Corpus provenance"): record in `design.md` the exact directory holding the full static-analysis JSONs, its file count, and the command that produced the 57.7 %/5.0 %/10.1 % byte split. `data/instrumented_apks/` does not exist in this repo (`data/` holds only `system-broadcast.json`); the JSONs are held by the sibling dataset repo (`rvsec-dataset/static_analysis/`). Without this the equivalence gate has no corpus and the T7 claim is unreproducible

## 2. Host-side generator (rv-android: `modules/aperv-tool`)

- [ ] 2.1 Create `aperv_tool/tools/aperv/derive_mop_artifact.py`: `derive(document) -> dict` skeleton with `DerivationError`, preconditions (`complete == true`, non-null `package`), and the `formatVersion: 1` envelope (`package`, `mainActivity`, `source.{digest,file,generator}`)
- [ ] 2.2 Implement flag derivation: `bySignature` index from `reachability[]`, producer-precedence, D8 synthetic-lambda recovery, per-normalized-eventType `none|direct|transitive|both` encoding (INV-DRV-01)
- [ ] 2.3 Implement the widget map: base-activity keying, `mopRank` collision policy, empty-id drop with `stats.droppedFlaggedNoId`, metadata projection, emit-only-if-flagged-or-has-metadata rule (INV-DRV-02)
- [ ] 2.4 Implement transitions processing: WTG click view keyed by base source activity, exact-duplicate removal, DIALOG re-keying (first incoming edge, move-not-copy, host promotion, orphan count) (INV-DRV-03)
- [ ] 2.5 Implement activity sets (`mopActivities` + A′ `mopActivitiesAugmented`), `optionsMenus` records, component projection (`reachesMop` rename, `hasTargetMethods`, trigger-surface fields only), and the `stats` block (INV-DRV-04, INV-DRV-06)
- [ ] 2.6 Implement `serialize_canonical(artifact) -> bytes` (sorted keys, fixed separators, UTF-8, deterministic array orders) (INV-DRV-05)
- [ ] 2.7 pytest suite: per-rule unit tests on synthetic fragments + cryptoapp ground-truth scenario (flags, sets, gateway inputs, components, stats) + determinism test (twice-derived byte identity) + no-`*Target*`-key / no-call-graph assertion
- [ ] 2.8 Run `/rv-doc-code modules/aperv-tool/src/aperv_tool/tools/aperv/derive_mop_artifact.py` (rv-android module ⇒ `rv-*` skill)

## 3. Jar-side rewrite, with the old parser retained as the equivalence oracle (ape, working tree only)

- [ ] 3.1 Generate the fixtures with the Group-2 generator: `src/test/resources/cryptoapp.apk.mop.json` (from `cryptoapp.apk.gh60-fresh.json`) and `test-apks/cryptoapp.apk.mop.json` (from `test-apks/cryptoapp.apk.json`)
- [ ] 3.2 Implement the compact-format parser as new code paths in `MopData`. The full-JSON parser stays in the working tree only because it is the **oracle** of the group-4 equivalence gate (tasks 4.2/4.3 — never adjust the oracle); group 5 deletes it, and groups 3+5 land in one commit (task 7.1), so no shipped state ever contains both. This is oracle scaffolding, not a fallback window (design D8): version gate (`reason=version-mismatch`, INV-MOP-34), widget/flag decoding with explicit-`none` entries, wire sets, WTG view, components, stats echo
- [ ] 3.3 Implement the on-device OPTIONSMENU-gateway recompute from `optionsMenus` + WTG + the flag-selected activity set (INV-MOP-13) and the `mopActivitySourceComponents` set selection (INV-MOP-27)
- [ ] 3.4 Update the load status record: new success fields (`formatVersion`, `sourceDigest`, echoed stats), reject reasons reduced to `file-missing|parse-error|version-mismatch|package-mismatch` (INV-MOP-21 unchanged; INV-MOP-22 abort unchanged)
- [ ] 3.5 JVM unit tests on the compact fixture: every scenario of the mop-guidance delta (fixture load, legacy-JSON rejection, per-event fallback decoding, flag-selected sets, absent metadata, unknown-key tolerance, strict-match reasons)
- [ ] 3.6 Run `/sdd-test-run MopDataTest`

## 4. Corpus equivalence gate (cutover condition)

- [ ] 4.1 Batch-derive artifacts for the corpus: run the generator over `data/instrumented_apks/*.apk.json` (134 apps); record per-app derivation time and artifact size (confirm the ≤ 1–5 MB ceiling)
- [ ] 4.2 Write `MopArtifactEquivalenceTest` (gated by `-Dmop.corpusDir`): old parser on full JSON vs new parser on derived artifact — assert identical widget flag maps (per-event + aggregate), metadata, both activity sets (flag off/on), gateway sets (flag off/on), WTG views, trigger/provider tuples, `package`/`mainActivity`
- [ ] 4.3 Run the gate over the corpus; investigate and fix every divergence in the generator (the old parser is the oracle — never adjust the oracle); re-run until 134/134 green
- [ ] 4.4 Record the gate result (corpus digest list + pass summary) in the change directory for the archive trail

## 5. Jar cutover (BREAKING — lands only with Group 6)

- [ ] 5.1 Delete the full-JSON parse machinery from `MopData.java`: `parseReachability`/`parseWindows`/`parseWidget`/`parseListener`/`deriveWidgetMopFlags`/`parseTransitions`/`parseComponents`/`parseIntentFilters`/`parseDataSpec`/`rekeyDialogsToHost`/`augmentActivitiesFromSources`/`precomputeMopOptionsMenus`/`computeHandlerJoinDiagnostics`, the `Window`/`Listener`/`Transition`/`TransitionEvent`/`ReachabilityClass`/`ReachabilityMethod` POJOs, and the test-only getters (`getReachability`, `getWindows`, `getWindow`, `getTransitions`, `isWidgetlessSubstrate`)
- [ ] 5.2 Delete the memory-safety machinery (design D5, V19): `PARSE_FOOTPRINT_FACTOR`, `PARSE_BUDGET_BYTES`, the budget-parameter test seam, `reason=too-large`, and the outer `catch (OutOfMemoryError)`; grep-verify zero `OutOfMemoryError` catches remain in the repo
- [ ] 5.3 Delete `WtgTransition.widgetClass`, the `DataSpec`/`readPermission`/`writePermission` surfaces from `ComponentInfo`, and `Widget.id`/`text`/`type`/`listeners`; compile-clean with zero consumer edits outside `MopData`/`ComponentInfo` (the query API is unchanged by construction)
- [ ] 5.4 Delete `MopArtifactEquivalenceTest` and the old-format test resources (`cryptoapp.apk.gh60*.json` fixtures move to test-only history; the compact fixture is now the only loader fixture); update the D7 vocabulary-boundary javadoc on `MopData` (boundary now: generator host-side)
- [ ] 5.5 Update `CLAUDE.md` (MopData naming note, `mopDataPath` artifact description) and run `/sdd-doc-code src/main/java/com/android/commands/monkey/ape/utils/MopData.java`
- [ ] 5.6 Run `/sdd-test-run ape` (full `mvn test` — 145-test suite adjusted for the removed seams)

## 6. Python push switch (BREAKING — lands only with Group 5)

- [ ] 6.1 `tool.py`: add `_derive_mop_artifact(task)` (digest-checked cache at `<apk_name>.mop.json`, atomic write, `DerivationError` → `RVToolExecutionError`); delete `_compact_static_analysis_json` and its enrichment helper and their fallback-to-source push
- [ ] 6.2 `tool.py` step 1c: replace the warn-and-continue branch with the raise (INV-APERV-05); push to `/data/local/tmp/mop-artifact.json`; update the `ape.mopDataPath` value in `_push_properties`
- [ ] 6.3 Update `test_aperv_tool.py`: absent-JSON raises, derivation-failure raises, cache hit/stale behavior, device path and properties line, non-MOP arms untouched, no-full-JSON-push assertion (INV-APERV-06)
- [ ] 6.4 Run `/rv-test-run aperv-tool` (pytest for the module — rv-android's skills are `rv-*`, not `sdd-*`)
- [ ] 6.5 **Cross-repo OpenSpec instrument**: rv-android's `openspec/specs/aperv/spec.md` MUST NOT be edited by hand (that repo's CLAUDE.md forbids it). Open a change there via `openspec-new-change` carrying this stage's counterpart delta — the push path switches from the full JSON to the derived artifact, `_compact_static_analysis_json` and its INV-APV-20..25/31/32 requirements retire, and absent-input becomes a raised error — and let that repo's archive/sync apply it

## 7. Coordinated deploy (design D8)

- [ ] 7.1 Land the ape commit (Groups 3+5) and the rv-android commit (Groups 2+6) together; `mvn install -Drvsec_home=…` refreshes the module-local `ape-rv.jar`
- [ ] 7.2 Rebuild the Docker image (hard constraint 2b: jar rebuilt from source) and verify the image contains the new jar and the new aperv-tool module in the same build
- [ ] 7.3 Skew drill on the bench: (a) old full JSON pushed to the new jar path → `status=rejected reason=version-mismatch` + `StopTestingException`; (b) MOP arm with the full JSON removed from `results_dir` → `RVToolExecutionError` before launch; both loud, no silent SATA run

## 8. Verification

- [ ] 8.1 End-to-end device smoke: cryptoapp `sata_mop` on the RVSec AVD — assert `status=loaded formatVersion=1 sourceDigest=…` in the trace, MOP boost fires on `btn_cipher_encrypt`/`buttonGenerateHash`, menu-gateway boost fires on MainActivity, run completes to timeout
- [ ] 8.2 Confirm artifact-size and load-time deltas on device (trace timestamps around agent construction) against a pre-change trace for one call-graph-heavy app (e.g. `com.blogspot.e_kanivets.moneytracker`, 12.45 MB full → compact artifact)
- [ ] 8.3 Run `/rv-qa-lint-fix aperv-tool` (rv-android) and `/sdd-qa-lint-fix ape` (this repo)
- [ ] 8.4 Run `/sdd-verify ape`
- [ ] 8.5 Invoke `/sdd-code-reviewer` via Skill tool
- [ ] 8.6 Run `/sdd-docs-sync ape` (CLAUDE.md + spec cross-references current)
