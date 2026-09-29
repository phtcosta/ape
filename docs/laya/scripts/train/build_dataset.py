"""Build the label-F training set for Laya from corpus/steps.jsonl.

Unit: one abstract state (app, state key) aggregated over every run of that app. Each targeted action tried there
gets a level per execution and its mean over executions:
  0  nothing changed (target state == source state)
  1  effect inside the activity (dialog, menu, tab, fragment)
  2  navigation to another activity
  3  navigation to an activity that reaches MOP (static analysis)
MODEL_BACK / MODEL_MENU are left out (they always "work"; SATA handles them).
Gold = softmax(mean_level / TAU) over the tried actions. Only contrastive states (levels differ) become items.
Options carry no visit/novelty information on purpose: novelty is SATA's job; Laya learns what an action does.

Split by app (deterministic hash): test ~10%, val ~10% (calibration), train the rest. The 2 apps that overlap the 163
study APKs always go to test.

Outputs (train/data/): {train,val,test}.jsonl (raw: state, criteria, gold, app, meta) and train_items.pt
(tokenized with laya.common.build_sequence at MAX_LEN/HEAD).
"""
import hashlib
import json
import math
import os
import random
import sys
from collections import defaultdict

CORPUS = "/home/pedro/tmp/laya/corpus/steps.jsonl"
EVAL163 = "/home/pedro/tmp/laya/corpus/eval163.txt"
OUT = "/home/pedro/tmp/laya/train/data"
BASE = ("/home/pedro/.cache/huggingface/hub/models--convaiinnovations--laya/snapshots/"
        "55cf4c4ebb4ebe31b2550e8bdf3bd21b99753851/typed-decisions")
TAU, KMAX, MAX_LEN, HEAD = 1.0, 20, 1024, 640
INPUT = ("EditText", "AutoCompleteTextView", "SearchView")
TOGGLE = ("CheckBox", "RadioButton", "Switch", "ToggleButton", "CheckedTextView", "CompoundButton")
INSTR = ("You are exploring an Android app for testing. Pick the action most likely to have an effect: open a "
         "dialog or menu, navigate to another screen, or reach security-sensitive (mop) functionality.")


def sig(c):
    return (c["type"], c.get("cls", ""), c.get("rid", ""),
            "" if any(i in c.get("cls", "") for i in INPUT) else c.get("text", ""), tuple(c.get("bounds", ())))


def describe(c):
    cls, rid, text = c.get("cls", "") or "View", c.get("rid", ""), (c.get("text") or "")[:40]
    t = c["type"]
    if any(i in cls for i in INPUT) and t == "MODEL_CLICK":
        what = f"type text into {cls}" + (f" '{c['hint'][:30]}'" if c.get("hint") else "")
    elif any(i in cls for i in TOGGLE) and t == "MODEL_CLICK":
        what = f"toggle {cls}" + (f" '{text}'" if text else "")
    elif t == "MODEL_CLICK":
        what = f"tap {cls}" + (f" '{text}'" if text else "")
    elif t == "MODEL_LONG_CLICK":
        what = f"long-press {cls}" + (f" '{text}'" if text else "")
    elif t.startswith("MODEL_SCROLL"):
        what = f"scroll {t[len('MODEL_SCROLL_'):].lower().replace('_', ' ')} {cls}"
    else:
        what = t.lower()
    if rid:
        what += f" (id={rid[:30]})"
    if c.get("mop"):
        what += " | mop:direct" if c["mop"] == "direct" else " | mop"
    return what


def split_of(app, eval_apps):
    if app in eval_apps:
        return "test"
    h = int(hashlib.sha1(app.encode()).hexdigest(), 16) % 10
    return "test" if h == 0 else ("val" if h == 1 else "train")


