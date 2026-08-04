# run-spec Delta Specification

## Purpose

Delta for `rearch-05-thin-python-arms`, over the one requirement stage 2 wrote in transitional terms.

`rearch-02-runspec` had to deploy the new resolver against an unchanged `tool.py`, and it recorded that constraint inside the requirement itself: the no-preset case was described as "the case for the entire current Python deployment, which this change does not touch", and a scenario named "zero Python changes verified" asserted that the campaign arms run with `APERV_PROPERTY_MAPPING`, the arm dicts and `_push_properties` untouched. That framing was accurate for stage 2 and is what makes it false now — the stage's rv-android counterpart `gh95-thin-python-arms` has rewritten `_push_properties` and re-expressed every surviving arm as `preset + overrides`, so a requirement that describes the Python side's pre-change shape describes something that no longer exists. The falsification is a fact about the tree, not a plan: verified in `modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py` on 2026-08-04.

What survives the edit is the behavior, which was never transitional: **when no preset is named, the plan is derived from the explicit keys and the jar's own defaults, then validated.** A bare standalone run with no properties file at all resolves exactly this way, and will keep doing so long after every campaign arm names a preset. What dies is the paragraph describing the deployment that used to depend on it.

This delta touches nothing else in the capability. The preset resolution requirement, the fail-fast validation classes, the `RUN_START` echo, and the run-identity requirements are all unchanged by this stage.

## MODIFIED Requirements

### Requirement: Explicit-Key Resolution When No Preset Is Named

When `ape.preset` is absent, the plan SHALL be derived from the explicit `ape.*` keys exactly as the jar interprets them — same defaults, same clamps — and then validated against the same rules a preset-resolved plan passes. There is one resolution path: the preset, when named, contributes a base key vector; its absence means the base vector is empty, not that a different resolver runs.

This case is not a compatibility affordance and does not depend on any particular caller. It is what a run with no properties file resolves to, and it is the reason a bare standalone invocation of the jar is a valid run: the plan is the jar's declared defaults, validated like any other.

#### Scenario: no preset resolves from explicit keys and jar defaults

- **WHEN** a properties file sets `ape.mopDataPath` and `ape.mopWeightDirect=500` and names no `ape.preset`
- **THEN** resolution SHALL succeed with `preset="explicit"`, the `MOP` feature active, and every unset key at its jar default
- **AND** the resulting plan SHALL pass exactly the validation a preset-resolved plan passes

#### Scenario: bare standalone run

- **WHEN** the jar is launched with no properties file at all
- **THEN** resolution SHALL succeed from the jar defaults alone
- **AND** the effective plan SHALL be echoed in `RUN_START` like any other run
