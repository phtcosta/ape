# Session prompt — consistency verification and adversarial review of OpenSpec change `llm-coordinate-single-base`

You are continuing work on **APE-RV** (`/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape`, branch `master`). Read `CLAUDE.md` of the repo and your memory index first. Answer the author in Portuguese (pt-BR); the artifacts are written in English.

## 1. Goal of this session

The change `openspec/changes/llm-coordinate-single-base` is **artifacts only**: nothing is implemented, nothing is committed, and implementation is not scheduled. A first consistency review (2026-10-05) found 1 blocker, 5 major and 12 minor findings, all of which were applied, and the author then approved a new decision (D11, below). This session does two things, in this order:

1. **Consistency verification** of the artifacts as they now stand. The previous session edited them heavily. Check that the edits are coherent with each other, with the code at HEAD, with the evidence, and with the main specs, and that nothing old and contradictory survived.
2. **Adversarial review of the proposal itself.** Do not assume the change is right. Try to break it: will it actually deliver a gain, does it work as specified, is it correct, are the numbers that justify it sound, is anything it claims unmeasurable or overstated, and could it make anything worse.

Deliver a findings report to the author first. Each finding gets a severity (blocker / major / minor / nit), `file:line`, the claim, the evidence and the proposed fix, plus one clear recommendation. **Do not edit anything until the author approves the specific fixes.**

## 2. Mandatory workflow rules (follow rigorously)

1. **Artifacts before code; no unapproved edits.** The order is: present findings → author approves → edit artifacts. Never touch `src/`, not even to "measure". Probe scripts go in your session scratchpad, never in the project tree. `evidence/` is the change's evidence folder: add or modify a script there only as part of an approved fix, keep scripts read-only over their inputs and printing to stdout, never let them leave `__pycache__` (run with `python3 -B`), and keep previously published scenario lines byte-identical when extending a script.
2. **Stay on `master`.** Do not check out, modify, merge or archive anything on other branches (`llm-opaque-screen`, `laya`, `mop-fairtest`, `rearch`). Never propose merging `llm-opaque-screen`: it was abandoned on 2026-09-28.
3. **No commit** unless the author explicitly says so. When committing, end messages with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
4. **Verify before concluding.** One empty search is not proof of absence. Check claims against code and evidence files, not against memory or against what a previous session wrote.
5. **Never run `find`/`grep -r` rooted at `/` or `~`.** Use bounded paths.
6. **Do not re-litigate settled decisions** (§6). You may report that a settled decision has a *consequence* the artifacts misstate, but not reopen the decision.
7. Use subagents for parallel, independent checks. **Verify every subagent claim you relay**, and say which claims you verified yourself. Subagents in the previous session were mostly right but made real errors (see §9).
8. Run `openspec validate llm-coordinate-single-base --strict` after any approved artifact edit. It must print `Change 'llm-coordinate-single-base' is valid`.
9. Be concrete and organized. The author has complained about loose, vague explanations. Give one clear recommendation, not a menu. When asked "why", answer with code and data, and admit a wrong call plainly.
10. Recompute every number you state. Exploratory scripts can differ from the evidence scripts (§9 has two examples from the previous session).

## 3. The defect and the change (summary; the artifacts are authoritative)

**Pipeline** (`LlmEngine.selectAction`, `src/main/java/com/android/commands/monkey/ape/llm/LlmEngine.java:86-192`):

1. `ScreenshotStep.deviceDimensions` gives `{W,H}` = `Display.getSize` = 1080 × 1794 on the campaign AVD (navigation bar excluded, 63 px status bar included).
2. Capture crops `(0,0)–(W,H)`; the image is resized to 602 × 1000; Qwen3-VL answers in [0,1000).
3. `ApePromptBuilder` lists widgets as `@(x,y)` in [0,1000). **Defect:** it divides by the active window's **root node** bounds (1080 × 1920 for a full-screen activity; the dialog's rectangle for dialogs).
4. The answer goes back to a pixel with the display (`CoordinateNormalizer.normalize`).
5. `CoordinateMapper.map`: back → boundary band → containment → snap (campaign floor 150 px) → off-tree `LlmTapAction`.

