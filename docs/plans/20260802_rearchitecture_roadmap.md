# APE-RV Re-architecture Roadmap — "Disposable Run Kernel"

**Created:** 2026-08-02 · **Source of truth:** `docs/analise_fable-selecao.md` (rev. 3)
**Baseline commit:** `5dcf225` · **Owner decisions:** D1–D6 (report Sec. 12) — all closed, do not reopen.

This roadmap is the tracking instrument for the execution of the re-architecture. There is
**no umbrella OpenSpec change**: the work is 7 real changes (`openspec/changes/rearch-0*`),
one per adoption stage (report Sec. 10), executed in order. Each change goes through the
full OpenSpec cycle (proposal → design + specs → tasks → apply → verify → archive) and
keeps the system runnable and results comparable on its own.

## Execution checklist

Per change: `[artifacts]` design/specs/tasks drafted and approved by the owner →
`[apply]` implemented → `[verify]` `/opsx:verify` clean + gates below → `[archive]`.

- [x] **1. `rearch-01-parity-oracle`** — golden capture of current per-preset decision
      sequences + preemption golden (incl. finding 3.3-1). Pure test infra, no production change.
      *Gate for stages 2–3.*
  - [x] artifacts approved (2026-08-03) · [x] apply · [x] verify · [x] archive (2026-08-03)
- [x] **2. `rearch-02-runspec`** — `RunSpec` + presets in jar + `Feature` enum + total
      fail-fast + level-0 `RUN_START` echo (D1) + removal of `/sdcard` readers (D6) and of
      `saveGraph`/`readGraph`/`--ape-model`. **One ordered Python edit** (AC1): the counterpart change
      in rv-android removes `ape_pure_mode` from `tool.py` and lands *before* this jar — stage 2, not
      stage 5, is where the cross-repo coupling starts.
      *Gate: parity oracle green per preset; the counterpart line merged into `modules` before the
      first `mvn install` of a post-stage-2 jar (D-4, restated 2026-08-04).*
  - [x] artifacts approved (2026-08-03) · [x] apply · [x] verify · [x] archive (2026-08-04)
- [ ] **3. `rearch-03-decision-pipeline`** — `DecisionPipeline` stages + `StageResult` sum
      type + episode state relocated + `LlmRouter` sliced + `ScoringPipeline` real injection.
      Hard preemption preserved exactly (Q1).
      *Gate: parity oracle green per preset; permanent preemption golden.*
  - [x] artifacts approved (2026-08-03) · [ ] apply · [ ] verify · [ ] archive
- [ ] **4. `rearch-04-step-ndjson-telemetry`** — `EventSink` step-grouped NDJSON (D2) +
      escaping serializer + heartbeat (D4) + `RUN_END` write-only (D5) + legacy outputs
      deleted + native NDJSON reader on the analysis side + gzip at collection. Dissolves
      INV-ARCH-01. **No NDJSON→legacy converter** (P3 sweep, 2026-08-03): the `.trace` is the
      NDJSON and is never rewritten.
      *Gates: neutrality test (sink on/off, same seed ⇒ same decisions); calibration report
      2026-07-24 regenerable (Sec. 9.11); round-trip/one-line tests (Sec. 9.12).*
  - [x] artifacts approved (2026-08-03) · [ ] apply · [ ] verify · [ ] archive
- [ ] **5. `rearch-05-thin-python-arms`** — arms = preset + overrides; dead keys and Python
      kill-switch duplication deleted; INV-APV-14 retired. Largest cross-repo stage (~95% rv-android;
      stage 2 is now the first to cross, per AC1).
      *Gate: regeneration diff of the 27 surviving arms' effective configs, identical before/after;
      `ape_pure`/`bfs` recorded as documented retirements, not diffs.*
  - [x] artifacts approved (2026-08-03) · [ ] apply · [ ] verify · [ ] archive
- [ ] **6. `rearch-06-memory-surgical`** — V12 cache release; V11/V24 diagnostic retention
      to IDs/minimal snapshots, conditional on caller audit. No speculative bounds.
      *Gate: action-sequence parity after each retention change.*
  - [x] artifacts approved (2026-08-03) · [ ] apply · [ ] verify · [ ] archive
- [ ] **7. `rearch-07-compact-static-artifact`** — host-side derived artifact (~1–5 MB);
      `MopData` consumes it; MOP-feature-without-artifact aborts fail-fast.
      *Gates: frozen metric sets preserved by derivation (R9); cross-repo push path updated.*
  - [x] artifacts approved (2026-08-03) · [ ] apply · [ ] verify · [ ] archive
- [ ] **Merge condition — empirical A/B against the E3 baseline** (owner decision 2026-08-03, item 8).
      Not a stage: the gate on merging the `rearch` line into `master`. 40 APKs × the E3 arms × 3 reps
      × 1800 s, same seeds, jar pre vs post, consolidated by `consolidate_cal.py` and compared with
      `stats_utils.paired_bootstrap_ci` (B=10,000, seed 42). The "before" side already exists — the E3
      decisive run was measured on this roadmap's own baseline commit (`5dcf2259…`, jar `386ce08d…`),
      `experimento-e3-decisiva/per_apk_paired.csv` versioned — so the marginal cost is one ~24 h
      campaign on 8 containers. Two constraints, both learned the hard way: **paired, n≈40 minimum**
      (an unpaired 16-APK smoke manufactured a −4.7 pp false positive on 2026-06-19 that was an exact
      tie at n≈70), and **capture before stage 4 or after `trace_ndjson.py` exists** — stage 4 freezes
      the comparison scaffold's trace readers on the legacy format, so the step-level panel (steps,
      `src_*`, actions by type) goes dark in between; outcomes (`cov_*`, `mop_unique`, `mop_total`)
      survive either way, coming from `tasks.json` and logcat. Hosted as a change in **rv-android**.
      *Rationale: all seven stage gates are host/JVM-level, so nothing else in the line would notice a
      jar that is not wrong, only worse — the gh71 failure mode (MOP boost fired 0× in 147,153
      evaluations, found only in post-hoc analysis of a 2,028-task campaign).*
  - [ ] change opened · [ ] baseline pinned · [ ] post-jar campaign run · [ ] compared · [ ] merge

## Standing constraints (apply to every change)

- Inviolable rules R1–R9 (report Sec. 2); R4 is the review gate against scope growth:
  any PR with IPC/persistence/async/generic registry is cut.
- Architectural tests Sec. 9 (11 tests + escaping) are the acceptance family; each change's
  specs must carry its applicable subset as requirements.
- Invariants table Sec. 8: preserved invariants stay in the specs; deliberately dissolved
  ones (INV-ARCH-01, INV-EXPL-03, INV-APV-14, INV-ARCH-06) are removed *by the change that
  dissolves them*, with the substitute recorded.
- Portuguese in conversation, English in artifacts. No implementation before the owner
  approves the change's artifacts.
- **P3 (rv-android CLAUDE.md): no strategy that keeps legacy code alive** — no adapter, shim,
  alias, converter, fallback window or speculative tolerance. Every change overwrites what it
  replaces, in the same landing. Swept 2026-08-03
  (`docs/20260802_verificacao_p3_rearch.md`); the surviving legacy-shaped constructs are
  legitimate and each says in the artifact why (07's old parser is the equivalence *oracle*,
  deleted in the same commit as its replacement; the frozen-corpus readers parse an archived
  dataset that will not change).
- **Execution happens in one git worktree**, branch `rearch`, for all 7 stages; merged into
  `master` once, after stage 7 (decided 2026-08-03). Procedure, inheritance, the
  `mvn install -Drvsec_home` caveat and the cross-repo carve-out:
  `docs/20260803_procedimento_worktree_rearch.md`. The constraint the shared branch makes easy
  to violate: stage 1's goldens are captured from pre-change code and MUST be committed before
  stage 2's first production edit.
- **The rv-android side runs on a dedicated branch `rearch-counterparts`**, cut from `modules`
  (owner decision 2026-08-03). It mirrors the ape worktree's shape — one isolated line, one merge —
  and keeps `modules` free of intermediate re-architecture state while the E3 analysis and the
  `master` jar deploy continue to run from it. The five counterparts `gh93`…`gh97` land there.
  The cost this choice accepts is explicit: **the line must be merged into `modules` before the
  first `mvn install` of a post-stage-2 jar** (23 of 29 arms push `ape.apePureMode`, which that jar
  aborts on).

  **Amended 2026-08-04.** This entry originally carved `gh93` out for an early, separate merge ahead
  of the rest of the line. The owner withdrew that carve-out: `rearch-counterparts` merges into
  `modules` only after all five counterparts (`gh93`…`gh97`) are finished, so `gh93` lands with the
  line. The safety argument is unchanged, because the hazard was never the merge itself — it is a
  post-stage-2 jar meeting a `tool.py` that still pushes the key, and that meeting happens at
  `mvn install` (`copy-jar-to-aperv-tool`). Both the counterpart merge and the `rearch` → `master`
  merge are end-of-line acts, so the constraint orders two deliberate decisions and gates no
  stage-2 group: group 6 deploys nothing, and — since the device smoke was descoped on 2026-08-04
  (design D-13) — **no stage-2 task installs the jar at all**, so the condition holds vacuously
  across the whole stage and the binding event sits outside it, at the coordinated end-to-end run.
  See `rearch-02-runspec` design D-4 and D-13.

## Related state

- `gh10-normalize-boosts` closed as **superseded** 2026-08-02 (never implemented; archived
  with `--skip-specs`). Boost-magnitude question re-expressible later as a preset override
  informed by #16 data.
- `telemetry-proof-llm-efficacy` is **archived** (2026-08-02, 51/51, verified and synced —
  `openspec/changes/archive/2026-08-02-telemetry-proof-llm-efficacy/`; task 17.4 was closed by the
  decisive-run evidence, commit `99dded5`). Its telemetry format is superseded by stage 4
  (acceptance Sec. 9.11 protects the transition).
