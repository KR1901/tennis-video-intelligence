# Architecture

## Android application: offline analysis with optional direct-link ingress

The Android app is the primary product execution path. A selected local video is accessed through Android's Storage Access Framework; an authorised direct HTTPS media URL can optionally be downloaded into private app storage by a resumable WorkManager job. The video itself is never uploaded to a backend.

```text
Android / Jetpack Compose
   |                         |
   | local video URI         | optional direct HTTPS video URL
   | persisted read grant    | WorkManager + HTTPS + Range resume
   v                         v
User-selected source    App-private downloaded media
           \                 /
            \               /
             v             v
       MediaMetadataRetriever
          frame decode / preview
                    |
                    v
         WorkManager 5-second chunks
           (5 fps sampling grid)
                    |
          +---------+----------+
          |                    |
          v                    v
 MediaPipe Pose Lite       TrackNet V1
 up to 2 generic poses    tennis-ball heatmap
          |                    |
          v                    v
 P1/P2 short-term         ball candidate points
 nearest-foot association      |
          |                    |
          +---------+----------+
                    |
          manual court-corner calibration
          + green-region corner suggestion
                    |
          4-point court homography
                    |
          court x/y metres + signed baseline
                    |
          +---------+----------+
          |                    |
          v                    v
    Reviewable event      Local project store
    trajectory heuristics   JSON + append-only NDJSON
          |                    |
          +---------+----------+
                    |
           mini-court / trails / heat dots
           CSV + SVG / PNG / PDF exports
```

## Inference and confidence

- Pose Landmarker Lite estimates up to two visible human body poses. It does not know which person is Player 1 or Player 2 by tennis identity. The app associates detections between adjacent samples using a nearest-foot-point heuristic, assigning temporary P1/P2 IDs.
- TrackNet V1 consumes three consecutive 640 × 360 frames and produces a tennis-ball probability heatmap. The current Android path samples at 5 fps; pose inference runs on alternate samples (about 2.5 fps). This is a resource trade-off, not full-frame-rate tracking.
- Ball and pose observations retain timestamps and confidence/proposal values. Missing observations remain missing.
- Candidate events are derived from ball-path changes and wrist proximity. They are saved as `needs_review` and never treated as confirmed match events without user action.
- The app can manually label events and save court locations. Manual labels and model candidates remain separate data sources.

## Court coordinate system

The court plane uses metres, with the origin at net centre, x across court width and y along court length. The near half is positive y and the far half is negative y. Baselines are at +11.885 m and -11.885 m; doubles boundaries at x = ±5.485 m and singles sidelines at x = ±4.115 m. A four-corner projective homography maps normalized image points to the standard 10.97 m × 23.77 m doubles court.

Court calibration is manually tapped or initialized by a green-region suggestion that requires user verification. There is no trained court-line/keypoint model or camera-motion segmentation. Measurements become invalid after an unhandled camera pan, zoom or cut.

## Storage and background work

- Local SAF videos are not copied; a persistable read grant is retained.
- Direct HTTPS video-file downloads use WorkManager with a connected-network constraint and resume by HTTP Range where supported. Content must be a direct video response, not a watch page, DRM stream or playlist.
- A shared 4,768 MiB limit is applied to local inputs and downloaded files.
- Each analysis request processes a bounded five-second segment. WorkManager chains the next segment and can resume pending work after process exit/reboot, subject to Android scheduling. Force-stop prevents automatic restart until the app is reopened.
- Project metadata is stored as JSON and the full sample stream as append-only NDJSON. The JSON project keeps a bounded recent sample tail for UI rendering; CSV export reads the full log.
- Downloaded source files and metadata remain in app-private storage. Original SAF videos remain in their user-selected location.

## Visual outputs

The mini-court displays recent calibrated P1/P2 paths, density markers, current foot positions and ball observations. SVG, PNG and PDF reports use court-calibrated observations and manually reviewed event locations. CSV exports expose the sample timeline, event review state and manual positions.

## Legacy backend

The previous FastAPI/OpenCV service remains in the repository as a research/reference implementation. It is not required by the Android application and is not the primary data or inference path. GitHub Actions builds the Android APK and retrieves the open-source model assets during the build; a permanent API server is not required.

## Validation requirements and limits

The open-source models are not a fully validated tennis analytics stack. Before deriving official or decision-grade statistics, the pipeline needs a manually labelled held-out tennis video set, calibration error measurements, pose precision/recall, ball tracking precision/recall, identity switch counts, missed-frame rates, camera-cut tests, device performance/battery/thermal benchmarks and human-reviewed event accuracy. Generic pose output, colour coverage or a TrackNet point alone must not be presented as proof of a serve, bounce, shot landing, winner or player identity.
