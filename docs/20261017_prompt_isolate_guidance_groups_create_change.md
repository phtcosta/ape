# Session prompt — APE-RV `isolate-guidance-groups`: create the OpenSpec change (and sync the rv-android agent)

You are continuing work on **APE-RV** (`/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape`, a
fork of APE, Java 11 + Maven + d8). Read this whole prompt before doing anything. The author is Pedro (PhD, UnB);
he writes in Portuguese — answer him in Portuguese; repository artifacts (OpenSpec, code, comments, commits) are in
English.

## 0. Your goal in this session

1. Create a branch `isolate-guidance-groups` in the APE-RV repo from `master` (`eea2b1a9`), and work **only** there.
   **Everything happens on the branch, including the creation of the change.** Nothing is merged into `master`
   until the implementation is tested and the author, having seen good results, explicitly says so. No push and
   no merge without his word.
2. Create the OpenSpec change **`isolate-guidance-groups`** in APE-RV from the plan
   `docs/plans/20261017_isolamento_grupos_aperv.md` (in Portuguese; it is the single source of the decisions).
   Produce proposal, delta specs, design and tasks. `tasks.md` must be organised by the **waves and fronts of §10**
   of the plan, so that wave 1 can be dispatched in parallel the moment wave 0 is done.
3. Keep the **rv-android agent named `aperv-tool`** in sync. It owns the sister change in rv-android (plan §9). Use
   `ListAgents` to find it and `SendMessage({to: "aperv-tool", …})` to talk to it (§5 below).
4. **Do not implement** in this session unless the author asks you to start `/opsx:apply`. Present the artifacts
   and wait for his approval first. That is a standing rule (§7).

Time pressure: the author wants the implementation finished **today (2026-10-17)** and a mini experiment run
**tonight**. Be efficient: parallelise independent reads with subagents, never re-derive what the plan already
settles, and never re-open a decision recorded there.

## 1. Why this change exists (short)

Study 03's campaign E6 v2 compared four arms on 163 APKs:
1. `ape`, the original jar;
2. `aperv:mop_off_llm_off`, "APE-RV unguided";
3. `aperv:mopd_on_llm_off`, MOP distance guidance;
4. `aperv:mopd_on_llm_90`, arm 3 + an LLM.

C1 (arm 3 × ape) was supported, with IRR 1.16. C2 (arm 3 × arm 2) was not resolvable, with IRR 1.05. The paper
cannot attribute the gain to guidance or to the rest of the fork, because:
- **arm 2 still consumed static-analysis and MOP data:**
  - the MOP-activity tie-break inside SATA navigation;
  - the WTG frontier +200;
  - artifact-typed text input;
- **the groups talk through one shared number** (action priority), so MOP depends on addons to act and `dec.src`
  is "largest boost", not cause;
- **the LLM dose is set by hidden gates:** 0.9 configured → 69 % called → ~45 % decided;
- **MOP guidance barely decides:** when a weighted action is on screen it is taken only 10 % above chance;
- **there is a screenshot-rotation bug**: landscape screenshots are captured at rotation 0.

The fix is an APE-RV in which **ADDONS, STATIC, MOP and LLM** switch on and off independently, explicitly and
recorded, with no `if/else` sprawl, so an experiment can combine them with few cells and each contrast measures
one group. Full evidence with `file:line`: plan §2.

## 2. What was done (exploration session `aperv`, 2026-10-17)

- **Read-only investigation, with five subagents** (MOP read-sites, arm keys and real `RUN_START`s, APE × APE-RV
  diff, telemetry coverage, SATA selection paths × weights) plus one on the rotation bug.
- **The plan.** Written at `docs/plans/20261017_isolamento_grupos_aperv.md`; it is **untracked** on `master`'s
  working tree right now. Commit it as the first commit of the new branch (by explicit path).
- **Memory.** `feature-groups-isolation-exploration.md` in the project memory dir (see §8).
- **No code was changed. No change exists yet** (`openspec list` is empty).

## 3. Decisions already taken (do not re-open) — plan §3

The plan records D-A1…D-A19. The ones that shape the artifacts:

- **D-A1** `Feature.FRONTIER` must not depend on `Feature.MOP`; rename it (e.g. `NAV_FRONTIER`); it depends on the
  static provider.
- **D-A2** The static-analysis JSON is optional; without it every STATIC and MOP feature is off by declared
  dependency; our own ADDONS stay.
- **D-A3** The LLM uses MOP (markers, count, `MOP NAVIGATION`, MOP system-prompt sentences, widget metadata) — that
  dependency must be explicit (prompt built from the contributions of the mounted groups).
