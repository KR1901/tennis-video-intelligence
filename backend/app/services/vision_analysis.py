import json
import math
import time
from datetime import UTC, datetime
from functools import lru_cache
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from app.services.court_geometry import (
    CourtDimensions,
    estimate_homography,
    project_point,
    signed_baseline_distance_m,
)

MAX_ANALYSIS_SAMPLES = 32_000
DEFAULT_SAMPLE_INTERVAL = 0.5
MAX_ANALYSIS_WIDTH = 960


class VideoAnalysisError(RuntimeError):
    """Raised when the source video cannot be analysed."""


def _atomic_json_write(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    temporary.replace(path)


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise VideoAnalysisError("The persisted analysis job could not be read.") from exc
    if not isinstance(value, dict):
        raise VideoAnalysisError("The persisted analysis job is malformed.")
    return value


def _ordered_quad(points: np.ndarray) -> np.ndarray:
    """Order four corners as top-left, top-right, bottom-right, bottom-left."""
    points = np.asarray(points, dtype=np.float32).reshape(4, 2)
    sums = points.sum(axis=1)
    differences = points[:, 0] - points[:, 1]
    return np.array(
        [
            points[np.argmin(sums)],
            points[np.argmax(differences)],
            points[np.argmax(sums)],
            points[np.argmin(differences)],
        ],
        dtype=np.float32,
    )


def detect_court_candidate(frame: np.ndarray) -> dict[str, Any] | None:
    """Find a large green court-shaped region; return suggestions, never silent approval."""
    if frame is None or frame.size == 0:
        return None

    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    mask = cv2.inRange(
        hsv,
        np.array([27, 28, 28], dtype=np.uint8),
        np.array([100, 255, 255], dtype=np.uint8),
    )
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel, iterations=2)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel, iterations=1)

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    frame_area = float(frame.shape[0] * frame.shape[1])
    candidates = [c for c in contours if cv2.contourArea(c) >= frame_area * 0.06]
    if not candidates:
        return None

    contour = max(candidates, key=cv2.contourArea)
    area_ratio = float(cv2.contourArea(contour) / frame_area)
    perimeter = cv2.arcLength(contour, True)
    approximated = cv2.approxPolyDP(contour, 0.025 * perimeter, True)

    if len(approximated) == 4 and cv2.isContourConvex(approximated):
        corners = _ordered_quad(approximated.reshape(4, 2))
        method = "green_contour_quadrilateral"
        shape_quality = 1.0
    else:
        rectangle = cv2.minAreaRect(contour)
        corners = _ordered_quad(cv2.boxPoints(rectangle))
        method = "green_region_minimum_rectangle"
        shape_quality = 0.55

    width = float(np.linalg.norm(corners[1] - corners[0]))
    height = float(np.linalg.norm(corners[3] - corners[0]))
    aspect = max(width, height) / max(1.0, min(width, height))
    if aspect < 1.15 or not np.isfinite(corners).all():
        return None

    confidence = min(0.72, area_ratio * 1.8) * shape_quality
    return {
        "image_points": [[float(x), float(y)] for x, y in corners],
        "area_ratio": area_ratio,
        "aspect_ratio": aspect,
        "confidence": confidence,
        "method": method,
        "requires_user_confirmation": True,
        "orientation_note": (
            "Image top is provisionally treated as the far baseline. Confirm the four "
            "corners in the preview before trusting court coordinates."
        ),
    }


@lru_cache(maxsize=1)
def _people_detector() -> cv2.HOGDescriptor:
    detector = cv2.HOGDescriptor()
    detector.setSVMDetector(cv2.HOGDescriptor_getDefaultPeopleDetector())
    return detector


def detect_players(frame: np.ndarray) -> list[dict[str, Any]]:
    """Detect people with OpenCV's built-in HOG pedestrian detector."""
    if frame is None or frame.size == 0 or frame.shape[0] < 128 or frame.shape[1] < 64:
        return []

    detector = _people_detector()
    rectangles, weights = detector.detectMultiScale(
        frame,
        hitThreshold=0.0,
        winStride=(8, 8),
        padding=(8, 8),
        scale=1.05,
        finalThreshold=2.0,
    )
    candidates: list[dict[str, Any]] = []
    for rect, weight in zip(rectangles, weights, strict=True):
        x, y, width, height = (int(value) for value in rect)
        if height < 48 or width < 18 or height / max(width, 1) < 1.15:
            continue
        margin = float(weight)
        confidence = 1.0 / (1.0 + math.exp(-max(-20.0, min(20.0, margin))))
        candidates.append(
            {
                "bbox": [x, y, width, height],
                "foot_point": [x + width / 2.0, y + float(height)],
                "confidence": confidence,
                "detector": "opencv_hog_default_people_detector",
            }
        )

    candidates.sort(
        key=lambda item: (
            item["confidence"] * 0.65 + min(1.0, item["bbox"][3] / max(1.0, frame.shape[0])) * 0.35
        ),
        reverse=True,
    )
    return candidates[:8]


