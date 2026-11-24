# Android application

The Android client is an offline-first tennis video analysis workspace. It can open a video already on your device or import a direct, authorised HTTPS video-file link. The downloaded or selected video is processed on the phone; the app does not need a hosted backend, API URL or bearer token.

## Requirements

- Android Studio with Android Gradle Plugin 9.5.0 support.
- JDK 17.
- Android SDK Platform 36 and Build Tools 36.0.0.
- Gradle 9.6.0.
- An internet connection during build/CI to retrieve the open-source model assets and Gradle dependencies.
- On-device internet access only when using direct HTTPS link import.

The project uses AGP 9.5.0, Kotlin Compose compiler plugin 2.4.10, Compose BOM 2026.06.01, WorkManager 2.12.0, MediaPipe Tasks Vision 1.2.3, ONNX Runtime Android 1.32.0, Gradle 9.6.0 and Java 17.

## Build

Open the `android/` directory in Android Studio and sync Gradle. The debug APK is produced at:

`app/build/outputs/apk/debug/app-debug.apk`

The release APK is produced at:

`app/build/outputs/apk/release/app-release.apk`

or `app-release-unsigned.apk` if no release keystore is configured. See the signing section below.

GitHub Actions downloads and verifies the MediaPipe Pose Landmarker model, downloads the MIT TrackNet V1 tennis checkpoint and converts it to ONNX before assembling the APK. The binary model assets are generated during the build rather than stored in Git. CI runs unit tests before assembling the debug APK.

## Input options

### Video already on the phone

1. Tap **Choose video from this device** and select a video through Android's document picker.
2. The app reads metadata and frames directly from the selected content URI. It does not upload or copy the source video.
3. The local processing ceiling is 4,768 MiB (just under 5 GB). The provider must report a verifiable file size; files with unknown sizes are rejected so the limit can be enforced.

### Direct video-file link

1. Paste a direct HTTPS URL that returns an actual video file, such as an MP4 or MOV.
2. Tap **Download direct video link**. The app schedules a connected-network WorkManager job and shows download progress.
3. Interrupted downloads are resumed from the saved byte offset when the origin supports HTTP Range. The same 4,768 MiB limit is enforced while streaming.
4. The downloaded file is stored in the app's private files and then opened as a local project. No video is sent to our backend.

This path requires internet access and the Android INTERNET permission. It deliberately does **not** scrape YouTube/watch pages, playlists, HLS/DASH manifests, DRM or subscription streams, or bypass provider access controls. Only use direct links you are authorised to download. A website page URL is not a direct media file URL.

## Calibrate the court

1. Preview a frame at a known timestamp.
2. Optionally tap **Suggest court corners from green region**. This is a rough colour-segmentation suggestion, not court-line recognition. Verify or clear it.
3. If needed, tap the four outer court corners in this order: far-left, far-right, near-right, near-left.
4. The app fits a four-point homography to a standard doubles court measuring 10.97 m across and 23.77 m long.
5. Tap **Mark player foot on this frame**, then tap the midpoint between the player's feet / ground-contact point to save a manual point in court x/y metres and signed baseline distance. Positive means behind the baseline; negative means inside the court.
6. Use **Mark serve / shot location on this frame** to save a user-labelled event location in calibrated court metres. The event type comes from the event-review field.

These measurements depend on correctly paired corners and a stable camera view. Recalibrate after the camera pans, zooms or cuts. A wrong calibration can produce plausible-looking but wrong distances.

## On-device analysis

The WorkManager pipeline processes bounded five-second chunks. Choose one of three sampling rates before starting: quick 2.5 fps (400 ms), balanced 5 fps (200 ms, default), or detailed 10 fps (100 ms). The choice is carried through every background continuation. Detailed mode runs more TrackNet inference and will take longer and use more battery/thermal headroom:

- **Player poses:** MediaPipe Pose Landmarker Lite is asked for up to two visible people. Foot/ankle points and wrist visibility are recorded. A short-term nearest-foot-point association assigns temporary P1/P2 labels; this is not a verified identity tracker and can swap during occlusion or camera cuts. Pose is sampled on alternate frames (about 2.5 observations per second).
- **Tennis ball:** TrackNet V1 is run on three consecutive frames to generate a tennis-ball probability heatmap and a candidate ball point. This is a model proposal, not a continuously validated track; fast motion, low resolution, camera changes and occlusion can cause misses or false detections.
- **Court mapping:** where four corners are available, player foot points and ball points are projected into court metres. This assumes the camera remains in the same view as the selected calibration.
- **Frame diagnostics:** green-pixel coverage, coarse frame-to-frame change, brightness and sample timestamps are also stored.
- **Events:** sharp ball-path changes may become possible racket-contact/bounce/direction-change candidates. Candidates remain `needs_review`; they are not automatically converted into official serves, contacts, winners, errors or bounces.

