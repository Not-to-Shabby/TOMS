#!/usr/bin/env python3
"""Decide whether an OTA phase 2 scenario passed, from what the board printed.

Why this is not just "look for the bad image's name in the log":
  * Early boot lines are often lost. USB serial only exists once Windows has enumerated the
    port, so anything the app prints in its first second never reaches the host. The 10 s
    heartbeat line carries the image name and state ("image good-2 (confirmed)"), so it counts
    as evidence too.
  * A hung image may print nothing at all. The bootloader's own "Loading image" lines still
    show the size of the code and data it mapped, and those differ per image. They are matched
    to each image's ELF, so "the bad-hang image started, then the good one did" can be shown
    even from a log with no app lines.

Used by phase2_test.py. `phase2_test.py --replay` runs it on the logs already saved in
firmware/build/phase2/logs, without touching the board.
"""
import json
import re
import subprocess
from pathlib import Path

BUILD_ROOT = Path("D:/TOMS/firmware/build/phase2")
READELF = ("D:/zephyr-sdk-0.17.2/xtensa-espressif_esp32s3_zephyr-elf/bin/"
           "xtensa-espressif_esp32s3_zephyr-elf-readelf.exe")
BUILDS = ("good-1", "good-2", "bad-crash", "bad-hang", "bad-noconfirm", "bad-selftest")

RE_TAG = re.compile(r"trial: build tag: (\S+)")
RE_STATE = re.compile(r"trial: image state: (CONFIRMED|ON TRIAL)")
RE_HB = re.compile(r"heartbeat #\d+: uptime \d+ s, reset (\S+)(?: \(raw (0x[0-9a-fA-F]+)\))?,.*? image (\S+) \((confirmed|ON TRIAL)\)")
RE_CAUSE = re.compile(r"main: reset cause raw (0x[0-9a-fA-F]+): (.*)")
# The chip's ROM prints these on every reset, before any firmware runs. They are the ground
# truth for why the chip reset, and they carry the address the CPU was at ("Saved PC").
RE_ROM = re.compile(r"rst:0x([0-9a-fA-F]+) \((\w+)\),boot:")
RE_SAVEDPC = re.compile(r"Saved PC:0x([0-9a-fA-F]+)")
ADDR2LINE = ("D:/zephyr-sdk-0.17.2/xtensa-espressif_esp32s3_zephyr-elf/bin/"
             "xtensa-espressif_esp32s3_zephyr-elf-addr2line.exe")
RE_LOAD = re.compile(r"boot: Loading image 0")
RE_IROM = re.compile(r"boot: IROM segment: .*?size=([0-9A-Fa-f]+)h")
RE_DROM = re.compile(r"boot: DROM segment: .*?size=([0-9A-Fa-f]+)h")

# One line the injected fault prints, per scenario (None: a hung image cannot be relied on to
# print anything). Missing lines are reported but do not fail a scenario on their own.
FAULT_LINE = {
    "crash": "FAULT INJECTION: crashing now",
    "hang": None,
    "noconfirm": "will NOT confirm",
    "selftest": "self-test FAILED",
}


def fingerprints(root=BUILD_ROOT):
    """{image name: (code bytes, data bytes)} as the bootloader prints them for that image.

    Read from each build's ELF: the code segment (vaddr 0x42000000) exactly, the data segment
    (vaddr 0x3c......) rounded up to 16. That rule was checked against every "Loading image"
    block in the saved logs. good-1 and good-2 have the same numbers (same code, different tag
    text of equal length), so the bootloader lines cannot tell those two apart."""
    cache = Path(root) / "fingerprints.json"
    out = {}
    for name in BUILDS:
        elf = Path(root) / name / "return-machine/zephyr/zephyr.elf"
        if not elf.exists():
            continue
        try:
            text = subprocess.run([READELF, "-lW", str(elf)], capture_output=True, text=True).stdout
        except OSError:
            break
        irom = drom = None
        for line in text.splitlines():
            f = line.split()
            if len(f) >= 6 and f[0] == "LOAD":
                vaddr, filesz = int(f[2], 16), int(f[4], 16)
                if vaddr == 0x42000000:
                    irom = filesz
                elif 0x3C000000 < vaddr < 0x3D000000 and filesz:
                    drom = (filesz + 15) // 16 * 16
        if irom and drom:
            out[name] = (irom, drom)
    if out:
        try:
            cache.write_text(json.dumps(out, indent=1))
        except OSError:
            pass
        return out
    if cache.exists():
        return {k: tuple(v) for k, v in json.loads(cache.read_text()).items()}
    return {}