def detect_ball_candidate(frame: np.ndarray) -> dict[str, Any] | None:
    """Find a tennis-ball-colour, compact moving-object candidate in the court image."""
    if frame is None or frame.size == 0:
        return None

    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    yellow = cv2.inRange(
        hsv,
        np.array([14, 65, 90], dtype=np.uint8),
        np.array([48, 255, 255], dtype=np.uint8),
    )
    white = cv2.inRange(
        hsv,
        np.array([0, 0, 205], dtype=np.uint8),
        np.array([180, 75, 255], dtype=np.uint8),
    )
    mask = cv2.bitwise_or(yellow, white)
    mask = cv2.morphologyEx(
        mask,
        cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3)),
    )

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    frame_scale = frame.shape[1] / 960.0
    minimum_area = max(2.0, 2.0 * frame_scale * frame_scale)
    maximum_area = max(90.0, 120.0 * frame_scale * frame_scale)

    best: dict[str, Any] | None = None
    for contour in contours:
        area = float(cv2.contourArea(contour))
        if area < minimum_area or area > maximum_area:
            continue
        perimeter = float(cv2.arcLength(contour, True))
        if perimeter <= 0:
            continue
        circularity = 4.0 * math.pi * area / (perimeter * perimeter)
        if circularity < 0.38:
            continue
        (x, y), radius = cv2.minEnclosingCircle(contour)
        if radius < 1.0 * frame_scale or radius > 7.5 * frame_scale:
            continue
        score = min(1.0, circularity) * 0.65 + min(1.0, area / maximum_area) * 0.1
        if best is None or score > best["score"]:
            best = {
                "image_point": [float(x), float(y)],
                "radius_px": float(radius),
                "score": float(score),
                "confidence": min(0.45, float(score) * 0.45),
                "detector": "hsv_compact_blob_candidate",
            }
    return best


def _load_manual_homography(
    metadata: dict[str, Any],
    calibration_id: str | None,
) -> tuple[np.ndarray | None, dict[str, Any] | None]:
    values = metadata.get("calibrations", [])
    if not isinstance(values, list):
        return None, None
    matching = [
        item
        for item in values
        if isinstance(item, dict)
        and (calibration_id is None or item.get("calibration_id") == calibration_id)
    ]
    if not matching:
        return None, None
    selected = matching[-1]
    try:
        matrix = np.asarray(selected["homography_image_to_court"], dtype=float)
        if matrix.shape != (3, 3) or not np.isfinite(matrix).all():
            return None, None
        return matrix, selected
    except (KeyError, TypeError, ValueError):
        return None, None


def _inside_polygon(point: tuple[float, float], polygon: np.ndarray | None) -> bool:
    if polygon is None:
        return True
    return (
        cv2.pointPolygonTest(
            np.asarray(polygon, dtype=np.float32),
            (float(point[0]), float(point[1])),
            False,
        )
        >= 0
    )


def _make_player_summary(
    player_samples: list[dict[str, Any]],
    player_id: str,
    eligible_scans: int,
    court: CourtDimensions,
) -> dict[str, Any]:
    values = [
        item
        for item in player_samples
        if item["player_id"] == player_id and item.get("baseline_distance_m") is not None
    ]
    distances = [float(item["baseline_distance_m"]) for item in values]
    if not distances:
        return {
            "player_id": player_id,
            "samples": 0,
            "average_baseline_distance_m": None,
            "median_baseline_distance_m": None,
            "behind_baseline_pct": None,
            "inside_baseline_pct": None,
            "coverage_pct": 0.0,
        }

    return {
        "player_id": player_id,
        "samples": len(distances),
        "average_baseline_distance_m": float(np.mean(distances)),
        "median_baseline_distance_m": float(np.median(distances)),
        "p10_baseline_distance_m": float(np.percentile(distances, 10)),
        "p90_baseline_distance_m": float(np.percentile(distances, 90)),
        "behind_baseline_pct": 100.0 * sum(value > 0.05 for value in distances) / len(distances),
        "inside_baseline_pct": 100.0 * sum(value < -0.05 for value in distances) / len(distances),
        "on_or_near_baseline_pct": (
            100.0 * sum(abs(value) <= 0.05 for value in distances) / len(distances)
        ),
        "coverage_pct": 100.0 * len(distances) / max(1, eligible_scans),
        "unit": "metres",
    }