**Decisions D1–D11 in `design.md`:**

- **D1–D5:** one base, the display pair from `deviceDimensions`.
  - New forward primitive `CoordinateNormalizer.toNormalized`.
  - `build(int W, int H, …)` loses its `GUITree` parameter.
  - History entries store **pixel bounds** (`Rect bounds`); the builder renders them.
- **D6:** no flag.
- **D7:** `ape.llmBoundaryBottomPct` is retired (`KeyOwnership.RETIRED`); the top band default becomes 0.035.
- **D8:** an answer outside [0,999] (tool ≠ back) is refused before it is **mapped**; `toPixels` still runs for telemetry.
- **D9:** history records what ran.
  - An LLM tap is recorded as a 1-px rectangle at its pixel.
  - A menu press is recorded as `menu`.
  - The v17 history goes through `formatHistoryEntry`.
- **D10:** v13/v17 lists render one line per widget (resolved node, by identity), with no ordinal.
  - The v17 status tag sums the widget's actions' visits.
  - The v17 SCREEN line counts widgets on both sides and reads `elements tested`.
- **D11 (new, approved 2026-10-05):** a widget is listed at the **centre of its visible part**, `bounds ∩ [0,W)×[0,H)`, via one private helper `visibleCentre`.
  - A wholly off-screen widget keeps its line with no `@(x,y)`.
  - Rationale: this is the point `MonkeySourceApe.generateClickEventAt` taps (`MonkeySourceApe.java:368-419`, `getVisibleBounds` `:299-333`), and that method drops off-screen actions (INV-EXPL-19).
  - The dispatcher also intersects with the window root. The list uses the display only, because `build` no longer receives the tree.
- **Unresolved node:** all three list sites omit `@(x,y)`. Today they write `@(0,0)`, contradicting the main-spec scenario "Unresolved action node" (since gh6, untested).

**Spec deltas:**

- `llm-prompt`:
  - INV-PRM-06;
  - ADDED "Single Coordinate Base" and "Element List of the v13 and v17 Variants";
  - MODIFIED "Widget List Generation" (now scoped to ape_current/ape_reasoning/compact_v1) and "Action History".
- `llm-infrastructure`:
  - INV-LLM-13;
  - MODIFIED "CoordinateNormalizer…" and "LLM Configuration Keys".
- `llm-routing`:
  - INV-RTR-21 and INV-RTR-22;
  - MODIFIED "Action Selection Pipeline", "Coordinate-to-ModelAction Mapping", "LLM Telemetry Logging" and "LLM Unit Lifecycle and Ownership".
- `run-spec`: MODIFIED "Total Fail-Fast Validation".
- `exploration` (**new delta**): MODIFIED "StatefulAgent — Action History Ring Buffer".

**Scope:** future studies only. E6 is not re-run. The defect enters the Study 03 paper as found in the analysis of results; its effect on misuses is never stated as null (replication package `HISTORY.md:693-697`).

## 4. Key evidence (E6 arm 4, 162 traces, avare excluded; E5b v13_A, 60 traces)

**Mapper replica:** reproduces 130,199 / 130,199 aligned calls.

**Copied answers mapping to the intended widget** (same-entry assumption: the model would copy the same list entry). Source: `evidence/coordinate_counterfactual{,_e5b}.out` §2.

| Scenario | E6 (53,352) | E5b (4,106) |
|---|---|---|
| S0 as run | 70.8 % | 69.9 % |
| S1 display base, old bands | 83.6 % | 85.3 % |
| S3 display base, bands re-derived | 87.2 % | 89.4 % |
| **S4 = S3 + visible-centre rule (this change)** | **87.1 %** | **89.4 %** |

