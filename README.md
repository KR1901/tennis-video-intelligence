# Tennis Video Intelligence

AI-assisted tennis match analysis from video. The long-term goal is to turn a match recording into video-linked, court-calibrated visual analytics: player positioning, serve and return patterns, shot placement, rally structure, movement heatmaps and tactical reports.

> **Project status: foundation / MVP scaffolding.** This repository currently contains a working video-ingestion API and project documentation. It does **not** yet detect players or balls, classify tennis events, or calculate match statistics. Those capabilities will be added behind explicit, testable analysis stages.

## Product direction

The app will support two input paths:

1. Upload a video file from the Android app.
2. Import a link only when the source is authorised and technically accessible. A URL is not a promise that the app can download arbitrary YouTube, broadcast or subscription-streaming content.

The analysis pipeline is designed around the following stages:

1. **Ingest** — validate, store and fingerprint the source video.
2. **Inspect** — probe codec, duration, frame rate, resolution and audio/video streams.
3. **Calibrate** — detect court keypoints and map image coordinates to real court coordinates.
4. **Track** — detect both players and the ball across frames, maintaining identities through camera changes where possible.
5. **Recognise events** — detect serves, contacts, bounces, rally boundaries and point endings.
6. **Calculate** — derive statistics from verified events and calibrated coordinates.
7. **Visualise** — generate court maps, timelines, heatmaps, comparisons and exportable infographics.

## First MVP target

The first useful end-to-end milestone is deliberately narrow:

- Upload a match video.
- Probe and inspect the video.
- Detect and calibrate the court on a stable camera segment.
- Track both players.
- Project player foot positions onto a real-world court map.
- Report player position in metres relative to the baseline, with confidence and video timestamps.
- Replay the source frames behind each reported measurement.

Ball tracking and shot/event recognition follow after player tracking and court calibration can be measured reliably.

## Repository layout

```text
backend/       FastAPI ingestion API and analysis service boundaries
android/       Android client direction and API integration notes
docs/          Architecture, metric definitions, reliability and delivery roadmap
.github/       CI workflow and issue templates
```

## Run the backend locally

Requirements: Python 3.11 or newer.

```bash
cd backend
python -m venv .venv

# macOS / Linux
source .venv/bin/activate

# Windows PowerShell
# .venv\\Scripts\\Activate.ps1

python -m pip install --upgrade pip
pip install -e ".[dev]"
uvicorn app.main:app --reload
```

Open `http://127.0.0.1:8000/docs` for the API documentation and `http://127.0.0.1:8000/health` for the health check.

### Upload a test video

```bash
curl -X POST "http://127.0.0.1:8000/api/v1/videos" \\
  -H "accept: application/json" \\
  -F "file=@/path/to/your-match.mp4"
```

The API checks the filename extension, declared content type and configured size cap, stores the file in the configured local storage directory and returns a video ID. It does not yet inspect the media container or prove that the bytes form a decodable video; that check belongs in the upcoming FFmpeg/ffprobe media-inspection stage. It does not claim that analysis has run. The current storage adapter is for local development; production storage, database persistence, background jobs and access controls are planned separately.

## Configuration

Environment variables use the `TVI_` prefix:

| Variable | Default | Purpose |
|---|---|---|
| `TVI_ENVIRONMENT` | `development` | Runtime environment |
| `TVI_STORAGE_DIR` | `./data/videos` | Local uploaded-video directory |
| `TVI_MAX_UPLOAD_MB` | `2048` | Maximum upload size |
| `TVI_ALLOWED_ORIGINS` | `http://localhost:8080` | CORS origins, comma-separated |

Do not expose the development server directly to the public internet. Production deployments need authentication, per-user quotas, malware/content checks, private object storage, retention controls and signed download URLs.

## Design principles

- **Video-backed evidence:** each event and metric should link to the frame range that supports it.
- **No fabricated precision:** missing or low-confidence detections must be shown as missing or uncertain, not silently estimated.
- **Calibration before metres:** distances in metres require valid court geometry and a known camera transform.
- **Separate observation from inference:** detected coordinates, classified events and derived statistics are distinct data layers.
- **Human correction:** users must be able to review and fix important event labels and tracking failures.
- **Reproducible analysis:** record model versions, processing settings, input fingerprints and analysis versions.
- **Privacy by default:** uploaded match footage is user content; avoid public sharing and retain it only according to an explicit policy.

## Development status

See [the roadmap](docs/roadmap.md), [architecture](docs/architecture.md) and [metric definitions](docs/metrics-and-reliability.md). The initial API tests run with:

```bash
cd backend
pytest
ruff check .
ruff format --check .
```

## Licensing

The original code in this repository is licensed under Apache-2.0. This does not automatically grant rights to third-party video footage, datasets, pretrained model weights, data feeds or external services. Each adopted dependency, model and dataset must be reviewed under its own licence and terms before distribution or commercial use.
