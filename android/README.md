# Android application

Native Android prototype built with Kotlin and Jetpack Compose. It connects to the FastAPI backend for video upload, media inspection, court calibration, candidate analysis, human event review, native video playback, deletion and SVG / PNG / PDF reporting.

## Requirements

- Android Studio with Android Gradle Plugin 9.5.0 support.
- JDK 17.
- Android SDK Platform 36 and Build Tools 36.0.0.
- Gradle 9.6.0.
- An accessible backend with FFmpeg and ffprobe installed.

The project uses AGP 9.5.0 with its built-in Kotlin support, Kotlin Compose compiler plugin 2.4.10, Compose BOM 2026.06.01, Gradle 9.6.0 and Java 17.

## Open and build

Open the `android/` directory in Android Studio and sync Gradle. The debug APK is produced at:

`app/build/outputs/apk/debug/app-debug.apk`

The release variant is produced at:

`app/build/outputs/apk/release/app-release.apk`

or `app-release-unsigned.apk` if a release keystore is not configured. See the signing section below.

A Gradle wrapper properties file is included; if the wrapper scripts are absent in your checkout, generate them once from this directory:

```bash
gradle wrapper --gradle-version 9.6.0
```

GitHub Actions builds the debug APK and the release APK. The release workflow uploads either a signed or explicitly unsigned artifact.

## Connect to the backend

The initial backend URL is `http://10.0.2.2:8000`, which reaches the host machine from the standard Android emulator. On a physical phone, change it in the app to the development computer's LAN IP and ensure both devices are on the same network.

Start the backend from `backend/`:

```bash
python -m pip install -e ".[dev]"
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Install FFmpeg so both `ffmpeg` and `ffprobe` are available on the backend PATH. Do not expose this unauthenticated development server to the public internet. Cleartext HTTP is enabled only in the debug manifest; release builds default to HTTPS-only.

## Current app flow

1. Choose a local video through Android's document picker and upload it.
2. Inspect duration, resolution, frame rate and codec through ffprobe; optionally enter the shared API bearer token when the backend is configured to require it.
3. Extract a JPEG frame at a chosen timestamp.
4. Request a heuristic green-region court-corner suggestion or tap the four corners manually in the order far-left, far-right, near-right, near-left. The app overlays the selected points so they can be checked before saving.
5. Save a manual court calibration and project a selected player's feet / ground-contact point into baseline distance in metres.
6. Start sampled analysis and refresh its progress.
7. Inspect near-end / far-end position summaries and density heatmaps.
8. Preview trajectory-change candidates at their source timestamps, then accept, reject or relabel them.
9. Add a manually confirmed event with an explicit timestamp and event type when the automatic detector misses it.
10. Export the court-positioning and reliability report as SVG, PNG or PDF.
11. Play the source video from the backend with seek support, or delete the uploaded video and associated local analysis files.

## Reliability limits

The current pipeline uses a green-region court heuristic, OpenCV's generic HOG pedestrian detector, a bounding-box-bottom ground-point approximation and a colour / compact-blob ball-candidate filter. These are not tennis-trained validated models or a robust multi-object player/ball tracker. They can miss the ball or players, detect spectators, and fail on camera cuts, zooms and occlusions. Automatic detections remain unverified candidates; the user must confirm court calibration and event labels before using event-derived statistics.

Position averages and heatmaps are prototype indicators based on generic person proposals. The current worker runs in-process and stores jobs / media locally. It is not a multi-user production service and does not provide authentication, cloud media storage, durable queue processing or integrated video playback.

## Release signing (Android)

The release workflow builds version `0.3.0`. A signed release is produced only when the repository has all four Actions secrets configured:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded keystore file.
- `ANDROID_KEYSTORE_PASSWORD` — keystore password.
- `ANDROID_KEY_ALIAS` — signing-key alias.
- `ANDROID_KEY_PASSWORD` — signing-key password.

Store the keystore securely offline and back it up. Do not commit it to the public repository. The same signing key must be retained for future app updates. Without all four secrets the workflow produces an explicitly labelled **unsigned** release APK artifact; that file is not installable as a normal production app until signed with your release key.

Run the workflow from GitHub Actions → Android Release APK → Run workflow. When signing is configured, the workflow uploads the signed APK and publishes the `v0.3.0` GitHub Release. When signing is absent, it uploads only the unsigned release artifact for inspection.


## API bearer-token configuration

For a single-instance private deployment, set `TVI_API_TOKEN` to a long random value and set `TVI_ENVIRONMENT=production`. The backend refuses to start in production mode without this token and requires `Authorization: Bearer <token>` on API routes (health and API documentation remain public). Enter the same token in the app's backend connection screen. This is a shared service token, not multi-user identity, account isolation or per-user media ownership. Put the backend behind HTTPS before using real footage; the release Android manifest disallows cleartext HTTP.
