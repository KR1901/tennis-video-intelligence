import subprocess
from pathlib import Path


class FFmpegUnavailableError(RuntimeError):
    """Raised when the ffmpeg executable cannot be started."""


class FrameExtractionTimeoutError(RuntimeError):
    """Raised when frame extraction exceeds its time limit."""


class FrameExtractionError(ValueError):
    """Raised when a frame cannot be extracted at the requested timestamp."""


def extract_video_frame(
    path: Path,
    timestamp_seconds: float,
    ffmpeg_path: str = "ffmpeg",
    timeout_seconds: int = 30,
) -> bytes:
    """Extract one JPEG frame to memory without writing derived media to disk."""
    if timestamp_seconds < 0:
        raise ValueError("Frame timestamp must be zero or greater.")

    command = [
        ffmpeg_path,
        "-hide_banner",
        "-loglevel",
        "error",
        "-ss",
        f"{timestamp_seconds:.3f}",
        "-i",
        str(path),
        "-frames:v",
        "1",
        "-q:v",
        "3",
        "-f",
        "image2pipe",
        "-vcodec",
        "mjpeg",
        "pipe:1",
    ]

    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            check=False,
            timeout=timeout_seconds,
        )
    except FileNotFoundError as exc:
        raise FFmpegUnavailableError("ffmpeg is not installed or is not on PATH.") from exc
    except subprocess.TimeoutExpired as exc:
        raise FrameExtractionTimeoutError("Frame extraction timed out.") from exc

    if completed.returncode != 0 or not completed.stdout:
        raise FrameExtractionError("No frame could be extracted at this timestamp.")
    return completed.stdout
