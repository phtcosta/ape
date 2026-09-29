"""Paired analysis Laya vs prior: bootstrap CI over apps, MOP (level-3) ranking, and prior+laya blend."""
import json, random, sys
from collections import defaultdict
sys.path.insert(0, "/home/pedro/tmp/laya/train")
import evaluate as E
ck = sys.argv[1]
prior = E.make_prior(); laya = E.make_laya("laya:" + ck)
for split in ("val", "test"):
    data = E.rows(split)
    P = [prior(r) for r in data]; Lp = [laya(r) for r in data]
    def blend(w):
        out = []
        for p, l in zip(P, Lp):
            mx = max(p.values()) or 1
            out.append({k: w * l[k] + (1 - w) * p[k] / 3.0 for k in l})
        return out
    sets = {"prior": P, "laya": Lp, "blend.5": blend(.5)}
    per = {n: defaultdict(list) for n in sets}
    mop = {n: [0, 0] for n in sets}
    for i, r in enumerate(data):
        for n, S in sets.items():
            per[n][r["app"]].append(E.score(r, S[i]))
            lv = r["levels"]
            if max(lv.values()) >= 2.5 and min(lv.values()) < 2.5:  # state with a MOP-activity option and a non-MOP one
                top = max(S[i], key=S[i].get)
                mop[n][0] += 1; mop[n][1] += lv[top] >= 2.5
    apps = sorted(per["prior"])
    def macro(n, idx, sample):
        return sum(sum(m[idx] for m in per[n][a]) / len(per[n][a]) for a in sample) / len(sample)
    print(f"== {split}: {len(data)} items, {len(apps)} apps")
    for n in sets:
        print(f"  {n:8s} best@1={macro(n,0,apps):.3f} pairacc={macro(n,2,apps):.3f}  MOP-top1 (states with a level-3 option)={mop[n][1]/max(1,mop[n][0]):.3f} (n={mop[n][0]})")
    rng = random.Random(7)
    for n in ("laya", "blend.5"):
        for idx, name in ((0, "best@1"), (2, "pairacc")):
            d = []
            for _ in range(2000):
                s = [rng.choice(apps) for _ in apps]
                d.append(macro(n, idx, s) - macro("prior", idx, s))
            d.sort()
            print(f"  {n} - prior {name}: {macro(n,idx,apps)-macro('prior',idx,apps):+.3f}  95% CI [{d[50]:+.3f}, {d[1949]:+.3f}]")
