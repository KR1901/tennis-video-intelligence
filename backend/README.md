# Backend

FastAPI service for video ingestion and, in later milestones, asynchronous computer-vision analysis.

## Current endpoints

- `GET /health` — service health.
- `POST /api/v1/videos` — validate and store one video locally; returns a generated video ID.

Video analysis is not implemented yet. The next backend milestone is media probing with `ffprobe`, durable metadata storage, and a queued analysis job.
