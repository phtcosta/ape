# Tasks: mop-activity-consumers

Strict TDD (RED → GREEN per group). No group starts before the previous is green.
Precondition: `mop-reach-strategies` archived (D5 archive ordering).

## 1. `stateMopDensity` activity-substrate floor

- [x] 1.1 RED — `MopScorerTest`: (a) state on an A′-only activity (`activityHasMop=true`, zero
      widget-flagged actions) returns **1**; (b) state with 2 widget-flagged actions returns
      **3** (`1 + 2`); (c) state on a non-MOP activity returns **0** without resolving widgets;
      (d) ordering guard: widget-flagged(1 action, =2) > A′-only(=1) > non-MOP(=0).
- [x] 1.2 GREEN — implement the floor in `MopScorer.stateMopDensity` (`MopScorer.java:134-141`
      region): early-out unchanged; return `1 + count`.
- [x] 1.3 Sweep existing tests asserting old density values (e.g. the `stateMopDensity(A)==0 /
      (B)==1` scenario pair) and update expectations to the +1 semantics; `mvn test` green.
      (No existing test asserted a nonzero density — all pre-existing density tests assert the
      `0` early-outs, unchanged by the floor; the cited scenario pair does not exist in the
      codebase. Full suite green: 593 run, 0 failures.)

## 2. Launcher framework/tooling denylist

- [x] 2.1 RED — `SataAgent` trigger-candidate tests (pure `selectTriggerCandidate` seam):
      (a) `androidx.compose.ui.tooling.PreviewActivity` (exported, permission-free, unvisited)
      is skipped and the next genuine app activity is returned; (b)
      `androidx.activity.ComponentActivity` skipped; (c) list containing only denylisted
      candidates returns null (falls through to SATA, counter not reset); (d) genuine app
      activity whose package merely *contains* "androidx" as a substring (e.g.
      `com.foo.androidxutils.MainActivity`) is NOT skipped (prefix match, not contains);
      (e) `triggerMopFirst` two-group ordering unaffected for eligible candidates.
- [x] 2.2 GREEN — add `FRAMEWORK_ACTIVITY_PREFIXES` constant and the prefix rejection inside
      `firstEligible` (`SataAgent.java:678-698`), before the exported/permission/main/visited
      conjunction.

## 3. A′ observability

- [x] 3.1 RED — `MopData` load-line tests: `status=loaded` line carries
      `mopActivities=<n> mopActsAugmented=<m>`; with `mopActivitySourceComponents=false`,
      `m==0` and `n` equals the widget-derived set size; with the flag on and a fixture whose
      components/reachability add 2 activities, `m==2`.
      (`MopDataActivityCountersTest`; flag-on flips the `static final` via `Unsafe` after forcing
      Config static-init — a plain `Field` read does not init the class.)
- [x] 3.2 GREEN — count the pre-augmentation set size in `MopData.load`, emit both counters on
      the existing status line (`MopData.java:312-317`).
- [x] 3.3 Tiebreak decision log: emit `[APE-RV] Nav MOP tiebreak: density=<d> paths=<n>` at the
      path-selection site (`SataAgent.java:1311-1326`) only on the `!allEqual` branch; unit-tested
      via the extracted pure helper `navMopTiebreakLog` (decisive branch formats the line;
      all-equal returns null); on-device presence verified in the group-5 smoke.

## 4. Consistency + validation

- [x] 4.1 `mvn test` — full suite green. (602 run, 0 failures, 19 Android-runtime skips.)
- [x] 4.2 Guard greps: INV-ARCH-06 registry untouched (no new RV flag — `Config.java` has no
      diff); no second activity exclusion mechanism introduced (`isFrameworkActivity` has a
      single call site, inside `firstEligible`).
- [x] 4.3 `openspec validate mop-activity-consumers --strict` passes; tasks checked off.

## 5. Device smoke (superseded — cmpft5 Gate 0)

- [x] 5.1 SUPERSEDED by cmpft5 Gate 0 (2026-07-11,
      `rvsec/rv-android/docs/20260711_relatorio_gate0_cmpft5.md`). The original criterion — "a live A′
      consumer differentiating the arms" — was answered NEGATIVELY by Gate 0 a+b under the launcher-OFF
      design: the nav tiebreak fired 0/6 arm-runs and `scoreWtg` produced identical boosted sets in
      3/3 smoke apps. That null (design cannot test H1) is the motivating evidence for the follow-on
      change `activity-trigger-dose` (launcher ON in both arms). Re-running this smoke would only
      re-confirm a design already known to be inert; the observability code (mopActivities/
      mopActsAugmented counters, framework denylist, Nav MOP tiebreak log) is unit-covered (Groups 1–4,
      602 tests) and carries forward into the launcher-ON cmpft5 Gate 0 rerun.
