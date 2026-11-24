# Backend

FastAPI service for video ingestion and media inspection. Computer-vision tracking and tennis-event recognition are not implemented yet.

## Current endpoints

- `GET /health` — service health.
- `POST /api/v1/videos` — validate and store one video locally; returns a generated video ID.
- `GET /api/v1/videos/{video_id}` — return upload / inspection status and persisted metadata.
- `POST /api/v1/videos/{video_id}/inspect` — run `ffprobe` and persist duration, frame rate, resolution, codec, rotation and other available stream metadata.

## Local requirements

Install FFmpeg so that `ffprobe` is available on `PATH`. The inspection endpoint returns:

- `503` when ffprobe is unavailable;
- `504` when probing times out;
- `422` when the uploaded file is not a readable video container or has no video stream;
- `404` when the video ID or stored file does not exist.

Storage is currently local and intended for development. Authentication, database persistence, background jobs and private object storage remain future work.
