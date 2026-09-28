# Laya probes

Setup: uv env, laya 0.3.21, torch 2.14+cu130, RTX 5070 Ti.
Warm call ~22-30 ms, first call 15-23 s (load + compile).

## probe1/probe2: simpletextcrypt screen-001 (lock screen: type 1111, then UNLOCK)
- All 3 checkpoints pick "type into passcode" from the screen text (correct first step).
- A history of "typed 1111" INCREASES p(type) (0.94 -> 0.99): no sense of temporal order.
- A state naming UNLOCK -> click_unlock 0.96; "UNLOCK is broken, leave" -> click_unlock 0.72-0.97 (negation ignored).
- With an empty state the choice flips between checkpoints (prior differs).
=> It behaves as a lexical matcher between state and option text. Mentioning an action in the history
   boosts that action. Anti-repetition must be done by masking options, never by writing history.
- noul: no clear separation; act_probability always 1.0 (as documented).

## Batch results (zero-shot)
### Exp A — grounding with gold (study03 E0, 636 unambiguous targets, instruction names the target)
top-1: N=5 english .71 / typed .74 / multi .45 (random .20, lexical .975); N=10 ~.46; N=20 .20-.38; N=40 ~0 (default head) / .12-.29 (head 768).
all-N: english .58, typed .60, multi .45 vs lexical .96. english 512 overflows on 22 screens.
Confidence is monotone with accuracy (typed: conf>=.4 -> acc .93+). Latency p50 27-29 ms.
### Exp B — replay of 2679 real Qwen3-VL decisions (study03 E5/E5b/E5c)
agreement with Qwen .20 (chance .12); AUROC(p_Laya(qwen pick) -> new_state) .45-.50 = no signal.
Trivial rule "untried" separates outcomes: new_state .19 vs .08.
### Exp C — exploration instruction, 427 screens x 4 shuffles, no gold
same pick in all 4 shuffles: english .40, multi .30, typed .45 -> choice depends on option order.
argmax in first quartile of list: multi .49, english .34, typed .24 (position bias, strongest in multilingual).
text-input over-picked (.10-.13 vs .03 in pool). typed-decisions near-uniform (normalized entropy .92).
cross-checkpoint agreement .40-.57 (chance .21).

## Own corpus (rv-android results, APE-format text traces) — corpus/
convert.py -> corpus/steps.jsonl: 2,042,243 steps, 167 apps (precal set; only 2 overlap with the 163 evaluation APKs).
Arms: aperv:sata_mop 1.79M, ape 247k, small LLM/MOP arms. Every step has the full candidate list.
K median 8, p90 23, 88% <= 20. Labels: text .50, rid .35, none .15. 19% of steps have a MOP candidate.
Outcomes: effect(state hash changed) .53 (noisy), act_change .08, new_state .11. act_change|chosen MOP .13 vs .07.
126,883 abstract states; 100,652 with >=2 actions tried (median 5); 71,462 contrastive; 593,612 (state,action) pairs; 153 apps.
Form dependency: NOT visible in aggregate. Paired same-button test (1,759 buttons seen with and without value widgets
touched): act_change .168 vs .170; 99 up / 83 down / 1,577 equal. Exists in specific cases (e.g. "Save" in profile setup),
rare in this corpus (random fuzz text likely fails validation; act_change misses in-activity success).
The 3-trace sample (71% vs 40%) was misleading.
Value visibility in traces: EditText text yes; checked/spinner selection/range value no.

## Label analysis (corpus/labels.py, after fixing per-step edge attribution in convert.py) — labels_out.txt
new_state: 1st exec .149 vs later .012; consistency .71 = chance .74; predicted by 'unvisited' (AUROC .77) -> history, SATA's job. Rejected.
new_act: .005, no contrast. Rejected.
effect: .53, consistency .91 vs chance .29, contrast .71, no trivial shortcut -> stable action property, but broad.
act_change .074 / escape_ok .058 / mop_act .051: consistency .98-.99, contrast .19-.30, shortcut <= .67.
Recommendation F: graded level per tried action (0 none, 1 in-activity effect, 2 other activity non-back,
3 MOP-reachable activity), soft gold over tried actions per state; ~72k contrastive states / 594k pairs / 153 apps.
G (future level 4): MOP method/violation via logcat join.
