"""Paired: the same button (app, activity, signature) clicked with no value widget touched vs with some touched."""
import json, sys
from collections import defaultdict
from stats import VALUE
key = lambda c: (c.get("cls"), c.get("rid"), tuple(c.get("bounds", ())))
tab = defaultdict(lambda: {"none": [0, 0, 0], "some": [0, 0, 0]})
touched, cur = set(), None
for line in open(sys.argv[1]):
    r = json.loads(line)
    cs = r["candidates"]
    ch = next((c for c in cs if c["aid"] == r["chosen"]), None)
    if (r["trace"], r["app"], r["activity"]) != cur:
        touched, cur = set(), (r["trace"], r["app"], r["activity"])
    vw = {key(c) for c in cs if any(v in c.get("cls", "") for v in VALUE)}
    if ch and vw and "effect" in r and ch["type"] == "MODEL_CLICK" and "Button" in ch.get("cls", "") and not any(v in ch.get("cls", "") for v in VALUE):
        b = "some" if vw & touched else "none"
        x = tab[(r["app"], r["activity"], key(ch), ch.get("text", ""))][b]
        x[0] += 1; x[1] += r["act_change"]; x[2] += r["new_state"] or r["act_change"]
    if ch and any(v in ch.get("cls", "") for v in VALUE):
        touched.add(key(ch))
both = [v for v in tab.values() if v["none"][0] and v["some"][0]]
def rate(b, i): return sum(v[b][i] / v[b][0] for v in both) / len(both)
print(f"buttons observed in both conditions: {len(both)} (of {len(tab)})")
print(f"mean act_change: none={rate('none',1):.3f} some={rate('some',1):.3f}")
print(f"mean new_state|act_change: none={rate('none',2):.3f} some={rate('some',2):.3f}")
up = sum(v['some'][1]/v['some'][0] > v['none'][1]/v['none'][0] for v in both); dn = sum(v['some'][1]/v['some'][0] < v['none'][1]/v['none'][0] for v in both)
print(f"buttons where act_change higher after touching: {up}; lower: {dn}; equal: {len(both)-up-dn}")
# examples with the biggest positive gap
ex = sorted(tab.items(), key=lambda kv: -((kv[1]['some'][1]/kv[1]['some'][0] if kv[1]['some'][0] else 0) - (kv[1]['none'][1]/kv[1]['none'][0] if kv[1]['none'][0] else 1)))[:8]
for k, v in ex: print("  ", k[0], k[1].rsplit('.',1)[-1], k[3][:20], v)
