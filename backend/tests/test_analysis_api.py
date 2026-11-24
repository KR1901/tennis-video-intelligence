import json
from uuid import uuid4

from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import app

client = TestClient(app)


def test_event_review_updates_only_confirmed_statistics(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    analysis_id = str(uuid4())
    events_dir = tmp_path / "analyses"
    events_dir.mkdir()
    event_id = "candidate-000001"
    job = {
        "analysis_id": analysis_id,
        "video_id": str(uuid4()),
        "status": "completed",
        "progress_pct": 100,
        "processed_samples": 100,
        "created_at": "2026-11-24T00:00:00+00:00",
        "completed_at": "2026-11-24T00:01:00+00:00",
        "summary": {"players": {}, "position_heatmaps": {}},
        "events": [
            {
                "event_id": event_id,
                "event_type": "trajectory_change_candidate",
                "timestamp_seconds": 23.5,
                "court_x_m": 1.0,
                "court_y_m": 8.0,
                "review_status": "pending",
                "confidence": 0.2,
            }
        ],
    }
    (events_dir / f"{analysis_id}.json").write_text(json.dumps(job), encoding="utf-8")

    response = client.post(
        f"/api/v1/analyses/{analysis_id}/events/{event_id}/review",
        json={
            "review_status": "accepted",
            "event_type": "serve_bounce",
            "court_x_m": 2.1,
            "court_y_m": 8.2,
            "player_id": "near_player",
            "note": "Confirmed in the video replay.",
        },
    )

    assert response.status_code == 200
    assert response.json()["reviewed_metrics"]["serve_placement"]["confirmed_serves"] == 1
    assert response.json()["reviewed_metrics"]["shot_placement"]["confirmed_bounces"] == 0

    events = client.get(f"/api/v1/analyses/{analysis_id}/events")
    assert events.status_code == 200
    reviewed = events.json()["events"][0]
    assert reviewed["review_status"] == "accepted"
    assert reviewed["event_type"] == "serve_bounce"
    assert reviewed["court_x_m"] == 2.1
    get_settings.cache_clear()


def test_accepted_event_requires_explicit_label(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    analysis_id = str(uuid4())
    events_dir = tmp_path / "analyses"
    events_dir.mkdir()
    event_id = "candidate-000001"
    job = {
        "analysis_id": analysis_id,
        "video_id": str(uuid4()),
        "status": "completed",
        "events": [{"event_id": event_id, "review_status": "pending"}],
    }
    (events_dir / f"{analysis_id}.json").write_text(json.dumps(job), encoding="utf-8")

    response = client.post(
        f"/api/v1/analyses/{analysis_id}/events/{event_id}/review",
        json={"review_status": "accepted", "player_id": "unknown"},
    )

    assert response.status_code == 422
    get_settings.cache_clear()


def test_analysis_report_exports_svg(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    analysis_id = str(uuid4())
    events_dir = tmp_path / "analyses"
    events_dir.mkdir()
    job = {
        "analysis_id": analysis_id,
        "video_id": str(uuid4()),
        "status": "completed",
        "summary": {
            "players": {
                "near_player": {"average_baseline_distance_m": 1.25},
                "far_player": {"average_baseline_distance_m": -0.45},
            },
            "position_heatmaps": {"near_player": [], "far_player": []},
        },
        "completed_at": "2026-11-24T00:01:00+00:00",
        "events": [],
    }
    (events_dir / f"{analysis_id}.json").write_text(json.dumps(job), encoding="utf-8")

    response = client.get(f"/api/v1/analyses/{analysis_id}/report.svg")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("image/svg+xml")
    assert b"Tennis Video Intelligence" in response.content
    get_settings.cache_clear()


def test_manual_event_creation_is_accepted_and_counted(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    analysis_id = str(uuid4())
    events_dir = tmp_path / "analyses"
    events_dir.mkdir()
    job = {
        "analysis_id": analysis_id,
        "video_id": str(uuid4()),
        "status": "completed",
        "summary": {"source": {"duration_seconds": 120.0}},
        "events": [],
    }
    (events_dir / f"{analysis_id}.json").write_text(json.dumps(job), encoding="utf-8")

    response = client.post(
        f"/api/v1/analyses/{analysis_id}/events",
        json={
            "timestamp_seconds": 12.5,
            "event_type": "shot_bounce",
            "court_x_m": 1.5,
            "court_y_m": -4.0,
            "player_id": "far_player",
            "note": "Confirmed from the replay.",
        },
    )

    assert response.status_code == 201
    event = response.json()["event"]
    assert event["review_status"] == "accepted"
    assert event["review_source"] == "human_manual_entry"
    assert response.json()["reviewed_metrics"]["shot_placement"]["confirmed_bounces"] == 1
    get_settings.cache_clear()


def test_manual_event_creation_rejects_timestamp_past_video_duration(tmp_path, monkeypatch) -> None:
    get_settings.cache_clear()
    monkeypatch.setenv("TVI_STORAGE_DIR", str(tmp_path))
    analysis_id = str(uuid4())
    events_dir = tmp_path / "analyses"
    events_dir.mkdir()
    job = {
        "analysis_id": analysis_id,
        "video_id": str(uuid4()),
        "status": "completed",
        "summary": {"source": {"duration_seconds": 10.0}},
        "events": [],
    }
    (events_dir / f"{analysis_id}.json").write_text(json.dumps(job), encoding="utf-8")

    response = client.post(
        f"/api/v1/analyses/{analysis_id}/events",
        json={"timestamp_seconds": 12.0, "event_type": "point_start"},
    )

    assert response.status_code == 422
    get_settings.cache_clear()
