import json
from datetime import datetime
from pathlib import Path
from typing import Annotated
from uuid import UUID, uuid4

import numpy as np
from fastapi import APIRouter, File, HTTPException, Query, Response, UploadFile, status

from app.core.config import get_settings
from app.schemas.calibration import (
    CourtCalibrationRequest,
    CourtCalibrationResponse,
    CourtProjectionRequest,
    CourtProjectionResponse,
)
from app.schemas.video import (
    MediaInspectionResponse,
    VideoStatusResponse,
    VideoUploadResponse,
)
from app.services.court_geometry import (
    CourtDimensions,
    estimate_homography,
    project_point,
    signed_baseline_distance_m,
)
from app.services.frame_extract import (
    FFmpegUnavailableError,
    FrameExtractionError,
    FrameExtractionTimeoutError,
    extract_video_frame,
)
from app.services.media_probe import (
    FFprobeUnavailableError,
    InvalidMediaError,
    MediaProbeTimeoutError,
    probe_video_file,
)
from app.services.video_storage import (
    LocalVideoStorage,
    VideoTooLargeError,
    VideoValidationError,
)

router = APIRouter(prefix="/videos", tags=["videos"])


def _load_video_metadata(video_id: str) -> tuple[Path, Path, dict[str, object]]:
    try:
        UUID(video_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail="Video not found.") from exc

    root = Path(get_settings().storage_dir)
    metadata_path = root / f"{video_id}.json"
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=404, detail="Video not found.") from exc

    filename = Path(str(metadata.get("filename", ""))).name
    suffix = Path(filename).suffix.lower()
    if suffix not in {".mp4", ".mov", ".mkv", ".webm", ".avi"}:
        raise HTTPException(status_code=404, detail="Video not found.")

    video_path = root / f"{video_id}{suffix}"
    if not video_path.is_file():
        raise HTTPException(status_code=404, detail="Video file not found.")

    return video_path, metadata_path, metadata


@router.post(
    "",
    response_model=VideoUploadResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Upload a tennis match video",
)
async def upload_video(file: Annotated[UploadFile, File()]) -> VideoUploadResponse:
    settings = get_settings()
    storage = LocalVideoStorage(
        root=Path(settings.storage_dir),
        max_upload_bytes=settings.max_upload_mb * 1024 * 1024,
    )

    try:
        metadata = await storage.save(file)
    except VideoTooLargeError as exc:
        raise HTTPException(status_code=413, detail=str(exc)) from exc
    except VideoValidationError as exc:
        raise HTTPException(status_code=415, detail=str(exc)) from exc
    except OSError as exc:
        raise HTTPException(status_code=500, detail="Could not store the uploaded video.") from exc

    return VideoUploadResponse(
        video_id=str(metadata["video_id"]),
        filename=str(metadata["filename"]),
        size_bytes=int(metadata["size_bytes"]),
        content_type=str(metadata["content_type"]),
        status="uploaded",
        created_at=datetime.fromisoformat(str(metadata["created_at"])),
    )


@router.get(
    "/{video_id}",
    response_model=VideoStatusResponse,
    summary="Get video ingestion and inspection status",
)
async def get_video(video_id: str) -> VideoStatusResponse:
    _, _, metadata = _load_video_metadata(video_id)
    return VideoStatusResponse(
        video_id=str(metadata["video_id"]),
        filename=str(metadata["filename"]),
        size_bytes=int(metadata["size_bytes"]),
        content_type=str(metadata["content_type"]),
        status=str(metadata.get("status", "uploaded")),
        created_at=datetime.fromisoformat(str(metadata["created_at"])),
        media_info=metadata.get("media_info"),
    )


@router.post(
    "/{video_id}/inspect",
    response_model=MediaInspectionResponse,
    summary="Inspect video metadata with ffprobe",
)
async def inspect_video(video_id: str) -> MediaInspectionResponse:
    video_path, metadata_path, metadata = _load_video_metadata(video_id)

    try:
        media_info = probe_video_file(video_path)
    except FFprobeUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except MediaProbeTimeoutError as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except InvalidMediaError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except OSError as exc:
        raise HTTPException(status_code=500, detail="Could not inspect the video file.") from exc

    metadata["media_info"] = media_info
    metadata["status"] = "inspected"
    temporary_path = metadata_path.with_name(f".{video_id}.json.tmp")
    temporary_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    temporary_path.replace(metadata_path)

    return MediaInspectionResponse(**media_info)


