# Backend

FastAPI API for local video ingestion, media inspection, manual / heuristic court calibration, analysis jobs, event review and SVG report export.

## Requirements

- Python 3.11+
- FFmpeg and ffprobe on PATH (media inspection and frame extraction)
- OpenCV headless, NumPy and the Python dependencies in `pyproject.toml`

## Current endpoints

- `GET /health` — service health.
- `POST /api/v1/videos` — upload a video to local development storage.
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
