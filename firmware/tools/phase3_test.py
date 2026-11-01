#!/usr/bin/env python3
"""OTA phase 3 power-cut test (firmware/OTA_PLAN.md): cut USB power during the MCUboot swap.

Each round:
  1. flash the bootloader, good-1 (confirmed, slot 0) and good-2 on trial (update slot 1), so the
     next power-on starts a swap;
  2. you unplug and plug the USB cable (this is the power-on that starts the swap);
  3. as soon as the board shows up, the script counts down a RANDOM time and says CUT NOW: you
     pull the cable at that moment, so the cut lands at a different point of the swap each round;
  4. you plug it back in; the script reads the board for 90 s and checks that an image boots.

PASS for a round: after the cut the board still boots one of the two known images (good-1 or
good-2), which prints its build tag, and that image is healthy (heartbeat seen). Either outcome
is fine: the swap finished (good-2, it confirms itself) or it was rolled back (good-1). FAIL: no
image prints its tag within the capture, or a tag nobody built. A FAIL means the unit may be
bricked: reflash with flash.py (BOOT+USB if needed) before continuing.

    python D:/TOMS/firmware/tools/phase3_test.py               20 rounds
    python D:/TOMS/firmware/tools/phase3_test.py --rounds 5
    python D:/TOMS/firmware/tools/phase3_test.py --min 0.5 --max 8   cut-time window in seconds

Results are appended to build/phase3/results.jsonl, so an interrupted run can be resumed with
--start N. The window is measured from the moment the board's USB port appears, not from
power-on (about 1 s later), and your own reaction time adds ~0.3 s: the real cut points are
spread over the swap, which is what matters. Close any serial monitor first.
"""
import argparse
import json
import random
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import board  # noqa: E402
from board import say  # noqa: E402

FW = Path("D:/TOMS/firmware")
IMG = FW / "build/phase2/images"
FLASH = FW / "tools/flash.py"
OUT = FW / "build/phase3"
SLOT0, SLOT1 = 0x20000, 0x170000
KNOWN = {"good-1", "good-2"}
TAG = re.compile(r"build tag: (\S+)")
LOAD = re.compile(r"I \((\d+)\) boot: Loading image")
HEART = re.compile(r"heartbeat #\d+: .*image (\S+) \((confirmed|ON TRIAL)\)")


def flash():
    cmd = [sys.executable, str(FLASH)]
    for addr, f in ((0x0, "mcuboot.bin"), (SLOT0, "good-1.confirmed.bin"), (SLOT1, "good-2.pending.bin")):
        cmd += ["--image", f"{addr:#x}={IMG / f}"]
    return subprocess.run(cmd).returncode == 0


def swap_evidence(text):
    """Did the boot after the cut still have swap work to do? A full swap stamps the bootloader's
    "Loading image" line at about 6600 ms (phase 2 logs); a boot with nothing to swap, about 400-1000."""
    m = LOAD.search(text)
    if not m:
        return "bootloader line not captured, cannot tell whether the cut hit the swap"
    ms = int(m.group(1))
    if ms >= 5000:
        return f"bootloader took {ms} ms: a FULL swap ran after the cut, so the cut came before the swap"
    if ms >= 1500:
        return f"bootloader took {ms} ms: partial work, the cut very likely interrupted the swap and it resumed"
    return f"bootloader took {ms} ms: little or no swap work, the swap was probably already done (cut too late?)"


def judge(text):
    """(ok, summary) from one capture."""
    tags = TAG.findall(text)
    beats = HEART.findall(text)
    if not tags and beats:
        # the capture opened after the boot lines; the heartbeat also names the image
        tags = [beats[-1][0]]
    if not tags:
        return False, "NO image printed a build tag: the board may not be booting"
    odd = [t for t in tags if t not in KNOWN]
    if odd:
        return False, f"unknown build tag(s) {odd}: a corrupted or wrong image ran"
    if not beats:
        return False, f"tag {tags[-1]} seen but no heartbeat: it started and did not stay healthy"
    img, state = beats[-1]
    return True, f"booted {img} ({state}); tags seen {tags}"