def _make_heatmap(
    player_samples: list[dict[str, Any]],
    player_id: str,
    court: CourtDimensions,
    columns: int = 12,
    rows: int = 24,
) -> list[list[int]]:
    bins = np.zeros((rows, columns), dtype=int)
    for item in player_samples:
        if item["player_id"] != player_id:
            continue
        x = item.get("court_x_m")
        y = item.get("court_y_m")
        if x is None or y is None:
            continue
        column = int((float(x) + court.doubles_width_m / 2) / court.doubles_width_m * columns)
        row = int((float(y) + court.half_length_m) / court.length_m * rows)
        if 0 <= column < columns and 0 <= row < rows:
            bins[row, column] += 1
    return bins.tolist()


def _make_candidate_event(
    timestamp: float,
    point: list[float],
    court_point: tuple[float, float] | None,
    previous_velocity: tuple[float, float],
    current_velocity: tuple[float, float],
    index: int,
) -> dict[str, Any]:
    dot = previous_velocity[0] * current_velocity[0] + previous_velocity[1] * current_velocity[1]
    previous_speed = math.hypot(*previous_velocity)
    current_speed = math.hypot(*current_velocity)
    cosine = dot / max(1e-9, previous_speed * current_speed)
    angle = math.degrees(math.acos(max(-1.0, min(1.0, cosine))))
    return {
        "event_id": f"candidate-{index:06d}",
        "event_type": "trajectory_change_candidate",
        "timestamp_seconds": timestamp,
        "image_point": point,
        "court_x_m": court_point[0] if court_point else None,
        "court_y_m": court_point[1] if court_point else None,
        "confidence": 0.2,
        "review_status": "pending",
        "evidence": {
            "direction_change_degrees": angle,
            "previous_speed_px_per_second": previous_speed,
            "current_speed_px_per_second": current_speed,
        },
        "note": (
            "Automatic visual candidate only. Confirm or relabel against the video; "
            "this detector does not establish a racket contact or bounce."
        ),
    }


