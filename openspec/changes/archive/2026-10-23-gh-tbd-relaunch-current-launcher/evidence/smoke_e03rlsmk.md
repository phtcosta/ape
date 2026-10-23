# Smoke e03rlsmk (2026-10-23)

Jar built from `a84b77ed` (sha256 `745ac549…4ee9`, `RUN_START.build.sha` = `a84b77e`), bind-mounted into
`rvandroid:0.9.6@ca873ee9`. `aperv:as_mop`, six Fossify apps (calendar, keyboard, math, notes, paint, voicerecorder),
two repetitions, 600 s. All 12 tasks carry `APE_FIXES` in `features`. Per-task table:
`rv-android/data/e03igg_analysis/out/e03rlsmk/check.csv`.

| Marker | 4 tasks with `toggleAppIconColor` | 8 tasks without |
|---|---|---|
| tasks | calendar ×2, paint, notes | the rest |
| `[APE-RV] relaunch launcher` lines | 2, 2, 3, 1 | 0 |
| component named | `…SplashActivity.Grey_black` (startup: `.Green`) | — |
| followed by `Expected activity package … is loaded` | every line | — |
| `We are still waiting for activity loading` | 0 | 0 |
| `waitForActivity exceeded 100 cycles` | 0 | 0 |

Agent restarts after the switch are `EVENT_RESTART@…Grey_black`. No line names an identical reordered list. Contrast:
in e03igg5 every aperv task with the toggle wedged, with 114–3002 wait lines and 11–30 relaunches in `as_mop`. No
`base`/`mop` control was run.
