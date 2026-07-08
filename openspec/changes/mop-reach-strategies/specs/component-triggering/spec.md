# component-triggering — delta: mop-reach-strategies

## Purpose

Order the stagnation activity launcher's candidate selection MOP-first (E-mín). The launcher itself — the `Stagnation-Triggered Activity Launch` requirement and its `selectTriggerCandidate` seam — is introduced by the `activity-frontier` change; this delta only refines **which eligible candidate is picked first**, gated by a new flag. It targets the 14.2% of missed MOP screens that are WTG-orphan but exported, which the launcher can reach directly. Extension to receivers/services (E-ext) is a NON-GOAL.

> Depends on `activity-frontier` (archived first): this delta is additive to that change's launcher and does not restate it.

## ADDED Requirements

### Requirement: MOP-First Ordering of Stagnation-Launch Candidates

`Config.triggerMopFirst` (declared in `Config.java`, loaded via `ape.triggerMopFirst`, default `false`, registered in the `apePureMode` RV-flag registry — INV-ARCH-06 of `scoring-pipeline` — and forced to `false` when `apePureMode=true`) SHALL control the order in which the stagnation activity launcher's candidate selection (`selectTriggerCandidate`, from `activity-frontier`'s `Stagnation-Triggered Activity Launch` requirement) considers eligible candidates. Eligibility is unchanged (exported ∧ `permission == null` ∧ not main ∧ unvisited at fire time — INV-CT-06); this requirement changes only ordering, never the eligible set.

- When `Config.triggerMopFirst == false` (default), candidate selection SHALL be exactly `activity-frontier`'s single-pass round-robin over the manifest activity list — behaviour byte-identical to that change.
- When `Config.triggerMopFirst == true`, candidate selection SHALL consider eligible candidates whose `ComponentInfo.reachesTarget == true` **before** eligible candidates whose `reachesTarget == false`. The ordering SHALL be a stable two-pass over the eligible set (MOP-reachable group first, then the rest), each group walked in the existing round-robin order, so selection is deterministic and reproducible under a fixed seed with no dependence on set/iteration order.

The ordering SHALL NOT launch receivers, services, or providers (E-ext is out of scope) and SHALL NOT alter the `EVENT_TRIGGER_ACTIVITY` step semantics, the `decision_source=Component` attribution, the once-per-episode gate, or the `ComponentName` package derivation (all owned by `activity-frontier`).

- **INV-CT-09**: With `Config.triggerMopFirst == true`, when at least one eligible candidate has `reachesTarget == true`, the launched activity SHALL be a `reachesTarget == true` candidate; a `reachesTarget == false` eligible candidate SHALL be launched only when no eligible `reachesTarget == true` candidate exists. With `Config.triggerMopFirst == false`, selection order SHALL be identical to `activity-frontier`'s round-robin.

#### Scenario: MOP-reachable candidate preferred
- **WHEN** `ape.triggerMopFirst=true` and the eligible set contains `com.x.Plain` (`reachesTarget=false`) and `com.x.Crypto` (`reachesTarget=true`)
- **THEN** the launcher SHALL select `com.x.Crypto`

#### Scenario: falls back to non-MOP when no MOP candidate eligible
- **WHEN** `ape.triggerMopFirst=true` and every eligible candidate has `reachesTarget=false`
- **THEN** the launcher SHALL select an eligible `reachesTarget=false` candidate in round-robin order (no candidate is skipped for lacking MOP)

#### Scenario: flag off preserves round-robin
- **WHEN** `ape.triggerMopFirst=false`
- **THEN** candidate selection SHALL be identical to `activity-frontier`'s round-robin, ignoring `reachesTarget`

#### Scenario: eligibility unchanged
- **WHEN** `ape.triggerMopFirst=true` and a `reachesTarget=true` activity is non-exported (ineligible)
- **THEN** it SHALL NOT be launched (ordering never widens eligibility)
