"""Compare candidate training labels on the own corpus (steps.jsonl).

Labels per executed step (chosen action only):
  effect      target state key != source state key (abstract state hash changed)
  act_change  target activity != source activity
  new_state   target state never seen before in this run (history-dependent)
  new_act     target activity never seen before in this run (history-dependent)
  mop_act     target activity is MOP-reachable (static analysis) and differs from source
  escape_ok   act_change and not a MODEL_BACK (forward navigation)

For each label we measure:
  base rate; rate on 1st vs later execution of the same (run, state, action);
  consistency over repeated executions of the same (app, state, action) across all runs
  (share of keys whose executions all agree, vs the agreement expected from the base rate);
  contrast: share of states (>=2 actions) where the label differs between actions;
  shortcut AUROC of trivial predictors (unvisited, is_back, is_menu, has_text, mop flag, class Button).
"""
import json
import sys
from collections import defaultdict

LABELS = ("effect", "act_change", "new_state", "new_act", "mop_act", "escape_ok")
INPUT = ("EditText", "AutoCompleteTextView", "SearchView")


def sig(c):
    return (c["type"], c.get("cls", ""), c.get("rid", ""),
            "" if any(i in c.get("cls", "") for i in INPUT) else c.get("text", ""), tuple(c.get("bounds", ())))


def auroc(pos, neg):
    # pos/neg are counts per predictor value (0/1): AUROC for a binary predictor
    tp, fn = pos[1], pos[0]
    fp, tn = neg[1], neg[0]
    P, N = tp + fn, fp + tn
    if not P or not N:
        return float("nan")
    return (tp / P) * (tn / N) + 0.5 * ((tp / P) * (fp / N) + (fn / P) * (tn / N))


def main(path):
    # pass 1: MOP activities per app
    mop_acts = defaultdict(set)
    for line in open(path):
        r = json.loads(line)
        if r.get("activity_mop"):
            mop_acts[r["app"]].add(r["activity"])
    # pass 2
    base = defaultdict(lambda: [0, 0])
    first = defaultdict(lambda: [0, 0])
    later = defaultdict(lambda: [0, 0])
    exec_tab = defaultdict(lambda: defaultdict(list))   # label -> (app,state,sig) -> [values]
    state_tab = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))  # label -> (app,state) -> sig -> vals
    shortcut = defaultdict(lambda: defaultdict(lambda: {1: [0, 0], 0: [0, 0]}))  # label->pred->{y:[x0,x1]}
    seen_run = {}
    cur = None
    for line in open(path):
        r = json.loads(line)
        if "effect" not in r:
            continue
        ch = next((c for c in r["candidates"] if c["aid"] == r["chosen"]), None)
        if ch is None:
            continue
        run = (r["trace"], r["app"])
        if run != cur:
            cur = run
            seen_states, seen_acts, execs = {r["state"]}, {r["activity"]}, defaultdict(int)
        tgt_act = r["target"].split("@")[0]
        y = {
            "effect": r["effect"],
            "act_change": r["act_change"],
            "new_state": r["target"] not in seen_states,
            "new_act": tgt_act not in seen_acts,
            "mop_act": r["act_change"] and tgt_act in mop_acts[r["app"]],
            "escape_ok": r["act_change"] and ch["type"] != "MODEL_BACK",
        }
        seen_states.add(r["target"]); seen_acts.add(tgt_act)
        k = (r["state"], sig(ch))
        execs[k] += 1
        is_first = execs[k] == 1
        preds = {
            "unvisited": int(ch.get("unvisited", False)),
            "is_back": int(ch["type"] == "MODEL_BACK"),
            "is_menu": int(ch["type"] == "MODEL_MENU"),
            "has_text": int(bool(ch.get("text"))),
            "mop_flag": int(bool(ch.get("mop"))),
            "button": int("Button" in ch.get("cls", "")),
        }
        for L, v in y.items():
            v = int(bool(v))
            base[L][0] += 1; base[L][1] += v
            (first if is_first else later)[L][0] += 1
            (first if is_first else later)[L][1] += v
            exec_tab[L][(r["app"], r["state"], sig(ch))].append(v)
            state_tab[L][(r["app"], r["state"])][sig(ch)].append(v)
            for p, x in preds.items():
                shortcut[L][p][v][x] += 1
    print(f"{'label':11s} {'base':>6s} {'1st':>6s} {'later':>6s} {'consist':>8s} {'expect':>7s} {'contrast':>9s}  shortcut AUROC")
    for L in LABELS:
        b = base[L][1] / base[L][0]
        f1 = first[L][1] / max(1, first[L][0])
        f2 = later[L][1] / max(1, later[L][0])
        rep = [v for v in exec_tab[L].values() if len(v) >= 2]
        cons = sum(len(set(v)) == 1 for v in rep) / len(rep)
        # expected all-agree if outcomes were iid Bernoulli(b) with the same repetition counts
        exp = sum(b ** len(v) + (1 - b) ** len(v) for v in rep) / len(rep)
        multi = [s for s in state_tab[L].values() if len(s) >= 2]
        contr = sum(len({round(sum(v) / len(v)) for v in s.values()}) > 1 for s in multi) / len(multi)
        sc = " ".join(f"{p}={auroc(shortcut[L][p][1], shortcut[L][p][0]):.2f}" for p in
                      ("unvisited", "is_back", "is_menu", "has_text", "mop_flag", "button"))
        print(f"{L:11s} {b:6.3f} {f1:6.3f} {f2:6.3f} {cons:8.3f} {exp:7.3f} {contr:9.3f}  {sc}")
    print(f"(repeated keys: {len([v for v in exec_tab['effect'].values() if len(v) >= 2])}; "
          f"states with >=2 actions: {len([s for s in state_tab['effect'].values() if len(s) >= 2])})")


if __name__ == "__main__":
    main(sys.argv[1])
