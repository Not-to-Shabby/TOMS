# USB card reader

An external NFC card reader on the phone's USB-C port, through an OTG adapter. It exists because the development phone's NFC chip failed (hardware fault, 2026-11-17), and it also removes the phone's NFC chip as a single point of failure.

The passenger card is a UID only: it stores no balance. The app reads the card's UID (ISO 14443-A) and nothing else, from the phone's NFC (reader mode, as before) or from a USB reader. Android has no NFC-DEP, so none is used.

**Nothing in this document, or in the code that goes with it, has run on a phone, with a reader or with a card.** The tests use a byte-level fake of the CCID specification. Every statement about hardware behaviour is either quoted from a source below or listed as an unticked phone check.

## What the app does

- `CardReader` (`reader/CardReader.kt`): start, stop, a flow of taps (the UID bytes, a time and which reader produced it) and a status: ready, no reader, permission needed, or an error with a plain-words reason.
- `NfcCardReader`: the existing reader-mode code (`nfc/NfcReader.kt`, unchanged) behind that interface.
- `UsbCcidCardReader` + `CcidClient`: finds a USB smart-card (CCID) reader, asks for permission, reads the UID when a card is presented, and copes with unplug, replug, a card removed during a read, and errors.
- `CardReaderManager`: chooses one reader at a time and gives the boarding logic a single stream of taps. The setting is on More: **Phone NFC**, **USB reader** or **Auto** (default). Auto uses the USB reader while one is plugged in and allowed (or waiting for the person to allow it), and the phone's NFC otherwise, and switches by itself when a reader is plugged in or out. A refused USB reader under Auto falls back to the phone's NFC so a card is never stranded.
- The conductor screen shows which reader is in use and its state as a chip ("Reader ready", "USB reader ready", "No USB reader", "USB: allow access", "USB access refused", "USB reader error", "NFC is off", "No NFC"); More shows the same in a sentence, with **Try again** when it helps.
- The boarding logic (`ConductorViewModel.onCardRead`) is unchanged and still has its own tap debounce (`TapDebouncer`, 1.5 s per card), so a card read twice in a moment is one boarding whichever reader read it.

## Research (sources retrieved 2026-11-25)

### How an app gets permission for a USB device

- **Attach filter.** *Android developers, "USB host overview"* (developer.android.com/develop/connectivity/usb/host): an `<intent-filter>` for `android.hardware.usb.action.USB_DEVICE_ATTACHED` with a `<meta-data>` pointing at a device-filter XML; "When users connect a device that matches your device filter, the system presents them with a dialog that asks if they want to start your application. If users accept, your application automatically has permission to access the device until the device is disconnected." This app declares it (`res/xml/usb_card_reader_filter.xml`: any device with USB class 11, Smart Card).
- **`requestPermission`.** Same page, and the `UsbManager` reference: "Requests temporary permission for the given package to access the device. This may result in a system dialog being displayed to the user … If successful, this grants the caller permission to access the device only until the device is disconnected." The answer arrives as `EXTRA_PERMISSION_GRANTED` on the `PendingIntent`.
- **Can the permission be remembered?** In the Android 14 source (`UsbPermissionActivity` → `UsbDialogHelper`), the OK button calls `grantDevicePermission` (until disconnect) and, **only if the "always open" checkbox is ticked**, `setDevicePackage`, which makes the app the default handler for the device. The checkbox is only offered when the app is a candidate handler (`canBeDefault`), which is why the manifest declares the attach filter. `UsbUserPermissionManager.hasPermission` also checks a persistent-permission map. So: **temporary by default; it can be kept by the person ticking "always" in the system dialog; the app cannot make it permanent itself.** Whether the box appears on the Tecno phone's build: **not confirmed**.
- **The result PendingIntent must be mutable.** `UsbDialogHelper.sendPermissionDialogResponse` sends the answer by calling `pendingIntent.send(context, 0, intent)` with the extras in a new `Intent`. The `PendingIntent` reference says that for `FLAG_IMMUTABLE` "the additional intent argument passed to the send methods … will be ignored", so an immutable one would lose the answer. `FLAG_MUTABLE` exists from API 31 and is required to be explicit on Android 12 and later. Android 14 added a second rule (behaviour changes for apps targeting 14): "If an app creates a mutable pending intent with an intent that doesn't specify a component or package, the system throws an exception." This app's `PendingIntent` is mutable **and** explicit (`setPackage(packageName)`), so both rules are met. (The USB host guide's own sample uses `FLAG_IMMUTABLE`; that is the documentation's, and by the source above it would not carry the answer. **Not confirmed on a phone.**)
- **Manifest.** The guide: declare `android.hardware.usb.host` with `<uses-feature>`. It is declared `required="false"`, so phones without USB host mode can still install the app and use their NFC.
- **Detach** is a broadcast an app registers for at run time (the guide: "Creating the broadcast receiver within the application, and not the manifest, allows your application to only handle detached events while it is running"). The app does this in `AndroidUsbReaderHost`.

