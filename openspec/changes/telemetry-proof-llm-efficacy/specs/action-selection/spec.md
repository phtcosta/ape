## MODIFIED Requirements

### Requirement: Per-action decision-source telemetry

Emission of the `[APE-STEP]` line is gated by `Config.stepTelemetryEnabled` (declared by the `scoring-pipeline` capability; default `true`). When `stepTelemetryEnabled` is `true` (default), `StatefulAgent.resolveNewAction()` SHALL emit one structured `[APE-STEP]` log line for the action returned by `selectNewActionNonnull()`, after the action is finalized and before it is executed. The line SHALL attribute the action to a `decision_source` and include the per-mechanism boosts that applied. When `stepTelemetryEnabled` is `false` (the `ape_pure` arm), no `[APE-STEP]` line SHALL be emitted for any action; the `decisionSource` provenance field on `ModelAction` SHALL still be populated for internal use, and no other selection behavior SHALL change. INV-SEL-04 ("exactly one `[APE-STEP]` line per finally-selected action") describes the default (`stepTelemetryEnabled=true`) configuration.

To attribute LLM and other early-return paths that bypass `logActionSelected`, `ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection. The field SHALL be populated on every return path: SATA strategies, the three LLM hooks (new-state, stagnation, random), the budget-exhausted trivial path, and the null path — regardless of `stepTelemetryEnabled`.

For SATA-chain selections, the `decisionSource` SHALL be set by a boost-attribution rule scoped to the **selection sub-paths that actually consume priority**, not to whole `SataEventType` branches. Attribution applies ONLY when the action is a `ModelAction` chosen by: (a) a priority roulette — `State.randomlyPickAction` in the epsilon-greedy random branch (`SataAgent.java:607`) or `RandomHelper.randomPickWithPriority` over the EARLY_STAGE unvisited candidates (`SataAgent.java:1558`) — or (b) a boost-based deterministic pick — the MOP short-circuit (`selectUnvisitedMopTarget`, `SataAgent.java:575-587`) or the EARLY_STAGE MOP preference (`pickBestMopTarget` in `findGreedyActionForward`, `SataAgent.java:1544-1552`). When attribution applies AND the action carries at least one boost greater than 0 among `getMopBoost()`/`getMopFrontierBoost()`/`getWtgBoost()`/`getMenuBoost()`/`getCoverageBoost()`/`getFormBoost()`, `decisionSource` SHALL be set to the mechanism holding the largest boost. Ties on the largest boost SHALL be resolved by the fixed precedence `MOP > MopFrontier > WTG > Menu > Form > Coverage` (`MopFrontier` sits next to `MOP` because it is a MOP mechanism — the de-aliasing exists precisely so it cannot launder as WTG). In all other cases `decisionSource` SHALL remain `SATA` — in particular on the sub-paths that select for reasons other than priority even though they live inside `EARLY_STAGE`/`EPSILON_GREEDY`: graph-navigation and shortest-path picks, the Back-/Menu-unvisited short-circuits, and `greedyPickLeastVisited` (minimum visit count; priority is only its tie-break).

This attribution reports which mechanism most contributed to the chosen action on a sub-path that actually consumed priority. It SHALL NOT be interpreted as a counterfactual claim that the boost changed the selection outcome (that claim belongs to the counterfactual fields — "Per-step counterfactual attribution").

The `decision_source` enum SHALL be: `SATA`, `MOP`, `MopFrontier`, `Coverage`, `LLM`, `Fuzz`, `Menu`, `WTG`, `Component`, `Budget`, `Form`. A pick driven by the MOP-frontier boost (`getMopFrontierBoost()` largest) SHALL be attributed `MopFrontier`; a pick driven by the form-completion boost (`getFormBoost()` largest) SHALL be attributed `Form`.

When emitted, the `[APE-STEP]` line SHALL also carry a `clock=<epochMillis>` field (device wall clock at emission), enabling offline temporal joins between the `.trace` and externally collected artifacts without any APE↔logcat coupling — APE SHALL NOT read from or write to logcat.

When emitted, the `[APE-STEP]` line SHALL carry a `step=<N>` field, where `<N>` is the agent exploration timestamp (`getTimestamp()`) at selection time. The timestamp is incremented exactly once per agent step at update-wrapper entry, so `step` is constant across a single selection, and — because at most one `[APE-STEP]` line is emitted per agent step (INV-SEL-04) — `step` values are unique within a run. `step` is the per-step join key consumed by the `[APE-OUTCOME]` attribution line (`Per-Step Decision Outcome Attribution`, scoring-pipeline capability). This is the agent timestamp, not the independent `Graph` timestamp.

**Boost fields.** The per-mechanism boost fields on the line SHALL include `mop_frontier=<N>` (the action's `mopFrontierBoost`, scoring-pipeline capability) alongside the existing `mop=`, `wtg=`, `coverage=`, `menu=`, `form=`; `wtg=` reports the WTG-family boosts only (WTG-MOP + generic frontier) and SHALL NOT include the MOP-frontier contribution.

**MOP-screen bit (`activity_has_mop`).** When emitted, the `[APE-STEP]` line SHALL carry `activity_has_mop=0|1`: `1` when `MopData` is non-null AND `MopData.activityHasMop(<current activity>)` is true, else `0` (including whenever `MopData` is null). This closes the third link of the evidential chain — whether the step happened on a MOP screen — using the O(1) pre-computed set lookup already specified by `mop-guidance`; today the bit is not in the trace and MOP-screen presence must be reconstructed offline.

**Selection channel (`pick_channel`).** When emitted, the `[APE-STEP]` line SHALL carry exactly one `pick_channel=<value>` field naming the selection channel that picked the action, from the fixed enum:

| Value | Channel |
|-------|---------|
| `short_circuit_unvisited` | the unvisited-MOP-target short-circuit in `selectNewActionEpsilonGreedyRandomly` (`SataAgent.java:575-587`) |
| `short_circuit_0step` | the 0-step MOP probe in `findGreedyActionForward` (`SataAgent.java:1544-1552`) |
| `roulette_greedy` | the epsilon-greedy priority roulette (`State.randomlyPickAction`, `SataAgent.java:607`) |
| `roulette_early` | the EARLY_STAGE unvisited-candidates roulette (`RandomHelper.randomPickWithPriority`, `SataAgent.java:1558`) |
| `launcher` | the stagnation activity launcher (`SataAgent.java:460-483`) |
| `llm` | any of the three LLM hooks (`SataAgent.java:422-453`) |
| `buffer` | `selectNewActionFromBuffer` |
| `sata_other` | every other selection path (least-visited scan, graph navigation, back-tracking, budget/trivial, null-recovery) |

Measured motivation: the short-circuit channel yields 15.1% new states while the MOP-boosted roulette yields 1.4% — aggregating them under `decision_source=MOP` mixes mechanism with noise, and today the separation is only recoverable by regex over free-text log lines. `pick_channel` is provenance carried on the `ModelAction` (like `decisionSource`), set at the pick site, so early-return paths are covered.

**Clickability-patch provenance (`patched`).** When emitted, the `[APE-STEP]` line SHALL carry `patched=0|1` for actions with a resolved target: `1` when the target node's clickability was written by `GUITreeBuilder.patchGUITree` rather than read from the `AccessibilityNodeInfo`, else `0`. Actions with no target (`MODEL_BACK`, `MODEL_MENU`, `MODEL_LLM_TAP`) SHALL omit the field, consistently with the other target-derived fields.

The patch is on by default (`Config.patchGUITree`), is not exposed through any experimental arm's configuration, and rewrites both `GUITreeNode.clickable` and the DOM mirror while leaving the `AccessibilityNodeInfo` untouched; `ActionPatchNamer.generatePatch` emits a property only when true and prints the already-patched value. The result is that **no recorded artifact today distinguishes a click on a natively clickable widget from a click on one the patch fabricated** — offline reconstruction can only estimate the share via a `(class, resource-id)` *type* proxy, which yields a point estimate of 36.0% at type level. That is not an interval: 19.4% rests on a different denominator, and no lower bound above 0 is derivable from this corpus, so `[19.4%; 36.0%]` SHALL NOT be reported as a range containing the true value. The estimate is further conditional on the patch log being complete (64 of 800 calibration runs carry 6,561 `MODEL_CLICK` steps and no patch log line at all). The bit makes the quantity exact and removes both the proxy and the condition.

Interpretation rules, part of this contract: `patched=1` records the **node's** provenance, not the action's causality. For `MODEL_CLICK` it does imply the action would not exist without the patch (the action is derived from `clickable || checkable`); for scroll and long-click actions on the same node it does not, so offline analysis SHALL condition on `MODEL_CLICK` before reading it causally. And where the action's `Name` resolves to more than one node, the bit describes the node the line prints — exact for that node, a sample for its siblings.

**Line well-formedness.** Every `[APE-STEP]` line SHALL occupy exactly one physical line: widget-derived text interpolated into the line (via `ModelAction.resolvedInfo`, which embeds the resolved node's text) SHALL have `\n` and `\r` flattened to spaces before emission. Measured defect: 752 of 166,359 `[APE-STEP]` lines were broken by embedded newlines in widget text, with non-uniform distribution across arms (32–116 per arm) — a 0.45% bias on every `decision_source` count.

#### Scenario: SATA-selected action attributed
- **WHEN** `stepTelemetryEnabled` is `true` and `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy strategy with all boosts equal to 0
- **THEN** a single `[APE-STEP]` line SHALL be emitted with `decision_source=SATA`
- **AND** the line SHALL include `step=<N>`, `state`, `action`, `activity_has_mop`, `pick_channel`, and per-mechanism boosts including `mop_frontier=`

