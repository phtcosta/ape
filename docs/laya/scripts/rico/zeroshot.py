"""Zero-shot Laya (typed-decisions) on Rico rows: top-1 vs gold (human choice) against random and a
feature-lift prior trained on apps disjoint from the evaluated rows.

Run: uv run python rico/zeroshot.py --n 500
"""
import argparse
import gzip
import json
import math
import random
import statistics as st
import time
from collections import Counter

from laya import Router

INSTR = {
    "explore": ("You are testing an Android app and want to reach new screens and functionality. "
                "Which action should be performed next?"),
    "user": "You are using this Android app. Which action would a typical user perform next on this screen?",
}

ap = argparse.ArgumentParser()
ap.add_argument("--rows", default="/home/pedro/tmp/laya/data/rico/rows_sample.jsonl")
ap.add_argument("--n", type=int, default=500)
ap.add_argument("--out", default="/home/pedro/tmp/laya/data/rico/zeroshot_results.jsonl")
a = ap.parse_args()

rows = [json.loads(l) for l in open(a.rows)]
random.Random(3).shuffle(rows)
rows = rows[: a.n]
eval_apps = {r["app"] for r in rows}

# feature-lift prior from extract.py records of OTHER apps
ch, base = Counter(), Counter()
for l in gzip.open("/home/pedro/tmp/laya/data/rico/steps.jsonl.gz", "rt"):
    r = json.loads(l)
    if r["app"] in eval_apps or r["gold"] is None or r["K"] < 2:
        continue
    for i, f in enumerate(r["cf"]):
        base[tuple(f)] += 1
        ch[tuple(f)] += i == r["gold"]
mean_sc = st.mean(math.log((ch[f] + .5) / (base[f] + 5)) for f in base)


def lift(f):
    if f[0] == "back":
        return mean_sc  # back is never observed as gold in Rico: score it neutrally, not -inf
    f = tuple(f)
    return math.log((ch[f] + .5) / (base[f] + 5))


router = Router(device="cuda")
res = Counter()
out = open(a.out, "w")
t0 = time.time()
for r in rows:
    crit = {c["id"]: c["text"] for c in r["candidates"]}
    ids = [c["id"] for c in r["candidates"]]
    lpick = ids[max(range(len(ids)), key=lambda i: lift(r["meta"]["feats"][i]))]
    rec = {"app": r["app"], "ui": r["ui"], "k": len(ids), "gold": r["gold_id"], "lift": lpick}
    for name, instr in INSTR.items():
        q = {"q": {"type": "choice", "instructions": instr, "criteria": crit}}
        try:
            ans = router.predict(r["state"], q, model="typed-decisions", max_len=1024, head_max_len=640)["answers"]["q"]
            pr = ans["probabilities"]
            rank = sorted(pr, key=lambda k: -pr[k]).index(r["gold_id"])
            rec[name] = {"pick": ans["choice"], "p_gold": pr.get(r["gold_id"]), "rank": rank,
                         "back_id": (bid := next(c["id"] for c in r["candidates"] if c["text"] == "press back")),
                         "p_back": pr.get(bid)}
        except Exception as e:
            rec[name] = {"err": str(e)[:100]}
    out.write(json.dumps(rec) + "\n")
out.close()
R = [json.loads(l) for l in open(a.out)]
print(f"n={len(R)} apps={len(eval_apps)} median k={st.median(x['k'] for x in R)} time={time.time() - t0:.0f}s")
print(f"random top1={st.mean(1 / x['k'] for x in R):.3f} top3={st.mean(min(3, x['k']) / x['k'] for x in R):.3f}")
print(f"feature-lift top1={st.mean(x['lift'] == x['gold'] for x in R):.3f}")
for name in INSTR:
    ok = [x for x in R if "err" not in x[name]]
    print(f"laya[{name}] n_ok={len(ok)} top1={st.mean(x[name]['pick'] == x['gold'] for x in ok):.3f} "
          f"top3={st.mean(x[name]['rank'] < 3 for x in ok):.3f} mean p_gold={st.mean(x[name]['p_gold'] for x in ok):.3f} "
          f"(uniform {st.mean(1 / x['k'] for x in ok):.3f}) picks back={st.mean(x[name]['pick'] == x[name]['back_id'] for x in ok):.3f} "
          f"mean p_back={st.mean(x[name]['p_back'] or 0 for x in ok):.3f}")