- **"Mapped" includes dead_pair-refused answers.** As run, 18,837 of the 37,780 E6 hits were refused by the ban. Check what share of the gain survives the ban: the "live intended hits" lines.
- **Dialogs and popups (narrow):** 21.0 % → 97.4 %.
- **S4 vs S3:** −25 hits. 24 of them are off-screen nodes reached by snap, an action the dispatcher drops; 1 is edge-cut. 227 copies were of off-screen entries (128 of them already INVALID).
- **Residual misses under S4:** 6,874.
  - 5,066 click on a scroll-only entry;
  - 576 click on a long-click-only widget;
  - 227 off-screen entry;
  - 22 type_text on a non-input entry;
  - 771 containment picks a smaller child;
  - 159 other overlapping widget;
  - 53 status-bar band.
- **Bottom band:** it rejected 4,879 non-copied answers (3.7 %). Under the change, 2,995 match, 1,865 tap off-tree, and 19 (y ≥ 1000) stay rejected.
- **History `@(0,0)`:** 24.2 % of E6 prompts (31,466 / 130,266), all class-less `click`. In E5b, 22.5 % (2,157 / 9,586). Source: `evidence/prompt_zero_scan_{e6,e5b}.out`.
- **List composition:**
  - by bounds, 25.3 % of lines repeat a widget; node identity collapses 24.9 %;
  - 4,633 lines are distinct nodes with identical bounds; 17,865 are alias-sampled duplicates of a Name;
  - 23,080 lines (2.1 %) are off-screen nodes and 58,765 (5.3 %) are edge-cut.
  - Source: `evidence/dup_identity_e6.out`.

**Evidence scripts** (all read raw traces in the replication package and cannot be regenerated from this repo):

- `coordinate_counterfactual.py`, now with S4;
- `curation_analysis.py`;
- `prompt_zero_scan.py`;
- `dup_identity.py`, which imports `curation_analysis` and `coordinate_counterfactual`;
- `audit_rerun.out`.

## 5. History of this work

- **2026-10-03:** the change was opened (commit `0b2db6e2`).
- **2026-10-05, earlier session:** investigation, an adversarial fix of 15 defects, D10 added at the author's request, and an index-tool suggestion retracted.
- **2026-10-05, consistency review (previous session).** Findings, all applied:
  - **B1:** a missing `exploration` delta (the ring-buffer table had `normX/normY` and no `menu`).
  - **M1:** the unresolved-node `@(0,0)`. Tasks said "keep existing omission" that does not exist.
  - **M2:** INV-RTR-22 said "before any pixel is computed" while `toPixels` runs.
  - **M3:** INV-PRM-03 and unscoped "Widget List Generation" vs D10.
  - **M4:** the v17 SCREEN ratio mixed units.
  - **M5:** node identity vs the bounds-based gate.
  - **Minors:** v17 history position; Capabilities; task 9.2 hand-carry list; test field reads; stale band comments; INV-RTR-14 → INV-RTR-13 for repair; 163 vs 162 traces; scroll-only population figures; the 925 explanation; avare times; "same day's"; history `@(0,0)` gate.
  - New evidence: `prompt_zero_scan.py` and `dup_identity.py`.
- **2026-10-05, D11:** the author asked why off-screen-but-resolved nodes were not handled. The previous session had wrongly classed that as "curation". D11 was approved and applied: the counterfactual gained S4, `dup_identity.py` gained visibility counts, and all artifacts were updated.
- **State now:** `openspec validate --strict` passes. Uncommitted (`git status`):
  - `M` on `proposal.md`, `design.md`, `tasks.md`, and `specs/{llm-infrastructure,llm-prompt,llm-routing}/spec.md`;
  - new `specs/run-spec/`, `specs/exploration/` and `evidence/`.

  The untracked `docs/20260927_prompt_*opaque_screen*.md`, `docs/analise_claude_fable5 (Cópia).md` and the `docs/20261005_prompt_*` files are not part of the change. Do not touch them.

