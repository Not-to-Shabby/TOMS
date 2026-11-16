"""Tests for judge.py's watchdog attribution, using synthetic logs (no hardware needed).

The evidence is the chip's ROM banner ("rst:0x7 (TG0WDT_SYS_RST),boot:...") because it is
printed before any firmware runs. The app's own "reset cause raw" line is NOT evidence: in the
2026-10-31 run it read 0x13 (clock glitch) while the ROM said 0x7 (main watchdog).
"""
import sys

sys.path.insert(0, "D:/TOMS/firmware/tools")
import judge  # noqa: E402

fails = 0


def check(cond, what):
    global fails
    print(("PASS  " if cond else "FAIL  ") + what)
    fails += not cond


FPS = {"good-1": (196866, 36208), "good-2": (196866, 36208), "bad-hang": (196910, 36272)}


def boot(fp):
    return (f"I (6411) boot: Loading image 0 - slot 0 from flash, area id: 2\n"
            f"I (6412) boot: IROM segment: paddr=00040000h, vaddr=42000000h, size={fp[0]:X}h ({fp[0]}) map\n"
            f"I (6413) boot: DROM segment: paddr=00080000h, vaddr=3c040000h, size={fp[1]:X}h ({fp[1]}) map\n")


def rom(code, name, pc=0x42003163):
    return (f"ESP-ROM:esp32s3-20210327\nBuild:Mar 27 2021\n"
            f"rst:0x{code:x} ({name}),boot:0x28 (SPI_FAST_FLASH_BOOT)\nSaved PC:0x{pc:x}\nSPIWP:0xee\n")


def app_good(app_raw=None):
    out = boot(FPS["good-1"])
    out += "[00:00:00.460,000] <inf> trial: build tag: good-1\n[00:00:00.460,000] <inf> trial: image state: CONFIRMED\n"
    if app_raw is not None:
        out += f"[00:00:00.398,000] <inf> main: reset cause raw 0x{app_raw:02x}: whatever the app thought\n"
    return out


BAD = boot(FPS["bad-hang"])  # the hung image prints nothing


def hang(rom_text, app_raw=None):
    return judge.judge("hang", [BAD + rom_text + app_good(app_raw), ""], FPS)


def wd_check(r):
    return next((c for c in r.checks if "WATCHDOG reset" in c[0]), None)


# 1. the ROM says watchdog: PASS, even though the app misread it (this is the real 2026-10-31 case)
r = hang(rom(0x7, "TG0WDT_SYS_RST"), app_raw=0x13)
check(r.ok and wd_check(r) and wd_check(r)[1], "ROM rst:0x7 (main watchdog), app misread 0x13: PASS (the real hang run)")
check(any("not reliable" in n for n in r.notes), "  ...and a note says the app's own reading disagreed and is not used")
check(any("trial_start" in n or "0x42003163" in n for n in r.notes), "  ...and the Saved PC is reported as a note")

# 2. each watchdog flavour in the ROM's naming
for code, name in ((0x7, "TG0WDT_SYS_RST"), (0x8, "TG1WDT_SYS_RST"), (0x9, "RTCWDT_SYS_RST"),
                   (0xB, "TG0WDT_CPU_RST"), (0xD, "RTCWDT_CPU_RST"), (0x10, "RTCWDT_RTC_RST"),
                   (0x12, "SUPER_WDT_RST")):
    r = hang(rom(code, name))
    check(r.ok, f"ROM rst:0x{code:x} ({name}) counts as a watchdog")

# 3. non-watchdog ROM causes must FAIL the hang scenario, whatever the app says
for code, name in ((0xC, "RTC_SW_CPU_RST"), (0x3, "RTC_SW_SYS_RST"), (0x1, "POWERON_RST"),
                   (0x15, "USB_UART_CHIP_RST"), (0xF, "RTCWDT_BROWN_OUT_RST")):
    r = hang(rom(code, name), app_raw=0x07)  # app claims watchdog; ROM disagrees: ROM wins
    check(not r.ok, f"ROM rst:0x{code:x} ({name}) with the app claiming a watchdog: FAIL (ROM wins)")

# 4. no ROM banner captured (the usual case, USB serial does not exist yet): the scenario still
#    passes on the revert, says the watchdog is NOT confirmed, and adds no check it cannot support
r = hang("", app_raw=0x07)
check(r.ok, "no ROM banner captured: the scenario still passes on the revert itself")
check(any("NOT confirmed" in n for n in r.notes), "  ...but says in a note that the watchdog is NOT confirmed")
check(wd_check(r) is None, "  ...and does not add a watchdog check, even though the app claimed 0x07")

# 5. a banner from BEFORE the bad image ran must not be used
r = judge.judge("hang", [rom(0x7, "TG0WDT_SYS_RST") + BAD + app_good(), ""], FPS)
check(wd_check(r) is None, "a watchdog banner printed before the bad image ran is ignored")

# 6. a banner AFTER good-1 came back (a later reset) must not be used either
r = judge.judge("hang", [BAD + app_good() + rom(0x7, "TG0WDT_SYS_RST"), ""], FPS)
check(wd_check(r) is None, "a watchdog banner printed after good-1 was already back is ignored")

# 7. two resets in a row: the FIRST banner after the bad image is the one that ended it
r = judge.judge("hang", [BAD + rom(0x7, "TG0WDT_SYS_RST") + rom(0x15, "USB_UART_CHIP_RST") + app_good(), ""], FPS)
check(r.ok and wd_check(r) and wd_check(r)[1], "watchdog reset then a USB reset: the first banner decides: PASS")

# 8. other scenarios do not get the watchdog check
for n, fp in (("crash", (196910, 36240)), ("selftest", (196830, 36208))):
    fps2 = dict(FPS, **{f"bad-{n}": fp})
    r = judge.judge(n, [boot(fp) + rom(0xC, "RTC_SW_CPU_RST") + app_good(), ""], fps2)
    check(wd_check(r) is None, f"{n}: no watchdog check added")

# 9. Saved PC to a source line, against the real hang build (skipped when the build is missing)
where = judge.resolve_pc("bad-hang", 0x42003163)
if where is None:
    print("skip  resolve_pc: the bad-hang ELF is not available here")
else:
    check("trial_start" in where, f"Saved PC 0x42003163 resolves into trial_start ({where[:50]}); the line moves with every rebuild")
check(judge.resolve_pc("no-such-build", 0x1234) is None, "an unknown build resolves to None, not an exception")

print("\njudge.py tests", "PASSED" if not fails else f"FAILED ({fails})")
sys.exit(1 if fails else 0)
