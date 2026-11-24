# Roadmap and implementation status

This roadmap distinguishes working prototype functions from production-ready capabilities. A checkbox marked **prototype** is implemented in code, but does not imply match-level accuracy or production infrastructure.

## Milestone 0 — repository and ingestion foundation

- [x] Establish project scope, architecture and Apache-2.0 source licence.
- [x] FastAPI health endpoint and API documentation.
- [x] Chunked local video upload with extension, content-type and size checks.
- [x] Backend tests and GitHub Actions CI.
- [x] Android project and CI build workflow.

## Milestone 1 — media inspection and job lifecycle

- [x] Probe media metadata with FFmpeg / ffprobe.
- [x] Persist local video metadata and inspection output.
- [x] Extract a JPEG frame by timestamp for review.
- [x] Persist manual court calibration and project image points into court coordinates.
- [x] Create analysis jobs, progress state and result files using local JSON storage and FastAPI background tasks.
- [ ] Replace local JSON storage with PostgreSQL / SQLite-backed repositories and migrations.
- [ ] Replace in-process background tasks with a durable queue, retry policy and worker heartbeats.
- [ ] Add user authentication, authorisation, quotas and access-scoped media records.
- [ ] Add private object storage, signed upload / playback links and retention/deletion controls.
- [ ] Generate low-resolution proxy videos and representative frames.

## Milestone 2 — court and player positioning

- [x] Homography estimation, image-to-court projection and signed baseline-distance utilities.
- [x] Manual court-corner selection endpoint and Android tap UI.
- [x] Heuristic green-region court-corner suggestion with explicit user confirmation.
- [x] Generic OpenCV HOG person proposals and a bounding-box-bottom ground-point approximation.
- [x] Court-plane projection when a manual calibration is supplied.
- [x] Near-end / far-end baseline summaries and position-density heatmaps (prototype).
- [ ] Tennis-specific court-keypoint model, multi-camera-segment detection and calibration confidence validation.
- [ ] Robust player detector and multi-object identity tracker with occlusion / changeover handling.
- [ ] Pose-based foot-contact estimation and validated ground-point error in metres.
- [ ] Camera pan / zoom compensation and per-segment calibration.
- [ ] Timestamp-linked mini-court replay and reviewed track correction.

## Milestone 3 — ball tracking and tennis events

- [x] HSV tennis-ball-colour blob candidates (low-confidence visual proposals).
- [x] Sparse ball-candidate trace and trajectory-direction-change candidates.
- [x] Event review API and audit metadata for accept / reject / relabel.
- [x] Manual event entry for serve, bounce, contact, point-boundary and tactical labels.
- [ ] Tennis-trained ball detector and short-gap trajectory tracker.
- [ ] Motion-aware candidate generation for racket contact, bounce and net crossing.
- [ ] Validated serve, return and point-boundary models.
- [ ] Synchronized frame / video playback during event review.
- [ ] Automatic point construction with missing-video and review-coverage accounting.

## Milestone 4 — shot and tactical analytics

- [x] Calculate serve and shot landing positions from explicitly reviewed events (prototype).
- [x] Calculate rally contact counts when point-start, point-end and contact labels are reviewed.
- [x] Calculate manually reviewed winners, forced errors, unforced errors, serve +1 and return +1 counts.
- [x] Include event provenance and review state in persisted results.
- [ ] Separate first-serve / second-serve placement and outcome reports.
- [ ] Calibrated shot depth, direction and service-box zone buckets.
- [ ] Validated rally sequence, point outcome and player-level aggregates.
- [ ] Match / player comparison filters and confidence-aware ranking.
- [ ] Benchmark every metric against human-labelled held-out matches.

## Milestone 5 — Android client and infographic studio

- [x] Kotlin / Jetpack Compose video picker, upload and media-inspection screens.
- [x] Frame preview, manual corner taps and visible calibration markers.
- [x] Analysis start / refresh, player-position summaries and heatmaps.
- [x] Candidate-event review, manual event entry and SVG report export.
- [ ] Native video playback with timestamp seek and event-to-frame review.
- [ ] Interactive mini-court with track / ball timelines and player comparison.
- [ ] PNG and PDF report export.
- [ ] User-managed retention and deletion controls.
- [ ] Production API authentication and HTTPS-only backend deployment.

## Production-readiness blockers

- The analysis worker currently uses FastAPI in-process background tasks and local JSON/files; it is a single-instance development implementation, not a durable distributed service.
- Court detection is a green-region heuristic and asks for user confirmation. Automatic orientation and camera-segment calibration are not solved.
- Player proposals use OpenCV's generic HOG pedestrian detector. They are not tennis-trained, their identities are not reliably tracked, and the bottom of a bounding box is only an approximate ground point.
- Ball proposals use colour / compact-blob filtering at sampled frames. They are not validated ball tracks and do not establish contacts or bounces.
- Event-derived statistics are counted only after a user explicitly confirms an event type. Unreviewed candidates must not be treated as official statistics.
- The current Android APK has an unsigned release build configuration. A production-signed APK requires a user-controlled signing keystore configured as GitHub Actions secrets.

## Out of scope until validated

- Exact spin measurement from ordinary single-camera broadcast video.
- Radar-grade serve speed claims from one camera.
- Guaranteed downloading of arbitrary YouTube, broadcast or subscription-streaming URLs.
- Automatic winner / error classification from colour blobs or generic person detection.
