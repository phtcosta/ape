## MODIFIED Requirements

### Requirement: MOP Marker Annotation

When `Feature.MOP` is in the plan, the MOP prompt contribution (`MopPromptContribution.lineMarker`, "Prompt Built From Group Contributions") SHALL mark each element-list line with the MOP verdict of the **line's actions** (design D22). In the shared line formatter (`ape_current`, `ape_reasoning`, `compact_v1`), a line has one action, the one it renders. In `v13` and `v17`, a line's actions are all the target-requiring actions of its widget ("Element List of the v13 and v17 Variants", design D10). When `Feature.MOP` is not in the plan, the contribution is not mounted and no line carries a marker (INV-PRM-04); `ApePromptBuilder` does not test for MOP data, it renders what the mounted contributions return.

For each of the line's actions that has a resolved node, the verdict SHALL come from `MopData.lookup(activity, node, event)` (design D17). `event` is `MopScorer.eventTypeOf(action)`. The lookup is applied to the same containment nodes the scoring pass uses: the node itself, up to two ancestors, and its descendants up to two levels down. The best result wins: the strongest flag under `flag`, and the smallest unretired distance under `distance`. The lookup reads the node's handler stamp for the event first, if `handlers` lists the stamp's class. Otherwise it reads the id key `(activity, shortId, event)`, with the per-event fallback to the aggregate flags (INV-MOP-14). The short resource id is `MopData.extractShortId(GUITreeNode.getResourceID())`: the substring after `":id/"`, or the empty string. An action with no resolved node contributes nothing.

The verdict depends on the run's `ape.mopScoring` mode (design D18):

| Line's actions, under the active mode | Marker |
|---|---|
| At least one action is **direct** and has a **positive MOP weight**. Under `flag`: the lookup gives a direct flag for the action's event and `ape.mopWeightDirect > 0`. Under `distance`: the lookup has an unretired target at `d = 0` and the ladder's level-1 weight (the first element of `ape.mopWeights`) is positive. | `[DM]` |
| Otherwise, at least one action has a **positive MOP weight**: the weight `MopScorer.score` gives it under the active mode is positive (design D18), before the tie split. | `[M]` |
| Otherwise. This covers: lookup source `NONE`; a flag of `none` for the action's event; a stamped handler listed as reaching nothing; every target beyond the ladder's cut (`d > c`, c the length of `ape.mopWeights`), at a level whose ladder weight is 0, or retired; no resolved node. | (no marker) |

