# Android application

The primary Android workflow is designed to run on the phone. It does not need a hosted backend, API URL, API token, or video upload.

## Requirements

- Android Studio with Android Gradle Plugin 9.5.0 support.
- JDK 17.
- Android SDK Platform 36 and Build Tools 36.0.0.
- Gradle 9.6.0.
- Internet access only to resolve Gradle dependencies when building; the installed app requests no network permission.

The project uses AGP 9.5.0, Kotlin Compose compiler plugin 2.4.10, Compose BOM 2026.06.01, WorkManager 2.12.0, Gradle 9.6.0 and Java 17.

## Build

Open the `android/` directory in Android Studio and sync Gradle. The debug APK is produced at:

`app/build/outputs/apk/debug/app-debug.apk`

The release APK is produced at:

`app/build/outputs/apk/release/app-release.apk`

or `app-release-unsigned.apk` if no release keystore is configured. See the signing section below.

GitHub Actions builds the debug APK for pull requests. The release workflow produces a signed APK only when all four Android signing secrets are configured.

## Use the offline app

1. Tap **Choose video from this device** and select a video through Android's document picker.
2. The app reads metadata and frames directly from the selected content URI. It does not copy or upload the source video.
3. The local processing ceiling is 4,768 MiB, just under 5 GB. The app requires Android's provider to report a verifiable file size; files with unknown sizes are not accepted because the limit cannot otherwise be enforced.
4. Preview a frame at a timestamp. Tap the four outer court corners in this order: far-left, far-right, near-right, near-left. The selected points are saved in the app's private storage.
5. Start on-device analysis. WorkManager processes the video in short, persistent chunks at one sampled frame per second and a 160 × 90 working resolution. It stores the sample timeline locally.
6. Leave the app while processing. WorkManager persists queued work across app process exit and device restart; Android can still defer execution under battery and system scheduling policies. A force-stop cancels the system's ability to restart work until the app is opened again.
7. Export the sampled frame timeline as CSV.

The local video remains in the original location selected by the user. The app stores project metadata, saved court-corner taps and sample results in its private application files. Uninstalling the app removes those private project files; it does not delete the original video.

## What the current detector actually measures

This first offline milestone samples small frames and reports:
- approximate green-pixel coverage, which can help assess whether a court-like green region is visible;
- coarse frame-to-frame pixel change, which is a general motion signal;
- frame brightness and timestamps.

These signals are **not** player detections, player identities, ball tracks, shot contacts, bounces, serve placements or court-metre measurements. The four manually selected court corners are persisted as calibration input, but the current motion-sampling pass does not yet map detections into court coordinates.

Do not use this version to claim player positioning, return depth, shot placement or match statistics. Those capabilities require tennis-specific on-device models, court homography, tracking, confidence handling and validation against manually labelled match footage.

## Background processing

Each WorkManager task samples a bounded number of frames and schedules the next chunk. This limits the duration of an individual job and allows Android to resume pending work after app process termination or reboot. The operating system controls when background work runs; battery saver, storage-provider availability, device temperature and force-stop can affect completion.

## Legacy backend

The repository still contains the original FastAPI prototype for development and algorithm comparison. It is not required by the current Android app and is not deployed by GitHub. Do not configure a backend URL or token for this offline app.

## Release signing

The release workflow currently builds version `0.4.0`. A signed APK is produced only when the repository has all four Actions secrets configured:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded keystore file.
- `ANDROID_KEYSTORE_PASSWORD` — keystore password.
- `ANDROID_KEY_ALIAS` — signing-key alias.
- `ANDROID_KEY_PASSWORD` — signing-key password.

The same signing key must be retained for future updates. Without all four secrets the workflow produces an explicitly labelled unsigned APK artifact, which is not a normal installable production update.

