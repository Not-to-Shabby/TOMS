# toms_android

Native Kotlin conductor app for TOMS v2. It replaces the Flutter app in `../toms_mobile`, which is kept only as read-only reference.

- Progress, code map and known gaps: `../documentation/v2_progress.md`
- Decisions: `../documentation/v2_decisions.md`
- Deferred work: `../documentation/v2_later.md`

## Build

```
export ANDROID_HOME=/d/Android_SDK        # or set sdk.dir in local.properties
./gradlew :app:testDebugUnitTest          # 53 unit tests
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build needs network access.

## Run on a phone

NFC must be on and location permission granted. The sign-in is a stub that accepts any non-blank username and password.

## Stack

Kotlin, Jetpack Compose, Hilt, Room with SQLCipher, WorkManager, kotlinx.serialization, platform `LocationManager` and NFC reader mode. The planned HTTP client is Retrofit with OkHttp; it is not wired in yet.
