"""Pass 2: statistics over data/rico/steps.jsonl.gz (from extract.py). Prints the report tables.

Run: uv run python rico/analyze.py
"""
import gzip
import json
import math
import random
import statistics as st
import sys
from collections import Counter, defaultdict

sys.path.insert(0, "/home/pedro/tmp/laya/rico")
recs = [json.loads(l) for l in gzip.open("/home/pedro/tmp/laya/data/rico/steps.jsonl.gz", "rt")]
P = lambda a, b: f"{a}/{b} = {a / b:.3f}" if b else f"{a}/0"


def q(xs, p):
    xs = sorted(xs)
    return xs[min(int(p * len(xs)), len(xs) - 1)]


apps = {r["app"] for r in recs}
traces = {(r["app"], r["trace"]) for r in recs}
print(f"== volume: apps={len(apps)} traces={len(traces)} UIs(records)={len(recs)}")
tl = Counter((r["app"], r["trace"]) for r in recs)
print(f"UIs/trace median={st.median(tl.values())} p90={q(list(tl.values()), .9)} max={max(tl.values())}"
      f" single-UI traces={sum(v == 1 for v in tl.values())}")
tpa = Counter(a for a, t in traces)
print(f"traces/app: {Counter(tpa.values()).most_common(5)}")
steps = [r for r in recs if r["next"] is not None]
print(f"transitions (UI with a successor in trace)={len(steps)}; last-UI gestures (no observed outcome)="
      f"{len(recs) - len(steps)}")
gaps = [r["next"] - r["ui"] for r in steps]
print(f"UI-number gap to successor: median={st.median(gaps)} p10={q(gaps, .1)} p90={q(gaps, .9)} "
      f"gap==1: {P(sum(g == 1 for g in gaps), len(gaps))}")

print("\n== gestures")
gt = Counter(r["gt"] for r in recs)
print(dict(gt))
print(f"npts: 1={sum(r['npts'] == 1 for r in recs)} 2-5={sum(2 <= r['npts'] <= 5 for r in recs)} "
      f">5={sum(r['npts'] > 5 for r in recs)} 0={sum(r['npts'] == 0 for r in recs)}")
print(f"multi-point gestures classified as tap (jitter<0.03): {sum(r['npts'] > 1 and r['gt'] == 'tap' for r in recs)}")
print(f"root bounds: {Counter(str(r['root']) for r in recs).most_common(4)}")
taps = [r for r in recs if r["gt"] == "tap"]
hit = [r for r in taps if r["gold"] is not None]
print(f"taps hitting an actionable candidate: {P(len(hit), len(taps))}; "
      f"miss but inside some visible node: {sum(r.get('any_node', False) for r in taps if r['gold'] is None)}")
sw = [r for r in recs if r["gt"].startswith("swipe")]
print(f"swipes starting on a scrollable: {P(sum(r['gold'] is not None for r in sw), len(sw))}")
gk = Counter(r["cf"][r["gold"]][0] for r in hit)
print(f"kind of hit candidate: {dict(gk)}")
ty = [r for r in hit if r["cf"][r["gold"]][0] == "type"]
print(f"taps on EditText: {len(ty)}; text changed in successor VH: {P(sum(bool(r.get('typed')) for r in ty if 'typed' in r), sum('typed' in r for r in ty))}; "
      f"keyboard deployed on UI: {P(sum(r['kb'] for r in recs), len(recs))}")

print("\n== candidates K (APE-like)")
Ks = [r["K"] for r in recs]
print(f"K median={st.median(Ks)} mean={st.mean(Ks):.1f} p90={q(Ks, .9)} p99={q(Ks, .99)} "
      f"K==0: {P(sum(k == 0 for k in Ks), len(Ks))} K<=20: {P(sum(k <= 20 for k in Ks), len(Ks))} "
      f"K+back<=20: {P(sum(k + 1 <= 20 for k in Ks), len(Ks))}")
kinds = Counter(f[0] for r in recs for f in r["cf"])
srcs = Counter(f[2] for r in recs for f in r["cf"])
tot = sum(srcs.values())
print(f"candidate kinds: {dict(kinds)}")
print("candidate label source: " + ", ".join(f"{k}={v / tot:.3f}" for k, v in srcs.most_common()))
gsrc = Counter(r["cf"][r["gold"]][2] for r in hit)
print("chosen label source:    " + ", ".join(f"{k}={v / len(hit):.3f}" for k, v in gsrc.most_common()))

print("\n== outcomes (successor UI in filtered trace)")
for lvl, a, b in (("activity", "act", "nact"), ("struct sig", "sig", "nsig"), ("text sig", "sigt", "nsigt")):
    ok = [r for r in steps if b in r]
    print(f"successor differs by {lvl}: {P(sum(r[a] != r[b] for r in ok), len(ok))}")
# novelty within trace (struct sig not seen earlier in the trace)
bytrace = defaultdict(list)
for r in recs:
    bytrace[(r["app"], r["trace"])].append(r)
nov, tot_n, rev = 0, 0, 0
for k, rs in bytrace.items():
    rs.sort(key=lambda r: r["ui"])
    seen = set()
    for r in rs:
        seen.add(r["sig"])
        if "nsig" in r:
            tot_n += 1
            nov += r["nsig"] not in seen
    sigs = [r["sig"] for r in rs]
    rev += len(sigs) - len(set(sigs))
