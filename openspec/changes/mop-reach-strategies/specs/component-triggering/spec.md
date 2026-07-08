# component-triggering — delta: mop-reach-strategies

## Purpose

Order the stagnation activity launcher's candidate selection MOP-first (E-mín). The launcher itself — the `Stagnation-Triggered Activity Launch` requirement and its `selectTriggerCandidate` seam — is introduced by the `activity-frontier` change; this delta only refines **which eligible candidate is picked first**, gated by a new flag. It targets the 14.2% of missed MOP screens that are WTG-orphan but exported, which the launcher can reach directly. Extension to receivers/services (E-ext) is a NON-GOAL.

The MOP-membership signal is the reachability-augmented `MopData.activityHasMop(className)` truth (built by `augmentActivitiesFromSources` — INV-MOP-27 — from widget-derived flags ∪ `components.activities[].reachesTarget` ∪ `reachability[]` activity-class-with-any-reaching-method), NOT the component-level `ComponentInfo.reachesTarget` field. The two disagree: the producer's `components.activities[].reachesTarget` false-negatives lambda-triggered activities (cryptoapp: all four activities report `components.reachesTarget=false`, yet `reachability[]` shows CipherActivity=2, MessageDigestActivity=1, CryptographyActivity=13 reaching methods). Keying E-mín on `activityHasMop` reconciles it with A′, FIX-2, and `MopFrontierPass` (which all consume the reachability side), so the launcher prefers activities that genuinely reach MOP even when the component-level flag is a false negative. Consuming the reliable side needs no GATOR re-run.

> Depends on `activity-frontier` (archived first): this delta is additive to that change's launcher and does not restate it.

## ADDED Requirements

### Requirement: MOP-First Ordering of Stagnation-Launch Candidates

`Config.triggerMopFirst` (declared in `Config.java`, loaded via `ape.triggerMopFirst`, default `false`, registered in the `apePureMode` RV-flag registry — INV-ARCH-06 of `scoring-pipeline` — and forced to `false` when `apePureMode=true`) SHALL control the order in which the stagnation activity launcher's candidate selection (`selectTriggerCandidate`, from `activity-frontier`'s `Stagnation-Triggered Activity Launch` requirement) considers eligible candidates. Eligibility is unchanged (exported ∧ `permission == null` ∧ not main ∧ unvisited at fire time — INV-CT-06); this requirement changes only ordering, never the eligible set.

A candidate is **MOP-reaching** iff `MopData.activityHasMop(candidate.className) == true` (the reachability-augmented set, INV-MOP-27). The component-level `ComponentInfo.reachesTarget` field SHALL NOT be used for this decision (it false-negatives lambda-triggered activities).

- When `Config.triggerMopFirst == false` (default), candidate selection SHALL be exactly `activity-frontier`'s single-pass round-robin over the manifest activity list — behaviour byte-identical to that change (MOP membership is not consulted).
- When `Config.triggerMopFirst == true`, candidate selection SHALL consider eligible MOP-reaching candidates **before** eligible non-MOP-reaching candidates. The ordering SHALL be a stable two-pass over the eligible set (MOP-reaching group first, then the rest), each group walked in the existing round-robin order, so selection is deterministic and reproducible under a fixed seed with no dependence on set/iteration order.

The ordering SHALL NOT launch receivers, services, or providers (E-ext is out of scope) and SHALL NOT alter the `EVENT_TRIGGER_ACTIVITY` step semantics, the `decision_source=Component` attribution, the once-per-episode gate, or the `ComponentName` package derivation (all owned by `activity-frontier`).

- **INV-CT-09**: With `Config.triggerMopFirst == true`, when at least one eligible candidate is MOP-reaching (`activityHasMop(className)`), the launched activity SHALL be a MOP-reaching candidate; a non-MOP-reaching eligible candidate SHALL be launched only when no eligible MOP-reaching candidate exists. With `Config.triggerMopFirst == false`, selection order SHALL be identical to `activity-frontier`'s round-robin, and `activityHasMop` SHALL NOT be consulted.

#### Scenario: MOP-reachable candidate preferred
- **WHEN** `ape.triggerMopFirst=true` and the eligible set contains `com.x.Plain` (`activityHasMop=false`) and `com.x.Crypto` (`activityHasMop=true`, e.g. reachable only via a lambda handler so `components.reachesTarget=false`)
- **THEN** the launcher SHALL select `com.x.Crypto`

#### Scenario: falls back to non-MOP when no MOP candidate eligible
- **WHEN** `ape.triggerMopFirst=true` and no eligible candidate is MOP-reaching (`activityHasMop=false` for all)
- **THEN** the launcher SHALL select an eligible candidate in round-robin order (no candidate is skipped for lacking MOP)

#### Scenario: flag off preserves round-robin
- **WHEN** `ape.triggerMopFirst=false`
- **THEN** candidate selection SHALL be identical to `activity-frontier`'s round-robin, ignoring MOP membership

#### Scenario: eligibility unchanged
- **WHEN** `ape.triggerMopFirst=true` and a MOP-reaching activity (`activityHasMop=true`) is non-exported (ineligible)
- **THEN** it SHALL NOT be launched (ordering never widens eligibility)