def _event(cap, kind, who, state, line, **extra):
    return {"cap": cap, "kind": kind, "who": set(who), "state": state, "line": line.strip(), **extra}


def parse(logs, fps):
    """Turn a list of captured logs (one string each) into ordered events."""
    by_fp = {}
    for n, fp in fps.items():
        by_fp.setdefault(fp, set()).add(n)
    events = []
    for cap, text in enumerate(logs):
        cur_tag, pending = None, None
        for line in text.splitlines():
            m = RE_TAG.search(line)
            if m:
                cur_tag = m.group(1)
                events.append(_event(cap, "tag", {cur_tag}, None, line))
                continue
            m = RE_STATE.search(line)
            if m and cur_tag:
                events.append(_event(cap, "state", {cur_tag}, m.group(1), line))
                continue
            m = RE_ROM.search(line)
            if m:
                events.append(_event(cap, "rom", set(), None, line, raw=int(m.group(1), 16),
                                     name=m.group(2), pc=None))
                continue
            m = RE_SAVEDPC.search(line)
            if m:
                if events and events[-1]["kind"] == "rom" and events[-1]["cap"] == cap:
                    events[-1]["pc"] = int(m.group(1), 16)
                continue
            m = RE_CAUSE.search(line)
            if m:
                events.append(_event(cap, "cause", set(), None, line, raw=int(m.group(1), 16), text=m.group(2)))
                continue
            m = RE_HB.search(line)
            if m:
                raw = int(m.group(2), 16) if m.group(2) else None
                events.append(_event(cap, "hb", {m.group(3)}, m.group(4).upper(), line, reset=m.group(1), raw=raw))
                continue
            if RE_LOAD.search(line):
                pending = {"irom": None, "drom": None, "line": line}
                continue
            if pending is not None:
                m = RE_IROM.search(line)
                if m:
                    pending["irom"] = int(m.group(1), 16)
                m = RE_DROM.search(line)
                if m:
                    pending["drom"] = int(m.group(1), 16)
                if pending["irom"] and pending["drom"]:
                    fp = (pending["irom"], pending["drom"])
                    events.append(_event(cap, "boot", by_fp.get(fp, set()), None, pending["line"], fp=fp))
                    pending = None
    return events


def describe(e):
    c = e["cap"] + 1
    if e["kind"] == "boot":
        who = "/".join(sorted(e["who"])) or "an image I do not recognise"
        return (f"capture {c}: the bootloader started an image with code {e['fp'][0]} B and "
                f"data {e['fp'][1]} B ({who})")
    if e["kind"] == "tag":
        return f"capture {c}: the app printed 'build tag: {next(iter(e['who']))}'"
    if e["kind"] == "state":
        return f"capture {c}: the app printed 'image state: {e['state']}'"
    if e["kind"] == "rom":
        return f"capture {c}: the chip's ROM printed 'rst:0x{e['raw']:x} ({e['name']})'"
    if e["kind"] == "cause":
        return f"capture {c}: the app printed 'reset cause raw 0x{e['raw']:02x}: {e['text'][:60]}'"
    return (f"capture {c}: heartbeat says image {next(iter(e['who']))} is "
            f"{e['state'].lower()} (reset {e['reset']})")


class Result:
    def __init__(self, name):
        self.name = name
        self.checks = []  # (label, ok, detail, required)
        self.notes = []

    def add(self, label, ok, detail="", required=True):
        self.checks.append((label, bool(ok), detail, required))

    @property
    def ok(self):
        return all(ok for _l, ok, _d, req in self.checks if req) and any(req for *_x, req in self.checks)

    def lines(self):
        out = []
        for label, ok, detail, req in self.checks:
            mark = "PASS" if ok else ("FAIL" if req else "note")
            out.append(f"  [{mark}] {label}" + (f": {detail}" if detail else ""))
        out += [f"  [note] {n}" for n in self.notes]
        return out

    def summary(self):
        failed = [l for l, ok, _d, req in self.checks if req and not ok]
        if self.ok:
            return "every required check passed"
        return "failed: " + "; ".join(failed) if failed else "no evidence at all"


