"""Task 17.2: an upper bound on the retirement counts of a `distance` run (design D19).

RUN
  python3 -I -B smoke_17_2_retire_bound.py <artifact.mop.json> <trace>

For each distinct (activity, widget id, action type) pick with a positive `dec.mop` it counts the
targets at d* of the widget's id-key pairs (for a click with no `click` key, the click aggregate).
A stamped pick (`mops=s`) reads its handler's pairs instead, which the trace does not name and
which are a subset of the event's reach, so the count is an upper bound for those picks.
"""
import json, re, sys, collections
art = json.load(open(sys.argv[1])); acts = {}; seen = set(); cnt = collections.Counter(); rows = []
EV = {"MODEL_CLICK": "click", "MODEL_LONG_CLICK": "longclick"}
for line in open(sys.argv[2], errors="replace"):
    if not line.startswith("{"): continue
    r = json.loads(line)
    if r.get("type") == "ACT": acts[r["id"]] = r["name"]; continue
    dec = r.get("dec")
    if not dec or dec.get("mop", 0) <= 0: continue
    m = re.search(r"@(MODEL_\w+?)class=.*?resource-id=[\w.]+:id/(\w+)", dec["a"])
    if not m: continue
    key = (acts[r["act"]], m.group(2), m.group(1))
    if key in seen: continue
    seen.add(key)
    w = art["widgets"].get(key[0], {}).get(key[1], {})
    dist = w.get("dist", {})
    ev = EV.get(key[2], key[2])
    pairs = dist.get(ev) or ([] if ev != "click" else [p for e, ps in dist.items() if e not in ("longclick","itemlongclick","implicitcreatecontextmenu","scroll") for p in ps])
    if not pairs: rows.append((key, dec.get("mops"), "no id-key pairs")); continue
    ds = min(d for _, d in pairs); ts = sorted({i for i, d in pairs if d == ds})
    for i in ts: cnt[i] += 1
    rows.append((key, dec.get("mops"), "d*=%d targets=%s" % (ds, ts)))
for row in rows: print(row[0][0].rsplit(".",1)[-1], row[0][1], row[0][2], "mops=%s" % row[1], row[2])
print("per-target first-interaction count (upper bound):", dict(cnt.most_common()))