- `gh88-cal-llm-control` (rvsec) is **archived** 2026-08-03 at **47/58**, with specs synced —
  `rv-android/openspec/changes/archive/2026-08-03-gh88-cal-llm-control/`, commit `c9dfb704`. Not a
  `--skip-specs` closure like gh10: the nine `cal_a*` arms, the `LLM_ARM_KEYS` guard, the two
  `APERV_PROPERTY_MAPPING` entries and the eight-state `experimento-cal/` scaffold are all built and
  in use, so the deltas describe real code (sync created the `calibration-control` capability, 9
  requirements, and extended `aperv`'s variant tiers). **Phase A ran** — `experimento-cal/iter0`,
  11 arms × 40 APKs × 2 reps × 300 s = 880 tasks, VERIFY `ADMISSIBLE`, `analysis.md` complete across
  four gates; only its DECIDE record was never written, and it would have promoted nothing (no LLM
  arm separated from either anchor; best arm `cal_a3`, Δ`cov_mop` vs ANC2 +0.85 pp, CI95
  [-2.52, 4.35]). **Phases B and C are superseded by the E3 decisive run**, which answers the
  keep-or-drop question at phase-C scale. The 11 open tasks are left unchecked, with the disposition
  recorded in the archived proposal and tasks.

## Cross-change decisions recorded during artifact drafting (owner ratifies at approval)

> **Owner decisions of 2026-08-03** (from `rv-android/docs/20260803_rearch_artifact_vs_code_verification.md` §9; the numbered items below are amended to match):
>
> - **AC1** — the `tool.py` edit removing `ape_pure_mode` is **pulled into stage 2** and lands **before** the jar. Not "accept `false` as inert": the key is retired, so it leaves the Python side.
> - **AC2** — the deep link travels as a **precomputed `deepLinkUri` string** per activity, derived host-side. Not the reduced filter structure.
> - **AC6** — the targetless-recovery-point remap **preserves HEAD parity** (`requireTarget()` guard carried forward). The unconditional remap, plausible as an improvement, is left to a change that can measure it.
> - **Item 8** — an empirical A/B against the E3 baseline (jar `386ce08d…`, git `5dcf2259…`) is a **merge condition** for the `rearch` line, with the **full E3 arm set**.
> - **Cross-repo partition** — work that belongs to rv-android becomes a change **in rv-android**, following that repo's `docs/WORKFLOW.md`; this roadmap coordinates order and checklists for both sides. Specs stay on both sides as distinct viewpoints (jar-side consumption vs Python-side production); the boundary is the wire.

1. **`ape_pure` and `bfs` variants are retired** (rearch-02 design → rearch-05 D2): no
   structural-purity preset exists; `ape.apePureMode` is a retired key that aborts, and
   unknown `--ape` values abort. **Amended 2026-08-03 (AC1)**: `ape.apePureMode=false` is pushed by
   23 of the 29 arms via `_BASELINE_ARM_FLAGS`, so retiring the key jar-side alone aborts every
   campaign arm before step 1. Stage 2 therefore carries one ordered Python edit — the key leaves
   `APERV_PROPERTY_MAPPING`, `_BASELINE_ARM_FLAGS`, `_APE_PURE_ARM_FLAGS` and `ARM_DEFINING_KEYS`
   (18 → 17 arm-defining keys) — landing **before** the stage-2 jar; the reverse order is forbidden.
   `ape_pure` does **not** break at stage 2: it already sets all 17 remaining flags to their off
   values explicitly, so its purity was structural on the Python side before this work began. Consistent with D3 (control = minimal `aperv`). Verified
   2026-08-02: `bfs` was never an agent type — `ApeAgent.createAgent` accepts only
   `sata`/`random`/`replay` and falls through silently to `SataAgent` (V9), so the `bfs` arm
   always carried the same effective configuration as `sata`. Retiring it removes a duplicate,
   not an experimental arm. The Python strategy whitelist shrinks to `["sata", "random"]` in
   rearch-05 (the deletion rearch-02 delegates to stage 5).
2. **Build provenance stamp reintroduced** (rearch-02 D-8): the gh14 stamp was archived
   without implementation; `RUN_START.build` requires it, so rearch-02 reintroduces it
   reusing the archived gh14 wiring.
3. **Inert-key rule** (rearch-02, substitute for INV-ARCH-06): sub-params of inactive
   features are accepted only at their neutral value (echoed as `inert`); non-neutral
   aborts — forced by `ape.llmPercentageNoSubstrate=-1` pushed by every current arm.
4. **Oracle capture level** (rearch-01 D1): goldens are decision-level
   (`selectNewActionNonnull` on synthetic states), harness-relative — the full device
   loop is not JVM-drivable. The stage-2/3 parity gates mean decision-level parity.
   **Amended 2026-08-03 (AC5/AC7)**: the consequence must be read strictly. `adjustActionsByGUITree()`
   runs in `resolveNewAction()` **above** the oracle's entry point (`StatefulAgent.java:1475-1478`),
   so the goldens execute neither the scoring pipeline nor GUITree building, `Model.release`,
   `appendToActionHistory`, `recoverCurrentState` or `updateModel`. They are a **decision-ladder
   regression floor**, never evidence of scoring-weight fidelity (rearch-03 INV-ARCH-12 guards that)
   nor of retention neutrality (rearch-06 D4: audits + per-path unit tests carry it). A gate that
   does not execute the changed code is green by construction.
5. **INV-ARCH-01 disposition split**: the requirement (scoring-pipeline spec) is REMOVED
   by rearch-02 with its subject (apePureMode, per D3); the telemetry-half substitute
   (INV-SNK-07 neutrality + sink-on/off test, R7) is recorded by rearch-04.
6. **V11 ordering**: rearch-06's ActionRecord snapshot fix is hard-blocked on stages 2+4
   (teardown `saveActionHistory` dies at stage 4; `reducer/` is dead tooling outside the
   Maven build).
7. **rearch-07 schema**: derived artifact `formatVersion: 1`; coordinated jar+Python cut, no
   fallback window, gated by the full-vs-derived corpus equivalence test (R9).
   **Amended 2026-08-03**: (a) *AC2* — the schema carries `components.activities[].deepLinkUri`;
   `IntentFilter.data` was wrongly listed as production-unused, while `SataAgent.buildDeepLinkUri`
   (`:869`, called at `:543`) reads it and `MonkeySourceApe:993-1002` dispatches on the result.
   (b) *AC3* — INV-DRV-02 now restates that a flagged widget marks its activity **before** the
   empty-short-id drop, the rule `MopData.java:428-444` implements and the whole `mopActivities`
   chain rests on. (c) *AC4* — the corpus is pinned to `<workspace>/rvsec-dataset/static_analysis/`
   (**345** `.apk.json`, 766 MB, verified 2026-08-03); `data/instrumented_apks/` never existed here.
   The 57.7 %/5.0 %/10.1 % split came from a different, unreproducible 134-file working set and is
   re-measured by task 4.1. The gate must also report a non-zero exercise count per relocated rule
   (coarse presence in the pinned corpus: 229 apps with empty `idName`, 321 with
   `ExternalSyntheticLambda`, 165 with DIALOG windows).
8. **rearch-04 telemetry cost is measured, not argued** (2026-08-03, AC8): the `SHALL NOT exceed`
   per-step cost clause was normative but orphaned — no implementation, no test, none of the 52
   tasks. It becomes INV-SNK-13 with a steps-per-minute gate (pre-jar twice to establish noise,
   post-jar once). Separately, INV-SNK-14: rv-platform streams `adb logcat … -s RVSEC:V RVSEC-COV:V`,
   a strict allowlist, so the D4 heartbeat reaches the joined file only once its tag is added there —
   deleting `clock_logcat_join.py`'s offset reconstruction is blocked until a captured run shows the
   lines present.

Cross-repo instrument (owner decision 2026-08-03) — every item of work that belongs to rv-android is
a change **in rv-android**, opened with `openspec-new-change` per that repo's `docs/WORKFLOW.md`
(it forbids hand-writing OpenSpec artifacts), while this roadmap holds the order and the checklists
for both sides. Counterparts, all opened 2026-08-03:

| Stage | rvsec issue | Change | Scope |
|---|---|---|---|
| 2 | `rvsec#93` | `gh93-retire-ape-pure-mode` (quick-path) | remove `ape_pure_mode` from `tool.py` — **predecessor** of the stage-2 jar (AC1) |
| 4 | `rvsec#94` | `gh94-ndjson-trace-reader` | native NDJSON reader, gzip at collection, heartbeat tag in the capture allowlist (INV-SNK-14) |
| 5 | `rvsec#95` | `gh95-thin-python-arms` | arms as preset + overrides (~95% of the stage) |
| 7 | `rvsec#96` | `gh96-mop-artifact-derivation` | the derivation generator and the push switch (~45%) |
| merge | `rvsec#97` | `gh97-rearch-ab-gate` | the empirical A/B gate described above | The
two `gh<N>` namespaces are independent and already collide (ape at gh15, rvsec at gh92): always
qualify cross-references as `phtcosta/ape#N` vs `rvsec#N`.

Open coordination items — status 2026-08-03:
- `telemetry-proof-llm-efficacy` must archive before rearch-03/04 (their deltas are written
  against its post-sync text; rearch-03 task 8.6) — **satisfied**: archived 2026-08-02.
- rv-android changes touching the same arms must merge before rearch-05 (task 1.3) — **satisfied**:
  `gh90-e3-decisive-run-setup` and `gh88-cal-llm-control` are both **archived**. Stage 5 has no
  remaining arm-level blocker. gh88's block ran through its task 12.1, which would have added
  `cal_b*` arms to `get_variants()` in the pre-migration format; retiring phase B released it.
- **Reciprocal debt created by that archive** (2026-08-03): syncing gh88's deltas put the
  calibration arm tier and `LLM_ARM_KEYS` into rv-android's main `aperv` spec, so `gh95` must now
  REMOVE both — its group 8 already deletes `LLM_ARM_KEYS` from `tool.py`, but the delta spec does
  not exist yet (gh95 is still an empty directory). Whoever writes gh95's artifacts owns this.
- **The stage-1 goldens are frozen for stages 2 and 3** (INV-ORA-07, in the synced
  `openspec/specs/parity-oracle/spec.md`). The five golden files and the scenario scripts SHALL NOT
  change while those two stages are in flight; the only layer that may adapt is `OracleScaffold`'s
  injection profile, and only to a renamed or relocated field. From stage 2 on,
  `git diff <capturedAt> HEAD -- src/main/java` stops being empty — that is expected and is not a
  reason to recapture. A golden that moves with the code it measures measures nothing.
- `gh92-emulator-boot-gating` (rv-android) blocks no **gate**: all seven stage gates are
  host/JVM-level (the parity goldens are decision-level, cross-change decision 4). It blocks only
  the device *smokes* routed through rv-platform — rearch-03 t8.4, rearch-04 t9.1, rearch-05
  t1.1/9.2, rearch-07 t8.1/8.2. See `docs/20260802_verificacao_consistencia_rearch.md` §8.

## Status log

- 2026-08-02 — Roadmap created; 7 changes scaffolded with proposals (1/4 artifacts each);
  gh10 archived as superseded.
- 2026-08-02 — All 7 changes complete at 4/4 artifacts (design + delta specs + tasks),
  `openspec validate` clean on all. Cross-change reconciliation applied: `t0` added to
  RUN_START (02, needed by 04); duplicate REMOVED of the apePureMode requirement in 04
  converted to a disposition note (02 owns the removal); 04's parity-flags and
  action-selection deltas re-expressed against post-02/post-03 spec text; `ape_pure`/`bfs`
  retirement propagated into 05 (design D2, tasks 1.2/3.1–3.3/9.1–9.2). Awaiting owner
  review/approval of the artifacts — no implementation started.
- 2026-08-02 — Rigorous consistency verification of all 7 changes against the report, the code and
  `openspec/specs/` (`docs/20260802_verificacao_consistencia_rearch.md`). Findings applied to the
  artifacts: the `execute_tool_specific_logic()` blocks of 05 and 07 rewritten over the post-04 /
  post-05 text (they were reverting stage 4's gzip+converter and stage 5's preset+overrides);
  `ape_pure`/`bfs` retirement propagated into 05's delta spec, proposal and tasks (29 → 27);
  strategy whitelist deletion assigned to 05; rearch-06's proposal corrected on its 2+4 hard block;
  eight orphaned requirements that referenced deleted mechanisms ported into the stage that
  invalidates them; the `Feature`-model substitutes re-recorded in 03 and 07; INV-SEL-01/04/05/06
  dispositioned; the preserved `type_text` defect declared in 03; corpus provenance of 07 flagged
  for pinning; cross-repo OpenSpec instrument tasks added to 05 and 07. The three missing delta
  files flagged by §4.1 were created in the same commit (`ui-coverage` and `activity-budget` in
  02, `form-completion` in 04) — that item is closed.
- 2026-08-03 — P3 sweep applied (`docs/20260802_verificacao_p3_rearch.md`, commit `52965ae`).
  **A1**: stage 4's NDJSON→legacy converter is removed — the `.trace` is the NDJSON, the analysis
  side gains a native reader, `clock_logcat_join.py` migrates onto it (deleting the UTC-offset
  reconstruction the D4 heartbeat makes dead), and the frozen-corpus scripts keep their own
  readers as a stated carve-out. Sec. 9.11 acceptance becomes gated on that reader.
  **D1/D2**: `Deterministic Dead-Pair Ban` and the `ScreenshotCapture` cause seam re-anchored off
  the dismantled `LlmRouter` (onto `CoordinateMapper` / `LlmTelemetry`, mechanism untouched);
  `Tolerant Action-History Persistence` removed with its subject (`saveActionHistory`).
  **C1**: stage 2's transitional test scaffolding gets an owner — stage 5 group 10 deletes it.
  **B1–B3**: wording corrected where the construct was already legitimate. New delta specs:
  04 `llm-prompt`/`model`/`wtg-navigation`, 05 `run-spec`.
- 2026-08-03 — Worktree procedure decided and recorded (commit `d74ce6b`): one worktree, branch
  `rearch`, all 7 stages, single merge after stage 7. Pointer added to all seven `tasks.md`.
  Still 0/309 tasks, no implementation started.
- 2026-08-03 — **Artifact-vs-code verification (third pass) and its corrections.** The two prior
  audits worked artifact-against-artifact and artifact-against-roadmap; this one re-derived every
  claim the artifacts make about the code from `src/main/java`, `src/test/java` and rv-android's
  `tool.py` (`rv-android/docs/20260803_rearch_artifact_vs_code_verification.md`, 8 findings AC1–AC8,
  6 new). Owner answered the five open questions; the decisions are in the box above. Corrections
  applied to the artifacts in §8 order, `openspec validate --strict` clean on all seven:
  **AC1** rearch-02 (proposal/design/tasks/run-spec/scoring-pipeline) — stage 2 carries the ordered
  `tool.py` edit, Python first; the `18 baseline flags` fixture becomes 17; the "zero Python changes"
  premise is retired where it was false. **AC2/AC3/AC4** rearch-07 — `deepLinkUri` added to the wire
  and to the equivalence gate, INV-DRV-07 introduced, INV-DRV-02 restated, corpus pinned at 345 with
  a per-rule exercise requirement; a new `component-triggering` delta restores the dispatch paragraph
  in its post-compaction form. **AC7** rearch-03 (+ rearch-01 guard scope) — INV-ARCH-12 defaults
  guard, a paired tiebreak test at the `greedyPickLeastVisited` seam, `PipelineParityTest` given an
  owner (task 5.5a; it compiles against the factory task 5.2 deletes and was named nowhere), the
  `LlmRouter` test count corrected 40+ → 66 with a per-file destination map. **AC5/AC6** rearch-06 —
  the goldens relabelled as a ladder regression floor, neutrality re-attributed to the audits and
  unit tests, the `requireTarget()` guard carried into the recovery-point remap with a contrast test.
  **AC8** rearch-04 — INV-SNK-13 (measured throughput gate) and INV-SNK-14 (heartbeat tag must be in
  the capture allowlist; task 8.3 blocked on observing the lines).
  Two side findings fixed in passing: rearch-03's `component-triggering` MODIFIED had dropped the
  dispatch paragraph entirely (explicit-intent rule, deep link, pool exclusion), which a MODIFIED
  block deletes at archive time; and `ape/openspec/config.yaml`'s `references:` field is rejected by
  the CLI ("must be an array of store ids") — harmless warning, not touched here.
  Still 0/309 tasks, no implementation started.
- 2026-08-03 — **Artifacts approved by the owner; implementation begins.** All seven changes are
  approved as corrected (the `[x] artifacts approved` marks above), which ratifies the cross-change
  decisions in the box and list of this document. Each stage still passes its own `apply` → `verify`
  → `archive` cycle and its own gates; approval is of the plan, not of any outcome. Two procedural
  facts settled in the same decision: the ape side executes in the worktree `../ape-rearch` on branch
  `rearch`, and the rv-android side on a dedicated branch `rearch-counterparts` cut from `modules`
  (standing constraints above). Stage 1 (`rearch-01-parity-oracle`) is the first work; its goldens
  must be committed on `rearch` with `mvn test` green before any stage-2 production edit.
- 2026-08-03 — **`gh88-cal-llm-control` archived (rvsec commit `c9dfb704`, `closes #88`), with spec
  sync.** Investigated on the owner's prompt after this roadmap had recorded it as stage 5's only
  live blocker: `experimento-cal/status.py` derives Phase A at 7/8 of its loop — the campaign ran
  23–24/07 (880 tasks, VERIFY `ADMISSIBLE`) and only DECIDE was never written. The journal's next
  two entries are the 2026-08-01 `FREEZE-PREREGISTRO` of the decisive run, so phases B and C were
  superseded in fact before they were retired on paper. Archived at 47/58 with the 11 open tasks
  left unchecked and their disposition recorded in the change. **Stage 5 is unblocked**; the
  reciprocal debt it creates for gh95 is listed under Open coordination items. No ape-side artifact
  changed — still 0/309 tasks.
- 2026-08-03 — **Stage 1 closed: `rearch-01-parity-oracle` applied, verified and archived** (39/39,
  commit `2b6098b`, `closes rearch-01`; archived to
  `openspec/changes/archive/2026-08-03-rearch-01-parity-oracle/`). Suite at closure: **843 tests,
  0 failures, 19 skipped** — 13 `@Ignore` plus 6 environment-dependent `Assume` in `SglangLiveTest`,
  which run and reach the network when `SGLANG_URL` is exported, so the total is comparable only at a
  constant environment. The delta was a pure `## ADDED` block and synced to
  `openspec/specs/parity-oracle/spec.md` (6 requirements, INV-ORA-01..07); `openspec/specs/README.md`
  was deliberately left alone, its domain map already listing 10 of 20 capability directories.
  `src/main/java` differs from the pre-stage baseline `b7baa68` by exactly one file — `SataAgent`'s
  `egreedy()` seam. Two review fixes landed in the closing commit: `ScenarioScript.Builder.build()`
  now rejects a transition whose *source* screen is undeclared, and `OracleScaffold`'s Config ledger
  enumerates the same 18 keys `LadderConfigGuard` asserts, up from 9. **Stage 2 may begin**; its gate
  (task 7.1) is these five goldens green against the stage-2 jar. This entry and the stage-1
  checkboxes were written after the fact: the closing commit updated the change, not this roadmap.
- 2026-08-03 — **Artifact revisions from the telemetry-opportunity analysis** (14 files across
  `rearch-02/03/04/07`, +121/−15). Owner-commissioned in a session that audited
  `rv-android/docs/20260803_propostas_telemetria_rearch.md` against the code and the E3 decisive
  corpus (360 traces) with verification subagents, and applied the surviving items — the owner's
  "A + B, without C". Substance: `RUN_START` gains `corpus_basis` (recognized key `ape.corpusBasis`,
  echoed and read by nothing) and records that the current deployment configures **no seed**, with
  the design constraint that a future one be a function of (application, arm, replica) rather than a
  campaign constant, which would zero the three-replica variance estimator; `MOP_DATA` carries the
  full load census with **`wtgEdges` replacing `transitions`** — the flat list was being read as the
  frontier gate and is not it (14 of 40 applications report 9–29 transitions with the whole family
  disabled); `PIPELINE` gains a sibling `candidates` census, without which "the arm turned it off"
  and "this application's data could not support it" stay indistinguishable, and the family is never
  constructed in 25 of 40 applications; `RUN_END` gains `t_first_step`/`t_last_step`; three per-step
  fields (`dec.wtgsrc`, `dec.mopx`, `dec.comp`) enter under the INV-SNK-13 measurement gate, with
  `mopx` named as the first to drop if it binds; stage 7 restores `windows` and gains INV-CT-14 (the
  launch result is an observation and SHALL NOT govern control flow). `openspec validate --strict`
  clean on all four. The revisions were applied with direct edits rather than through
  `openspec-update-change` — the content is owner-commissioned and verified against the tree, the
  deviation is in the mechanism, and it is recorded here rather than left implicit. One item binds
  stage 2 group 1: task 4.3a makes `ape.corpusBasis` a third resolver-owned key, so task 1.2's
  key-ownership table is `ape.preset`, `ape.runId`, `ape.corpusBasis`.
- 2026-08-04 — **Stage 2 group 7 (the gates) is green; stage 2 is not done.** Tasks 7.1–7.6 complete,
  `rearch-02-runspec` at 40/49. What the gates said, as observed: the five rearch-01 golden classes
  across the four presets green (`ParityOracle{Aperv,Mop,Llm,LlmMop}Test` + `PreemptionGoldenTest`,
  14 tests, 0 failures); full suite **937 / 0 failures / 19 skipped** (13 `@Ignore` + 6 `Assume` in
  `SglangLiveTest`, with `SGLANG_URL` unset — the decomposition, not the total, is the comparable
  number); `mvn clean package` green with `target/ape-rv.jar` holding exactly one entry,
  `classes.dex`, 641564 bytes. No golden changed (INV-ORA-07 holds).
  **D-9's sanctioned-divergence clause did not apply, and that was established rather than assumed**:
  `StringCache.nextString()` was temporarily poisoned to throw on any call; `StringCacheSeededTest`
  went red (proving the probe live) while all five golden classes stayed green (proving the ladder
  never reaches it). The string list is not merely empty on these fixtures — the method is not
  called at all, because the list is populated only by `GUITreeBuilder`'s `cacheString(s, true)`
  sites and the oracle enters below GUITree building. The probe was reverted; byte-parity everywhere.
  **The build stamp reports `b7baa68`, not this branch's `fbcfcde`** — the documented worktree
  misreport (`docs/20260803_procedimento_worktree_rearch.md`): in a linked worktree the plugin
  normalizes `.git` to the main repo's common dir and stamps `master`'s HEAD. The stamping mechanism
  is therefore verified working (constant present in the dex, no `${…}` residue) while its *value* is
  known-wrong here; no worktree jar is deployed, so no delivered jar carries it. Neither a green nor
  a regression — recorded as observed.
  Tasks 7.4/7.5 were followed by hand: their `sdd-*` skills exist on disk but are absent from the
  Skill registry of an rv-android-rooted session. `sdd-qa-lint-fix` is a no-op here by its own rule
  (Java/checkstyle has no auto-fix) and checkstyle is neither installed nor configured; `sdd-verify`
  resolves to tests **pass** / lint **skipped** (checkstyle absent) / complexity **null** (no Java
  tool) ⇒ overall **pass**. The 7.5 review surfaced six comment-level P4 defects in groups 1–5's own
  writing, fixed here and named in the commit: five `formerly a non-final Config field` javadocs in
  `RunSpec` (lineage, and describing fields `Config` no longer has) and one `SataAgent` javadoc still
  naming the deleted `saveGraph`. No production behavior changed — the dex is byte-identical in size.
  **Why the stage is still not deployable**, corrected against the tree rather than carried over from
  the session brief, which said `rvsec#93` was blocked: it is neither blocked nor unstarted any more.
  `rvsec` commit `d8f1df0a` (2026-08-04, *"retire ape_pure_mode from the aperv arm surface"*)
  implements it — `gh93-retire-ape-pure-mode` is 30/30 tasks and `grep ape_pure_mode` over `tool.py`
  returns nothing. But it sits on branch `rearch-counterparts` alone, committed `refs #93` rather than
  `closes #93`, and design D-4's precondition is the counterpart **merged into `modules`** before any
  stage-2 jar reaches a device — the reverse order aborts every campaign arm before step 1. The gate
  is therefore unmet on the merge criterion, not on the implementation one. That commit was made
  outside this session; nothing here touched `rvsec`. Stage 2's own group 6 (4 tasks: the fixture and
  compat-test half that follow 6.0) is skipped, not done; groups 8 (owner-executed device smoke) and
  9 (change hygiene) remain. "Stage 2's gates are green" is the claim this entry makes; "stage 2 is
  done" is not, and the `apply` box above stays open.

  > **Superseded in part, later the same day (2026-08-04).** The sentence above that reads design
  > D-4's precondition as "the counterpart **merged into `modules`**" was the roadmap's own proxy for
  > D-4, not D-4's text, and the owner has since withdrawn the early-merge carve-out it rested on.
  > The precondition now reads: the `rearch-counterparts` → `modules` merge precedes the first
  > `mvn install` of a post-stage-2 jar. The entry's *conclusion* survives — stage 2 is still not
  > deployable and the `apply` box stays open — but its stated reason does not, and group 6 was not
  > in fact blocked. See D-4 and the group-6 entry below.

