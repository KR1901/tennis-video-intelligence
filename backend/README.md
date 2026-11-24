# Backend

FastAPI service for video ingestion, media inspection, frame preview and manual court calibration. Automatic court detection, player / ball tracking and tennis-event recognition are not implemented yet.

## Current endpoints

- `GET /health` — service health.
- `POST /api/v1/videos` — validate and store one video locally; returns a generated video ID.
- `GET /api/v1/videos/{video_id}` — return upload / inspection status and persisted metadata.
- `POST /api/v1/videos/{video_id}/inspect` — run `ffprobe` and persist duration, frame rate, resolution, codec, rotation and other available stream metadata.
- `GET /api/v1/videos/{video_id}/frame?timestamp_seconds=...` — return one JPEG frame extracted with `ffmpeg`, for preview and manual calibration.
- `POST /api/v1/videos/{video_id}/calibrations` — create and persist a manual court calibration from four image points.
- `POST /api/v1/videos/{video_id}/calibrations/{calibration_id}/project` — project a selected image point into court-plane metres and calculate its signed distance from the near or far baseline.

## Manual calibration point order

Select the four outer corners in this exact order:

1. Far-left doubles (or singles) court corner.
2. Far-right court corner.
3. Near-right court corner.
4. Near-left court corner.

Use the same area consistently for the intended measurement. The API maps taps to the regulation court rectangle using a planar homography. It currently trusts the selected points; the response does not certify that the points are correct or that the camera view is stable.

For player positioning, send the image point corresponding to the player's feet / ground contact, not the centre of their torso bounding box. The near baseline is at y = +11.885 m and the far baseline at y = -11.885 m. A signed baseline distance is positive behind the baseline and negative inside the court.

## Local requirements

Install FFmpeg so that both `ffprobe` and `ffmpeg` are available on `PATH`.

- `503` when the required executable is unavailable;
- `504` when media inspection / frame extraction times out;
- `422` when the media is invalid, a timestamp is out of range, or calibration points cannot define a stable transform;
- `404` when the video or calibration ID does not exist.

Storage is currently local and intended for development. Authentication, database persistence, background jobs and private object storage remain future work.
