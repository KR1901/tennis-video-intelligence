import json
import os
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from fastapi import UploadFile

ALLOWED_EXTENSIONS = {".mp4", ".mov", ".mkv", ".webm", ".avi"}
CHUNK_SIZE = 1024 * 1024


class VideoValidationError(ValueError):
    """Raised when an uploaded file is not an accepted video upload."""


class VideoTooLargeError(ValueError):
    """Raised when an upload exceeds the configured byte limit."""


class LocalVideoStorage:
    """Development-only local storage adapter for uploaded video files."""

    def __init__(self, root: Path, max_upload_bytes: int) -> None:
        self.root = root
        self.max_upload_bytes = max_upload_bytes

    async def save(self, upload: UploadFile) -> dict[str, object]:
        original_name = Path(upload.filename or "").name
        suffix = Path(original_name).suffix.lower()
        if suffix not in ALLOWED_EXTENSIONS:
            raise VideoValidationError("Unsupported video extension.")

        content_type = upload.content_type or "application/octet-stream"
        if not content_type.startswith("video/") and content_type != "application/octet-stream":
            raise VideoValidationError("The uploaded file must have a video content type.")

        self.root.mkdir(parents=True, exist_ok=True)
        video_id = str(uuid4())
        final_path = self.root / f"{video_id}{suffix}"
        temporary_path = self.root / f".{video_id}.upload"
        total_bytes = 0

        try:
            with temporary_path.open("xb") as output:
                while chunk := await upload.read(CHUNK_SIZE):
                    total_bytes += len(chunk)
                    if total_bytes > self.max_upload_bytes:
                        raise VideoTooLargeError("Upload exceeds the configured size limit.")
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())

            if total_bytes == 0:
                raise VideoValidationError("The uploaded video is empty.")

            temporary_path.replace(final_path)
            created_at = datetime.now(UTC)
            metadata = {
                "video_id": video_id,
                "filename": original_name,
                "size_bytes": total_bytes,
                "content_type": content_type,
                "status": "uploaded",
                "created_at": created_at.isoformat(),
            }
            metadata_path = self.root / f"{video_id}.json"
            metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
            return metadata
        except Exception:
            temporary_path.unlink(missing_ok=True)
            final_path.unlink(missing_ok=True)
            raise
        finally:
            await upload.close()