def run_analysis_job(
    video_path_string: str,
    job_path_string: str,
    config: dict[str, Any],
    video_metadata: dict[str, Any],
) -> None:
    """Run the conservative OpenCV analysis pipeline in a FastAPI background worker."""
    job_path = Path(job_path_string)
    video_path = Path(video_path_string)
    job = _read_json(job_path)
    job["status"] = "running"
    job["progress_pct"] = 0
    job["started_at"] = datetime.now(UTC).isoformat()
    _atomic_json_write(job_path, job)

    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise VideoAnalysisError("OpenCV could not open the uploaded video.")

    fps = float(capture.get(cv2.CAP_PROP_FPS))
    if not math.isfinite(fps) or fps < 1:
        fps = 25.0
    source_width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
    source_height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
    total_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
    duration = total_frames / fps if total_frames > 0 else None
    sample_interval = float(config.get("sample_interval_seconds", DEFAULT_SAMPLE_INTERVAL))
    sample_interval = min(2.0, max(0.1, sample_interval))
    stride = max(1, round(fps * sample_interval))
    maximum_samples = min(
        MAX_ANALYSIS_SAMPLES,
        max(100, int(config.get("max_samples", MAX_ANALYSIS_SAMPLES))),
    )

    court = CourtDimensions()
    manual_matrix, manual_calibration = _load_manual_homography(
        video_metadata,
        config.get("calibration_id"),
    )
    player_samples: list[dict[str, Any]] = []
    ball_trace: list[dict[str, Any]] = []
    events: list[dict[str, Any]] = []
    court_candidate: dict[str, Any] | None = None
    auto_matrix: np.ndarray | None = None
    auto_polygon: np.ndarray | None = None
    previous_ball: tuple[float, float, float] | None = None
    previous_velocity: tuple[float, tuple[float, float]] | None = None
    event_index = 0
    sampled = 0
    eligible_player_scans = 0
    player_scans_with_detections = 0
    last_progress_write = 0.0
    frame_index = 0
    hog_stride = max(1, round(1.0 / sample_interval))

    try:
        while sampled < maximum_samples:
            ok = capture.grab()
            if not ok:
                break
            current_index = frame_index
            frame_index += 1
            if current_index % stride != 0:
                continue

            ok, frame = capture.retrieve()
            if not ok or frame is None:
                continue

            sampled += 1
            timestamp = current_index / fps
            resize_scale = min(1.0, MAX_ANALYSIS_WIDTH / max(1, frame.shape[1]))
            if resize_scale < 1.0:
                small = cv2.resize(
                    frame,
                    None,
                    fx=resize_scale,
                    fy=resize_scale,
                    interpolation=cv2.INTER_AREA,
                )
            else:
                small = frame
            height, width = small.shape[:2]

            if manual_matrix is None and (auto_matrix is None or sampled % 15 == 1):
                candidate = detect_court_candidate(small)
                if candidate is not None:
                    candidate_points = np.asarray(candidate["image_points"], dtype=np.float32)
                    try:
                        auto_matrix = estimate_homography(
                            candidate_points,
                            court.boundary_corners("doubles"),
                        )
                        auto_polygon = candidate_points
                        court_candidate = candidate
                    except ValueError:
                        pass

            matrix = manual_matrix if manual_matrix is not None else auto_matrix
            matrix_is_manual = manual_matrix is not None

            if sampled % hog_stride == 1 or sampled == 1:
                eligible_player_scans += 1
                detections = detect_players(small)
                valid_detections: list[dict[str, Any]] = []
                for detection in detections:
                    foot = detection["foot_point"]
                    if not _inside_polygon((foot[0], foot[1]), auto_polygon):
                        continue

                    court_point = None
                    baseline_distance = None
                    player_end = "near" if foot[1] >= height / 2 else "far"
                    if matrix is not None:
                        if matrix_is_manual:
                            original_point = (foot[0] / resize_scale, foot[1] / resize_scale)
                            court_point = project_point(original_point, matrix)
                        else:
                            court_point = project_point((foot[0], foot[1]), matrix)
                        player_end = "near" if court_point[1] >= 0 else "far"
                        baseline_distance = signed_baseline_distance_m(
                            court_point, player_end, court
                        )

                    valid_detections.append(
                        {
                            **detection,
                            "court_point": court_point,
                            "baseline_distance_m": baseline_distance,
                            "player_end": player_end,
                        }
                    )

                # Keep the highest-confidence candidate on each court end. HOG's
                # pedestrian detector is a generic person detector, not tennis-specific.
                selected_by_end: dict[str, dict[str, Any]] = {}
                for detection in valid_detections:
                    end = detection["player_end"]
                    current = selected_by_end.get(end)
                    if current is None or detection["confidence"] > current["confidence"]:
                        selected_by_end[end] = detection

                if selected_by_end:
                    player_scans_with_detections += 1
                for end, detection in selected_by_end.items():
                    foot = detection["foot_point"]
                    court_point = detection["court_point"]
                    player_samples.append(
                        {
                            "timestamp_seconds": timestamp,
                            "player_id": f"{end}_player",
                            "player_end": end,
                            "image_point": [
                                float(foot[0] / resize_scale),
                                float(foot[1] / resize_scale),
                            ],
                            "court_x_m": court_point[0] if court_point else None,
                            "court_y_m": court_point[1] if court_point else None,
                            "baseline_distance_m": detection["baseline_distance_m"],
                            "confidence": detection["confidence"],
                            "detector": detection["detector"],
                            "review_status": "automatic_unverified",
                        }
                    )

            if bool(config.get("enable_ball_tracking", True)):
                ball = detect_ball_candidate(small)
                if ball is not None:
                    image_point = ball["image_point"]
                    court_point = None
                    if matrix is not None:
                        if matrix_is_manual:
                            court_point = project_point(
                                (image_point[0] / resize_scale, image_point[1] / resize_scale),
                                matrix,
                            )
                        else:
                            court_point = project_point((image_point[0], image_point[1]), matrix)

                    trace_item = {
                        "timestamp_seconds": timestamp,
                        "image_point": [
                            float(image_point[0] / resize_scale),
                            float(image_point[1] / resize_scale),
                        ],
                        "court_x_m": court_point[0] if court_point else None,
                        "court_y_m": court_point[1] if court_point else None,
                        "radius_px": float(ball["radius_px"] / resize_scale),
                        "confidence": float(ball["confidence"]),
                        "detector": ball["detector"],
                        "review_status": "automatic_unverified",
                    }
                    ball_trace.append(trace_item)

                    if previous_ball is not None:
                        previous_time, previous_x, previous_y = previous_ball
                        elapsed = timestamp - previous_time
                        displacement = math.hypot(
                            image_point[0] - previous_x,
                            image_point[1] - previous_y,
                        )
                        if elapsed > 0 and displacement < 120.0:
                            velocity = (
                                (image_point[0] - previous_x) / elapsed,
                                (image_point[1] - previous_y) / elapsed,
                            )
                            if previous_velocity is not None:
                                prior_timestamp, prior_velocity_value = previous_velocity
                                if timestamp - prior_timestamp <= 1.5:
                                    dot = (
                                        prior_velocity_value[0] * velocity[0]
                                        + prior_velocity_value[1] * velocity[1]
                                    )
                                    cosine = dot / max(
                                        1e-9,
                                        math.hypot(*prior_velocity_value) * math.hypot(*velocity),
                                    )
                                    if cosine < -0.2:
                                        event_index += 1
                                        events.append(
                                            _make_candidate_event(
                                                timestamp,
                                                trace_item["image_point"],
                                                court_point,
                                                prior_velocity_value,
                                                velocity,
                                                event_index,
                                            )
                                        )
                            previous_velocity = (timestamp, velocity)
                    previous_ball = (timestamp, float(image_point[0]), float(image_point[1]))

            now = time.monotonic()
            if sampled % 90 == 0 or now - last_progress_write > 5.0:
                job["progress_pct"] = (
                    min(99, int(100 * current_index / max(1, total_frames - 1)))
                    if total_frames > 0
                    else min(99, sampled % 100)
                )
                job["processed_samples"] = sampled
                job["player_scans"] = eligible_player_scans
                _atomic_json_write(job_path, job)
                last_progress_write = now
    finally:
        capture.release()

    if sampled == 0:
        raise VideoAnalysisError("No decodable frames were found in the uploaded video.")

    players = {
        "near_player": _make_player_summary(
            player_samples, "near_player", eligible_player_scans, court
        ),
        "far_player": _make_player_summary(
            player_samples, "far_player", eligible_player_scans, court
        ),
    }
    heatmaps = {
        player_id: _make_heatmap(player_samples, player_id, court)
        for player_id in ("near_player", "far_player")
    }

    if manual_calibration is not None:
        calibration = {
            "mode": "manual",
            "calibration_id": manual_calibration.get("calibration_id"),
            "confidence": None,
            "requires_confirmation": False,
        }
    elif court_candidate is not None:
        calibration = {
            "mode": "automatic_candidate",
            "calibration_id": None,
            "confidence": court_candidate["confidence"],
            "requires_confirmation": True,
            "candidate": court_candidate,
        }
    else:
        calibration = {
            "mode": "unavailable",
            "confidence": 0.0,
            "requires_confirmation": True,
        }

    job["status"] = "completed"
    job["progress_pct"] = 100
    job["completed_at"] = datetime.now(UTC).isoformat()
    job["summary"] = {
        "source": {
            "width": source_width,
            "height": source_height,
            "fps": fps,
            "duration_seconds": duration,
            "sampled_frames": sampled,
            "sample_interval_seconds": sample_interval,
            "max_samples_reached": sampled >= maximum_samples,
        },
        "calibration": calibration,
        "player_detector": "OpenCV default HOG pedestrian detector",
        "player_scans": eligible_player_scans,
        "player_scans_with_detections": player_scans_with_detections,
        "player_detection_coverage_pct": (
            100.0 * player_scans_with_detections / max(1, eligible_player_scans)
        ),
        "players": players,
        "position_heatmaps": heatmaps,
        "position_samples": player_samples,
        "ball_detector": "HSV compact-blob candidate detector",
        "ball_candidates": len(ball_trace),
        "ball_trace": ball_trace,
        "candidate_event_count": len(events),
        "reviewed_events": 0,
        "analytics_reliability": (
            "Player and ball observations are unverified computer-vision candidates. "
            "Generic HOG and colour-blob detection are not tennis-specific models. "
            "Do not treat candidate events, player identities or statistics as official "
            "match data without human review."
        ),
        "units": {"position": "metres", "time": "seconds"},
    }
    job["events"] = events
    _atomic_json_write(job_path, job)
