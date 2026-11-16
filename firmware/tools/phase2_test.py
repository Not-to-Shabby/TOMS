#!/usr/bin/env python3
"""OTA phase 2 revert test (firmware/OTA_PLAN.md).

For each scenario this puts the known-good image 1.0.0 in the primary slot, puts a test image
in the update slot marked "run on trial", has you power-cycle the board, and reads the serial
log to see which image ends up running. It does not trust the flash tool: pass or fail comes
from what the board prints, judged by judge.py.

    python D:/TOMS/firmware/tools/phase2_test.py              run every scenario
    python D:/TOMS/firmware/tools/phase2_test.py crash        run one (names below)
    python D:/TOMS/firmware/tools/phase2_test.py --list
    python D:/TOMS/firmware/tools/phase2_test.py --replay     judge the logs already saved in
                                                              build/phase2/logs, no board needed

Scenarios and the result that counts as a PASS:
  good      good-2 (2.0.0) is healthy      -> runs, confirms itself, and STAYS after a power cycle
  crash     bad-crash (3.0.0) crashes      -> the board ends up running good-1
  hang      bad-hang (3.0.0) hangs         -> a watchdog reset brings back good-1
  noconfirm bad-noconfirm (3.0.0)          -> runs but never confirms, the next reset restores good-1
  selftest  bad-selftest (3.0.0)           -> self-test fails, it resets itself, good-1 returns

Early boot lines never reach the PC (the USB serial port does not exist yet), and a hung image
prints nothing, so judge.py also uses the bootloader's own "Loading image" lines and the 10 s
heartbeat. Close any serial monitor first. The board is overwritten (bootloader, both slots).
You are asked to unplug and re-plug the USB cable after each flash, and once more in "good".
"""
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import board  # noqa: E402
import judge  # noqa: E402
from board import say  # noqa: E402

FW = Path("D:/TOMS/firmware")
IMG = FW / "build/phase2/images"
FLASH = FW / "tools/flash.py"
LOGDIR = FW / "build/phase2/logs"
SLOT0, SLOT1 = 0x20000, 0x170000

SCENARIOS = {
    "good": "good-2.pending.bin",
    "crash": "bad-crash.pending.bin",
    "hang": "bad-hang.pending.bin",
    "noconfirm": "bad-noconfirm.pending.bin",
    "selftest": "bad-selftest.pending.bin",
}
EXPECT = {
    "good": "good-2 runs on trial, passes its self-test, confirms itself, and STAYS after a power cycle",
    "crash": "bad-crash crashes at boot; the crash handler reboots; MCUboot restores good-1",
    "hang": "bad-hang hangs; the watchdog resets it; MCUboot restores good-1",
    "noconfirm": "bad-noconfirm runs but never confirms; the next reset restores good-1",
    "selftest": "bad-selftest fails its self-test and resets itself; MCUboot restores good-1",
}
LOGSETS = {
    "good": ["good-1-first-boot", "good-2-after", "good-3-after-second-cycle"],
    "crash": ["crash-1-first-boot", "crash-2-after"],
    "hang": ["hang-1-first-boot", "hang-2-after"],
    "noconfirm": ["noconfirm-1-first-boot", "noconfirm-2-after"],
    "selftest": ["selftest-1-first-boot", "selftest-2-after"],
}


def flash(images):
    """images: list of (address, path). Uses flash.py so retries and the re-plug wait apply."""
    cmd = [sys.executable, str(FLASH)]
    for addr, path in images:
        cmd += ["--image", f"{addr:#x}={path}"]
    return subprocess.run(cmd).returncode == 0


def capture(seconds, label):
    """Read the board's log, showing it live, and keep a copy in build/phase2/logs/."""
    LOGDIR.mkdir(parents=True, exist_ok=True)
    path = LOGDIR / f"{label}.log"
    say(f"reading the board's serial log for up to {seconds}s (a copy goes to {path})")
    with open(path, "w", encoding="utf-8") as sink:
        return board.capture(seconds, sink=sink)


