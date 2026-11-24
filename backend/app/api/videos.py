import json
from datetime import datetime
from pathlib import Path
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, File, HTTPException, UploadFile, status

from app.core.config import get_settings
from app.schemas.video import (
    MediaInspectionResponse,
    VideoStatusResponse,
    VideoUploadResponse,
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
