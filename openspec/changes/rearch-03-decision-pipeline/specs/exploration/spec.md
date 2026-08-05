## MODIFIED Requirements

### Requirement: StatefulAgent — LLM Router Integration

`StatefulAgent` SHALL integrate the LLM through the `RunContext`-owned LLM units and the decision pipeline's LLM stages — not through a router field of its own. The fields:

| Field | Owner after this change | Description |
|-------|------------------------|-------------|
| LLM units (`LlmClient`, `LlmEngine`, `ScreenshotStep`, `CoordinateMapper`, `LlmTelemetry`, `ApePromptBuilder`, `ToolCallParser`) | `RunContext` (constructed once at bootstrap when the plan carries the LLM feature) | replace the former `_llmRouter` reference; see `llm-routing` "LLM Unit Lifecycle and Ownership" |
| `_isNewState` | `StatefulAgent` (unchanged) | captured in `updateStateInternal()` **before** `markVisited()` (unchanged capture requirement) |
| `_actionHistory` (ring buffer, max 5) | `StatefulAgent` (unchanged) | last executed actions with results, fed to the prompt builder |
| `_lastState` / `_stateBeforeLast` | `StatefulAgent` (unchanged) | history for action-result determination |
| Stagnation single-shot flag | **`LlmStagnation` stage** (moved out of `StatefulAgent`) | see "SataAgent — LLM Stagnation Hook" |

When the plan does not carry the LLM feature, no LLM unit SHALL be constructed and no LLM stage SHALL be assembled (feature absent = stage absent — replaces the null-`_llmRouter` convention). The agent SHALL forward each visited `StateTransition` once to every assembled stage's `onStateTransition` hook, immediately after its own stability-counter bookkeeping in `onVisitStateTransition` — this is the sole reset channel for stage-owned episode state.

#### Scenario: LLM enabled via Config

- **WHEN** the resolved plan carries the LLM feature
- **THEN** `RunContext` SHALL hold the LLM units for the entire session
- **AND** the pipeline SHALL contain the enabled LLM stages

#### Scenario: LLM disabled (default)

- **WHEN** the resolved plan does not carry the LLM feature
- **THEN** no LLM infrastructure object SHALL be instantiated
- **AND** no LLM stage SHALL exist in the pipeline

#### Scenario: transition events reach the stages

- **WHEN** `onVisitStateTransition` records an edge
- **THEN** every assembled stage's `onStateTransition(edge)` SHALL be invoked exactly once, after the agent's own counter bookkeeping

---

### Requirement: SataAgent — LLM New-State Hook

LLM new-state routing SHALL be the `LlmNewState` decision stage — the second stage of the pipeline (after `Budget`), assembled only when the plan enables the LLM new-state mode. `SataAgent.selectNewActionNonnull()` SHALL contain no inline LLM block: it delegates to `DecisionPipeline.decide()`, and the stage occupies the ladder position the inline block occupied (before stagnation/random/launcher/trigger/SATA — behavior parity gated by the per-preset goldens).

The stage's guards are unchanged in content: the shared LLM precondition — action buffer empty (to not interrupt multi-step navigation) AND the state has more than 2 actions (to skip trivial states like permission dialogs) — evaluated through the single `LlmGate` helper shared by the three LLM stages (the former verbatim triplication of this precondition is deleted); then the new-state trigger (`_isNewState`) and the breaker gate. On a non-null engine result the stage returns `Select` (stamping `DecisionSource.LLM`/`PickChannel.LLM`, and resolving a synthesized `MODEL_LLM_TAP` against the state — unchanged accept semantics); on null it returns `Continue` and the remaining pipeline decides (structural fallback).

When the plan has no LLM feature, the stage does not exist and selection cost is zero.

#### Scenario: LLM provides action on new state

- **WHEN** the pipeline reaches `LlmNewState` with the buffer empty, `actions.size() > 2`, `_isNewState` true, breaker allowing
- **AND** the engine returns a non-null `ModelAction`
- **THEN** the action SHALL decide the step (hard preemption — no later stage evaluated)

#### Scenario: LLM returns null, SATA takes over

- **WHEN** the engine returns `null`
- **THEN** the stage SHALL return `Continue`
- **AND** the launcher/trigger/SATA stages SHALL evaluate normally

#### Scenario: LLM skipped — buffer has pending navigation

- **WHEN** the action buffer is non-empty (multi-step navigation in progress)
- **THEN** the shared precondition SHALL fail and all three LLM stages SHALL return `Continue`
- **AND** the buffered action SHALL be returned by the `SataChain` buffer rung (existing behavior)

#### Scenario: LLM skipped — trivial state

