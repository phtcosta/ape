"""Evaluate a selector on data/{split}_bal.jsonl (label F). Metrics are macro-averaged over apps.

  best@1   argmax option is among the best-level options of the state
  lvl@1    mean level of the argmax option, normalised: (lvl - min) / (max - min) of that state
  pairacc  over option pairs with different levels, share ordered like the levels (ties in score = 0.5)
  ece      calibration of answer_confidence against best@1 (15 bins)

Selectors: random, prior (mean level by verb+class learned on train), laya (a checkpoint path or a built-in name).
    uv run python train/evaluate.py test laya:typed-decisions
    uv run python train/evaluate.py test laya:/home/pedro/tmp/laya/train/out/v1
    uv run python train/evaluate.py test prior random
"""
import json
import random
import sys
from collections import defaultdict

D = "/home/pedro/tmp/laya/train/data"


def rows(split):
    return [json.loads(l) for l in open(f"{D}/{split}_bal.jsonl")]


def verb_class(desc):
    head = desc.split(" (id=")[0].split(" | ")[0]
    parts = head.split(" ")
    return " ".join(parts[:2]) + (" mop" if "| mop" in desc else "")


def make_prior():
    s, n = defaultdict(float), defaultdict(int)
    for r in rows("train"):
        for k, d in r["questions"]["next"]["criteria"].items():
            vc = verb_class(d)
            s[vc] += r["levels"][k]
            n[vc] += 1
    table = {k: s[k] / n[k] for k in s}
    return lambda r: {k: table.get(verb_class(d), 0.5) for k, d in r["questions"]["next"]["criteria"].items()}


def make_laya(spec):
    from laya import Router
    name = spec.split(":", 1)[1]
    if name.startswith("/"):
        router = Router(models={"english": name, "multilingual": name}, default="english", device="cuda")
        model = "english"
    else:
        router, model = Router(device="cuda"), name

    def f(r):
        a = router.predict(r["state"], r["questions"], model=model, max_len=1024, head_max_len=640)["answers"]["next"]
        f.conf.append(a["answer_confidence"])
        return a["probabilities"]
    f.conf = []
    return f


def score(r, sc):
    lv = r["levels"]
    best = max(lv.values())
    lo = min(lv.values())
    top = max(sc, key=sc.get)
    b1 = float(lv[top] == best)
    l1 = (lv[top] - lo) / (best - lo)
    ok = tot = 0
    ks = list(lv)
    for i in range(len(ks)):
        for j in range(i + 1, len(ks)):
            a, b = ks[i], ks[j]
            if lv[a] == lv[b]:
                continue
            tot += 1
            d = (sc[a] - sc[b]) * (lv[a] - lv[b])
            ok += 1.0 if d > 0 else (0.5 if d == 0 else 0.0)
    return b1, l1, ok / tot


def ece(conf, hit, bins=15):
    tot, e = len(conf), 0.0
    for b in range(bins):
        idx = [i for i, c in enumerate(conf) if b / bins <= c < (b + 1) / bins or (b == bins - 1 and c == 1.0)]
        if idx:
            e += len(idx) / tot * abs(sum(hit[i] for i in idx) / len(idx) - sum(conf[i] for i in idx) / len(idx))
    return e


def main():
    split, sels = sys.argv[1], sys.argv[2:]
    data = rows(split)
    for spec in sels:
        rng = random.Random(1)
        if spec == "random":
            f = lambda r: {k: rng.random() for k in r["levels"]}
        elif spec == "prior":
            f = make_prior()
        else:
            f = make_laya(spec)
        per = defaultdict(list)
        hits = []
        for r in data:
            m = score(r, f(r))
            per[r["app"]].append(m)
            hits.append(m[0])
        macro = [sum(x[i] for ms in per.values() for x in [tuple(sum(v[i] for v in ms) / len(ms) for i in range(3))]) / len(per) for i in range(3)]
        micro = [sum(m[i] for ms in per.values() for m in ms) / len(data) for i in range(3)]
        line = (f"{spec:45s} n={len(data)} apps={len(per)}  macro best@1={macro[0]:.3f} lvl@1={macro[1]:.3f} "
                f"pairacc={macro[2]:.3f} | micro best@1={micro[0]:.3f} pairacc={micro[2]:.3f}")
        if hasattr(f, "conf") and f.conf:
            line += f"  ece={ece(f.conf, hits):.3f}"
        print(line, flush=True)


if __name__ == "__main__":
    main()