- 2026-08-04 — **Stage 2 group 6 (the Python contract) implemented: `rearch-02-runspec` 45/49.**
  Tasks 6.0–6.4 all closed; only groups 8 (owner-executed device smoke) and 9 (change hygiene)
  remain. Suite **956 tests, 0 failures, 19 skipped** (up from 937 by the +19 this group added:
  13 `RunSpecCompatTest`, 6 `PresetsTest`). Skip decomposition unchanged and re-observed before
  the change: 13 `@Ignore` (`ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4,
  `ApePinchOrZoomEventTest` 3, `GUITreeBuilderPasswordTest` 1) + 6 `Assume` in `SglangLiveTest`
  with `SGLANG_URL` unset. Goldens untouched (INV-ORA-07 holds; `git status --short` on them is
  empty) and the parity gate is green on all four presets (14 tests). `target/ape-rv.jar` holds
  one entry, `classes.dex`, at **641564 bytes — byte-identical to `54fddb7`**, which is the proof
  that this group's single `src/main` edit (a `Presets` javadoc) was comment-only and that group 6
  changed no production behavior.

  **D-4's precondition was restated, on owner instruction, and this is the substantive decision of
  the session.** The roadmap had encoded it as "`gh93` merged into `modules`", resting on a
  carve-out granting that counterpart an early, separate merge. The owner withdrew the carve-out —
  `rearch-counterparts` merges only after all five counterparts finish — which made the old
  phrasing self-defeating: task 6.0 could never tick, because the merge that gated it now follows
  the work it gated. Reading D-4's own text settled it: the design says *"a hard predecessor of the
  stage-2 jar **deploy**"*, so the binding event is the first `mvn install` of a post-stage-2 jar
  (`copy-jar-to-aperv-tool`), and what must hold then is that the deployed `tool.py` carries the
  removal. The merge is the durable guarantee, not the hazard boundary. Restated: **the
  `rearch-counterparts` → `modules` merge precedes the first `mvn install` of a post-stage-2 jar**
  — two end-of-line acts, gating no stage-2 group. Folded into design D-4, the stage Gate line,
  tasks 6.0/6.4, a new gate at group 8 (where it actually binds), and this document's stage-2 gate
  and branch-decision entries.

  **Task 6.4's findings.** Blast radius verified from the diff of `d8f1df0a`, not asserted: the
  only value lines it removes from `tool.py` are the three `ape_pure_mode` entries plus its
  `ARM_DEFINING_KEYS` membership; everything else in that file's diff is comment text.
  `_push_properties` is untouched and no other arm-dict entry moved. `tool.py` pinned at sha256
  `aba920ea…c93ae8`, re-derived at capture time rather than copied from the brief. The ordering
  clause is **recorded, not discharged**: both its events are owner acts still pending, and the
  end-to-end device run of the five arms is group 8's.

  **Two findings worth carrying, both discovered by a test going red.** First, feature activation
  reads *effective* values, so three features activate on the campaign arms from jar defaults that
  no arm dictionary states: `COVERAGE_BOOST` (`ape.coverageBoostWeight`=100) and `FUZZING`
  (`ape.doFuzzing`=true) on every arm, and `LLM_RANDOM` on both LLM arms — `ape.llmPercentage`
  defaults to 0.02, so `sata_llm` and `sata_mop_llm` route to the LLM at a 2% random rate that is
  invisible from the harness side (`_LLM_FLAGS` omits the key and it is not arm-defining). None is
  arm-defining, so all three cancel in a paired comparison; they are pinned in `RunSpecCompatTest`
  and now stated out loud in `RUN_START` rather than left implicit in `Config`. Second, `ape_pure`
  is therefore pure in the precise sense that *nothing it states turns anything on* — its resolved
  feature set is exactly that arm-neutral inheritance, not the empty set. The empty-set assertion
  the task text implies is false, and the test asserts the true form instead of being loosened.

- 2026-08-04 — **Stage 2 group 9 (change hygiene) implemented: `rearch-02-runspec` 47/49.**
  Tasks 9.1–9.2 closed; **only group 8, the device smoke, remains.** The group changed no
  production Java, added no test, touched no golden and no Python — the whole diff is four
  artifact files (`design.md`, `tasks.md`, `specs/exploration/spec.md`, `specs/run-spec/spec.md`).
  Gate re-observed *before* the change rather than trusted from the handoff: **956 tests, 0
  failures, 19 skipped**, BUILD SUCCESS, with the decomposition unchanged (13 `@Ignore` —
  `ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4, `ApePinchOrZoomEventTest` 3,
  `GUITreeBuilderPasswordTest` 1 — plus 6 `Assume` in `SglangLiveTest`, `SGLANG_URL` unset);
  `git status --short src/test/resources/goldens` empty (INV-ORA-07 holds); working tree carrying
  only the five `rearch-07` files of a concurrent session, which were left alone and excluded from
  the commit. `tool.py` re-checked at sha256 `aba920ea…c93ae8` at the moment it was relied on, and
  the rv-android tree confirmed on `rearch-counterparts`.

  **9.1 — eight divergences between the artifacts and the tree, all resolved in favour of the
  code.** The roster claims were sound (25 features: 15 root, 7 MOP-family, 3 LLM-family; 10
  retired keys, matching the fail-fast enumeration). What had gone stale: (a) the key split in
  D-2, `60 base / 9 retired` → **`59 / 10`**, the total 121 unchanged because retiring
  `ape.saveStates` in group 5 *moved* a key between columns instead of adding one — a retirement
  that raised the total would mean a key nobody had owned; (b) the claim, in both the inert-key
  rule and the Testing Strategy table, that the compat fixtures were checked against **all 29
  arms** — five are covered (the four campaign arms plus `ape_pure`) and the other 24 are not,
  which is task 6.1's deliberate scope and is now stated as such; (c) four test classes in the
  Mapping table that exist under other names (`FeatureDependencyTest`→`FeatureDerivationTest`,
  `RunStartEchoTest`→`RunSpecEchoTest`, `TearDownOrderTest`→`StatefulAgentCoverageDumpOrderTest`
  + `StatefulAgentTearDownTest`, "grep-guard test"→`DeviceInputChannelAbsenceTest`), plus a row
  pairing RunContext ownership with INV-RUN-06/07 instead of D-12; (d) a Mapping row promising a
  deserialization absence guard that was never written; (e) the device-smoke row still naming
  `scripts/run_emulator.sh`; (f) the second Open Question, closed by group 5's seven-method dead
  set and still listed as open; (g) task 8.1's ownership; (h) task 6.2's implied empty feature set.
  Group 6's three arm-neutral jar-default activations were also recorded in D-3, where no artifact
  had carried them, with the consequence stated once: **what an arm states is not what the run
  does**, so a configuration read off `tool.py` alone is incomplete.

  **9.2 — the three dissolutions are in order; the coverage clause is not.** INV-ARCH-06 records
  INV-RUN-05 as its substitute (echoed in D-2's inert-key rule and in INV-RUN-05's own
  parenthetical) and INV-ARCH-01 is removed with its subject under D3 — both already correct.
  INV-EXPL-03 read *"Nothing replaces it"* and now names **INV-RUN-07**: the substitution is a
  widening, from "the content of this one file" to "no artifact of a previous run is read at all",
  which is the property worth carrying — a reintroduced serializer violates the invariant before
  it can produce a file whose content anyone would need to describe.

  **Two coverage gaps, recorded rather than ticked.** Six of INV-RUN-01..08 map to a named test.
  **INV-RUN-07 has none**: the retired persistence keys are covered by
  `RunSpecAbortTest.everyRetiredKeyAbortsWithItsOwnReason` and the deletion of `Graph.readGraph` /
  `--ape-model` is compile-enforced, but neither proves the *absence* of a read-back path.
  **INV-RUN-08 is partial**: `DeviceInputChannelAbsenceTest`'s scan covers `ThreadLocalRandom`,
  one of the three mechanisms the invariant names, leaving `Math.random` and unseeded `Random`
  unscanned. A manual audit shows the tree satisfies both today (no `ObjectInputStream`/
  `readObject`/`readGraph`; no `Math.random`; the two `new Random(` sites seeded, and
  `RandomHelper`'s unseeded field initializer replaced by `RunContext.initialize` before any
  component draws). What is missing is the guard against re-introduction, of the kind INV-RUN-06
  already has. Writing the two guards is out of a hygiene group's scope and is an owner decision;
  9.2 therefore ticks as *confirmation performed*, not as *coverage complete*.

  **Group 8's ownership is corrected: it is assistant-executed.** Task 8.1 and the earlier handoff
  prompts called the device check "owner-executed" — that is superseded by owner instruction of
  2026-08-04. The permanent rule is unchanged and is what makes the correction coherent: an
  emulator is never managed by hand (no `emulator`, no `adb emu kill`, no manual boot-wait or
  install), and `rv-experiment`/`rv-platform` own the whole lifecycle, so driving the smoke through
  them *is* bringing the emulator up and down. The `scripts/run_emulator.sh` + adb fallback is
  dropped from the task: the platform route is the whole route. Group 8 also carries a live
  precondition of its own — running it means deliberately `mvn install`-ing the stage-2 jar over
  the deployed one, which every other task of this workstream forbids — so it needs the owner's
  explicit go-ahead, with the previous jar's `ls -l`/`sha256sum` captured first (the jar is
  gitignored, so git will not restore it).

