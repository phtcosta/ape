# Session goal — implement `telemetry-proof-llm-efficacy` (ape repo, Java)

Implement the OpenSpec change **`telemetry-proof-llm-efficacy`** (GitHub issue **#16 in the `ape`
repo**) in the sibling Java project `ape`. It is the Java half of a two-repository deliverable;
the Python half (`gh90-e3-decisive-run-setup` in `rv-android`) is committed and blocked waiting on
this jar. Read this whole prompt before touching anything.

## Hard constraints

- **Do NOT run any `rv-*` skill** (`/rv-test-run`, `/rv-verify`, `/rv-qa-lint-fix`,
  `/rv-code-reviewer`, `/rv-docs-sync`, …). The author decided on 2026-07-31 not to run them.
  Run `mvn test` / `mvn package` directly. Note `tasks.md` task 17.3 still names `/rv-test-run`
  and `/rv-code-reviewer` — mark it with that decision recorded, rather than executing the skills.
- **Never start, stop or manage an Android emulator, in any context.** rv-platform owns the whole
  lifecycle. No `emulator`, no `adb emu kill`, ever. All smoke runs go through rv-platform on the
  rv-android side; this repo builds the jar only. `ape/scripts/run_emulator.sh` exists and is
  documented in `ape/CLAUDE.md` — do not use it.
- **Do not touch `rvsec-gator`** (standing rule; gross error only).
- **Never create a git branch unless explicitly asked.** `ape` is on `master`, `rv-android` on
  `modules`. Do not branch even though `master` is the default branch — this rule overrides the
  usual "branch before committing on the default branch" default.
- **Never add `Co-Authored-By` or any co-author trailer.** The user is the sole author.
- **OpenSpec artifacts are edited ONLY through the skills** (`openspec-apply-change`,
  `openspec-update-change`, `openspec-verify-change`) — never with `Write`/`Edit`. Marking
  `tasks.md` checkboxes is part of `openspec-apply-change` and is allowed. Implementation code is
  written normally.
- **Read-only on the corpus.** Never modify `rv-android/experimento-cal/iter0/` — it is the
  fixture set (3.5 GB, 892 traces).
- Throwaway scripts go in the session scratchpad, never in the repo.
- Code and comments in English. Portuguese only when the user asks, with correct accents.
- P1–P4 apply: simplicity, narrative docs explaining *why*, no backward-compat shims,
  current-state comments only (no migration history, no promotional language).
- **There is no deadline.** The 2026-07-31/08-01 window was cancelled by the author. The artifacts
  have already been cleaned of it — do not reintroduce schedule-based descoping, and do **not**
  treat group 16 (A3) as the "designated cut". A3 is to be implemented.

## Paths

```
APE     = /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape
CHANGE  = $APE/openspec/changes/telemetry-proof-llm-efficacy
SRC     = $APE/src/main/java/com/android/commands/monkey
TEST    = $APE/src/test/java
RVA     = /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android
CORPUS  = $RVA/experimento-cal/iter0/results     (read-only fixtures)
```

`ape` is its **own git repo** with its own root (not nested in `rvsec/`). Bash cwd **does persist**
between calls in this environment — always `cd` to an absolute path in the same call rather than
assuming the cwd. Note the Java package path is
`src/main/java/com/android/commands/monkey/ape/...` — **not** `src/main/java/ape/...`.

## State

### `ape` repo — 0 of ~51 tasks implemented, artifact revisions UNCOMMITTED

```
branch: master
HEAD:   1385ccf  plan(openspec): fix cross-artifact inconsistencies in telemetry-proof-llm-efficacy (refs #16)
```

Uncommitted (all from the 2026-07-31 artifact revision — no code written yet):

