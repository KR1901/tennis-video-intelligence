import json
import subprocess
from fractions import Fraction
from pathlib import Path
from typing import Any


class FFprobeUnavailableError(RuntimeError):
    """Raised when the ffprobe executable cannot be started."""


class MediaProbeTimeoutError(RuntimeError):
    """Raised when media inspection exceeds its time limit."""


class InvalidMediaError(ValueError):
    """Raised when ffprobe cannot identify a decodable video stream."""


def _as_int(value: Any) -> int | None:
    try:
        return int(value) if value not in (None, "", "N/A") else None
    except (TypeError, ValueError):
        return None


def _as_float(value: Any) -> float | None:
    try:
        return float(value) if value not in (None, "", "N/A") else None
    except (TypeError, ValueError):
        return None


def _parse_frame_rate(value: str | None) -> float | None:
    if not value or value in {"0/0", "N/A"}:
        return None
    try:
        rate = float(Fraction(value))
        return rate if rate > 0 else None
    except (ValueError, ZeroDivisionError):
        return None


def probe_video_file(
    path: Path,
    ffprobe_path: str = "ffprobe",
    timeout_seconds: int = 30,
) -> dict[str, Any]:
    """Read technical metadata using ffprobe without decoding the entire video."""
    command = [
        ffprobe_path,
        "-v",
        "error",
        "-show_entries",
        (
            "format=duration,format_name,size,bit_rate:"
            "stream=index,codec_type,codec_name,width,height,r_frame_rate,"
            "avg_frame_rate,duration,nb_frames,bit_rate:"
            "stream_tags=rotate:stream_side_data=rotation"
        ),
        "-of",
        "json",
        str(path),
    ]

    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            check=False,
            text=True,
            timeout=timeout_seconds,
        )
    except FileNotFoundError as exc:
        raise FFprobeUnavailableError("ffprobe is not installed or is not on PATH.") from exc
    except subprocess.TimeoutExpired as exc:
        raise MediaProbeTimeoutError("Media inspection timed out.") from exc

    if completed.returncode != 0:
        raise InvalidMediaError("ffprobe could not read a valid media container.")

    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise InvalidMediaError("ffprobe returned invalid metadata.") from exc

    streams = payload.get("streams", [])
    video_stream = next(
        (stream for stream in streams if stream.get("codec_type") == "video"),
        None,
    )
    if video_stream is None:
        raise InvalidMediaError("The uploaded file does not contain a video stream.")

    format_info = payload.get("format", {})
    rotation = _as_int(video_stream.get("tags", {}).get("rotate"))
    for side_data in video_stream.get("side_data_list", []):
        side_rotation = _as_int(side_data.get("rotation"))
        if side_rotation is not None:
            rotation = side_rotation
            break

    frame_rate = _parse_frame_rate(video_stream.get("avg_frame_rate")) or _parse_frame_rate(
        video_stream.get("r_frame_rate")
    )

    return {
        "duration_seconds": _as_float(format_info.get("duration"))
        or _as_float(video_stream.get("duration")),
        "width": _as_int(video_stream.get("width")),
        "height": _as_int(video_stream.get("height")),
        "frame_rate": frame_rate,
        "frame_count": _as_int(video_stream.get("nb_frames")),
        "video_codec": video_stream.get("codec_name"),
        "container_format": format_info.get("format_name"),
        "rotation_degrees": rotation or 0,
        "bit_rate": _as_int(format_info.get("bit_rate"))
        or _as_int(video_stream.get("bit_rate")),
        "file_size_bytes": path.stat().st_size,
    }
