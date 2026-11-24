from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict


class VideoUploadResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    video_id: str
    filename: str
    size_bytes: int
    content_type: str
    status: Literal["uploaded"] = "uploaded"
    created_at: datetime
    message: str = "Video stored. Analysis has not started."


class MediaInspectionResponse(BaseModel):
    status: Literal["inspected"] = "inspected"
    duration_seconds: float | None = None
    width: int | None = None
    height: int | None = None
    frame_rate: float | None = None
    frame_count: int | None = None
    video_codec: str | None = None
    container_format: str | None = None
    rotation_degrees: int = 0
    bit_rate: int | None = None
    file_size_bytes: int


class VideoStatusResponse(BaseModel):
    video_id: str
    filename: str
    size_bytes: int
    content_type: str
    status: Literal["uploaded", "inspected"]
    created_at: datetime
    media_info: MediaInspectionResponse | None = None


class ResumableUploadCreateRequest(BaseModel):
    filename: str
    content_type: str = "application/octet-stream"
    size_bytes: int


class ResumableUploadSessionResponse(BaseModel):
    upload_id: str
    upload_offset: int
    upload_length: int
    chunk_size_bytes: int
    expires_in_seconds: int = 604800