```
 M openspec/changes/telemetry-proof-llm-efficacy/design.md          (+35/-?)
 M openspec/changes/telemetry-proof-llm-efficacy/proposal.md
 M openspec/changes/telemetry-proof-llm-efficacy/specs/llm-routing/spec.md
 M openspec/changes/telemetry-proof-llm-efficacy/tasks.md
 M .project                                                        (pre-existing noise, not ours)
?? docs/2026*.md                                                   (pre-existing untracked notes)
```

`openspec validate "telemetry-proof-llm-efficacy" --strict` → **valid**.
`target/ape-rv.jar` is dated Jul 23 15:51 — built long before this change; **it does not contain B1**.

**Commit cadence for the `ape` repo was never settled — ask the author before the first commit.**
The repo's own convention from its log: `plan(openspec): …` for artifact-only commits,
`feat(<area>): …` / `fix(<area>): …` for code, `refs #16` during work and `closes #16` at the end.

### `rv-android` sister repo — committed, blocked on this jar

```
branch: modules
commit: 0f41d5dd  feat(gh90): decisive-run arms, offline enrichment, provenance and the two
                  analysis utilities (refs #90)   — 10 files, +2996/-67
```

gh90 groups 1–5 are done, tested (159 pass) and committed. What is still open there and **why it
needs this jar**:

| gh90 task | Needs |
|---|---|
| 6.1 → 3.3 | the git sha of an `ape-rv.jar` containing B1; then add `llm_snap_tolerance_px=150` **and** `expected_jar_git_sha="<sha>"` to the `mop_on_llm_70` arm |
| group 6 | real smoke through rv-platform; gate 6.3 checks `[APE-BUILD]` git_sha against the declared sha |
| group 7 | the RQ-C1 power probe (also blocked on a separate author decision about task 7.6's feedback path) |
| 8.6 | issue #90 acceptance criteria, then archive |

`expected_jar_git_sha` must stay OUT of `APERV_PROPERTY_MAPPING` (a test asserts it). The guard
`_snap_tolerance_offenders` in `rv-android/modules/aperv-tool/tests/test_aperv_tool.py` enforces
the 150 ↔ sha pairing in both directions and requires exactly 150.

## Author decisions — settled 2026-07-31, already folded into the artifacts

All three of `design.md`'s former "Open Questions" were ratified. **Do not re-litigate these.**

1. **D1 / B1 threshold → `k=5` uniform, WITH input-capable widgets exempt.**
   This *amended* what the artifacts previously said ("uniform, no exemption list"). §0 of the
   decision ledger exempted `EditText` by name and the author kept that carve-out, generalized to
   the four input-capable classes, while taking k=5 for everything else. Rationale recorded in the
   spec: no finite k reproduces an exemption — a threshold protects for k executions, not forever.
2. **D9 / A10 → ratified as written.** Teardown reordering (dump hoisted ahead of `saveGraph`);
   the shutdown hook stays withdrawn; the device-side sink stays out of scope.
3. **D11 / O4 → ratified as written, stays in scope.**

`design.md` "Open Questions" now reads **none outstanding**.

### What the D1 amendment means concretely (already normative in the spec)

- A `matched` pair whose resolved target is input-capable **never becomes dead, at any strike count**.
- Realized by **not recording the strike** — the pair never enters the map. There is no filter at
  the ban check and no state kept for exempt pairs. This is the cheaper site and the directly
  testable invariant.
- The exemption **keys on the widget, not the event type**, so it covers the text-entry action that
  group 4 (B6(iv) fixTextEdit) converts a click into.
- It applies to the **`matched` half only**. This is a property of the evidence, not an oversight:
  an `llm_tap` carries `matched_class=none` in 1,033 of 1,033 corpus occurrences, so no widget
  class is knowable there.
- **27.5% is now an upper bound**, not the realized refusal share. Exempting a class can only
  remove pairs from the refused block, so the sub-30% ceiling holds a fortiori. **The exact
  post-exemption share was NOT re-derived from the corpus** — say so plainly; do not restate 27.5%
  as if it still held. The decisive run's `dead_pair` summary counter reports the realized value.
- New task **1.3b** carries this; task **1.5**'s test list was corrected (EditText now asserted
  *exempt*; Switch/Spinner/Button still die at k=5).