- 2026-08-04 — **Stage 2 archived as `2026-08-04-rearch-02-runspec`. `rearch-02-runspec` 48/48.**
  Two owner decisions closed the stage, and the archive itself turned up a class of defect worth
  recording.

  **The device smoke is skipped (design D-13).** Group 8 is descoped and its validation folded into
  a **single coordinated end-to-end run once every planned change on both sides has landed** — the
  seven `ape` stages and the five `rvsec` counterparts. Owner's reason as given: the Python side is
  still being implemented, so a stage-2 device smoke would validate a moving jar against a moving
  harness, and each later stage overwrites the jar it installed. The two tasks were **removed, not
  ticked**; section 8 of `tasks.md` is now a disposition block enumerating the four deferred
  assertions (the abort composition in `Monkey.run`; `RUN_START` preceding every `[APE-*]` line at
  runtime; the after-state of a real run; the end-to-end run of the five arms). **Stage 2 therefore
  ships validated by 959 JVM tests and the per-preset parity goldens, and by nothing that ran on a
  device** — stated plainly rather than left for a reader to infer from "48/48, archived".
  Consequence for D-4: no stage-2 task installs the jar at all, so the ordering holds *vacuously*
  across the stage and its binding event moves outside the change. That strengthens the argument —
  there is no stage-2 act capable of violating it. The constraint's text is unchanged.

  **The two coverage gaps 9.2 reported are closed (task 9.3).** The owner chose to write the guards
  before archiving. Three scans added to `DeviceInputChannelAbsenceTest`:
  `noCodePathReadsBackAnArtifactOfAPreviousRun` (INV-RUN-07), `noOtherUnseedableGenerator…`
  (`Math.random`) and `theOnlyUnseededRandomIsTheOneTheContextReplaces`. The third is deliberately
  **not** an absence assertion: a bare `new Random()` does survive, in `RandomHelper`'s field
  initializer, and is genuinely unseeded — what makes it harmless is that `RunContext.initialize`
  reseeds before any exploration component exists. Asserting *exactly one*, in that file, is what
  makes a second one a failure instead of a silent second stream. All three passed on first run, so
  9.2's manual audit was correct rather than optimistic. Suite **959 / 0 / 19** (956 + 3; skip
  decomposition unchanged), goldens untouched, parity gate green (14 tests).

  **The archive's spec sync silently drops `## Invariants` and `## Data Contracts`, and that cost
  four fixes.** `openspec archive` applies `## Requirements` blocks and nothing else, so a delta's
  invariant section is written, validated, archived — and never reaches the main spec. Caught by
  grepping the synced specs rather than by any tool: `run-spec` landed with its nine requirements
  but **without INV-RUN-01..08** (the capability's core) and without its Data Contracts;
  `exploration` kept a live INV-EXPL-03 describing the deleted `sataModel.obj` and an INV-EXPL-29
  step list still naming the graph save; `heuristic-input` never gained INV-INP-07; `ui-coverage`'s INV-COV-10 was
  still anchored to `saveGraph`. All four were applied to `openspec/specs/` by hand after the
  archive. Two further stale sites were the *change's own* omission rather than the tool's: the
  `model` and `exploration` Data Contracts still listed `sataModel.obj`/`sataGraph.*` as live
  outputs, because no delta rewrote those sections; and `scoring-pipeline` carried three dangling
  `apePureMode` references (INV-ARCH-08 and two scenarios) that the proposal had promised to
  re-ground for four other capabilities but never enumerated for this one. **Lesson for stages 3–7:
  after every archive, grep the synced main specs for the mechanisms the change deleted — a green
  `openspec validate --specs` says nothing about whether a deleted mechanism is still described as
  live.**

  **The archive also aborted twice before succeeding, and both aborts were correct.** `openspec
  archive` refuses a MODIFIED requirement whose block omits a scenario the current spec has — the
  guard against learning 7's failure mode (a MODIFIED block replaces the whole requirement). It
  named `exploration`, then `heuristic-input`; a scan of all twelve deltas found **five** blocks
  affected, in two classes. Most were scenario *renames* the exact-title matcher cannot recognise
  (`dump precedes model serialization` → `dump precedes every other teardown writer`, and four
  others) — restored to their original titles with post-change bodies. One was a genuine loss:
  `exploration`'s `disconnect failure does not lose the model`, whose property **survives** — a
  throwing `disconnect()` must not skip `mAgent.tearDown()` — and which had been dropped along with
  the requirement sentence backing it, because the artifact it named was gone. Restored. Note for
  stage 4: `rearch-04-step-ndjson-telemetry` also MODIFIES `Output Persistence on Termination`, so
  it inherits the same discipline and must carry forward the scenario set as it now stands.

- 2026-08-04 — **Stage 3 group 1 (the scaffolding) implemented: `rearch-03-decision-pipeline` 5/53,
  and task 1.3 is deliberately left open.** Four new files under a new package, one new test package,
  **no existing `.java` touched** — `git status --short` after the work listed exactly two untracked
  directories, which is the cheap proof that a group promising to wire nothing wired nothing.
  Gate observed *before* the change rather than trusted from the handoff: **959 tests, 0 failures,
  19 skipped**, BUILD SUCCESS, decomposition 13 `@Ignore` (`ImageProcessorIntegrationTest` 5,
  `ImageProcessorTest` 4, `ApePinchOrZoomEventTest` 3, `GUITreeBuilderPasswordTest` 1) + 6 `Assume`
  in `SglangLiveTest` with `SGLANG_URL` unset. After: **984 / 0 / 19**, the +25 being this group's
  own tests (`StageResultTest` 10, `DecisionPipelineTest` 15) and the decomposition unchanged.
  Parity gate green on all four presets (`ParityOracle{Aperv,Mop,Llm,LlmMop}Test` + 
  `PreemptionGoldenTest`, 14 tests); `git status --short src/test/resources/goldens` empty
  (INV-ORA-07 holds). No `mvn install`, no `mvn package`, no Python — stage 3 has no counterpart.

  **The package name was a decision, not a reading.** No stage-3 artifact names the home of these
  types (verified by grep over the whole change directory). Chosen:
  **`com.android.commands.monkey.ape.agent.pipeline`**, the parallel of the sibling
  `agent.scoring` that this change's own `scoring-pipeline` delta names explicitly. It still reads
  correctly as "the pipeline package" for task 6.5's grep-guard, which asserts zero `Config.`
  references in the pipeline/stage/llm-unit/scoring packages. Group 2's stage classes belong in the
  same package unless it decides otherwise.

  **Why 1.3 stays open, and what landed in its place.** The task bundles
  `fromSpec(RunSpec, RunContext)` with the `decide` loop, but `fromSpec` constructs stages and **no
  stage class exists until task 2.1** — and the design's own postcondition for it ("never returns an
  empty pipeline, `SataChain` always present") is unachievable before **2.7** lands the terminal
  stage. Writing it now would mean either an unconditional throw or a method that ignores both
  parameters; both are worse than the honest gap, so it is recorded as a gap rather than ticked
  (the group-9 precedent). What *did* land is everything in 1.3 that does not need a stage:
  the roster-fixing constructor with the one `[APE-ARCH] stages=[...]` echo (package-private, so a
  run's roster comes from its plan and an arbitrary stage list is a test fixture — the
  `ScoringPipeline` shape); the `decide` loop with all four invariants (INV-DP-01/02/05/06); and the
  **plan-to-roster mapping as a static `Candidate` table** — the seven candidates in fixed order,
  each with the leaf feature that assembles it, plus `assembledCandidates(RunSpec)`. That table is
  also the static candidate census the `decision-pipeline` delta requires for the stage-4 `PIPELINE`
  record, and separating it from construction is what makes "which stages does this plan imply"
  assertable per preset with no device, model or live stage. Group 2 needs only to add the
  construction step; the mapping tested here is the one that ships.
  **Where 1.3 closes, decided by the owner (2026-08-04): at task 2.1, and its text is not moved.**
  The first stage class makes `fromSpec` writable, so the group-2 session that lands `BudgetStage`
  writes `fromSpec` over the existing `Candidate` table in the same task and ticks 1.3 then. The box
  stays open until that happens — it is the record of the residue, not an oversight, and an
  `openspec-update-change` relocating the clause was considered and declined: a task that will be
  discharged two tasks later is better left visible than rewritten. The design's "never empty"
  postcondition still only becomes true at 2.7, which is why `decide()` throws on an exhausted roster
  rather than returning null in the meantime. The gating conjuncts name the
  leaf feature only (`LLM_NEW_STATE`, not `LLM ∧ LLM_NEW_STATE`): `Feature` declares those
  dependencies and plan resolution enforces them, so the root would be a second guard for a fact the
  plan already guarantees.

  **Two members were added beyond the task text, on purpose.** `DecisionPipeline.onStateTransition`
  fans a visited edge over the roster once, in order — the roster is the only thing that knows the
  stages, and without it the `DecisionStage.onStateTransition` hook of task 1.2 has no possible
  caller; the fan-out is tested here, its wiring is 2.3/7.1's. And `lastStepSideEffects()` is the
  observable form of INV-DP-05's "the pipeline SHALL record it": per-step, cleared at each `decide`,
  and deliberately **not** a new trace line — the dispatch already logs itself, and a line invented
  here is a format stage 4 immediately restructures.

  **A wrong premise, caught by a red test rather than patched.** The assembly matrix was first
  written asserting that a bare plan assembles `[SataChain]` alone. It assembles
  **`[Budget, SataChain]`**: `ape.activityBudgetEnabled` defaults to `true`
  (`Config.java:252`), so `ACTIVITY_BUDGET` is in every plan that does not turn it off — and that
  two-stage roster is exactly the `aperv` scenario the delta spec states. The trap is that
  `Feature`'s constant table carries the **neutral** value ("false"), not the jar default, so the
  enum reads like an off-by-default gate and is not one. The assertion was corrected to the true
  behavior and paired with its contrast (budget explicitly off ⇒ `[SataChain]`). This is learning 35
  in a second costume: read configuration off the resolved plan and `Config`'s defaults, never off a
  table that happens to be adjacent.

  Task 1.5 was followed by hand — the `sdd-*` skills are on disk but absent from the Skill registry
  of an rv-android-rooted session (the stage-2 task 4.5 precedent). `sdd-doc-code`'s own INV-CODE-01
  makes most of it a no-op here, since the files were written with substantive Javadoc; the pass added
  `@param`/`@return`/`@throws` tags to the non-trivial API and skipped them on the accessors, where
  the convention's tag would only restate the summary line (P1). Its step-7 checks: no promotional
  term outside the upstream licence header, no migration-history phrasing, suite green. Task 1.6 ran
  `mvn test`, which is what the skill's own Priority-4 (Maven) branch resolves to.

  **Group 2 is not started.** It extracts the ladder block by block with the goldens as the gate
  after *every* task (INV-DP-09), which is why the session boundary is here: group 1 is the last
  point at which a green golden proves nothing about one's own edit.

- 2026-08-04 — **Stage 3 group 2, tasks 2.1–2.5 landed: `rearch-03-decision-pipeline` 11/53.**
  The ladder is now the pipeline for its first five rungs. Gate observed *before* the work rather
  than trusted from the handoff: **984 tests, 0 failures, 19 skipped**, BUILD SUCCESS, decomposition
  13 `@Ignore` (`ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4, `ApePinchOrZoomEventTest`
  3, `GUITreeBuilderPasswordTest` 1) + 6 `Assume` in `SglangLiveTest` with `SGLANG_URL` unset. After
  2.5: **1020 / 0 / 19**, the +36 being this group's own tests, decomposition unchanged. The five
  golden classes (14 tests) were re-run after **every** task and are green at each of the five
  commits; `git status --short src/test/resources/goldens` empty throughout (INV-ORA-07 holds).

  **The §4.3 question was settled at 2.1 and is recorded as design D14.** Stages reach the agent
  through a second, narrow seam — `StageCollaborators` — while `StepContext` keeps exactly the
  read surface D2 enumerates. `StepContext` is what the step *is*; `StageCollaborators` is what the
  agent *does*. Assembly reduces each collaborator to the narrowest function object its stage takes,
  so no stage holds the whole surface. Naming an interface rather than `SataAgent` is what keeps
  `fromSpec` — where INV-DP-01 and INV-DP-03 actually live — assertable from a plan and a fake
  instead of only on a device. The signature is `fromSpec(RunSpec, StageCollaborators)`: the
  `RunContext` parameter of D3's sketch was dropped as one nothing reads, and the delta spec was
  corrected to match. Task 7.1 is where the context returns.

  **The extraction seam is `InlineLadderStage`, and it is why the goldens can attribute a failure.**
  It carries whatever the extraction has not reached yet as the roster's terminal stage, so every
  interim roster satisfies INV-DP-06 and `decide()` is the live decision path of real runs and of
  every golden from task 2.1 onwards — not only after the last extraction. Each task then reads as
  one block moving out of that remainder into a stage in front of it. It and
  `StageCollaborators.decideInlineLadder()` are both replaced by `SataChainStage` at task 2.7. This
  supersedes the earlier note that the interim gap was guarded only by `decide()` throwing on an
  exhausted roster: the throw remains, but no interim roster is exhaustible.

  **The production `StepContext` is `StatefulAgent` itself.** The harness allocates its agent through
  `Unsafe`, so a field holding a view would have to be injected and its production and harness
  constructions could drift — the way the duplicated `ScoringContext` already can.

  **A red golden at 2.2, and it was right.** All four LLM golden tests failed with "the agent never
  consulted the new-state hook". The stage was correctly absent: `OracleScaffold`'s presets installed
  their plan without `ape.llmUrl`, so the LLM feature was not in it. The preset now states the URL,
  exactly as it already had to state its MopParams once the launcher gate moved into the plan. It is
  a plan value only — the router is still injected and nothing in the harness opens a socket. This is
  the injection profile adapting, the one adaptation INV-ORA-07 permits.

  **Two relocations changed where an artifact must be read from, not what it says.** The stagnation
  single shot and the launcher's cadence counter are now stage fields, so `PreemptionGoldenTest`
  reaches them through `DecisionPipeline.stages()`. Assertion content is unchanged; what changed is
  that a test observing another owner's episode state has to say so out loud, which is INV-DP-07
  working rather than an inconvenience.

  **`isNewState` went onto `StepContext` through the design's gate, not around it.** It is the
  new-state hook's whole trigger argument and is set once per step, so it is per-step data; routing
  it through `StageCollaborators` would have hidden a datum behind a behaviour seam purely to avoid
  touching the enumeration that exists to be touched. Design and delta spec both updated.

  **Tasks 2.6–2.9 are not started.** The session stopped at a green golden with 2.5's boxes ticked,
  which is the outcome the group-2 handoff asked for over a complete but unattributable group.

- 2026-08-04 — **Stage 3 group 2 finished bar its doc pass: tasks 2.6–2.8 landed,
  `rearch-03-decision-pipeline` 14/53.** The ladder is gone. `selectNewActionNonnull()` is the
  logging prologue plus `decisionPipeline.decide(this)` and nothing else — verified by reading the
  body, which carries no residual guard and no counter (2.8). Gate observed before the work rather
  than trusted: **1020 tests, 0 failures, 19 skipped**, BUILD SUCCESS, decomposition 13 `@Ignore`
  (`ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4, `ApePinchOrZoomEventTest` 3,
  `GUITreeBuilderPasswordTest` 1) + 6 `Assume` in `SglangLiveTest` with `SGLANG_URL` unset. After
  2.7: **1030 / 0 / 19**, the +10 being `ComponentTriggerStageTest` 6 and `SataChainStageTest` 4,
  decomposition unchanged. The five golden classes (14 tests) ran after each of the two extractions
  and are green at both commits; `git status --short src/test/resources/goldens` empty throughout
  (INV-ORA-07 holds).

  **The component trigger's parity argument is now structural, and it is the same argument as 2.2's
  in reverse.** The coin (`getRandom().nextDouble() < componentPercentage`) was the last conjunct of
  a short-circuiting condition whose first two conjuncts became assembly conditions. No preset
  states `ape.componentPercentage`, so `COMPONENT_TRIGGER` is in no preset's plan and the stage is
  in no preset's roster — an absent stage draws nothing, exactly as the false first conjunct drew
  nothing. Finding 2.1-c survives its own relocation: what used to be "the block's first conjunct is
  false" is now "the stage does not exist", and `OracleScaffold`'s and `OracleSataAgent`'s notes say
  so rather than pointing at deleted line numbers.

  **The cursor moved and the dispatch did not, which needed two collaborator methods rather than
  one.** D5 puts the component round-robin cursor in the stage and leaves `dispatchTrigger` /
  `dispatchProvider` with the agent. `triggerMopComponent()` held both, so it split:
  `mopComponentTargetCount()` builds the pool once and reports its size, and
  `triggerMopComponent(int)` fires the target the stage names. The alternative — passing the cursor
  in and returning it — would have left the round-robin walk on the agent and made the stage a box
  holding an int, which is ownership in name only.

  **A gap in the delta spec, settled by following its normative sentence.** `component-triggering`
  says a winning coin calls the trigger *and returns `SideEffect`*, but no scenario covers the case
  where the census declares components and none is triggerable — visible only once the split exposed
  the count. Both branches return `SideEffect`; the empty one describes itself as
  `no triggerable component target`. The cursor does not advance there, which is the pre-change
  behaviour (`total == 0` returned before the increment). Recorded rather than quietly decided: this
  is a spec gap filled by its own requirement text, not a behaviour choice.

  **`fromSpec`'s `default: break;` became its error one task early.** Task 2.7's text owns that
  clause, but 2.6 was the task after which every candidate had a construction step, so leaving a
  silent skip whose comment said "the extraction has not reached these yet" would have been a false
  comment (P4). It is now an `IllegalStateException` naming the candidate — the guard for a constant
  added without its construction step.

  **The rung table is exactly the de-duplication D12 describes and nothing more.** Seven
  `(Supplier<ModelAction>, SataEventType)` pairs in one list, walked by one loop that logs and
  returns on the first non-null, then `BadStateException("No available action on the current
  state")`. The order lives in the stage's constructor rather than at the assembly call site, which
  is what lets `SataChainStageTest` assert the order that ships instead of one a fixture invented.
  **The label is read after `logActionSelected`, not before**: that call attributes every rung but
  the two priority-consuming ones as `SATA`, so a stage that captured the source first would report
  a stale value and break INV-DP-04's equality clause. It has its own test.

  **Widening was the honest route for the six protected rungs.** `selectNewActionFromBuffer`,
  `handleNullAction` (both `StatefulAgent`), `selectNewActionBackToActivity`,
  `selectNewActionEarlyStageForward`, `selectNewActionEarlyStageBackward`,
  `selectNewActionEpsilonGreedyRandomly` and `logActionSelected` (all `SataAgent`) are now public and
  declared on `StageCollaborators`; `SataAgent.SataEventType` became public because the rung table
  carries it. `InlineLadderStage`, `StageCollaborators.decideInlineLadder()`,
  `SataAgent.decideInlineLadder()` and `SataAgent.selected(...)` were deleted in the same commit
  (P3), together with the three imports that died with them.

  **`selected(...)`'s non-model branch turned out to have been unreachable.** All seven rungs return
  `ModelAction`, so the helper's `nonModelDecisionSource` leg never ran from the chain; the stage
  types its rungs `Supplier<ModelAction>` and reads `getDecisionSource()` directly. The generic form
  was written when the block still contained the launcher, which does produce a non-model action —
  it went with `MopLauncherStage` at 2.5 and the generality was left behind.

  **`DecisionPipelineFromSpecTest`'s fake followed the code, which is learning 52 again.** Its
  `decideInlineLadder` stub became the seven rung stubs, and `inlineLadderCalls` became `chainCalls`
  counted on the buffer rung — the chain's first, and the only one no other stage shares, since the
  trivial-activity search is both rung four and the budget gate's producer. Assertion content is
  unchanged; the roster names it asserts are now `[Budget, SataChain]`.

  **Task 2.9 is not started**, and it is all that stands between group 2 and group 3: the
  `sdd-doc-code` pass over the stage classes and `sdd-test-run`. Groups 3–8 are untouched.

- 2026-08-04 — **Stage 3 group 2 closed: task 2.9's doc pass landed, `rearch-03-decision-pipeline`
  15/53.** The `ape` repo's `sdd-*` skills are on disk at `.claude/skills/` but absent from the Skill
  registry of an rv-android-rooted session, so `sdd-doc-code/SKILL.md` was read and followed
  manually — the same route task 1.5 and stage 2's task 4.5 took, and it is recorded in the commit.
  `sdd-test-run`'s Maven branch is plain `mvn test`. Gate observed before the work and again after:
  **1030 tests, 0 failures, 19 skipped**, BUILD SUCCESS, decomposition unchanged at 13 `@Ignore`
  (`ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4, `ApePinchOrZoomEventTest` 3,
  `GUITreeBuilderPasswordTest` 1) + 6 `Assume` in `SglangLiveTest` with `SGLANG_URL` unset. The
  count is identical on both sides, which is the expected shape of a comment-only task rather than
  evidence of nothing having happened. `git status --short src/test/resources/goldens` empty
  throughout (INV-ORA-07 holds); `openspec validate --strict` clean.

  **The pass was a near no-op, and that is the honest report.** INV-CODE-01 preserves substantive
  documentation, and all thirteen files in the package were written with it, so the sweep over the
  seven stages and their six seams changed exactly two comment blocks. Nothing was rewritten, no
  file gained a class or method summary it lacked, and the diff contains no executable line — the
  proof being that stripping comment lines from `git diff -U0` leaves it empty. A doc task that
  touched two blocks is not a doc task that rewrote a package, and the value of running it was the
  audit, not the edit.

  **The stale reference the P4 pass was told to hunt does exist, and it was in `StepContext`.** Its
  class javadoc justified the single write method by pointing at `SataAgent.java:503` as the site of
  the stagnation stage's accepted escape. That line has held an unrelated back/menu pick block since
  the ladder was deleted at 2.8; the reset now lives in `LlmStagnationStage.decide`, reaching the
  agent through `resetGraphStableCounter()`. It is the third instance of finding 2.1-c's family
  after `OracleScaffold`'s and `OracleSataAgent`'s, and it was fixed the way those were — by naming
  the current owner (`{@link LlmStagnationStage}`) rather than a new line number, which is the form
  that cannot go stale again. No comment was found describing behaviour the code does not have, so
  nothing had to be escalated.

  **The one genuine tag gap was `DecisionPipeline`'s package-private constructor.** Every other
  constructor in the package carries `@param`; this one had prose explaining why it is
  package-private but no tag for `stages`, which is the parameter whose copy semantics matter — the
  roster is defensively copied, so a caller's later mutation cannot reorder a policy the
  `[APE-ARCH]` echo has already reported. That is the test seam of learning 48, so it is exactly the
  member worth the tag. Everywhere else the tags were skipped deliberately (P1): `StepContext`'s and
  `DecisionPipeline`'s accessors have summaries that a `@return` would only restate, and the five
  stages whose `decide` adds nothing to `DecisionStage.decide`'s contract inherit its documentation
  instead of repeating it — the two that do add something (`SataChainStage`'s `BadStateException`,
  `LlmStagnationStage.onStateTransition`) already carried `{@inheritDoc}` blocks from their own
  tasks.

  **Two P4 checks came back clean and one is worth naming rather than acting on.** No promotional
  term occurs anywhere in the package outside the upstream Apache header (whose "Advanced Software
  Technologies Lab" is the ETH Zurich lab's name), and no comment names a type the extraction
  deleted — `InlineLadderStage`, `decideInlineLadder`, `selected(...)`, `componentTriggerIndex`,
  `stagnationHookFired` and the three underscore-prefixed launcher counters appear nowhere in
  `src/main`. What the package does carry, throughout and by construction, is prose that explains a
  current constraint by reference to the pre-extraction shape — "the conjunction below is the
  original one", "the block this replaces spelled it out seven times". Read as lineage that is
  migration history; read as rationale it is the parity argument this stage exists to make
  defensible, and it is the reason a later reader will not reorder a conditional RNG draw. It is a
  deliberate authorial voice landed across eight reviewed commits, so a doc pass is the wrong
  instrument for revisiting it: flagged here, left alone, and if it is ever to change it should be
  one decision over the whole package rather than a sweep's collateral.

  **Group 2 is complete: 2.1–2.9 all ticked.** Group 3's four permanent tests (3.1–3.4) are not
  started and change no production code; group 4, the `LlmRouter` decomposition, is the next real
  extraction and was deliberately not begun here.

- 2026-08-04 — **Stage 3 group 3 closed: the four permanent architectural tests landed,
  `rearch-03-decision-pipeline` 19/53.** Gate observed before the work and again after: **1030 →
  1042 tests, 0 failures, 19 skipped**, BUILD SUCCESS, decomposition unchanged on both sides at 13
  `@Ignore` (`ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4, `ApePinchOrZoomEventTest` 3,
  `GUITreeBuilderPasswordTest` 1) + 6 `Assume` in `SglangLiveTest` with `SGLANG_URL` unset. The
  twelve added tests are the whole delta: no production file was touched, `git status --short
  src/test/resources/goldens` was empty throughout (INV-ORA-07 holds), and no scenario script
  changed. `openspec validate --strict` clean, `--specs --strict` 21 passed.

  **What each test actually pins, and what it deliberately does not.** The group's difficulty was
  judgement, not typing: two of the four tasks were largely discharged already, and the honest
  output was to find the gap each one still left rather than to restate an existing assertion under
  a new name.

  - **3.4** (`DecisionPipelineFromSpecTest` 5 → 9) asserts the four *shipped* arms, resolved as a
    device receives them — preset name plus the deployment keys a preset omits. `DecisionPipelineTest`
    already covers what each feature gates, so what was left to get wrong is which features an arm
    states.
  - **3.1** (`HardPreemptionTest`, 3 tests) states the plan instead of shipping it, which is the only
    way to reach the fourth contender: no preset assembles `ComponentTrigger`, so `OracleScaffold`
    cannot produce a step where its coin is live. Two of its three tests are controls — the same step
    re-run with the model declining, watching the launcher fire and then the coin spend itself —
    because "no trigger fired and no rung ran" is trivially true of a step where nothing else could
    have fired anyway.
  - **3.2** (`HardPreemptionTest` 3 → 4) reads the cadence across a preempted step *within one run*.
    `PreemptionGoldenTest` pins finding 3.3-1 twice already, both times as a contrast between two
    agents; INV-DP-08 is written about one run. Cadence two, three steps, the launcher firing on the
    third and not the second — which says both halves at once, and says them behaviourally, the way
    `MopLauncherStageTest` reads these counters.
  - **3.3** (`LlmStructuralFallbackTest` 3 tests + `LlmRouterTest` 31 → 32) splits across two seams.
    Decline and timeout are already pinned by `goldens/llm/baseline.ndjson` steps 1–2; what is new is
    the composition over an assembled pipeline, run on two plans so that "the configured remainder"
    is `SataChain`/`SATA` in one and `MopLauncher`/`Component` in the other. A test that only ever saw
    `SATA` would pass against a hardcoded chain.

  **The artifacts got three things wrong and the session owed an update for all of them.** Task
  3.4's text said `mop` adds launcher/trigger — it adds neither — and that `llm`/`llm_mop` add "the
  enabled LLM stages", which is two by its own reading and three in fact. **The handoff's own
  prediction was wrong the same way**: it read `Feature`'s `LLM_RANDOM("ape.llmPercentage",
  POSITIVE, "0")` as a default and concluded the random stage is absent, when that column is the
  *neutral* value and the jar default is `0.02` (`KeyOwnership.java:216`). Learning 46 had already
  been written about exactly this column and it still caught a careful reader; the test went red on
  its first run and the assembly was right. Task 3.3's text presumed a scriptable breaker-open that
  INV-ORA-03 forbids. Task 4.7's migration table counted `LlmRouterTest` at 31, now 32. All four
  corrections went through `openspec-update-change` in one commit, together with a renamed assembly
  scenario — it was titled "full llm_mop plan assembles all stages" while describing a feature set no
  arm states, which is the same confusion the task text fell into.

  **One gap found and closed on the way, worth naming.** `LlmRouter.breakerAllows()`'s open-episode
  latch — the thing that keeps a 60-second breaker window from writing dozens of identical trace
  lines — had no test anywhere. `LlmCircuitBreakerTest` covers the state machine and
  `LlmRouterTest.shouldRouteRandom_circuitBreakerOpen_returnsFalse` covers the conjunct, but nothing
  covered the latch. It does now, and task 4.1 inherits it.

  **`PipelineFixture` is new test-only scaffolding.** 3.1 and 3.3 assert opposite halves of one
  mechanism, so they want the same plan, census, router and collaborators and differ only in the
  verdict; the shared parts live in a fixture class, which is what `FakeStepContext` already is one
  level down.

  **Group 3 is complete: 3.1–3.4 all ticked.** Group 4 — the `LlmRouter` decomposition into five
  units, with the class deleted and its 67 tests migrated — is the next real extraction and was
  deliberately not begun here.

- 2026-08-04 — **Stage 3 group 4 opened: three of the five LLM units landed,
  `rearch-03-decision-pipeline` 22/53.** Gate observed before the work and again after each task:
  **1042 → 1057 tests, 0 failures, 19 skipped**, BUILD SUCCESS, decomposition unchanged on both
  sides at 13 `@Ignore` (`ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4,
  `ApePinchOrZoomEventTest` 3, `GUITreeBuilderPasswordTest` 1) + 6 `Assume` in `SglangLiveTest`
  with `SGLANG_URL` unset. The parity gate ran after every task and held at 14/14; `git status
  --short src/test/resources/goldens` was empty throughout (INV-ORA-07 holds) and no scenario
  script changed. `openspec validate --strict` clean.

  **`LlmRouter` is still the production path.** 4.1–4.3 built the units beside it, as groups 1–3
  built the pipeline beside the ladder; nothing constructs them yet. The class dies at 4.7, and the
  wiring that replaces it is 4.6.

  - **4.1 `LlmClient`** composes `SglangClient` and `LlmCircuitBreaker` so the breaker cannot be
    consulted twice per decision — `allows()` is that single consultation, carrying the
    open-episode latch and the side-effect-free `isOpen()` emission check verbatim. Two manifest
    fields needed a decision the task text does not anticipate: `stagnation_threshold` is an
    **exploration**-scope key, not an LLM one, so it is an explicit constructor argument rather
    than an `LlmParams` read; and the three trigger keys (`llmOnNewState`, `llmOnStagnation`,
    `llmPercentage`) belong to features a plan may omit, where an absent key means a disabled
    trigger and the manifest must state the value the run behaves as rather than drop the field an
    offline reader joins on. Everything else is by key on `LlmParams` — §4.4 of the handoff asked
    whether to add named accessors, and the answer is no: `url()`/`model()` earn their names by
    being read at several sites, while nine keys read once each would be nine accessors for one
    caller (P1).

  - **4.2 `ScreenshotStep`** keeps dimension probing, capture and encoding as three calls rather
    than one, because they fail differently and the engine must report them differently: a null
    capture is `cause=screenshot` and carries the failing stage, a null encode is `cause=image` and
    has no stage at all. **`deviceDimensions()` has no unit test and that is a property of the
    method, not an omission.** `AndroidDevice.getDisplayBounds()` raises `NoClassDefFoundError` in
    the JVM suite — an `Error`, which the historical `catch (Exception)` around the tree fallback
    never covered and still does not, so it leaves the method. That is unchanged from the block the
    unit extracted, so the probe is recorded as device-only rather than given a seam it does not
    have on a device (learning 44).

  - **4.3 `CoordinateMapper`** takes geometry and the ban together because the ban's key material
    is exactly what the mapping produced. The `type_text`/`MODEL_LONG_CLICK` defect is reproduced
    and named in the javadoc. **The 28 tests task 4.7's table assigns here moved now rather than at
    the deletion, and all 28 were green on their first run against the new unit with every
    assertion unchanged** — which is the semantic-preservation evidence the extraction owes, and it
    is worth more than the same 28 tests moved after the fact.
    `feedingAndConsultingTheBanNeverTouchesTheBreaker` is the one rewrite: the mapper now holds no
    breaker at all, which states INV-RTR-16 structurally, so the assertion was remade across the
    seam a run actually has.

  **The §6.0 oracle question is settled, and the handoff's framing of it was incomplete in a way
  that changes the plan.** The handoff proposed a scripted `LlmEngine` as the natural replacement
  for `ScriptedLlmRouter`. **Object substitution alone cannot work**, and two verified facts say so:

  1. `ape.graphStableRestartThreshold` defaults to **100** (`KeyOwnership.java:142`), so the real
     `stagnationMidpointReached` midpoint is 50. Every LLM scenario's `graphStableCounter` is 0, 5,
     6 or 7 — `ParityOracleLlmTest`, `ParityOracleLlmMopTest` and `PreemptionGoldenTest` alike. The
     stub does not merely *gate* the stagnation predicate, it **replaces** it: the real predicate
     is false on every scripted step, so a stage that evaluates it would never fire the hook the
     script declares, and `llm:"declined"` would stop being reproducible.
  2. `ape.llmPercentage` defaults to 0.02, and the delta spec's `Probabilistic LLM Routing`
     requires the coin to be drawn in the stage, before the breaker gate. A real coin at 0.02
     refuses the scripted random hook ~98% of the time. (`RunContext.rng()` has no other consumer
     today, so the *draw* is stream-neutral in the oracle — it is the *verdict* that diverges.)

  The decisive case is smaller still and needs neither default: `llm/baseline` step 2 carries
  `isNewState=true` with `routesNewState=false`. The script's per-hook booleans are what select
  which hook fires on a step whose agent-side conditions hold for several. **So the scripted seam
  must be able to veto and to force each hook independently — it cannot come from the plan, and it
  cannot come from a hook-blind `allows()`.**

  **The shape chosen, to be built at 4.6:** `OracleScaffold` post-processes the assembled roster
  and gives each of the three LLM stages its *own* scripted `LlmEngine`, one per hook, so
  `allows()` is hook-aware by construction rather than by carrying a mode argument production would
  ignore. The scaffold already walks `pipeline.stages()` to seed the launcher's cadence counter, so
  this is the same adaptation, and it needs **no production test seam and no golden change** —
  squarely inside INV-ORA-07's "only the injection scaffold MAY be adapted". It also needs the
  scaffold's installed plan to state `ape.graphStableRestartThreshold` and `ape.llmPercentage` at
  values that let a scripted hook through, which is the same class of move as the `ape.llmUrl` the
  scaffold already installs. `ScriptedLlmRouter`'s contract is preserved, not weakened;
  `HookOrderRouter` becomes a recorder the three scripted engines write to. **No deviation from
  INV-ORA-07 is required and no golden needs regenerating.**

  One consequence to state rather than hide: `finishStep()`'s consumption bookkeeping shifts
  meaning slightly. Today "the predicate was invoked" means "the `LlmGate` precondition passed";
  afterwards it means "`LlmGate` passed **and** the stage's own conjunct passed". No current
  scenario declares a hook whose agent-side conjunct is false, so no scenario changes — but the
  next scenario author should know the check got sharper.

  **The artifacts owed three corrections and all three went through `openspec-update-change` in
  the same session, in two commits.** 53 tasks and 22 done throughout; `openspec validate --strict`
  clean and `--specs --strict` 21 passed after each.

  - **Task 4.6 said only "point the three LLM stages at `LlmEngine`"**, while D7's first row and
    the three MODIFIED trigger requirements all move the predicates into the stages. That move has
    to happen inside group 4 — `LlmRouter` cannot die while the predicates live in it — so the task
    now names it, together with the three sub-decisions it owns: the redundant mode conjunct is
    deleted (behaviour-neutral *because of assembly*, INV-DP-03, and for no other reason), the
    coin's stream, and the oracle seam above. Its stale `StatefulAgent:1041` anchor was corrected
    to name the owner rather than a fresh line number — the site is `:1089-1091` (learning 58).

  - **`Probabilistic LLM Routing` contradicted itself about the RNG, and this is the substantive
    one.** It said the coin is "drawn from the run's seeded RNG (`RunContext`)" while the same
    sentence invokes INV-DP-10 to promise the draw sequence is unchanged. `RunContext.rng()` is
    `RandomHelper`'s stream; the router's coin is Monkey's `mRandom` (`StatefulAgent.java:186`
    constructs the router with `ape.getRandom()`). They are **different `Random` instances seeded
    from one number** — `RunContext`'s own constructor comment says as much — and `SataAgent` draws
    from both. Moving the coin from one to the other would shift every later `RandomHelper` draw in
    an LLM arm: a real change in what a device does, **and one the parity goldens cannot catch**,
    because the oracle's scripted LLM replaces the coin outright (INV-ORA-03). The requirement now
    names the agent's stream and says why. This is the clearest instance so far of the gate having
    a blind spot exactly where a stub replaces production behaviour.

  - **`Action Selection Pipeline` declared a five-argument `selectAction`** and then, three
    paragraphs down, required step 4 to call `ApePromptBuilder.build(...)`, which cannot build a
    prompt without the `mopData` and `recentActions` the signature had dropped. The five-argument
    form is not implementable without breaking the design: both values are per-step and
    agent-owned, while the engine is constructed once per run and owned by `RunContext`, so
    sourcing them internally would mean holding a `StepContext` or the agent — which is what D2
    exists to prevent. The pre-decomposition signature is restored, with the reason recorded so the
    next reader does not re-shorten it. The alternative considered and declined was bundling the
    five per-step values into one input type: it reads better, but it is a design change in a stage
    whose contract is semantic neutrality, and it would add a type the parity gate then has to
    cover.

  **Group 4 is three of eight: 4.1–4.3 ticked, 4.4–4.8 open.** The remaining work is `LlmTelemetry`
  (4.4), the engine that composes the five (4.5), the wiring and the oracle rework above (4.6), the
  deletion and the residual test migration (4.7), and the doc pass (4.8). Group 5 was not begun.

