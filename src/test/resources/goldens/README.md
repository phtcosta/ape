# The parity oracle's golden files

These NDJSON files pin the action-selection decisions of
`SataAgent.selectNewActionNonnull()` as it behaves today, so that
`rearch-02-runspec` and `rearch-03-decision-pipeline` can restructure the code around it and
prove they changed nothing. Everything else in the oracle is machinery; these five files are
the product.

A golden's whole value is that **a diff in it means something**. That property does not come
from the format — it comes from the rule that a golden changes for exactly one reason, and from
somebody reading the diff before committing it. This document is that rule, written down for
someone who was not in the sessions that captured them.

## What is here

| File | Written by | Preset | Seed | Fixture | What its records pin |
|---|---|---|---|---|---|
| `aperv/baseline.ndjson` | `ParityOracleApervTest` | `aperv` | 4242 | — | the EARLY_STAGE forward pick on a fresh screen (step 0), then **both** legs of the epsilon-greedy rung: the priority roulette (step 1) and the least-visited pick walking `b0`/`b1`/`b2` (steps 2–4) |
| `mop/baseline.ndjson` | `ParityOracleMopTest` | `mop` | 5150 | `cryptoapp.apk.mop.json` | EARLY_STAGE, the cadence launcher firing into `MessageDigestActivity`, the unvisited-MOP short-circuit, then the epsilon-greedy roulette |
| `llm/baseline.ndjson` | `ParityOracleLlmTest` | `llm` | 6060 | — | all four LLM provenance values — `accepted`, `declined`, `timeout`, `not_routed` — with every non-accept falling through to `roulette_early` |
| `llm_mop/baseline.ndjson` | `ParityOracleLlmMopTest` | `llm_mop` | 7070 | `cryptoapp.apk.mop.json` | accept → decline into the MOP short-circuit → `not_routed` into the launcher → timeout into the SATA chain |
| `llm_mop/preemption.ndjson` | `PreemptionGoldenTest` | `llm_mop` | 8080 | `cryptoapp.apk.mop.json` | the precedence order itself: an accept preempting a launcher that was due, a decline falling through **into** that launcher on the same step, then the SATA chain as the fallback |

Format and semantics live in `DecisionRecord` and `GoldenFile` (design D4). The short version:
one header line, then one line per selection step, keys in a fixed order, fields that do not
apply left **out** rather than written as `null`.

## When regenerating is legitimate

**Exactly one reason: a behavior change that somebody decided to make.**

A red comparison is a question, not a problem to be silenced. The comparator's report names the
preset, scenario, step, first divergent field, both values, and how many records diverge in
total. Read it and answer which of two things happened:

- **A migration regression.** The restructured code no longer reproduces a decision it was
  supposed to reproduce. **Fix the code.** This is the case the oracle exists for, and it is by
  far the more common one during stages 2 and 3.
- **A decided behavior change.** Someone chose to change what the ladder does, the change is
  recorded in a change's artifacts, and the new decisions are the intended ones. Then — and only
  then — regenerate, review, and commit the new golden together with the decision.

There is no third case. "The comparison is red and I want it green" is not a reason; it is the
failure mode this whole change set was built to make impossible. If a golden is red and you
cannot say which of the two cases you are in, you are not ready to regenerate it.

## The command

Regeneration is scoped to the golden you actually intend to change:

```bash
mvn test -Dtest=ParityOracleMopTest -DfailIfNoSpecifiedTests=false \
    -Dape.oracle.regenerate=true -Dape.oracle.capturedAt=$(git rev-parse HEAD)
git status --short src/test/resources/goldens   # only the intended golden may appear
```

Four things about that command, each of which matters:

- **`-Dape.oracle.regenerate=true` is the only thing that can write a golden** (INV-ORA-04).
  `GoldenFile.write` refuses without it, and the refusal lives in the only method that can
  write, so no caller can forget to ask. Compare mode never writes, and a *missing* golden fails
  with these instructions instead of quietly creating one.
