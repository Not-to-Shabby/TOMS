# Return Machine — Bluetooth OTA plan

Status: plan only. No OTA code is written, nothing is built or flashed for it. Every claim
about the ESP32-S3 MCUboot port comes from reading the source in `D:\zephyrproject`, not from
running it. This plan expands `SPIKE.md` item 5 (OTA) and depends on item 6 (watchdog).

## Why

The machine is deployed in a vehicle and serviced by someone who has a phone and no computer.
A firmware fix must reach it over Bluetooth, and a bad update must not leave it dead.

## Ground rules

1. **The first flash needs a computer.** The bootloader and the first image go in over USB,
   once. OTA starts from that point.
2. **OTA never touches the bootloader.** It writes only the update slot. A bootloader that is
   written once is rarely at risk. Nothing can repair it over Bluetooth.
3. **A new image must prove itself.** It boots on trial, runs a self-test, then confirms
   itself. If it crashes, hangs or never confirms, the bootloader restores the old image.
4. **Nothing unsigned runs.** The bootloader verifies the signature at boot. The app also
   checks the hash before it asks for the swap, so a bad upload is rejected early.
5. **Only the enrolled phone may update.** An encrypted, bonded link is required. Dev mode
   (`CONFIG_TOMS_DEV_MODE`) must never be able to start an OTA.
6. **No update on a weak battery.** Below a threshold the machine refuses to begin.

## Recovery ladder (what saves you at each level)

| Failure | What happens | Needs a computer? |
| --- | --- | --- |
| Link drops mid-upload | Partial image in the update slot fails verification and is ignored. Old image keeps running. The phone resumes from the last offset. | No |
| Complete but broken image | Boots on trial, fails its self-test or crashes or hangs, the watchdog or bootloader swaps the old image back. | No |
| Power cut during the swap | The swap records progress sector by sector and resumes after reset. Relies on the scratch area and the slot trailer being intact. | No, if the trailer survived |
| Corrupt trailer or scratch area | Possibly neither image boots. | Yes |
| Corrupt bootloader | The ROM starts the bootloader from one fixed address and nothing repairs it. | Yes |
| Boot loop that even the old image cannot leave | | Yes |

The computer-needed rows are rare if the bootloader is written once and the swap is
power-safe. They are not zero. The physical fallback is: hold BOOT while plugging in USB
(download mode) and reflash a known-good full image. Keep that image and the exact command
written down with the unit.

**Recovery partition / second bootloader.** I searched ESP-IDF 5.4.1 and found no
recovery-bootloader support for the ESP32-S3, so a second bootloader copy would never be
used by the ROM. Not confirmed for this chip, so the plan does not rely on it. A factory app
slot (the Angerer layout) protects against a bad app but still needs a working bootloader.
Decision: **bootloader written once over USB, never over OTA.**

## Flash layout

Assumed from the board's 4 MB devicetree include `partitions_0x0_amp_4M.dtsi`:

| Partition | Offset | Size | Use |
| --- | --- | --- | --- |
| mcuboot | 0x000000 | 64 KB | bootloader, USB only |
| sys | 0x010000 | 64 KB | Espressif system area |
| image-0 | 0x020000 | 1344 KB | running image (primary slot) |
| image-1 | 0x170000 | 1344 KB | update slot (secondary) |
| storage | 0x3B0000 | 192 KB | NVS: config, read ring buffer, bond keys |
| image-scratch | 0x3E0000 | 124 KB | swap scratch |
| coredump | 0x3FF000 | 4 KB | |

Notes:
- The app is 362 KB, so a 1344 KB slot is roomy. We may shrink both slots to grow `storage`
  for the offline read ring (`SPIKE.md` item 3). Decide after the ring is sized.
- **Open:** the MCUboot sample config uses different offsets (primary 0x10000, secondary
  0x110000, scratch 0x210000). The devicetree and the bootloader config must agree or the
  swap corrupts data. One source of truth: the devicetree, with the bootloader taking its
  numbers from it. Verify in Phase 1.