- 2026-08-04 — **Stage 3, task 4.4 (`LlmTelemetry`) landed. `rearch-03-decision-pipeline` is
  23/53.** Suite **1074 tests, 0 failures, 19
  skipped** (13 `@Ignore`: `ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4,
  `ApePinchOrZoomEventTest` 3, `GUITreeBuilderPasswordTest` 1; + 6 `Assume` in `SglangLiveTest`
  with `SGLANG_URL` unset), BUILD SUCCESS. The baseline was observed at 1057/0/19 *before* the
  change, so the delta is exactly −3 migrated + 20 new. Parity gate 14/14; `git status --short
  src/test/resources/goldens` empty (INV-ORA-07 holds); no scenario script changed. `openspec
  validate --strict` clean, `--specs --strict` 21 passed, task count 53 throughout.

  **`LlmRouter` is still the production path.** 4.4 built the fourth unit beside it, as 4.1–4.3
  built the first three. Nothing constructs any of them yet; that is 4.6.

  - **What the unit owns.** The seventeen counters and their getters, the `[APE-LLM-PROMPT]` pair,
    `[APE-LLM-RESPONSE]`, the once-per-run `[APE-LLM-CONFIG-ACK]` latch (INV-RTR-12), the four
    `[APE-LLM-ERROR]` emissions with their cause counters, the `[APE-LLM-TEL]` builder, and
    `printSummary`. Counting and emission stayed together because they are the same act: the
    invariant that the seven causes partition the abandoned attempts (INV-RTR-11) is now a property
    of one file's control flow rather than of two files agreeing. The engine keeps the *judgement*
    (which outcome an answer is, per D7) and hands the verdict over; the unit records it.

  - **`breaker_trips` is asked for, not mirrored.** D5 assigns the trip count to `LlmClient`, and
    the router's field was refreshed at every site that could raise it — a `recordFailure()` is the
    only thing that can — so a mirror and a live read are value-identical. It arrives as an
    `IntSupplier` (production binds `client::getTripCount`), which also keeps the unit constructible
    in a test without a client. This answers the open question the group-4 handoff left at §6.1.

  - **The repair overlay still increments inside the emission**, where it always has. It is a
    property of the line — counted only when the line carries `repair=` — and moving it out would
    have made INV-RTR-13 a convention instead of a mechanism.

  - **The prompt variant is read once at construction from `LlmParams`**, the same move 4.1 made for
    the manifest, rather than per decision from `ApePromptBuilder.getPromptVariant()`. `Config`'s
    field is `final` for the run, so a per-line read could only ever produce the same string. Note
    for group 6: `ApePromptBuilder:143` still reads it statically for the prompt body itself, which
    is 6.4's, not this unit's.

  - **The three migrated tests kept their assertion content, including the reflective counter poke**
    — `LlmTelemetry` keeps `matchedCount`/`llmTapCount` as private field names, so the helper works
    unchanged (learning 68: migrating at extraction time is the evidence). **Seventeen tests are
    new, and most were not previously writable**: the `[APE-LLM-TEL]` line was built inside
    `selectAction`, which loads `AndroidDevice`, so the per-decision line was device-gated and
    validated only by the smoke. It is now a pure function of the verdict handed in, so the cause
    partition, the ACK latch, the `dead_pair` overlay, the two other `no_match` reasons and the
    repair overlay are all pinned in the JVM. `LlmRouterToolSchemaTest` was **not** touched: its 4
    assertions already exist verbatim in `LlmClientTest`, so it is 4.7's deletion, not 4.4's.

  - **One artifact correction, through `openspec-update-change`.** Task 4.4 ended with "teardown
    call site updated", which is not completable at 4.4 — nothing constructs the unit until 4.6, and
    4.6's own text already names that move together with the `_llmRouter == null` guard above it
    (learning 47). The clause now says so instead of promising it twice. One line edited, no line
    added; 53 tasks before and after.

  **What the reconnaissance established, so the next session does not re-derive it.** Three
  read-only sweeps ran before any code was written, and their findings bind 4.5–4.7:

  1. **The 4.7 destination table is stale on 28 of its 67 tests** — `LlmRouterDeadPairTest`,
     `LlmRouterCoordinateMappingTest` and `LlmRouterMappingTest` no longer exist (4.3 migrated
     them). Of the rest, `LlmRouterToolSchemaTest`'s 4 and `LlmRouterTest`'s
     `breakerOpenIsLoggedOncePerOpenEpisode` are deletions, not moves.
  2. **`LlmRouterTest`'s 32 do not split "across `LlmEngine` and `LlmClient`" as the table says.**
     By assertion content: 11 belong to the three *stages* (the trigger predicates 4.6 moves there,
     including the five pure `stagnationMidpointReached` tests), 7 to `CoordinateMapper`, 3 to
     `LlmTelemetry`, 3 to `LlmClient`, 2 to `LlmEngine`, 1 to `ToolCallParser`, and 5 are already
     covered elsewhere or vacuous. **Three of them read `LlmRouter.java` off disk as a string** and
     therefore fail at *runtime*, not at compile time, when the file is deleted — a `mvn compile`
     sweep will not surface them.
  3. **The LLM decision path reads static `Config` at exactly two files**: `LlmRouter` (25 reads,
     14 keys) and `ApePromptBuilder:62`. All of `agent/pipeline/` is already clean. Fourteen of the
     fifteen keys are available injected; `ape.graphStableRestartThreshold` is the one that is not,
     being exploration-scope — which is why `LlmClient` takes it as a separate argument, and why
     `LlmStagnationStage` will need the same channel at 4.6. A live divergence to carry into 4.6:
     the router's own predicates read `Config.llmOnNewState`/`llmOnStagnation`/`llmPercentage`
     **unguarded**, so they read the jar default even for a plan that does not carry the feature;
     today assembly masks it, and 4.6's deletion of those conjuncts removes it.

- 2026-08-04 — **Stage 3 group 4 closed: `LlmRouter` is deleted and its five successors are the
  production path. `rearch-03-decision-pipeline` is 27/53.** Four tasks landed since the last
  entry: 4.5 `LlmEngine` (`8416c305`), 4.6 the wiring and the oracle rework (`aeec13ae`), 4.7 the
  deletion (`598b00c8`), 4.8 the doc pass (`076a7c4b`). Group 5 was not begun.

  **The gates, with the skip decomposition rather than the bare total.** The 19 skips are 13
  `@Ignore` (`ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4, `ApePinchOrZoomEventTest` 3,
  `GUITreeBuilderPasswordTest` 1) plus 6 `Assume` in `SglangLiveTest`, which *run* when `SGLANG_URL`
  is exported — so a total is only comparable at a constant environment.

  | Commit | Task | Tests | Failures | Skipped |
  |---|---|---:|---:|---:|
  | `8416c305` | 4.5 | 1084 | 0 | 19 |
  | `aeec13ae` | 4.6 | 1098 | 0 | 19 |
  | `598b00c8` | 4.7 | 1067 | 0 | 19 |
  | `076a7c4b` | 4.8 | 1067 | 0 | 19 |

  The parity gate held at 14/14 after each of the four; `git status --short
  src/test/resources/goldens` printed nothing throughout (INV-ORA-07); `openspec validate --strict`
  stayed clean and `--specs --strict` 21 passed; the task count stayed 53.

  **Neither the +14 nor the −31 is drift.** 4.6 added assertions the old shape could not express —
  the five migrated `stagnationMidpointReached` statics, a coin group that drives the real draw at a
  *stated* rate instead of against a jar default, and per-hook counts of gate consultations. 4.7's
  fall decomposes as −32 (`LlmRouterTest`) −4 (`LlmRouterToolSchemaTest`) +3 moved +1 retargeted +1
  newly written.

  - **What the five own.** `LlmClient`: the transport and the breaker composed, `allows()` with the
    once-per-episode OPEN log latch, the `[APE-LLM-CONFIG]` manifest, and the tools-schema builders
    — now `buildToolsSchema(boolean, String)`, taking the prompt variant explicitly where the
    router resolved it implicitly. `ScreenshotStep`: dimension probing, capture and encode, with
    the failure-stage seam intact so a null capture and a null encode never collapse into one
    cause. `CoordinateMapper`: the normalize/denormalize wrap, the two boundary bands, `map` and
    the fixTextEdit conversion, the dead-pair ban (`banKey`/`isDeadPair`/strikes/`recordLlmOutcome`)
    and the nearest-widget calculation. `LlmTelemetry`: the counters, the four line families, the
    once-per-run ACK latch and `printSummary`. `LlmEngine`: the nine-step orchestration, never
    throwing, and the *judgement* — which outcome an answer is — handed over as a `Verdict` for the
    telemetry unit to record.

  - **The three stages now own their own trigger predicates.** Each evaluates its agent-side
    conjunct — `ctx.isNewState()`, the pure `stagnationMidpointReached`, the coin — and only then
    consults the `LlmClient.allows()` breaker gate, so the short-circuit and the draw position are
    unchanged (INV-DP-10). The redundant mode conjuncts were deleted rather than carried: by D3 and
    INV-DP-03 the feature's absence is the stage's absence, so inside the stage each was
    necessarily true.

  - **The oracle rework, and why one substitution was load-bearing.** `ScriptedLlmRouter` became
    `ScriptedLlm`, which extends nothing and hands out `gateFor(hook)` (a `BooleanSupplier`) and
    `engine()`. `OracleScaffold.installScriptedLlm` substitutes, per LLM stage, the gate and the
    engine — and on the probabilistic stage **also the `Random` field**, with a scaffold-owned `new
    Random(0L)`. Omitting that last one would have moved four committed baselines: assembly hands
    the stage the agent's own generator, the same one the epsilon-greedy rungs draw from, and an
    overridden verdict still *consumes* a draw. A consumed draw shifts every later draw of a seeded
    run.

  - **Correcting the part-3 prompt's "no scenario changes" claim.** No scenario script and no
    golden changed — that half holds. What the claim missed is that the harness's *observation
    window* shrank: a hook now reaches the script only after its own agent-side conjunct held, so
    `PreemptionGoldenTest`'s `consultedAt(2)` and `consultedAt(3)` fell from all three hooks to the
    probabilistic one alone, and `ScriptedLlm`'s "declared but never consulted" check moved from
    per-hook to per-block. Both were rewritten to the true behaviour with the reason inline rather
    than loosened. The reformulation loses no protection: the bug the check was written for is the
    shared precondition short-circuiting, which blocks all three at once, and the per-hook form
    never caught "declared but agent-conjunct false" anyway.

  - **Correcting why `ape.graphStableRestartThreshold` is inert in the harness.** Not because the
    counters top out at 7. Because `Config.graphStableRestartThreshold` is a `static final` read
    from System properties at class init (`Config.java:50`), which no `RunSpec` value writes: the
    restart test at `StatefulAgent:1127` therefore still reads 100 regardless of the plan, and it
    sits above the oracle's entry point in any case. The **plan** value reaches two sites and
    exactly one decision — `LlmStagnationStage`'s midpoint (`DecisionPipeline:160`); the other,
    `LlmClient`'s `stagnation_threshold=` manifest field (`RunContext:94`), is write-only.
    **Note for group 6**: that static read is one of 6.4's remaining sites, and `SataAgent:22`
    still carries an unused static import of the same field.

  - **4.7 deleted far more than it moved, and said so before doing it.** Of `LlmRouterTest`'s 32:
    3 guards moved to `CoordinateMapperMappingTest`, 1 was retargeted, 3 merged into one assertion
    group in `LlmTelemetryTest`, 11 were already covered by the stage tests 4.6 wrote, 4 by
    `LlmClientTest`/`ToolCallParserTest`/`CoordinateMapperOffTreeTapTest`/`ConfigTest`, 6 were
    vacuous, 2 were dropped deliberately and 2 were trivial. Three dropped things are worth naming.
    **Six boundary tests passed an empty actions list**, so `map` returned null at the no-candidates
    branch whatever the boundary branch would have decided — they proved nothing about the band
    they named; the top band, which nothing else covered, got the honest form instead.
    **`mapToModelAction_useSites_readConfigNotLiterals` asserted the source *contains* four
    `Config.` reads**, the opposite of what this change is for (6.5's grep-guard asserts zero), so
    it was deleted rather than retargeted. **INV-RTR-09 is the one this task could have destroyed
    silently**: its check read `LlmRouter.java` off disk, so deleting the file would have made it
    pass vacuously *at runtime*, not fail at compile time. It now lives in `LlmRandomStageTest` and
    sweeps the whole decision path (`agent/pipeline` + `llm`) rather than one named file.

  - **One handoff fact was wrong in the reader's favour, and checking cost minutes.** The session
    prompt held that deleting `LlmRouterToolSchemaTest` would lose the pin on "the default prompt
    variant is `ape_current`", because `grep promptVariant src/main/java/.../runtime/` returned
    nothing. The key is `ape.llmPromptVariant` — the `llm` prefix is why the grep missed it — and
    it is a registered plan key defaulting to `ape_current` (`Feature.java:166`,
    `KeyOwnership.java:208`), pinned by `LlmClientTest.manifestReportsTheValuesTheRequestsWillCarry`
    on a plan that does not state it and again on `LlmTelemetry`'s decision line. No coverage was
    lost and no compensating test was needed.

  - **Two artifact corrections, through `openspec-update-change`, before any code moved.** Task
    4.7's destination table named `LlmRouterDeadPairTest`, `LlmRouterCoordinateMappingTest`,
    `LlmRouterMappingTest` and `LlmRouterTelemetryTest` — all four migrated away by 4.3 and 4.4 —
    and its "67 unit tests" was therefore wrong; `design.md`'s risk entry carried the same stale
    67 and now records how the risk actually resolved. 53 tasks before and after: editing a table
    inside a task line adds no checkbox.

  - **4.8 was an audit, and its honest output was three re-flows.** The five units carry 1,479 lines
    with five symbols lacking javadoc, and all five classify as skip under `sdd-doc-code`'s own
    table — two value-type constructors over already-documented fields, a private JSON-envelope
    builder, a no-arg constructor, and a `safe`-prefixed one-liner following the same undocumented
    idiom as its sibling in `ApePromptBuilder`. What the pass did find was three ragged line wraps
    left by 4.7's own comment sweep. Nothing was written to make the task look worked on.

  - **The `router` vocabulary is gone from `src/main` and the `llm` tests** (P4): it had become the
    name of a class that no longer exists. `ScenarioScript`'s `routeRandom` stays — it names a step
    routed to the LLM, which still happens, and the scenario format is frozen by INV-ORA-07.

- 2026-08-04 — **rearch-04 groups 1 and 2 implemented in worktree B** (`ape-rearch-b`, branch
  `rearch-b`), 13/59 tasks. Group 1 grew `ape.runtime.Json` — the class stage 2 shipped — into the
  streaming writer stage 4 needs, rather than creating the second escaper the pre-stage-2 task text
  described; the permanent test that keeps "one format" honest is the agreement assertion between
  `Json.object(Map)` and `Json.Buf`. U+2028/U+2029 now escape: both were passing through, both are
  legal JSON unescaped, and both split a record in the readers that split before parsing. Group 2 is
  the sink core — `EventSink`, `NdjsonSink`, `StepRecord`, `NoopSink` — with the record lifecycle,
  the `ACT`/`STATE` dictionaries, the volume rules and the failure latch, plus 25 tests. No producer
  is wired: the `key=value` families still emit, and group 3 moves them. Suite 1056 → 1088, 0
  failures, 19 skipped.

  **Four `EventSink` signatures differ from the design's sketch, and design.md now records why.**
  `outcome()` carries the target's activity, because a target state can be first seen at outcome
  time and its `STATE` entry must name an activity for the reader's `out.target → STATE.act →
  ACT.mop` derivation — the sketched signature would have broken the outcome-side MOP flag for
  exactly the new states a run exists to find. `mopExposure()`, `componentLaunch()` and `llmDump()`
  are separate calls because their data exists at a different moment than the decision does; the
  launch result in particular is *why* the retired `[APE-STEP]` line could never carry it.

  **Group 3 not started — it is the stage-3 file surface** (`StatefulAgent`, `LlmRouter`), and the
  concurrent session owns those files until `rearch-03` lands. Owner decision pending.

  **Operational note for the sdd skills**: they resolve paths against the primary working directory,
  not a worktree. `/sdd-doc-code src/main/...` reported `NoTargetFiles` against `workspace-rv/ape`;
  an absolute path into `ape-rearch-b` works.

- 2026-08-04 — **Stage 3 group 5 closed: the `ScoringPipeline`'s config parameter is no longer
  decorative, and the pass list is no longer the only thing the trace says about assembly.
  `rearch-03-decision-pipeline` is 35/53.** Seven commits: the artifact update group 5 was owed
  (`adbfaf0e`), then 5.1 (`4f81edce`), 5.1a (`ead1fe1a`), 5.2+5.3 (`caa12d2a`), 5.2a (`377e89d1`),
  5.4 (`4e331d57`), 5.5 (`2c013210`), 5.5a (`214798a8`). Group 6 was not begun. All work is in
  `ape-rearch` on `rearch`; `git worktree list` still shows exactly two worktrees and no `rearch-b`.

  **The gates, with the skip decomposition rather than the bare total.** The 19 skips are 13
  `@Ignore` (`ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4, `ApePinchOrZoomEventTest` 3,
  `GUITreeBuilderPasswordTest` 1) plus 6 `Assume` in `SglangLiveTest`, which *run* when `SGLANG_URL`
  is exported — the environment was constant across every row below.

  | Commit | Task | Tests | Failures | Skipped |
  |---|---|---:|---:|---:|
  | `adbfaf0e` | artifacts | 1113 | 0 | 19 |
  | `4f81edce` | 5.1 | 1113 | 0 | 19 |
  | `ead1fe1a` | 5.1a | 1116 | 0 | 19 |
  | `caa12d2a` | 5.2 + 5.3 | 1118 | 0 | 19 |
  | `377e89d1` | 5.2a | 1121 | 0 | 19 |
  | `4e331d57` | 5.4 | — | — | — |
  | `2c013210` | 5.5 | 1124 | 0 | 19 |
  | `214798a8` | 5.5a | 1124 | 0 | 19 |

  `4e331d57` is comment-only and was verified by `test-compile` rather than re-run; it sits between
  two 1121/1124 measurements. The parity gate held at **14/14** after every task; `git status
  --short src/test/resources/goldens` printed nothing throughout (INV-ORA-07); `openspec validate
  --strict` stayed clean, `--specs --strict` 21 passed, and the task count stayed **53**.

  **The +11 is all new coverage, and each piece names a thing that could not be asserted before.**
  +3 `ScoringParamsDefaultsTest` (5.1a). +2 in `ScoringPassGateTest` (5.2/5.3): `mopWeightWtg=0` and
  `frontierBoostWeight=0` shutting their passes with the substrate present — cases the file's own
  javadoc had recorded as unreachable in-JVM because the gates read `static final` fields, and which
  an injected weight makes ordinary. +3 census tests (5.2a). +3 contrast tests (5.5).

  - **What `ScoringParams` owns.** The eight weights and gates the scoring path reads, derived from
    the resolved plan by `fromSpec`: `mopWeightDirect` 500, `mopWeightTransitive` 300,
    `mopWeightOpenMenu` 250, `mopWeightWtg` 200, `frontierBoostWeight` 200, `mopFrontierWeight` 0,
    `coverageBoostWeight` 100, `formCompletionEnabled` true. They span two scopes of the plan — six
    are MOP-family, two are not — and a pass has no business knowing which, so `fromSpec` collapses
    them and states once that **an absent feature reads as its off value**: a plan without MOP has
    no `MopParams` at all, and a plan with MOP but without WTG carries no `ape.mopWeightWtg`. To a
    pass both mean the weight is zero and the gate is shut.

    Because `mopFrontierWeight` defaults to **0**, **a default plan assembles six passes, not
    seven**. That is a shut gate rather than a missing pass, and telling those two apart in the
    trace is what 5.2a is for.

  - **The `OracleScaffold` adaptation, and why it is INV-ORA-07-permitted rather than a deviation.**
    `fromConfig` had five call sites, not the one task 5.2 named, and `OracleScaffold:553` is the
    parity gate's own harness — so the deletion breaks the merge gate's compilation. The repair is
    one line: the scaffold had already resolved the plan it installs (`RunSpec spec`) at the same
    assembly site, so it derives the params from that. INV-ORA-07 freezes the goldens and the
    scenario scripts and permits the *injection profile* to adapt to a relocated collaborator; the
    file already carried exactly this adaptation for `DecisionPipeline.fromSpec(spec, agent)`, with
    the permission recorded in a comment, and the new one is recorded the same way. No golden and
    no scenario script moved, and none could: the pipeline runs in `adjustActionsByGUITree()`,
    above this harness's entry point. It was folded into 5.2's text through
    `openspec-update-change` **before** any code was written, not discovered at `mvn test`.

  - **The candidate census (5.2a).** `ScoringPipeline.candidates()` returns every candidate pass in
    declaration order mapped to whether it was constructed — the `PIPELINE.candidates` member. It is
    a **sibling** of the pass list and never a widening of it (INV-ARCH-04): `passNames()` still
    returns exactly the constructed passes and the `[APE-ARCH]` line is byte-identical. What it buys
    is that the pass list becomes readable as a data-dependent outcome instead of a configuration
    echo — across the decisive campaign's 360 runs that line took three values, split identically in
    every arm, because the frontier family is never constructed in 25 of the 40 applications, and
    the only evidence in the trace was three names missing from a list everyone read as
    configuration.

    It is taken in the **constructor**, not in `fromParams`: same moment by a shorter route, since
    the constructor is where the full candidate list is in scope and the last moment the disabled
    passes exist. Building it in `fromParams` would mean handing the constructor a map derivable
    from the list it already receives. Keeping the disabled passes in a field so they could be
    enumerated later is what both places reject — it would keep objects alive for telemetry's sake.

    **No `reason` on an entry and no `disabledReason()` on `ScoringPass`.** Each gate is a
    conjunction of `mopData != null`, `hasWtgData()` and a weight, and all three conjuncts are
    already recorded elsewhere in the same trace (`MOP_DATA.status`, `MOP_DATA.wtgEdges`,
    `RUN_START.params`), so the reason is a lookup rather than a field — and adding one would touch
    all seven implementations plus the test double. It would also be unreliable: the passes do not
    evaluate their conjuncts in one order, so a "first failing conjunct" would report source order
    rather than cause. The consumer already existed and was not designed here: the `rearch-b` merge
    brought `EventSink.pipeline(List stages, List passes, Map<String, Boolean> candidates)` with a
    test (`NdjsonSinkTest`) and **no producer**, so the shape was given. Stage 4 wires the emission.

  **Where the handoff's scouting was wrong, and it matters in two of the four cases.**

  - **`MopScorer` does not carry six or seven static `Config` reads. It carried four**
    (`mopWeightDirect:45`, `mopWeightTransitive:48`, `mopWeightWtg:111`/`:120`); two of the six grep
    hits were javadoc. The scoring package's six were right (`FrontierPass` 2, `CoveragePass` 2,
    `WtgPass` 1, `FormCompletionPass` 1). D9's table said `MopScorer (7)` with `mopWeightOpenMenu(3)`
    and the scoring passes `(9)` with `mopFrontierWeight(3)`; design.md now carries the corrected
    rows.
  - **Two of the eight keys had no static read at all.** `mopWeightOpenMenu` and `mopFrontierWeight`
    already reached the plan through an **ambient `RunContext.current()`** read (`MopScorer:101`,
    `MopFrontierPass:43`) — which a `Config` grep does not find and INV-ARCH-11 forbids just as
    much. For those two, 5.3 replaced an ambient global with an argument rather than deleting a
    static read. **Note for 6.5**: its grep-guard is worded against "the scoring package", so as
    written it misses `MopScorer` (in `ape/utils`) entirely, and a guard that greps only for
    `Config.` would have passed over both ambient reads. Not fixed here.
  - **`StatefulAgent.java:1475-1478`** — the anchor D8 and INV-ARCH-12 use to argue that the goldens
    cannot observe a scoring default — is stale; that range is `moveForward()` today. The argument
    is sound and the sites are `:1537-1538`, where `resolveNewAction()` calls
    `adjustActionsByGUITree()` and only then `selectNewActionNonnull()`. Corrected inside
    `rearch-03`. **The same stale anchor survives in `openspec/specs/parity-oracle/spec.md:201`, in
    `rearch-06/design.md:237` and in this file at `:173`** — inherited, out of this session's scope,
    and left alone deliberately.
  - Line anchors: 5.2's `StatefulAgent:208` is `:198`; 5.5a's `PipelineParityTest` `:112`/`:148` are
    `:129`/`:165`. 5.5a's tautology had already migrated from `Config.mopWeightOpenMenu` to
    `RunContext.current().spec().mop().weightOpenMenu()` — same tautology, newer source.

  - **A latent order-dependence was fixed rather than found later.** `BasePriorityCharacterizationTest`
    errored **3/3 in isolation** at `23e4eede` (`IllegalStateException: no run context`) and was
    green in the full suite only because another class left a `RunContext` installed:
    `fromConfig` constructed `MopFrontierPass`, which reached for one. It states a sata plan as a
    value now and passes alone. This is why 5.3 is load-bearing beyond the invariant.

  - **The empty pipeline is not reachable by zeroing the weights.** Written first as "params alone
    can empty the pipeline", 5.5's contrast test failed with `[MopWidgetPass, MenuGatewayPass]`:
    those two gate on the substrate and ignore every weight. The assertion was corrected to the true
    behaviour rather than loosened — emptying it takes **both** halves, no weights *and* no MOP data
    path, which is what "a plan carrying no scoring feature" means, the path being the MOP feature's
    activation key. Both halves are pinned so they cannot be confused, and the case is now reached
    through the real entry point for the first time: it previously needed the retired `apePureMode`
    switch and was asserted through the package-private constructor.

  - **Group 5's order was changed once, deliberately.** 5.3 landed with 5.2 in one commit, ahead of
    5.2a. A `fromParams` whose passes still read statics in `isEnabled()` would compile, pass the
    suite and deliver nothing — the decorative parameter the task exists to remove — so a tree in
    which the signature had changed but the values had not would not be one consistent state (P3).
    Nothing was skipped and the count did not move.

  - **5.4 was an audit and its honest output was two edits** (learning 57). Finding 3.3-3 was
    already discharged by the injection commit, which had to rewrite the same javadoc; what
    remained was that the roster lived only on the factory method, and one comment that dated
    itself ("before this change") in a stage with several. Six pass javadocs still open with
    "extracted verbatim from the inline block in `adjustActionsByGUITree()`", which reads like the
    lineage P4 deletes. It was kept deliberately: that sentence is the parity claim (INV-ARCH-05),
    the reason the goldens hold over this code, not an account of how the file got here.

  **`ScoringParamsDefaultsTest` was verified to be able to fail**, which is the only thing that
  makes a drift guard one. With `ape.mopWeightDirect` drifted 500 → 400 it failed naming the field
  and both values (`expected:<500> but was:<400>`) while the parity gate stayed **14/14** — the
  whole argument for why it is a separate test and not a golden. The probe was reverted.

  **Group 6 was not started**, and `SataAgent:22`'s unused static import of
  `graphStableRestartThreshold` was left alone as the 6.4 note records.

