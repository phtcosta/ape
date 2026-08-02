# activity-budget Delta Specification

## Purpose

Delta for `rearch-02-runspec`: the budget gate's behavior is unchanged; only its flag-off framing is re-grounded. The pre-change text described the flag-off branch as "the `ape_pure` arm", which this re-architecture retires — the `apePureMode` Properties-overwrite kill-switch is deleted with its three string registries (V8), and purity becomes structural. `activityBudgetEnabled` remains a parity flag (`scoring-pipeline` capability) and is now the activation key of its `Feature`: with the feature absent from the resolved plan, the `ActivityBudgetTracker` is never constructed and no budget check exists to disable.

## MODIFIED Requirements

### Requirement: Budget Check in SATA Action Selection

The activity-budget mechanism SHALL be gated by `Config.activityBudgetEnabled` (declared by the `scoring-pipeline` capability; default `true`).

When `activityBudgetEnabled` is `true` (default), `StatefulAgent` SHALL instantiate the `ActivityBudgetTracker`, and `SataAgent.selectNewActionNonnull()` SHALL check `ActivityBudgetTracker.isBudgetExhausted()` for the current activity BEFORE the normal SATA priority chain. The budget is a **soft constraint** — it influences navigation but does not block exploration:

1. Try `selectNewActionForTrivialActivity()` — navigate to a different activity
2. If null: fall through to normal SATA chain

**Rationale**: Both hard fallbacks were tested and found harmful:
- EVENT_RESTART caused destructive restart loops (40+ restarts/run, -3.39pp method coverage)
- MODEL_BACK caused stuck loops (78 useless BACKs/run burning 67% of time, -1.10pp on 20 APKs)
- Fallthrough (no special action) was validated as correct: +3.67pp method, +6.01pp MOP on cryptoapp

The budget's value is in the trivial activity navigation — when available, it forces diversification. When not available, the normal SATA chain (with coverage boost, WTG boost, and greedy tiebreaker) handles exploration efficiently without forced navigation.

When `activityBudgetEnabled` is `false` (the feature absent from the resolved plan — `run-spec` INV-RUN-05), `StatefulAgent` SHALL NOT instantiate the `ActivityBudgetTracker`, and `SataAgent.selectNewActionNonnull()` SHALL NOT perform any budget check — selection proceeds directly to the normal SATA chain, reproducing upstream APE (which has no activity budget). No `recordIteration`/`isBudgetExhausted` call SHALL occur.

#### Scenario: Budget exhausted forces navigation via trivial activity (flag on)
- **WHEN** `Config.activityBudgetEnabled` is `true`, the current activity's budget is exhausted, and a path to a trivial activity exists
- **THEN** the agent SHALL navigate to the target activity

#### Scenario: Budget exhausted, no trivial activity — fallthrough (flag on)
- **WHEN** `Config.activityBudgetEnabled` is `true`, the current activity's budget is exhausted, and `selectNewActionForTrivialActivity()` returns null
- **THEN** the agent SHALL fall through to the normal SATA priority chain
- **AND** exploration continues normally with all priority boosts active

#### Scenario: No budget tracker or check when the flag is off
- **WHEN** `Config.activityBudgetEnabled` is `false`
- **THEN** `StatefulAgent` SHALL NOT instantiate an `ActivityBudgetTracker`
- **AND** `SataAgent.selectNewActionNonnull()` SHALL perform no budget check and proceed directly to the normal SATA chain
- **AND** no `recordIteration` or `isBudgetExhausted` call SHALL occur during the run
