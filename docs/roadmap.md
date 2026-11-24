# Roadmap and implementation status

A checked item means the code path exists; it does not imply match-level accuracy. The offline Android work is being developed separately from the legacy hosted-backend prototype.

## Milestone 0 — repository foundation

- [x] Establish project scope, architecture and Apache-2.0 source licence.
- [x] Android Kotlin / Jetpack Compose project and GitHub Actions build.
- [x] Legacy FastAPI prototype and its backend CI suite.

## Milestone 1 — Android offline foundation

- [x] Select a local video with Android's Storage Access Framework.
- [x] Persist the document read grant and reopen the last local project.
- [x] Inspect duration, dimensions, MIME type and provider-reported file size.
- [x] Enforce a 4,768 MiB local video ceiling; reject unverified file sizes.
- [x] Preview a decoded frame at a selected timestamp.
- [x] Save four manually tapped outer-court corners in app-private project data.
- [x] Run bounded on-device frame-sampling chunks with WorkManager continuation.
- [x] Persist sample timestamps, approximate green-pixel coverage, brightness and coarse frame-change values.
- [x] Export the sample timeline as CSV.
- [ ] Add instrumentation tests for local media providers, permission persistence, process death and reboot recovery.
- [ ] Benchmark processing time, memory, battery and thermal load on mid-range Android phones.
- [ ] Add local project management, rename/delete and storage estimates.

## Milestone 2 — court geometry and player positioning

- [ ] Tennis-court line/keypoint model that runs on device.
- [ ] Confidence-scored homography from image coordinates into court metres.
- [ ] Camera pan / zoom / cut segmentation and recalibration.
- [ ] Tennis-specific player detector and two-player identity tracker.
- [ ] Foot / ground-contact estimation with an explicit uncertainty measure.
- [ ] Calibrated baseline-distance and return-position metrics.
- [ ] Court heatmaps based on reviewed, confidence-scored player observations.

## Milestone 3 — ball and event understanding

- [ ] Tennis-ball detector with temporal tracking and occlusion recovery.
- [ ] Adaptive high-frame-rate sampling around likely contacts.
- [ ] Validated serve, bounce, racket-contact and point-boundary event models.
- [ ] Review and correction UI linked to source frames and timestamps.
- [ ] Human-labelled test set with precision / recall and coverage reporting.

## Milestone 4 — tactical analytics and visual reports

- [ ] Serve placement (T / body / wide) with confidence and denominators.
- [ ] Shot placement and depth (short / mid / deep) in court coordinates.
- [ ] Return position relative to baseline, in metres and by serve type.
- [ ] Rally length, movement patterns and player comparisons.
- [ ] Time-linked mini-court, shot maps, filters and infographic export.
- [ ] Benchmarks against manually annotated matches before metrics are labelled reliable.

## Reliability and product constraints

- The current offline pass is a coarse visual sampler, not a tennis detector.
- Manual court corners are stored but are not yet used to transform observations into metres.
- No player identity, player tracking, ball tracking, shot recognition, serve recognition or automatic event statistics are implemented in the offline Android path.
- WorkManager persists and resumes pending work, but Android controls scheduling and may defer work under battery/system constraints. A force-stop prevents work from restarting until the user opens the app.
- Original videos remain in the user's selected storage location. Project data is private to the app and is removed if the app is uninstalled.
- The 4,768 MiB ceiling applies to verifiable local input size; providers that do not disclose a size are rejected.

## Legacy backend research

The FastAPI ingestion and OpenCV prototype remains in the repository for algorithm comparison and migration reference. It is not required by the offline Android application.
