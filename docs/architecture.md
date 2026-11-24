# Architecture

## System shape

```text
Android (Kotlin / Jetpack Compose)
        |
        | HTTPS: upload, status, results, signed playback
        v
FastAPI control plane -------------------- PostgreSQL
        |                                  users, videos, jobs,
        | enqueue analysis job             events, metrics, versions
        v
Queue (Redis / managed queue)
        |
        v
GPU analysis workers ------------------- Object storage
        |                                  original clips, derived
        |                                  frames and visual exports
        v
Versioned analysis pipeline
  1. media probe / shot-boundary detection
  2. court keypoints + homography
  3. player detection + tracking
  4. ball detection + tracking
  5. hit / bounce / serve / point events
  6. calibrated metrics + confidence
        |
        v
Results API -> interactive dashboards, replay and infographic export
```

The current code implements only the first local-development slice: FastAPI health and video upload to local disk. The other components are design targets, not existing functionality.

## Coordinate system

The implemented court-plane convention uses metres, with the origin at the centre of the net, the x-axis across the court width, and the y-axis along the court length. The near half is positive y and the far half is negative y. The baselines are therefore at +11.885 m and -11.885 m. Doubles boundaries are at x = ±5.485 m; singles sidelines are at x = ±4.115 m. The overall court length is 23.77 m, singles width is 8.23 m, and doubles width is 10.97 m, following ITF Rule 1 and Appendix IX (https://www.itftennis.com/media/15604/atp-2026-rulebook.pdf).

The new `court_geometry` service estimates an image-to-court homography from at least four manually or automatically identified point correspondences and projects image-space foot points into this coordinate system. The baseline-distance utility returns positive values behind the baseline, zero on the line and negative values inside the court. A valid transform still depends on correct, correctly paired court keypoints; automatic keypoint detection and camera-segment handling are not implemented yet.

A court-keypoint detector identifies visible line intersections. A homography maps image-plane coordinates to the court plane. For a camera that pans, zooms or cuts, calibration must be re-estimated or associated with a stable camera segment. Measurements should be marked unavailable when calibration is invalid.

For player positioning, the preferred reference point is the midpoint between the feet / ground-contact estimate, not the bounding-box centre. A person's feet can be occluded; when that happens, retain a confidence flag and avoid presenting a falsely exact distance.

## Processing model

Video ingestion and analysis are separate. Upload creates a `video` record; scheduling creates an `analysis_job`; workers produce versioned observations and events. The API should remain responsive while GPU work runs asynchronously.

Each result must retain:

- source video ID and content fingerprint;
- source timestamp or frame index;
- calibration segment and coordinate transform version;
- detector / tracker / classifier model identifiers;
- confidence and review state;
- derivation version for calculated metrics.

## Data entities (planned)

- `Video`: owner, storage key, content hash, media metadata, retention state.
- `AnalysisJob`: requested pipeline version, state, progress and failure details.
- `CameraSegment`: timestamp range, camera state and calibration reference.
- `Track`: stable player / ball identity within a segment.
- `Observation`: frame-level image and court coordinates, confidence.
- `Event`: serve, contact, bounce, net crossing, point start/end, manual correction.
- `Metric`: value, unit, denominator, filters, provenance and confidence coverage.
- `Report`: charts, court maps and export references.

## API evolution

The initial API is intentionally small. Planned resource shape:

- `POST /api/v1/videos` — upload or register an authorised source.
- `GET /api/v1/videos/{video_id}` — video metadata and ingestion state.
- `POST /api/v1/videos/{video_id}/analyses` — request an analysis configuration.
- `GET /api/v1/analyses/{analysis_id}` — status and progress.
- `GET /api/v1/analyses/{analysis_id}/events` — timestamped event list.
- `GET /api/v1/analyses/{analysis_id}/metrics` — calculated metrics with denominators.
- `GET /api/v1/analyses/{analysis_id}/court-map` — normalised court observations.

These endpoints are planned and are not implemented yet.

## Deployment boundaries

- API: authentication, authorisation, quota and lifecycle management.
- Queue: at-least-once job delivery with idempotent workers.
- GPU workers: isolated, versioned model environments.
- Object storage: private buckets, encryption, retention and signed URLs.
- Database: durable metadata and queryable event/metric records.
- Observability: job timings, dropped-frame rate, detector coverage, calibration quality and error rates.
