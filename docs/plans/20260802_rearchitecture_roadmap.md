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

- [ ] **1. `rearch-01-parity-oracle`** — golden capture of current per-preset decision
      sequences + preemption golden (incl. finding 3.3-1). Pure test infra, no production change.
      *Gate for stages 2–3.*
  - [ ] artifacts approved · [ ] apply · [ ] verify · [ ] archive
- [ ] **2. `rearch-02-runspec`** — `RunSpec` + presets in jar + `Feature` enum + total
      fail-fast + level-0 `RUN_START` echo (D1) + removal of `/sdcard` readers (D6) and of
      `saveGraph`/`readGraph`/`--ape-model`. Zero Python changes.
      *Gate: parity oracle green per preset.*
  - [ ] artifacts approved · [ ] apply · [ ] verify · [ ] archive
- [ ] **3. `rearch-03-decision-pipeline`** — `DecisionPipeline` stages + `StageResult` sum
      type + episode state relocated + `LlmRouter` sliced + `ScoringPipeline` real injection.
      Hard preemption preserved exactly (Q1).
      *Gate: parity oracle green per preset; permanent preemption golden.*
  - [ ] artifacts approved · [ ] apply · [ ] verify · [ ] archive
- [ ] **4. `rearch-04-step-ndjson-telemetry`** — `EventSink` step-grouped NDJSON (D2) +
      escaping serializer + heartbeat (D4) + `RUN_END` write-only (D5) + legacy outputs
      deleted + temporary converter + gzip at collection. Dissolves INV-ARCH-01.
      *Gates: neutrality test (sink on/off, same seed ⇒ same decisions); calibration report
      2026-07-24 regenerable (Sec. 9.11); round-trip/one-line tests (Sec. 9.12).*
  - [ ] artifacts approved · [ ] apply · [ ] verify · [ ] archive
- [ ] **5. `rearch-05-thin-python-arms`** — arms = preset + overrides; dead keys and Python
      kill-switch duplication deleted; INV-APV-14 retired. First cross-repo stage (rv-android).
      *Gate: regeneration diff of the 27 surviving arms' effective configs, identical before/after;
      `ape_pure`/`bfs` recorded as documented retirements, not diffs.*
  - [ ] artifacts approved · [ ] apply · [ ] verify · [ ] archive
- [ ] **6. `rearch-06-memory-surgical`** — V12 cache release; V11/V24 diagnostic retention
      to IDs/minimal snapshots, conditional on caller audit. No speculative bounds.
      *Gate: action-sequence parity after each retention change.*
  - [ ] artifacts approved · [ ] apply · [ ] verify · [ ] archive
- [ ] **7. `rearch-07-compact-static-artifact`** — host-side derived artifact (~1–5 MB);
      `MopData` consumes it; MOP-feature-without-artifact aborts fail-fast.
      *Gates: frozen metric sets preserved by derivation (R9); cross-repo push path updated.*
  - [ ] artifacts approved · [ ] apply · [ ] verify · [ ] archive

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

## Related state

- `gh10-normalize-boosts` closed as **superseded** 2026-08-02 (never implemented; archived
  with `--skip-specs`). Boost-magnitude question re-expressible later as a preset override
  informed by #16 data.
- `telemetry-proof-llm-efficacy` is **archived** (2026-08-02, 51/51, verified and synced —
  `openspec/changes/archive/2026-08-02-telemetry-proof-llm-efficacy/`; task 17.4 was closed by the
  decisive-run evidence, commit `99dded5`). Its telemetry format is superseded by stage 4
  (acceptance Sec. 9.11 protects the transition).

## Cross-change decisions recorded during artifact drafting (owner ratifies at approval)

1. **`ape_pure` and `bfs` variants are retired** (rearch-02 design → rearch-05 D2): no
   structural-purity preset exists; `ape.apePureMode` is a retired key that aborts, and
   unknown `--ape` values abort. Consistent with D3 (control = minimal `aperv`). Verified
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
5. **INV-ARCH-01 disposition split**: the requirement (scoring-pipeline spec) is REMOVED
   by rearch-02 with its subject (apePureMode, per D3); the telemetry-half substitute
   (INV-SNK-07 neutrality + sink-on/off test, R7) is recorded by rearch-04.
6. **V11 ordering**: rearch-06's ActionRecord snapshot fix is hard-blocked on stages 2+4
   (teardown `saveActionHistory` dies at stage 4; `reducer/` is dead tooling outside the
   Maven build).
7. **rearch-07 schema**: derived artifact `formatVersion: 1`; `reachability[]` confirmed
   57.7% of aggregate bytes over the 134 real JSONs; coordinated jar+Python cut, no
   fallback window, gated by the full-vs-derived corpus equivalence test (R9).

Open coordination items — status 2026-08-02:
- `telemetry-proof-llm-efficacy` must archive before rearch-03/04 (their deltas are written
  against its post-sync text; rearch-03 task 8.6) — **satisfied**: archived 2026-08-02.
- rv-android changes touching the same arms must merge before rearch-05 (task 1.3):
  `gh90-e3-decisive-run-setup` is **archived**; **`gh88-cal-llm-control` (47/58, untouched since
  2026-07-24) is the only live blocker** for stage 5.
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
  for pinning; cross-repo OpenSpec instrument tasks added to 05 and 07. Three requirements still
  need delta files that do not exist yet (`ui-coverage`, `form-completion`, `activity-budget`) —
  see the verification document §4.1.