## 6. Settled decisions — do not re-open

- **No LLM action selection by list index or option list.** Discarded 2026-07-29 (`rvsec/rv-android/docs/20260724_relatorio_calibracao_aperv.md` §[rev2b]; the D-table at `:593`).
- **No scroll tool for the LLM.** Excluded since gh6 (`archive/2026-03-17-gh6-aperv-llm-integration/design.md:155`; `docs/analise_prompts_sota.md:198,213,540`). Rejected "for now" on 2026-07-29 (`rv-android/docs/20260729_propostas_melhorias_e3.md:32,45`). No document measures LLM scroll over-use.
- **List curation is open and NOT in this change.** That covers hiding scroll-only widgets and hiding off-screen widgets.
- **D10 is in:** one line per widget and no ordinal. The author was emphatic.
- **D11 is in:** visible-part centre, and no coordinate for wholly off-screen widgets.
- **No flag to reproduce the old base (D6).** E6 is reproduced with jar `e93dea86`.
- **Top band = status-bar fraction 0.035; bottom band key retired.**
- **The change is not scheduled for implementation.** Future studies only.

## 7. What to check

### Part A — consistency (artifacts vs artifacts, code, evidence, main specs)

1. **Leftovers of the pre-D11 design.** Look for any text that still says:
   - the list shows the clamped whole centre;
   - `y=1857 → 999`;
   - history entries carry `centerX/centerY`;
   - `6,849` / `5,793` / `85 %` residuals;
   - landscape `49 %` as this change's figure.

   Also look for any place that contradicts "wholly off-screen → no coordinate" (scenarios, Error Handling, Risks, Data Flow, API, tasks 2.x/3.x/6.3/8.2, proposal What Changes / Not changed / Impact).
2. **Traceability.** Every D1–D11 has a requirement, scenario or invariant, a task and a test row. Every task traces to a requirement. The proposal's Capabilities list matches the deltas (now including `exploration`).
3. **Numbers.** Recompute from `evidence/*.out`:
   - S4 87.1 % = 46,478 / 53,352;
   - the 25 = 24 + 1;
   - 227 = 103 + 83 + 17 + 24;
   - 6,874 = sum of its causes;
   - 5,891 = 5,066 + 576 + 227 + 22;
   - 23,080 / 58,765 (= 55,288 + 3,477);
   - 2,157 / 9,586;
   - 274,830 + 4,633 = 279,463;
   - 17,865 = 292,695 − 274,830.
4. **Scenario arithmetic.** Use the formulas `(int)(p*1000.0/d)` clamped to [0,999], the inverse `(int)(q/1000.0*d)` clamped to [0,d−1], and the visible centre `(l+r)>>1` of the intersection. Cases:
   - `[0,1740][1080,1974]` → `@(500,984)`;
   - `[0,1800][1080,1900]` → no `@(`;
   - `(540,178)` → `(500,99)`;
   - `(821,1090)` → `(760,607)`;
   - LLM tap `[600,900][601,901]` → `(555,501)`;
   - `(540,538)` → `(500,299)`;
   - v17 `Recent: click@(500,99), back`;
   - `[TESTED-3x]`;
   - `1/2 elements tested`.

   Also re-verify INV-LLM-13 exhaustively and the round-trip claim "inside the visible part for visible part ≥ 4 px".
5. **Code references.** Every `file:line` cited in `design.md` and `tasks.md` exists at HEAD and says what is claimed. That includes the new ones:
   - `ApePromptBuilder.java:398,438,634,646,713,726`;
   - `MonkeySourceApe.java:299-333, 368-419, 375-380`;
   - `CoordinateMapper.java:53-54, 128-129, 160, 274`;
   - `LlmEngine.java:230`;
   - `Config.java:186-193`;
   - `LlmTapAction.java:59,63,67`;
   - `StatefulAgent.java:1804-1817`;
   - the test lines in tasks 3.6 and 4.5.
