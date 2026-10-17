# Delta: LLM Routing (isolate-guidance-groups)

## Purpose

In the E6 LLM arm, `p_L = 0.9` was configured and the LLM was called on 69 % of steps. Only about 45 % of steps
were decided by it (plan §2.5). The rest of the dose was lost to rules the plan did not declare:
- G1: a non-empty SATA buffer;
- G2: two actions or fewer, although the `LlmGate` javadoc said "three or fewer";
- G3: the circuit breaker, with constants in code;
- G4: Budget, by order;
- G7: a coin drawn from the agent stream only after the precondition passed.

This delta declares every one of these rules:
- **Gates are a Specification** (D-B8). `LlmGate` becomes an interface, `name()` and `admits(ctx)`. Its two
  implementations are `MinActionsGate(min)` and `BreakerGate`, and each takes its parameter from the plan.
  G4 is the BUDGET guard (`decision-pipeline`).
- **The buffer is not a gate** (D-A6, P-4). An LLM step preempts a non-empty buffer, which is abandoned without
  weakening.
- **The triggers stay as they are** (D-A5, D-A17): new-state on every new state and stagnation once per episode,
  with no coin. The coin `p_L` applies to the other steps. The dose is described as "every new state + `p_L` on the
  other steps".
- **The coin has its own stream** (P-3, `decision-pipeline` INV-DP-13). `u_L` is drawn every step, before any
  gate. `LLM_RANDOM` is renamed `LLM_COIN`.
- **Every LLM-intended step is recorded** in `dec.own`: the intended owner, the trigger, the gate that barred
  the step and the fallback reason.

## Invariants

- **INV-RTR-23**: The conditions under which an LLM stage, once its trigger or coin holds, does not call the LLM
  SHALL be exactly the mounted `LlmGate` chain. That chain is `MinActionsGate(ape.llmGateMinActions)` and then
  `BreakerGate`, evaluated in this order with short-circuit. A gate whose parameter is `0` SHALL NOT be mounted.
  `MinActionsGate(3)` admits exactly the states with `getActions().size() >= 3`, which equals the former `> 2`.
  The SATA action buffer SHALL NOT be a condition of any LLM stage.
- **INV-RTR-24**: The `LlmCoin` stage's trigger SHALL be `u_L < p_L`, where `u_L` was drawn at step open from
  `StepRng`'s `llmCoin` stream (`decision-pipeline` INV-DP-13). The `LlmNewState` and `LlmStagnation` triggers SHALL
  consume no coin.
- **INV-RTR-25**: On every step on which an LLM stage's trigger or coin held, `dec.own` SHALL record the following,
  whichever owner finally took the step:
  - `i="llm"`;
  - the trigger `trg`;
  - the barring gate `g`, when one barred;
  - the fallback reason `fb`, when the LLM did not take the step.

## ADDED Requirements

### Requirement: Declared LLM Gates

`LlmGate` SHALL be an interface, `String name()` and `boolean admits(StepContext ctx)`. A gate SHALL be pure: it
does not draw from any random stream and does not write the step record. `BreakerGate` is the exception to the
second clause: it consults the breaker once, which may move it from OPEN to HALF_OPEN. Two implementations exist:

| Gate | `name()` | Admits when | Plan key (owner `LLM`) | Default | Mounted when |
|---|---|---|---|---|---|
| `MinActionsGate(min)` | `min_actions` | `newState.getActions().size() >= min` | `ape.llmGateMinActions` | `3` | key `> 0` |
| `BreakerGate` | `breaker` | `LlmClient.allows()` returns true (one `shouldAttempt()`) | `ape.llmBreakerFailures` | `3` | key `> 0` |

The composition root SHALL build one chain from the plan, in the order `MinActionsGate`, then `BreakerGate`.
All three LLM stages SHALL share that chain, and it SHALL be evaluated with short-circuit, so that a breaker
refusal is unambiguously breaker-caused. A gate whose parameter is `0` SHALL NOT be mounted. Both keys appear in
`RUN_START`. `ape.llmBreakerOpenMs` (default `60000`) parameterizes the breaker, not the gate's mounting
(`llm-infrastructure`).

The javadoc of `MinActionsGate` SHALL state the rule the code applies: the gate refuses a state with **fewer than
`min`** actions, so the default refuses two actions or fewer. The former `LlmGate` javadoc said "three actions or
fewer" while the code refused `<= 2`. The SATA action buffer (former G1) is not a gate, and Budget (former G4) is
the `Budget` guard (`decision-pipeline`).

