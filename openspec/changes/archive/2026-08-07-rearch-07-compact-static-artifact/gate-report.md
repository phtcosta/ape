# Equivalence gate result — `rearch-07-compact-static-artifact` group 4

Recorded 2026-08-05, at the close of tasks 4.1–4.3. This is the evidence the cutover (group 5)
rests on, written down here because the gate deletes itself immediately afterwards: the old parser
is the oracle, and once it is gone nothing in the tree can re-run this comparison.

## What was compared

`MopArtifactEquivalenceTest` (ordinary `mvn test`, no system property, no skip branch) loads each
member twice — the **full static-analysis JSON through the old parser**, the **derived artifact
through the compact reader** — and asserts the two agree on every consumed query:

| Dimension | How | Note |
|---|---|---|
| `package` / `mainActivity` | direct equality | — |
| widget flags | aggregates + both per-event maps | over the full key universe of both sides |
| widget metadata | the 6 string fields + `entries` | null and `""` compared as equal, because no consumer distinguishes them |
| MOP-activity sets | flag off **and** flag on | the flag-on oracle comes from `augmentActivitiesFromSources(…, true)` |
| OPTIONSMENU gateways | flag off **and** flag on | the record universe is asserted equal *before* the answers are |
| WTG click view | **set-based** per source activity | see "the 17/16 divergence" below |
| receivers / services | `reachesTarget`, filter actions/categories, `targetMethods` emptiness, in list order | fixes the trigger tuples exactly |
| providers | `reachesTarget`, `authorities`, `permission` | fixes the provider tuples exactly |
| activities | `className`, `isMain`, `reachesTarget`, `permission`, **`deepLinkUri`** | oracle side is `MopLauncherStage.buildDeepLinkUri` |

Two things are deliberately **not** compared. The `stats` block, because its counters are
observability under INV-DRV-04 and one of them legitimately changes value across the cut. And the
tuples themselves: the builders are pure functions of the component fields above — read off
`buildTriggerTuples` / `buildProviderTuples` rather than assumed — so comparing their whole input in
list order fixes their output without needing the agent package on the classpath.

**The 17/16 divergence is expected and is why the WTG comparison is set-based.** The oracle keeps
exact-duplicate `(widget, target)` edges; the derivation removes them per INV-DRV-03. cryptoapp
alone diverges 17 against 16, on the very first member. The licence for ignoring multiplicity is the
audit closed as `gh96` 7.3: every WTG consumer either returns on first match (`MopScorer.scoreWtg`,
`StatefulAgent.frontierBoost`, `matchesQualifyingTarget`) or folds into a `HashSet` (`FrontierPass`,
`MopFrontierPass`, `qualifyingMopTargets`), so no decision in the tree can observe it.

