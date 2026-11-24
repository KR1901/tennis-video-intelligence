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
