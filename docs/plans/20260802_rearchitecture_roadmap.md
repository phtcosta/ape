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
- [ ] **2. `rearch-02-runspec`** — `RunSpec` + presets in jar + `Feature` enum + total
      fail-fast + level-0 `RUN_START` echo (D1) + removal of `/sdcard` readers (D6) and of
      `saveGraph`/`readGraph`/`--ape-model`. **One ordered Python edit** (AC1): the counterpart change
      in rv-android removes `ape_pure_mode` from `tool.py` and lands *before* this jar — stage 2, not
      stage 5, is where the cross-repo coupling starts.
      *Gate: parity oracle green per preset; counterpart merged before any device deploy.*
  - [x] artifacts approved (2026-08-03) · [ ] apply · [ ] verify · [ ] archive
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
  The cost this choice accepts is explicit: **`gh93` must be merged into `modules` before the
  stage-2 jar reaches any device** (23 of 29 arms push `ape.apePureMode`, which that jar aborts on),
  so its merge is early and separate from the line's final merge.

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