#### Scenario: MOP-boosted action from the EARLY_STAGE roulette attributed to MOP
- **WHEN** the EARLY_STAGE unvisited roulette (or the MOP preference probing it) picks a `ModelAction` whose boosts are `mop=500, mop_frontier=0, wtg=0, menu=0, coverage=0, form=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** when `stepTelemetryEnabled` is `true` the emitted `[APE-STEP]` line SHALL report `decision_source=MOP`

#### Scenario: MOP-frontier-driven pick attributed to MopFrontier, not WTG
- **WHEN** a roulette pick carries boosts `mop=0, mop_frontier=200, wtg=0, menu=0, coverage=100, form=0`
- **THEN** the action's `decisionSource` SHALL be `MopFrontier`
- **AND** the line SHALL report `mop_frontier=200 wtg=0`

#### Scenario: Tie precedence MOP>MopFrontier>WTG>Menu>Form>Coverage
- **WHEN** a roulette pick carries boosts `mop=300, mop_frontier=300, wtg=0, menu=0, coverage=0, form=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** when the tie is instead `mop_frontier=300, wtg=300` the `decisionSource` SHALL be `MopFrontier`

#### Scenario: Boosted action in a priority-blind branch stays SATA
- **WHEN** `logActionSelected(action, USE_BUFFER)` is called for a `ModelAction` whose boosts are `mop=500, wtg=0, menu=0, coverage=0`
- **THEN** the action's `decisionSource` SHALL be `SATA`
- **AND** the line SHALL carry `pick_channel=buffer`

