> Exported from the Claude Doc "TOMS v2 plan (clean version)" on 2026-10-26 for use outside Claude. Source of truth for hardware pins is `return-machine-v2-netlist.xml` and `firmware-agent-brief.md`; the pin table inside this export may be older.

# TOMS v2 plan (clean version)

·

## What we are building

TOMS counts passengers on Iligan minibuses and PUJs by the card each passenger carries, and records the fare for each trip. The first headline for the paper is still to be picked: occupancy, or fare recording.
- **Passenger card:** an RFID/NFC card issued to each passenger, standing included. There are no seat numbers.
- **Conductor phone:** a native Kotlin Android app on a Tecno Pova 4. The phone reads the card itself, picks the stop and fare, and saves locally first.
- **Return Machine:** an ESP32-S3 with a PN532 at the exit. It reads the card UID when the passenger leaves and talks to the phone over BLE.
- **Backend and dashboard:** a Node and PostgreSQL server with a web dashboard. Fares and discounts are set there, and trips sync up when there is signal.
- **Receipt:** a QR on the card opens a page with the latest synced trip. It carries no personal data.
- **Dead zones** such as Timoga and Tag-ibo come from the conductor survey, so the app works offline and syncs later.

## Current focus

The only priority now is Phase 1: mobile and web working end to end against a simulated Return Machine. Hardware, Zephyr, hosting and the MDM route wait. Bench checks on the power parts can run in parallel, but they do not block Phase 1.

Next three actions:
- Finish step 1.4: backend ingest, the web dashboard, the receipt page and card registration, merged on the v2 branch.
- Open the draft pull request from the v2 branch into main (waiting on your go-ahead).
- Build the simulated Return Machine (step 1.5) so the app's BLE client is tested before the hardware arrives.

How we work: one owner per phase, a date change is posted within a day, and a task is done only when its "Done when" test passes and the code is merged. Phase dates stay "[date to agree]" until someone confirms them. The pilot date and the paper submission date are agreed dates, and a slip is raised two weeks ahead.

## Where things stand

This sorts every decision by how firm it is, as of 2026-10-19.

### Decided
- The Google Doc design is the current one. The repo's Flutter and ESP32 master/slave work is the older iteration.
- Native Kotlin Android app on a Tecno Pova 4, with the phone doing NFC itself and a BLE Return Machine.
- No seat numbers. Occupancy is the count of passengers by the card issued to each, standing included. The term is "passenger card".
- Fares and discounts are set in the web dashboard and fetched by the app.
- Return Machine hardware: ESP32-S3 Super Mini with a PN532 and a 2000 mAh cell. Zephyr is the intended RTOS, pending the spike.
- The IP5306 module bought has no I2C. The Super Mini has its own TP4054 charger at about 100 mA, with a 300 mA mode for cells above 500 mAh.
- Hosting is on Railway, chosen on 2026-10-25, in its Singapore region.

