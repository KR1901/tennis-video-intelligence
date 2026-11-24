import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.services.media_probe import InvalidMediaError, _parse_frame_rate, probe_video_file


def test_parse_fractional_frame_rate() -> None:
    assert _parse_frame_rate("30000/1001") == pytest.approx(29.97002997)


def test_probe_extracts_video_metadata(tmp_path: Path, monkeypatch) -> None:
    source = tmp_path / "match.mp4"
    source.write_bytes(b"video")
    payload = {
        "format": {
            "duration": "91.5",
            "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
            "bit_rate": "4000000",
        },
        "streams": [
            {
                "codec_type": "video",
                "codec_name": "h264",
                "width": 1920,
                "height": 1080,
                "avg_frame_rate": "30000/1001",
                "nb_frames": "2742",
                "tags": {"rotate": "0"},
            },
            {"codec_type": "audio", "codec_name": "aac"},
        ],
    }
    monkeypatch.setattr(
        "app.services.media_probe.subprocess.run",
        lambda *args, **kwargs: SimpleNamespace(
            returncode=0,
            stdout=json.dumps(payload),
            stderr="",
        ),
    )

    result = probe_video_file(source)

    assert result["duration_seconds"] == pytest.approx(91.5)
    assert result["width"] == 1920
    assert result["height"] == 1080
    assert result["frame_rate"] == pytest.approx(29.97002997)
    assert result["frame_count"] == 2742
    assert result["video_codec"] == "h264"
    assert result["file_size_bytes"] == 5


def test_probe_rejects_media_without_video_stream(tmp_path: Path, monkeypatch) -> None:
    source = tmp_path / "audio.mp4"
    source.write_bytes(b"audio")
    monkeypatch.setattr(
        "app.services.media_probe.subprocess.run",
        lambda *args, **kwargs: SimpleNamespace(
            returncode=0,
            stdout=json.dumps({"format": {}, "streams": [{"codec_type": "audio"}]}),
            stderr="",
        ),
    )

    with pytest.raises(InvalidMediaError, match="does not contain a video stream"):
        probe_video_file(source)