- **WHEN** `actions.size() <= 2` (e.g., permission dialog)
- **THEN** the LLM stages SHALL return `Continue` and the pipeline SHALL handle the state directly

#### Scenario: LLM disabled, zero overhead

- **WHEN** the plan carries no LLM feature
- **THEN** no LLM stage SHALL exist and the pipeline SHALL be structurally identical to the non-LLM preset's
- **AND** the decision sequence SHALL equal the non-LLM golden under the same seed

---

### Requirement: SataAgent — LLM Stagnation Hook

LLM stagnation routing SHALL be the `LlmStagnation` decision stage, assembled after `LlmNewState` and before `LlmRandom` when the plan enables the stagnation mode. The trigger is at-or-past the midpoint with the episode's single shot unspent: `graphStableCounter >= graphStableRestartThreshold / 2` AND the stage's per-episode fired flag is armed. The same shared precondition (`LlmGate`) applies.

**Episode-state ownership (moved out of `StatefulAgent`):** the fired flag SHALL be a private field of the `LlmStagnation` stage. It is burned inside `decide()` whenever the trigger fires — whatever the LLM answers (a null result is a failed attempt, not an unused one; the restart at the full threshold is what follows if the stagnation persists) — and re-armed by the stage's `onStateTransition` hook on `NEW_ACTION`/`NEW_ACTION_TARGET` edges (a new edge ends the episode). The stage's own reset of `graphStableCounter` after a successful escape does not re-arm the flag: no new edge was observed, so it is the same episode.

**One scenario below is a replacement, not a carry-forward.** `"Stagnation hook fires only once per phase"` asserted in the main spec that the hook fires *only* at `graphStableCounter == threshold/2` exactly — 49 and 51 both declined by the equality check. This change replaces that trigger with at-or-past the midpoint guarded by the episode flag, so the old body is contradicted rather than forgotten: 51 with the flag armed now fires. What the scenario's name asserts — one firing per episode — is what survives, and it survives structurally (the flag is burned in `decide()`, not re-derived from the counter), so the scenario is restated under its own header with the new mechanism as its body. The counter-jump case the old equality check would have missed is covered by `llm-routing :: Stagnation LLM Mode :: "midpoint skipped by a counter jump still fires"`.

`graphStableCounter` remains a `StatefulAgent`/`RunContext` field — it is shared exploration state consumed by the forced-restart mechanism (`onGraphStable` at `counter > threshold`), which this stage does not modify. On an accepted escape the stage SHALL reset the counter to 0 through the `StepContext`'s single declared write method (unblocking exploration, unchanged semantics).

#### Scenario: LLM breaks stagnation (single-shot at midpoint)

- **WHEN** `graphStableCounter` reaches `graphStableRestartThreshold / 2` with the episode flag armed, the shared precondition holds, and the breaker allows
- **AND** the engine returns a non-null `ModelAction`
- **THEN** the action SHALL decide the step, the flag SHALL be burned, `graphStableCounter` SHALL be reset to `0`
- **AND** `requestRestart()` SHALL NOT be called

#### Scenario: Stagnation hook fires only once per phase

- **WHEN** `graphStableCounter` is `49` with `graphStableRestartThreshold=100` (below the midpoint) and the episode flag is armed
- **THEN** the stage SHALL NOT fire
- **WHEN** the counter then reaches `50` and the stage fires, burning the flag
- **THEN** every later step of the same episode — `51`, `52`, and every counter value up to the restart threshold — SHALL NOT fire again, whatever the counter reads
- **AND** the single shot SHALL be spent by the firing itself, not by the counter leaving the midpoint (the pre-change equality check declined `51` because `51 != 50`; the flag declines it because the episode is over)

#### Scenario: LLM fails at midpoint, stagnation continues to restart

- **WHEN** the trigger fires and the engine returns `null`
- **THEN** the flag SHALL be burned and the stage SHALL return `Continue`
- **AND** the counter SHALL continue incrementing and the stage SHALL NOT fire again this episode
- **AND** if `graphStableCounter` eventually exceeds `graphStableRestartThreshold`, `requestRestart()` SHALL be called (existing behavior per INV-EXPL-09)

#### Scenario: new edge re-arms the episode through the stage hook

- **WHEN** the flag is burned and a later transition records a `NEW_ACTION` edge
- **THEN** `onStateTransition` SHALL re-arm the flag inside the stage
- **AND** a later stagnation reaching the midpoint SHALL fire again

#### Scenario: no stagnation state remains in StatefulAgent

- **WHEN** the change is complete
- **THEN** `StatefulAgent` SHALL declare no LLM-stagnation fired flag
- **AND** the only stagnation-episode state SHALL live in the `LlmStagnation` stage
