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
- [ ] 1.2 Confirm zero production readers of the drop list, **by grep against `src/main`, not by reading this design**: `WtgTransition.widgetClass`, `ProviderInfo.readPermission`/`writePermission`, `Widget.id`/`text`/`type`, raw `listeners`, `targetMethods` beyond `isEmpty()`, `getReachability`/`getWindows`/`getWindow`/`getTransitions`/`isWidgetlessSubstrate`. `IntentFilter.data` was on this list and does **not** belong: `SataAgent.buildDeepLinkUri` (`:869`) reads it and `:543` calls it — it is projected to `deepLinkUri` instead (INV-DRV-07). Treat that as the standing warning about this list's provenance and re-derive every remaining member first-hand
- [ ] 1.3 Confirm the analysis/metrics side reads only the full JSON: grep rv-android (`rv-coverage`, `rv-platform`, consolidation scripts) for static-analysis JSON readers; assert none will resolve `*.mop.json` (R9 provenance argument, design D9)
- [ ] 1.4 **Corpus pinned** (design "Corpus provenance"): `<workspace>/rvsec-dataset/static_analysis/`, 345 `*.apk.json`, 766 MB, verified 2026-08-03; `data/instrumented_apks/` never existed in this repo. What remains for this task is confirming the corpus is reachable from wherever the gate runs (it is a sibling repo, not vendored here — the gate must fail with a clear message when `-Dmop.corpusDir` is unset or empty, never silently pass on zero apps)

## 2. Host-side generator (rv-android: `modules/aperv-tool`)

- [ ] 2.1 Create `aperv_tool/tools/aperv/derive_mop_artifact.py`: `derive(document) -> dict` skeleton with `DerivationError`, preconditions (`complete == true`, non-null `package`), and the `formatVersion: 1` envelope (`package`, `mainActivity`, `source.{digest,file,generator}`)
- [ ] 2.2 Implement flag derivation: `bySignature` index from `reachability[]`, producer-precedence, D8 synthetic-lambda recovery, per-normalized-eventType `none|direct|transitive|both` encoding (INV-DRV-01)
- [ ] 2.3 Implement the widget map: base-activity keying, `mopRank` collision policy, empty-id drop with `stats.droppedFlaggedNoId`, metadata projection, emit-only-if-flagged-or-has-metadata rule (INV-DRV-02). **Order matters**: a flagged widget marks its base activity in the widget-derived MOP-activity set *before* the empty-id drop removes it, mirroring `MopData.java:428-444` — deriving that set from the emitted map instead is the silent-shrink bug this rule exists to prevent
- [ ] 2.4 Implement transitions processing: WTG click view keyed by base source activity, exact-duplicate removal, DIALOG re-keying (first incoming edge, move-not-copy, host promotion, orphan count) (INV-DRV-03)
- [ ] 2.5 Implement activity sets (`mopActivities`, fed by flagged widgets per 2.3 and by the dialog-host promotion of 2.4, + A′ `mopActivitiesAugmented`), `optionsMenus` records, component projection (`reachesMop` rename, `hasTargetMethods`, trigger-surface fields only, and the per-activity `deepLinkUri` derivation — first `ACTION_VIEW` filter with a non-empty scheme list ⇒ `scheme://host + path`, absent otherwise, INV-DRV-07), and the `stats` block (INV-DRV-04, INV-DRV-06)
- [ ] 2.6 Implement `serialize_canonical(artifact) -> bytes` (sorted keys, fixed separators, UTF-8, deterministic array orders) (INV-DRV-05)
- [ ] 2.7 pytest suite — this is the **permanent** protection once the one-shot gate is deleted, so it must not be cryptoapp-shaped (cryptoapp has no synthetic lambdas, no orphan dialogs, no empty-id widgets and a trivial A′). Beyond the cryptoapp ground-truth scenario, determinism (twice-derived byte identity) and the no-`*Target*`-key / no-call-graph assertion, it SHALL carry a named test per relocated semantics, on synthetic fragments where the corpus is thin:
  - listener × reachability cross-reference with producer precedence (INV-DRV-01)
  - D8 synthetic-lambda recovery, including the negative case (`X` with no reaching `lambda$…` ⇒ not flagged)
  - DIALOG re-keying, all five coupled sub-rules incl. host promotion and the retained dialog-class entry (INV-DRV-03)
  - the A′ union with all three sources contributing distinctly (INV-DRV-06 selection surface)
  - **the flagged-empty-id activity-marking rule of INV-DRV-02** (widget dropped, activity present, `droppedFlaggedNoId` incremented)
  - `deepLinkUri`: derivation, the three null cases (no `ACTION_VIEW`, empty scheme list, no filters), and host/path defaulting (INV-DRV-07)