def one_round(n, rng, lo, hi):
    say("")
    say("=" * 70)
    say(f"ROUND {n}")
    say("=" * 70)
    say("STEP: write bootloader + good-1 (confirmed) + good-2 (on trial) so the next power-on swaps")
    if not flash():
        say(">>> flash failed (port locked). UNPLUG and re-plug the cable; I retry once after that <<<")
        board.wait_replug(180)
        if not flash():
            return {"round": n, "ok": False, "why": "could not flash", "cut": None}
    say("")
    say(">>> UNPLUG the USB cable, then PLUG IT BACK IN (this power-on starts the swap) <<<")
    if board.wait_replug(180) is None:
        return {"round": n, "ok": False, "why": "no re-plug seen", "cut": None}
    delay = round(rng.uniform(lo, hi), 2)
    t0 = time.time()
    say(f"swap is running. Keep your hand on the cable. CUT in {delay:.2f} s ...")
    while time.time() - t0 < delay:
        time.sleep(0.01)
    print("\a", end="", flush=True)
    say(">>> CUT NOW: pull the USB cable out, wait 3 s, plug it back in <<<")
    if board.wait_replug(120) is None:
        return {"round": n, "ok": False, "why": "no re-plug after the cut", "cut": delay}
    OUT.mkdir(parents=True, exist_ok=True)
    say("STEP: read the board for 90 s to see which image comes up")
    with open(OUT / f"round-{n:02d}.log", "w", encoding="utf-8") as sink:
        text = board.capture(90, sink=sink)
    ok, why = judge(text)
    ev = swap_evidence(text)
    say(f"  swap evidence: {ev}")
    why += " | " + ev
    say(f"round {n}: {'PASS' if ok else 'FAIL'}  (cut at {delay:.2f} s)  {why}")
    return {"round": n, "ok": ok, "why": why, "cut": delay}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rounds", type=int, default=20)
    ap.add_argument("--start", type=int, default=1)
    ap.add_argument("--min", type=float, default=0.5)
    ap.add_argument("--max", type=float, default=8.0)
    ap.add_argument("--seed", type=int, default=None)
    a = ap.parse_args()
    for f in (IMG / "mcuboot.bin", IMG / "good-1.confirmed.bin", IMG / "good-2.pending.bin"):
        if not f.exists():
            sys.exit(f"missing {f}: run build_phase2.sh first")
    seed = a.seed if a.seed is not None else int(time.time())
    rng = random.Random(seed)
    say(f"board now: {board.describe()}")
    say(f"rounds {a.start}..{a.rounds}, cut window {a.min}-{a.max} s, seed {seed}")
    OUT.mkdir(parents=True, exist_ok=True)
    results = []
    for n in range(a.start, a.rounds + 1):
        try:
            r = one_round(n, rng, a.min, a.max)
        except Exception as e:
            r = {"round": n, "ok": False, "why": f"test crashed: {e!r}", "cut": None}
        results.append(r)
        with open(OUT / "results.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps(dict(r, seed=seed)) + "\n")
        if not r["ok"]:
            say("FAIL: stopping here so the failed state can be looked at. Resume with "
                f"--start {n + 1} after reflashing.")
            break
    say("")
    say("================ RESULT ================")
    for r in results:
        say(f"{'PASS' if r['ok'] else 'FAIL'}  round {r['round']:2d}  cut {r['cut']}  {r['why']}")
    passed = sum(r["ok"] for r in results)
    say(f"{passed}/{len(results)} rounds passed")
    allok = passed == len(results) and len(results) == a.rounds - a.start + 1
    say("PHASE 3 POWER-CUT TEST " + ("PASSED" if allok else "FAILED or INCOMPLETE"))
    return 0 if allok else 1


if __name__ == "__main__":
    sys.exit(main())
