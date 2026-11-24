# Roadmap and implementation status

Checked items mean that code paths exist in the current offline Android branch. They do not imply match-level accuracy. The legacy hosted backend is retained for research and is not required by the Android app.

## Milestone 0 — repository foundation

- [x] Establish project scope, architecture and Apache-2.0 source licence.
- [x] Android Kotlin / Jetpack Compose project and GitHub Actions build.
- [x] Legacy FastAPI prototype and backend CI suite.

## Milestone 1 — media input and local job lifecycle

- [x] Select local videos through Android Storage Access Framework and retain read permission.
- [x] Restore the last local project and inspect duration, dimensions, MIME type and file size.
- [x] Enforce a 4,768 MiB input ceiling; reject local providers that do not expose a verifiable size.
- [x] Add direct HTTPS video-file import with redirect validation, connected-network WorkManager, partial Range resume and streaming size enforcement.
- [x] Preview decoded frames at selected timestamps.
- [x] Run bounded, persistent WorkManager analysis chunks and record progress.
- [x] Persist the full sample stream incrementally in NDJSON and retain a bounded recent UI window.
- [x] Export the complete sample timeline as CSV.
- [ ] Add instrumentation tests for SAF provider variations, HTTP Range resume, process death, reboot and interrupted-download cases.
- [ ] Benchmark processing time, memory, battery and thermal load on mid-range devices.
- [ ] Add project rename, delete, multi-project library and storage cleanup controls.

## Milestone 2 — court geometry and player positioning

- [x] Save four manually tapped court corners and fit a projective homography to a standard doubles court.
- [x] Suggest approximate corner bounds from a green-region colour mask, with explicit manual verification.
- [x] Save manually tapped player ground points in court x/y metres and signed baseline distance.
- [x] Run MediaPipe Pose Landmarker Lite on sampled frames and extract feet, wrists and bounding boxes.
- [x] Associate up to two pose detections with temporary P1/P2 labels using nearest-foot-point continuity between chunks.
- [x] Project pose foot points into court coordinates where calibration is present.
- [ ] Train/validate a tennis-specific two-player detector and identity tracker.
- [ ] Add trained court-line/keypoint detection, camera pan/zoom/cut detection and confidence-scored recalibration.
- [ ] Validate foot-contact position error against manually labelled footage.

## Milestone 3 — ball and event understanding

- [x] Add the MIT TrackNet V1 tennis checkpoint export to ONNX and Android on-device heatmap inference.
- [x] Sample TrackNet every 200 ms (5 fps) using three consecutive decoded frames and store candidate ball points.
- [x] Project ball points into court coordinates when the user calibration is valid.
- [x] Generate confidence-gated possible direction-change/contact/bounce candidates.
- [x] Review, accept, reject and relabel event candidates with separate per-candidate label drafts.
- [x] Reproject candidate/manual event locations after court recalibration when source image coordinates are available.
- [x] Add manual timestamped event entry and manually tapped serve/shot location in court metres.
- [ ] Implement validated long-gap ball tracking, occlusion recovery and camera-segment handling.
- [ ] Train/validate serve, bounce, racket-contact and point-boundary classifiers.
- [ ] Add an annotated match set with precision, recall, false-positive and review-coverage reporting.
- [ ] Increase/adapt sampling around high-speed contacts after device performance benchmarking.

## Milestone 4 — tactical analytics and infographic studio

- [x] Mini-court with recent P1/P2 movement trails, density dots and ball candidate locations.
- [x] Court infographic export as SVG, PNG and PDF.
- [x] Export candidate/reviewed event records and calibrated manual positions as CSV, including event court coordinates and image points where available.
- [x] Calculate review-backed P1/P2 baseline summaries and sampled path lengths from the full on-device sample log.
- [x] Bin accepted, manually located serve labels into T/body/wide/outside-service-box and shot labels into short/mid-court/deep and left/centre/right.
- [x] Calculate rally duration/stroke summaries only from accepted point_start/point_end and accepted stroke labels.
- [x] Export review-backed match metrics as CSV and create whole-match report snapshots by streaming/decimating the full sample log.
- [ ] Train/validate automatic serve-placement and return-position recognition by serve type. The current T/body/wide bins are only descriptive counts from manually accepted event locations.
- [ ] Train/validate automatic shot landing/depth recognition and point/rally construction. The current shot bins and rally-length summaries require user-entered/accepted event markers.
- [ ] Derive winners, forced/unforced errors, serve +1 and return +1 only from verified event sequences.
- [ ] Add player comparison filters, set/point selection, time-linked replay and performance summaries.
- [ ] Benchmark all automated metrics against human-annotated held-out matches before presenting them as reliable.

## Accuracy boundaries

- MediaPipe pose is generic; it is not trained to identify the two tennis players. P1/P2 are short-term spatial labels and may swap after occlusions, ends changes, camera cuts or large movement.
- TrackNet V1 produces candidate tennis-ball positions on a 5 fps grid. This is not a validated continuous track and can miss high-speed/occluded ball motion.
- Green-region suggestions are approximate color bounds, not line detection. Court metres require correct four-corner calibration and a stable camera.
- Event candidates are trajectory heuristics. They are review aids, not official serves, contacts, winners, errors or bounce statistics.
- Manual event labels/coordinates are distinct from model-derived observations and must retain that provenance.
- WorkManager persists pending work but Android controls scheduling. Force-stop halts automatic restart until the app is reopened.
- Direct URL input supports only authorised direct HTTPS video files, not watch pages, playlists, DRM or subscription streams.
- User-selected source videos remain at their original location. Direct-link downloads and generated project data are private to the app and are removed on uninstall.

## Legacy backend research

The FastAPI ingestion and OpenCV prototype remains available for algorithm comparison. It is not deployed by GitHub and is not on the primary Android product path.