**One asymmetry is permitted and checked rather than waived.** A widget that is neither flagged nor
carrying metadata is not written to the wire at all (INV-DRV-02's emit rule). The gate asserts of
every such omission that the oracle's widget was in fact inert, and the omission is observationally
void: `MopScorer.score` returns 0 for "null or resolved-but-unflagged", and
`ApePromptBuilder.widgetMetadata(null)` returns the empty string.

## Input set (task 4.1) — what each member fires

Every artifact was produced by running the **real generator** (`derive_mop_artifact.derive`) over
the source beside it. Nothing here is hand-written; a hand-written artifact would make the gate
compare the parser against someone's belief about the generator.

| Member | Source → artifact | Rule | What actually fired in the derivation |
|---|---|---|---|
| Real application | `cryptoapp.apk.gh60-fresh.json` → `cryptoapp.apk.mop.json` | D8 recovery in situ | `flagged=3` including `executeButton` via `recovered=1`; 30 widgets, 16 WTG edges (17 pre-dedup), 4 activities + 1 provider |
| Empty id | `gate-empty-id.sa.json` → `.mop.json` | INV-DRV-02 | `droppedFlaggedNoId=1`, `mopActivities=["p.A"]` — the activity is marked **before** the drop; the id-less widget is absent from the map |
| Synthetic lambda | `gate-synthetic-lambda.sa.json` → `.mop.json` | INV-DRV-01 | positive: `p.Rec/recovered` → `transitive` (`recovered=1`); **negative**: `p.NoRec/notRecovered` → `mop:{click:"none"}` although `syntheticLambda=2`; exact join: `p.Direct/direct` → `both` |
| Dialog re-key | `gate-dialog-rekey.sa.json` → `.mop.json` | INV-DRV-03 | the dialog's `confirm` widget filed under `p.Host` (move, not copy), host promoted into the set, `android.app.AlertDialog` retained in it, `orphanDialogs=1` for the unreachable dialog |
| A′ union | `gate-activity-union.sa.json` → `.mop.json` | INV-DRV-06 | `mopActivities=["p.Widget"]` vs `mopActivitiesAugmented=["p.Component","p.Reach","p.Widget"]` — all three sources contribute distinctly; the `p.Gate` menu is a gateway only under the augmented selection |
| Deep link | `gate-deep-link.sa.json` → `.mop.json` | INV-DRV-07 | `myapp://detail/x`; host/path defaulting as `only://`; three nulls — no `ACTION_VIEW`, empty scheme list, no filters at all |

The derivation is byte-stable: the authoring script was run twice and produced identical artifacts.

## Result (task 4.3)

**Green on the first run, with no divergence to investigate and therefore no generator fix.** Six
test methods × six members. Suite after the gate landed: **1207 tests, 0 failures, 19 skipped.**

A gate that passes immediately has proved nothing until it has been made to fail, so ten mutations
were applied to the derived artifacts and every one was caught by the intended test:

| Mutation | Caught by |
|---|---|
| the recovered Execute button loses its flag | `widgetFlagsAndMetadataAgree` |
| a metadata-carrying widget vanishes from the wire | `widgetFlagsAndMetadataAgree` |
| the wrapper with no reaching lambda is flagged anyway (INV-DRV-01 negative) | `widgetFlagsAndMetadataAgree` |
| the dialog widget is filed under the dialog class instead of the host | `widgetFlagsAndMetadataAgree` |
| one A′ source stops contributing | `mopActivitySetsAgreeUnderBothFlagStates` |
| the id-less widget's activity stops being marked (INV-DRV-02) | `mopActivitySetsAgreeUnderBothFlagStates` |
| a gateway qualifies on condition 1 when the oracle says it does not | `optionsMenuGatewaysAgreeUnderBothFlagStates` |
| the re-keyed WTG edge disappears | `wtgViewsAgreeAsSets` |
| the deep-link assembly drops its path segment | `componentSurfacesAndDeepLinksAgree` |
| the artifact names a different package | `packageAndMainActivityAgree` |

## What this gate does not establish

The group was designed around a JVM comparison over the pinned 345-app corpus. **That run does not
happen** (owner decision 2026-08-05): the only APE-RV execution this stage gets is the counterpart
campaign, which runs *after* both repositories are complete and measures coverage outcomes rather
than parse equivalence — a gate that runs after the cutover licenses a merge, not a deletion.

So the green above is equality **over cases someone thought of**. The two breadth facts it leans on
instead, stated here so the archive cannot be read as if a corpus gate had run:

1. **The generator has met real-world variety, and that half was executed.** `gh96` tasks 7.1/7.2
   derived all **345** producer documents with no crash and no refusal; the totals (flagged widgets
   **3,733 → 4,965** under the relocated rules) are recorded in `gh96`'s `specs/aperv/spec.md` and
   were independently reproduced by `gh97` group 3 while computing the G3 displacement.
2. **Real-application variety on the jar side is deferred to `gh97`'s campaign**, whose smoke
   asserts `MOP_DATA status=loaded` with `formatVersion=1` and a non-empty `sourceDigest` on every
   MOP arm, plus a MOP boost firing (`gh97` task 7.2a).

What was never demonstrated, and now never will be, is the **jar-side comparison on a real
application nobody wrote a synthetic for**. Its only standing net is `gh96`'s permanent Python suite
(task 2.7's named test per relocated semantics), which outlives this gate by design — this one is
deleted in task 5.4, along with the synthetic full-JSON fragments, because a full-JSON document in
the test tree after the cutover is an input no shipped code path can read.