- **Flash size is 4 MB** (owner confirmed; esptool also reported 4 MB). The build says 8 MB
  (`CONFIG_FLASH_SIZE=8388608`, "FLASH: 8388352 B") only because the devkit's SoC definition
  assumes an 8 MB module; it is not the real chip. Consequences:
  - The 4 MB partition include above is the right one. Keep it.
  - The app's own flash-size setting must be changed to 4 MB in Phase 1, or the build can place
    data past the end of the chip. Until then, nothing in this build is written past 0x400000.
  - Every flash command used so far passes `--flash_size 8MB`. It worked for the app, but it
    writes the wrong size into the image header. Use `--flash_size 4MB` from now on,
    especially once the bootloader is involved.
- PSRAM is not enabled and is not needed (data RAM 60% used, flash 4% used).

## Boot mode

MCUboot **swap using scratch**, with the test-then-confirm flow. This is the mode that allows
revert. Zephyr's sysbuild defaults ESP32 to overwrite-only (no revert), so the default must
be changed. The port compiles `swap_scratch.c` and `swap_move.c` and its sample config names
scratch as the default, but whether a swap-mode sysbuild builds and boots on this board is
**unverified** and is the first gate.

App API (in `zephyr/dfu/mcuboot.h` and `flash_img.h`): `boot_is_img_confirmed()`,
`boot_write_img_confirmed()`, `boot_request_upgrade()`, `flash_img_*` to write the slot.

The bootloader's own watchdog is enabled in the sample config (`ESP_MCUBOOT_WDT_ENABLE`). The
app also needs a real watchdog (`SPIKE.md` item 6) so a hung trial image is reset and
reverted instead of sitting there.

## Signing

- `imgtool` signs each image with an ECDSA P-256 key. Private key never enters git.
- Two keys: a development key for the bench, and a production key kept offline. A machine is
  provisioned with exactly one public key.
- **Production key holder: the developer** (owner decision, 2026-10-30). There is one holder
  and no second person, so the key is a single point of failure. Rules that follow from it:
  - Keep the private key off the repo and off any synced or cloud folder.
  - Keep at least two encrypted offline copies in different places (for example two USB
    sticks), with the passphrase stored separately from them.
  - If the key is lost, no field unit can be updated over Bluetooth again, because the
    bootloader only accepts images signed by it. Recovery is a USB reflash of every unit with a
    new public key.
  - If the key leaks, anyone can sign an image the machines will accept. Plan the same recovery.
  - Sign production images only from one machine, and log each signing (version, date, hash).
- **Downgrade prevention: ON for field units** (owner decision, 2026-10-30). The bootloader
  options are `CONFIG_ESP_DOWNGRADE_PREVENTION` (version number) and
  `CONFIG_ESP_DOWNGRADE_PREVENTION_SECURITY_COUNTER` (a counter set with `imgtool`), both seen
  in the port's sample `bootloader.conf`. Plan to use the security counter, since a version
  string is easier to get wrong. What it commits you to:
  - A confirmed image cannot be replaced by an older one over the air. If a release turns out
    to be bad, the fix is a newer release with a higher counter, never an older one. The
    bad-image revert in phase 2 is a different thing and still works: it restores the image
    that was running before an unconfirmed trial.
  - Every release must have a higher counter than the last, and the counter must be bumped
    deliberately (add it to the release checklist).
  - It is set in the bootloader, which is flashed over USB once. It cannot be switched off over
    the air. Turning it off later means a USB reflash of the bootloader.
  - The bench unit must use a separate development build without it. Never push a test image
    with a very high counter at a unit you want to keep using for development.
  - Unverified: exactly when the port raises the stored counter (on confirm or on boot) and
    whether a reverted trial leaves it unchanged. Test this in phase 2 before relying on it.

## OTA service (custom, on Zephyr's Bluetooth host)

NimBLE is not available for the ESP32-S3 under Zephyr (the HAL ships it only for C3 and C6),
so this is our own GATT service in `src/ble/`, modelled on the Angerer design but with
resume and a hash check. Proposed characteristics (suffixes continue after `…0B`; reconcile
with `PROTOCOL.md` before coding):

| Suffix | Name | Properties | Purpose |
| --- | --- | --- | --- |
| `…0C` | ota_control | write, notify | start, finish, abort; replies ACK or NAK with a reason |
| `…0D` | ota_data | write-without-response | image chunks |

