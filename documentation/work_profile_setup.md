# TOMS in its own work profile (no MDM, no Google Play services)

Decision of 2026-11-23: no Headwind MDM. The TOMS app is the profile owner of an Android work profile on the pilot phone (Tecno Pova 4, Android 13 or newer), with no MDM server and no Google Play services.

**What this document is.** Research from Android's documentation and the Android 13 source (sources at the end), what the app now does about it, the steps the researcher follows, and the checks that still have to be done on the real phone. **Nothing here was run on a phone or an emulator.** Every claim about behaviour comes from documentation or source code. The boxes at the end are unticked on purpose.

## 1. What the platform allows

### 1.1 The profile owner role

| Question | Answer | Source |
| --- | --- | --- |
| Can an app own a work profile with no MDM server? | Yes. The role is a `DeviceAdminReceiver` registered as profile owner of a managed-profile user. A server is not part of the role. | [S1], [S8] |
| Is Google Play or GMS needed? | Not by the profile owner API. `ManagedProvisioning` (the system app that runs the setup flow) was read for Google dependencies: the only Google reference in its manifest is a setup-wizard compatibility permission. The DPC Support Library (managed Google Play accounts) is optional and only for Play-managed fleets. TOMS uses the platform `LocationManager`, not Fused Location, and has no Play dependency. | [S4], [S1], `LocationTracker.kt` |
| What does the app need to declare? | A `DeviceAdminReceiver` with `BIND_DEVICE_ADMIN`, the `android.app.device_admin` metadata, and the `DEVICE_ADMIN_ENABLED` intent filter. Done: `TomsDeviceAdminReceiver`. | [S2] |
| Can the phone make work profiles at all? | Only if it declares `FEATURE_MANAGED_USERS`. Without it the provisioning check returns `STATUS_MANAGED_USERS_NOT_SUPPORTED`. **Not known for the Tecno Pova 4; see the checklist.** | [S1], [S4] |

### 1.2 How the profile gets created without a server, GMS or MDM

There are two routes. Both are for the researcher to do, once per phone.

1. **adb route (recommended for the pilot).** `pm create-user --managed --profileOf 0 <name>` creates a managed-profile user under the main user. The app is installed into it, then `dpm set-profile-owner --user <id> <package>/<receiver>` makes TOMS the owner. The shell command only requires that the target user exists, has no profile owner yet, and (because adb is the caller) that the profile has **no accounts**. It sets the profile's setup-finished state itself. The adb route sends **no** "provisioning complete" broadcast, so the profile can stay switched off until the app enables it: the app does this at every launch (`setProfileEnabled`, harmless to repeat). Source: `PackageManagerShellCommand.runCreateUser`, `DevicePolicyManagerServiceShellCommand.runSetProfileOwner`, `DevicePolicyManagerService.setProfileOwner` / `enforceCanSetProfileOwnerLocked` / `setProfileEnabled` (Android 13 source). [S5]
2. **Intent route.** `ACTION_PROVISION_MANAGED_PROFILE` with `EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME` starts the system's own flow (consent screen, profile creation, owner set, then `onProfileProvisioningComplete` to the receiver). It needs an app that sends the intent. TOMS does **not** send it in this PR: nothing would be tested without the phone, and the adb route does the same job. The receiver does handle the broadcast, so this route can be added without changing the policy code. [S1], [S2], [S4]

QR and NFC provisioning (the factory-reset flows) set up a **fully managed device**, not a work profile, so they are out of scope; Android 13 also requires internet during them unless `EXTRA_PROVISIONING_ALLOW_OFFLINE` is set. [S3]

### 1.3 What a profile owner can and cannot enforce

