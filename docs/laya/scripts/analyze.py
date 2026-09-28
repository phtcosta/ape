"""Summaries for batch.py outputs."""
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

R = Path("results")


def rows(p):
    return [json.loads(l) for l in open(p)]


def exp_a(p):
    rs = [r for r in rows(p) if r["ambiguous"] == "false"]
    print(f"\n== {p.name}  (unambiguous targets; top-1 accuracy)")
    buckets = [5, 10, 20, 40, "all"]
    models = sorted({r["model"] for r in rs})
    hdr = f"{'N':>5} {'n':>5} {'random':>7} {'lexical':>8} " + " ".join(f"{m[:14]:>14}" for m in models)
    print(hdr)
    for b in buckets:
        sel = [r for r in rs if r["nbucket"] == b]
        if not sel:
            continue
        per = {m: [r for r in sel if r["model"] == m] for m in models}
        one = per[models[0]]
        rnd = sum(1 / r["n"] for r in one) / len(one)
        lex = sum(r["lex_correct"] for r in one) / len(one)
        cells = []
        for m in models:
            ok = [r for r in per[m] if r["err"] is None]
            err = len(per[m]) - len(ok)
            acc = sum(r["correct"] for r in per[m]) / len(per[m])
            cells.append(f"{acc:>9.3f}" + (f" e{err:<3d}" if err else "     "))
        print(f"{str(b):>5} {len(one):>5} {rnd:>7.3f} {lex:>8.3f} " + " ".join(f"{c:>14}" for c in cells))
    # by category / label source at N=all
    for field in ("category", "label_source"):
        print(f"-- N=all by {field}")
        sel = [r for r in rs if r["nbucket"] == "all"]
        for v in sorted({r[field] for r in sel}):
            s = [r for r in sel if r[field] == v]
            one = [r for r in s if r["model"] == models[0]]
            line = f"   {v:16s} n={len(one):4d} lex={sum(r['lex_correct'] for r in one)/len(one):.2f} "
            line += " ".join(f"{m[:8]}={sum(r['correct'] for r in s if r['model']==m)/len(one):.2f}" for m in models)
            print(line)
    # calibration: accuracy by confidence bin
    print("-- confidence bins (all N): acc / count")
    for m in models:
        bins = defaultdict(list)
        for r in rs:
            if r["model"] == m and r["conf"] is not None:
                bins[min(int(r["conf"] * 5), 4)].append(r["correct"])
        print(f"   {m:16s} " + "  ".join(f"[{b/5:.1f}-{(b+1)/5:.1f}) {sum(v)/len(v):.2f}/{len(v)}" for b, v in sorted(bins.items())))
    ms = [r["ms"] for r in rs if r["ms"]]
    ms.sort()
    print(f"-- latency ms p50={ms[len(ms)//2]:.0f} p95={ms[int(.95*len(ms))]:.0f}")


def exp_c(p):
    rs = rows(p)
    print(f"\n== {p.name}  (exploration instruction, {len({r['screen'] for r in rs})} screens, 4 shuffles each)")
    models = sorted({r["model"] for r in rs})
    for m in models:
        s = [r for r in rs if r["model"] == m and all(r["picks"])]
        stable = sum(len(set(r["picks"])) == 1 for r in s) / len(s)
        agree_mode = sum(Counter(r["picks"]).most_common(1)[0][1] for r in s) / (len(s) * 4)
        # position bias: relative position of argmax in the shuffled list
        rel = Counter()
        for r in s:
            for pos in r["pos"]:
                rel[min(int(pos / r["n"] * 4), 3)] += 1
        tot = sum(rel.values())
        cats = Counter(c for r in s for c in r["cats"])
        conf = sum(c for r in s for c in r["conf"]) / (len(s) * 4)
        ent = sum(e for r in s for e in r["ent"]) / (len(s) * 4)
        print(f"  {m:16s} all-4-same={stable:.2f} modal-share={agree_mode:.2f} conf={conf:.2f} normEnt={ent:.2f}")
        print(f"  {'':16s} argmax quartile of list: " + " ".join(f"Q{q+1}={rel[q]/tot:.2f}" for q in range(4)))
        print(f"  {'':16s} kinds picked: " + ", ".join(f"{k}={v/sum(cats.values()):.2f}" for k, v in cats.most_common()))
    # pool base rate of kinds
    # cross-checkpoint agreement on modal pick
    modal = defaultdict(dict)
    for r in rs:
        if all(r["picks"]):
            modal[r["screen"]][r["model"]] = Counter(r["picks"]).most_common(1)[0][0]
    for i, a in enumerate(models):
        for b in models[i + 1:]:
            both = [v for v in modal.values() if a in v and b in v]
            print(f"  agreement {a} vs {b}: {sum(v[a]==v[b] for v in both)/len(both):.2f} (n={len(both)})")
    ns = [r["n"] for r in rs if r["model"] == models[0]]
    print(f"  chance of agreeing by luck ~ {sum(1/n for n in ns)/len(ns):.2f}")


if __name__ == "__main__":
    for p in sorted(R.glob("expA_*.jsonl")):
        exp_a(p)
    for p in sorted(R.glob("expC_*.jsonl")):
        exp_c(p)
