"""Exp B: replay the real APE-RV LLM decisions of study03 E5/E5b/E5c through Laya.

Each step record with an LLM call carries the exact candidate list the jar printed (variants
compact_v1 / v13 / v17), Qwen3-VL's answer (normalized coords or back) and the outcome
(`out.new_state`). We rebuild a Laya question from the same candidates (no screenshot) and measure:
  - agreement Laya vs Qwen (vs chance),
  - whether p_Laya(Qwen's pick) predicts that the pick reached a new state (AUROC),
  - new-state rate of Qwen's pick when Laya agrees vs disagrees.
Only the chosen action's outcome is observed, so these are weak, partial labels.
"""
import glob
import json
import math
import random
import re
import sys
from pathlib import Path

from laya import Router

RAW = "/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/data/raw"
LINE = re.compile(r'^\s*(?:\[(\d+)\]|(\d+)\.)\s+(\w+)(?:\s+"([^"]*)")?\s*@\((\d+),(\d+)\)(.*)$')
KEYLINE = re.compile(r'^\s*\[(\d+)\]\s+(MODEL_BACK|MODEL_MENU)')
INSTR = ("You are testing an Android app. Pick the action most likely to reach a new, unexplored screen "
         "or to trigger a monitored security-sensitive operation (marked mop).")


def parse_prompt(user):
    opts, activity, extra = [], None, []
    for ln in user.splitlines():
        m = LINE.match(ln)
        if m:
            _, _, cls, text, x, y, rest = m.groups()
            tags = []
            if "[DM]" in rest:
                tags.append("mop:direct")
            elif "[M]" in rest:
                tags.append("mop")
            v = re.search(r"\(v:(\d+)\)", rest)
            t = re.search(r"\[(UNTESTED|TESTED-(\d+)x|WELL-TESTED)\]", rest)
            if v:
                tags.append("untried" if v.group(1) == "0" else f"tried {v.group(1)}x")
            elif t:
                tags.append({"UNTESTED": "untried", "WELL-TESTED": "tried many times"}.get(t.group(1), f"tried {t.group(2)}x"))
            h = re.search(r'hint="([^"]*)"', rest)
            desc = f"tap {cls}" + (f" '{text}'" if text else "") + (f" (near text '{h.group(1)[:30]}')" if h else "")
            opts.append({"x": int(x), "y": int(y), "text": desc + (" | " + ", ".join(tags) if tags else ""), "kind": "tap"})
            continue
        k = KEYLINE.match(ln)
        if k:
            opts.append({"x": None, "y": None, "kind": "back" if k.group(2) == "MODEL_BACK" else "menu",
                         "text": "press back" if k.group(2) == "MODEL_BACK" else "open the options menu"})
            continue
        a = re.search(r'(?:Screen "|Current activity: |SCREEN: )([\w.$]+)', ln)
        if a and not activity:
            activity = a.group(1)
        if re.search(r"Visited \d+x|NEW state|visits:", ln):
            extra.append(ln.strip())
    if not any(o["kind"] == "back" for o in opts):
        opts.append({"x": None, "y": None, "kind": "back", "text": "press back"})
    return opts, activity, " ".join(extra)[:200]


def map_qwen(ev, opts):
    if ev.get("tool") == "back":
        return next(i for i, o in enumerate(opts) if o["kind"] == "back")
    q = ev.get("qwen")
    if not q:
        return None
    taps = [(math.dist((o["x"], o["y"]), q), i) for i, o in enumerate(opts) if o["kind"] == "tap"]
    if not taps:
        return None
    d, i = min(taps)
    return i if d <= 60 else None  # within 6% of the screen in normalized space


def auroc(pos, neg):
    if not pos or not neg:
        return float("nan")
    wins = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg)
    return wins / (len(pos) * len(neg))


