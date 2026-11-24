from __future__ import annotations

import math
from collections import Counter, defaultdict
from statistics import mean, median
from typing import Any

from app.services.court_geometry import CourtDimensions

COURT = CourtDimensions()
SINGLES_HALF_WIDTH_M = COURT.singles_width_m / 2
SERVICE_LINE_FROM_NET_M = 6.40


def _accepted_events(job: dict[str, Any]) -> list[dict[str, Any]]:
    raw = job.get("events", [])
    if not isinstance(raw, list):
        return []
    result = [
        event
        for event in raw
        if isinstance(event, dict)
        and event.get("review_status") == "accepted"
        and isinstance(event.get("timestamp_seconds"), (int, float))
        and math.isfinite(float(event["timestamp_seconds"]))
    ]
    return sorted(result, key=lambda event: float(event["timestamp_seconds"]))


def _positions(items: list[dict[str, Any]]) -> list[dict[str, float]]:
    return [
        {"x_m": float(item["court_x_m"]), "y_m": float(item["court_y_m"])}
        for item in items
        if isinstance(item.get("court_x_m"), (int, float))
        and isinstance(item.get("court_y_m"), (int, float))
        and math.isfinite(float(item["court_x_m"]))
        and math.isfinite(float(item["court_y_m"]))
    ]


def _serve_zone(event: dict[str, Any]) -> str | None:
    """Coarse descriptive zone; not a rules-level adjudication of a service target."""
    x = event.get("court_x_m")
    y = event.get("court_y_m")
    if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
        return None
    x_value, y_value = float(x), float(y)
    if abs(x_value) > SINGLES_HALF_WIDTH_M or abs(y_value) > SERVICE_LINE_FROM_NET_M:
        return None
    distance = abs(x_value)
    if distance <= 1.35:
        return "T"
    if distance <= 2.75:
        return "body"
    return "wide"


def _shot_depth(event: dict[str, Any]) -> str | None:
    explicit = event.get("shot_depth")
    if explicit in {"short", "mid_court", "deep"}:
        return str(explicit)
    y = event.get("court_y_m")
    if not isinstance(y, (int, float)) or not math.isfinite(float(y)):
        return None
    distance_from_baseline = COURT.half_length_m - abs(float(y))
    if distance_from_baseline < 0:
        return None
    if distance_from_baseline >= 7.5:
        return "short"
    if distance_from_baseline >= 3.5:
        return "mid_court"
    return "deep"


def _count_by(items: list[dict[str, Any]], field: str, allowed: set[str]) -> dict[str, int]:
    counter: Counter[str] = Counter()
    for item in items:
        value = item.get(field)
        if value is None:
            continue
        normalized = str(value)
        if normalized in allowed:
            counter[normalized] += 1
    return {key: counter.get(key, 0) for key in sorted(allowed)}


def _rally_sequences(events: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], int]:
    """Pair reviewed point markers sequentially; never reuse one point-end for two starts."""
    sequences: list[dict[str, Any]] = []
    incomplete = 0
    active: dict[str, Any] | None = None

    for event in events:
        event_type = event.get("event_type")
        timestamp = float(event["timestamp_seconds"])
        if event_type == "point_start":
            if active is not None:
                incomplete += 1
            active = {
                "point_id": event.get("point_id"),
                "start_seconds": timestamp,
                "end_seconds": None,
                "contacts": [],
            }
        elif event_type == "racket_contact" and active is not None:
            active["contacts"].append(
                {
                    "timestamp_seconds": timestamp,
                    "player_id": event.get("player_id", "unknown"),
                    "contact_index": event.get("rally_contact_index"),
                }
            )
        elif (
            event_type == "point_end"
            and active is not None
            and timestamp >= active["start_seconds"]
        ):
            active["end_seconds"] = timestamp
            sequences.append(active)
            active = None

    if active is not None:
        incomplete += 1
    completed = [
        {
            "point_id": item.get("point_id"),
            "start_seconds": item["start_seconds"],
            "end_seconds": item["end_seconds"],
            "duration_seconds": item["end_seconds"] - item["start_seconds"],
            "contact_count": len(item["contacts"]),
            "contacts": item["contacts"],
        }
        for item in sequences
    ]
    return completed, incomplete


