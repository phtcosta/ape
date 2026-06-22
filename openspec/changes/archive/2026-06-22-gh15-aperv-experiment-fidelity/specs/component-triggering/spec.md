## Purpose

Component triggering exercises non-GUI Android components (Services, BroadcastReceivers) that UI-only exploration cannot reach. Its activation probability, `Config.componentPercentage`, currently derives its default from `Config.mopDataPath` — so enabling MOP guidance silently also enables triggering. This delta removes that coupling so an experiment that toggles the MOP scorer does not simultaneously toggle component triggering. The two `aperv` arms `sata` and `sata_mop` must differ in exactly one variable (the scorer), not two.

## Invariants

- **INV-CT-01**: Component triggering SHALL only fire when `Config.componentPercentage > 0` AND `MopData.hasComponents()` is true. When `componentPercentage` is `0.0` (the default, regardless of whether `mopDataPath` is set), behavior SHALL be identical to APE-RV without component triggering.

## MODIFIED Requirements

### Requirement: Config — componentPercentage

`Config.componentPercentage` (double) SHALL control the probability of component triggering per step. The default SHALL be `0.0` regardless of `Config.mopDataPath`. Component triggering is enabled only by an explicit `ape.componentPercentage` setting in `ape.properties`.

This decouples component triggering from MOP scoring: setting `ape.mopDataPath` (which enables the MOP scorer) SHALL NOT change `componentPercentage`. An experiment arm that wants both MOP scoring and triggering SHALL set `ape.componentPercentage` explicitly.

Anchor: `Config.java:169-170`. Sole consumer: `SataAgent.java:351-354`.

#### Scenario: Default with mopDataPath set
- **WHEN** `ape.properties` sets `ape.mopDataPath` but not `ape.componentPercentage`
- **THEN** `Config.componentPercentage` SHALL default to `0.0` (triggering disabled)
- **AND** no component triggering SHALL occur

#### Scenario: Default without mopDataPath
- **WHEN** `ape.properties` does not set `ape.mopDataPath` and does not set `ape.componentPercentage`
- **THEN** `Config.componentPercentage` SHALL default to `0.0` (disabled)

#### Scenario: Explicit override enables triggering
- **WHEN** `ape.properties` sets `ape.componentPercentage=0.10`
- **THEN** `Config.componentPercentage` SHALL be `0.10` regardless of `mopDataPath`
- **AND** triggering SHALL fire with probability `0.10` per step (subject to INV-CT-01)
