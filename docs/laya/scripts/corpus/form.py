"""Form dependency with stronger outcomes (act_change, new_state) instead of the state-hash 'effect'."""
import json, sys
from collections import defaultdict
from stats import VALUE, INPUT
res = defaultdict(lambda: [0, 0, 0, 0])
touched, cur = set(), None
for line in open(sys.argv[1]):
    r = json.loads(line)
    cs = r["candidates"]
    ch = next((c for c in cs if c["aid"] == r["chosen"]), None)
    if (r["trace"], r["app"], r["activity"]) != cur:
        touched, cur = set(), (r["trace"], r["app"], r["activity"])
    key = lambda c: (c.get("cls"), c.get("rid"), tuple(c.get("bounds", ())))
    vw = {key(c) for c in cs if any(v in c.get("cls", "") for v in VALUE)}
    if ch and "effect" in r and ch["type"] == "MODEL_CLICK" and "Button" in ch.get("cls", "") and not any(v in ch.get("cls", "") for v in VALUE):
        if vw:
            frac = len(vw & touched) / len(vw)
            b = "none" if frac == 0 else ("all" if frac == 1 else "partial")
        else:
            b = "no_value_widgets"
        x = res[b]; x[0] += 1; x[1] += r["effect"]; x[2] += r["act_change"]; x[3] += r["new_state"]
    if ch and any(v in ch.get("cls", "") for v in VALUE):
        touched.add(key(ch))
for b, (n, e, a, s) in sorted(res.items()):
    print(f"{b:18s} n={n:7d} effect={e/n:.3f} act_change={a/n:.3f} new_state={s/n:.3f}")