The five-fps sample grid is a resource-conscious first mobile implementation, not full-frame-rate analysis. Review the source frame and correct event type/time before using any event count.

## Review and visualisation

- Source-frame preview can overlay the nearest player pose boxes/feet and ball proposal.
- The mini-court plots calibrated P1/P2 trails, density dots and ball observations.
- Review candidate events in independent per-event label editors; accepting one candidate does not alter another candidate's label.
- Add manually confirmed events at a selected timestamp.
- Save a calibrated manual position or serve/shot location.
- Export the full sampled timeline, event-review table and manual court positions as CSV.
- Export a court infographic/report as SVG, PNG or PDF.

CSV export of the sample timeline reads the full on-device sample log. Match metrics also stream the full sample log rather than using only the recent JSON window. The in-app mini-court uses a bounded recent window; SVG/PNG/PDF reports use an evenly sampled whole-match snapshot with a bounded point count.

## Background processing and storage

Each WorkManager job analyses a bounded five-second segment and schedules the next segment. This keeps work persistent across normal activity/process exit and device restart. Android controls when work runs; battery saver, storage-provider availability, thermal state and system policy can defer processing. A user force-stop prevents work from restarting until the app is opened again.

Original videos selected from the phone remain in their original location. Direct-link downloads are stored under the app's private files directory. Project metadata, frame samples, calibration points and event reviews remain in app-private storage. Uninstalling the app removes app-private projects and downloaded copies; it does not delete a selected original video.

## Review-backed match metrics

The match metrics card uses the full saved sample log to summarise calibrated P1/P2 observations: observation count, percentage behind the baseline, mean signed baseline distance and sampled path length. Movement is summed only across short temporal gaps and plausible single-step displacements; camera movement and P1/P2 identity swaps can still bias it.

Serve placement bins (T/body/wide/outside service box) and shot placement bins (short/mid-court/deep; left/centre/right) count only accepted, manually reviewed event labels that have court coordinates. Returner-position summaries use the nearest calibrated pose on the serve target half within 1.2 seconds of an accepted serve-location marker. Rally duration and reviewed stroke counts require accepted point_start and point_end markers plus manually accepted stroke labels. The app exports these tables to CSV. Direction labels refer to the calibrated court x-axis, not necessarily the player's facing direction. These are descriptive summaries of reviewed annotations, not automatic match-stat recognition. Return-position estimates are especially sensitive to P1/P2 identity swaps, camera cuts, pose misses and an incorrectly timed or located serve marker.

## Accuracy and current limitations

This is an AI-assisted prototype, not a validated professional match-statistics system.

- MediaPipe pose proposals are generic human poses, not tennis-specific player detections.
- P1/P2 are temporary spatial associations, not guaranteed player identities across ends/changeovers.
- TrackNet V1 ball positions are low-confidence proposals until checked against the video; the app does not yet have a robust long-gap ball tracker.
- No automatic court-line/keypoint detector or camera-pan/zoom segmentation is implemented. Green-region suggestions must be checked.
- There is no trained serve/forehand/backhand/volley classifier, landing detector, bounce validator or point-outcome model in this release.
- The app can manually measure a ground point or event location in court metres after calibration. Those manual measurements are different from model-derived observations.
- Automatic event candidates are heuristic review aids, not official statistics.

Reliable automatic return position, serve placement, shot placement, rally structure, winners/errors and tactical comparisons still require tennis-specific models, longer temporal tracking, labelled match datasets, device benchmarks and held-out validation.

## Legacy backend

The previous FastAPI service remains in the repository for research/reference comparisons. It is not on the Android execution path and is not required for either local-video or direct-HTTPS-link analysis.

## Release signing

The current follow-up branch prepares version `0.6.0`. A signed APK is produced only when all four repository Actions secrets are configured:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded keystore file.
- `ANDROID_KEYSTORE_PASSWORD` — keystore password.
- `ANDROID_KEY_ALIAS` — signing-key alias.
- `ANDROID_KEY_PASSWORD` — signing-key password.

The same signing key must be retained for future updates. Without all four secrets the workflow produces an explicitly labelled unsigned APK artifact, not a production-installable release.
