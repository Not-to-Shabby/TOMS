"""Find and watch the Return Machine's USB serial port.

Shared by flash.py and phase2_test.py. The board is found by its USB vendor ID (0x303A,
Espressif), not by the COM number: Windows keeps ghost entries for this chip and can hand it a
different COM number after a re-plug, which a name-based wait never notices.
"""
import re
import sys
import time

ESPRESSIF_VID = 0x303A
ANSI = re.compile(r"\x1b\[[0-9;]*[mK]")
_T0 = time.time()


def say(msg="", end="\n"):
    """Print with seconds since the script started, flushed so it shows up immediately."""
    if msg == "":
        print(flush=True)
        return
    print(f"[{time.time() - _T0:6.1f}s] {msg}", end=end, flush=True)


def list_boards(list_fn=None):
    """Espressif serial ports currently present, as a list of (device, description, serial)."""
    if list_fn is None:
        from serial.tools import list_ports

        list_fn = list_ports.comports
    out = []
    for p in list_fn():
        if getattr(p, "vid", None) == ESPRESSIF_VID:
            out.append((p.device, getattr(p, "description", ""), getattr(p, "serial_number", None)))
    return out


def find_board(list_fn=None):
    """The board's current port name, or None."""
    boards = list_boards(list_fn)
    return boards[0][0] if boards else None


def describe(list_fn=None):
    b = list_boards(list_fn)
    if not b:
        return "no Espressif USB serial port present"
    return "; ".join(f"{d} ({desc}, serial {s})" for d, desc, s in b)


def wait_replug(timeout=180.0, list_fn=None, poll=0.05, sleep=time.sleep, clock=time.time):
    """Wait for the board to disappear and appear again. Returns the new port name or None.

    Reports every change it sees and a status line every 5 s, so a silent wait is never silent.
    If the board is already gone it only waits for it to appear. Works when Windows gives the
    board a different COM number on return."""
    t0 = clock()
    start = find_board(list_fn)
    say(f"watching USB: {describe(list_fn)}")
    if start is None:
        say("board is not present now; waiting for it to appear")
    else:
        say(f"waiting for {start} to disappear (unplug the cable), then reappear (plug it in)")
    seen_gone = start is None
    last = start
    last_note = t0
    while clock() - t0 < timeout:
        cur = find_board(list_fn)
        if cur != last:
            if cur is None:
                say(f"board DISAPPEARED (was {last}): cable is out")
                seen_gone = True
            elif last is None:
                say(f"board APPEARED as {cur}")
            else:
                say(f"board changed port {last} -> {cur} without a gap being seen")
                seen_gone = True
            last = cur
        if seen_gone and cur is not None:
            sleep(1.0)  # let Windows finish setting the port up
            final = find_board(list_fn) or cur
            if final != cur:
                say(f"port settled as {final}")
            return final
        if clock() - last_note >= 5.0:
            last_note = clock()
            state = f"present as {cur}" if cur else "absent"
            need = "plug it back in" if seen_gone else "unplug it"
            say(f"still waiting ({clock() - t0:.0f}/{timeout:.0f}s): board {state}; {need}")
        sleep(poll)
    say(f"gave up after {timeout:.0f}s: seen_gone={seen_gone}, board {describe(list_fn)}")
    return None


def capture(seconds, sink=None, stop_when=None, quiet_note=10.0, show=True, port=None,
            list_fn=None):
    """Read the board's serial log and show it live. Returns the cleaned text.

    Reopens the port as it re-enumerates (looking it up by USB ID each time, so a changed COM
    number does not stop it). `stop_when(text)` ends the capture early when it returns True.
    A silent stretch prints a note every `quiet_note` seconds. Every line is also written to
    `sink` (an open text file) when given."""
    import serial

    t0 = time.time()
    buf, text = b"", ""
    s, name, got_any, last_note, opens = None, None, False, t0, 0
    while time.time() - t0 < seconds:
        if s is None:
            name = port or find_board(list_fn)
            if name is None:
                if show and time.time() - last_note >= quiet_note:
                    last_note = time.time()
                    say(f"  (no board port; {time.time() - t0:.0f}/{seconds:.0f}s)")
                time.sleep(0.1)
                continue
            try:
                s = serial.Serial(name, 115200, timeout=0.1)
                opens += 1
                if show:
                    say(f"  opened {name} (open #{opens})")
            except Exception as e:
                if show and opens == 0 and time.time() - last_note >= quiet_note:
                    last_note = time.time()
                    say(f"  cannot open {name}: {type(e).__name__}: {str(e)[:70]}")
                time.sleep(0.2)
                continue
        try:
            chunk = s.read(4096)
        except Exception:
            if show:
                say("  port dropped (the board resets or re-enumerates); reopening")
            try:
                s.close()
            except Exception:
                pass
            s = None
            continue
        if chunk:
            got_any = True
            last_note = time.time()
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                clean = ANSI.sub("", line.decode(errors="replace")).rstrip("\r")
                text += clean + "\n"
                if sink:
                    sink.write(f"[{time.time() - t0:6.1f}] {clean}\n")
                    sink.flush()
                if show and clean.strip():
                    say(f"  board| {clean[:150]}")
            if stop_when and stop_when(text):
                if show:
                    say("  (enough output to decide, stopping this capture early)")
                break
        elif show and time.time() - last_note >= quiet_note:
            last_note = time.time()
            what = "no output yet" if not got_any else "no new output"
            say(f"  ({what}; {time.time() - t0:.0f}/{seconds:.0f}s, port {name})")
    if s is not None:
        try:
            s.close()
        except Exception:
            pass
    return text
