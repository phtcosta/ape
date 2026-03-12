## MODIFIED Requirements

### Requirement: StatefulAgent — Priority-Based Action Selection

`StatefulAgent` and its subclasses use a `priority` integer field on each `ModelAction` to break ties and express preferences. Higher numeric priority means higher preference. The method `StatefulAgent.adjustActionsByGUITree()` is called after the base priority has been assigned and before the agent selects an action. This is the designated extension point where external components MAY call `ModelAction.setPriority(int)` to boost specific actions.

When `Config.mopDataPath` is non-null, `StatefulAgent` SHALL load `MopData` at construction time and apply MOP scoring in `adjustActionsByGUITree()` after the base priority loop. The MOP scoring pass SHALL only apply to actions where `action.requireTarget() == true` AND `action.isValid() == true`. The boost is additive: `action.setPriority(action.getPriority() + MopScorer.score(...))`. When `Config.mopDataPath` is null, the MOP scoring pass is skipped entirely and the method behaves identically to its pre-Phase-3 form.

The selection method `RandomHelper.randomPickWithPriority(List<ModelAction>)` MUST prefer actions with higher priority; actions with equal priority are selected uniformly at random among that priority tier.

#### Scenario: Higher priority action preferred
- **WHEN** `StatefulAgent` has two candidate actions `actionA` (priority=10) and `actionB` (priority=1)
- **THEN** `actionA` SHALL be selected with probability proportional to its priority weight under `randomPickWithPriority`

#### Scenario: MOP boost applied to direct-reachable widget
- **WHEN** `Config.mopDataPath` points to a valid JSON AND `actionA` targets widget `btn_encrypt` which has `directMop=true`
- **THEN** `actionA.getPriority()` after `adjustActionsByGUITree()` SHALL equal `basePriority + 500`

#### Scenario: MOP scoring skipped when mopDataPath is null
- **WHEN** `Config.mopDataPath` is `null`
- **THEN** `adjustActionsByGUITree()` SHALL complete without any calls to `MopScorer`
- **AND** all action priorities SHALL equal their base SATA-assigned values

#### Scenario: Non-target actions not boosted
- **WHEN** MOP data is loaded AND `actionB` is `MODEL_BACK` (`requireTarget() == false`)
- **THEN** `actionB.getPriority()` SHALL NOT be modified by the MOP scoring pass