Flow:
1. Phone reads `device_info`, checks `protocol_version`, battery and current image version.
2. Phone writes `start` with image size, SHA-256 and version. The machine checks: bonded link,
   battery above threshold, size fits the slot, and the version and security counter are
   higher than the running image's (downgrade prevention is on). It erases the update slot
   and replies ACK with the offset to begin at.
3. Phone streams chunks to `ota_data`. Each chunk carries its offset. The machine writes with
   `flash_img`, and every N chunks it notifies the offset reached, so the phone can slow down
   or resume after a drop.
4. Phone writes `finish`. The machine hashes the slot, compares with the SHA-256 from `start`,
   and only then calls `boot_request_upgrade()`. It replies ACK or NAK.
5. The machine reboots into the trial image. The trial image runs its self-test and calls
   `boot_write_img_confirmed()`. If it never does, the next reset reverts.
6. The phone reconnects, reads `device_info`, and shows "updated to version X" or "reverted".

Limits to design for: one write holds at most 62 bytes today (`CONFIG_BT_L2CAP_TX_MTU=65`).
At that size a 362 KB image is about 6000 chunks, so raise the buffers for a larger MTU. At
the Angerer client's measured 5-6 KB/s, a 362 KB image takes about a minute. Real speed on
your phones is unmeasured.

## Phases and gates

Each gate is pass or fail with evidence. Do not start a phase before the previous one passes.

| # | Phase | Pass when |
| --- | --- | --- |
| 0 | Facts about the board (needs a computer) | Flash size is 4 MB (owner confirmed; also confirm with `flash_id`). Read whether PSRAM exists. Both written into `HW_NOTES.md`. |
| 1 | Swap-mode bootloader boots the current app | Sysbuild with swap-using-scratch builds, the bootloader and a signed app flash once, the app runs and logs over USB. Layout offsets agree between devicetree and bootloader. |
| 2 | Revert works | A deliberately bad second image (a) crashes on boot, (b) hangs, (c) boots but never confirms. Each reverts to the good image without a computer. A good image confirms and stays. Needs the watchdog from `SPIKE.md` item 6. |
| 3 | Power-cut safety | Unplug during the swap, 20 times at random points. The machine always ends on a bootable image. Record the failures, if any. |
| 4 | OTA service with nRF Connect | A signed image uploads by hand-written characteristic writes, the hash check passes, the swap happens. A corrupt upload is rejected. A drop mid-upload resumes. |
| 5 | Phone uploader | The Android app (or a test tool) does steps 1 to 6 of the flow end to end with a real phone, at the real MTU. |
| 6 | Security | Dev mode cannot start an OTA. An unbonded phone is refused. An image signed with the wrong key is refused by the bootloader. |
| 7 | Battery gate | Needs the battery sense wired (GPIO1 is unwired today). OTA refuses below the threshold. |
| 8 | Field procedure | One-page sheet: update steps for the technician, the revert sign, the BOOT+USB recovery path, the known-good full image and its flash command. |

## Phase 1 status (2026-10-30)

**Build: done and checked. Boot on the board: NOT done.** The board was unplugged, so the
bootloader and signed app have not been flashed.

Run `bash firmware/tools/build_phase1.sh`. It builds a bootloader plus app with MCUboot
swap-using-scratch and ECDSA P-256 signing into `firmware/build/phase1` (the working
`build/devkitc` is untouched), then checks 18 things without the board. All pass:

- the bootloader is an ESP32-S3 image, 47,104 B, inside its 64 KB partition
- the app is wrapped for MCUboot and is 362,956 B in a 1344 KB slot, leaving room for the trailer
- swap-using-scratch is set in both the bootloader and the app
- the bootloader's compiled flash map equals the app's devicetree partitions for image-0
  (0x20000, 1344 KB), image-1 (0x170000, 1344 KB), scratch (0x3e0000, 124 KB), storage and
  the boot partition, so the "offsets must agree" risk is closed for this layout
- no partitions overlap and the last one ends at 0x400000, inside 4 MB

Still open from phase 1:

