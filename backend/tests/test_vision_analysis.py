import json

import cv2
import numpy as np

from app.services.analysis_metrics import calculate_reviewed_match_metrics
from app.services.vision_analysis import (
    detect_ball_candidate,
    detect_court_candidate,
    run_analysis_job,
)


def test_detect_court_candidate_finds_synthetic_green_quadrilateral() -> None:
    frame = np.zeros((360, 640, 3), dtype=np.uint8)
    cv2.fillConvexPoly(
        frame,
        np.array([[90, 35], [550, 55], [525, 325], [110, 305]], dtype=np.int32),
        (55, 145, 65),
    )

    result = detect_court_candidate(frame)

    assert result is not None
    assert result["requires_user_confirmation"] is True
    assert len(result["image_points"]) == 4
    assert 0 < result["confidence"] <= 0.72


def test_detect_ball_candidate_finds_compact_yellow_blob() -> None:
    frame = np.full((360, 640, 3), (55, 145, 65), dtype=np.uint8)
    cv2.circle(frame, (320, 180), 4, (0, 230, 255), -1)

    result = detect_ball_candidate(frame)

    assert result is not None
    assert result["detector"] == "hsv_compact_blob_candidate"
    assert result["confidence"] <= 0.45


def test_reviewed_match_metrics_ignore_unreviewed_candidates() -> None:
    job = {
        "events": [
            {
                "timestamp_seconds": 1.0,
                "event_type": "serve_bounce",
                "review_status": "accepted",
                "court_x_m": 2.2,
                "court_y_m": 8.0,
            },
            {
                "timestamp_seconds": 1.5,
                "event_type": "shot_bounce",
                "review_status": "accepted",
                "court_x_m": -1.2,
                "court_y_m": -6.0,
            },
            {
                "timestamp_seconds": 2.0,
                "event_type": "racket_contact",
                "review_status": "accepted",
            },
            {
                "timestamp_seconds": 2.5,
                "event_type": "trajectory_change_candidate",
                "review_status": "pending",
                "court_x_m": 0.0,
                "court_y_m": 0.0,
            },
        ]
    }

    metrics = calculate_reviewed_match_metrics(job)

    assert metrics["accepted_event_count"] == 3
    assert metrics["serve_placement"]["confirmed_serves"] == 1
    assert metrics["shot_placement"]["confirmed_bounces"] == 1
    assert metrics["rally_length"]["average_contacts_per_point"] is None


def test_analysis_worker_persists_completed_job(tmp_path, monkeypatch) -> None:
    import app.services.vision_analysis as vision

    class FakeCapture:
        def __init__(self, path: str) -> None:
            self.index = 0
            self.frames = [np.zeros((160, 240, 3), dtype=np.uint8) for _ in range(8)]

        def isOpened(self) -> bool:
            return True

        def get(self, prop: int) -> float:
            if prop == cv2.CAP_PROP_FPS:
                return 2.0
            if prop == cv2.CAP_PROP_FRAME_WIDTH:
                return 240.0
            if prop == cv2.CAP_PROP_FRAME_HEIGHT:
                return 160.0
            if prop == cv2.CAP_PROP_FRAME_COUNT:
                return float(len(self.frames))
            return 0.0

        def read(self):
            if self.index >= len(self.frames):
                return False, None
            frame = self.frames[self.index]
            self.index += 1
            return True, frame

        def release(self) -> None:
            return None

    source = tmp_path / "source.mp4"
    source.write_bytes(b"placeholder")
    job_path = tmp_path / "job.json"
    job_path.write_text(
        json.dumps(
            {
                "analysis_id": "test-analysis",
                "video_id": "test-video",
                "status": "queued",
                "progress_pct": 0,
                "created_at": "2026-01-01T00:00:00+00:00",
            }
        ),
        encoding="utf-8",
    )
    monkeypatch.setattr(vision.cv2, "VideoCapture", FakeCapture)
    monkeypatch.setattr(vision, "detect_court_candidate", lambda frame: None)
    monkeypatch.setattr(vision, "detect_players", lambda frame: [])
    monkeypatch.setattr(vision, "detect_ball_candidate", lambda frame: None)

    run_analysis_job(
        str(source),
        str(job_path),
        {"sample_interval_seconds": 0.5, "max_samples": 100, "enable_ball_tracking": False},
        {},
    )

    result = json.loads(job_path.read_text(encoding="utf-8"))
    assert result["status"] == "completed"
    assert result["progress_pct"] == 100
    assert result["summary"]["source"]["sampled_frames"] == 8
    assert result["summary"]["calibration"]["mode"] == "unavailable"