#### Scenario: click on a patch-fabricated widget is marked
- **WHEN** `patchGUITree` sets `clickable=true` on a child that the `AccessibilityNodeInfo` reported as non-clickable, and a later step selects the `MODEL_CLICK` derived from it
- **THEN** the emitted `[APE-STEP]` line SHALL carry `patched=1`
- **AND** a `MODEL_CLICK` on a node whose clickability came from the `AccessibilityNodeInfo` SHALL carry `patched=0`

#### Scenario: targetless actions omit the patch bit
- **WHEN** the selected action is `MODEL_BACK`, `MODEL_MENU` or `MODEL_LLM_TAP`
- **THEN** the emitted `[APE-STEP]` line SHALL NOT carry a `patched` field

#### Scenario: [APE-STEP] carries the MOP-screen bit
- **WHEN** `stepTelemetryEnabled` is `true`, `MopData` is present, and the current activity is in the pre-computed MOP-activity set
- **THEN** the emitted `[APE-STEP]` line SHALL carry `activity_has_mop=1`
- **AND** on an activity outside the set it SHALL carry `activity_has_mop=0`

#### Scenario: MOP-off arm always reports activity_has_mop consistent with MopData
- **WHEN** a run executes with `MopData` null
- **THEN** every `[APE-STEP]` line SHALL carry `activity_has_mop=0`

