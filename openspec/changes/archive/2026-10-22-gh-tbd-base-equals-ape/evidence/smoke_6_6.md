# Smoke 6.6 (2026-10-22)

Jar built from `17f9c0fd` (`RUN_START.build.sha` = `17f9c0f`), run through rv-experiment on the RVSec AVD with
`aperv:base,aperv:as_mop`, `com.manimarank.spell4wiki_21` (E6 corpus), 180 s, one repetition. Both runs ended on
`timeout`.

| Marker | base | as_mop |
|---|---|---|
| `APE_FIXES` in `features` | no | yes |
| `PIPELINE.stages` | `[SataChain]` | `[Budget, Restart, MopLauncher, ComponentTrigger, MopCoin, SataChain]` |
| steps | 201 | 160 |
| `clearChildren DOM prune aborted` | 0 | 0 |
| `Too many nodes in WebView` | 20 | 4 |
| `Error to fetch bounds.` (display-centre click) | 1 | 0 |
| `off-screen action dropped` | 0 | 1 |
| `waitForActivity exceeded` | 0 | 0 (not exercised) |
| `Fuzzing: generate pinch or zoom.` lines | 1 | 3 |
| `pinch` in `dec.fz` | 0 (dropped) | 3 (sent) |
| steps with `dec.fz` | 2 | 5 |
| restarts (`dec.restart`, `RUN_END.restarts`) | 0 | 1 periodic, stage path |

The post-selection restart did not fall due within 180 s in `base`; it is covered by `PostSelectionRestartTest`.
The five `Exception` lines of `as_mop` are one app crash (`RecordAudioActivity` started by the launcher without its
extras), recorded by APE's crash handler.

Item 3 and item 11 markers did not occur in either run. The campaign smoke `e03igg5smk` (jar `9789e669`) covered item 3:
`too many GUI trees` appeared only in `as_mop`.
