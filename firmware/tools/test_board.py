"""Tests for board.py, using a fake USB port list and a fake clock (no hardware needed)."""
import io
import sys
import contextlib
from types import SimpleNamespace as P

sys.path.insert(0, "D:/TOMS/firmware/tools")
import board

fails = 0


def check(cond, what):
    global fails
    print(("PASS  " if cond else "FAIL  ") + what)
    fails += not cond


def port(dev, vid=0x303A, desc="USB Serial Device", serial="9C:13"):
    return P(device=dev, vid=vid, description=desc, serial_number=serial)


class Sim:
    """A scripted USB bus: at simulated time t the list of ports is script[t]."""

    def __init__(self, script):
        self.script, self.t = sorted(script.items()), 0.0

    def list_fn(self):
        cur = []
        for t, ports in self.script:
            if t <= self.t:
                cur = ports
        return cur

    def sleep(self, s):
        self.t += s

    def clock(self):
        return self.t


def run(script, timeout=60):
    sim = Sim(script)
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        r = board.wait_replug(timeout, list_fn=sim.list_fn, sleep=sim.sleep, clock=sim.clock)
    return r, buf.getvalue()


other = port("COM3", vid=0x0403, desc="some other USB serial adapter")

# 1. normal unplug and replug, same COM number
r, out = run({0: [port("COM11")], 5: [], 9: [port("COM11")]})
check(r == "COM11", "normal re-plug: returns COM11")
check("DISAPPEARED" in out and "APPEARED as COM11" in out, "normal re-plug: both events are reported")

# 2. Windows hands the board a different COM number on return
r, out = run({0: [port("COM11")], 5: [], 9: [port("COM12")]})
check(r == "COM12", "COM number changes: returns the NEW port (COM12), not COM11")

# 3. a re-plug so fast the poll never sees the gap, but the port changed
r, out = run({0: [port("COM11")], 5: [port("COM9")]})
check(r == "COM9", "port changed with no gap seen: treated as a re-plug")

# 4. the cable is never touched
r, out = run({0: [port("COM11")]}, timeout=20)
check(r is None, "no re-plug: gives up with None, does not return the old port")
check("still waiting" in out and "gave up" in out, "no re-plug: status lines and a give-up message were printed")

# 5. other USB serial devices must not count
r, out = run({0: [port("COM11"), other], 5: [other], 9: [other, port("COM11")]})
check(r == "COM11", "an unrelated USB serial device is ignored")
r, out = run({0: [other]}, timeout=10)
check(r is None and "board is not present now" in out, "only an unrelated device present: board treated as absent")

# 6. board already absent when we start: only wait for it to appear
r, out = run({0: [], 6: [port("COM11")]})
check(r == "COM11" and "board is not present now" in out, "board already unplugged: waits for it to appear")

# 7. the board must be there to be 'back': a gap alone is not success
r, out = run({0: [port("COM11")], 5: []}, timeout=15)
check(r is None, "unplugged and never returned: not success")

# 8. quiet waits are never silent: a status line at least every 5 s
r, out = run({0: [port("COM11")]}, timeout=30)
n = out.count("still waiting")
check(n >= 5, f"status line roughly every 5 s over 30 s ({n} lines)")

# 9. the real detection filter
check(board.find_board(lambda: [other]) is None, "find_board ignores non-Espressif ports")
check(board.find_board(lambda: [other, port("COM7")]) == "COM7", "find_board picks the Espressif one among several")
check(board.describe(lambda: []) == "no Espressif USB serial port present", "describe() with nothing present")

# 10. timestamps are on every line
check(all(l.startswith("[") for l in out.splitlines() if l.strip()), "every printed line carries a timestamp")

print("\nboard.py tests", "PASSED" if not fails else f"FAILED ({fails})")
sys.exit(1 if fails else 0)