6. **OpenSpec delta rules.**
   - Each MODIFIED block reproduces the full main-spec block and keeps every scenario title (openspec 1.14). Diff each block against `openspec/specs/<cap>/spec.md`, including the new `exploration` block.
   - `openspec archive` syncs requirements only. Task 9.2 must list every invariant, Purpose and Data Contract to hand-carry:
     - INV-PRM-06;
     - INV-PRM-03 rescope;
     - INV-LLM-13;
     - INV-RTR-21/22;
     - INV-RTR-14 supersession;
     - llm-prompt Purpose `:3` and Data Contracts;
     - llm-infrastructure Purpose and Data Contracts.
7. **Tasks completeness.**
   - Could an implementer follow the tasks and still ship the defect, or ship `@(0,0)`?
   - Do the gates in 6.3 and 8.2 fail on a correct implementation, or pass on a wrong one?
   - Are all call sites, field reads and pass-throughs listed?
   - Does `Rect` work in the JVM tests? `CoordinateMapperMappingTest` uses `new Rect`. Does `Rect.intersect` behave there, or is it a stub? **Check it.**

### Part B — adversarial review of the proposal (does it help, does it work, is it right?)

Challenge each of these with code and data. Report what holds and what does not.

1. **Is the gain real or an artifact of the metric?**
   - The headline 70.8 % → 87.1 % rests on the **same-entry assumption**, and counts dead_pair-refused answers as hits. How much survives as *live* hits ("ALL, live intended hits" lines)?
   - Copies are 41 % of answers. What is the expected effect on all LLM decisions, and on exploration outcomes (new states)? Can any of that be estimated offline, or only on device? Say so honestly.
   - The rv-android report's copy-vs-visual figure (14.89 % vs 31.61 %) was measured **with the defect active**. Does the change's argument depend on it?
2. **Behavioural shift.** With a correct list, will the model copy more or less? Could fixing the base change the copy/visual mix or the dead-pair dynamics (more correct copies → more strikes → more bans)? Is the dead-pair ban (k = 5) calibrated on defective-base data (`CoordinateMapper.java:36-41`, measured on cal_a1)? Flag the consequence; do not redesign the ban.
3. **Bottom band removal (D7).**
   - 1,865 formerly rejected answers become off-tree taps near the bottom edge. Are those likely useful or noise?
   - Is 0.035 right on other AVDs?
   - Does any path still produce y ≥ 1794 (landscape, the UiAutomation fallback, a root-node fallback in `deviceDimensions`)?
4. **D8 out-of-space refusal.** Only 19 E6 answers. Is it worth a new code path? Does `back` with coordinates really bypass it? Does `type_text` labelled `boundary` muddy telemetry?
5. **D10 one line per widget.**
   - Node identity vs alias sampling: `State.resolveAction` randomly picks one alias per action, so click and long-click on one Name can resolve to different nodes. Is the resulting list stable across steps?
   - Does grouping change what `hasInputField` or the type_text offer sees? It should not, because it reads `actions`. Verify.
   - Does the `elements tested` label change, a new prompt text, have any downside?
6. **D11 visible centre.**
   - The dispatcher clips to root ∩ display; the list clips to the display only. Find real cases where they differ: dialogs, keyboard-resized windows, popups whose child extends beyond the window. Can the evidence measure it?
   - Is "no coordinate" for an off-screen widget better than dropping the line or keeping a clamped coordinate, given the model can still name it visually?
   - The 24 lost "hits" are snaps to nodes the dispatcher drops. Confirm in `MonkeySourceApe` that a node action whose node has no visible intersection is really dropped, and that `isValid` does not already exclude them.
   - Does storing `Rect bounds` in history (a copy) leak memory, or keep tree references? See CLAUDE.md's OOM note.
