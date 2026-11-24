from fastapi.testclient import TestClient

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
