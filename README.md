# Tennis Video Intelligence

An Android-first tennis video analysis prototype. The current client processes match videos on the phone and does not require a permanently hosted API server.

> **Status: on-device analysis prototype.** This branch integrates generic two-person pose estimation (MediaPipe Pose Landmarker Lite), tennis-ball candidate inference (TrackNet V1), manually verified court calibration, court-plane projections, reviewable trajectory events, manual event/location correction, mini-court visualisation and local SVG/PNG/PDF/CSV reports. These components are not yet validated to generate official or decision-grade tennis statistics.

## What the Android app supports

- Select a local video from Android storage, or optionally download an authorised direct HTTPS video-file URL. The app does not upload footage to a backend.
- Enforce a local 4,768 MiB ceiling (just under 5 GB).
- Preview frames and save manual court-corner calibration. A green-region colour suggestion may help initial setup but must be checked.
- Run background on-device pose/ball inference in bounded WorkManager chunks.
- Project player feet and ball candidate points into court metres after a valid four-corner calibration.
- Review event candidates, relabel/accept/reject them, and manually enter timestamped serve/shot/event locations.
- Inspect a mini-court with recent player trails, density dots and ball candidates.
- Export the sample timeline, event review and manual positions as CSV, plus SVG/PNG/PDF court reports.

See the [Android guide](android/README.md), [architecture](docs/architecture.md) and [roadmap](docs/roadmap.md).

## Input and privacy

Local videos are read through Android's document picker and remain in their selected storage location. Direct link import downloads only a direct HTTPS video response into app-private storage, with HTTP Range resume where the server supports it. The app does not scrape YouTube/watch pages, playlists, DRM or subscription streams. Analysis runs locally; the internet permission is used only for the optional direct-link workflow. Model binaries are downloaded from their licensed upstream sources and packaged during GitHub Actions builds rather than checked into Git.

## Accuracy limits

Pose detections are generic body poses; P1/P2 are temporary spatial associations, not verified tennis-player identities. TrackNet V1 ball outputs are candidate points on a 5 fps sampling grid, not a robust full-frame-rate track. Event candidates are heuristic and stay unconfirmed until reviewed. The app has no trained court-line detector, camera movement segmentation, automatic serve/shot classifier, bounce validator, point constructor or validated winner/error model. Distances in metres require correct manual calibration and a stable camera. See the roadmap for the validation work still required.

## Repository layout

```text
android/       Kotlin + Jetpack Compose offline-first Android app
backend/       Legacy FastAPI/OpenCV research prototype (not required by app)
scripts/       Reproducible download and ONNX conversion of licensed model assets
docs/          Architecture, metric definitions, reliability and roadmap
.github/       CI and release workflows
```

## Legacy backend

The previous FastAPI prototype remains available for development and algorithm comparisons. It is not required by the Android app. See the [legacy backend guide](backend/README.md) if you need to run it locally.

## Android release signing

The release workflow produces a signed APK only when all four repository Actions secrets are configured: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. Without them the workflow uploads an explicitly unsigned release artifact, not a production-installable GitHub Release.

## Model licences

- TrackNet V1 tennis checkpoint: MIT, see `android/THIRD_PARTY_NOTICES_MODELS.txt`.
- MediaPipe Pose Landmarker Lite / MediaPipe Tasks: Apache 2.0.
- ONNX Runtime Android: MIT.

The original application source is Apache-2.0. Model weights, datasets and match footage retain their own licences and terms.
