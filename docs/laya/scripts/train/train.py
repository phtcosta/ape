"""Single-GPU RLCD fine-tune (laya's recipe: GRPO-style noisy-logit policy gradient + soft CE), from a laya checkpoint.

    LAYA_BASE=<checkpoint dir> python finetune/train.py out/train_items.pt out/laya-browser [epochs=4]
"""
import json, os, random, sys, time
import torch
# (upstream sys.path hack removed: laya is installed in the uv env)
from safetensors.torch import load_file, save_file
from transformers import AutoTokenizer
from laya.common import build_model, proper_reward

def collate(items, pad_id):
    n, L = len(items), max(len(it["ids"]) for it in items); kmax = max(len(it["markers"]) for it in items)
    ids = torch.full((n, L), pad_id, dtype=torch.long); att = torch.zeros((n, L), dtype=torch.long)
    mpos = torch.zeros((n, kmax), dtype=torch.long); mmask = torch.zeros((n, kmax), dtype=torch.bool); target = torch.zeros((n, kmax))
    for i, it in enumerate(items):
        ids[i, :len(it["ids"])] = torch.as_tensor(it["ids"], dtype=torch.long) if not hasattr(it["ids"], "typecode") else torch.frombuffer(it["ids"], dtype=torch.int32).long(); att[i, :len(it["ids"])] = 1; k = len(it["markers"])
        mpos[i, :k] = torch.tensor(it["markers"]); mmask[i, :k] = True; target[i, :len(it["target"])] = torch.tensor(it["target"])
    return dict(input_ids=ids, attention_mask=att, marker_pos=mpos, marker_mask=mmask, target=target, qtype=torch.tensor([it["qtype"] for it in items]))