| Wanted | Verdict | Evidence |
| --- | --- | --- |
| Block uninstall of TOMS | **Yes.** `setUninstallBlocked`, "device owner, profile owner, or a delegate". Since API 21. | [S6] (`DevicePolicyManager.java`, android13-release) |
| Block clearing data and force-stop | **Yes, Android 11+.** `setUserControlDisabledPackages`, "device owner or a profile owner". Since API 30. Before 11 nothing blocks it. | [S6] |
| Keep the app in the work profile | **Not enforced, and not shown to be possible.** An app installed in a work profile lives in that user and does not move out by itself. Stopping the user from *removing the whole work profile* is a different matter: the restriction for it, `DISALLOW_REMOVE_MANAGED_PROFILE`, is listed in the platform's `DEPRECATED_USER_RESTRICTIONS`, and the source read here does not show a profile owner being able to set it (it is cleared on the parent user only for organisation-owned profile owners). So this PR does **not** try, and says so. Removing the profile wipes TOMS and its database, so the checklist says to send unsent events first and the box in section 4 asks the phone whether Remove work profile is available. | [S5] (`UserRestrictionsUtils`, `DevicePolicyManagerService`) |
| Allow location and Bluetooth | **Allow, not grant.** The app clears a `DISALLOW_SHARE_LOCATION` or `DISALLOW_CONFIG_BLUETOOTH` restriction it set itself, and reports the state. A profile owner **cannot grant** location permissions (Android 12+: "A profile owner may not grant these permissions … but may deny them", silently ignored); the existing runtime prompts stay the way location is granted. `DISALLOW_BLUETOOTH` is a primary-user-only restriction, not settable from a work profile. | [S6], [S7], [S5] |
| Disable screen capture in the work profile | **Yes.** `setScreenCaptureDisabled`: "Called by a device/profile owner". Without the parent-instance call it applies to the profile only. Since API 21. Camera is a different, user-wide policy and is not touched. | [S6] |
| Whole-device kiosk / lock task | **No, and not available inside a work profile at all.** `setLockTaskPackages` is allowed for "a profile owner when no device owner is set", but the service's own check says "Managed profiles are not allowed to use lock task" (`canUserUseLockTaskLocked`). Kiosk mode needs a device owner on a fully managed (factory-reset) device. Not built. | [S6], [S9] |
| Control the main profile, the lockscreen of the whole phone | **No.** "A profile owner … doesn't have device admin privileges outside the work profile", with extra reach only on organisation-owned devices. | [S8] |
| Force the personal profile to stay off or hide apps | **No** (same reason). | [S8] |

### 1.4 Does TOMS keep working from inside the profile?

These were read in the platform source; each is still an unticked box for the phone.

| Capability | What the source shows | Open question for the phone |
| --- | --- | --- |
| NFC reader mode | `NfcService.setReaderMode` allows the call when "the caller is in foreground" (by uid); there is no managed-profile condition in that method. The work-profile guide notes only that a profile's admin limits which *system apps* exist there. | Does reader mode deliver tags while TOMS is the foreground app in the work profile? Does Android's tag dispatch behave the same? |
| BLE scan and GATT | `BLUETOOTH_*` are normal runtime permissions; the profile shares the radio ("Wi-Fi and Bluetooth … are shared system-wide"). A profile owner cannot grant them, so the existing prompts are used. | Does the permission prompt appear and stick inside the profile? Does the Return Machine bond (and the key flow) work from the profile? |
| Location | `LocationManagerService.isLocationEnabledForUser(userId)` takes a user id, so the on/off switch is read per user. The source read here does not say how a work profile's switch relates to the main user's. `DISALLOW_SHARE_LOCATION` is the restriction the app reports. | Does GPS give fixes inside the profile? Does the work profile have its own location switch, and what is it set to? With the screen off during a shift? |
| Foreground service (`location\|connectedDevice`) | Not read in the platform source. The app already starts it the same way in any user, with the permission checks of PR #34 (open). | Does the service start in the profile, does its notification show and stay, and does it survive Doze? |
| Battery exemption | `DeviceIdleController` keeps the whitelist by package name; the settings screen to request it may be **absent in a work profile** (the admin limits system apps). The app now catches the missing handler and carries on, as if the conductor chose "Not now". | Does the request screen open from inside the profile? If not, the exemption has to be set from Settings in the main profile. |
| Work-profile "paused" state | `UserManagerService` has a quiet-mode state for managed profiles (the user switches the work profile off); its effect on running apps was not read. | What happens to a running shift's GPS and BLE when the work profile is switched off and on? The shift itself is in the database, so it should resume. |
| Internet and WorkManager | Normal. | Does the upload worker run while the profile is on? |

