from datetime import datetime
from pathlib import Path

from fastapi import APIRouter, File, HTTPException, UploadFile, status

from app.core.config import get_settings
from app.schemas.video import VideoUploadResponse
from app.services.video_storage import (
    LocalVideoStorage,
    VideoTooLargeError,
    VideoValidationError,
)

router = APIRouter(prefix="/videos", tags=["videos"])


@router.post(
    "",
    response_model=VideoUploadResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Upload a tennis match video",
)
async def upload_video(file: UploadFile = File(...)) -> VideoUploadResponse:
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
