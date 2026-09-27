## Purpose

The exploration capability restates the LLM new-state hook as it sits in the agent's decision ladder. This delta only brings its statement of the shared LLM precondition in line with `llm-routing`'s: with opaque routing on (`ape.llmPercentageNoSubstrate >= 0` on a plan carrying `LLM_RANDOM`), a step whose state offers actions but none requiring a target (an *opaque* step) and whose tree holds a dynamic region (a large, label-less, touch-taking `android.view.View` leaf — a game canvas; `llm-routing` INV-RTR-26) passes the precondition even though the state has two actions or fewer. An opaque step without one — a progress dialog, a camera preview, a splash screen — does not. The "trivial state" scenario, which used a permission dialog as its example, is narrowed accordingly: a dialog with a button still has a widget action and is not opaque, so what it illustrates is unchanged; a two-action state with no widget action is now opaque, and is closed only when opaque routing is off.

## MODIFIED Requirements

### Requirement: SataAgent — LLM New-State Hook

LLM new-state routing SHALL be the `LlmNewState` decision stage — the second stage of the pipeline (after `Budget`), assembled only when the plan enables the LLM new-state mode. `SataAgent.selectNewActionNonnull()` SHALL contain no inline LLM block: it delegates to `DecisionPipeline.decide()`, and the stage occupies the ladder position the inline block occupied (before stagnation/random/launcher/trigger/SATA — behavior parity gated by the per-preset goldens).

The stage's guards are: the shared LLM precondition — action buffer empty (to not interrupt multi-step navigation) AND either the state has more than 2 actions (to skip trivial states like permission dialogs) or, when opaque routing is on, the step is opaque (the state has actions and none requires a target — `llm-routing` INV-RTR-21) and its tree has a dynamic region (`llm-routing` INV-RTR-26) — evaluated through the single `LlmGate` helper shared by the three LLM stages (the former verbatim triplication of this precondition is deleted); then the new-state trigger (`_isNewState`) and the breaker gate. On a non-null engine result the stage returns `Select` (stamping `DecisionSource.LLM`/`PickChannel.LLM`, and resolving a synthesized `MODEL_LLM_TAP` against the state — unchanged accept semantics); on null it returns `Continue` and the remaining pipeline decides (structural fallback).

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

- **WHEN** `actions.size() <= 2` and the state offers at least one action requiring a target (e.g., a permission dialog with one button and `MODEL_BACK`)
- **THEN** the LLM stages SHALL return `Continue` and the pipeline SHALL handle the state directly, whether or not opaque routing is on

#### Scenario: opaque state — opaque routing and the dynamic region decide

- **WHEN** the state's actions are `[MODEL_BACK, MODEL_MENU]`, its tree has a dynamic region, the buffer is empty, `_isNewState` is true and the breaker allows
- **THEN** the stage SHALL call the engine when opaque routing is on
- **AND** SHALL return `Continue` without calling it when opaque routing is off (`ape.llmPercentageNoSubstrate=-1`)
- **AND** WHEN the same state's tree has no dynamic region (a progress dialog), the stage SHALL return `Continue` without calling the engine whether or not opaque routing is on

#### Scenario: LLM disabled, zero overhead

- **WHEN** the plan carries no LLM feature
- **THEN** no LLM stage SHALL exist and the pipeline SHALL be structurally identical to the non-LLM preset's
- **AND** the decision sequence SHALL equal the non-LLM golden under the same seed