## FINDING that changes group 1's implementation — report, do not silently work around

**`INPUT_CLASS_NAMES` is already duplicated in the tree.** Two byte-identical four-class copies,
both `private`, verified this session:

| Location | Members |
|---|---|
| `SRC/ape/llm/LlmRouter.java:44-49`, `isInputClass` at `:700` | `android.widget.EditText`, `android.widget.AutoCompleteTextView`, `android.widget.SearchView`, `androidx.appcompat.widget.SearchView` |
| `SRC/ape/llm/ApePromptBuilder.java:31-36`, `isInputClass` at `:784` | identical four |

Severity: **smell**, pre-existing, not a defect today (the copies agree). It matters because this
change adds three more consumers of "input-capable" — B1's exemption (1.3b), B6(iii)'s
`hasInputField` (`ApePromptBuilder.java:774`), and B6(iv)'s fixTextEdit — and the spec text now
says the ban "SHALL NOT carry a second, independently-maintained list", which is already false of
the tree as it stands.

**Plan agreed with the author: consolidate to ONE canonical definition as part of group 1**, rather
than adding a third copy. Flagged rather than done silently because it also touches
`ApePromptBuilder`, which group 5 (N1) edits. Existing consumers to keep working:
`ApePromptBuilder.java:406` (`isInputField`), `:779` (inside `hasInputField`),
`LlmRouter.java:608` and `:657` (the `type_text` filters in both mapping passes).

## Task groups — all open

Full text in `$CHANGE/tasks.md`. Order is dependency/risk, **not** a deadline order.

| # | Item | Scope |
|---|---|---|
| 1 | **B1** dead-pair ban | `LlmRouter` map + check, `StatefulAgent` outcome feedback, k=5 + exemption, tests |
| 2 | **B6(i)** ActionType filter | containment + euclidean loops require `MODEL_CLICK` for `click` |
| 3 | **B6(iii)** per-request tool schema | `SglangClient.chat(messages, tools)`; delete constructor-era `setTools` (P3) |
| 4 | **B6(iv)** fixTextEdit | click on input widget → text-entry action, harness-generated text |
| 5 | **N1** identifiers in prompt element lines | `safeGetDisplayText` renders `id=<shortId>` fallback |
| 6 | **B4** edge-based snapping | point-to-rectangle distance replaces centre distance |
| 7 | **A4** `activity_has_mop` | on `[APE-STEP]` and `[APE-OUTCOME]` |
| 8 | **A5** `pick_channel` | `ModelAction` provenance + all pick sites |
| 9 | **A6** un-alias `wtgBoost` | new `mopFrontierBoost` field + `DecisionSource.MopFrontier` |
| 10 | **A8** escape newlines | `ModelAction.resolvedInfo` + prompt display text |
| 11 | **A7** screenshot-failure telemetry | `[APE-LLM-ERROR] cause=screenshot` + `ScreenshotCapture` stage seam |
| 12 | **A10** hoist coverage dump | overridable teardown step before `saveGraph` |
| 13 | **O4** `patched=0\|1` | `GUITreeNode.patchedClickable` set in `patchGUITree` |
| 14 | **B7(i)** stagnation trigger | `>=` + per-episode re-arm flag |
| 15 | **K10** docs fix | `CLAUDE.md` `activityTriggerEnabled` default → `true` |
| 16 | **A3** per-step counterfactual | `cf_action`/`cf_changed` at 4 pick sites, RNG-isolated |
| 17 | Verification | build, `mvn test`, smoke gates (a)–(f) |

## Code sites

**Verified against the worktree this session** (trust these):