def power_cycle(why):
    """Ask for a USB unplug and re-plug and wait until the board is seen going and coming back.

    A reset made through the serial control lines can leave this chip in download mode
    ("waiting for download": no app, no bootloader), so a real power-on is the only start these
    tests trust. Returns the board's port name, or None."""
    say("")
    say(f"STEP: {why}")
    say(">>> UNPLUG the USB cable now, then PLUG IT BACK IN <<<")
    port = board.wait_replug(180)
    if port is None:
        say("no re-plug was seen, so this scenario cannot continue")
    return port


def prepare(test_image):
    """Primary = good-1 confirmed, update slot = the test image marked for trial."""
    say(f"STEP: write the bootloader, good-1 (confirmed, slot 0) and {test_image} (slot 1, on trial)")
    return flash([
        (0x0, IMG / "mcuboot.bin"),
        (SLOT0, IMG / "good-1.confirmed.bin"),
        (SLOT1, IMG / test_image),
    ])


def verdict(name, logs):
    """Judge the captured logs and print every check, so a FAIL says exactly which one."""
    fps = judge.fingerprints()
    if not fps:
        say("WARNING: no image fingerprints available, so bootloader lines cannot be matched to images")
    res = judge.judge(name, logs, fps)
    say(f"verdict for {name}:")
    for line in res.lines():
        say(line)
    return res.ok, res.summary()


def scenario(name):
    say("")
    say("=" * 70)
    say(f"SCENARIO {name}: {SCENARIOS[name]}")
    say("=" * 70)
    say(f"expected: {EXPECT[name]}")
    if not prepare(SCENARIOS[name]):
        return False, "could not flash the board"

    if power_cycle("start the bootloader. It swaps the update slot in (this prints nothing "
                   "and takes ~20-40 s), then the new image runs on trial.") is None:
        return False, "no power cycle, so the bootloader never ran"

    say("")
    say("STEP: watch the first boot (the trial)")
    logs = [capture(90, f"{name}-1-first-boot")]
    say("")
    say("STEP: keep watching so the bad image has time to fail, reset, and be reverted")
    logs.append(capture(60, f"{name}-2-after"))

    if name == "good":
        if power_cycle("check that the confirmed image STAYS: power-cycle once more.") is None:
            return False, "no second power cycle, could not check that it stays"
        say("STEP: watch the boot after the second power cycle")
        logs.append(capture(40, "good-3-after-second-cycle"))
    return verdict(name, logs)


def replay(names):
    results = {}
    for n in names:
        say("")
        say(f"--- replaying saved logs for {n}")
        files = [LOGDIR / f"{x}.log" for x in LOGSETS[n]]
        missing = [f.name for f in files if not f.exists()]
        if missing:
            results[n] = (False, f"saved logs missing: {missing}")
            say(f"cannot replay {n}: missing {missing}")
            continue
        results[n] = verdict(n, [f.read_text(encoding="utf-8") for f in files])
    return results


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    if "--list" in sys.argv:
        for k, v in SCENARIOS.items():
            print(f"{k:10} {v}")
        return 0
    names = args or list(SCENARIOS)
    for n in names:
        if n not in SCENARIOS:
            sys.exit(f"unknown scenario {n!r}; try --list")

    if "--replay" in sys.argv:
        results = replay(names)
    else:
        for f in [IMG / "mcuboot.bin", IMG / "good-1.confirmed.bin"] + [IMG / SCENARIOS[n] for n in names]:
            if not f.exists():
                sys.exit(f"missing {f}: run build_phase2.sh first")
        say(f"board now: {board.describe()}")
        say(f"scenarios to run: {names}. Each needs one USB re-plug (the good one needs two).")
        results = {}
        for n in names:
            try:
                results[n] = scenario(n)
            except Exception as e:  # keep going so one failure does not hide the rest
                results[n] = (False, f"test crashed: {e!r}")

    say("")
    say("================ RESULT ================")
    for n, (ok, why) in results.items():
        say(f"{'PASS' if ok else 'FAIL'}  {n:10} {why}")
    allok = all(ok for ok, _ in results.values())
    say("")
    say("PHASE 2 REVERT TESTS " + ("PASSED" if allok else "FAILED or INCOMPLETE"))
    say(f"full logs: {LOGDIR}")
    return 0 if allok else 1


if __name__ == "__main__":
    sys.exit(main())