- **D-A4** Groups act as **scorers** (a chain of enabled scorers adds weights; SATA selects). Simple loop: LLM coin →
  LLM (error → fallback); then MOP coin; then SATA.
- **D-A5** The LLM triggers new-state and stagnation **stay**.
- **D-A6** The LLM coin wins over a non-empty SATA buffer (with P-4: abandon the buffer **without**
  `weakenStateTransition`).
- **D-A7** The MOP navigation tie-break is a **pluggable component**: the APE default (including RNG consumption),
  or the MOP variant when MOP is on.
- **D-A8 / D-A16** Launcher: not before the LLM; **global cadence** (counter advances on every step; fires on the
  first step that reaches the MOP/SATA branch).
- **D-A9** Fix the rotation bug by porting **only the code files** of commits `f993081f` and `3b35073a` from the
  abandoned branch `llm-opaque-screen` (`ScreenshotCapture.java`, `AndroidDevice.java`, the test).
  `git diff --stat e93dea86 eea2b1a9` on those two files is empty, so they apply cleanly. **Never merge that
  branch.**
- **D-A10 / D-A15** MOP coin, after the LLM:
  - With the LLM mounted, the LLM is the intended owner in `p_L` of the steps; if it fails, is refused or is
    gated, and in the remaining `1−p_L`, the step goes to the MOP coin.
  - With the LLM off, the MOP coin comes first.
  - MOP is **eligible** when the screen holds an action with an unretired target weight that has not yet been
    tried in this activity. It picks the highest weight.
  - `p_M` is configurable, default 1.0.
- **D-A11** Simplest, most elegant design; best practices; no `if/else` sprawl.
- **D-A12** Minimal cells, including a **"pure MOP"** cell; ADDONS + STATIC may be one variant in the experiment.
- **D-A13** Remove **all 10** current `aperv-tool` variants (no alias).
- **D-A14** **One** change, today, waves by file ownership.
- **D-A17** Triggers as today: new-state calls on every new state and stagnation once per episode, without the coin;
  `p_L` applies to the other steps; all of it is recorded in `dec.own`.
- **D-A18** Approved defaults:
  - one key per group over the same JSON, and the absence of the JSON switches STATIC and MOP off;
  - X2 (MOP path choice) is **out**;
  - R2 (least-visited tie broken by the scorer sum, so zero scorers = APE) is SATA's behaviour;
  - in the MOP+LLM cell the LLM reads the MOP signal;
  - Budget and restart are guards before the LLM coin (P-2);
  - own RNG streams for the LLM coin, the MOP coin and fuzzing, drawn every step before any gate (P-3);
  - no full per-candidate scorer vector.
- **D-A19** Everything on branch `isolate-guidance-groups` in both repos; merge only after good results and the
  author's explicit decision.

Still open, and **not blocking the jar**: Q-9 (which cells the mini experiment runs) and Q-10 (whether the original
`ape.jar` is in it). They belong to the experiment in `$P3`, not to this change.

## 4. What the change must contain

From the plan; cite plan sections in the artifacts:
- **Groups and data (§4.1, P-1).**
  - Groups: BASE / ADDONS / STATIC / MOP / LLM.
  - The loader splits the artifact (wire format 2, **unchanged**, the derive is untouched) into the
    `StaticGuiModel` (WTG + widget metadata) and `MopData` (targets, distances, handlers, MOP activities).
- **The step (§4.2):**
  1. guards (foreign/tree);
  2. model update;
  3. guards (Budget, restart);
  4. LLM coin;
  5. MOP coin;
  6. SATA with the scorer chain and the extension points;
  7. refiners (the input text);
  8. Monkey events.
- **Scorers R1 (§4.3).** Additive, never read the current priority, one field per scorer. The extension points
  X1 (R2), X3 and X4 carry the APE default (X3: never replace on a tie, no draw; X4: always `randomPick`).
