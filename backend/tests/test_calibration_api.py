import pytest
from fastapi.testclient import TestClient

from app.api import videos as videos_api
from app.core.config import get_settings
from app.main import app

client = TestClient(app)


def _upload_video(tmp_path, monkeypatch) -> str:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    response = client.post(
        "/api/v1/videos",
        files={"file": ("court-view.mp4", b"video-bytes", "video/mp4")},
    )
    assert response.status_code == 201
    return response.json()["video_id"]


def test_manual_calibration_projects_player_point_to_metres(tmp_path, monkeypatch) -> None:
    video_id = _upload_video(tmp_path, monkeypatch)
    request = {
        "frame_timestamp_seconds": 0.0,
        "court_area": "doubles",
        "image_points": [[100, 100], [500, 100], [500, 500], [100, 500]],
    }

    calibration = client.post(f"/api/v1/videos/{video_id}/calibrations", json=request)
    assert calibration.status_code == 201
    calibration_id = calibration.json()["calibration_id"]

    projection = client.post(
        f"/api/v1/videos/{video_id}/calibrations/{calibration_id}/project",
        json={"image_point": [300, 500], "player_end": "near"},
    )

    assert projection.status_code == 200
    result = projection.json()
    assert result["court_x_m"] == pytest.approx(0.0, abs=1e-7)
    assert result["court_y_m"] == pytest.approx(11.885, abs=1e-7)
    assert result["signed_baseline_distance_m"] == pytest.approx(0.0, abs=1e-7)
    get_settings.cache_clear()


def test_manual_calibration_rejects_collinear_points(tmp_path, monkeypatch) -> None:
    video_id = _upload_video(tmp_path, monkeypatch)
    response = client.post(
        f"/api/v1/videos/{video_id}/calibrations",
        json={
            "frame_timestamp_seconds": 0.0,
            "court_area": "doubles",
            "image_points": [[10, 10], [20, 10], [30, 10], [40, 10]],
        },
    )

    assert response.status_code == 422
    get_settings.cache_clear()


def test_frame_preview_returns_jpeg_bytes(tmp_path, monkeypatch) -> None:
    video_id = _upload_video(tmp_path, monkeypatch)
    monkeypatch.setattr(videos_api, "extract_video_frame", lambda path, timestamp: b"jpeg-frame")

    response = client.get(f"/api/v1/videos/{video_id}/frame?timestamp_seconds=4.5")

    assert response.status_code == 200
    assert response.headers["content-type"] == "image/jpeg"
    assert response.content == b"jpeg-frame"
    get_settings.cache_clear()