def calculate_reviewed_match_metrics(job: dict[str, Any]) -> dict[str, Any]:
    """Derive descriptive metrics only from events explicitly accepted by a human reviewer."""
    events = _accepted_events(job)
    all_events = job.get("events", [])
    total_event_count = len(all_events) if isinstance(all_events, list) else 0

    serve_events = [
        event for event in events if event.get("event_type") in {"serve_attempt", "serve_bounce"}
    ]
    serve_bounces = [event for event in events if event.get("event_type") == "serve_bounce"]
    shot_bounces = [event for event in events if event.get("event_type") == "shot_bounce"]

    serve_numbers = _count_by(serve_events, "serve_number", {"1", "2"})
    serve_results = _count_by(
        serve_events,
        "serve_result",
        {"in", "fault", "ace", "double_fault", "let"},
    )
    serve_zone_counts = Counter(
        zone for event in serve_bounces if (zone := _serve_zone(event)) is not None
    )
    serve_zone_map = {zone: serve_zone_counts.get(zone, 0) for zone in ("T", "body", "wide")}

    serves_with_known_result = [
        event
        for event in serve_events
        if event.get("serve_result") in {"in", "fault", "ace", "double_fault"}
    ]
    successful_serves = sum(
        event.get("serve_result") in {"in", "ace"} for event in serves_with_known_result
    )
    serve_in_percentage = (
        100.0 * successful_serves / len(serves_with_known_result)
        if serves_with_known_result
        else None
    )

    shot_depth_counts = Counter(
        depth for event in shot_bounces if (depth := _shot_depth(event)) is not None
    )
    shot_direction_counts = _count_by(
        shot_bounces,
        "shot_direction",
        {"crosscourt", "down_the_line", "middle", "unknown"},
    )
    shot_depth_map = {
        depth: shot_depth_counts.get(depth, 0) for depth in ("short", "mid_court", "deep")
    }

    rallies, incomplete_points = _rally_sequences(events)
    rally_lengths = [int(point["contact_count"]) for point in rallies]

    by_player: dict[str, dict[str, int]] = defaultdict(
        lambda: {
            "winners": 0,
            "forced_errors": 0,
            "unforced_errors": 0,
            "serve_plus_one": 0,
            "return_plus_one": 0,
        }
    )
    for event in events:
        player_id = event.get("player_id", "unknown")
        if player_id not in {"near_player", "far_player", "unknown"}:
            player_id = "unknown"
        event_type = event.get("event_type")
        mapping = {
            "winner": "winners",
            "forced_error": "forced_errors",
            "unforced_error": "unforced_errors",
            "serve_plus_one": "serve_plus_one",
            "return_plus_one": "return_plus_one",
        }
        if event_type in mapping:
            by_player[player_id][mapping[event_type]] += 1

    winners = sum(value["winners"] for value in by_player.values())
    forced_errors = sum(value["forced_errors"] for value in by_player.values())
    unforced_errors = sum(value["unforced_errors"] for value in by_player.values())

    return {
        "accepted_event_count": len(events),
        "pending_or_rejected_event_count": max(0, total_event_count - len(events)),
        "review_coverage_pct": (
            100.0 * len(events) / total_event_count if total_event_count else 0.0
        ),
        "serve_placement": {
            "confirmed_serves": len(serve_bounces),
            "recorded_attempts": len(serve_events),
            "first_serve_events": serve_numbers["1"],
            "second_serve_events": serve_numbers["2"],
            "serve_results": serve_results,
            "known_result_attempts": len(serves_with_known_result),
            "serve_in_percentage": serve_in_percentage,
            "zones": serve_zone_map,
            "positions": _positions(serve_bounces),
            "zone_definition": (
                "Coarse x-coordinate bands within singles width and service-box depth"
            ),
            "status": "based_on_human_review",
        },
        "shot_placement": {
            "confirmed_bounces": len(shot_bounces),
            "depths": shot_depth_map,
            "directions": shot_direction_counts,
            "positions": _positions(shot_bounces),
            "depth_definition": "Baseline distance >=7.5 m short, 3.5-7.5 m mid-court, <3.5 m deep",
            "status": "based_on_human_review",
        },
        "rally_length": {
            "completed_points": len(rallies),
            "incomplete_points": incomplete_points,
            "contact_counts": rally_lengths,
            "average_contacts_per_point": mean(rally_lengths) if rally_lengths else None,
            "median_contacts_per_point": median(rally_lengths) if rally_lengths else None,
            "sequences": rallies,
            "status": "requires_reviewed_point_and_contact_labels",
        },
        "serve_plus_one": {
            "reviewed_count": sum(value["serve_plus_one"] for value in by_player.values()),
            "player_labels": {
                player: value["serve_plus_one"] for player, value in by_player.items()
            },
        },
        "return_plus_one": {
            "reviewed_count": sum(value["return_plus_one"] for value in by_player.values()),
            "player_labels": {
                player: value["return_plus_one"] for player, value in by_player.items()
            },
        },
        "winner_error_classification": {
            "status": "based_on_human_review",
            "winners": winners,
            "forced_errors": forced_errors,
            "unforced_errors": unforced_errors,
            "by_player": dict(by_player),
        },
    }