A line therefore carries a marker if and only if the score treats one of its actions as MOP; an arm that zeroes the MOP weights shows no marker. The marker follows the undivided weight (`mopWeight`), not the split boost. (rv-android's `mop_off_llm_off` zeroes them but runs without the LLM, so it has no prompt; no current arm zeroes the weights with the LLM on.) The two never disagree on the reason, because they use the same lookup and the same containment. Under `distance`, a target retires after `ape.mopRetireAfter` first interactions (design D19). A retired target stops counting toward the marker, so a line whose only targets have retired loses its marker from the next prompt on. Under `flag`, nothing retires.

The marker SHALL NOT query `activityHasMop`. A MOP-bearing activity does not mark any line by itself. This matches the score, which has had no activity-level fallback since mop-discriminative-boost.

Until this change, the requirement said the marker queried `mopData.getWidget(activity, shortResourceId)` and `mopData.activityHasMop(activity)`. The code never called `activityHasMop`. `buildMopMarker` (`ApePromptBuilder.java:456-469`) read the **aggregate** flags of the node's own id key, which are the OR over all events (`MopData.java:374-375`). It used no event, no containment and no stamp. The score reads the per-event flag with containment (`MopWidgetPass.mopBoostWithContainment`). Review R9 (rv-android `docs/20261006_revisao_analise_plano_guia_mop.md`) lists three cases where they disagreed:
- a container or child boosted through containment showed no marker;
- a widget direct only for long-click showed `[DM]` on its click line, while the click got the transitive weight or nothing;
- an event flagged `none` still showed the marker of another event, while the boost was 0.

With D10's lines joining several actions, and with per-event distances and per-node stamps, a rule tied to one node cannot hold. Even under `flag`, the new rule adds and removes markers in the `v13` prompt beyond Part A's changes.

The vocabulary (`[DM]`, `[M]`) is unchanged. The system-message sentences that cite it are themselves part of the MOP contribution, so a prompt carries the vocabulary's explanation exactly when it can carry a marker ("System Message", "System Message of the v13 Variant").

Note: "Monitored operations" refers to operations being monitored by runtime verification specifications. The specification sets vary by experiment — they may be cryptographic API misuse specs (JCA), generic protocol specs (e.g., Iterator hasNext/next), or other RV specification sets. The LLM does not need to know which specification set is active; it only needs to know that [DM]/[M] widgets lead to code paths under monitoring.

#### Scenario: Direct MOP widget

- **WHEN** `ape.mopScoring=flag`
- **AND** widget `btn_encrypt` has `mop: {"click": "direct"}` in MopData for activity `com.example.MainActivity`, and its node carries no stamp
- **AND** the line's action is a `MODEL_CLICK` on it
- **THEN** the action line SHALL contain `[DM]`

#### Scenario: Transitive MOP widget

- **WHEN** `ape.mopScoring=flag`
- **AND** widget `btn_settings` has `mop: {"click": "transitive"}` in MopData, and its node carries no stamp
- **AND** the line's action is a `MODEL_CLICK` on it
- **THEN** the action line SHALL contain `[M]`

#### Scenario: widget flagged only through containment is marked

- **WHEN** `ape.mopScoring=flag`
- **AND** a `MODEL_CLICK` targets a `TextView` whose resource id `row_label` is not in MopData
- **AND** that node's parent has resource id `item_row`, with `mop: {"click": "direct"}`
- **THEN** the scoring pass gives the action `mopWeightDirect` through containment
- **AND** the `TextView`'s line SHALL contain `[DM]` (at `e93dea86` it had no marker)

#### Scenario: long-click-only widget flagged only for click is not marked

- **WHEN** `ape.mopScoring=flag`
- **AND** widget `btn_delete` has `mop: {"click": "direct", "longClick": "none"}`, and no containment node is flagged
- **AND** the widget's only action is a `MODEL_LONG_CLICK`
- **THEN** its line SHALL carry no marker (at `e93dea86` it showed `[DM]` from the aggregate flag)
- **AND** the action's MOP weight SHALL be 0

#### Scenario: an event flagged none takes no marker from another event

- **WHEN** `ape.mopScoring=flag`, the variant is `ape_current`, and no containment node is flagged
- **AND** widget `btn_a` has `mop: {"click": "none", "longClick": "direct"}`, and its line renders a `MODEL_CLICK`
- **AND** widget `btn_b` has `mop: {"click": "transitive", "longClick": "direct"}`, and its line renders a `MODEL_CLICK`
- **THEN** `btn_a`'s line SHALL carry no marker
- **AND** `btn_b`'s line SHALL carry `[M]`, not `[DM]`

#### Scenario: a stamped handler that reaches nothing removes the marker

- **WHEN** `ape.mopScoring=flag`
- **AND** a node's resource id `btn_save` has `mop: {"click": "direct"}`
- **AND** the node's `rvsec.click` stamp is `com.example.SaveListener`, and `handlers` lists that class with `mop: {"click": "none"}`
- **AND** the line's action is a `MODEL_CLICK` on that node, and no containment node is flagged
- **THEN** the lookup source SHALL be `STAMP`
- **AND** the line SHALL carry no marker, even though the id key is flagged direct

#### Scenario: a stamp naming an unlisted class falls back to the id key

- **WHEN** `ape.mopScoring=flag`
- **AND** a node's `rvsec.click` stamp is `androidx.appcompat.app.AppCompatViewInflater$DeclaredOnClickListener`, which `handlers` does not list
- **AND** its resource id `btn_encrypt` has `mop: {"click": "direct"}`
- **THEN** the line of its `MODEL_CLICK` SHALL contain `[DM]`

#### Scenario: distance mode marks by the nearest unretired target

- **WHEN** `ape.mopScoring=distance`, no target is retired, and no containment node carries a distance
- **AND** three `MODEL_CLICK` lines have click lookups with `dist` `[[4, 0]]`, `[[2, 1]]` and `[[7, 5]]`
- **THEN** the first line SHALL carry `[DM]`
- **AND** the second SHALL carry `[M]`, even though `d = 1` takes the same weight as `d = 0` (level 1 of `ape.mopWeights`)
- **AND** the third SHALL carry no marker

#### Scenario: a line whose only target retired loses its marker

- **WHEN** `ape.mopScoring=distance`
- **AND** a line's only action has the click lookup `dist: [[4, 0]]`, with no other pair through containment
- **AND** the line carries `[DM]` while target 4 is unretired
- **AND** target 4 then retires, because its counter reaches `ape.mopRetireAfter` (design D19)
- **THEN** the next prompt SHALL render that line with no marker

#### Scenario: a collapsed click and long-click line is direct if either action is

- **WHEN** `ape.mopScoring=flag` and variant `v13` builds the prompt
- **AND** an EditText has a `MODEL_CLICK` and a `MODEL_LONG_CLICK` that collapse into one line (design D10)
- **AND** its id key has `mop: {"click": "none", "longClick": "direct"}`
- **THEN** its single line SHALL carry `[DM]`
- **AND** with `mop: {"click": "transitive", "longClick": "none"}` instead, the line SHALL carry `[M]`

#### Scenario: no marker in a cell without MOP

- **WHEN** cell `as_llm` builds a prompt for a screen whose `btn_encrypt` has `mop: {"click": "direct"}` in the loaded artifact
- **THEN** no line SHALL carry `[DM]` or `[M]`, because `Feature.MOP` is not in the plan and the MOP contribution is not mounted