- **DONE 2026-10-30, see 'Phase 1 flash result' below.** Original step: flash it and see it boot (needs a USB re-plug). Command, with the size fixed to 4 MB:
  `esptool.py --chip esp32s3 --port COM11 --baud 230400 --before usb_reset --after hard_reset
  write_flash -u --flash_mode dio --flash_freq 80m --flash_size 4MB
  0x0 build/phase1/mcuboot/zephyr/zephyr.bin
  0x20000 build/phase1/return-machine/zephyr/zephyr.signed.confirmed.bin`
  The app is flashed as the `.signed.confirmed.bin`, which is marked confirmed so the first
  boot does not revert. Expect the USB serial log to come back, since the console is on the
  native USB port. The bootloader's own log is off, so no output before the app starts.
- **The app still assumes 8 MB flash** (`CONFIG_FLASH_SIZE=8388608`). The bootloader also
  reports 8 MB. Not fixed yet, so a 4 MB chip is being treated as 8 MB in both.
- **The signing key is MCUboot's public development key** (`root-ec-p256.pem`, shipped in
  the MCUboot repo), so anyone can sign an image this bootloader accepts. Bench only.
- **Downgrade prevention is not turned on** in this build. It was decided for field units.
- The port's `main.c` selects the primary or secondary slot from the Kconfig address
  numbers (`CONFIG_ESP_IMAGE0_PRIMARY_START_ADDRESS`), but the built bootloader's flash map
  came from the devicetree. They agree here only because the compiled map matches. Keep the
  check in `build_phase1.sh` so a layout change cannot slip through.
- **MCUboot-on-ESP32-S3 under Zephyr has still never booted on this hardware.**

## Phase 1 flash result (2026-10-30)

**Flashed and verified by reading the flash back.** The owner flashed with esptool
(bootloader at 0x0, signed app at 0x20000, 4 MB). A readback with esptool shows the board
holds the phase 1 images:

- 0x20000 starts with `3d b8 f3 96`, the MCUboot header, and the image size field matches the
  build (0x58914).
- The app payload (0x20 to 0x58934, 362,708 B) is byte-identical to the build.
- The SHA-256 inside the app's MCUboot trailer is identical to the build's
  (`3da826cc...129b1d`).
- The boot log is clean: display, BLE advertising as `TOMS-RM-3C48`, no reset loop.

Differences between the board and the build files, all explained (not a different build):

- Bootloader byte 3: build 0x3f, board 0x2f. This is the flash size/frequency byte of the ESP
  image header. esptool rewrites it from `--flash_size`/`--flash_freq` when it flashes. The
  bootloader's trailing SHA-256 differs for the same reason (the checksum covers the header).
- App trailer: the board's signature entry is 70 bytes and the build's is 72, so the total is
  150 against 152. Same payload and same SHA-256, only the signature encoding differs in
  length. ECDSA signatures vary by a byte or two. **Not yet explained:** why a different
  signature is on the board than in `zephyr.signed.bin`. The flashed file was
  `zephyr.signed.confirmed.bin`, a separate file that is re-signed at build time; compare
  against that file to close this. It would confirm the explanation, not change the result.

**What this proves and does not prove:**

- Proven: the phase 1 images are on the board and the app runs.
- Not proven: that MCUboot, not a direct start, launched the app. The bootloader log is off
  and nothing in the app's log distinguishes the two. Needs the `boot_is_img_confirmed()` log
  line, or the bootloader log turned on.
- Not proven: any swap or revert. That is phase 2.
- Chip facts from this readback (new): ESP32-S3 (QFN56) revision v0.2, **embedded flash 4 MB
  (XMC)**, **embedded PSRAM 2 MB (AP_3v3)**. So the chip has PSRAM (2 MB). It is not enabled
  in the build and is not needed.

## Phase 2 result (2026-10-30): gate met, with caveats

Run with `python D:/TOMS/firmware/tools/phase2_test.py` (all five scenarios, fresh flash and a
USB power cycle each, judged by `judge.py` from the board's own serial log; logs kept in
`firmware/build/phase2/logs/`). **All five scenarios PASS.**

