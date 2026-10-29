# Pitchprint

Pitchprint is an Android app that records one player's movement during small-sided football games, so that distance, speed, sprints and time spent in each part of the pitch can be worked out afterwards.

It is at an early stage. The current version is a raw logger: it records GNSS fixes, satellite status, raw GNSS measurements where the phone supports them, and the motion sensors, keeps recording with the screen off, and exports each session as a zip. You can label the recording as you go: splits with the layout and your position, pauses, held points at the pitch corners, short test segments, and a marker on every volume button press. Nothing is computed on the phone yet.

The app has no internet permission. Recordings stay on the phone until you export one yourself.

## Building

You need JDK 17 and the Android SDK with platform 37 (Android Studio installs both). Point Gradle at the SDK with a `local.properties` file containing `sdk.dir=...`, or open the project in Android Studio, which writes it for you. Then:

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The debug APK lands in `app/build/outputs/apk/debug/`. It installs next to a release build because its package name ends in `.debug`, and the two keep separate recordings.

A release build is signed only when `local/keys/signing.properties` exists (with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`); that folder is never committed. Without it, `assembleRelease` produces an unsigned APK.

## Installing

Copy the APK to the phone and open it, or use `adb install -r <apk>`. Android will ask to allow installs from that source, and Play Protect may warn about an unknown app. The app needs Android 10 or newer.

On first use the home screen lists the settings that can stop a screen-off recording (precise location, notifications, battery optimisation and so on), each with a button that opens the right settings page. Updating the app keeps recordings; uninstalling deletes them, so export anything you want to keep first.

## Recordings

Sessions live in the app's own storage, `Android/data/<package>/files/sessions/`, one folder each. Export from the Sessions screen zips a session and opens the share sheet. The file format is described in [docs/session-format.md](docs/session-format.md), and `tools/check_session.py` (Python 3, standard library only) checks a session folder or zip for gaps, sample rates and broken lines:

```
python tools/check_session.py pitchprint_2026-11-01_0912_ab12.zip
```
