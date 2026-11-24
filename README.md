# Tennis Video Intelligence

AI-assisted tennis match analysis from video. The long-term goal is to turn a match recording into video-linked, court-calibrated visual analytics: player positioning, serve and return patterns, shot placement, rally structure, movement heatmaps and tactical reports.

> **Project status: research / working prototype.** The repository contains a FastAPI video-ingestion and analysis API, an Android Jetpack Compose client, manual court calibration, heuristic court suggestions, generic person/ball candidate detection, position heatmaps, manual event review and SVG report export. The current CV detectors are not trained or validated for tennis and must not be treated as official match statistics without human review.

## Product direction

The app will support two input paths:

1. Upload a video file from the Android app.
2. Import a link only when the source is authorised and technically accessible. A URL is not a promise that the app can download arbitrary YouTube, broadcast or subscription-streaming content.

The analysis pipeline is designed around the following stages:

1. **Ingest** — validate, store and fingerprint the source video.
2. **Inspect** — probe codec, duration, frame rate, resolution and audio/video streams.
3. **Calibrate** — use a manual four-corner selection or inspect a heuristic green-court suggestion before mapping image coordinates to court coordinates.
4. **Detect candidates** — run generic OpenCV person proposals and colour-based tennis-ball candidates on sampled frames; these are unverified and are not tennis-trained multi-object tracks.
5. **Review events** — inspect low-confidence trajectory-change candidates, accept/reject/relabel them, or add a manually confirmed event with a timestamp.
6. **Calculate** — derive event counts and positioning summaries; serve/shot/rally/tactical event counts only use human-accepted labels.
7. **Visualise** — inspect player-position heatmaps and export an SVG positioning report.

## Current prototype workflow

- Upload and inspect a tennis video.
- Preview any timestamped frame.
- Manually calibrate the four outer court corners, or review a heuristic green-region corner suggestion.
- Project a selected foot / ground-contact point into court metres.
- Start a sampled CV analysis and review near/far position candidates and heatmaps.
- Review automatic event candidates or add manually confirmed event labels.
- Export the positioning report as SVG.

The prototype does not yet provide reliable player identity tracking, validated ball tracking, automatic serve/shot recognition, or production-quality match statistics. The generic HOG / colour-blob detectors are intentionally labelled as candidates.

## Repository layout

```text
backend/       FastAPI ingestion API and analysis service boundaries
android/       Kotlin + Jetpack Compose client prototype
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

### Container deployment

A backend image definition is provided at `backend/Dockerfile`. Build and run it with a persistent volume and a strong token:

```bash
docker build -t tennis-video-intelligence-api ./backend
export TVI_API_TOKEN="$(openssl rand -hex 32)"
printf 'Save this token securely and enter it in the Android app: %s\n' "$TVI_API_TOKEN"
docker run --name tvi-api --restart unless-stopped \
  -p 8000:8000 \
  -e TVI_API_TOKEN="$TVI_API_TOKEN" \
  -v tvi-video-data:/data \
  tennis-video-intelligence-api
```

Put a TLS-terminating reverse proxy or managed HTTPS ingress in front of the container and point the Android release app at that HTTPS hostname. The volume must have at least 5 GB plus operational headroom. This container runs one API worker for the local resumable-upload store; it is a single-instance deployment starter, not a horizontally scalable production platform.

### Upload a test video

```bash
curl -X POST "http://127.0.0.1:8000/api/v1/videos" \\
  -H "accept: application/json" \\
  -F "file=@/path/to/your-match.mp4"
```

The API validates uploads, supports resumable 16 MiB chunks up to 5 GB per file, inspects media with ffprobe, extracts timestamped frames, stores manual court calibrations, suggests green-region court corners for human confirmation, and starts a local background CV analysis job. Use `POST /api/v1/videos/{video_id}/analyses` to start the job, `GET /api/v1/analyses/{analysis_id}` for status and positioning, `GET /api/v1/analyses/{analysis_id}/events` to review candidates, and the `/events` POST routes to accept/reject/relabel or add human-confirmed events. `GET /api/v1/analyses/{analysis_id}/report.svg` exports an SVG. These endpoints are documented at `/docs`. The local storage adapter uses a persistent disk directory and in-process background tasks; a durable queue, multi-user isolation, shared object storage, per-user quotas and production-scale worker orchestration are not yet implemented.

## Android prototype

See [the Android setup and usage guide](android/README.md). The app includes resumable 5 GB video uploads, Android-managed background continuation and retry, hosted HTTPS backend configuration at build time, Liquid Glass-inspired Android surfaces, court calibration, sampled candidate analysis, event review, playback, and SVG/PNG/PDF reports. Debug and release builds are checked by GitHub Actions.

## Release signing

The Android release workflow produces a signed APK only when all four repository Actions secrets are configured: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. Without them it deliberately publishes only an unsigned release artifact, not a production GitHub Release. See [Android release setup](android/README.md#release-signing-android).

## Configuration

Environment variables use the `TVI_` prefix:

| Variable | Default | Purpose |
|---|---|---|
| `TVI_ENVIRONMENT` | `development` | Runtime environment |
| `TVI_STORAGE_DIR` | `./data/videos` | Local uploaded-video directory |
| `TVI_MAX_UPLOAD_MB` | `4768` | Maximum upload size in MiB (~5 GB per file) |
| `TVI_ALLOWED_ORIGINS` | `http://localhost:8080` | CORS origins, comma-separated |
| `TVI_API_TOKEN` | empty | Optional shared bearer token; required when `TVI_ENVIRONMENT=production` |

Do not expose the development server directly to the public internet. Production deployments still need individual user identity and access isolation, quotas, durable job processing, private object storage, malware/content checks and retention controls. The optional bearer token is a shared service credential, not multi-user authentication. Do not expose the local-file backend publicly without HTTPS and additional operational hardening.

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


## Android background uploads

The Android client schedules user-initiated data-transfer jobs on Android 14+ and a WorkManager foreground worker on older supported versions. It stores the persistable document URI grant and resumes chunked upload from the server's last confirmed offset after an interruption. The upload can continue when the activity closes or is swiped out of Recents; Android force-stop / Task Manager Stop, uninstall, or loss of the document permission requires the user to reopen the app and resume.

For a release APK that connects automatically, configure the GitHub Actions repository variable `TVI_API_BASE_URL` and secret `TVI_API_TOKEN`. The release workflow fails rather than publishing an APK with an empty production connection configuration.
