from types import SimpleNamespace

import pytest

from app.services.frame_extract import FrameExtractionError, extract_video_frame


def test_extract_frame_returns_jpeg_bytes(tmp_path, monkeypatch) -> None:
    video = tmp_path / "match.mp4"
    video.write_bytes(b"video")
    monkeypatch.setattr(
        "app.services.frame_extract.subprocess.run",
        lambda *args, **kwargs: SimpleNamespace(returncode=0, stdout=b"jpeg-frame", stderr=b""),
    )

    result = extract_video_frame(video, 12.345)

    assert result == b"jpeg-frame"


def test_extract_frame_rejects_missing_frame(tmp_path, monkeypatch) -> None:
    video = tmp_path / "match.mp4"
    video.write_bytes(b"video")
    monkeypatch.setattr(
        "app.services.frame_extract.subprocess.run",
        lambda *args, **kwargs: SimpleNamespace(returncode=1, stdout=b"", stderr=b"bad timestamp"),
    )

    with pytest.raises(FrameExtractionError):
        extract_video_frame(video, 999.0)


def test_extract_frame_rejects_negative_timestamp(tmp_path) -> None:
    with pytest.raises(ValueError, match="zero or greater"):
        extract_video_frame(tmp_path / "match.mp4", -0.1)