- **LLM gates as Specification (§4.4).** `LlmGate { name(); admits(ctx); }`, with `MinActionsGate(3)` and
  `BreakerGate`. G1 (buffer) is no longer a gate. Fix the `LlmGate` javadoc ("three or fewer" vs the code's `> 2`).
- **Launcher with global cadence (§4.5).**
- **Prompt by contributions (§4.6).**
- **No-key changes (§4.7, P-6).** F governed by `formCompletionEnabled`; the bug fixes declared in BASE.
- **Telemetry (§5).**
  - Fields: `dec.own`, `cfc`, `draw`, `nav`, `inp`, `restart`, `bmc`, `strat`, `rot`, `buf`, plus `RUN_END`
    counters and the variant name in `RUN_START`.
  - The completeness table: every mechanism that can decide or change a step appears in the trace.
  - Every new computation is pure: no RNG, no `resolveAction`, no cache effect, in the mould of
    `MopCounterfactual`.
  - Gates:
    - `SinkNeutralityTest` with one preset per cell, including the real control;
    - a clean-control gate: in a cell without MOP, every MOP field is absent or 0;
    - an RNG-neutrality test.
- **The rotation fix (§2.4)**, plus `dec.rot` on every step.
- **The 6 cells (§6.2), each a jar preset composition (Q-1):**

  | Cell | Groups |
  |---|---|
  | `base` | — |
  | `mop` | MOP |
  | `as` | ADDONS + STATIC |
  | `as_mop` | ADDONS + STATIC + MOP |
  | `as_llm` | ADDONS + STATIC + LLM |
  | `as_mop_llm` | all |

- **The change is MEASUREMENT** (plan header): it changes what an arm measures.

`tasks.md` follows plan §10:
- **Wave 0, serial — contracts only:** `Feature`/`KeyOwnership`/`Presets`/`RunSpec` keys and groups,
  `EventSink`/`StepRecord`/`NdjsonSink` fields and setters, empty interfaces.
- **Wave 1, parallel, one agent and one worktree per front, disjoint files:**
  - F1 rotation;
  - F2 data;
  - F3 SATA;
  - F4 decision/coins;
  - F5 agent;
  - F6 prompt;
  - F7 is the rv-android side, owned by the `aperv-tool` agent.
- **Wave 2 — integration:** `mvn clean test`, the parity, neutrality and clean-control gates, then
  `mvn install -Drvsec_home=…`.
- **Wave 3 — device smoke:** the 6 cells on cryptoapp, plus one landscape APK with the LLM.
- **A final Verification group** (`openspec/config.yaml` rule).

The plan §10 lists file ownership and the cross-front dependencies:
- the MOP shortcut leaves `SataAgent` (F3) and becomes the MOP coin decider (F4);
- F5 owns `recordModelDecision`.

## 5. Keeping the `aperv-tool` agent in sync

The `aperv-tool` agent is a separate Claude session working in rv-android
(`/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android`, module
`modules/aperv-tool`). It owns plan §9:
- **P-7:** 6 variants = cells; a variant = groups + doses + LLM URL; the artifact is pushed iff STATIC or MOP is on;
  the variant name goes into `RUN_START`;
- **D-A13:** delete the 10 old variants;
- **P-8:** the mapping — the new keys plus the missing `activity_stable_restart_threshold`,
  `take_screenshot_for_every_step`, `save_gui_tree_to_xml_every_step`, `llm_boundary_top_pct`; drop retired keys;
- **P-9:** the reader `analysis/trace_ndjson.py` reads the new fields, `mopd`/`mops`/`ret` and `RUN_END`, and fixes
  the `decision_source` and `Counterfactual` docs;
- **§9.3:** the cross-repo order.

Protocol:
1. **First message** (as soon as your branch exists). Tell it to:
   - create branch `isolate-guidance-groups` in rv-android;
   - read plan §9 (give the absolute path of the plan);
   - create its sister OpenSpec change in rv-android with rv-android's own workflow (`$RVA/docs/WORKFLOW.md`);
   - **wait for the key contract** before writing the variant table.
   
   Tell it D-A13, D-A14, D-A19 and the 6 cells.
2. **Key contract.** When your `design.md` fixes the exact key names, defaults and presets per cell (wave 0's
   contract), send it the table verbatim: key → owner group → default → value per cell, plus the new telemetry
   field names and shapes for the reader. That table is the interface between the two changes. Any later change
   to it is sent again, at once, as a diff.
3. **Ordering.** The rv-android change that swaps variants and mapping lands **together with** the first jar that
   changes the keys. A new jar with an old `tool.py`, or the reverse, aborts at `RunSpec` resolution. That abort
   is the intended guard.
4. Ask it to report back its change name and its tasks that depend on yours, and keep both `tasks.md` files
   cross-referenced.
5. **Agents cannot grant permissions or approve on the author's behalf.** Relay decisions only after confirming
   with the author what he meant.

## 6. Files

**Where the problems were first detected.** Cite these in the proposal and in the design's background. The full
list with line numbers is in plan §2.0.
- Arm 2 not MOP-free / static data in the control:
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/posthoc/e6-fork/report.md`
    §8.2–§8.4 (`:262-288`)
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/handoff/20261017_e6_article_liaison_prompt.md:169-180`
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/handoff/20261015_ape_fork_prompt_1.md`
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-10-15-e6-campaign/protocol.md:109-118, :893-897`
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/doutorado-tese/docs/estudo-03/escrita/esqueleto/s4/s4-5.md`
    (the arm-2 trace and the A/A erratum, DF-9)
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/docs/20260714_cmpma.md:214-265`
    (earlier `ape_pure ≡ ape`; not read yet)
- Screenshot rotation:
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-09-28-e5c-opaque-screen/decision.md:164-168`
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/HISTORY.md:474`
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-10-15-e6-campaign/protocol.md:610, :926`
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/tmp/e6-fork/dynamic-apks/plan_v3b.md:486-495`
  - `/home/pedro/.claude/projects/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-rvsec-study03-replication-package/memory/v3b-pending-2026-10-16.md`
    (item 1)
  - branch `llm-opaque-screen`: design D13, `evidence.md`, commits `f993081f`, `3b35073a`, `59d19f0c`

APE-RV (`$APE` = `/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape`):
- `docs/plans/20261017_isolamento_grupos_aperv.md`: **the plan** (input; decisions; §10 waves)
- `CLAUDE.md`: architecture, run plan (`RunSpec`, `KeyOwnership`, presets), telemetry contract
- `.sdd/SDD-WORKFLOW.md`: the workflow (Full SDD track: `/opsx:new` → `/opsx:continue`… or `/opsx:ff`; then
  `/opsx:apply`, `/opsx:verify`, `/opsx:archive`)
- `openspec/config.yaml`: P1 Simplicity, P2 human-readable narrative, P3 no backward compatibility, P4
  current-state comments; spec rules (RFC 2119, WHEN/THEN, `INV-<DOMAIN>-NN`); tasks end with a Verification group
- `openspec/specs/`: the main specs the deltas modify (run-spec, decision-pipeline, action-selection, llm-routing,
  llm-prompt, event-sink, mop-*, wtg-navigation, …; list them with `openspec list --specs`)
- `docs/plans/20260802_rearchitecture_roadmap.md`: the model for cross-repo ordering (AC1)
- Hot code (paths under `src/main/java/com/android/commands/monkey/ape/`):
  - `agent/SataAgent.java` (1,632 lines), `agent/StatefulAgent.java` (2,136), `agent/ApeAgent.java`
  - `model/State.java` (`beatsLeastVisited` :185)
  - `agent/scoring/*`, `agent/pipeline/*` (`LlmGate` :53, `LlmRandomStage` :83-86, `MopLauncherStage` :123-129)
  - `llm/ApePromptBuilder.java`, `llm/ScreenshotCapture.java` :79-81, `AndroidDevice.java` :122-141
  - `utils/MopData.java`, `runtime/{Feature,KeyOwnership,Presets,RunSpec}.java`
  - `telemetry/{EventSink,StepRecord,NdjsonSink}.java`
- Abandoned branch with the rotation fix: `llm-opaque-screen` (`e91d48bf`); commits `f993081f`, `3b35073a`.

Study 03 (`$P3` = `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package`):
- `openspec/changes/archive/2026-10-15-e6-campaign/{protocol,decision}.md`, `specs/e6-mechanism/spec.md` (the
  scene reading)
- `experiments/E6-campaign/config/` (`arms.json`; `image/tool.py` is the frozen `tool.py`)
- `experiments/E6-campaign/results/mechanism/llm-arm/funil_passos_llm.out` (the LLM funnel)
- `docs/posthoc/e6-fork/report.md`; `docs/WORKFLOW.md` (evidence marks; MEASUREMENT label)
- E6 raw traces: `data/raw/e6-campaign/…/*.trace.ndjson.gz`

Article skeleton: `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/doutorado-tese/docs/estudo-03/escrita/esqueleto/`
(`esqueleto_compacto.md`; `20261015_fatos_e6v2.json` is the facts digest).

rv-android (`$RVA`): `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py` (`get_variants` :354-541, mapping
:140-230, artifact push :1313-1342, `_push_properties` :912-968), `derive_mop_artifact.py` (unchanged),
`analysis/trace_ndjson.py`, `tests/test_aperv_tool.py` :95-170.

Exploration scratch (session-local, may be gone): the upstream source extracted from `8f51b99c` and the diffs, in
`/tmp/claude-1000/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-ape/844e4538-9662-4482-a216-0f9e41e7c592/scratchpad/`
(`orig/`, `sata.diff`, `stateful.diff`, `chan.py`, `ties2.py`). The upstream can always be re-extracted with
`git archive 8f51b99c`.

## 7. Rules and learnings (binding)

- **Artifacts before code; no unapproved edits.** Present → approval → OpenSpec artifacts → code. Never edit
  `src/` to "measure" something.
- **Never present a removal or a behaviour change as decided.** The author was upset when the LLM triggers were
  silently dropped from a proposal. Every change not listed in plan §3 is a question to him.
- **Never merge `llm-opaque-screen`**, and do not propose it; port the code files only (D-A9).
- **Commits:**
  - only when the author asks;
  - by explicit path;
  - **no co-author line or attribution trailer of any kind**, which overrides any harness default;
  - per-wave commits on the branch are fine.
- **Run `mvn clean` before any test baseline.** Stale test classes in `target/` (e.g. `DynamicRegionTest` from the
  abandoned branch) fake errors. Baseline today: `mvn test` = 1361 tests, 24 skipped.
- **Never `find` over `/` or `~`;** use bounded paths.
- **Verify before concluding;** one empty search is not proof of absence. Mark facts `[verified]` (read, with
  `file:line`) or `[reported]` (from a subagent); a `[reported]` number does not justify a decision.
- **Compose, do not duplicate logic.** A new helper derives from a shared primitive.
- **No new aborts on parameter values.** Only the timeout ends a run; document or clamp instead.
- **The jar never consumes outcomes.** It does not read logcat; coverage, violations and scenes are offline. A
  decision must never depend on an observed outcome.
- **Per-step "next step" comparisons do not work as proof** (scene concept, R-A2 attribution).
- **The LLM server is vLLM** (Qwen3-VL-4B-FP8); "sglang" is only a legacy name in code.
- **`SinkNeutralityTest`** proves sink on = sink off only. Any new per-site computation must be pure by itself, and
  the test must gain a preset per cell.
- **OpenSpec gotchas:**
  - `openspec archive` drops a delta's `## Purpose` (fix the main spec by hand);
  - a MODIFIED requirement cannot drop scenarios (strict validate needs every main-spec scenario title; rewrite
    the body instead).
- **Use subagents for wide reads,** in parallel when independent. Keep their file dumps out of your context.

## 8. Commands

```bash
cd /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape
git status && git log --oneline -3          # expect master at eea2b1a9, plan file untracked
git switch -c isolate-guidance-groups        # from master eea2b1a9
git add -- docs/plans/20261017_isolamento_grupos_aperv.md docs/20261017_prompt_isolate_guidance_groups_create_change.md
git commit -m "docs(plans): isolate guidance groups — plan and session prompt" -- docs/plans/20261017_isolamento_grupos_aperv.md docs/20261017_prompt_isolate_guidance_groups_create_change.md
#   ^ ask the author before committing; no co-author trailer
openspec list --json
openspec list --specs
# create the change on the branch (skill): /opsx:ff isolate-guidance-groups   (or /opsx:new + /opsx:continue)
openspec validate isolate-guidance-groups --strict
mvn clean test                               # baseline before wave 0 (1361 tests, 24 skipped)
mvn package                                  # → target/ape-rv.jar
mvn install -Drvsec_home=/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec
```

Project memory (read `MEMORY.md` first; it is loaded automatically):
`/home/pedro/.claude/projects/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-ape/memory/`,
especially `feature-groups-isolation-exploration.md`, `llm-gate-opaque-screens.md`,
`llm-opaque-screen-merge-pending.md`, `llm-random-point-change.md`, `no-coauthor-in-commits.md`,
`artifacts-before-code-no-unapproved-edits.md`, `mvn-clean-before-baseline.md`.

## 9. Next steps, in order

1. Confirm the state with `git status` (master `eea2b1a9`, plan untracked). Create the branch. Ask the author
   whether to commit the plan and this prompt on it.
2. Send the first message to `aperv-tool` (§5.1).
3. Create the change `isolate-guidance-groups` on the branch from the plan (`/opsx:ff` is fastest):
   - proposal (MEASUREMENT, the why, the capabilities touched);
   - the delta specs per touched capability, with invariants;
   - the design (the decisions D-A*, the interfaces, the key contract table, the file-ownership table, the
     ported code from `llm-opaque-screen` with `path@SHA`);
   - `tasks.md` organised by waves/fronts with explicit file ownership.
   
   Run `openspec validate --strict`.
4. Send the key contract to `aperv-tool` (§5.2); collect its change name and its dependent tasks; cross-reference.
5. Present the artifacts to the author and wait for approval. Only then `/opsx:apply`: wave 0 serial, then wave 1
   dispatched in parallel (one subagent per front, `isolation: "worktree"`), then waves 2 and 3.
6. After waves 2–3, hand the jar sha256 + `GIT_SHA` to the Study 03 side for the mini experiment (Q-9/Q-10 are the
   author's). Merge into `master` only on his explicit go after the results.
