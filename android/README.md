# Android application

Native Android prototype built with Kotlin and Jetpack Compose. It currently connects to the FastAPI development backend for video upload, media inspection, frame preview and manual court calibration.

## Requirements

- Android Studio with a compatible Android Gradle Plugin 9.5.x environment.
- JDK 17.
- Android SDK Platform 36 and Build Tools 36.0.0.
- Gradle 9.6.0.
- An accessible backend with FFmpeg and ffprobe installed.

The project pins Android Gradle Plugin 9.5.0, Kotlin 2.4.21, Compose BOM 2026.09.00, Gradle 9.6.0 and Java 17. These versions were selected against the official Android / Kotlin release documentation.

## Open and build

Open the `android/` directory in Android Studio and sync Gradle. The debug APK is produced at:

`app/build/outputs/apk/debug/app-debug.apk`

A Gradle wrapper properties file is included; if the wrapper scripts are absent in your checkout, generate them once from this directory using:

```bash
gradle wrapper --gradle-version 9.6.0
```

The Android CI workflow installs Gradle 9.6.0 and runs `gradle :app:assembleDebug` on Android-related changes.

## Connect to the backend

The initial backend URL is `http://10.0.2.2:8000`, which reaches the host machine from the standard Android emulator. On a physical phone, change it in the app to the development computer's LAN IP and ensure both devices are on the same network.

Start the backend from `backend/`:

```bash
python -m pip install -e ".[dev]"
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Install FFmpeg so both `ffmpeg` and `ffprobe` are available on the backend PATH. Do not expose this unauthenticated development server to the public internet.

The app's development manifest currently permits cleartext HTTP for local testing. Before any production release, switch the API to HTTPS, disable cleartext traffic, add authentication and implement secure upload / playback.

## Current app flow

1. Choose a local video through Android's document picker.
2. Upload it to the backend.
3. Inspect its metadata through ffprobe.
4. Extract a JPEG frame at a timestamp.
5. Tap the four court corners in the displayed order: far-left, far-right, near-right, near-left.
6. Save the manual court calibration.
7. Tap a player's feet / ground-contact point, choose near or far court end, and request its signed baseline distance.

This prototype uses manual taps. It does not yet detect court lines or players automatically, and it is not a finished production app.
