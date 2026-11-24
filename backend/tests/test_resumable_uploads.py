from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import app

client = TestClient(app)


def test_resumable_upload_can_append_resume_and_finalize(tmp_path: Path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    monkeypatch.setenv("TVI_MAX_UPLOAD_MB", "10240")

    created = client.post(
        "/api/v1/videos/uploads",
        json={
            "filename": "match.mp4",
            "content_type": "video/mp4",
            "size_bytes": 10,
        },
    )
    assert created.status_code == 201
    session = created.json()
    upload_id = session["upload_id"]
    assert session["upload_offset"] == 0
    assert session["upload_length"] == 10
    assert session["chunk_size_bytes"] == 16 * 1024 * 1024

    first = client.patch(
        f"/api/v1/videos/uploads/{upload_id}",
        headers={"Upload-Offset": "0", "Content-Type": "application/offset+octet-stream"},
        content=b"01234",
    )
    assert first.status_code == 204
    assert first.headers["upload-offset"] == "5"

    head = client.head(f"/api/v1/videos/uploads/{upload_id}")
    assert head.status_code == 204
    assert head.headers["upload-offset"] == "5"
    assert head.headers["upload-length"] == "10"

    wrong_offset = client.patch(
        f"/api/v1/videos/uploads/{upload_id}",
        headers={"Upload-Offset": "0", "Content-Type": "application/offset+octet-stream"},
        content=b"56789",
    )
    assert wrong_offset.status_code == 409
    assert wrong_offset.headers["upload-offset"] == "5"

    second = client.patch(
        f"/api/v1/videos/uploads/{upload_id}",
        headers={"Upload-Offset": "5", "Content-Type": "application/offset+octet-stream"},
        content=b"56789",
    )
    assert second.status_code == 204
    assert second.headers["upload-offset"] == "10"

    completed = client.post(f"/api/v1/videos/uploads/{upload_id}/complete")
    assert completed.status_code == 201
    video = completed.json()
    assert video["size_bytes"] == 10
    assert (tmp_path / f"{video['video_id']}.mp4").read_bytes() == b"0123456789"
    assert (tmp_path / f"{video['video_id']}.json").exists()
    get_settings.cache_clear()


def test_resumable_upload_rejects_a_file_over_the_configured_limit(
    tmp_path: Path, monkeypatch
) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    monkeypatch.setenv("TVI_MAX_UPLOAD_MB", "1")

    response = client.post(
        "/api/v1/videos/uploads",
        json={
            "filename": "too-large.mp4",
            "content_type": "video/mp4",
            "size_bytes": 1024 * 1024 + 1,
        },
    )

    assert response.status_code == 413
    get_settings.cache_clear()


def test_default_upload_limit_is_ten_gibibytes(monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.delenv("TVI_MAX_UPLOAD_MB", raising=False)

    assert get_settings().max_upload_mb == 10_240


def test_default_upload_limit_rejects_a_file_over_ten_gibibytes(
    tmp_path: Path, monkeypatch
) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    monkeypatch.delenv("TVI_MAX_UPLOAD_MB", raising=False)

    response = client.post(
        "/api/v1/videos/uploads",
        json={
            "filename": "over-limit.mp4",
            "content_type": "video/mp4",
            "size_bytes": 10_240 * 1024 * 1024 + 1,
        },
    )

    assert response.status_code == 413
    get_settings.cache_clear()