```
LlmRouter.java        797 lines
  :44-49    INPUT_CLASS_NAMES (duplicate #1)
  :120      client.setTools(buildToolsSchema())    ← B6(iii) removes this
  :150-167  buildToolsSchema()
  :320-332  null-screenshot branch, intentionally silent at :328-329   ← A7
  :601-622  bounds containment pass; :611 preferLongClick               ← B6(i)
  :651-677  euclidean fallback; :659-676 snap loop; tolerance at ~:666-669 ← B4, B6(i)
  :700      isInputClass()
  ~:690     off-tree MODEL_LLM_TAP synthesis (LlmTapAction)
  printSummary() emits the [APE-RV] LLM Summary line ← dead_pair=<N> goes here
ApePromptBuilder.java 858 lines
  :31-36    INPUT_CLASS_NAMES (duplicate #2)
  :133      hasInputField(actions) gates type_text in the system message
  :406      isInputField usage
  :774      hasInputField(); :784 isInputClass()
  :830-839  safeGetDisplayText() — text → contentDesc → ""            ← N1
StatefulAgent.java   1775 lines
SataAgent.java       1664 lines
ModelAction.java      270 lines
SglangClient.java     472 lines
```

**Cited by `design.md` as verified against the worktree, but NOT re-verified this session — confirm
before editing:**

```
StatefulAgent.java  :1396-1404 [APE-STEP] emission      :1007-1010 [APE-OUTCOME]
                    :1694-1704 tearDown chain           :1699 safeStep("saveGraph", …)
                    :1334-1345 graphStableCounter reset :1732 "Save graph data to /sdcard/sata-…"
SataAgent.java      :283-292 tearDown dump call (:290-291 the dump itself)
                    :252-276 attributeByLargestBoost    :436 stagnation predicate
                    :575-587 short_circuit_unvisited    :1544-1552 short_circuit_0step
                    :607 roulette_greedy                :1558 roulette_early
                    :460-483 launcher                   :422-453 LLM hooks
                    :815-827 mopPickKey
ModelAction.java    :136-144 resolvedInfo / resolvedNodes
MopFrontierPass.java:79   (WtgPass.java:60, FrontierPass.java:75 write the same field today)
GUITreeBuilder.java :262-304 patchGUITree; :286 child made clickable; :295 parent demoted; :289 index
ScreenshotCapture.java :40-57
LlmRouter.java      :224-228 shouldRouteStagnation
UICoverageTracker.java :240-255 widgetId
MopData.java        :975-977 activityHasMop (O(1))
Config.java         :90 patchGUITree(true)  :165 activityTriggerEnabled(true)  :223 llmSnapTolerancePx(50)
CLAUDE.md           ~:128 K10 target line
```

## Traps already paid for — reuse, do not re-derive

- **`GUITreeNode` has NO `toXPath()`** (`grep -c toXPath GUITreeNode.java` → 0). The only
  `toXPath()` is on `Name` (`Name.java:22`, `AbstractName.java:55`). B1's `matched` key is
  therefore **abstraction-level**, not per-node: one ban withdraws the action from every node the
  `Name` resolves to, and **16.3% of targeted steps resolve more than one node** (23,441/144,174).
  A ban count is a count of abstract pairs and must never be reported as a widget count.
- **Never anchor a ban on a list index.** That is the autopsy-catalogued bug class in this codebase.
- **The k sweep was originally run on the wrong key.** It keyed *both* result types by
  `(state, pixel)`, but only `llm_tap` (15.9% of the stream) uses that; the other 84.1% is
  `matched` with the looser `Name`-level key. Under the shipped keys k=3 refuses 37.6% (breaks the
  30% ceiling) and k=5 refuses 27.5%. Denominator is **6,500** decisions (84 `cal_a1` traces = 80
  main at timeout=300 + 4 smoke at timeout=90); main-only is 6,440 → 27.8%.