## 2. What the app does now

* `TomsDeviceAdminReceiver` (manifest, `res/xml/device_admin.xml` with no policies declared) is the profile owner component. On `PROFILE_PROVISIONING_COMPLETE` and `DEVICE_ADMIN_ENABLED` it sets the policies. **Do not rename or move it** once phones are provisioned: Android stores the owner by component name.
* `WorkProfilePolicies` sets, on itself and only if it is the owner: uninstall blocked, user control disabled (Android 11+), screen capture disabled; clears a location or Bluetooth restriction **it** set. It reads everything back, so a refused call shows as **Failed** with a plain-words reason and never as "on". Policy logic is behind a `PolicyAdmin` seam and is tested with a fake.
* `WorkProfileGate` classifies the phone (owner in a work profile / work profile owned by another app / owner of the main profile / ordinary install / no work-profile support) and decides whether the app may go on.
* **First-run check screen** (`WorkProfileScreen`): says in plain words whether TOMS runs in a work profile and lists every policy as On / Off / n/a / Failed. A release build **stops here** unless TOMS owns its work profile; every launch re-checks (so a phone that lost its profile is stopped again).
* **Debug-only switches** (development phones have no profile):
  * debug builds start with the gate **off** (`REQUIRE_WORK_PROFILE = false`): the check is shown once, then out of the way;
  * `./gradlew assembleDebug -PrequireWorkProfile=true` turns the gate on to try it, and then the screen offers "Continue without a work profile (development phone)";
  * a release build always requires the profile and has no way around the check. (The remembered "continue without profile" choice is stored but only honoured when the gate says the build is a debug build.)
* The battery-exemption prompt no longer crashes if the profile has no settings screen for it.

## 3. Provisioning checklist for the researcher (not yet run on the Tecno Pova 4)

Do this on a **freshly reset** phone, or at least one with **no accounts** in the main profile (see the account rule above). Replace `<id>` with the user id printed by step 3.

1. Install a **release** APK (or debug) in the main profile: `adb install app-release.apk`. Note the package: `ph.toms.conductor`.
2. Check the feature: `adb shell pm has-feature android.software.managed_users` (should print `true`). If not, stop: this phone cannot have a work profile.
3. Create the work profile: `adb shell pm create-user --managed --profileOf 0 TOMS`. It prints `Success: created user id <id>`.
4. Install the app into the profile: `adb shell pm install-existing --user <id> ph.toms.conductor`.
5. Make TOMS its owner: `adb shell dpm set-profile-owner --user <id> ph.toms.conductor/.workprofile.TomsDeviceAdminReceiver`. It prints `Success: Active admin and profile owner set to …`. If it fails with "there are already some accounts on the profile", remove the accounts from that profile (or recreate the profile).
6. Start the app **in the work profile**: `adb shell am start --user <id> -n ph.toms.conductor/.MainActivity`, or open the badged TOMS icon in the launcher. The first-run check should say **TOMS is running in a work profile**, with every policy On. Tap Continue.
7. Verify from adb (read-only): `adb shell dpm list-owners`, `adb shell pm list users` (the work profile should be **running**, not disabled).
8. Remove the personal copy of the app if one is not wanted: `adb shell pm uninstall --user 0 ph.toms.conductor` (this only removes the main-profile copy; the work profile copy stays).

To undo: `adb shell pm remove-user <id>` (this deletes the profile and everything in it). **Send any unsent events first** (More → Sync).

Notes: the account rule, the encryption rule and the offline rule of the intent route are not needed on this route as far as the source shows; the real phone decides.

## 4. To test on the real Tecno Pova 4 (all unticked)

