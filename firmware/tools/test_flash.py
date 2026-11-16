"""Tests for flash.py's control flow with a fake esptool and a fake USB (no hardware needed).

The earlier version of flash.py called a function that had been deleted, which only showed up
when every round failed. These tests drive that path on purpose.
"""
import contextlib
import io
import sys
import time

sys.path.insert(0, "D:/TOMS/firmware/tools")
import board  # noqa: E402
import flash  # noqa: E402

IMG = "D:/TOMS/firmware/build/phase2/images/mcuboot.bin"
fails = 0


def check(cond, what):
    global fails
    print(("PASS  " if cond else "FAIL  ") + what)
    fails += not cond


def drive(results, replug, port="COM11", attempts=3):
    """Run flash.main() with scripted esptool results. Returns (exit code, calls, output)."""
    calls, state = [], {"port": port}
    real = (flash.run_once, board.wait_replug, board.find_board, time.sleep, sys.argv)

    def fake_run_once(p, mode, baud, images):
        calls.append((p, mode, baud))
        ok, text = results[min(len(calls) - 1, len(results) - 1)]
        return ok, text, text.splitlines()[-6:]

    def fake_replug(timeout, **_k):
        if replug:
            state["port"] = replug
        return replug

    flash.run_once, board.wait_replug = fake_run_once, fake_replug
    board.find_board = lambda *a, **k: state["port"]
    time.sleep = lambda s: None
    sys.argv = ["flash.py", "--image", f"0x0={IMG}", "--attempts", str(attempts), "--replug-timeout", "60"]
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            rc = flash.main()
    finally:
        flash.run_once, board.wait_replug, board.find_board, time.sleep, sys.argv = real
    return rc, calls, buf.getvalue()


LOCK = (False, "A serial exception error occurred: Write timeout")
OK = (True, "Hash of data verified.")

rc, calls, out = drive([OK], None)
check(rc == 0 and len(calls) == 1, "first round succeeds: exit 0 after one call")
check("flashing on COM11" in out and "OK after" in out, "first round: output names the port and says OK")

rc, calls, out = drive([LOCK, LOCK, LOCK, OK], "COM12")
check(rc == 0, "every round locked, then re-plug, then success: exit 0 (this path used to crash)")
check(len(calls) == 4, f"3 failed rounds plus 1 after the re-plug ({len(calls)} calls)")
check(calls[-1][0] == "COM12", "the call after the re-plug uses the board's NEW port (COM12)")
check([c[1:] for c in calls[:3]] == [("usb_reset", 230400), ("default_reset", 115200), ("no_reset", 115200)],
      "the three rounds use different reset modes and baud rates")
check("PLUG IT BACK IN" in out and "[locked]" in out, "output says why it is waiting and what failed")

rc, calls, out = drive([LOCK], None)
check(rc == 1 and len(calls) == 3, "locked and never re-plugged: exit 1 after the rounds, no crash")
check("No re-plug was seen" in out and "FLASH FAILED" in out, "output says no re-plug was seen and that it failed")

rc, calls, out = drive([(False, "could not open port 'COM11': PermissionError(13, 'Access is denied.'")], None)
check(rc == 2 and len(calls) == 1, "port held by another program: stops at once with exit 2, no retries")
check("serial monitor" in out, "...and tells you to close the serial monitor")

rc, calls, out = drive([LOCK, OK], None)
check(rc == 0 and len(calls) == 2, "fails once, then succeeds in round 2: exit 0, no re-plug needed")
check(all(l.startswith("[") for l in out.splitlines() if l.strip()), "every printed line carries a timestamp")

print("\nflash.py tests", "PASSED" if not fails else f"FAILED ({fails})")
sys.exit(1 if fails else 0)