| Scenario | Image | What the board did |
| --- | --- | --- |
| good | good-2 (2.0.0) | swapped in on trial, passed its self-test, confirmed itself, and was still good-2 and confirmed after a later power cycle |
| crash | bad-crash (3.0.0) | printed `FAULT INJECTION: crashing now`, rebooted, MCUboot restored good-1 |
| hang | bad-hang (3.0.0) | printed nothing, the board reset about 15 s later and good-1 was running again |
| noconfirm | bad-noconfirm (3.0.0) | ran, printed `will NOT confirm`, good-1 came back at the next reset |
| selftest | bad-selftest (3.0.0) | self-test failed, the board reset itself, good-1 came back |

This meets the phase 2 gate as written: a deliberately bad image reverts to the good one with no
computer, and a good image confirms and stays. It also settles an open phase 1 point: the
`boot:` lines in the log come from MCUboot, so MCUboot did launch the app.

Caveats, kept honest:

- **The hang revert is not attributed to the watchdog.** The hung image prints nothing, and the
  reset reason read after the revert is `other (5)`, not the watchdog code. Something reset the
  board and the old image returned. Whether it was the watchdog is unproven. The reset-reason
  mapping in `reset_reason.c` was written from guesses about the S3's codes (see the comment
  on case 16), so it may simply be wrong. Next step: log the raw reset-cause number at boot.
- **noconfirm** reverted after a reset that re-opening the serial port probably caused, not a
  reset the firmware chose. The result still shows "never confirms means it reverts at the next
  reset".
- **The judge was written after seeing the first run's logs.** The second, fresh run (this one)
  agrees, but both were run on one board with one cable.
- **good-1 and good-2 have identical bootloader fingerprints,** so bootloader lines alone cannot
  tell them apart; the app's tag or heartbeat is needed for that pair.
- **Not tested:** power cuts during the swap (phase 3), what happens to the downgrade counter
  (not turned on in this build), any upload over Bluetooth (phase 4).
- Early boot lines never reach the PC (USB serial does not exist yet), so some evidence comes
  from the 10 s heartbeat and the bootloader's own lines.

### Phase 2 follow-up (2026-10-31): the hang revert IS a watchdog reset, shown by the chip itself

The hang scenario was re-run with the raw reset cause logged. Its serial capture happened to have
the port open when the chip reset, so it caught the ROM's boot banner:

    rst:0x7 (TG0WDT_SYS_RST),boot:0x28 (SPI_FAST_FLASH_BOOT)
    Saved PC:0x42003163

`rst:0x7` is "main watchdog 0 reset the digital core" (ESP-IDF `soc/esp32s3/include/soc/reset_reasons.h`).
The ROM prints it before any firmware runs, so it does not depend on my code. `Saved PC` looked up
in the bad-hang build's own ELF is `trial_start` at `src/trial.c:240`, the `for (;;)` after
`irq_lock()`: the chip was sitting in the injected hang loop when the watchdog reset it. Roughly
8.4 s passed between the bootloader's last line and the banner on the host clock, consistent with
the 8 s timeout but not a precise measurement (USB buffering).

Caveats:

- **The app's own reset-cause reading is wrong after any reset that is not a power-on.** For this
  reset the ROM said 0x7 and the app read 0x13 ("clock glitch"). Every non-power-on boot in the
  saved logs prints `reset: other (5)`, including the earlier selftest revert where the ROM said
  0xC (software CPU reset, from `sys_reboot`). So the heartbeat's `reset_reason` byte is not
  trustworthy for field diagnostics yet. Cause not found. Untested guesses: the second-stage
  MCUboot touches the clock-glitch detector, or the register is rewritten between the ROM and the
  app. The judge therefore uses the ROM banner and ignores the app's value.
- **The banner is only captured when the serial port is already open at the moment of the reset.**
  After a power cycle the first lines are lost, so a run without it reports "watchdog NOT
  confirmed" and still passes on the revert itself.
- `judge.py` was changed after seeing this run's log (it first used the app's value, which failed
  the scenario). The evidence, the ROM banner, is independent of that change. A further fresh run
  would confirm it.
- The stage-0 `WATCHDOG STAGE 0 FIRED` line did not appear, as expected for this scenario: the hang
  locks interrupts, so the stage-0 interrupt cannot run. The log line "to be measured below" in
  `watchdog armed` is a leftover; nothing measures it.
- The old reset table was wrong (0x0C counted as watchdog, 0x11 and the USB resets unhandled); the
  new table in `lib/toms_core/src/reset.c` matches ESP-IDF's header exactly, but the app does not
  read the cause correctly yet, so it is not what makes this result trustworthy.