def _ident(events):
    return [e for e in events if e["kind"] in ("tag", "hb", "boot")]


def watchdog_raws():
    """Raw reset causes the firmware's own table (lib/toms_core/src/reset.c) calls a watchdog."""
    src = Path("D:/TOMS/firmware/lib/toms_core/src/reset.c")
    try:
        return {int(m.group(1), 16) for m in re.finditer(r"\{(0x[0-9A-Fa-f]+), TOMS_RESET_WATCHDOG", src.read_text())}
    except OSError:
        return {0x07, 0x08, 0x09, 0x0B, 0x0D, 0x10, 0x11, 0x12}


def revert_cause(ev, is_bad, is_good):
    """What ended the bad image: (ROM banner event, app-reported raw or None), or None.

    The evidence is the FIRST ROM banner ("rst:0x.. (NAME)") printed after the bad image's own
    boot lines and before good-1 came back: that is the reset that ended the bad image. The
    app's own "reset cause raw" value is returned alongside, for comparison only. It is not
    evidence: in the 2026-10-31 hang run the app read 0x13 (clock glitch) while the ROM said
    0x7 (main watchdog), and the app read 'other' after every revert since the start."""
    first_bad = next((i for i, e in enumerate(ev) if e["kind"] in ("tag", "hb", "boot") and is_bad(e)), None)
    if first_bad is None:
        return None
    first_good = next((i for i in range(first_bad + 1, len(ev))
                       if ev[i]["kind"] in ("boot", "tag", "hb") and is_good(ev[i])), None)
    if first_good is None:
        return None
    rom = next((ev[i] for i in range(first_bad + 1, first_good) if ev[i]["kind"] == "rom"), None)
    if rom is None:
        return None
    app_raw = None
    for i in range(first_good, len(ev)):
        if ev[i]["kind"] in ("boot", "tag", "hb") and is_bad(ev[i]):
            break
        if ev[i].get("raw") is not None and ev[i]["kind"] in ("cause", "hb"):
            app_raw = ev[i]["raw"]
            break
    return rom, app_raw