- [ ] `pm has-feature android.software.managed_users` is `true`.
- [ ] `pm create-user --managed --profileOf 0 TOMS` works and creates a profile.
- [ ] `dpm set-profile-owner` succeeds on the Tecno build (some vendor builds restrict it).
- [ ] The work profile is **enabled** (not disabled) after the first launch of the app.
- [ ] The first-run check shows "TOMS is running in a work profile" and all five policies as On (or n/a on Android 10).
- [ ] The app cannot be uninstalled from the work profile (Settings → Apps → TOMS in the work tab: Uninstall is greyed out).
- [ ] Clear data and Force stop are disabled for TOMS (Android 11+).
- [ ] A screenshot of TOMS inside the profile is blocked.
- [ ] NFC: a card tap is read while TOMS is in the foreground in the work profile.
- [ ] NFC: reading still works after the screen is off and on again.
- [ ] Bluetooth permission prompt appears and is granted inside the profile.
- [ ] BLE: the Return Machine is found, bonded, key provisioned and signed messages verify from the profile.
- [ ] Location permission prompt appears and GPS fixes arrive inside the profile.
- [ ] The shift foreground service starts and its notification stays with the screen off.
- [ ] GPS fixes and the Return Machine link keep running with the screen off for a full shift.
- [ ] The battery-optimization prompt opens, or the app carries on without it and the researcher can set it from Settings.
- [ ] The upload worker sends events from inside the profile.
- [ ] Turning the work profile off (quiet mode) and on again leaves the shift intact.
- [ ] Rebooting the phone starts the work profile and TOMS resumes on the open shift.
- [ ] Removing the work profile removes TOMS and its data (confirms the warning in section 1.3), and whether the user can do it from Settings with TOMS as the profile owner.
- [ ] A release build on an ordinary install (no profile) stops at the check and cannot be passed.
- [ ] A debug build on an ordinary install still runs.

## 5. What this does not do

* No kiosk or lock task (not possible in a work profile, section 1.3).
* No server, no remote policy, no MDM, no Google Play accounts. Policies are set by the app on itself.
* The intent route (`ACTION_PROVISION_MANAGED_PROFILE`) is not started by the app. Only the receiver side exists.
* The policies were not tried on a real profile owner; the tests use a fake service that follows the rules in the source, and Robolectric for the manifest.

## Sources

Retrieved 2026-11-23. "Source" in the text means the Android 13 (`android13-release`) AOSP tree on android.googlesource.com.

* [S1] Build a device policy controller: https://developer.android.com/work/dpc/build-dpc
* [S2] Device administration: https://developer.android.com/work/device-admin
* [S3] Android 13 enterprise changes: https://developer.android.com/work/versions/android-13 (also Android 12: /android-12, Android 14: /android-14)
* [S4] `packages/apps/ManagedProvisioning` (manifest, `PreProvisioningActivityController`)
* [S5] `frameworks/base`: `PackageManagerShellCommand`, `DevicePolicyManagerServiceShellCommand`, `DevicePolicyManagerService`, `UserManagerService`, `UserRestrictionsUtils`, `UserTypeFactory`
* [S6] `frameworks/base/core/java/android/app/admin/DevicePolicyManager.java` (Javadoc of `setUninstallBlocked`, `setUserControlDisabledPackages`, `setScreenCaptureDisabled`, `setLockTaskPackages`, `setPermissionGrantState`, `isUninstallBlocked`)
* [S7] Android 12 enterprise changes (sensor permission grants deprecated for profile owners): https://developer.android.com/work/versions/android-12
* [S8] Work profiles in AOSP: https://source.android.com/docs/devices/admin/managed-profiles, and work profile guide https://developer.android.com/work/managed-profiles
* [S9] Lock task mode availability: https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode
* `packages/apps/Nfc` (`NfcService.setReaderMode`), `services/core/.../LocationManagerService`, `DeviceIdleController`

The developer.android.com class reference for `DevicePolicyManager` is too large to retrieve completely through the tool used; the method wording was taken from the Javadoc in the source file instead.