7. **Unresolved-node omission.** It changes no measured prompt. Is the added complexity justified, and is the test meaningful?
8. **What could get worse?** Prompt length, token cost, comparability, the fine-tuning line (`laya`, on hold), offline analyses that align lines 1:1 with actions, the plan digest. Are all of them recorded in Impact and Risks?
9. **Verification plan realism.** Task 8.1/8.2 needs a device and the campaign server (vLLM v0.29.0, `Qwen/Qwen3-VL-4B-Instruct-FP8`). Are its gates checkable from the trace this jar writes: is node identity recoverable, are visible bounds printed? Would a 2–5 min smoke detect a regression in the copy hit rate, or is it underpowered? Propose the minimum convincing check.

## 8. Related files

- **Change:** `openspec/changes/llm-coordinate-single-base/{proposal.md,design.md,tasks.md}`, `specs/{llm-prompt,llm-routing,llm-infrastructure,run-spec,exploration}/spec.md`, `evidence/`.
- **Main specs:** `openspec/specs/{llm-prompt,llm-routing,llm-infrastructure,run-spec,exploration,event-sink}/spec.md`; principles P1–P4 in `openspec/config.yaml:9-13`.
- **Code (read only):**
  - `src/main/java/com/android/commands/monkey/ape/llm/{ApePromptBuilder,CoordinateNormalizer,CoordinateMapper,LlmEngine,ScreenshotStep,ScreenshotCapture,ImageProcessor,LlmClient,ToolCallParser}.java`;
  - `ape/agent/StatefulAgent.java` (`recordActionHistory` `:1796-1864`, `validateAllNewActions` `:1554`);
  - `ape/model/{State,ModelAction,LlmTapAction}.java` (`State.resolveAction` `:435-448`);
  - `ape/tree/GUITree.java` (`pickNodes` `:164-175`);
  - `ape/utils/Config.java`;
  - `ape/runtime/{KeyOwnership,Feature,RunSpec}.java`;
  - `MonkeySourceApe.java` (`getVisibleBounds` `:299-333`, `generateClickEventAt` `:368-419`, llm_tap `:936-941`).