- **`-Dtest=…` is not optional in practice.** The write gate is the property and nothing else,
  so an unscoped `mvn test -Dape.oracle.regenerate=true` rewrites **all five** goldens. Their
  decision records are deterministic and would come back byte-identical — but `capturedAt` is
  re-stamped on every write, so all five would show up in `git status` with a new provenance
  line, and the reviewer of a one-golden change would be handed a five-file diff to certify.
  Use the unscoped form only when the decided change actually moves every preset, and say so in
  the commit message.
- **`-DfailIfNoSpecifiedTests=false`** stops the build from failing when the `-Dtest=` filter
  matches nothing. The cost of that convenience is real: a mistyped class name then produces a
  green build that captured nothing at all. Which is why the `git status` line above is part of
  the command and not an afterthought.
- **`-Dape.oracle.capturedAt=…`** stamps the header's provenance. Without it the header records
  the literal `unknown`: capture never blocks on provenance (owner decision, 2026-08-03), so the
  only thing standing between an unattributable golden and the repository is the person
  reviewing the capture diff. Pass it.

The `d8` PATH prefix that `docs/20260803_procedimento_worktree_rearch.md` requires is **not**
needed here: the `d8-dex` execution is bound to the `package` phase and `mvn test` never reaches
it (verified 2026-08-03). It is required for `mvn package`.

## Reading the diff

The format was chosen so a diff is per-step and cannot churn on serialization: `DecisionRecord`
writes the keys in the fixed order of `FIELDS` rather than through `JSONObject.toString()`,
whose `HashMap`-backed order is not part of any contract. So every line that moved, moved
because a decision moved. What each field says when it changes:

- **`pickChannel`** — a different *rung or pick site* selected the action. This is the field that
  most directly reflects the ladder's shape, so a change here during a restructuring is the
  loudest possible signal. The committed set contains four of the eight channels:
  `roulette_early` (EARLY_STAGE forward, `aperv` step 0), `roulette_greedy` (the epsilon-greedy
  priority roulette, `aperv` step 1 and `mop` step 3), `short_circuit_unvisited` (the
  unvisited-MOP short-circuit, `mop` step 2 and `llm_mop/baseline` step 1), and `llm` (an
  accepted LLM result, `llm` step 0). One caveat: `sata_other` — `aperv` steps 2–4 — is the
  enum's total catch-all, stamped by the epsilon-greedy least-visited leg *and* by every other
  path that names no channel of its own. A diff moving into or out of `sata_other` therefore
  under-determines which rung ran; read the run's `[APE] Select action … by strategy …` log
  lines, or the capture test's direct assertions, before concluding.
- **`decisionSource`** — the *attribution* changed: which mechanism the ladder credits for the
  pick (`SATA`, `MOP`, `LLM`). Independent of the channel by design — the source names the
  mechanism whose boost was largest, the channel names the code path that consumed it. In
  `mop/baseline` step 2 the two move together (`MOP` / `short_circuit_unvisited`); a diff where
  only one of them moves is worth more attention than one where both do.
- **`actionType`** — normally `MODEL_CLICK`. `EVENT_TRIGGER_ACTIVITY` appearing or disappearing
  is the **launcher** starting or stopping to fire, which means the cadence, the census, or the
  precedence order above the launcher moved. Those steps carry the candidate class in `target`
  and carry neither provenance field, because an `ActivityTriggerAction` is not a `ModelAction`
  and has none to read. Provenance fields appearing on such a step would mean something else
  entirely was returned.
- **`target` alone, with every other field equal** — the same rung picked a different member of
  the same candidate set. Usually a seed or scenario change, not a ladder change: `aperv`
  steps 2–4 walk `b0`, `b1`, `b2` through one unchanging channel, so the target is *which*
  member, not *how* it was chosen. Verify the seed and the scenario before reading it as a
  regression.
- **`llm`** — provenance only. `declined` and `timeout` are the same observable at the selection
  level (both return null and fall through); they are distinguishable only here, which is why
  `llm/baseline` steps 1 and 2 pick the same target through the same channel and differ in this
  field alone. A swap between those two is a script change. A step moving between `not_routed`
  and any verdict is different in kind: it means a hook's precondition changed, and that **is** a
  ladder change. Absent (rather than `not_routed`) means the preset has no router at all.