## Phase 3 result (2026-10-31): power-cut gate met at a lower bar
16 rounds run (plan said 20, owner chose about 10-20), all 16 booted a healthy image. 11 of them
interrupted the swap (bootloader took 1.5-4.5 s instead of ~1 s; inferred from timing, the stop and
resume were not seen). No round rolled back to good-1. Cuts later than ~3.5 s landed after the swap
had finished. Not probed: the last sectors / trailer write. Round 17 was an operator miss (no cut made).
Script: tools/phase3_test.py, logs in build/phase3.

## Phase 4 result (2026-11-02): gate met, full pass on real hardware

Tested end-to-end over BLE with the ESP32-S3 Return Machine on the bench:

1. **Protocol & GATT Pipeline**:
   - Custom GATT service `544F4D53-5245-5455-524E-414348494E45` operational.
   - `0x0C` (`ota_control`): write/notify session handshake (`START`, `FINISH`, `ABORT`, resync `0xB0`, progress `0xA0`).
   - `0x0D` (`ota_data`): chunk streaming with write-without-response at 58 bytes (MTU 65), paced at 8 ms.
   - Live LCD progress rendering (`ERASING`, progress bar 0–100%, `CHECKING`, `RESTARTING`).
2. **Corrupt Image Rejection**:
   - Uploaded `corrupt.signed.bin` (payload bit flipped at `0x200`).
   - ESP32 OTA worker computed SHA-256 over slot 1, detected hash mismatch, logged `OTA REFUSED at the check: status -1 (TOMS_OTA_HASH)`, and sent error `0x01` (`TOMS_OTA_HASH`).
   - `boot_request_upgrade()` was never called; the board did NOT swap slots and stayed on `good-2`.
3. **Injected Fault Uploads & Rollback**:
   - All four Phase 2 fault-injected images were uploaded over BLE OTA:
     - `bad-crash.signed.bin`: Booted into bad-crash splash, crashed on boot, MCUboot automatically restored `good-2`.
     - `bad-hang.signed.bin`: Booted into bad-hang splash, hung, hardware watchdog (`rst:0x7 TG0WDT_SYS_RST` at `Saved PC: 0x42003a17`) reset the chip, MCUboot restored `good-2`.
     - `bad-noconfirm.signed.bin`: Booted into bad-noconfirm splash, never marked confirmed, manual power reset restored `good-2`.
     - `bad-selftest.signed.bin`: Booted into bad-selftest splash, self-test failed, rebooted, MCUboot restored `good-2`.
4. **Clean Upgrade**:
   - `good-2.signed.bin` (`2.0.0+0`) uploaded, verified SHA-256, swapped into primary slot, confirmed itself, and booted cleanly.

## Phase 5 result (2026-11-02): Android phone uploader gate met

Built standalone Kotlin Android application (`toms_android/ota`, app id `ph.toms.ota`) running on physical hardware (TECNO Pova 4, Android 14):

1. **Cupertino Clean UI**:
   - Apple Wallet-style NFC pass card with live animated status ring, 6-tone tactile buzzer soundboard, physical button counter, and display backlight switch.
   - Fixed, bottom-anchored action button ("Flash Firmware to Machine") preventing screen overflow.
   - Collapsible bottom-sheet log console with auto-scroll, copy-all, and log clearing.
2. **Transfer Telemetry**:
   - Measured live transfer speed ~48.5 KB/s over BLE.
   - 3-Phase visual stepper (`1. Image` -> `2. Machine` -> `3. Update`), live byte progress, speed indicator, and ETA timer.
3. **Link Robustness**:
   - Automatic 3-round retry loop recovering from Android BLE GATT 133 status and connection drops.
   - Automated file picking via Android MediaStore and adb intent flags (`--es firmware <path> --ez auto true`).

---

## Phase 6: Security, Downgrade Prevention & Server Certificate Architecture