- **A shutdown hook cannot recover the lost coverage dumps.** The sink, not the signal, is the
  binding constraint: `Logger` writes only to `System.out`, the trace is the host's `adb` stdout
  opened by `aperv-tool`, and the harness SIGKILLs `adb` and closes the file. Confirmed: the
  `.logcat` sibling of a lossy run has **0** lines matching `APE`. Ordering is what works —
  330 of 338 lossy runs are cut inside `saveGraph`, three steps before the dump; hoisting recovers
  333/338 (98.5%). The remaining 5 never reached teardown at all.
- **A10's hoisted step lands third in the chain, not first.** The chain is
  `llmSummary → superTearDown → saveGraph → …`. The property that matters — and the only one
  INV-COV-10, the unit test and smoke gate (e) assert — is "before the model serialization", not
  "first".
- **`OutOfMemoryError` is an `Error`** and escapes `catch (Exception)` in `ScreenshotCapture`.
  Do **not** claim it is conflated with the FLAG_SECURE/reflection/permission nulls.
- **Partial coverage dumps are expected and valid**, not corrupt runs. Hoisting moves the dump; it
  does not make it atomic.
- **The `[APE-RV]` dump/telemetry lines lack the `*** INFO *** ` infix** that other lines carry, and
  every logcat line is prefixed `[APE] `. Never anchor a log pattern at `^`. `UICOV` is a prefix of
  `UICOV-ACT`, so test the ACT tag first.
- **The violation tag is `RVSEC`, not `RVSEC:`** — logcat pads it to eight chars (`V RVSEC   :`).
  `RVSEC-COV:` is a different, ~100k-lines-per-file stream and must never be admitted.
- **O4's `patched` bit records node provenance, not action causality.** For `MODEL_CLICK` it does
  imply the action would not exist without the patch; for scroll/long-click on the same node it
  does not, so offline analysis must condition on `MODEL_CLICK`. `Config.patchGUITree` is absent
  from `APERV_PROPERTY_MAPPING` and from the `apePureMode` kill-switch registry, so **no arm can
  toggle it** — O4 characterizes an invariant of the substrate, it does not make it a variable.
- **A3 must not perturb the seeded RNG stream** (INV-EXPL-14). `java.util.Random` does not expose
  its seed, so the "clone" is realized as common-random-number replay: the factual roulette records
  the draw it consumed, and the counterfactual replays it as a *fraction of total weight*
  (`f = r / totalWeight`, pick at `f × cfTotalWeight`) — zero additional draws. The dedicated
  seed-identity test (task 16.4) is the merge gate.
- **A6 changes `decision_source` distributions mid-experiment-series.** Intended — the old WTG
  counts were conflated. Offline analysis keys the label change to the jar provenance stamp.

## Findings from the rv-android side worth carrying

1. **The corpus contains ZERO truncated dumps.** The artifacts state "3 of the 462 runs that dump
   today are truncated mid-`UICOV-ACT`"; over the recorded iter0 corpus every dump line terminates
   in its final field and every dumping trace has ≥1 `UICOV-ACT` line. The rv-android parser still
   classifies `partial` (tested with a synthetic truncated tail), but the "3 of 462" figure does
   not reproduce.
2. **"Reaching a MOP screen fires the monitor" does not hold in general.** Over 549 aligned runs
   and 2,533 distinct violations, **70.8% first appear before the first exploration step** — they
   fire at launch with no agent decision involved. Of the 740 that first appear during exploration,
   only 21.1% fire within 2 s of arriving at the current Activity (median delay 10.2 s, p90 49.8 s).
   This is the mechanism behind `mop_unique` saturation and the evidence base for the deferred N5
   decision. Caveat: the join brackets violations in time, it does not causally attribute them.

## Commands

