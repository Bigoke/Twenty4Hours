# Twenty4Hours

An Android alarm clock that shows your whole day on a 24-hour dial.

- Alarms sit on the ring as soft cream pucks; the next one glows amber.
- A glowing arc runs from **now** to your next alarm and shrinks as it gets closer.
- Drag a puck around the ring to move an alarm (5-minute steps with haptic ticks).
- Hold an empty spot on the ring to add an alarm there.
- Ring sequences: e.g. **4 rings · every 5 min**, shown as trailing dots on the dial.
- Repeat days, labels, snooze, full-screen ringing over the lock screen.

## Install

Every push to `main` builds a signed APK and publishes it on the
[Releases page](https://github.com/Bigoke/twenty4hours/releases/latest).
Open that link on your phone, download the `.apk`, and install it.

After installing, open the ⚙ settings sheet and allow everything listed there.
On OnePlus/OxygenOS also allow background activity for the app, then use
**Ring in 1 min** with the phone locked to confirm alarms come through.

## Build locally

```
./gradlew assembleRelease
```

Kotlin · Jetpack Compose · AlarmManager.setAlarmClock · minSdk 26 · targetSdk 35.

## Signing

No signing key lives in this repo. Without secrets, CI signs each APK with a
temporary debug key, so a new build won't install over the old one (uninstall first).

For stable signing (updates install in place), add these repository secrets under
Settings → Secrets and variables → Actions: `KEYSTORE_BASE64` (the .jks file,
base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
