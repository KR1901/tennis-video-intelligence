# Backend

FastAPI API for local video ingestion, media inspection, manual / heuristic court calibration, analysis jobs, event review and SVG report export.

## Requirements

- Python 3.11+
- FFmpeg and ffprobe on PATH (media inspection and frame extraction)
- OpenCV headless, NumPy and the Python dependencies in `pyproject.toml`

## Current endpoints

- `GET /health` — service health.
- `POST /api/v1/videos` — legacy multipart upload for small development files.
- `POST /api/v1/videos/uploads` — create a resumable upload session with a declared filename, MIME type and total size.
- `HEAD /api/v1/videos/uploads/{upload_id}` — read the durable server-committed upload offset.
- `PATCH /api/v1/videos/uploads/{upload_id}` — append one raw binary chunk using the `Upload-Offset` header (16 MiB maximum per request).
- `POST /api/v1/videos/uploads/{upload_id}/complete` — atomically finalize the assembled video.
- `DELETE /api/v1/videos/uploads/{upload_id}` — discard an incomplete upload session.
- `GET /api/v1/videos/{video_id}` — read upload / inspection metadata.
- `POST /api/v1/videos/{video_id}/inspect` — inspect streams with ffprobe.
- `GET /api/v1/videos/{video_id}/frame?timestamp_seconds=...` — extract one JPEG frame.
- `POST /api/v1/videos/{video_id}/calibrations` — save manual four-corner homography.
- `POST /api/v1/videos/{video_id}/calibrations/{calibration_id}/project` — project an image point into court-plane metres.
- `POST /api/v1/videos/{video_id}/court-detection` — suggest green-region court corners from a frame; the user must confirm orientation and keypoints.
- `POST /api/v1/videos/{video_id}/analyses` — start a local background analysis job.
- `GET /api/v1/analyses/{analysis_id}` — inspect job state, positioning candidates and reviewed metrics.
- `GET /api/v1/analyses/{analysis_id}/events` — list unreviewed candidates and reviewed labels.
- `POST /api/v1/analyses/{analysis_id}/events/{event_id}/review` — accept / reject / relabel a candidate.
- `POST /api/v1/analyses/{analysis_id}/events` — add a manually confirmed event.
- `GET /api/v1/analyses/{analysis_id}/report.svg` — export a court-positioning SVG report.

## How the analysis works today

The initial worker samples the video, uses a generic OpenCV HOG pedestrian detector to propose people, approximates their ground points from bounding-box bottoms, and uses a manual calibration or a heuristic green-region court quadrilateral to project points into court coordinates. A simple HSV blob filter proposes possible tennis-ball locations and very low-confidence trajectory direction changes.

These are development candidates, not a validated tennis vision model. They can fail on camera cuts, zooms, dark / light court surfaces, occlusions, audience members and similar-coloured objects. Automatic court suggestions require visual confirmation. Without a verified calibration, the position metrics remain unavailable.

Serve placement, shot placement, rally contacts, serve +1 / return +1 and winners / errors are only counted from events a person explicitly confirms or enters. The system does not silently convert computer-vision proposals into official match events.

## Local run

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
uvicorn app.main:app --reload
```

Run tests and style checks:

```bash
pytest
ruff check .
ruff format --check .
```

## Important deployment limitation

Video files and analysis JSON currently live in a local directory. Analysis jobs use FastAPI in-process background tasks. There is no database, durable queue, authentication, multi-user isolation, cloud object store, retention scheduler or signed playback. Do not expose this development service to the public internet or use it to process confidential footage until those controls are deployed.


## Additional analysis and report endpoints

- `GET /api/v1/videos/{video_id}/stream` — stream the original file with HTTP byte-range support for native playback and seeking.
- `DELETE /api/v1/videos/{video_id}` — delete the source video, metadata and related local analysis JSON.
- `GET /api/v1/analyses/{analysis_id}/report.png` and `/report.pdf` — export a raster positioning / reviewed-event report.
- `DELETE /api/v1/analyses/{analysis_id}` — delete a saved analysis JSON file.

The reviewed event metric engine reports first / second serve labels and outcomes, coarse serve zones, short / mid-court / deep shot placement, explicit shot-direction labels, paired point sequences and player-side event aggregates. These figures are counted only from human-accepted labels. The serve-zone and depth partitions are descriptive bins, not official line calls or trained classifiers.

## Optional bearer token

Set `TVI_API_TOKEN` to enable shared bearer-token protection. Set `TVI_ENVIRONMENT=production` to require a non-empty token at startup. Send `Authorization: Bearer <token>` on protected routes. This is a shared service token, not user accounts or multi-tenant access isolation. Deploy behind HTTPS.


## Large-video ingestion

The default maximum upload size is 10 GiB (`TVI_MAX_UPLOAD_MB=10240`). The Android client sends 16 MiB chunks rather than one multi-gigabyte request. The backend streams each request body to a temporary file, fsyncs each accepted chunk before advancing the committed offset, rejects stale offsets, and truncates incomplete tails after a disconnected request. Upload sessions expire after seven days. This limits memory use to a small request buffer instead of buffering the entire match.

For production, `TVI_STORAGE_DIR` must point to a persistent volume with at least the upload size plus working headroom; the volume must survive container restarts. Configure reverse proxies and ingress limits for individual 16 MiB PATCH requests (not 10 GiB single requests), and set idle/request timeouts high enough for one chunk on the slowest supported network. Keep the API behind HTTPS and configure `TVI_API_TOKEN`. The 10 GiB setting is a per-file ceiling, not a storage quota or a guarantee that the host has enough free disk. The current local storage adapter is intended for a single backend instance; production multi-instance deployments should move upload parts to shared object storage and use a distributed session/lock store.

The analysis worker defaults to two sampled frames per second and downsizes frames to a maximum width of 960 pixels before the generic CV detectors. This reduces work compared with the earlier three-frame-per-second default, but it is still a CPU-based prototype. Large-video production analysis still needs a durable job queue, worker concurrency controls, cancellation, job persistence and measured hardware-specific tuning; the current FastAPI in-process background task is not a high-availability job system.
