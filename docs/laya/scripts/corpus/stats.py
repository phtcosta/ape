"""Corpus measurements over steps.jsonl (output of convert.py)."""
import json
import statistics
import sys
from collections import Counter, defaultdict

INPUT = ("EditText", "AutoCompleteTextView", "SearchView")
VALUE = INPUT + ("CheckBox", "RadioButton", "Switch", "ToggleButton", "Spinner", "SeekBar", "RatingBar",
                 "NumberPicker", "DatePicker", "TimePicker", "CompoundButton", "CheckedTextView")


def sig(c):
    """Run-independent action identity: type + class + resource-id + text + bounds."""
    return (c["type"], c.get("cls", ""), c.get("rid", ""), c.get("text", "") if not any(i in c.get("cls", "") for i in INPUT) else "", tuple(c.get("bounds", ())))


def main(path, eval_apks=None):
    n = 0
    apps, traces, arms, strat = set(), set(), Counter(), Counter()
    K = []
    lab = Counter()
    mop_steps = mop_chosen = mop_any_known = 0
    out = Counter()
    by_strat = defaultdict(lambda: [0, 0])
    by_unv = defaultdict(lambda: [0, 0])
    by_mop = defaultdict(lambda: [0, 0])
    table = defaultdict(lambda: defaultdict(lambda: [0, 0]))  # (app,state) -> sig -> [n, effect]
    form = defaultdict(lambda: [0, 0])  # filled? -> [clicks on Button in states with inputs, effect]
    form2 = defaultdict(lambda: [0, 0])  # generic: fraction of value widgets manipulated since arrival
    touched, cur_trace, cur_act = set(), None, None
    for line in open(path):
        r = json.loads(line)
        n += 1
        apps.add(r["app"]); traces.add(r["trace"]); arms[r["arm"]] += 1; strat[r["strategy"]] += 1
        cs = r["candidates"]
        K.append(len(cs))
        for c in cs:
            if c["type"] in ("MODEL_BACK", "MODEL_MENU"):
                continue
            lab["text" if c.get("text") else ("rid" if c.get("rid") else "none")] += 1
        ch = next((c for c in cs if c["aid"] == r["chosen"]), None)
        if r["trace"] != cur_trace or r["activity"] != cur_act:
            touched, cur_trace, cur_act = set(), r["trace"], r["activity"]
        vw = {(c.get("cls"), c.get("rid"), tuple(c.get("bounds", ()))) for c in cs if any(v in c.get("cls", "") for v in VALUE)}
        if ch and vw and ch["type"] == "MODEL_CLICK" and "Button" in ch.get("cls", "") and not any(v in ch.get("cls", "") for v in VALUE) and "effect" in r:
            frac = len(vw & touched) / len(vw)
            b = "none" if frac == 0 else ("all" if frac == 1 else "partial")
            form2[b][0] += 1; form2[b][1] += r["effect"]
        if ch and any(v in ch.get("cls", "") for v in VALUE):
            touched.add((ch.get("cls"), ch.get("rid"), tuple(ch.get("bounds", ()))))
        if "mop" in (cs[0] if cs else {}):
            mop_any_known += 1
            if any(c.get("mop") for c in cs):
                mop_steps += 1
                if ch and ch.get("mop"):
                    mop_chosen += 1
        if "effect" not in r or ch is None:
            continue
        e = r["effect"]
        out["with_outcome"] += 1; out["effect"] += e; out["act_change"] += r["act_change"]; out["new_state"] += r["new_state"]
        by_strat[r["strategy"]][0] += 1; by_strat[r["strategy"]][1] += e
        by_unv[ch["unvisited"]][0] += 1; by_unv[ch["unvisited"]][1] += e
        by_mop[bool(ch.get("mop"))][0] += 1; by_mop[bool(ch.get("mop"))][1] += r["act_change"]
        t = table[(r["app"], r["state"])][sig(ch)]
        t[0] += 1; t[1] += e
        inputs = [c for c in cs if any(i in c.get("cls", "") for i in INPUT)]
        if inputs and ch["type"] == "MODEL_CLICK" and "Button" in ch.get("cls", ""):
            filled = any(c.get("text") for c in inputs)
            form[filled][0] += 1; form[filled][1] += r["act_change"] or e
    print(f"records={n} traces={len(traces)} apps={len(apps)}")
    print("arms:", arms.most_common())
    print("strategies:", strat.most_common(8))
    K.sort()
    print(f"K candidates/step: median={statistics.median(K)} p90={K[int(.9*len(K))]} max={K[-1]} frac<=20={sum(k<=20 for k in K)/len(K):.2f} frac<=40={sum(k<=40 for k in K)/len(K):.2f}")
    tot = sum(lab.values())
    print("candidate labels:", {k: round(v / tot, 2) for k, v in lab.items()})
    print(f"MOP: steps with >=1 mop candidate={mop_steps/max(1,mop_any_known):.2f}; chosen mop when available={mop_chosen/max(1,mop_steps):.2f}")
    w = out["with_outcome"]
    print(f"outcomes (n={w}): effect={out['effect']/w:.2f} act_change={out['act_change']/w:.2f} new_state={out['new_state']/w:.2f}")
    print("effect by chosen unvisited:", {k: f"{v[1]/v[0]:.2f} (n={v[0]})" for k, v in by_unv.items()})
    print("act_change by chosen mop:", {k: f"{v[1]/v[0]:.2f} (n={v[0]})" for k, v in by_mop.items()})
    print("effect by strategy:", {k: f"{v[1]/v[0]:.2f} (n={v[0]})" for k, v in sorted(by_strat.items(), key=lambda x: -x[1][0])[:8]})
    # per-state contrastive tables
    st = len(table)
    multi = [s for s, v in table.items() if len(v) >= 2]
    contrast = [s for s in multi if len({(v[1] / v[0]) > 0.5 for v in table[s].values()}) > 1]
    acts = [len(table[s]) for s in multi]
    print(f"abstract states={st}; with >=2 distinct actions tried={len(multi)} (median actions {statistics.median(acts) if acts else 0}); "
          f"contrastive (some actions effective, some not)={len(contrast)}; "
          f"(state,action) pairs in contrastive states={sum(len(table[s]) for s in contrast)}")
    capps = Counter(s[0] for s in contrast)
    print(f"contrastive states per app: apps={len(capps)} median={statistics.median(capps.values()) if capps else 0}")
    print("form dependency (Button click in state with input fields): ",
          {("filled" if k else "empty"): f"effect={v[1]/v[0]:.2f} (n={v[0]})" for k, v in form.items()})
    print("generic form state (Button click on screen with value widgets; effect rate by share manipulated since arrival):",
          {k: f"{v[1]/v[0]:.2f} (n={v[0]})" for k, v in form2.items()})
    if eval_apks:
        ev = {l.strip() for l in open(eval_apks) if l.strip()}
        ov = {a for a in apps if a in ev or a.removesuffix(".apk") in ev}
        print(f"overlap with evaluation APK list: {len(ov)} of {len(apps)} corpus apps ({len(ev)} in eval list)")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