- **Tests:** `src/test/java/com/android/commands/monkey/ape/llm/*`, `oracle/ScriptedLlm.java:188-215`, `runtime/KeyOwnershipTotalityTest.java`, `runtime/RunSpecAbortTest.java`.
- **Historical design:** `openspec/changes/archive/2026-03-17-gh6-aperv-llm-integration/design.md` (`:155`, `:277`, `:294`), `archive/2026-07-23-llm-native-toolcall-repair/` (INV-RTR-14), `archive/2026-07-23-llm-toolcall-parse-recovery/` (INV-RTR-13), `docs/analise_prompts_sota.md`.
- **Study 03:**
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/experiments/E6-campaign/settings-audit.md` §4 and `settings-audit/coordinate_base.py`;
  - `HISTORY.md:671-701`;
  - raw traces `data/raw/e6-campaign/e6_0?/e6_0?/<apk>/*__aperv:e6_mop_on_llm_90.trace`;
  - E5b traces `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/data/results/e5bm1_*/`.
- **rv-android decisions:** `rvsec/rv-android/docs/20260724_relatorio_calibracao_aperv.md`, `docs/20260729_propostas_melhorias_e3.md`.
- **Thesis skeleton (read only, never edit):** `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/doutorado-tese/docs/estudo-03/escrita/esqueleto.md` (`:430-444`, `:622-627`).
- **Previous prompt:** `docs/20261005_prompt_llm_coordinate_single_base_consistency_review.md`.

## 9. Commands

```bash
cd /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape
git branch --show-current            # must be master
git status --short
git diff -- openspec/changes/llm-coordinate-single-base
openspec validate llm-coordinate-single-base --strict
openspec show llm-coordinate-single-base --json --deltas-only
# diff a MODIFIED block against the main spec, e.g.:
sed -n '/### Requirement: StatefulAgent — Action History Ring Buffer/,/### Requirement: SataAgent — LLM New-State Hook/p' openspec/specs/exploration/spec.md
# rerun evidence (read-only over the replication package; write outputs to the SCRATCHPAD and diff
# against the committed .out files; always -B so no __pycache__ lands in evidence/):
E=openspec/changes/llm-coordinate-single-base/evidence
python3 -B $E/coordinate_counterfactual.py      > <scratchpad>/cf_e6.out
python3 -B $E/coordinate_counterfactual.py e5b  > <scratchpad>/cf_e5b.out
python3 -B $E/curation_analysis.py              > <scratchpad>/cur_e6.out
python3 -B $E/prompt_zero_scan.py               > <scratchpad>/pz_e6.out
python3 -B $E/prompt_zero_scan.py e5b           > <scratchpad>/pz_e5b.out
python3 -B $E/dup_identity.py                   > <scratchpad>/dup_e6.out
```

Read each script's header before running it. A full E6 pass takes a few minutes with a multiprocessing pool.

## 10. Learnings and pitfalls

- **E6 raw data was overwritten.** On 2026-10-05, between 13:01 and 13:14, container `e6_07` (`phtcosta/rvandroid:0.9.4`) rewrote the aperv files of `com.ds.avare_404` in E6 raw data; HISTORY.md:701 records the restart. The arm-4 trace now has 0 LLM answers. The evidence scripts exclude it **only under `/e6-campaign/`**.
  - A naive substring exclusion also drops E5b's avare. The previous session briefly reported wrong E5b numbers (2,149 / 9,432 instead of 2,157 / 9,586) because of it.
  - `audit_rerun.out` reads 163 traces, since the audit script has no exclusion; avare contributes no answer.
- **Display per call.** Use the per-call display (`coordinate_counterfactual.display_of`) rather than a fixed 1080 × 1794. A fixed portrait display gave 23,355 off-screen lines instead of the correct 23,080.
- **The code renders `@(0,0)` for an unresolved node at all three list sites** (`normX = 0` defaults, always appended). The main spec said otherwise since gh6 and no test caught it. Do not trust spec scenarios as descriptions of the code.
- **`validateAllNewActions`** re-resolves every action of the state each step, and `pickNodes` returns ≥1 node or throws. So unresolved listed actions are practically absent: `nonode` = 0 in E6 and E5b.
- **`State.resolveAction`** picks one alias node at random per action. Click and long-click on one Name can resolve to different nodes.
- **`MonkeySourceApe`** taps node actions at the centre of node ∩ root ∩ display and drops them when that is empty (INV-EXPL-19). The previous session first mislabelled "off-screen node" as curation; it is a coordinate rule. Ask "what point does the jar actually tap?" before proposing any prompt coordinate.
- **`uiautomator dump` clips nodes to `Display.getSize`.** The jar's tree is not clipped. The dump has no `inputType`/`hint`.
- **The prompt's element list is text only.** Nothing reads list lines back. The mapper uses `state.getActions()` and resolved nodes; `hasInputField` reads `actions`.
- **The model almost never answers `long_click`** (once in 130k calls).
- **`Config.llmBoundary*` fields are not read at runtime.** The effective default comes from `KeyOwnership.defaultOf` (`KeyOwnership.java:216`). No test compares `Feature.own` neutral values with defaults.
- **openspec 1.14 MODIFIED blocks must keep every main-spec scenario title.** Retired behaviour keeps the title with a rewritten body.
- **`openspec archive` syncs requirements only.** Invariants, Purpose and Data Contracts are hand-carried (task 9.2).
- **Subagent errors seen.** Snap tolerance 150 vs 50; "lower bound 1.3 %" vs 1.2 %; the 85 % residual share needing the 128 invalid; and a scroll-only outcome figure attributed to the wrong population. Recompute before stating.
- **Before proposing any action-space redesign,** search rv-android docs for prior author decisions.