def main():
    rng = random.Random(7)
    router = Router(device="cuda", max_loaded=3)
    models = ("english", "typed-decisions", "multilingual")
    out = open("results/expB.jsonl", "w")
    files = sorted(glob.glob(f"{RAW}/e5*/**/*.trace", recursive=True))
    n = 0
    for f in files:
        variant = re.search(r"(compact_v1|v13|v17|e5c_on|e5c_off)", f)
        for line in open(f):
            if not line.startswith('{"s"') or '"user"' not in line:
                continue
            d = json.loads(line)
            evs = [e for e in d.get("llm", []) if "user" in e]
            if not evs or "out" not in d:
                continue
            ev = evs[-1]
            if ev.get("result") not in ("matched",):
                continue
            opts, activity, extra = parse_prompt(ev["user"])
            if len(opts) < 2:
                continue
            qi = map_qwen(ev, opts)
            if qi is None:
                continue
            order = list(range(len(opts)))
            rng.shuffle(order)
            crit = {f"o{j}": opts[i]["text"] for j, i in enumerate(order)}
            back = {f"o{j}": i for j, i in enumerate(order)}
            state = {"activity": activity or "?", "screen": extra}
            rec = {"file": Path(f).name, "s": d["s"], "variant": variant.group(1) if variant else "?",
                   "n": len(opts), "qwen": qi, "new_state": bool(d["out"].get("new_state")),
                   "qwen_text": opts[qi]["text"], "untried": "untried" in opts[qi]["text"],
                   "mop": "mop" in opts[qi]["text"]}
            for m in models:
                try:
                    a = router.predict(state, {"q": {"type": "choice", "instructions": INSTR, "criteria": crit}},
                                       model=m, max_len=512 if m == "english" else 1024,
                                       head_max_len=400 if m == "english" else 768)["answers"]["q"]
                    probs = {back[k]: v for k, v in a["probabilities"].items()}
                    rec[m] = {"pick": back[a["choice"]], "p_qwen": probs[qi], "conf": a["answer_confidence"],
                              "pick_text": opts[back[a["choice"]]]["text"]}
                except Exception as e:
                    rec[m] = {"err": str(e)[:80]}
            out.write(json.dumps(rec) + "\n")
            n += 1
    print("steps", n)


def report():
    rs = [json.loads(l) for l in open("results/expB.jsonl")]
    print(f"\n== Exp B: {len(rs)} real LLM decisions (Qwen3-VL) replayed; base new-state rate of Qwen picks = "
          f"{sum(r['new_state'] for r in rs)/len(rs):.2f}; chance agreement = {sum(1/r['n'] for r in rs)/len(rs):.2f}")
    for m in ("english", "typed-decisions", "multilingual"):
        ok = [r for r in rs if "pick" in r.get(m, {})]
        agree = [r for r in ok if r[m]["pick"] == r["qwen"]]
        dis = [r for r in ok if r[m]["pick"] != r["qwen"]]
        pos = [r[m]["p_qwen"] for r in ok if r["new_state"]]
        neg = [r[m]["p_qwen"] for r in ok if not r["new_state"]]
        ns = lambda s: sum(r["new_state"] for r in s) / len(s) if s else float("nan")
        picks_untried = sum("untried" in r[m]["pick_text"] for r in ok) / len(ok)
        picks_mop = sum("mop" in r[m]["pick_text"] for r in ok) / len(ok)
        picks_back = sum(r[m]["pick_text"] == "press back" for r in ok) / len(ok)
        print(f"  {m:16s} n={len(ok)} agree={len(agree)/len(ok):.2f}  newstate|agree={ns(agree):.2f} "
              f"newstate|disagree={ns(dis):.2f}  AUROC(p_qwen->new_state)={auroc(pos, neg):.2f}  "
              f"picks: untried={picks_untried:.2f} mop={picks_mop:.2f} back={picks_back:.2f}")
    # simple baselines for the same AUROC question
    print(f"  qwen picks: untried={sum(r['untried'] for r in rs)/len(rs):.2f} mop={sum(r['mop'] for r in rs)/len(rs):.2f}; "
          f"new_state|untried={sum(r['new_state'] for r in rs if r['untried'])/max(1,sum(r['untried'] for r in rs)):.2f} "
          f"new_state|tried={sum(r['new_state'] for r in rs if not r['untried'])/max(1,sum(not r['untried'] for r in rs)):.2f}")


if __name__ == "__main__":
    report() if sys.argv[1:] == ["report"] else main()