#### Scenario: default gates reproduce the former thresholds
- **WHEN** the plan leaves `ape.llmGateMinActions` and `ape.llmBreakerFailures` at their defaults and a coin step's state has exactly 2 actions
- **THEN** `MinActionsGate(3)` SHALL refuse, `BreakerGate` SHALL NOT be consulted, and `dec.own.g` SHALL be `"min_actions"`
- **AND** with 3 actions on the state, `MinActionsGate(3)` SHALL admit

#### Scenario: a gate at zero is not mounted
- **WHEN** the plan sets `ape.llmGateMinActions=0`
- **THEN** the chain SHALL be `[BreakerGate]` alone, and an LLM stage SHALL call the LLM on a state with 1 action when its trigger holds and the breaker allows

## MODIFIED Requirements

### Requirement: New-State LLM Mode

When the plan carries `LLM_NEW_STATE`, the `LlmNewState` stage SHALL be assembled after the `Budget` and
`Restart` guards and ahead of the stagnation, coin, launcher, trigger, MOP-coin and SATA stages.
`decide()` SHALL perform these steps in order:
1. **Trigger.** Check `_isNewState`, which is captured before `markVisited()` (the capture is unchanged). When it
   is false, return `Continue` and record nothing.
2. **Gates.** Evaluate the shared gate chain (INV-RTR-23) on the step's `StepContext`. When a gate refuses,
   return `Continue` and record `dec.own` with `i="llm"`, `trg="new_state"`, `g=<gate name>` and `fb="gate"`.
3. **Call.** When every gate admits, invoke `LlmEngine.selectAction(..., "new-state", step)`.
4. **Result.** On a non-null result:
   - stamp the owner `llm` and the channel `llm` through `LlmAcceptance`, the acceptance helper formerly in
     `LlmGate.accept`, and resolve a synthesized `MODEL_LLM_TAP` against the state (unchanged);
   - when the buffer is non-empty, abandon it without weakening (`decision-pipeline` INV-DP-15);
   - return `Select`.

   Otherwise return `Continue`, with `dec.own.fb` set to the reason.

The action buffer SHALL NOT be consulted as a condition. The gate chain exists in exactly one place, the
composition root. All three LLM stages consult the same chain instance.

The stage SHALL run after `adjustActionsByGUITree()` has assigned priorities (unchanged, INV-EXPL-11) and before any SATA rung.

#### Scenario: First visit to new state with LLM enabled
- **WHEN** the pipeline reaches `LlmNewState` on a first-visit state with 3+ actions, with the buffer empty or not
- **AND** the breaker allows and `LlmEngine.selectAction(...)` returns a non-null `ModelAction`
- **THEN** the stage SHALL return `Select` with `dec.src="llm"` and `dec.own.trg="new_state"` (later stages not evaluated)

#### Scenario: First visit but circuit breaker open
- **WHEN** the state is new, it has 5 actions, and `BreakerGate` refuses because `LlmClient.allows()` returns false
- **THEN** the stage SHALL return `Continue`, no HTTP call SHALL be made, and `dec.own` SHALL carry `g="breaker"` and `fb="gate"`

#### Scenario: Revisit of known state
- **WHEN** `_isNewState` is `false`
- **THEN** the stage SHALL return `Continue` regardless of other conditions, and SHALL evaluate no gate

#### Scenario: LLM returns null on new state
- **WHEN** `LlmEngine.selectAction(...)` returns `null`
- **THEN** the stage SHALL return `Continue` and the remaining pipeline SHALL decide the step

#### Scenario: precondition evaluated in one place
- **WHEN** the three LLM stages evaluate their gates on a step
- **THEN** all three SHALL consult the same `LlmGate` chain that the composition root mounted from the plan (`MinActionsGate`, then `BreakerGate`)
- **AND** no stage SHALL carry its own copy of a gate expression, and none SHALL test the action buffer

---

### Requirement: Stagnation LLM Mode