### Recommended, not confirmed
- MDM route: fork the Headwind agent with a stock Community server, or make the TOMS app its own device policy controller.
- Power: Route A (cell on the board's battery pads, TP4054 at 300 mA, vehicle USB power) or Route B (cell on the IP5306 module, buck converter into the 3V3 pin). Route A is the simpler default.
- Hosting cost: Railway is chosen (see Decided), so the open part is whether the usage stays near the included credit and whether Headwind's ports work there.
- Research questions: one set of three or four, with the counting question worded around the card.

### Open checks
- Confirm the buck converter really outputs 3.3V before the board is connected.
- Measure the Return Machine's real average current and the IP5306 light-load shutoff.
- Choose divider values or a fuel-gauge IC for battery level.
- Zephyr spike: MCUboot over BLE on ESP32-S3, the application watchdog binding, the PN532 driver port.
- Whether the phone's bootloader was relocked.
- Draft pull request for the v2 branch.

## Phases

Phase 1 needs no custom hardware. Status is as last reported in this thread and in the repo's v2 branch on 2026-10-17.

| Step | Why | Done when | Status |

|---|---|---|---|

| Phase 0: decisions | Remove blockers before coding | Fare timing, discounts, rounding, backend, MDM route, approved phones and RTOS are recorded here and in the paper | Partly done; MDM route and fare timing still open |

| 1.1 Read a card | Prove the phone can read the cards | Phone reads three different cards reliably and shows each UID | Reported done |

| 1.2 Stop selection and fare | Correct fares are the core of the paper | Tests pass and a tap shows the correct fare and card state | Reported done |

| 1.3 Save locally, show pending | A tap must work with no signal | Boardings taken in airplane mode survive a restart and show as pending | Reported done, with Room encryption and GPS as follow-ups |

| 1.4 Backend, upload, dashboard | First working end-to-end system | Pending boardings upload once, duplicates are ignored, and they appear on the dashboard | In progress |

| 1.5 Simulated Return  Machine | Test the BLE client before hardware | The app returns a card through the simulated  Return Machine  and the paid check answers correctly, including after a disconnect | Not started |

| 1.6 Uplink manager, fare settings | Handle bad signal and change fares without a new build | The app retries offline and syncs on return, and a dashboard fare change changes the next fare | Not started |

| 1.7 MDM, battery, stress tests | Show the phone survives a 16-hour shift | Tests pass in CI, end-to-end trip works, duplicates create no records, offline then sync loses nothing | Not started |

| Phase 2: Return  Machine  hardware | Replace the simulator with the real  Return Machine | Spike criteria met on the real board and card reads work over real BLE with the Phase 1 app | Waiting on hardware |

| Phase 3: field trial | Evidence for the paper | Dead-zone tests in Timoga and Tag-ibo, conductor pilot, metrics collected, with ground truth and run counts defined first | Not started |

| Phase 4: paper update | Make Chapters III and IV match what was built | Chapters III and IV updated, then results and discussion | Not started |

The repeating test for steps 1.2 to 1.5 is a unit or integration test written with the code. Step 1.4 also includes card registration and the Docker Compose stack.

## Design reference

The phone owns the trip and the card state. The Return Machine only reads cards and raises the alarm, and everything is saved locally before it is uploaded.

### Tech stack

| Layer | Choice | Note |

|---|---|---|

| Conductor app | Kotlin, Jetpack Compose, Coroutines and Flow, Hilt | Native NFC reader mode, BLE and device control |

| Phone database | Room (SQLite) and WorkManager | Offline-first store plus an outbox |

| Phone to cloud | HTTPS REST with batched, idempotent uploads | Ktor or Retrofit |

| Location | Android LocationManager (GNSS) | Works without Google Play services |

| Card | ISO 14443-A, such as NTAG21x | Android has no NFC-DEP, so reader mode only |

| Return  Machine | ESP32-S3 with a PN532 in reader mode, plus a buzzer and LED | RTOS decision after the spike |

| Backend | Node, TypeScript, Express, Socket.IO | The existing server, hardened |

| Server database | PostgreSQL | Idempotent ingest |

| Web dashboard | React, Vite, TypeScript, MapLibre | The existing dashboard, salvageable |

### Trip flow
- The passenger boards. The conductor asks for the destination and passenger type.
- The conductor taps the passenger card on the phone. The app reads the card and creates the trip: boarding stop, destination, fare, discount, time and vehicle.
- The conductor hands the card to the passenger, who can scan its printed QR to see the receipt.
- At exit the passenger returns the card to the Return Machine, which reads it, checks payment and reports the read to the phone.
- The phone marks the trip returned and the card available again.

### Card states

The phone owns these states. Registration (unregistered, registered, retired) is separate and applies before a card enters service.

| State | Meaning | Moves to |

|---|---|---|

| available | Card free for use | assigned_unpaid |

| assigned_unpaid | Trip created, fare not yet collected | assigned_paid, returned_unpaid |

| assigned_paid | Fare collected | returned |

| returned | Card read at the  Return Machine , trip closed | available |

| returned_unpaid | Card returned with the fare unpaid, alarm raised | available, after the conductor resolves it |

| lost | Not returned within a set time | available, after reconciliation |

Block a second tap on an assigned card, debounce repeated taps, and let the conductor clear or override an alarm from the phone.

### Card registration and receipt
- A card is unusable until an admin pairs its NFC UID with its printed card_uuid. The server creates the random card_uuid and its receipt URL together. The QR holds https://<domain>/c/<card_uuid> only, never trip or personal data.
- Pairing happens in an admin-only Register card mode: scan the QR, then tap the card. Each UID and each card_uuid can be paired once, every pairing is audit-logged, and a lost card is retired and replaced.
- The approved-cards list is part of the cached config, so a registered card works offline after the next sync. A tap on an unregistered card is rejected on the phone.
- The QR page redirects to the receipt of that card's latest synced trip and shows only fare, stops and time. It is rate-limited and needs the passenger's phone to be online, so it may not load in a dead zone. Measure how often that happens in the trial.
- Open question: whether registration happens at the depot by an admin (recommended) or in the field.
- Open question: fare is computed at boarding from the declared destination or at alighting from GPS. Pick one, align Chapter IV 4.8 and 4.9, and state the discount rates and rounding.

### Database and sync
- Every event has a client-generated UUID, a device ID, a local sequence number and delivery fields. The event and its outbox row are written in one transaction.
- The server ingests with INSERT ... ON CONFLICT (event_id) DO NOTHING and returns the accepted IDs. Events are append-only, and corrections are new events.
- Config (routes, stops, fares, discounts, approved cards) is versioned on the server and set in the dashboard. Each trip records the rule version used. Config is server-wins and event data is phone-wins, with no sync framework.
- Time is recorded in UTC plus an elapsed offset, and large clock skew is flagged.

### Network fallback

Every transaction is saved locally first. The chain only decides how it is delivered: SIM A data, then SIM B data, then encrypted SMS, then retry from the outbox.
- Define "unstable" explicitly, for example no validated connection or N consecutive timeouts, and add hysteresis so the manager does not flap.
- Dual-SIM switching depends on the phone, so choose approved phones and test them in Timoga and Tag-ibo.
- SMS needs cellular signal too. It helps weak coverage, not a true dead zone, and it is unordered and costs per message. Keep SMS-sent records in the outbox until the server confirms them.
- The SMS payload is compact, encrypted with a per-device Keystore key, and carries a sequence number and nonce against replay.

## Return Machine

The Return Machine is a BLE peripheral and the phone is the central and the authority for card state. The hardware is an ESP32-S3 Super Mini with a PN532 over I2C in ISO 14443-A reader mode, a buzzer and LED, and a 2000 mAh cell.

### BLE interface

| Characteristic | Direction | Content |

|---|---|---|

| Card read | Return Machine  to phone (notify) | card UID,  Return Machine  time, sequence number, signature |

| State update | phone to  Return Machine  (write) | state table version, card, status (delta or full snapshot) |

| Paid check | Return Machine to phone (write), phone to Return Machine  (notify) | request: card; reply: OK, UNPAID, UNKNOWN or ALREADY_RETURNED |

| Heartbeat | Return Machine  to phone (notify) | battery, NFC reader status, state version applied, buffered-read count, uptime, reset reason |

| Time sync | phone to  Return Machine  (write) | UTC time |

| Config | phone to  Return Machine  (write) | vehicle binding, tones, thresholds |
- The phone pushes every card state change and a full snapshot on each reconnect. The Return Machine keeps a small table and acks the version.
- On a read the Return Machine asks the phone whether the card is paid and waits one to two seconds. If BLE is down or the reply is late, it plays a distinct "unverified" tone from cached state instead of the full alarm. The conductor can clear an alarm from the phone.
- Reads while disconnected are buffered in flash with sequence numbers and sent on reconnect.
- The phone treats a missed heartbeat as Return Machine offline and warns the conductor. It reconnects from a foreground service with backoff.
- Security: LE Secure Connections, messages signed with a per-machine key, and each Return Machine bound to one vehicle's phone at enrollment.

### Power

The schematic shows the board's 3V3 regulator (an ME6217C33) fed from the VBUS rail, with the battery joining VBUS through a Schottky diode and a TP4054 charging the battery pads. There is no battery-level connection to a GPIO.
- **Route A (simpler default):** cell on the board's battery pads, the TP4054 at 300 mA (the default is about 100 mA), and vehicle USB power on the 5V path. The IP5306 module is left out.
- **Route B:** cell on the IP5306 module only, its 5V output into the board's 5V pin, or into a 5V to 3.3V buck converter feeding the 3V3 pin. The buck saves energy over the board's linear regulator.
- The IP5306 module bought has no I2C, so the S3 cannot read or set it. Only one charger should touch the battery.
- Battery level comes from a resistor divider from the battery into an ADC pin, or from a fuel-gauge IC such as a MAX17048 or BQ27441. The IP5306 reports no voltage or current.
- Runtime on 2000 mAh is roughly 10 to 14 hours at an assumed 100 to 150 mA draw. That is an estimate, so measure it. Plan on vehicle power with the cell as backup.
- Bench checks before connecting the board: confirm the buck output is 3.3V, check the IP5306 light-load auto-shutoff (about 50 mA for 32 seconds by default), and check PN532 read range with the buck running.

### Schematic v2

The v2 schematic uses the Route B power chain: 2000 mAh cell, then the DWEII boost module (5V), then a DC-DC converter to 3.3V. The 3.3V rail feeds the ESP32-S3 Super Mini, the PN532, the button pull-up and the LCD. The board's battery pads and 5V pin are not used.

| Part | Connection |

|---|---|

| PN532 (I2C) | SDA on GPIO2, SCL on GPIO 7 , IRQ on GPIO5, powered at 3.3V |

| Button | GPIO4, with a 10K pull-up to 3.3V, a switch to GND and a 100 nF debounce capacitor |

| Buzzer | GPIO6 through 1K to an S8050 NPN base (10K base pull-down), collector to the buzzer minus, buzzer plus to 3.3V |

| LCD (1.8 inch ST7735S, 160x128) | GPIO8 reset, GPIO9 A0, GPIO10 CS, GPIO11 SDA, GPIO12 SCLK, backlight through GPIO13 |

| Backlight switch | Open: drawn as a low-side 2N3904 (1K base from GPIO13); likely needs a 2N3906 high-side switch, decided by the 100 ohm test |

Checks and changes from the v2 review:
- **LCD backlight (bug):** in v2 nothing feeds the module's LED pin, which only goes to the low-side switch. If that pin is the backlight anode with the cathode already on GND, as on many 1.8 inch ST7735S modules, the backlight can never light. Check your module. The fix is either to drive LED from GPIO13 through about 47 to 100 ohm (check the module's own series resistor, and stay under the pin's current limit), or to switch 3.3V to the LED pin with a 2N3906 PNP high-side switch, with a base pull-up. Drop the 2N7000 and its floating gate.
- **GPIO3 strapping pin:** SCL was on GPIO3, which selects the JTAG signal source at reset. Fixed in the latest drawing: SCL is now on GPIO7. Keep GPIO1 free for battery sensing.
- **LCD reset net name:** the review says the LCD block's reset label is ESP_TO_LCD RST with a space. I could not confirm that from the image. In Fritzing, check that the LCD reset net matches ESP_TO_LCD_RST exactly, or the router will leave it disconnected.
- **Battery level not connected:** add a resistor divider from the battery positive into GPIO1, or a fuel-gauge IC.
- **Power chain:** boost then buck is roughly 85% times 90%, about 75% efficient (an estimate). Feeding the boost's 5V into the board's 5V pin is worse, because the onboard LDO from 5V to 3.3V is only about 66% efficient. Better options are Route A (cell straight onto the board's battery pads) or one buck-boost converter from the cell, such as a TPS63020. Decide after measuring real current, with the LCD backlight counted.
- **Two supplies:** keep the S3's 5V pin unwired and unplug its USB-C while the converter feeds 3V3. Flash with the converter disconnected. Verify the converter really outputs 3.3V first, and check the DWEII module's light-load shutoff against the real idle current.
- **Buzzer:** drive it through a low-side transistor (the S8050 NPN) with a 10K gate or base pull-down. Add a flyback diode only if it is an electromagnetic buzzer, since a piezo does not need one.
- **PN532:** confirm the module has I2C pull-ups (add 4.7K to 3.3V if not) and that its switches are set to I2C. RSTO on this module looks like an output, so it cannot reset the chip. For recovery, re-initialize in firmware or power-cycle the module.
- **Decoupling:** add 10 uF to 100 uF plus 100 nF across 3.3V and GND next to the PN532 and the LCD, for the RF current spikes.
- **Button:** with INPUT_PULLUP the external 10K is optional. A 100 nF capacitor gives hardware debounce, but firmware debounce is enough.
- **Drawing:** use one title block, name it Return Machine v2, group sub-circuits in dashed boxes, and use standard power symbols.

### Parts list for the v2 modules

The modules carry their own inductors and capacitors, so no discrete inductor is needed. The values below are typical starting points. Confirm them against each module's datasheet and your own measurements before ordering.

Modules: ESP32-S3 Super Mini, PN532 V3 module, DWEII boost module, 5V to 3.3V DC-DC module, 1.8 inch ST7735S LCD, 2000 mAh cell, a buzzer and a tactile button.

Itemized by module (through-hole parts assumed for a prototype; "if needed" items depend on a check):

| Ref | Part | Value | Module | Qty | Condition |

|---|---|---|---|---|---|

| U1 | ESP32-S3 Super Mini |  | Main board | 1 |  |

| U2 | PN532 V3 NFC module |  | Reader | 1 |  |

| U3 | DWEII boost module |  | Power | 1 |  |

| U4 | 5V to 3.3V DC-DC module |  | Power | 1 | Verify it outputs 3.3V |

| LCD1 | ST7735S 1.8 inch LCD | 160x128 | Display | 1 |  |

| BT1 | Li-ion cell | 2000 mAh | Power | 1 | Needs its own protection circuit |

| BZ1 | Buzzer |  | Buzzer | 1 |  |

| SW1 | Tactile switch |  | Button | 1 |  |

| R1, R2 | Resistor | 100K, 1% | Battery sense | 2 |  |

| C1 | Ceramic capacitor | 100 nF | Battery sense | 1 |  |

| C2 | Ceramic capacitor | 100 nF | PN532 bypass | 1 |  |

| C6 | Electrolytic capacitor | 100 uF, 10V | PN532 bulk | 1 | Raise or lower after measuring rail sag |

| R3, R4 | Resistor | 4.7K | PN532 I2C pull-ups | 2 | If the module has none |

| C3 | Ceramic capacitor | 100 nF | LCD bypass | 1 |  |

| C5 | Ceramic or electrolytic capacitor | 10 uF | LCD bulk | 1 |  |

| Q2 | 2N3906 PNP (bought, used in the build;  emitter to 3.3V, collector to LED pin, base through 1K from GPIO13) |  | LCD backlight switch | 1 | If not driving LED from the GPIO |

| R7 | Resistor | 10K | Backlight  base pull-up to 3.3V  to 3.3V | 1 | With Q2 |

| R8 | Resistor | 47 to 100 ohm | LCD LED series | 1 | If the module has no series resistor |

| C4 | Ceramic capacitor | 100 nF | S3 bypass | 1 |  |

| Q1 | S8050 NPN  (bought) |  | Buzzer driver | 1 |  |

| R5 | Resistor | 1K (base) or 100 ohm (gate) | Buzzer driver | 1 |  |

| R6 | Resistor | 10K | Buzzer base or gate to GND | 1 |  |

| D1 | 1N4148 diode |  | Across the buzzer | 1 | Electromagnetic buzzer only |

| R9 | Resistor | 10K | Button pull-up | 1 | Optional with INPUT_PULLUP |

| C7 | Ceramic capacitor | 100 nF | Button debounce | 1 | Optional |

| J2 | JST-PH connector, keyed | 2 pin | Battery | 1 |  |

| F1 | Polyfuse | 1 A | Battery lead | 1 | If the cell has no protection circuit |

Buy list, with the discrete parts totalled (add about 50% spares):

| Type | Value | Qty to buy |

|---|---|---|

| Resistor | 100K, 1% | 2 |

| Resistor | 10K | 3 |

| Resistor | 4.7K | 2 (if needed) |

| Resistor | 1K | 1 |

| Resistor | 47 to 100 ohm | 1 (if needed) |

| Ceramic capacitor | 100 nF | 5 |

| Capacitor | 10 uF | 1 |

| Electrolytic capacitor | 100 uF, 10V | 1 |

| Transistor | S8050  NPN (bought) | 1 |

| Transistor (backlight) | 2N3906 PNP (have) | 1 (if needed) |

| Diode | 1N4148 | 1 (if needed) |

| Connector | JST-PH 2 pin | 1 |

| Polyfuse | 1 A | 1 (if needed) |

Before ordering, check that the 2000 mAh cell has its own protection circuit (over-charge, over-discharge and short-circuit), and use a 1 A polyfuse in the battery lead if it does not.

### Firmware and the Zephyr spike

Options are ESP-IDF with FreeRTOS and NimBLE (the old repo's PN532, storage and power code is written for it), or Zephyr, which is the intended choice. Zephyr uses LittleFS, NVS or FCB rather than SPIFFS. The spike runs for one to two days once the hardware arrives, and if any hard criterion fails, use ESP-IDF and record the reason in the paper.
- BLE GATT peripheral on the ESP32-S3: advertises, a phone connects, notify and write work, and the link stays up for an hour.
- PN532 over I2C in reader mode (port or write a driver): reads NTAG and DESFire UIDs reliably at the exit distance, 100 consecutive reads.
- Flash-backed ring buffer: no data loss across 20 power cycles.
- Reconnect: drop and restore the link 50 times, and the phone reconnects and the buffer flushes in order.
- OTA over BLE: Zephyr's SMP sample documents MCUboot two-slot upload over BLE but lists only nRF boards, so prove upload, image swap and confirm or revert on ESP32-S3.
- Watchdog: the ESP32 XT watchdog binding covers only the slow-clock crystal, so find the application watchdog binding in the board's devicetree. Feed it only from a loop that proves the PN532 task and BLE stack are alive, and log the reset reason in the heartbeat.
- ESP-IDF fallback OTA: the reference design has no image signing, no pairing, no per-chunk retries and no whole-image hash. Add all four, and mark a new image valid only after PN532, BLE and heartbeat checks pass.

Decide on BLE stability, driver availability, storage reliability, effort and code reuse. Then name the chosen RTOS once and consistently in Chapters III and IV.

## Backend and web

The web dashboard is salvageable as it is: dashboard, fleet, route builder, conductors, analytics and audit-log pages. The backend started as a prototype and needs:
- Real authentication: argon2 or bcrypt instead of unsalted SHA-256, real JWTs or sessions, admin and conductor roles, and per-device credentials issued at enrollment. Remove seeded credentials and the open CORS setting.
- PostgreSQL with real migrations, and no committed SQLite files in the repo.
- A new event schema built on card_uuid, device_id, terminal_id and a client event UUID for idempotency, with validation and authentication on the events endpoint.
- One stack: Express with PostgreSQL. The repo docs say Supabase and the paper says ASP.NET, so make all three agree.
- Receipt page, card registry, delivery-channel and backlog views, and Return Machine status on the dashboard.
- Fare and discount settings set by an administrator, not hard-coded: fare matrix per route (base fare, base distance, per-km rate), rounding rule, discount categories and rates, effective date and legal source, and a draft, published or retired status. Every change is audit-logged, each trip records the fare version used, and the phone shows its cached version and date. Verify the current mandated rates before they go in the paper.

### Hosting on Railway
- Chosen on 2026-10-25. Railway has a Singapore region, the closest of its four regions (Amsterdam, Singapore, California, Virginia) to Iligan. Pick Singapore when creating each service, since moving a database between regions later takes effort.
- Pricing, from a third-party page dated 2026-09-18: Hobby is $5 a month with $5 of usage included. CPU is about $20 per vCPU per month, memory $10 per GB per month, volumes $0.15 per GB per month, and egress $0.05 per GB. Confirm on Railway's own pricing page.
- A forum answer says the Hobby volume limit is 5 GB per volume. The database must fit in that, or the plan must change.
- Railway runs each service separately, so the Compose stack is split into the API, the web dashboard and PostgreSQL. Whether Railway can import a Compose file is not confirmed.
- A small API plus PostgreSQL may use more than the included $5 credit. My rough guess is $10 to $20 a month, so check usage after the first week.
- If Headwind is used, confirm that Railway can expose its MQTT port and keep its data on a volume before relying on it.
- Keep secrets in Railway variables, never in git, and check what database backups the plan includes.

## Device management

The MDM exists to stop conductors browsing and to cut battery use. Only one device-policy controller can be device owner on a phone, so the route is not both.
- **Route 1:** fork the Headwind Android agent (Apache-2.0), keep the stock Community server pinned in Docker, and implement the missing kiosk stubs with lock-task mode. Headwind's kiosk, URL filtering and location tracking are paid Enterprise features. Time-box this as a spike, and fall back to Route 2 if it stalls.
- **Route 2:** make the TOMS app its own device owner with lock-task kiosk, user restrictions, health telemetry in the heartbeat, config pulled at sync, and updates by adb. It is lighter for the MVP but means building the MDM features yourself.
- No Google services means no managed Google Play or zero-touch. Enrollment is by QR or adb, and phones must be factory reset.
- Lockdown policy: TOMS as the only foreground app, other apps hidden, no browser, settings or installs, hotspot off, unused radios and sensors off, dimmed screen, and a battery-optimization exemption for TOMS only. Run the same battery test with the policy on and off and report the difference.
- Device baseline: the Tecno Pova 4 now runs Axion OS v2.9 (Android 16) with Google apps, and spoofing is present. Whether the bootloader was relocked is unconfirmed. Do not rely on device integrity in the design.
- Battery budget: 6,000 mAh over a 16-hour shift is about 340 mA at 90% usable. A screen-on phone with GNSS, BLE, cellular and NFC may draw more, so measure it and plan for a vehicle charger.
- Health telemetry in the heartbeat: battery, temperature, thermal status, memory, storage, signal, crashes, NFC read success, BLE reconnects and outbox backlog.
- Stress tests: full-shift battery, heat, a 24 to 72 hour soak, 1,000 rapid taps, 10,000 queued events, network flapping, 50 BLE reconnects, power loss mid-transaction, and backend load with simulated vehicles.

## Paper changes
- Chapter III: replace the NFC-DEP claim with ISO 14443-A reader mode (Android cannot do NFC-DEP), describe the QR as card UUID plus URL, use the term Return Machine throughout, and update the phone, Return Machine and software tables.
- Chapter III: add subsections on BLE, device management and network fallback, and fix the security section. Remove unsupported claims such as a "Device ID Lock".
- Chapter IV: update the architecture and requirements, the BLE table, the card workflow, the fare rule (4.9), storage and sync (4.10), and the dashboard (4.11).
- Evaluation: reconcile six metrics against four criteria, state how the null hypothesis is tested (for example McNemar's test for paired correct and incorrect results), define ground truth, plan sample size, and measure the manual baseline in the same trips. Fix the sync reliability formula (synced divided by records created offline) and split it by channel.
- Research questions: the paper has two sets (three in section 1.3.1, four in the literature review). Pick one and word the counting question around the passenger card.
- State the limitation that a release of an unpaid card is final, and describe the soak and battery tests as short-term reliability checks, since long-term durability is out of scope.
- Pick one headline: the title and Chapter 1 lean toward fare and point of sale, while TOMS means occupancy.

## Risks and open decisions

| Item | Risk or decision |

|---|---|

| Fare timing | Boarding or alighting, and how early or late alighting is handled |

| Dual-SIM switching | Device-dependent, so pick approved phones and test them |

| SMS in dead zones | Fails with no cellular signal, and costs per message |

| Receipt in dead zones | Needs the passenger's phone online and the trip synced |

| RTOS | ESP-IDF or Zephyr, decided by the spike |

| MDM | Headwind fork or the TOMS app as device owner |

| Card cloning | A UID alone is weak, so decide whether authenticated cards are needed |

| Backend security | Prototype authentication must be replaced before any real use |

| Power | One charger on the battery, and buck output and idle current verified on the bench |

Later items, decided: keep a disputed payment status in the schema now and build the dispute screen after Phase 1. Build the shift summary first among the later items, because the manual comparison needs it. Park the GPS proximity alarm, since the paper's alarm is the Return Machine's unpaid-card alarm.