```bash
# build (Java 11+, Maven, Android SDK with d8 in PATH, build-tools 28+)
cd $APE && mvn compile
cd $APE && mvn package          # → target/ape-rv.jar
cd $APE && mvn test             # baseline: 145 tests, 14 skipped (Android runtime)

# install the jar into the rv-android aperv-tool module
cd $APE && mvn install -Drvsec_home=/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec

# live LLM tests (only if an SGLang server is up)
cd $APE && SGLANG_URL=http://localhost:30000/v1 mvn test -Dtest=SglangLiveTest

# change state
cd $APE && openspec status --change "telemetry-proof-llm-efficacy" --json
cd $APE && openspec validate "telemetry-proof-llm-efficacy" --strict

# the git sha to declare in rv-android task 3.3, once the B1 jar is built
cd $APE && git rev-parse HEAD
```

Note: `openspec` in this repo prints a harmless `Invalid 'references' field in config` warning
before its JSON output — ignore it, the JSON that follows is valid.

Existing test conventions live in `$TEST`; relevant neighbours to model new tests on:
`LlmRouterTest.java`, `LlmRouterMappingTest.java`, `LlmRouterTelemetryTest.java`,
`ApePromptBuilderTest.java`, `ModelActionTest.java`, `MopFrontierPassTest.java`,
`LlmCircuitBreakerTest.java`, `GUITreeBuilderEditTextTest.java`, `FormCompletionTest.java`.

## Next steps, in order

1. **Ask the author about `ape` commit cadence** and whether to commit the uncommitted artifact
   revision first (suggested: `plan(openspec): ratify D1/D9/D11 and drop the deadline framing (refs #16)`).
2. **Group 1 (B1)** — including the `INPUT_CLASS_NAMES` consolidation described above, the k=5 +
   input-capable-exemption death rule (tasks 1.3 and 1.3b), the `StatefulAgent` outcome feedback,
   and the task-1.5 test set. Verify the `StatefulAgent`/`SataAgent` line numbers before editing.
3. **Groups 2–15**, in order. Groups 3 and 4 both touch the input-capable predicate consolidated in
   group 1; group 5 also edits `ApePromptBuilder`.
4. **Group 16 (A3)** — last by dependency, not optional. Task 16.4's seed-identity test is its gate.
5. **Group 17** — `mvn package`, `mvn test`, `openspec validate`. Smoke gates (a)–(f) run on the
   rv-android side via rv-platform; gate (a) needs the MOP-off arm that gh90 group 1 already defines
   (committed in `0f41d5dd`), so it is now reachable.
6. **Hand the jar's git sha back to rv-android** to unblock gh90 tasks 6.1 → 3.3 and group 6.

## Context documents (read as needed; never edit change artifacts outside the skills)

```
$CHANGE/proposal.md · design.md · tasks.md · specs/{action-selection,llm-infrastructure,
        llm-prompt,llm-routing,scoring-pipeline,ui-coverage}/spec.md
$APE/CLAUDE.md                                            build, architecture, Config flag reference
$RVA/docs/20260729_propostas_melhorias_e3.md              decision ledger; §0 is the source of record
$RVA/docs/20260731_verificacao_analise_percepcao.md        adversarial verification that drove the plan
$RVA/docs/20260730_preregistro_corrida_decisiva.md         pre-registration, revised, NOT frozen
$RVA/docs/20260730_compose_gator_substrato_estatico.md     Compose saturation, §4-§5.2
$RVA/openspec/changes/gh90-e3-decisive-run-setup/          the sister change (committed)
```

## Rigor rules

- Every factual claim cites `file:line`; every quantitative claim shows the command that produced it.
- State severity honestly: contradiction / gap / smell / cosmetic. Do not inflate.
- When the artifacts cannot settle a question, say so and state what would close it.
- Report a clean result as a result.
- Do not propose redesign; if a decision looks wrong, report it as a question with its evidence.
- Standing rules: **vetoes are conditional on the pair (mechanism, use)** — report the pair; and
  **do not re-propose what is already decided** — check §0 of the ledger first.
- Report progress after each task group.