When the plan carries `LLM_STAGNATION`, the `LlmStagnation` stage SHALL be assembled after `LlmNewState` and
before `LlmCoin`. The stage SHALL fire at most once per stagnation episode. The trigger is
`graphStableCounter >= threshold / 2` AND the stage's per-episode fired flag is still armed (the pure predicate
`stagnationMidpointReached`, owned by the stage). The trigger consumes no coin (D-A17). When the trigger holds, the
shared gate chain (INV-RTR-23) is evaluated. A refusal returns `Continue` and records `dec.own` with
`trg="stagnation"`, the gate and `fb="gate"`. The flag stays armed, because no call was made. The fired flag SHALL
be **owned by the stage** and SHALL be:

- burned inside `decide()` whenever the gates admit and the LLM is called, whatever the LLM answers. A null result
  is a failed attempt, not an unused one. If stagnation persists, the restart at the full threshold follows;
- re-armed by the stage's `onStateTransition(edge)` hook on `NEW_ACTION`/`NEW_ACTION_TARGET` edges. A new edge
  ends the episode; it is the same event that resets `graphStableCounter`.

On an accepted answer the stage abandons a non-empty buffer without weakening (`decision-pipeline` INV-DP-15).

`graphStableCounter` itself remains shared exploration state owned by the agent; the stability restart consumes
it independently. On an accepted escape the stage SHALL reset it to 0 through the `StepContext`'s single declared
write method. The restart mechanism is a guard: step 2's `onGraphStable()` raises the flag at
`counter > threshold`, and the `Restart` stage selects the restart on the next pipeline pass (`decision-pipeline`
"Restart Guard Stage").

#### Scenario: LLM provides escape action at or past the stagnation midpoint
- **WHEN** `graphStableCounter` reaches `threshold / 2` (or any greater value with the flag armed), the state has 3+ actions, and the breaker allows
- **THEN** the stage SHALL burn the flag and invoke `LlmEngine.selectAction(..., "stagnation", step)`
- **AND** on a non-null result it SHALL reset `graphStableCounter` to 0 and return `Select` with `dec.src="llm"`

#### Scenario: hook fires once per episode even when the LLM fails
- **WHEN** the trigger fires, the gates admit, and the LLM returns null
- **THEN** the stage SHALL return `Continue` with the flag burned
- **AND** the stage SHALL NOT fire again until a new edge re-arms it
- **AND** if `graphStableCounter` eventually exceeds the full threshold, `requestRestart()` SHALL be called and the `Restart` stage SHALL select the restart (existing behavior, now a guard)

#### Scenario: new edge re-arms the episode
- **WHEN** the flag is burned and a later step records a `NEW_ACTION` edge
- **THEN** the stage's `onStateTransition` SHALL re-arm the flag
- **AND** a subsequent stagnation reaching the midpoint SHALL fire again (new episode)

#### Scenario: midpoint skipped by a counter jump still fires
- **WHEN** the counter passes from below the midpoint to above it without equaling `threshold / 2` at any check
- **THEN** the stage SHALL still fire at the first check where `graphStableCounter >= threshold / 2`

#### Scenario: Stagnation mode disabled
- **WHEN** the plan does not enable the stagnation mode
- **THEN** the `LlmStagnation` stage SHALL NOT exist in the pipeline
- **AND** the restart behavior SHALL proceed unchanged

---

### Requirement: Probabilistic LLM Routing

When the plan carries `LLM_COIN` (LLM ∧ `p_L = ape.llmPercentage > 0`), the `LlmCoin` stage SHALL be assembled
after `LlmStagnation` and before `MopLauncher`. It is renamed from `LlmRandom` (feature `LLM_RANDOM`). Its trigger
SHALL be `u_L < p_L` (INV-RTR-24). When the coin holds, the shared gate chain is evaluated (INV-RTR-23). When every
gate admits, the engine is invoked. When the coin does not hold, the stage returns `Continue`: a lost coin claims
nothing, so `dec.own.i` is left to the owner that claims the step next (design D-B14.6), and `uL` is still written.

