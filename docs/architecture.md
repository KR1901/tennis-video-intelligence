# Architecture

## Current Android architecture: offline-first

The Android app is moving to a self-contained architecture. A selected video is accessed through Android's Storage Access Framework (SAF); the original file remains in the user's chosen location. No upload, API endpoint, bearer token or network permission is needed.

```text
Android / Jetpack Compose
        |
        | ACTION_OPEN_DOCUMENT + persisted read grant
        v
Original video URI (user-controlled storage)
        |
        +---- MediaMetadataRetriever: metadata + frame preview
        |
        v
WorkManager persistent, bounded analysis chunks
        |
        v
Frame sampler (currently 1 fps, 160 x 90)
        |
        +---- green-pixel coverage (coarse court-colour signal)
        +---- frame-to-frame pixel change (coarse motion signal)
        +---- brightness + timestamp
        |
        v
Private app storage (project JSON + sample timeline)
        |
        +---- manual court-corner taps
        +---- CSV export
```

The current implementation deliberately exposes only coarse frame signals. It does not yet claim to locate players or the ball, classify shots, calculate court positions, or produce tactical statistics.

## Processing and persistence

- The app requests a persistable read grant for the chosen document URI; it does not duplicate the source video.
- Metadata inspection and frame extraction use Android `MediaMetadataRetriever`.
- The local input ceiling is 4,768 MiB. The app rejects files larger than this and files whose provider cannot report a verifiable size.
- WorkManager processes at most 24 one-second samples per work request, then queues a uniquely named continuation. This keeps individual jobs bounded and makes pending work durable across process exit and device restart. Android retains control over execution timing.
- Project metadata, sample rows and manual court-corner taps are stored under the app's private files directory. Re-running an analysis resets the project's prior sample timeline.
- CSV export is written through Android's document picker; there is no server-side report dependency.

## Next on-device pipeline stages

1. **Court geometry:** identify court lines/keypoints and estimate a confidence-scored image-to-court homography from the manual corners. Recalibrate when the camera pans, zooms or cuts.
2. **Player detection:** integrate a licence-compatible on-device detector, estimate foot/ground-contact points and maintain two stable player tracks across frames.
3. **Ball tracking:** use high-resolution adaptive frame windows around likely contact intervals; track the ball across occlusions and camera changes.
4. **Tennis events:** infer serve, bounce, contact and point events only with confidence thresholds and review affordances.
5. **Metrics:** derive serve location, return position, depth behind/inside baseline, shot placement and movement only from calibrated, validated observations.
6. **Visual reports:** produce time-linked court maps, timelines and infographics on the device.

Every observation should retain timestamp/frame index, model version, coordinate system, confidence and review state. Missing or uncertain observations must remain missing or uncertain rather than being filled with fabricated precision.

## Legacy backend

The repository retains the previous FastAPI service as a research/reference implementation. It is not on the current Android execution path. GitHub Actions can build and distribute the Android APK, but the current product direction does not require a permanently running server.

## Coordinate system

The eventual court plane uses metres, with the origin at the centre of the net, x across court width and y along court length. The near half is positive y and the far half negative y. Baselines are at +11.885 m and -11.885 m; doubles boundaries at x = ±5.485 m and singles sidelines at x = ±4.115 m. A valid homography and stable camera segment are prerequisites for reporting distances in metres.

## Validation requirements

Before any player/ball metric is presented as reliable, benchmark it against manually labelled held-out videos from different broadcast angles, court surfaces, lighting conditions, camera movements and resolutions. Report precision, recall, tracking identity switches, calibration error in metres, dropped-frame coverage and human-review rates. A green-area heuristic or generic motion signal is not a substitute for those tests.