#### Scenario: pick_channel discriminates short-circuit from roulette
- **WHEN** one step is selected by the unvisited-MOP short-circuit and a later step by the epsilon-greedy roulette
- **THEN** the first line SHALL carry `pick_channel=short_circuit_unvisited`
- **AND** the second SHALL carry `pick_channel=roulette_greedy`
- **AND** both MAY carry `decision_source=MOP` (channel and source are independent axes)

#### Scenario: LLM early-return attributed with its channel
- **WHEN** the new-state LLM hook returns a non-null action (bypassing `logActionSelected`)
- **THEN** that action's `decisionSource` SHALL be `LLM`
- **AND** when `stepTelemetryEnabled` is `true` exactly one `[APE-STEP]` line SHALL be emitted for it with `decision_source=LLM pick_channel=llm`

#### Scenario: widget text with a newline stays on one line
- **WHEN** the selected action's resolved node text is `"Sign\nIn"`
- **THEN** the emitted `[APE-STEP]` line SHALL contain `"Sign In"` and SHALL occupy exactly one physical line

#### Scenario: Every step is attributable when telemetry is enabled
- **WHEN** `stepTelemetryEnabled` is `true` and a run completes
- **THEN** every executed action SHALL have exactly one corresponding `[APE-STEP]` line
- **AND** every line SHALL carry exactly one `pick_channel` and exactly one `activity_has_mop` field

#### Scenario: No [APE-STEP] lines when telemetry is disabled
- **WHEN** `stepTelemetryEnabled` is `false` and a run completes
- **THEN** zero `[APE-STEP]` lines SHALL be emitted for the run
- **AND** each executed action's `decisionSource` provenance field SHALL still be populated
- **AND** no other selection behavior SHALL differ from the enabled case

## ADDED Requirements

### Requirement: Per-step counterfactual attribution

When `stepTelemetryEnabled` is `true` and the step's action was picked by one of the four MOP-sensitive channels (`short_circuit_unvisited`, `short_circuit_0step`, `roulette_greedy`, `roulette_early`), the `[APE-STEP]` line SHALL additionally carry `cf_action=<action>` and `cf_changed=0|1`: the action the same channel would have selected with `mopBoost` and `mopFrontierBoost` zeroed on every candidate (all other boosts and the candidate set unchanged), and whether that counterfactual pick differs from the factual one. `cf_action` SHALL use the same action rendering as the line's `action` field. Lines from other channels (LLM, launcher, buffer, `sata_other`) SHALL NOT carry the counterfactual fields — MOP boosts do not participate in those picks.

Channel semantics:
- The two short-circuits exist only because of MOP boosts: their counterfactual is the pick of the channel they short-circuit (the roulette/least-visited fall-through with MOP weights zeroed), and `cf_changed=1` whenever the short-circuit fired on a target the fall-through would not have picked.
- The two roulettes recompute the weighted pick over the same candidates with per-action priority reduced by that action's `mopBoost + mopFrontierBoost`.

