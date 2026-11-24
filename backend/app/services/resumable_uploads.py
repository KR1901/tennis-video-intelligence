from __future__ import annotations

import asyncio
import json
import os
import shutil
import threading
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, AsyncIterator
from uuid import UUID, uuid4

ALLOWED_EXTENSIONS = {".mp4", ".mov", ".mkv", ".webm", ".avi"}
CHUNK_BYTES = 16 * 1024 * 1024
SESSION_TTL = timedelta(days=7)
_SESSION_LOCKS: dict[str, threading.Lock] = {}
_SESSION_LOCKS_GUARD = threading.Lock()


class UploadError(ValueError):
    """Base class for resumable-upload validation errors."""


class UploadNotFound(UploadError):
    """The upload session does not exist or has expired."""


class UploadOffsetMismatch(UploadError):
    def __init__(self, expected_offset: int) -> None:
        super().__init__(f"Upload offset mismatch; server expects byte {expected_offset}.")
        self.expected_offset = expected_offset


class UploadTooLarge(UploadError):
    """The requested upload exceeds the configured limit."""


class UploadValidationError(UploadError):
    """The upload metadata or chunk is invalid."""


class UploadBusy(UploadError):
    """Another chunk is already being appended to this session."""


def _atomic_json_write(path: Path, payload: dict[str, Any]) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    temporary.replace(path)


def _session_lock(upload_id: str) -> threading.Lock:
    with _SESSION_LOCKS_GUARD:
        return _SESSION_LOCKS.setdefault(upload_id, threading.Lock())


class ResumableVideoStorage:
    """Disk-backed resumable upload sessions; request bodies are never buffered in memory."""

    def __init__(self, root: Path, max_upload_bytes: int) -> None:
        self.root = root
        self.sessions = root / ".uploads"
        self.max_upload_bytes = max_upload_bytes

    def create(self, filename: str, content_type: str, size_bytes: int) -> dict[str, Any]:
        original_name = Path(filename or "").name
        suffix = Path(original_name).suffix.lower()
        if suffix not in ALLOWED_EXTENSIONS:
            raise UploadValidationError("Unsupported video extension.")
        if not content_type.startswith("video/") and content_type != "application/octet-stream":
            raise UploadValidationError("The uploaded file must have a video content type.")
        if size_bytes <= 0:
            raise UploadValidationError("The uploaded video must not be empty.")
        if size_bytes > self.max_upload_bytes:
            raise UploadTooLarge("Upload exceeds the configured maximum file size.")

        self.sessions.mkdir(parents=True, exist_ok=True)
        self.root.mkdir(parents=True, exist_ok=True)
        if shutil.disk_usage(self.root).free < size_bytes:
            raise UploadValidationError("Not enough free storage is available for this upload.")

        upload_id = str(uuid4())
        session = {
            "upload_id": upload_id,
            "filename": original_name,
            "suffix": suffix,
            "content_type": content_type,
            "size_bytes": size_bytes,
            "upload_offset": 0,
            "created_at": datetime.now(UTC).isoformat(),
        }
        _atomic_json_write(self.sessions / f"{upload_id}.json", session)
        (self.sessions / f"{upload_id}.part").touch(exist_ok=False)
        return session

    def get(self, upload_id: str) -> dict[str, Any]:
        try:
            UUID(upload_id)
        except ValueError as exc:
            raise UploadNotFound("Upload session not found.") from exc
        session_path = self.sessions / f"{upload_id}.json"
        try:
            session = json.loads(session_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise UploadNotFound("Upload session not found.") from exc
        try:
            created_at = datetime.fromisoformat(str(session["created_at"]))
        except (KeyError, ValueError) as exc:
            raise UploadNotFound("Upload session metadata is invalid.") from exc
        if datetime.now(UTC) - created_at > SESSION_TTL:
            self.abort(upload_id)
            raise UploadNotFound("Upload session expired; start the upload again.")
        part_path = self.sessions / f"{upload_id}.part"
        if not part_path.is_file():
            raise UploadNotFound("Upload data is missing; start the upload again.")
        return session

    async def append(
        self,
        upload_id: str,
        expected_offset: int,
        chunks: AsyncIterator[bytes],
    ) -> dict[str, Any]:
        lock = _session_lock(upload_id)
        if not lock.acquire(blocking=False):
            raise UploadBusy("A chunk for this upload is already being received.")
        try:
            session = self.get(upload_id)
            current_offset = int(session["upload_offset"])
            if expected_offset != current_offset:
                raise UploadOffsetMismatch(current_offset)

            total_size = int(session["size_bytes"])
            part_path = self.sessions / f"{upload_id}.part"
            bytes_written = 0
            try:
                with part_path.open("r+b") as output:
                    # Discard any uncommitted tail left behind by a disconnected request.
                    output.truncate(current_offset)
                    output.seek(current_offset)
                    async for block in chunks:
                        if not block:
                            continue
                        bytes_written += len(block)
                        if bytes_written > CHUNK_BYTES:
                            raise UploadTooLarge(
                                f"Each upload chunk must be at most {CHUNK_BYTES // (1024 * 1024)} MB."
                            )
                        if current_offset + bytes_written > total_size:
                            raise UploadTooLarge("Chunk would exceed the declared upload length.")
                        output.write(block)
                    if bytes_written == 0:
                        raise UploadValidationError("Empty upload chunks are not accepted.")
                    output.flush()
                    os.fsync(output.fileno())
            except BaseException:
                with part_path.open("r+b") as output:
                    output.truncate(current_offset)
                    output.flush()
                    os.fsync(output.fileno())
                raise

            session["upload_offset"] = current_offset + bytes_written
            _atomic_json_write(self.sessions / f"{upload_id}.json", session)
            return session
        finally:
            lock.release()

    def complete(self, upload_id: str) -> dict[str, Any]:
        session = self.get(upload_id)
        expected_size = int(session["size_bytes"])
        if int(session["upload_offset"]) != expected_size:
            raise UploadOffsetMismatch(int(session["upload_offset"]))

        part_path = self.sessions / f"{upload_id}.part"
        if part_path.stat().st_size != expected_size:
            raise UploadValidationError("Uploaded bytes do not match the declared file size.")

        video_id = upload_id
        filename = str(session["filename"])
        final_path = self.root / f"{video_id}{session['suffix']}"
        part_path.replace(final_path)
        created_at = datetime.now(UTC)
        metadata = {
            "video_id": video_id,
            "filename": filename,
            "size_bytes": expected_size,
            "content_type": str(session["content_type"]),
            "status": "uploaded",
            "created_at": created_at.isoformat(),
        }
        _atomic_json_write(self.root / f"{video_id}.json", metadata)
        (self.sessions / f"{upload_id}.json").unlink(missing_ok=True)
        return metadata

    def abort(self, upload_id: str) -> None:
        try:
            UUID(upload_id)
        except ValueError:
            return
        (self.sessions / f"{upload_id}.part").unlink(missing_ok=True)
        (self.sessions / f"{upload_id}.json").unlink(missing_ok=True)