def main():
    items_f, out, epochs = sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 4
    limit = int(os.environ.get("LIMIT", "0"))  # LIMIT=N: time N items only (benchmarking)
    base = os.environ["LAYA_BASE"]; dev = torch.device("cuda")
    cfg = json.load(open(os.path.join(base, "rl_agent_config.json")))
    cfg.update(max_len=int(os.environ.get('LAYA_MAXLEN', '1024')), head_max_len=int(os.environ.get('LAYA_HEAD', '512')))
    tok = AutoTokenizer.from_pretrained(os.path.join(base, "tokenizer"))
    model = build_model(cfg, encoder_dir=os.path.join(base, "encoder"))
    model.load_state_dict(load_file(os.path.join(base, "model.safetensors")), strict=True)
    model.encoder.config.reference_compile = False
    if os.environ.get("CKPT", "0") == "1":   # gradient checkpointing costs ~30%; 16 GB fits micro=4 x 1024 without it
        model.encoder.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.to(dev).train()
    if os.environ.get("COMPILE", "0") == "1":
        model.encoder = torch.compile(model.encoder, dynamic=True)
    items = torch.load(items_f, weights_only=False)
    if limit: items = items[:limit]
    MICRO, ACCUM, G, LR_ENC, LR_HEAD, S0, S1 = 4, 8, 4, 2.5e-5, 1e-4, 0.4, 0.1
    # FP_DONE_W=w: cost-sensitive completion.  On states whose answer is NOT "done", add w * p(done) to the loss -- the
    # DONE option of the operation question and the "yes" of the goal_done noul question -- so a premature DONE costs
    # more than a missed one.  The DONE option's position is found once per item from the option text.
    FP_DONE_W = float(os.environ.get("FP_DONE_W", "0"))
    if FP_DONE_W > 0:
        done_ids = tok(" DONE", add_special_tokens=False)["input_ids"]
        for it in items:
            it["done_pos"] = -1
            if it.get("qid") == "operation" and it.get("gold_op") != "DONE":
                ids_ = list(it["ids"])
                for j, m in enumerate(it["markers"]):
                    if ids_[m + 1:m + 1 + len(done_ids)] == done_ids:
                        it["done_pos"] = j; break
            elif it.get("qid") == "goal_done" and it.get("label") == 0:
                it["done_pos"] = 1
        print("FP_DONE_W", FP_DONE_W, "penalised items", sum(it["done_pos"] >= 0 for it in items), flush=True)
    enc = [p for n, p in model.named_parameters() if "encoder." in n]; head = [p for n, p in model.named_parameters() if "encoder." not in n]
    # HEAD_ONLY=1: frozen encoder, only the decision head trains (minutes per run: the fast single-variable loop)
    HEAD_ONLY = os.environ.get("HEAD_ONLY") == "1"
    if HEAD_ONLY:
        for p_ in enc: p_.requires_grad_(False)
        model.encoder.eval()
    opt = torch.optim.AdamW(([] if HEAD_ONLY else [{"params": enc, "lr": LR_ENC}]) + [{"params": head, "lr": LR_HEAD}], weight_decay=0.01)
    total = max(1, (len(items) // (MICRO * ACCUM)) * epochs)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=total, eta_min=1e-6)
    t0 = time.time(); step = 0
    # ---- resumable: <out>/resume.pt holds model/optimizer/scheduler + (epoch, micro-batch offset); written every
    # SAVE_EVERY optimizer steps and at each epoch end.  Re-running the same command continues from it.
    ck_path = os.path.join(out, "resume.pt"); os.makedirs(out, exist_ok=True)
    start_ep, start_b = 0, 0
    if os.path.exists(ck_path):
        ck = torch.load(ck_path, map_location=dev, weights_only=False)
        tgt = model.encoder._orig_mod if hasattr(model.encoder, "_orig_mod") else None
        model.load_state_dict({k.replace("encoder._orig_mod.", "encoder."): v for k, v in ck["model"].items()} if tgt is None else ck["model"], strict=False)
        opt.load_state_dict(ck["opt"]); sched.load_state_dict(ck["sched"]); step = ck["step"]; start_ep, start_b = ck["epoch"], ck["b"]
        print(f"resumed from {ck_path}: epoch {start_ep+1}, item {start_b}, step {step}", flush=True)
    SAVE_EVERY = int(os.environ.get("SAVE_EVERY", "300"))
    def save_resume(ep_, b_):
        tmp = ck_path + ".tmp"
        torch.save({"model": model.state_dict(), "opt": opt.state_dict(), "sched": sched.state_dict(), "step": step, "epoch": ep_, "b": b_}, tmp)
        os.replace(tmp, ck_path)
    # graceful interrupt (shutdown / kill / Ctrl+C): finish the current micro-batch, save the resume point, exit
    import signal
    stop = {"flag": False}
    def _on_signal(sig, frame):
        stop["flag"] = True
        print(f"signal {sig}: saving the resume point after this micro-batch", flush=True)
    for _sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(_sig, _on_signal)
    for ep0 in range(start_ep):          # replay the shuffles of finished epochs so the order matches the original run
        random.seed(42 + ep0); random.shuffle(items)
    for ep in range(start_ep, epochs):
        random.seed(42 + ep); random.shuffle(items)
        sigma = S0 + (S1 - S0) * ep / max(1, epochs - 1); tot_loss, nb = 0.0, 0; opt.zero_grad(set_to_none=True)
        for b in range((start_b if ep == start_ep else 0), len(items), MICRO):
            batch = collate(items[b:b + MICRO], tok.pad_token_id); batch = {k: v.to(dev) for k, v in batch.items()}
            with torch.autocast("cuda", dtype=torch.bfloat16):
                logits, act = model(batch["input_ids"], batch["attention_mask"], batch["marker_pos"], batch["marker_mask"], batch["qtype"], detach_encoder=HEAD_ONLY)
            logits = logits.float(); mask = batch["marker_mask"]; k = mask.sum(-1, keepdim=True).float(); target = batch["target"]
            eps = torch.randn((G,) + logits.shape, device=dev) * sigma * mask; eps = (eps - eps.sum(-1, keepdim=True) / k) * mask
            z = logits.detach().unsqueeze(0) + eps; q = torch.softmax(z.masked_fill(~mask, -1e4), -1)
            with torch.no_grad():
                r = proper_reward(q, target.unsqueeze(0), batch["qtype"], mask, w_sph=0.75, w_rps=1.0); adv = r - r.mean(0, keepdim=True); adv = adv / (adv.std() + 1e-6)
            logp = -(((z - logits.unsqueeze(0)) ** 2) * mask).sum(-1) / (2 * sigma ** 2)
            loss_rl = -(adv * logp).mean(); loss_ce = -(target * torch.log_softmax(logits.masked_fill(~mask, -1e4), -1)).sum(-1).mean()
            loss = (loss_rl + loss_ce) / ACCUM + 0.0 * act.sum()
            if FP_DONE_W > 0:
                dp = torch.tensor([it.get("done_pos", -1) for it in items[b:b + MICRO]], device=dev)
                sel = dp >= 0
                if sel.any():
                    probs = torch.softmax(logits.masked_fill(~mask, -1e4), -1)
                    p_done = probs[sel].gather(1, dp[sel].unsqueeze(1)).squeeze(1)
                    loss = loss + FP_DONE_W * p_done.sum() / len(dp) / ACCUM
            loss.backward(); nb += 1; tot_loss += loss.item() * ACCUM
            if nb % ACCUM == 0 or b + MICRO >= len(items):
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0); opt.step(); sched.step(); opt.zero_grad(set_to_none=True); step += 1
                if step % SAVE_EVERY == 0: save_resume(ep, b + MICRO)
            if stop["flag"]:
                # resume point = the next micro-batch; gradients of a half-finished accumulation window are dropped
                save_resume(ep, b + MICRO); print(f"saved {ck_path} at epoch {ep+1}, item {b + MICRO}; exiting", flush=True)
                sys.exit(0)
            if nb % (ACCUM * 50) == 0: print(f"  ep {ep+1} step {step}/{total} loss {loss.item()*ACCUM:.3f} ce {loss_ce.item():.3f} reward {r.mean().item():.3f} {time.time()-t0:.0f}s", flush=True)
        print(f"=== epoch {ep+1}/{epochs} avg loss {tot_loss/max(1,nb):.4f}  {time.time()-t0:.0f}s", flush=True)
        save_resume(ep + 1, 0)
    os.makedirs(out, exist_ok=True); model.eval()
    if hasattr(model.encoder, "_orig_mod"):
        model.encoder = model.encoder._orig_mod
    save_file({k: v.half().contiguous().cpu() for k, v in model.state_dict().items()}, os.path.join(out, "model.safetensors"))
    model.encoder.config.save_pretrained(os.path.join(out, "encoder")); tok.save_pretrained(os.path.join(out, "tokenizer"))
    cfg.update(fine_tuned=True, model_name="laya-aperv", temperature=[1.0, 1.0, 1.0], temperature_by_options={},
               laya_fmt=os.environ.get("LAYA_FMT", "v1"), head_max_len_train=cfg["head_max_len"])
    json.dump(cfg, open(os.path.join(out, "rl_agent_config.json"), "w"), indent=2); print("saved", out)
    if os.path.exists(ck_path): os.remove(ck_path)

if __name__ == "__main__":
    main()