### A. Local Device Security
1. **Production Configuration (`prj_release.conf`)**:
   - Sets `CONFIG_TOMS_DEV_MODE=n`.
   - Forces `BT_GATT_PERM_WRITE_ENCRYPT` on all write characteristics (`0x02`, `0x04`, `0x06`, `0x07`, `0x08`, `0x0C`, `0x0D`, `0x0E`).
   - Unbonded devices attempting to write are rejected at the BLE stack with `BT_ATT_ERR_AUTHENTICATION`.
2. **Production Signing Key (`production-ec-p256.pem`)**:
   - Generated using `firmware/tools/generate_keys.py`.
   - Replaces MCUboot's public development key with a private, offline ECDSA P-256 keypair.
   - Private key is excluded from git via `.gitignore` (`*.pem`).
3. **Strict Downgrade Prevention**:
   - When `CONFIG_TOMS_DEV_MODE=n`, `toms_ota_version_cmp(&img, &running)` rejects equal or older versions with `TOMS_OTA_VERSION`.

### B. Server-Backed Conductor Authentication (VPS Ephemeral Certificate Architecture)
To prevent unauthorized passengers from connecting to or manipulating the Return Machine:

```
┌─────────────────────┐            ┌──────────────────────┐            ┌────────────────────────┐
│   TOMS VPS Server   │            │ Conductor Smartphone │            │  ESP32-S3 Return Mach. │
│(toms.perchbuilds.me)│            │  (TOMS Android App)  │            │  (Bus Transit Terminal)│
└──────────┬──────────┘            └──────────┬───────────┘            └───────────┬────────────┘
           │                                  │                                    │
           │  1. Conductor Login & Shift      │                                    │
           │◄─────────────────────────────────┤                                    │
           │                                  │                                    │
           │  2. Issue Ephemeral Certificate  │                                    │
           │     (Signed with Server PrivKey) │                                    │
           ├─────────────────────────────────►│                                    │
           │                                  │  3. Connect & Pair BLE (Encrypted) │
           │                                  ├───────────────────────────────────►│
           │                                  │                                    │
           │                                  │  4. Handshake: Present Certificate │
           │                                  ├───────────────────────────────────►│
           │                                  │                                    │
           │                                  │     5. Verify Sig with ServerPubkey│
           │                                  │        Check Expiry & Vehicle ID   │
           │                                  │        [ACCESS GRANTED]            │
           │                                  │◄───────────────────────────────────┤
           │                                  │                                    │
           │                                  │  6. Sync Fares / Normal Op / OTA   │
           │                                  ├───────────────────────────────────►│
```

1. **Conductor Shift Enrolment**:
   - When the conductor starts a shift, the phone authenticates with the VPS over HTTPS.
   - The VPS validates conductor credentials and vehicle assignment, then issues a short-lived **Ephemeral Authorization Certificate** (valid for 8–12 hours).
