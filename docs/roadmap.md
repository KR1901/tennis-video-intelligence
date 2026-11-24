# Roadmap

## Milestone 0 — repository and ingestion foundation

- [x] Establish project scope and architecture.
- [x] FastAPI health endpoint.
- [x] Chunked local video upload with size and file-type checks.
- [x] API tests and GitHub Actions CI.
- [x] Create remote GitHub repository.

## Milestone 1 — media inspection and job lifecycle

- [x] Probe media metadata with FFmpeg / ffprobe.
- [x] Persist inspection output alongside the local video metadata.
- [ ] Persist video and job metadata in PostgreSQL.
- [ ] Add background job queue and retry-safe job states.
- [ ] Add authentication, private object storage and retention controls.
- [ ] Generate low-resolution proxy video and representative frames.

## Milestone 2 — court and player positioning

- [ ] Court-keypoint model and camera-segment detection.
- [ ] Homography calibration with quality score and manual correction.
- [ ] Player detection and tracking with identity review.
- [ ] Ground-contact estimate and calibrated player coordinates.
- [ ] Baseline distance in metres and positioning heatmaps.
- [ ] Video-linked mini-court replay.

## Milestone 3 — ball tracking and tennis events

- [ ] Ball detector and short-term trajectory tracker.
- [ ] Hit, bounce and net-crossing candidates.
- [ ] Serve, return and point-boundary classification.
- [ ] Manual event correction UI and event audit log.
- [ ] Rally sequence construction and coverage reporting.

## Milestone 4 — shot and tactical analytics

- [ ] Serve placement and first/second serve reports.
- [ ] Shot landing map and depth / direction buckets.
- [ ] Rally length distribution and outcomes.
- [ ] Winners, forced errors and unforced errors with reviewed labels.
- [ ] Serve +1 / return +1 patterns.
- [ ] Player comparison and match filters.

## Milestone 5 — Android client and infographic studio

- [ ] Kotlin / Jetpack Compose upload and job progress screens.
- [ ] Interactive court visualisations and timestamp-linked replay.
- [ ] Match and player dashboards.
- [ ] Export PNG / PDF reports.
- [ ] User-managed retention and deletion controls.

## Out of scope until validated

- Exact spin measurement from ordinary broadcast video.
- Radar-grade serve speed claims from a single camera.
- Guaranteed download of arbitrary third-party video URLs.
- Fully automatic winner / error classification without review.
