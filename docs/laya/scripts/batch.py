"""Batch measurements over the study03 E0 grounding set (28 apps, 458 screens, 3840 actionable candidates).

Exp A  grounding (gold labels): instruction names the target ("Tap the "X" button"), options = the
       screen's actionable candidates. Top-1 vs random and vs a lexical baseline, by N and checkpoint.
Exp C  exploration (no gold): generic testing instruction on every screen; position bias (flip rate
       over shuffles, argmax position), checkpoint agreement, entropy, option kind picked.

Outputs JSONL rows to results/ and prints summary tables.
"""
import argparse
import csv
import json
import math
import random
import re
import time
from collections import defaultdict
from pathlib import Path

from laya import Router

E0 = Path("/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/"
          "rvsec-study03-replication-package/experiments/E0-evaluation-set/results")
OUT = Path("results")
MODELS = ("english", "typed-decisions", "multilingual")
VERB = {"text-button": "tap button", "icon": "tap icon", "list-item": "tap list item",
        "toggle": "toggle", "text-input": "type text into field"}


def load():
    pool = [r for r in csv.DictReader(open(E0 / "pool.csv")) if r["excluded"] == ""]
    by_screen = defaultdict(list)
    for r in pool:
        by_screen[(r["apk"], r["screen"])].append(r)
    targets = [r for r in csv.DictReader(open(E0 / "targets-600.csv")) if r["excluded"] == ""]
    return by_screen, targets


def opt_text(r):
    cls = r["class"].rsplit(".", 1)[-1]
    return f"{VERB.get(r['category'], 'tap')} '{r['label'][:40]}' ({cls})"


def make_options(rows, rng, shuffle=True):
    rows = list(rows)
    if shuffle:
        rng.shuffle(rows)
    crit, key_of = {}, {}
    for i, r in enumerate(rows):
        k = f"o{i}"  # neutral keys: no label leakage through the key itself
        crit[k] = opt_text(r)
        key_of[k] = r["target_id"]
    return crit, key_of


def toks(s):
    return set(re.findall(r"[a-z0-9]+", s.lower()))


def lexical_pick(instr, rows, rng):
    it = toks(instr) - {"tap", "the", "button", "icon", "item", "toggle", "field", "type", "text", "into", "list"}
    best, score = [], -1.0
    for r in rows:
        lt = toks(r["label"])
        s = len(it & lt) / (len(lt) or 1)
        if s > score:
            best, score = [r], s
        elif s == score:
            best.append(r)
    return rng.choice(best)["target_id"]


def entropy(p):
    return -sum(v * math.log(v) for v in p.values() if v > 0) / math.log(len(p)) if len(p) > 1 else 0.0


def ask(router, model, state, instr, crit, head_max_len):
    kw = {"model": model}
    if head_max_len:
        kw.update(head_max_len=min(head_max_len, 400) if model == "english" else head_max_len,
                  max_len=512 if model == "english" else 1024)
    q = {"q": {"type": "choice", "instructions": instr, "criteria": crit}}
    t = time.perf_counter()
    a = router.predict(state, q, **kw)["answers"]["q"]
    return a, (time.perf_counter() - t) * 1000


def screen_state(rows):
    return ""  # Exp A: no screen context; the instruction carries the target


def exp_a(router, by_screen, targets, ns, head, seed):
    rng = random.Random(seed)
    OUT.mkdir(exist_ok=True)
    f = open(OUT / f"expA_head{head or 'def'}.jsonl", "w")
    for t in targets:
        rows = by_screen[(t["apk"], t["screen"])]
        others = [r for r in rows if r["target_id"] != t["target_id"]]
        gold = next(r for r in rows if r["target_id"] == t["target_id"])
        for n in ns:
            if n != "all" and n > len(rows):
                continue
            sub = [gold] + (rng.sample(others, n - 1) if n != "all" else others)
            crit, key_of = make_options(sub, rng)
            lex = lexical_pick(t["instruction"], sub, rng)
            for m in MODELS:
                try:
                    a, ms = ask(router, m, "", t["instruction"], crit, head)
                    pick = key_of[a["choice"]]
                    err = None
                except Exception as e:  # options overflow etc.
                    a, ms, pick, err = {"answer_confidence": None, "probabilities": {}}, 0, None, str(e)[:80]
                f.write(json.dumps({
                    "target": t["target_id"], "category": t["category"], "label_source": t["label_source"],
                    "ambiguous": t["ambiguous"], "n": len(sub), "nbucket": n, "model": m,
                    "correct": pick == t["target_id"], "lex_correct": lex == t["target_id"],
                    "conf": a.get("answer_confidence"), "ms": ms, "err": err}) + "\n")
    f.close()


EXPLORE = ("You are testing an Android app and want to reach new screens and functionality. "
           "Which action should be performed next?")


def exp_c(router, by_screen, head, seed, shuffles=4):
    rng = random.Random(seed)
    f = open(OUT / f"expC_head{head or 'def'}.jsonl", "w")
    for (apk, scr), rows in sorted(by_screen.items()):
        if len(rows) < 2:
            continue
        texts = [r["label"] for r in rows][:30]
        state = {"app": apk.rsplit("_", 1)[0], "screen_text": " | ".join(texts)[:600]}
        for m in MODELS:
            picks, poss, confs, ents = [], [], [], []
            for s in range(shuffles):
                crit, key_of = make_options(rows, rng)
                try:
                    a, ms = ask(router, m, state, EXPLORE, crit, head)
                except Exception as e:
                    picks.append(None)
                    continue
                picks.append(key_of[a["choice"]])
                poss.append(int(a["choice"][1:]))
                confs.append(a["answer_confidence"])
                ents.append(entropy(a["probabilities"]))
            cat = {r["target_id"]: r["category"] for r in rows}
            f.write(json.dumps({"screen": f"{apk}/{scr}", "n": len(rows), "model": m, "picks": picks,
                                "pos": poss, "conf": confs, "ent": ents,
                                "cats": [cat.get(p) for p in picks]}) + "\n")
    f.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("exp", choices=["A", "C"])
    ap.add_argument("--head", type=int, default=0, help="head_max_len override (0 = checkpoint default)")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    by_screen, targets = load()
    if args.limit:
        targets = targets[: args.limit]
    router = Router(device="cuda", max_loaded=3)
    if args.exp == "A":
        exp_a(router, by_screen, targets, [5, 10, 20, 40, "all"], args.head, args.seed)
    else:
        exp_c(router, by_screen, args.head, args.seed)