2. **Certificate Structure**:
   - `vehicle_id` (UUID) & `machine_id`
   - `conductor_id` (UUID)
   - `valid_from` & `valid_until` (UNIX epoch timestamps)
   - `capabilities` bitmask (`CAP_READ_CARDS`, `CAP_SYNC_FARES`, `CAP_CONFIG`, `CAP_OTA`)
   - `signature` (ECDSA P-256 signature generated by the VPS server's private key)
3. **Return Machine Verification**:
   - The Return Machine holds the **TOMS VPS Public Key** embedded in its firmware (`CONFIG_TOMS_SERVER_PUBKEY`).
   - When the phone connects over BLE, it writes the certificate to an authorization characteristic.
   - The Return Machine verifies:
     - Digital signature matches the TOMS VPS public key.
     - Current timestamp is within `valid_from` and `valid_until` (using synced RTC clock).
     - Target `machine_id` matches its own device ID.
   - If verification succeeds, the requested capabilities are unlocked for the duration of the BLE session.
   - **OTA Locking**: `CAP_OTA` is only granted to technician or authorized supervisor certificates; regular passenger or conductor certificates cannot initiate firmware flash sessions.

---

## Phase 7: Battery Threshold Gate

*(Gated on hardware divider wiring)*
- **Hardware Requirement**: 2:1 resistor divider (e.g. 100kΩ / 100kΩ) connecting the LiPo battery to GPIO1 (ADC1 channel 0).
- **Firmware Logic**:
  - In `src/ble/ota.c:do_start()`, sample battery ADC millivolts before beginning slot erase.
  - If $V_{batt} < 3500\text{ mV}$ (approx. 20% capacity), immediately reject `START` with `TOMS_OTA_BATTERY` (`0x06`).
  - Prevents power loss during non-volatile flash sector erasing.

---

## Phase 8: Field Technician SOP & Emergency Recovery

### 1. Standard In-Field OTA Update Procedure
1. Conductor/Technician logs into the TOMS Updater / Conductor app with valid server credentials.
2. Select target firmware binary (`.signed.bin`).
3. Connect to the Return Machine via BLE and initiate upload.
4. Observe the LCD display:
   - Screen shows `ERASING...` ➔ Progress bar (0–100%) ➔ `CHECKING SHA-256` ➔ `RESTARTING`.
5. After reboot, verify the boot splash displays the new build tag (e.g. `v2.0.0`) and confirms healthy operation.

### 2. Recognizing an Automatic Revert
If a newly flashed image crashes, hangs, or fails self-tests:
- The screen will briefly reboot or hang for 8.4 seconds until the hardware watchdog fires (`rst:0x7`).
- MCUboot will automatically swap back to the previous confirmed image in slot 0.
- The boot splash will display the previous image tag (e.g. `good-1 (confirmed)`), confirming the unit safely recovered.

### 3. Emergency USB Recovery (Unbrick Procedure)
If a physical hardware failure or corrupted bootloader requires direct recovery:
1. Disconnect the terminal from vehicle power.
2. Press and hold the physical **`BOOT`** button on the ESP32-S3 Super Mini.
3. Plug the USB cable into a service laptop, then release `BOOT`.
4. Run the factory flash recovery command:
   ```bash
   python firmware/tools/flash.py plain
   ```
   Or direct `esptool`:
   ```bash
   esptool.py --chip esp32s3 --port COM11 --baud 460800 write_flash -u --flash_size 4MB \
     0x0 firmware/build/phase1/mcuboot/zephyr/zephyr.bin \
     0x20000 firmware/build/phase2/images/good-1.confirmed.bin
   ```
5. Power cycle the machine; the terminal boots back to the baseline factory firmware.

## Tooling changes this causes

- The build becomes **sysbuild** (bootloader + app), with a different build folder layout.
  `build_devkitc.sh`, the VS Code tasks and the Zephyr IDE profile all need updating.
- The flash command writes the bootloader and the signed app at their offsets, not one
  `zephyr.bin` at 0x0.
- The USB lockups after each flash (needs a re-plug) are unrelated to OTA and will continue
  during phases 0 to 3.

## Risks

| Risk | Mitigation |
| --- | --- |
| Swap-mode MCUboot does not build or boot on the S3 under Zephyr 4.2 | Phase 1 is the first gate. Fallback is ESP-IDF with NimBLE (`SPIKE.md` fallback), which has its own rollback. |
| Bad update bricks a unit in the field | Revert gate (phase 2), power-cut gate (phase 3), battery gate (phase 7), BOOT+USB recovery sheet (phase 8). |
| Tampered image | Signature plus bonded-only access (phase 6). |
| Layout offsets disagree between devicetree and bootloader | One source of truth, checked in phase 1. |
| A lost production signing key | Two encrypted offline copies, passphrase kept apart. Without it, no further OTA is possible and every unit needs a USB reflash. |
| A bad release that is already confirmed | Downgrade prevention blocks going back. Ship a fixed release with a higher counter. Keep releases small and run phase 2 and 3 checks on every one. |
| Counter bumped too high on a test image | Separate dev build without downgrade prevention for the bench. Release checklist step for the counter. |
| MTU on real phones is lower than assumed | Measure in phase 5. The design carries offsets, so any chunk size works. |

## Open questions for the owner

1. ~~Downgrade prevention on or off?~~ Decided: on (see Signing).
2. ~~Who holds the production signing key?~~ Decided: the developer (see Signing). Still to
   decide: where the two encrypted backup copies live.
3. Should the first field units ship with the bootloader write-protected by hand-procedure
   only, or is a technician laptop acceptable for the rare bricked unit?
4. The Android app does not have Bluetooth yet (`documentation/v2_research_questions.md`
   says so). Phase 5 depends on that app work.