- [ ] 2.8 Run `/rv-doc-code modules/aperv-tool/src/aperv_tool/tools/aperv/derive_mop_artifact.py` (rv-android module ⇒ `rv-*` skill)

## 3. Jar-side rewrite, with the old parser retained as the equivalence oracle (ape, working tree only)

- [ ] 3.1 Generate the fixtures with the Group-2 generator: `src/test/resources/cryptoapp.apk.mop.json` (from `cryptoapp.apk.gh60-fresh.json`) and `test-apks/cryptoapp.apk.mop.json` (from `test-apks/cryptoapp.apk.json`)
- [ ] 3.2 Implement the compact-format parser as new code paths in `MopData`. The full-JSON parser stays in the working tree only because it is the **oracle** of the group-4 equivalence gate (tasks 4.2/4.3 — never adjust the oracle); group 5 deletes it, and groups 3+5 land in one commit (task 7.1), so no shipped state ever contains both. This is oracle scaffolding, not a fallback window (design D8): version gate (`reason=version-mismatch`, INV-MOP-34), widget/flag decoding with explicit-`none` entries, wire sets, WTG view, components, stats echo
- [ ] 3.2a `ComponentInfo` gains `deepLinkUri`, decoded from the wire for activities; the launcher call site (`SataAgent:543`) passes `candidate.deepLinkUri` and `MonkeySourceApe`'s dispatch is untouched. Verify against the `component-triggering` delta that the restored dispatch paragraph matches what the code does — that paragraph also carries the explicit-intent rule and the pool exclusion, which the `rearch-03` rewrite of the same requirement had dropped
- [ ] 3.3 Implement the on-device OPTIONSMENU-gateway recompute from `optionsMenus` + WTG + the flag-selected activity set (INV-MOP-13) and the `mopActivitySourceComponents` set selection (INV-MOP-27)
- [ ] 3.4 Update the load status record: new success fields (`formatVersion`, `sourceDigest`, echoed stats), reject reasons reduced to `file-missing|parse-error|version-mismatch|package-mismatch` (INV-MOP-21 unchanged; INV-MOP-22 abort unchanged). **Keep `windows`** — it is on the stage-4 record and survives as `stats.windows`, so dropping it here would make the field appear at stage 4 and vanish at stage 7. Verify field-by-field against the stage-4 census that this record is a superset of it, minus `transitions` only (superseded by `wtgEdges` and deliberately never reinstated)
- [ ] 3.4a Surface the launch result on the dispatch path: `AndroidDevice.startActivity` returns the `START_*` code it currently discards from `IActivityManager.startActivity`, and the launcher hands it to the sink as `dec.comp` (INV-CT-14). Recording only — no retry, no re-dispatch, no cursor or budget effect (INV-CT-12's "returned actions" accounting is unchanged). Add a test asserting the launcher's behaviour is identical with the recording present and absent
- [ ] 3.5 JVM unit tests on the compact fixture: every scenario of the mop-guidance delta (fixture load, legacy-JSON rejection, per-event fallback decoding, flag-selected sets, absent metadata, unknown-key tolerance, strict-match reasons)
- [ ] 3.6 Run `/sdd-test-run MopDataTest`

## 4. Corpus equivalence gate (cutover condition)

- [ ] 4.1 Batch-derive artifacts for the pinned corpus: run the generator over `<workspace>/rvsec-dataset/static_analysis/*.apk.json` (345 apps); record per-app derivation time and artifact size (confirm the ≤ 1–5 MB ceiling); re-measure the `reachability`/`windows`/`transitions` byte split over these 345 and amend `design.md` with the command and result (the 57.7 %/5.0 %/10.1 % figures came from a different, unreproducible 134-file working set); and count how many apps genuinely exercise each of the four relocated rules — the coarse presence counts are 229 with empty `idName`, 321 with `ExternalSyntheticLambda`, 165 with DIALOG windows
- [ ] 4.2 Write `MopArtifactEquivalenceTest` (gated by `-Dmop.corpusDir`; hard-fail when unset or empty rather than passing on zero apps): old parser on full JSON vs new parser on derived artifact — assert identical widget flag maps (per-event + aggregate), metadata, both activity sets (flag off/on), gateway sets (flag off/on), WTG views, trigger/provider tuples, **per-activity `deepLinkUri` including null cases** (activities are excluded from the trigger-tuple pool, so nothing else compares them), `package`/`mainActivity`
- [ ] 4.3 Run the gate over the corpus; investigate and fix every divergence in the generator (the old parser is the oracle — never adjust the oracle); re-run until 345/345 green. A rule exercised by zero apps fails the gate: cover it with a synthetic fixture in the 2.7 suite and record the substitution
- [ ] 4.4 Record the gate result (corpus digest list + pass summary) in the change directory for the archive trail

## 5. Jar cutover (BREAKING — lands only with Group 6)

- [ ] 5.1 Delete the full-JSON parse machinery from `MopData.java`: `parseReachability`/`parseWindows`/`parseWidget`/`parseListener`/`deriveWidgetMopFlags`/`parseTransitions`/`parseComponents`/`parseIntentFilters`/`parseDataSpec`/`rekeyDialogsToHost`/`augmentActivitiesFromSources`/`precomputeMopOptionsMenus`/`computeHandlerJoinDiagnostics`, the `Window`/`Listener`/`Transition`/`TransitionEvent`/`ReachabilityClass`/`ReachabilityMethod` POJOs, and the test-only getters (`getReachability`, `getWindows`, `getWindow`, `getTransitions`, `isWidgetlessSubstrate`)
- [ ] 5.2 Delete the memory-safety machinery (design D5, V19): `PARSE_FOOTPRINT_FACTOR`, `PARSE_BUDGET_BYTES`, the budget-parameter test seam, `reason=too-large`, and the outer `catch (OutOfMemoryError)`; grep-verify zero `OutOfMemoryError` catches remain in the repo
- [ ] 5.3 Delete `WtgTransition.widgetClass`, the `DataSpec`/`readPermission`/`writePermission` surfaces from `ComponentInfo`, and `Widget.id`/`text`/`type`/`listeners`. `ComponentInfo` gains `deepLinkUri` (read from the wire) and `SataAgent.buildDeepLinkUri` is deleted with the structure it walked — its call site at `:543` passes `candidate.deepLinkUri` instead. This is the one place outside `MopData`/`ComponentInfo` that changes; everything else compiles clean because the query API is unchanged by construction
- [ ] 5.3a Migrate the deep-link assertions of `ActivityFrontierTest` ("Lever B", 6 assertions over `buildDeepLinkUri`) to the Python generator suite (task 2.7) — they pin INV-DRV-07 now, on the side that computes it. Assert what remains on the jar side instead: a `ComponentInfo` carrying `deepLinkUri` dispatches `ACTION_VIEW`, one carrying null dispatches the explicit component. **Deleting these assertions instead of migrating them is the wrong fix** — they are the only thing standing between a schema omission and a silently degraded activity frontier
- [ ] 5.4 Delete `MopArtifactEquivalenceTest` and the old-format test resources (`cryptoapp.apk.gh60*.json` fixtures move to test-only history; the compact fixture is now the only loader fixture); update the D7 vocabulary-boundary javadoc on `MopData` (boundary now: generator host-side)
- [ ] 5.5 Update `CLAUDE.md` (MopData naming note, `mopDataPath` artifact description) and run `/sdd-doc-code src/main/java/com/android/commands/monkey/ape/utils/MopData.java`
- [ ] 5.6 Run `/sdd-test-run ape` (full `mvn test` — 145-test suite adjusted for the removed seams)

## 6. Python push switch (BREAKING — lands only with Group 5)

- [ ] 6.1 `tool.py`: add `_derive_mop_artifact(task)` (digest-checked cache at `<apk_name>.mop.json`, atomic write, `DerivationError` → `RVToolExecutionError`); delete `_compact_static_analysis_json` and its enrichment helper and their fallback-to-source push
- [ ] 6.2 `tool.py` step 1c: replace the warn-and-continue branch with the raise (INV-APERV-05); push to `/data/local/tmp/mop-artifact.json`; update the `ape.mopDataPath` value in `_push_properties`
- [ ] 6.3 Update `test_aperv_tool.py`: absent-JSON raises, derivation-failure raises, cache hit/stale behavior, device path and properties line, non-MOP arms untouched, no-full-JSON-push assertion (INV-APERV-06)
- [ ] 6.4 Run `/rv-test-run aperv-tool` (pytest for the module — rv-android's skills are `rv-*`, not `sdd-*`)
- [ ] 6.5 **Cross-repo OpenSpec instrument**: rv-android's `openspec/specs/aperv/spec.md` MUST NOT be edited by hand (that repo's CLAUDE.md forbids it). Counterpart opened 2026-08-03: **`rvsec#96`**, change `gh96-mop-artifact-derivation`, carrying this stage's delta — the push path switches from the full JSON to the derived artifact, `_compact_static_analysis_json` and its INV-APV-20..25/31/32 requirements retire, and absent-input becomes a raised error — and let that repo's archive/sync apply it

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