def main():
    eval_apps = {l.strip() for l in open(EVAL163) if l.strip()}
    mop_acts = defaultdict(set)
    for line in open(CORPUS):
        r = json.loads(line)
        if r.get("activity_mop"):
            mop_acts[r["app"]].add(r["activity"])
    states = {}
    for line in open(CORPUS):
        r = json.loads(line)
        if "effect" not in r:
            continue
        ch = next((c for c in r["candidates"] if c["aid"] == r["chosen"]), None)
        if ch is None or ch["type"] in ("MODEL_BACK", "MODEL_MENU"):
            continue
        key = (r["app"], r["state"])
        st = states.get(key)
        if st is None:
            labels = []
            for c in r["candidates"]:
                t = (c.get("text") or "").strip()
                if t and t not in labels:
                    labels.append(t[:40])
            st = states[key] = {"activity": r["activity"], "mop_act": bool(r.get("activity_mop")),
                                "screen": " | ".join(labels)[:500], "acts": {}}
        tgt_act = r["target"].split("@")[0]
        level = 0 if not r["effect"] else (1 if not r["act_change"] else (3 if tgt_act in mop_acts[r["app"]] else 2))
        a = st["acts"].setdefault(sig(ch), {"desc": describe(ch), "n": 0, "sum": 0})
        a["n"] += 1
        a["sum"] += level
    os.makedirs(OUT, exist_ok=True)
    rng = random.Random(20260929)
    outs = {s: open(os.path.join(OUT, f"{s}.jsonl"), "w") for s in ("train", "val", "test")}
    counts = defaultdict(int)
    level_hist = defaultdict(int)
    for (app, skey), st in states.items():
        acts = list(st["acts"].values())
        if len(acts) < 2:
            continue
        means = [a["sum"] / a["n"] for a in acts]
        if max(means) - min(means) < 0.5:
            continue  # not contrastive
        order = sorted(range(len(acts)), key=lambda i: -means[i])
        if len(acts) > KMAX:  # keep the best few, fill with random others
            keep = order[:KMAX // 2] + rng.sample(order[KMAX // 2:], KMAX - KMAX // 2)
        else:
            keep = order
        rng.shuffle(keep)
        crit, gold, seen = {}, {}, set()
        for j, i in enumerate(keep):
            d = acts[i]["desc"]
            if d in seen:  # identical descriptions are indistinguishable for the model: keep the first
                continue
            seen.add(d)
            crit[f"a{j}"] = d
            gold[f"a{j}"] = means[i]
        if len(crit) < 2 or max(gold.values()) - min(gold.values()) < 0.5:
            continue
        z = {k: math.exp(v / TAU) for k, v in gold.items()}
        s = sum(z.values())
        state = {"activity": st["activity"].rsplit(".", 1)[-1], "mop_activity": st["mop_act"], "screen": st["screen"]}
        row = {"app": app, "state_key": skey, "state": state,
               "questions": {"next": {"type": "choice", "instructions": INSTR, "criteria": crit}},
               "gold": {"next": {"probabilities": {k: v / s for k, v in z.items()}}},
               "levels": gold}
        sp = split_of(app, eval_apps)
        outs[sp].write(json.dumps(row) + "\n")
        counts[sp] += 1
        for v in gold.values():
            level_hist[round(v)] += 1
    for f in outs.values():
        f.close()
    print("items per split:", dict(counts))
    print("option level histogram (rounded mean level):", dict(sorted(level_hist.items())))
    apps = defaultdict(set)
    for sp in ("train", "val", "test"):
        for line in open(os.path.join(OUT, f"{sp}.jsonl")):
            apps[sp].add(json.loads(line)["app"])
    print("apps per split:", {k: len(v) for k, v in apps.items()})


def tokenize():
    import torch
    from transformers import AutoTokenizer
    from laya.common import build_sequence
    tok = AutoTokenizer.from_pretrained(os.path.join(BASE, "tokenizer"))
    items, dropped, trunc = [], 0, 0
    for line in open(os.path.join(OUT, "train_bal.jsonl")):
        r = json.loads(line)
        q = r["questions"]["next"]
        keys = list(q["criteria"])
        seq, mk, stats = build_sequence(tok, r["state"], {"t": "choice", "ins": q["instructions"], "crit": q["criteria"]},
                                        MAX_LEN, HEAD, return_stats=True)
        if len(mk) != len(keys):
            dropped += 1
            continue
        trunc += stats["tokens_per_option"] is not None
        p = r["gold"]["next"]["probabilities"]
        target = [p[k] for k in keys]
        items.append({"ids": seq, "markers": mk, "qtype": 0, "target": target, "label": target.index(max(target))})
    torch.save(items, os.path.join(OUT, "train_items.pt"))
    print(f"tokenized train items: {len(items)} (dropped {dropped}, options truncated in {trunc})")
    L = sorted(len(it["ids"]) for it in items)
    print(f"sequence length: median {L[len(L)//2]} p90 {L[int(.9*len(L))]} max {L[-1]}")


if __name__ == "__main__":
    if sys.argv[1:] == ["tokenize"]:
        tokenize()
    else:
        main()