- 2026-08-04 — **Stage 3 group 6 closed: the decision path no longer reads `Config`, and the guard
  that says so can fail. `rearch-03-decision-pipeline` is 41/53.** Seven commits: the artifact
  update group 6 was owed (`adf88b0b`), then 6.1 (`9273ecb2`), 6.2 (`ea7d61c7`), 6.3 (`4d8d5b53`),
  6.4 (`0eb42a22`), 6.5 (`6be9d537`), 6.6 (`a18245ad`). Group 7 was not begun. All work is in
  `ape-rearch` on `rearch`; `git worktree list` still shows exactly two worktrees and no `rearch-b`.
  `docs/handoff/` is still untracked and was left alone.

  **The gates, with the skip decomposition rather than the bare total.** The 19 skips are 13
  `@Ignore` (`ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4, `ApePinchOrZoomEventTest` 3,
  `GUITreeBuilderPasswordTest` 1) plus 6 `Assume` in `SglangLiveTest`; `SGLANG_URL` was unexported
  throughout, so the environment was constant across every row.

  | Commit | Task | Tests | Failures | Skipped |
  |---|---|---:|---:|---:|
  | `adf88b0b` | artifacts | — | — | — |
  | `9273ecb2` | 6.1 | 1124 | 0 | 19 |
  | `ea7d61c7` | 6.2 | 1124 | 0 | 19 |
  | `4d8d5b53` | 6.3 | 1126 | 0 | 19 |
  | `0eb42a22` | 6.4 | 1126 | 0 | 19 |
  | `6be9d537` | 6.5 | 1130 | 0 | 19 |
  | `a18245ad` | 6.6 | 1130 | 0 | 19 |

  The parity gate held at **14/14** after every task; `git status --short src/test/resources/goldens`
  printed nothing throughout (INV-ORA-07); `openspec validate --strict` stayed clean, `--specs
  --strict` 21 passed, and the task count stayed **53**.

  **The +6 is +2 at 6.3 (the paired tiebreak test and its counterfactual twin) and +4 at 6.5 (the
  guard).** Group 6 rewrites the selection ladder's own reads — `SataAgent.selectNewActionNonnull()`
  *is* the oracle's entry point, unlike the scoring pipeline of group 5, which runs above it — so a
  golden diff here would have been a real regression. None appeared.

  **The census the tasks quoted was of the wrong quantity, in both directions.** `Config`'s fields
  are `public static final`, so a read takes two source forms: qualified, or bare behind an
  `import static`. Eleven files in `src/main` import the second way. A grep for `Config\.` reports
  neither count — it misses every bare read *and* matches the `import static` lines, which are
  declarations, not reads. Counted with both forms enumerated and the import lines excluded:

  | Class | Before (qual. + static-imported) | Task/D9 claimed | After |
  |---|---:|---:|---:|
  | `SataAgent` | 10 + 6 = **16** over 11 keys | 25 | **0** |
  | `StatefulAgent` | 10 + 16 = **26** over 20 keys | ~30 | **0** |
  | `State` | 2 + 0 = **2** | 2 ✓ | **1** (the residue) |
  | `ApePromptBuilder` | 1 + 0 = **1** | 1 ✓ | **0** |
  | `MopCounterfactual` | 1 + 0 = **1** | not named by any task | **0** |

  `SataAgent`'s row is smaller than D9's because groups 1–4 had already moved the launcher pair,
  `componentPercentage`, `llmPercentage`, `activityBudgetEnabled` and `graphStableRestartThreshold`
  into the stages. **The previous handoff's own correction (41 and 21) was wrong the same way**: it
  counted the import lines as reads and double-counted the qualified occurrences. This is learning
  59 landing precisely where it warned it would.

  **Where the reads went.** Both agents hold an `ExplorationParams` field assigned in the
  constructor and read as a field at each site. Reaching the run context is injection at assembly
  and a violation at decision time, and putting the only plan consultation in the constructor is
  what makes that difference structural rather than a convention. The field sits on `StatefulAgent`,
  not `SataAgent` where 6.1 first put it: both classes read from it, and a second field on the
  subclass would shadow this one — including for the oracle harness, whose `setField` walks the
  hierarchy and stops at the first match. Four values resolve to primitives instead, because their
  params object does not exist on every plan: `mopTargetPickCap` and `mopDataPath` (`MopParams`),
  `stepTelemetryEnabled` (`TelemetryParams`), and `defaultEpsilon`, which the delegating constructor
  needs before any field exists. `RunSpec` grew the named accessors these sites read, following
  group 5's shape.

  **D9 was wrong about `mopDataPath`**, and it was not a bookkeeping error: it claimed rearch-02 had
  already replaced those reads, and both were live at `StatefulAgent:178`/`:179`, loading the MOP
  substrate. They now come from `MopParams.dataPath()`, null exactly when the MOP feature is absent
  — the feature is *derived* from that key being set, so `requireMopArm` reads the null exactly as it
  read an unset `Config` field. Three more keys D9 listed only under "…" (`maxIdleTimeoutMs`,
  `fuzzingActivityVisitThreshold`, `maxExtraPriorityAliasedActions`) were swept too: 6.5's guard
  asserts zero over the class, and an unclassified read is still a read.

  **What 6.5's guard actually asserts, and in what scopes.** Four assertions over
  `agent/pipeline/**`, `agent/scoring/**`, `llm/**`, `utils/MopScorer.java` and the three swept
  classes: (a) no `Config` read in **either** form, resolving each file's static imports and then
  looking for their bare use, with comments stripped first so prose about the invariant cannot trip
  it; (b) `modelMenuEnabled` pinned at **exactly one occurrence**; (c) no static
  `leastVisitedPriorityTiebreak` read anywhere in `src/main`; (d) no ambient `RunContext.current()`
  in the units that exist only as injected collaborators. `MopScorer` is named explicitly because it
  lives in `ape/utils`, outside every package the task's original wording listed — the omission that
  would have exempted it by accident. Each scope is resolved and asserted non-empty before it is
  searched, so a guarded file that moves fails loudly instead of leaving the guard passing over
  nothing (learning 72).

  **What it deliberately does not cover**, stated in its own javadoc: `RunContext.current()` in
  `SataAgent` (4 occurrences) and `StatefulAgent` (6), which is legitimate at assembly and for
  fetching the LLM collaborators, and which no source scan can tell from a decision-time read; and
  any parameter reached through a helper in a file outside the scopes, which no source scan sees
  through a call.

  **The guard was shown able to fail in each form it claims to catch**, and two probes changed it.
  Red on: a qualified read in `SataAgent`; a static-imported read in `MopScorer`;
  `RunContext.current()` in `ScoringParams`; a second `modelMenuEnabled` occurrence; a static
  `leastVisitedPriorityTiebreak` read in `MopCounterfactual`; and a guarded file renamed, which fails
  on the scope assertion. Every probe was reverted. **The residue probe found a real defect**: the
  first draft collected reads into a `Set`, so a second read of the same field hid behind the first
  and the "exactly one" pin certified nothing. It counts occurrences now. **A second probe was
  itself wrong** — it added only an `import static`, with no use, and the guard correctly stayed
  green: an unused import is a declaration, not a read, which is the same distinction that makes
  `SataAgent:22`'s dead import not a read either.

  **The `greedyPickLeastVisited` parameter.** The argument comes from
  `exploration.leastVisitedPriorityTiebreak()` at `SataAgent`'s epsilon-greedy call site, false when
  the tiebreak feature is absent (the `ape_pure` arm). The single-argument overload was deleted
  rather than kept delegating: it had no production caller and its only behaviour was the implicit
  `Config` read the task removes (P3). **`MopCounterfactual` had to move with it** — it mirrors the
  same tie rule and read the same static, so leaving it would let the counterfactual disagree with
  the branch it stands in for on any `ape_pure` plan, making `cf_changed` wrong with nothing to
  notice. The handoff said not to touch that file; 6.5's clause (c) — "zero anywhere in `src/main`",
  which predates this session's artifact update — requires it, and the disagreement is a real defect
  rather than an out-of-order tidy.

  **What the paired test proves that no golden could.** One fixed action set, one fixed set of visit
  counts, differing priorities, called with `true` and then `false`, asserting the two calls pick
  **different** actions. This is where every priority boost — MOP, WTG, coverage — becomes a chosen
  action, on the 85-98% of decisions the greedy path takes. An argument wired wrong here degrades MOP
  guidance while every stage reports the same structure: same roster, same rungs, same lines, and no
  golden record moves, because a golden records which action was chosen under one fixed plan, not
  whether the plan's value reached the comparison. Nothing else in the suite would go red. The
  priorities the test states appear nowhere in production, so the result can only carry them by
  having travelled through the argument under test (the 5.5a discipline).

  **The `modelMenuEnabled` residue, as a current decision rather than a deferral.** It stays, and it
  is not a decision-time read at all: it gates which actions a `State` is *built* with, during model
  construction, from a value the plan freezes at load. Threading plan state through
  `Model`/`State`/naming construction to reach it would be a model-layer change with no
  decision-path payoff. The rationale now sits at the read site, and the guard pins it at exactly one
  occurrence so it cannot grow a sibling.

  **6.4 was one line and 6.6 was close to nothing** (learning 57). `ApePromptBuilder` takes the
  variant by constructor from `LlmParams`; `getPromptVariant()` and the no-argument constructor are
  deleted, so the thirteen test construction sites name the variant they exercise. For 6.6,
  `/sdd-qa-lint-fix` is not in this session's Skill registry, so its `SKILL.md` was read and followed
  by hand — and its own rule decides this repo: Java maps to checkstyle, which the skill states has
  no auto-fix, and which is neither installed here nor configured. What the pass did find is the
  class of issue this sweep could have created: eight orphaned imports across the three rewritten
  files. All eight predate the sweep; they were removed anyway, because the file a task rewrites is
  the file that task's lint step owns.

  **Two `OracleScaffold` injection-profile adaptations** (INV-ORA-07, recorded in comments beside the
  existing two): the swept parameters — `exploration`, `mopTargetPickCap`, `stepTelemetryEnabled` —
  are now injected from the same plan the harness already installs. `stepTelemetryEnabled` in
  particular had to be injected rather than left at the allocator's `false`, or the harness would
  have silently stopped emitting the telemetry the jar default turns on.

  **Where the scouting turned out to be wrong.** Besides the census and `mopDataPath` above: the
  handoff pointed at `openspec/specs/decision-pipeline/spec.md` as a file to read first, and there is
  no such file — `decision-pipeline` is a capability this change *introduces*, so INV-DP-01..12 live
  only in the change's delta at `openspec/changes/rearch-03-decision-pipeline/specs/`. INV-DP-12 was
  widened there this session to require what the guard now checks: it named stages, engines, LLM
  units and scoring passes, but not `MopScorer` or the swept classes, and it did not say that a read
  takes three forms.

  **Still inherited and still left alone**: the stale anchor `StatefulAgent.java:1475-1478` in
  `openspec/specs/parity-oracle/spec.md:201`, in `rearch-06/design.md:237` and in this file at
  `:173`.

- 2026-08-04 — **Stage 3 group 7 closed: the wiring is verified rather than moved, and the two hooks
  no golden can see are now pinned. `rearch-03-decision-pipeline` is 45/53.** Five commits: the
  artifact update group 7 was owed (`08ce429b`), then 7.1 (`ed89bea7`), 7.2 (`6edf96de`), 7.3
  (`90266cfb`), 7.4 (`3d997cbb`). **Group 8 was not begun** — it is the whole stage's
  verification-and-archive gate and should run once, deliberately, against a finished stage. All work
  is in `ape-rearch` on `rearch`; `git worktree list` still shows exactly two worktrees and no
  `rearch-b`. `docs/handoff/` is still untracked and was left alone.

  **The gates, with the skip decomposition rather than the bare total.** The 19 skips are 13
  `@Ignore` (`ImageProcessorIntegrationTest` 5, `ImageProcessorTest` 4, `ApePinchOrZoomEventTest` 3,
  `GUITreeBuilderPasswordTest` 1) plus 6 `Assume` in `SglangLiveTest`; `SGLANG_URL` was unexported
  throughout, so the environment was constant across every row.

  | Commit | Task | Tests | Failures | Skipped |
  |---|---|---:|---:|---:|
  | `08ce429b` | artifacts | — | — | — |
  | `ed89bea7` | 7.1 | 1132 | 0 | 19 |
  | `6edf96de` | 7.2 | 1137 | 0 | 19 |
  | `90266cfb` | 7.3 | 1137 | 0 | 19 |
  | `3d997cbb` | 7.4 | 1137 | 0 | 19 |

  The parity gate held at **14/14** after every task; `git status --short src/test/resources/goldens`
  printed nothing throughout (INV-ORA-07); `openspec validate --strict` stayed clean, `--specs
  --strict` 21 passed, and the task count stayed **53**.

  **7.1's premise was contradicted by the code, and the decision was to keep the pipeline with the
  agent** — outcome 1 of the three the handoff called defensible, taken on three grounds and
  recorded as design **D15** before any code was written. (i) *Initialization order.* `fromSpec` binds
  the agent's action producers as method references (D14), and `RunContext` is established before any
  agent exists — which is exactly what makes `RunContext.current().spec()` readable inside the agent's
  constructor. Ownership would need a two-phase `installPipeline(...)`, a mutable field with a null
  window in the one class whose contract is a context established once, or an inverted order that puts
  the plan behind the agent that reads it. (ii) *No consumer wants it there.* The only production
  readers are the agent's `decide` and `onStateTransition`; stage 4's `PIPELINE` census is
  `assembledCandidates(RunSpec)` plus `Candidate.values()`, a pure function of the plan needing no
  instance; and `lastStepSideEffects()` has no production reader at all yet. An accessor on the context
  would add one more ambient read of the kind INV-DP-12 forbids and 6.5's guard bans in
  `agent/pipeline/**`. (iii) *The LLM half was already true* since 4.1–4.6. D14's closing sentence,
  which forward-referenced the move as settled, was corrected, as were the same promise in
  `fromSpec`'s javadoc and the assembly comment in `SataAgent`'s constructor. **The count stayed 53:
  this was a rewording, not a retirement.**

  **What 7.1 actually delivered is the assertion nothing had written**, and the probe for it is the
  strongest evidence this group produced. `TransitionForwardingTest` allocates a `SataAgent` the way
  `OracleScaffold` does and gives it a pipeline of one recording stage. Order is observable only
  through `graphStableCounter`, which the super call writes and the stage reads at hook time: three
  edges record `[1, 2, 0]` in the right order and `[0, 1, 2]` in the wrong one. Edge *identity* is
  asserted rather than equality, because `StateTransition.equals` compares source/action/target and
  **ignores the visit type** — an edge rebuilt on the way through would compare equal while re-arming
  the stagnation flag on the wrong type. **Both probes were run and both were reverted.** With the two
  calls swapped the test failed `[1, 2, 0]` → `[0, 1, 2]` **while the parity gate stayed 14/14**. With
  the forwarding deleted outright — which kills the stagnation re-arm for every real run — both new
  tests failed and the parity gate **plus `LlmStagnationStageTest`'s own 14 tests** all stayed green.
  That is learning 95 measured rather than argued.

  **Why the oracle cannot see it, verified rather than assumed.** `OracleScaffold` `Unsafe`-allocates
  its agent, so `StatefulAgent`'s `graph.addListener(this)` never runs and the harness's agent is not
  a `GraphListener` at all — nothing in the oracle ever calls `onVisitStateTransition`. The
  "exactly once per visited edge" claim of the task is about the listener graph, not a method body,
  and it decomposes into four facts: `Graph` fans each edge at one call site
  (`fireStateTransitionEvents`, `Graph.java:407`); the agent registers once
  (`StatefulAgent.java:175`) and is built once per run (`ApeAgent.createAgent`, reached once from
  `MonkeySourceApe.java:276`); the agent forwards once; and the pipeline forwards to each stage once.
  The first two are single-call-site facts about code no stage owns; the last two are what this change
  moved, and both are now under test.

  **7.2 is asserted over the roster, not per stage, and the per-stage form was already there.** Five
  of the six selecting stages already assert their own label in the test the group that extracted them
  wrote, so five more copies would restate what is pinned and still say nothing about a seventh stage.
  `StageProvenanceLabelTest` holds one expectation per `Candidate` and **asserts the table's coverage
  of the enum**, then walks the stages `fromSpec` really assembled from a plan carrying every feature
  — built explicitly, because no shipped preset states one. Each stage is asked directly rather than
  through `decide()`, since hard preemption would let the first stage answer for all of them.

  **What it catches that the goldens do not — and here the answer is "everything", for a reason worth
  recording**: `StageResult.decisionSource()` has **no caller in `src/main`**. The `[APE-STEP]` line
  reports the action's own stamped source, not the pipeline's label, so a mislabelled stage changes no
  trace and fails no golden; it stays invisible until stage 4 wires the label into the step record, at
  which point it would arrive already wrong. These tests are the label's only readers until then. Two
  facts nothing had asserted: **`SataChain`'s label is not a constant** — it stamps by boost
  attribution, and `SataChainStageTest` pins the literal `SATA` its fixture happens to produce, which
  keeps passing if the stage is rewritten to hardcode `SATA`, flattening every MOP- and WTG-guided step
  into the baseline source (the probe proved it: `MopFrontier` vs `SATA` red here, `SataChainStageTest`
  green); and **`MopLauncher`'s action is an `ActivityTriggerAction`, not a `ModelAction`**, which is
  the only reason a constant label is legitimate there, since INV-DP-04's equality is conditional on
  the action being a `ModelAction`. `Budget` is the third with teeth: it stamps and then names the
  constant again, two independent writes that could disagree. Three probes, all reverted, including a
  candidate added with no table entry — which fails naming it, the property the copies would not have.

  **7.3's scope decision, stated as one: this change's own files, not the tree.** The pass covered the
  51 `src/main` files `git diff fb3b6faf~1..HEAD` names and left the rest, on the group 6 rule that the
  file a task rewrites is the file that task's lint step owns. Six orphaned imports removed, all in
  touched files and all predating this change: `ApeAgent` (`SystemClock`), `GUITreeBuilder` (`List`),
  `Config` (`Arrays`, `LinkedHashMap`, `Map`), `SglangClientTest` (`ArrayList`). Nine remain in
  `src/main` and two in `src/test`, every one in a file this change never opened.

  **The census had to be re-derived and the handoff's was wrong in both directions**, in the same way
  group 6's was. Measured: **14 in `src/main` across 9 files**, not 15 across 10, **plus 3 in
  `src/test` it did not count at all**. The divergence is `StageResult`: its `ModelAction` import is
  referenced three times in javadoc `{@link}`, so it *is* used and removing it would break those links
  — a census that ignores javadoc reports it dead. So the change's own pipeline files had **zero**.

  **Two of 7.3's three items were "nothing to do", honestly.** The triplicated precondition text was
  already collapsed: task 2.2 created `LlmGate`, the three LLM stages call `LlmGate.allows(ctx)`, and
  `LlmRandomStage`'s one mention places its coin draw relative to the precondition — that stage's own
  draw-order content (INV-DP-10), not a copy. The P4 pass produced **two edits**: `SataChainStage`
  promised "Task 6.1 is where those reads become injected parameters", which 6.1 had already made
  false; and `State` named "the guard of task 6.5", where task numbers die at archive, so it names
  `StaticConfigReadGuardTest`. **Left alone deliberately**: the scoring package's "extracted from the
  (formerly interleaved)" family, which the owner ruled in group 5 is the INV-ARCH-05 parity claim and
  not lineage; `MopLauncherStage`'s account of the counter that is gone rather than relocated, which is
  the rationale for an absence (INV-DP-03); the measured-rationale comments in `LlmTelemetry` and
  `ModelAction`, which are the *why* P2 asks for; and `NdjsonSink`'s forward reference to group 4,
  still in flight.

  **7.4 ran the `sdd-verify` pipeline by hand and skipped two stages by the skill's own rule.**
  `/sdd-verify` is not in an rv-android-rooted session's Skill registry, so its `SKILL.md` was read and
  followed, as 6.6 did for `/sdd-qa-lint-fix`. Detection resolved at step 1 —
  `.sdd/sdd-config.yaml` states `language: java`, `mode: minimal` — so no `sdd-detection` and no MCP.
  **overall: pass**; tests 1118 passed / 0 failed / 19 skipped; **lint skipped on four independent
  grounds** (checkstyle not on `PATH`, no plugin in `pom.xml`, no `/google_checks.xml`, and the project
  config states `linter: none`), and the skill prescribes skipping rather than installing;
  **complexity null**, because the skill's table defines no complexity tool for Java, which it
  distinguishes from a defined-but-missing one. Noted and **not** fixed, being outside this change:
  `.sdd/sdd-config.yaml` states `test_framework: none` while JUnit 4 runs 1137 tests. Its
  `build_system: ant` is correct — the tree carries both `build.xml` and `pom.xml`.

  **Where the scouting turned out to be right, and where wrong.** §3.1 was right and was the session's
  load-bearing finding: 7.1's premise really was contradicted, and settling it through
  `openspec-update-change` first is what kept a design decision from arriving as a compile fix. §3.4
  was wrong about the import census in both directions (above) and right to predict that the
  precondition item would be "nothing to do". §3.2's second half was true but for a reason it did not
  state: the forwarding *body* was already correct, and what was missing was any assertion over it.

  **A tooling note for the next session.** A Java LSP is reachable from this session, but it is rooted
  at the primary working directory (`rv-android`) and never imported `ape-rearch`'s Maven project — it
  reports these files as `non-project file, only syntax errors are reported`. It resolves references
  within `src/main` and returns **nothing at all** for `src/test`: `findReferences` on
  `DecisionPipeline` found 6 in 2 files while 15 test files name it. For census-shaped questions that
  is the dangerous failure mode, so grep over both trees stayed the instrument. `/add-dir` on the
  worktree would fix it.

  **Still inherited and still left alone**: the stale anchor `StatefulAgent.java:1475-1478` in
  `openspec/specs/parity-oracle/spec.md:201`, in `rearch-06/design.md:237` and in this file at
  `:173`.
