from fastapi.testclient import TestClient

from app.api import videos as videos_api
from app.core.config import get_settings
from app.main import app

client = TestClient(app)


def test_health_endpoint() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_upload_video_saves_file_and_metadata(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    monkeypatch.setenv("TVI_MAX_UPLOAD_MB", "1")

    response = client.post(
        "/api/v1/videos",
        files={"file": ("practice.mp4", b"fake-video-bytes", "video/mp4")},
    )

    assert response.status_code == 201
    data = response.json()
    assert data["filename"] == "practice.mp4"
    assert data["size_bytes"] == len(b"fake-video-bytes")
    assert data["status"] == "uploaded"
    assert (tmp_path / f"{data['video_id']}.mp4").exists()
    assert (tmp_path / f"{data['video_id']}.json").exists()
    get_settings.cache_clear()


def test_upload_rejects_non_video_extension(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))

    response = client.post(
        "/api/v1/videos",
        files={"file": ("notes.txt", b"not a video", "text/plain")},
    )

    assert response.status_code == 415
    get_settings.cache_clear()


def test_upload_rejects_empty_file(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))

    response = client.post(
        "/api/v1/videos",
        files={"file": ("empty.mp4", b"", "video/mp4")},
    )

    assert response.status_code == 415
    get_settings.cache_clear()


def test_upload_rejects_file_over_size_limit(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    monkeypatch.setenv("TVI_MAX_UPLOAD_MB", "1")

    response = client.post(
        "/api/v1/videos",
        files={"file": ("large.mp4", b"x" * (1024 * 1024 + 1), "video/mp4")},
    )

    assert response.status_code == 413
    assert list(tmp_path.iterdir()) == []
    get_settings.cache_clear()

def test_video_status_and_inspection_are_persisted(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    uploaded = client.post(
        "/api/v1/videos",
        files={"file": ("match.mp4", b"fake-video-bytes", "video/mp4")},
    )
    assert uploaded.status_code == 201
    video_id = uploaded.json()["video_id"]

    media_info = {
        "duration_seconds": 123.4,
        "width": 1920,
        "height": 1080,
        "frame_rate": 59.94,
        "frame_count": 7400,
        "video_codec": "h264",
        "container_format": "mov,mp4",
        "rotation_degrees": 0,
        "bit_rate": 5_000_000,
        "file_size_bytes": len(b"fake-video-bytes"),
    }
    monkeypatch.setattr(videos_api, "probe_video_file", lambda path: media_info)

    inspection = client.post(f"/api/v1/videos/{video_id}/inspect")
    assert inspection.status_code == 200
    assert inspection.json()["width"] == 1920
    assert inspection.json()["duration_seconds"] == 123.4

    status_response = client.get(f"/api/v1/videos/{video_id}")
    assert status_response.status_code == 200
    assert status_response.json()["status"] == "inspected"
    assert status_response.json()["media_info"]["video_codec"] == "h264"
    get_settings.cache_clear()


def test_video_status_returns_not_found_for_invalid_id() -> None:
    response = client.get("/api/v1/videos/not-a-uuid")
    assert response.status_code == 404