print(f"successor is a NEW struct-sig within trace: {P(nov, tot_n)}; UIs that revisit a sig: {P(rev, len(recs))}")

print("\n== human choice vs candidate base rate (taps with a hit, K>=2)")
H = [r for r in hit if r["K"] >= 2]
for fi, name in ((1, "class"), (2, "label src"), (3, "y quintile"), (4, "area bin"), (0, "kind")):
    ch = Counter(r["cf"][r["gold"]][fi] for r in H)
    base = Counter(f[fi] for r in H for f in r["cf"])
    nb = sum(base.values())
    print(f"  {name:10s} " + "  ".join(f"{k}:{ch[k] / len(H):.2f}/{base[k] / nb:.2f}(x{(ch[k] / len(H)) / (base[k] / nb):.1f})"
                                     for k, _ in base.most_common(9)))
rk = [r["gold"] / (r["K"] - 1) for r in H]
print(f"  DFS-order rank of choice (0=first,1=last) mean={st.mean(rk):.2f} (uniform .50)")
print(f"  random top-1 over these steps = mean(1/K) = {st.mean(1 / r['K'] for r in H):.3f}; median K={st.median(r['K'] for r in H)}")

# simple heuristics and a feature-lift model trained on 80% apps, tested on 20%
rng = random.Random(0)
test_apps = {a for a in apps if rng.random() < .2}
tr = [r for r in H if r["app"] not in test_apps]
te = [r for r in H if r["app"] in test_apps]
ch, base = Counter(), Counter()
for r in tr:
    for i, f in enumerate(r["cf"]):
        base[tuple(f)] += 1
        ch[tuple(f)] += i == r["gold"]
def sc(f):
    f = tuple(f)
    return math.log((ch[f] + .5) / (base[f] + 5))
def top1(pick):
    return sum(pick(r) == r["gold"] for r in te) / len(te)
def topk(k):
    n = 0
    for r in te:
        s = sorted(range(r["K"]), key=lambda i: -sc(r["cf"][i]))
        n += r["gold"] in s[:k]
    return n / len(te)
print(f"  held-out apps n={len(te)}: random={st.mean(1 / r['K'] for r in te):.3f} "
      f"largest={top1(lambda r: max(range(r['K']), key=lambda i: r['cf'][i][4])):.3f} "
      f"feature-lift top1={top1(lambda r: max(range(r['K']), key=lambda i: sc(r['cf'][i]))):.3f} "
      f"top3={topk(3):.3f} (random top3={st.mean(min(3, r['K']) / r['K'] for r in te):.3f})")
# K<=20 subset
te20 = [r for r in te if r["K"] <= 20]
print(f"  held-out K<=20 n={len(te20)}: random={st.mean(1 / r['K'] for r in te20):.3f} "
      f"lift top1={sum(max(range(r['K']), key=lambda i: sc(r['cf'][i])) == r['gold'] for r in te20) / len(te20):.3f}")

print("\n== consistency across traces (same app + same struct sig, different traces)")
by = defaultdict(list)
for r in H:
    by[(r["app"], r["sig"])].append(r)
agree = chance = pairs = 0
for k, rs in by.items():
    for i in range(len(rs)):
        for j in range(i + 1, len(rs)):
            a, b = rs[i], rs[j]
            if a["trace"] == b["trace"]:
                continue
            pairs += 1
            agree += a["ck"][a["gold"]] == b["ck"][b["gold"]]
            chance += 1 / max(a["K"], b["K"])
print(f"  cross-trace pairs={pairs} agreement={agree / max(pairs, 1):.3f} chance~{chance / max(pairs, 1):.3f}")
same = rep = 0
for k, rs in bytrace.items():
    done = defaultdict(set)
    for r in rs:
        if r["gold"] is None:
            continue
        key = r["ck"][r["gold"]]
        if done[r["sig"]]:
            same += 1
            rep += key in done[r["sig"]]
        done[r["sig"]].add(key)
print(f"  within-trace revisits of a sig: {same}; repeated same action: {P(rep, same)}")

print("\n== outcome of human choice vs other candidates (cross-trace outcome table, struct sig)")
tab = defaultdict(list)
for r in steps:
    if r["gold"] is not None and "nsig" in r:
        tab[(r["app"], r["sig"], r["ck"][r["gold"]])].append(r["nsig"] != r["sig"])
hum, oth, n = [], [], 0
for r in steps:
    if r["gold"] is None or "nsig" not in r:
        continue
    others = [tab[(r["app"], r["sig"], k)] for i, k in enumerate(r["ck"]) if i != r["gold"] and (r["app"], r["sig"], k) in tab]
    if not others:
        continue
    n += 1
    hum.append(r["nsig"] != r["sig"])
    oth.append(st.mean(st.mean(o) for o in others))
print(f"  steps with >=1 other candidate's outcome observed: {n} of {len(steps)}; human changed={st.mean(hum):.3f} "
      f"others (observed) changed={st.mean(oth):.3f}")
print(f"  test apps listed: {len(test_apps)}")
