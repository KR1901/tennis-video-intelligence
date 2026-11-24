import json
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import app

client = TestClient(app)


def _video_file(tmp_path, suffix=".mp4"):
    video_id = str(uuid4())
    path = tmp_path / f"{video_id}{suffix}"
    path.write_bytes(b"0123456789abcdef")
    metadata = {
        "video_id": video_id,
        "filename": f"match{suffix}",
        "size_bytes": path.stat().st_size,
        "content_type": "video/mp4",
        "created_at": "2026-11-24T00:00:00+00:00",
    }
    (tmp_path / f"{video_id}.json").write_text(json.dumps(metadata), encoding="utf-8")
    return video_id, path


def test_video_stream_supports_byte_ranges(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    video_id, _ = _video_file(tmp_path)

    response = client.get(
        f"/api/v1/videos/{video_id}/stream",
        headers={"Range": "bytes=3-7"},
    )

    assert response.status_code == 206
    assert response.content == b"34567"
    assert response.headers["accept-ranges"] == "bytes"
    assert response.headers["content-range"] == "bytes 3-7/16"
    get_settings.cache_clear()


def test_video_stream_rejects_unsatisfiable_byte_range(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    video_id, _ = _video_file(tmp_path)

    response = client.get(
        f"/api/v1/videos/{video_id}/stream",
        headers={"Range": "bytes=99-100"},
    )

    assert response.status_code == 416
    assert response.headers["content-range"] == "bytes */16"
    get_settings.cache_clear()


def test_delete_video_removes_original_metadata_and_analysis_files(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    video_id, video_path = _video_file(tmp_path)
    analyses_dir = tmp_path / "analyses"
    analyses_dir.mkdir()
    analysis_id = str(uuid4())
    associated = analyses_dir / f"{analysis_id}.json"
    associated_payload = {"analysis_id": analysis_id, "video_id": video_id}
    associated.write_text(json.dumps(associated_payload), encoding="utf-8")
    unrelated = analyses_dir / f"{uuid4()}.json"
    unrelated.write_text(json.dumps({"video_id": str(uuid4())}), encoding="utf-8")

    response = client.delete(f"/api/v1/videos/{video_id}")

    assert response.status_code == 204
    assert not video_path.exists()
    assert not (tmp_path / f"{video_id}.json").exists()
    assert not associated.exists()
    assert unrelated.exists()
    get_settings.cache_clear()


def test_api_token_is_optional_but_protects_non_health_endpoints(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    monkeypatch.setenv("TVI_API_TOKEN", "test-shared-token")

    assert client.get("/health").status_code == 200
    protected = client.get(f"/api/v1/videos/{uuid4()}")
    assert protected.status_code == 401

    authorised = client.get(
        f"/api/v1/videos/{uuid4()}",
        headers={"Authorization": "Bearer test-shared-token"},
    )
    assert authorised.status_code == 404
    get_settings.cache_clear()


def test_production_settings_require_api_token(monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_ENVIRONMENT", "production")
    monkeypatch.delenv("TVI_API_TOKEN", raising=False)

    with pytest.raises(ValueError, match="TVI_API_TOKEN must be configured"):
        get_settings()
    get_settings.cache_clear()


def test_analysis_png_and_pdf_report_endpoints(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    analysis_id = str(uuid4())
    (tmp_path / "analyses").mkdir()
    job = {
        "analysis_id": analysis_id,
        "video_id": str(uuid4()),
        "status": "completed",
        "completed_at": "2026-11-24T00:00:00+00:00",
        "summary": {
            "players": {
                "near_player": {
                    "average_baseline_distance_m": 1.0, "samples": 3, "coverage_pct": 50
                },
                "far_player": {
                    "average_baseline_distance_m": -1.0, "samples": 2, "coverage_pct": 30
                },
            },
            "position_heatmaps": {"near_player": [[0, 1]], "far_player": [[1, 0]]},
        },
        "events": [],
    }
    (tmp_path / "analyses" / f"{analysis_id}.json").write_text(json.dumps(job), encoding="utf-8")

    png = client.get(f"/api/v1/analyses/{analysis_id}/report.png")
    pdf = client.get(f"/api/v1/analyses/{analysis_id}/report.pdf")

    assert png.status_code == 200
    assert png.headers["content-type"] == "image/png"
    assert png.content.startswith(b"\x89PNG\r\n\x1a\n")
    assert pdf.status_code == 200
    assert pdf.headers["content-type"] == "application/pdf"
    assert pdf.content.startswith(b"%PDF-1.4")
    get_settings.cache_clear()



def test_manual_event_metadata_is_persisted_for_review_metrics(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    analysis_id = str(uuid4())
    analyses_dir = tmp_path / "analyses"
    analyses_dir.mkdir()
    job = {
        "analysis_id": analysis_id,
        "video_id": str(uuid4()),
        "status": "completed",
        "summary": {"source": {"duration_seconds": 100.0}},
        "events": [],
    }
    (analyses_dir / f"{analysis_id}.json").write_text(json.dumps(job), encoding="utf-8")

    response = client.post(
        f"/api/v1/analyses/{analysis_id}/events",
        json={
            "timestamp_seconds": 5.2,
            "event_type": "serve_attempt",
            "serve_number": 2,
            "serve_result": "ace",
            "player_id": "near_player",
            "note": "Second serve ace confirmed from replay.",
        },
    )

    assert response.status_code == 201
    event = response.json()["event"]
    assert event["serve_number"] == 2
    assert event["serve_result"] == "ace"
    assert response.json()["reviewed_metrics"]["serve_placement"]["second_serve_events"] == 1
    get_settings.cache_clear()