**RNG stream isolation (hard constraint).** The counterfactual recomputation SHALL NOT advance or otherwise perturb the seeded RNG stream that drives selection (INV-EXPL-14 — the seeded run's action sequence must be bit-identical with the counterfactual computation on or off). The roulette counterfactual SHALL reuse the factual pick's recorded draw (as a fraction of total weight) rather than drawing again; the short-circuit counterfactuals are deterministic and draw nothing. A dedicated test SHALL assert sequence identity under a fixed seed with the counterfactual enabled and disabled — perturbing the stream would be a silent bias of the exact class the calibration autopsy catalogued.

**Honest caveat (interpretation rule, part of this contract):** the counterfactual is 1-step myopic. It establishes the *divergence point* — "did the MOP boost change THIS pick?" — not the cumulative trajectory effect, which only an arm-level contrast (MOP-off arm, rv-android side) can measure. Offline analysis SHALL NOT sum `cf_changed` into a claim about end-of-run coverage.

**Failure containment:** if the recomputation fails for any reason, the line SHALL carry `cf_changed=0` and selection SHALL be unaffected (the factual pick was already made before the counterfactual runs).

#### Scenario: MOP short-circuit divergence recorded
- **WHEN** the unvisited-MOP short-circuit selects action A (`mopBoost=500`) and the fall-through roulette with MOP weights zeroed would have picked action B
- **THEN** the `[APE-STEP]` line SHALL carry `pick_channel=short_circuit_unvisited cf_action=<B> cf_changed=1`

#### Scenario: roulette pick unchanged by MOP weights
- **WHEN** the epsilon-greedy roulette picks action C and the recomputation with `mopBoost`/`mopFrontierBoost` zeroed — replaying the same recorded draw fraction — also picks C
- **THEN** the line SHALL carry `cf_changed=0`
- **AND** `cf_action` SHALL equal the line's `action`

#### Scenario: MOP-off arm is counterfactually inert
- **WHEN** a run executes with all MOP weights zero (MOP-off arm)
- **THEN** every emitted counterfactual field SHALL be `cf_changed=0`
- **AND** any `cf_changed=1` in such a run SHALL be treated as a defect in the counterfactual implementation (smoke-gate invariant)

#### Scenario: seeded sequence identical with counterfactual on and off
- **WHEN** two runs execute with the same seed, APK, and configuration, one with the counterfactual computation enabled and one with it disabled
- **THEN** the sequence of selected actions SHALL be identical (the live RNG stream consumed exactly the same draws)

#### Scenario: non-MOP channels carry no counterfactual fields
- **WHEN** a step is picked by the LLM hook or the buffer
- **THEN** its `[APE-STEP]` line SHALL NOT contain `cf_action` or `cf_changed`

## Invariants

- **INV-SEL-05**: When `stepTelemetryEnabled` is `true`, every `[APE-STEP]` line SHALL carry exactly one `pick_channel` field whose value is a member of the fixed enum (`short_circuit_unvisited`, `short_circuit_0step`, `roulette_greedy`, `roulette_early`, `launcher`, `llm`, `buffer`, `sata_other`) — never a free-form string; the channel is provenance set at the pick site, covering every early-return path.
- **INV-SEL-06**: On every emitted `[APE-STEP]` line, `activity_has_mop` SHALL equal `MopData.activityHasMop(<current activity>)` when `MopData` is non-null, and `0` whenever `MopData` is null. The field is 0/1, never absent when the line is emitted.
- **INV-SEL-07**: Every `[APE-STEP]` line SHALL occupy exactly one physical line; no widget-derived text SHALL introduce an unescaped `\n`/`\r` into the line.
- **INV-SEL-08**: Counterfactual fields (`cf_action`, `cf_changed`) SHALL appear on exactly the lines whose `pick_channel` is one of the four MOP-sensitive channels, and on no others. In a run where every MOP weight is zero, every emitted `cf_changed` SHALL be `0`.
- **INV-SEL-09**: The counterfactual recomputation SHALL consume zero draws from the live seeded RNG stream: for a fixed seed, APK, and configuration, the selected-action sequence SHALL be identical with the counterfactual computation enabled or disabled.
- **INV-SEL-10**: Every emitted `[APE-STEP]` line for an action with a resolved target SHALL carry exactly one `patched=0|1` field reporting whether that target's clickability was written by `patchGUITree`; lines for targetless actions SHALL omit it. The bit SHALL be set at the patch sites and never inferred at emission time from post-patch attributes, which are indistinguishable from native ones by construction. `patched=1` records that the patch *wrote* the node's clickability, in either direction — it is set both where the patch grants clickability to a child and where it removes it from the parent — so a causal reading ("this action exists only because of the patch") holds for `MODEL_CLICK` and SHALL NOT be extended to other action types on a patched node.