### What a CCID reader needs from the app

- **The protocol.** *USB-IF, "Specification for Integrated Circuit(s) Cards Interface Devices", CCID rev 1.1* (usb.org/sites/default/files/DWG_Smart-Card_CCID_Rev110.pdf). Every message starts with a 10-byte header: `bMessageType`, `dwLength` (4 bytes, little endian), `bSlot`, `bSeq`, then three type-specific bytes. Bulk-OUT types used here: `PC_to_RDR_IccPowerOn` 62h (6.1.1, `bPowerSelect` 00h = automatic voltage), `PC_to_RDR_IccPowerOff` 63h (6.1.2), `PC_to_RDR_GetSlotStatus` 65h (6.1.3), `PC_to_RDR_XfrBlock` 6Fh (6.1.4). Bulk-IN: `RDR_to_PC_DataBlock` 80h (6.2.1), `RDR_to_PC_SlotStatus` 81h (6.2.2). The status byte (6.2.6, table 6.2-3): bits 0-1 `bmICCStatus` (0 active, 1 present and inactive, 2 no ICC), bits 6-7 `bmCommandStatus` (0 ok, 1 failed with the error in `bError`, 2 time extension requested). Errors used: `ICC_MUTE` FEh, `HW_ERROR` FBh, `CMD_SLOT_BUSY` E0h.
- **The UID command.** The ACS reference manuals (ACR122U API V2.04, section 4.1; ACR1252U reference manual 1.12, section 5.2.2.1): "Get Data … `FFh CAh 00h 00h 00h`" returns "the serial number … of the connected PICC" as UID bytes followed by `SW1 SW2`, `90 00h` meaning success; `63 00h` failure, `6A 81h` function not supported. The ACR1252U manual adds `62 82h` (Le larger than the UID) and `6C XXh` (Le too small; `XX` is the exact length), which the app does not provoke because it sends `Le = 00h`. **This is an ACS pseudo-APDU, not part of the CCID spec**; other makers' readers may differ. The app accepts 4-, 7- and 10-byte UIDs (the ISO 14443-A lengths) and refuses anything else.
- **The ACS Android library: not used.** Licence: **not stated** on any page retrieved (acs.com.hk product pages and `/en/mobile-support/`); the only licence-like sentence is "These utility tools are offered free of charge", about a different item. Redistribution inside an app: **not stated**. Dependence on Google Play services: **not stated**. Supported USB readers it lists: ACM1252U-Y3, ACM1252U-Z2, ACM1281U-C7, ACR1252U, CryptoMate EVO, ACR1281U-C1, APG8201-B2, ACR1251T, ACR1222L (**the ACR122U is not on the list**, though its product page lists "Android" under OS support and an "Android EVK" download). Since it fails the brief's two conditions (licence allows use, no Play services) on **"not confirmed"**, this PR writes a minimal CCID client instead, limited to: power on the slot, send the get-UID APDU, read the response, power off (plus a slot-status poll to see a card arrive and leave).
- **Readers that are named in sources.** ACS publishes CCID compliance for the **ACR122U** ("CCID Compliance", API manual 2.04; "The Standard Microsoft CCID and PC/SC drivers are used"; USB id `VID_072F&PID_2200` is quoted in the same manual; the product page also says the reader is end-of-life and warns of counterfeit units) and the **ACR1252U** ("CCID-compliant", manual 1.12). The ACR1255U-J1 product page says CCID too, but it is a Bluetooth reader whose USB use is not the point here. **The app does not match on vendor or product id**: it takes any device with a USB Smart Card interface (class 11) that has bulk-IN and bulk-OUT endpoints. Whether a given model's real descriptor looks like that, and answers `FF CA 00 00 00`, is **not confirmed**: it is a phone-and-reader check. Which model the pilot will buy: **not decided**.
- **A class-11 device may also expose other interfaces** (the ACR122U is a combination). The app uses the first Smart Card interface with a bulk pair. Not confirmed on a real reader.