**Which stream the coin comes from.** `u_L` SHALL come from `StepRng`'s `llmCoin` stream, which is derived from
the run seed. It SHALL be drawn at step open on every step of a plan carrying `LLM`, before any stage runs,
whether or not the step reaches this stage (`decision-pipeline` INV-DP-13). It SHALL NOT come from the agent's
generator (`ape.getRandom()`) or from `RunContext.rng()`. The draw therefore no longer depends on any gate or on
an earlier stage, and mounting, unmounting or reparameterizing a gate shifts neither the coin nor SATA's draws.
This changes the random sequence relative to `eea2b1a9`, where the coin came from the agent stream after the
precondition. The design declares it, and the parity goldens are recaptured (`decision-pipeline` "Migration
Parity Gate").

When the stage takes the step while the buffer is non-empty, it abandons the buffer without weakening
(`decision-pipeline` INV-DP-15).

When the stage fires and the engine returns a non-null action, the telemetry mode label SHALL be `"random"`.
`dec.own.trg` is `"coin"`.

#### Scenario: Default 2% routing
- **WHEN** `p_L` is `0.02`, no earlier stage selected this step, `u_L` is below 0.02, the state has 3+ actions, and the breaker allows
- **THEN** the stage SHALL invoke the engine with mode `"random"`

#### Scenario: Disabled
- **WHEN** `ape.llmPercentage` is `0.0` on a plan carrying `LLM`
- **THEN** the `LlmCoin` stage SHALL NOT be assembled
- **AND** `u_L` SHALL still be drawn at every step open, so that a change of `p_L` never shifts another draw

#### Scenario: Priority order preserved
- **WHEN** `_isNewState` is `true` and the new-state mode is enabled, with `p_L = 0.7`
- **THEN** the `LlmNewState` stage SHALL decide the step (hard preemption)
- **AND** at most one LLM call SHALL be made for that step

#### Scenario: draw order preserved under the same seed
- **WHEN** two runs share a cell, a seed and fixtures, and differ only in `ape.llmGateMinActions` (`3` against `0`)
- **THEN** the sequence of `u_L` values SHALL be identical in both runs, step by step
- **AND** SATA's choices SHALL be identical on every step that both runs hand to `SataChain`

#### Scenario: High percentage (70%)
- **WHEN** `p_L` is `0.7`, and neither the new-state nor the stagnation stage decided the step
- **THEN** the `LlmCoin` stage's coin SHALL hold on approximately 70 % of the steps that reach it
- **AND** the rate SHALL be the plan's value applied to a single draw, never a per-stage rescaling: the coin is `u_L < p_L` with one `u_L` per step

---

### Requirement: Declared LLM Fallback

The plan declares the LLM fallback through the groups it mounts. The decision pipeline realizes it structurally:
an LLM stage returns `Continue` and the rest of the assembled pipeline decides the step (`decision-pipeline`
INV-DP-11). The rest is the following:
- with MOP: the launcher, the component trigger (when STATIC mounts it), the MOP coin and SATA;
- without MOP: the component trigger (when STATIC mounts it) and SATA.

This covers every lost coin, gate refusal, decline, failure, refused answer and breaker denial. There SHALL be no
in-step retry, no substitute selection inside an LLM stage, and no exception path. The `dec.src` of a
step decided by the fallback SHALL be the deciding stage's owner, never `llm`.

Each such step on which an LLM trigger fired or the coin was won SHALL record in `dec.own` the intended owner
`i="llm"` and the trigger `trg` (`new_state`, `stagnation` or `coin`); a lost coin claims nothing and records no
`i="llm"` (design D-B14.6). When an earlier LLM stage's trigger held and a later one made the call, the record names
the stage that made the call (design D-B14.14). It also records the fallback reason `fb`:
- `gate`: a gate refused, named in `g` (`min_actions` or `breaker`);
- `error`: the attempt was abandoned before mapping (screenshot, image, HTTP, timeout or parse);
- `no_match`: the answer was refused at mapping (including `dead_pair`, `boundary` and `degenerate`);
- `decline`: the engine returned no action for any other reason.

Each gate refusal adds one to `RUN_END.counters.gateMinActions` or `gateBreaker`, once per step.

#### Scenario: timeout falls back to the declared base
- **WHEN** an `as_mop_llm` run's coin holds, its LLM call times out, and the launcher's counter is at or past its cadence with an eligible candidate
- **THEN** the step SHALL be decided by the `MopLauncher` stage with `dec.src="launcher"`
- **AND** `dec.own` SHALL carry `i="llm"`, `trg="coin"`, `fb="error"`

#### Scenario: decline on an aperv-based LLM arm falls to SATA
- **WHEN** an `as_llm` (no MOP) run's LLM answer is refused (dead pair) on a step
- **THEN** the step SHALL be decided by the `SataChain` stage
- **AND** the refused answer SHALL still be telemetered as `result=no_match reason=dead_pair`, with `dec.own.fb="no_match"`