def resolve_pc(build, pc, root=BUILD_ROOT):
    """'function file:line' for an address in one build's ELF, or None when it cannot be done."""
    elf = Path(root) / build / "return-machine/zephyr/zephyr.elf"
    if not elf.exists():
        return None
    try:
        out = subprocess.run([ADDR2LINE, "-f", "-p", "-e", str(elf), f"0x{pc:x}"],
                             capture_output=True, text=True, timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    if not out or out.startswith("??"):
        return None
    return out.replace("D:/TOMS/firmware/return-machine/", "")


def judge(name, logs, fps):
    """Judge scenario `name` (good, crash, hang, noconfirm, selftest) from its captured logs."""
    ev = parse(logs, fps)
    ident = _ident(ev)
    r = Result(name)
    alltext = "\n".join(logs)
    if not ident:
        r.add("any sign of which image ran", False, "no tag, heartbeat or bootloader line in the logs")
        return r

    if name == "good":
        new = "good-2"
        first_new = next((e for e in ident if e["kind"] in ("tag", "hb") and e["who"] == {new}), None)
        r.add("the new image (good-2) ran", first_new is not None,
              describe(first_new) if first_new else "never seen")
        line = next((ln for ln in alltext.splitlines() if "self-test passed: image CONFIRMED" in ln), None)
        r.add("it passed its self-test and confirmed itself", line is not None,
              line.strip()[:110] if line else "no 'self-test passed: image CONFIRMED' line")
        last_cap = len(logs) - 1
        late = [e for e in ev if e["cap"] == last_cap and e["kind"] == "hb" and e["who"] == {new}]
        r.add("after a later power cycle it was still good-2 and confirmed",
              bool(late) and all(e["state"] == "CONFIRMED" for e in late),
              describe(late[-1]) if late else "no heartbeat from good-2 in the last capture")
        reverted = [e for e in ident if e["kind"] in ("tag", "hb") and e["who"] == {"good-1"}]
        r.add("it was never reverted to good-1", not reverted,
              describe(reverted[0]) if reverted else "", required=True)
        return r

    bad = f"bad-{name}"

    def is_bad(e):
        return e["who"] == {bad}

    def is_good(e):
        return "good-1" in e["who"] and bad not in e["who"]

    first_bad = next((i for i, e in enumerate(ident) if is_bad(e)), None)
    good_idx = [i for i, e in enumerate(ident) if is_good(e)]
    r.add(f"the bad image ({bad}) actually ran", first_bad is not None,
          describe(ident[first_bad]) if first_bad is not None else "never seen")
    reverted = first_bad is not None and good_idx and good_idx[-1] > first_bad
    r.add("the board then ran good-1 again", reverted,
          describe(ident[good_idx[-1]]) if good_idx else "good-1 never seen")
    after = ident[good_idx[-1] + 1:] if good_idx else []
    r.add("the bad image did not come back afterwards", not any(is_bad(e) for e in after))
    last_app = next((e for e in reversed(ev) if e["kind"] in ("tag", "hb", "state")), None)
    if last_app is not None and last_app["who"] == {"good-1"}:
        r.add("good-1 is confirmed, not on trial", last_app["state"] in (None, "CONFIRMED"),
              describe(last_app), required=False)

    if name != "noconfirm":
        c0 = [e for e in ident if e["cap"] == 0]
        b0 = next((i for i, e in enumerate(c0) if is_bad(e)), None)
        g0 = next((i for i, e in enumerate(c0) if b0 is not None and i > b0 and is_good(e)), None)
        r.add("it went back to good-1 by itself, in the first capture, before anyone touched the board",
              g0 is not None, describe(c0[g0]) if g0 is not None else "the first capture does not show it")
    else:
        confirmed_bad = [e for e in ev if is_bad(e) and e.get("state") == "CONFIRMED"]
        r.add("the bad image never confirmed itself", not confirmed_bad,
              describe(confirmed_bad[0]) if confirmed_bad else "")
        if reverted:
            r.notes.append("this scenario has no reset of its own: the revert happens at the next reset, "
                           "which in this test is caused by re-opening the serial port")

    want = FAULT_LINE.get(name)
    if want:
        seen = next((ln for ln in alltext.splitlines() if want in ln), None)
        r.add(f"the injected fault ran ('{want}')", seen is not None,
              seen.strip()[:100] if seen else "line not seen (it may have been lost)", required=False)
    if name == "hang":
        found = revert_cause(ev, is_bad, is_good)
        wd = watchdog_raws()
        if found is None:
            r.notes.append("the chip's ROM banner for the reset that ended the bad image was not captured "
                           "(it is printed before USB serial exists and is often lost), so the watchdog is "
                           "NOT confirmed by this run")
        else:
            rom, app_raw = found
            raw = rom["raw"]
            r.add("the revert was caused by a WATCHDOG reset (from the chip's own ROM banner)", raw in wd,
                  f"{describe(rom)} = {'a watchdog value' if raw in wd else 'NOT a watchdog value'}")
            if rom.get("pc") is not None:
                where = resolve_pc(bad, rom["pc"])
                r.notes.append(f"the chip was at 0x{rom['pc']:08x} when it was reset"
                               + (f": {where}" if where else " (could not resolve it in the build's ELF)"))
            if app_raw is not None and app_raw != raw:
                r.notes.append(f"the app read raw 0x{app_raw:02x} for that reset but the ROM said 0x{raw:02x}: "
                               "the app's own reading is not reliable and is not used as evidence")
        stage0 = next((ln for ln in alltext.splitlines() if "WATCHDOG STAGE 0 FIRED" in ln), None)
        if stage0:
            r.notes.append("watchdog stage 0 warning was logged: " + stage0.strip()[:100])
    if name == "hang":
        app_for_bad = [e for e in ident if e["kind"] in ("tag", "hb") and is_bad(e)]
        if not app_for_bad:
            r.notes.append("the hung image printed no app lines at all; only the bootloader's own lines "
                           "show that it ran")
    unknown = [e for e in ident if e["kind"] == "boot" and not e["who"]]
    if unknown:
        r.notes.append(f"{len(unknown)} bootloader block(s) match no known image: "
                       f"{[e['fp'] for e in unknown][:3]}")
    return r