### Work profile (PR #36)

- *Android developers, "Work profiles"* (developer.android.com/work/managed-profiles): "The profile IT admin can limit which system apps are available on the work profile. This restriction can also result in there being no handler for some common intents on the work profile." That is the only statement found about system dialogs and apps in a work profile; **nothing was found about USB host, the permission dialog or peripherals in a work profile.**
- *AOSP, `UsbProfileGroupSettingsManager`* (Android 14): attach handlers are resolved across **all enabled profiles of the group** (`queryIntentActivitiesForAllProfiles` over `getEnabledProfiles`), and a "forward intent to managed profile" match is kept only when needed, so an app installed in the work profile is a candidate when a reader is plugged in. Permission is held per user (`getPermissionsForUser(userId)`). The permission dialog itself is a SystemUI activity (`config_usbPermissionActivity`) started "as user"; whether it shows inside the work profile on the Tecno phone: **not confirmed**.
- **This stays an unticked phone check** (below).

### Charging and USB host at the same time

- **Not confirmed, and a phone check.** The platform keeps the USB *power role* and *data role* of a port separate (`UsbPort`, `UsbPortStatus.POWER_ROLE_*`, `DATA_ROLE_*`, a system API), so a port can in principle be a host while it is a power sink, but whether the Tecno Pova 4's USB-C port, its firmware and the OTG-plus-charging adapter do it is a property of the phone and the adapter, not of the app. The app does nothing about charging; there is nothing to handle yet (see the claims in the PR). Do not assume it works: until it is checked, plan for the reader to run the phone from its own battery, and see the battery check below.

## Limits to know

- A card swapped for another **with no empty moment in between** is not seen as a new card (the reader's slot status stays "present"). A person lifts one card and presents the next, so there is a gap; the test for this documents the limit.
- A reader that answers its status but fails every read is given up after three bad reads in a row ("The USB reader stopped answering. Unplug it and plug it in again.") and retried after three seconds.
- UIDs are never logged in full; in a debug build a two-byte prefix may be.
- Possible follow-up, **not built**: a manual card-number entry screen for when no reader works. Out of scope here.

## Phone and reader checklist (all unticked)

- [ ] A real reader is found, and the permission dialog appears when it is plugged in (with the app open, and with the app closed).
- [ ] The "always open" box is offered, and ticking it means the next plug-in needs no dialog.
- [ ] Pressing "Don't allow" shows "USB access refused", and **Try again** asks again and works.
- [ ] A passenger card is read: its UID is the same as the phone's NFC reads for the same card.
- [ ] A card is read once per presentation, however long it is held on the reader.
- [ ] Unplugging during a read does not crash, shows "No USB reader", and **Auto** falls back to the phone's NFC (on a phone whose NFC works).
- [ ] Replugging reads again, and asks for permission again unless "always" was ticked.
- [ ] Taking a card away mid-read does not give a tap or an error.
- [ ] The reader keeps working through a whole shift, with the screen kept on.
- [ ] Inside the work profile (PR #36): the reader is found, the permission dialog appears and can be answered, and a card is read.
- [ ] Charging and USB host at the same time with an OTG-plus-charging adapter: the phone charges and the reader still reads (or it does not: record which).
- [ ] Battery draw over a one-hour run with the reader plugged in, against the same hour on the phone's NFC.
- [ ] A cable vibration test: with the phone on a vehicle or a shaker, the cable and adapter do not drop out, and a drop shows as a plain notice and recovers.
- [ ] The model(s) chosen answer `FF CA 00 00 00` with the UID and `90 00`, and their descriptors have a Smart Card interface with a bulk pair.