- **`step`, or a changed record count** — the scenario changed. The comparator reports a record
  dropped or inserted mid-sequence as a `step` divergence, and a shorter or longer replay with
  its own message naming the golden step that lost its counterpart. During stages 2 and 3 the
  scenarios are frozen (below), so a record-count change in that window means the *harness*
  moved, not the ladder — and that is a bug in the harness, not a golden to regenerate.

## `capturedAt`, and why the five do not share one

`capturedAt` attributes **a capture**, not the set. It records the commit that was `HEAD` when
that one file was written, which is normally the *parent* of the commit that carries the file —
you cannot stamp a commit sha before making the commit. `GoldenFile.compareHeaders` deliberately
excludes it from comparison (`Header.COMPARED_FIELDS`), because it necessarily differs between a
capture and every later run; `preset`, `scenario`, `seed` and `fixture` are compared, and a
mismatch in those is reported as its own failure rather than as a decision divergence.

So a mixed set is normal. The four baselines carry `58d0b40` and the preemption golden carries
`ee26650` — each the commit its own capture ran at. What makes the set coherent is not a shared
value but this:

```bash
git diff --stat <capturedAt> HEAD -- src/main/java   # empty for every golden in the set
```

That is the check worth running, and the thing to be suspicious of is a golden whose
`capturedAt` names a commit with production changes since. Such a golden was captured against
code that no longer exists, and a green comparison against it proves nothing about today's
ladder. A `capturedAt` of `unknown` cannot be checked at all, which is the whole cost of
capturing without the property.

## Committing a regeneration

The diff is the artifact; the commit message is what makes it reviewable a year from now. It
must state **the decision that changed the behavior** — not that the goldens were regenerated,
which the diff already says. Name the change (`rearch-0N-…`), the mechanism whose behavior moved,
and what the new decisions are. A regeneration commit that says "update goldens" is
indistinguishable from the failure mode this procedure exists to prevent.

Commit the goldens **with** the production change that motivated them, in the same commit or an
adjacent one on the same branch, so a reader never meets a golden whose motivation is somewhere
else in the history.

## Never in CI (INV-ORA-04)

The default build only compares. No CI job, script, or profile sets `ape.oracle.regenerate`, and
none may: a pipeline that can rewrite a golden turns the gate into a rubber stamp. `GoldenFileTest`
asserts that the default mode writes nothing under this directory, so a mechanism that started
regenerating automatically would fail the suite rather than pass quietly.

## Frozen during stages 2 and 3 (INV-ORA-07)

While `rearch-02-runspec` and `rearch-03-decision-pipeline` are in flight, **the golden files and
the scenario scripts do not change.** They are the fixed point those stages are measured against;
a golden that moves with the code it is measuring measures nothing. The one layer that may adapt
is `OracleScaffold` — the injection profile can follow a renamed or relocated field, because the
goldens are meant to outlive the class structure that produced them. That is the entire reason
the format is NDJSON text and not Java serialization.

If a stage-2/3 change genuinely requires a golden to move, it is a **deviation**: it needs an
explicit owner decision recorded in that change's artifacts before the capture is run, not after.

### The escape hatch, if a future preset needs non-default Config

A preset is realized here by its injection profile over **jar-default** `Config` values
(design D2), and every preset test guard-asserts the defaults its ladder reads
(`LadderConfigGuard`), so a changed default fails with the key named instead of surfacing as an
unexplained golden divergence. That works today because the only preset-divergent key the ladder
reads — `ape.llmPercentage` — is removed from the picture entirely by the scripted router, which
overrides `shouldRouteRandom()` outright.

A future preset that diverges on a **non-LLM** key the ladder reads cannot be expressed this way:
`Config`'s fields are `public static final`, resolved once at class load, so no test can move one
after the fact. The available answer is a **second surefire execution** in `pom.xml` with its own
`systemPropertyVariables`, forked into a fresh JVM so `Config` resolves against those properties,
running only that preset's capture test. It was deliberately not built — it buys nothing while no
such preset exists — and it is written down here so that the day one appears, the option is a
known one rather than a rediscovery.