@router.get(
    "/{video_id}/frame",
    summary="Extract a video frame for preview and manual calibration",
)
async def get_video_frame(
    video_id: str,
    timestamp_seconds: float = Query(ge=0),
) -> Response:
    video_path, _, metadata = _load_video_metadata(video_id)
    media_info = metadata.get("media_info")
    if isinstance(media_info, dict):
        duration = media_info.get("duration_seconds")
        if duration is not None and timestamp_seconds > float(duration):
            raise HTTPException(status_code=422, detail="Timestamp is beyond the video duration.")

    try:
        frame = extract_video_frame(video_path, timestamp_seconds)
    except FFmpegUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except FrameExtractionTimeoutError as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except FrameExtractionError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    return Response(
        content=frame,
        media_type="image/jpeg",
        headers={"Cache-Control": "no-store"},
    )


@router.post(
    "/{video_id}/calibrations",
    response_model=CourtCalibrationResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a manual court-plane calibration",
)
async def create_court_calibration(
    video_id: str,
    request: CourtCalibrationRequest,
) -> CourtCalibrationResponse:
    _, metadata_path, metadata = _load_video_metadata(video_id)
    media_info = metadata.get("media_info")
    if isinstance(media_info, dict):
        duration = media_info.get("duration_seconds")
        if duration is not None and request.frame_timestamp_seconds > float(duration):
            raise HTTPException(
                status_code=422,
                detail="Calibration timestamp is beyond the video duration.",
            )

    court = CourtDimensions()
    court_points = court.boundary_corners(request.court_area)
    try:
        homography = estimate_homography(request.image_points, court_points)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    calibration_id = str(uuid4())
    calibration = {
        "calibration_id": calibration_id,
        "frame_timestamp_seconds": request.frame_timestamp_seconds,
        "court_area": request.court_area,
        "image_points": [list(point) for point in request.image_points],
        "court_points": [list(point) for point in court_points],
        "homography_image_to_court": homography.tolist(),
        "coordinate_convention": "x across court; y from net toward near baseline; metres",
        "status": "manual_calibration",
    }
    calibrations = metadata.setdefault("calibrations", [])
    if not isinstance(calibrations, list):
        calibrations = []
        metadata["calibrations"] = calibrations
    calibrations.append(calibration)
    temporary_path = metadata_path.with_name(f".{video_id}.json.tmp")
    temporary_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    temporary_path.replace(metadata_path)

    return CourtCalibrationResponse(**calibration)


@router.post(
    "/{video_id}/calibrations/{calibration_id}/project",
    response_model=CourtProjectionResponse,
    summary="Project a manually selected image point onto the calibrated court",
)
async def project_court_point(
    video_id: str,
    calibration_id: str,
    request: CourtProjectionRequest,
) -> CourtProjectionResponse:
    _, _, metadata = _load_video_metadata(video_id)
    try:
        UUID(calibration_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail="Calibration not found.") from exc

    calibrations = metadata.get("calibrations", [])
    calibration = (
        next(
            (
                item
                for item in calibrations
                if isinstance(item, dict) and item.get("calibration_id") == calibration_id
            ),
            None,
        )
        if isinstance(calibrations, list)
        else None
    )
    if calibration is None:
        raise HTTPException(status_code=404, detail="Calibration not found.")

    try:
        homography = np.asarray(calibration["homography_image_to_court"], dtype=float)
        court_point = project_point(request.image_point, homography)
        baseline_distance = signed_baseline_distance_m(court_point, request.player_end)
    except (KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=422, detail="Stored calibration is invalid.") from exc

    return CourtProjectionResponse(
        image_point=request.image_point,
        court_x_m=court_point[0],
        court_y_m=court_point[1],
        signed_baseline_distance_m=baseline_distance,
    )
