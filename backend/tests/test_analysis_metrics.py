import cv2
import numpy as np
import pytest

from app.services.analysis_metrics import calculate_reviewed_match_metrics
from app.services.report_raster import render_analysis_pdf, render_analysis_png


def _event(event_id, timestamp, event_type, **kwargs):
    return {
        "event_id": event_id,
        "timestamp_seconds": timestamp,
        "event_type": event_type,
        "review_status": "accepted",
        **kwargs,
    }


def test_first_second_serve_and_serve_zone_statistics() -> None:
    job = {
        "events": [
            _event("s1", 1.0, "serve_attempt", serve_number=1, serve_result="fault"),
            _event("s2", 2.0, "serve_attempt", serve_number=2, serve_result="in"),
            _event(
                "b2",
                2.1,
                "serve_bounce",
                court_x_m=0.5,
                court_y_m=5.0,
                serve_number=2,
                serve_result="in",
            ),
            _event("s3", 3.0, "serve_attempt", serve_number=1, serve_result="ace"),
            _event(
                "b3",
                3.1,
                "serve_bounce",
                court_x_m=3.3,
                court_y_m=-4.0,
                serve_number=1,
                serve_result="ace",
            ),
        ]
    }
    metrics = calculate_reviewed_match_metrics(job)
    serve = metrics["serve_placement"]
    assert serve["recorded_attempts"] == 3
    assert serve["first_serve_events"] == 2
    assert serve["second_serve_events"] == 1
    assert serve["serve_results"]["fault"] == 1
    assert serve["serve_results"]["ace"] == 1
    assert serve["serve_in_percentage"] == pytest.approx(66.6666666667)
    assert serve["zones"]["T"] == 1
    assert serve["zones"]["wide"] == 1


def test_shot_depths_and_rally_pairing_do_not_reuse_point_end() -> None:
    job = {
        "events": [
            _event("p1", 0.0, "point_start", point_id="1"),
            _event("c1", 0.2, "racket_contact", player_id="near_player"),
            _event("b1", 0.4, "shot_bounce", court_x_m=0.0, court_y_m=10.0),
            _event("e1", 1.0, "point_end", point_id="1"),
            _event("p2", 2.0, "point_start", point_id="2"),
            _event("c2", 2.2, "racket_contact", player_id="far_player"),
            _event("c3", 2.6, "racket_contact", player_id="near_player"),
            _event("e2", 3.0, "point_end", point_id="2"),
        ]
    }
    metrics = calculate_reviewed_match_metrics(job)
    assert metrics["shot_placement"]["depths"]["deep"] == 1
    assert metrics["rally_length"]["contact_counts"] == [1, 2]
    assert metrics["rally_length"]["completed_points"] == 2
    assert metrics["rally_length"]["incomplete_points"] == 0
    assert metrics["rally_length"]["sequences"][1]["duration_seconds"] == 1.0


def test_raster_and_pdf_reports_are_valid_outputs() -> None:
    job = {
        "analysis_id": "analysis",
        "video_id": "video",
        "completed_at": "2026-11-24T00:00:00+00:00",
        "summary": {
            "players": {
                "near_player": {
                    "average_baseline_distance_m": 1.2,
                    "samples": 10,
                    "coverage_pct": 60,
                },
                "far_player": {
                    "average_baseline_distance_m": -0.7,
                    "samples": 8,
                    "coverage_pct": 50,
                },
            },
            "position_heatmaps": {
                "near_player": [[0, 1], [2, 0]],
                "far_player": [[1, 0], [0, 1]],
            },
        },
        "events": [
            _event(
                "serve",
                1.0,
                "serve_bounce",
                serve_number=1,
                serve_result="in",
                court_x_m=0.2,
                court_y_m=4.0,
            )
        ],
    }
    png = render_analysis_png(job)
    decoded = cv2.imdecode(np.frombuffer(png, dtype=np.uint8), cv2.IMREAD_COLOR)
    assert decoded is not None
    assert decoded.shape[:2] == (1500, 1200)
    pdf = render_analysis_pdf(job)
    assert pdf.startswith(b"%PDF-1.4")
    assert pdf.rstrip().endswith(b"%%EOF")
