# Activity Archive (Android)

A small, read-only Android app that imports usage history already exposed by Android, stores retrieved records locally, and exports date-filtered reports.

## What it does

- Uses Android `UsageStatsManager` / `UsageEvents` after the user grants Usage Access.
- Imports app foreground/background transitions, screen interactive/non-interactive, keyguard shown/hidden, and device startup/shutdown markers when Android returns them.
- Imports Android's daily per-app usage aggregates and launch counts where available.
- Stores imported records in a private SQLite database on the phone and avoids duplicate event rows on later imports.
- Filters saved data by today, one day, custom date range, or all locally saved history.
- Exports HTML, CSV, JSON, and plain-text reports through Android's document picker.

## Deliberately does NOT do

- No foreground service, background service, accessibility service, boot receiver, or periodic worker.
- No continuous event collection. Import happens only when the user taps **Import history available on this phone**.
- No internet permission, account, analytics SDK, or cloud upload.
- No root requirement.

## Important Android limitations

1. Android documents that detailed `UsageEvents` are retained by the system for only a few days. The importer asks for a seven-day window, but Android decides which events are actually available. The last few minutes can also be truncated by the platform. If the user did not import older detailed events while they were still available, this app cannot recreate their exact timestamps later.
2. `queryUsageStats()` may return older daily aggregates than `queryEvents()`, depending on the device and system retention. Aggregate totals are not exact per-session timestamps. The app labels these separately in the interface and exports.
3. `ACTIVITY_PAUSED` means an activity was paused / moved out of the foreground; it does not necessarily mean the app process was killed or the user explicitly force-closed the app. An app with multiple activities can produce several transitions. Session durations are therefore best-effort pairs of foreground and background events.
4. `DEVICE_SHUTDOWN` is a persisted runtime shutdown marker, not a guaranteed physical power-button timestamp. A sudden battery loss may not produce one. The documented shutdown timestamp is when the UsageStats database was last persisted, not necessarily the exact instant the phone powered off.
5. Ordinary third-party apps cannot freely read all system log buffers using `READ_LOGS`. ADB can expose additional diagnostic output in some circumstances, but logs are often volatile, OEM-dependent, and not a reliable substitute for retained UsageEvents. This starter project does not claim full logcat import.

## Build the app

1. Install Android Studio with Android SDK Platform 36 and JDK 17.
2. This project uses Android Gradle Plugin 8.10.1, Kotlin Gradle Plugin 2.4.21, and Gradle 8.14.4. The Kotlin JVM target is configured with the current `compilerOptions` DSL.
3. In this folder, generate the standard wrapper once with `gradle wrapper --gradle-version 8.14.4`, then open the project folder in Android Studio and allow Gradle sync to download the Android Gradle Plugin and Kotlin plugin.
4. Connect the OnePlus Nord CE 4 with USB debugging enabled, or use Android Studio's wireless deployment, then press **Run**. (USB debugging is only for installing/debugging the app; normal app use does not need ADB.)
5. On the phone, open **Settings → Apps → Special app access → Usage access** (wording can vary by OxygenOS release) and enable **Activity Archive**. You can also tap the app's Usage Access button to open the relevant Settings screen.
6. Return to Activity Archive and tap **Import history available on this phone**.
7. Choose a filter and export HTML, CSV, JSON, or text. Android's document picker lets you save to Downloads or another folder.

This source bundle does not include a generated APK. The build environment used to prepare it did not have the Android SDK installed, so the project still needs to be Gradle-synced and built in Android Studio on a development machine.

## Project structure

- `app/src/main/java/com/example/activityarchive/MainActivity.kt` — UI, imports, filtering, session pairing, export generation.
- `app/src/main/AndroidManifest.xml` — Usage Stats permission and launcher visibility query.
- `app/src/main/res/values/styles.xml` — simple platform Material theme.
- SQLite database — `activity_archive.db`, private to this app.

## Privacy and deletion

Imported records stay in the app's private local database. Uninstalling the app removes that private database. Exported files remain in the folder selected by the user. This initial version does not yet include an in-app delete/reset-archive button.

## Optional ADB diagnostics (not needed for normal operation)

The `optional-adb` folder contains read-only scripts for Windows and macOS/Linux. They collect a `dumpsys usagestats` snapshot, the currently buffered `events` log, the power service state, and basic boot properties. These are diagnostic snapshots only: log buffers are finite, may reset at reboot, and the format/content varies by Android/OxygenOS version. They are not automatically merged into the app database in this first version. ADB does not grant root access and is not needed to use the app.

## Build without Android Studio (GitHub Actions)

This project includes `.github/workflows/build-apk.yml`. To build the APK in GitHub's cloud without installing Android Studio locally:

1. Extract this ZIP on your computer.
2. Create a new repository on GitHub.
3. Upload the contents of the `ActivityArchive` folder to the repository, including the `.github/workflows/build-apk.yml` file. Make sure `.github` is not omitted as a hidden folder. You can also create that workflow file directly in GitHub using the included YAML.
4. Open the repository's **Actions** tab and select **Build Activity Archive APK**. If it has not started automatically after upload, choose **Run workflow**.
5. Open the completed workflow run and download the `activity-archive-debug-apk` artifact.
6. Extract the downloaded artifact ZIP to get `app-debug.apk`, then transfer it to the phone and install it. Android may ask you to allow installation from that source.

The workflow uses GitHub-hosted Linux runners, Java 17, Android SDK Platform 36, Build Tools 36.0.0, and Gradle 8.14.4. The APK is a debug build for testing, not a Play Store release. GitHub Actions must be enabled for the repository.
