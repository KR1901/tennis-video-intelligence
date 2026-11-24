from __future__ import annotations

from typing import Any

import cv2
import numpy as np

from app.services.analysis_metrics import calculate_reviewed_match_metrics
from app.services.court_geometry import CourtDimensions

COURT = CourtDimensions()


def _text(
    image: np.ndarray,
    value: object,
    x: int,
    y: int,
    scale: float = 0.55,
    color: tuple[int, int, int] = (42, 58, 49),
    thickness: int = 1,
) -> None:
    cv2.putText(
        image,
        str(value)[:120],
        (x, y),
        cv2.FONT_HERSHEY_SIMPLEX,
        scale,
        color,
        thickness,
        cv2.LINE_AA,
    )


def render_analysis_png(job: dict[str, Any]) -> bytes:
    width, height = 1200, 1500
    canvas = np.full((height, width, 3), (247, 249, 248), dtype=np.uint8)
    summary = job.get("summary") if isinstance(job.get("summary"), dict) else {}
    players = summary.get("players") if isinstance(summary.get("players"), dict) else {}
    heatmaps = (
        summary.get("position_heatmaps")
        if isinstance(summary.get("position_heatmaps"), dict)
        else {}
    )
    reviewed = calculate_reviewed_match_metrics(job)

    _text(canvas, "TENNIS VIDEO INTELLIGENCE", 60, 76, 1.05, (35, 51, 42), 2)
    _text(canvas, "Court positioning and reviewed-event report", 60, 116, 0.65)
    _text(
        canvas,
        f"Video: {job.get('video_id', 'unknown')}  |  Analysis: {job.get('analysis_id', 'unknown')}",
        60,
        152,
        0.48,
    )
    _text(canvas, f"Completed: {job.get('completed_at', 'unknown')}", 60, 180, 0.48)

    court_left, court_top, court_width, court_height = 90, 245, 480, 940
    court_right = court_left + court_width
    court_bottom = court_top + court_height
    cv2.rectangle(canvas, (court_left, court_top), (court_right, court_bottom), (89, 125, 30), -1)

    for player_id in ("near_player", "far_player"):
        grid = heatmaps.get(player_id, [])
        if not isinstance(grid, list) or not grid:
            continue
        values = np.asarray(grid, dtype=np.float32)
        if values.ndim != 2 or values.size == 0:
            continue
        maximum = max(1.0, float(values.max()))
        for row in range(values.shape[0]):
            for column in range(values.shape[1]):
                value = float(values[row, column])
                if value <= 0:
                    continue
                alpha = min(0.72, 0.12 + value / maximum * 0.55)
                x1 = court_left + int(column * court_width / values.shape[1])
                x2 = court_left + int((column + 1) * court_width / values.shape[1])
                y1 = court_top + int(row * court_height / values.shape[0])
                y2 = court_top + int((row + 1) * court_height / values.shape[0])
                overlay = canvas.copy()
                cv2.rectangle(overlay, (x1, y1), (x2, y2), (40, 215, 240), -1)
                cv2.addWeighted(overlay, alpha, canvas, 1.0 - alpha, 0, canvas)

    white = (255, 255, 255)
    cv2.rectangle(canvas, (court_left, court_top), (court_right, court_bottom), white, 4)
    net_y = (court_top + court_bottom) // 2
    cv2.line(canvas, (court_left, net_y), (court_right, net_y), white, 3)
    singles_offset = int(
        court_width
        * (COURT.doubles_width_m - COURT.singles_width_m)
        / (2 * COURT.doubles_width_m)
    )
    cv2.line(
        canvas,
        (court_left + singles_offset, court_top),
        (court_left + singles_offset, court_bottom),
        white,
        2,
    )
    cv2.line(
        canvas,
        (court_right - singles_offset, court_top),
        (court_right - singles_offset, court_bottom),
        white,
        2,
    )
    service_offset = int(court_height * 6.4 / COURT.length_m)
    cv2.line(
        canvas,
        (court_left, court_top + service_offset),
        (court_right, court_top + service_offset),
        white,
        2,
    )
    cv2.line(
        canvas,
        (court_left, court_bottom - service_offset),
        (court_right, court_bottom - service_offset),
        white,
        2,
    )
    center_x = court_left + court_width // 2
    cv2.line(
        canvas,
        (center_x, court_top + service_offset),
        (center_x, court_bottom - service_offset),
        white,
        2,
    )
    _text(canvas, "FAR BASELINE", court_left, court_top - 18, 0.45)
    _text(canvas, "NEAR BASELINE", court_left, court_bottom + 30, 0.45)

    panel_x = 630
    _text(canvas, "BASELINE POSITIONING", panel_x, 270, 0.72, (35, 51, 42), 2)
    y = 320
    for key, label in (("near_player", "Near-end occupant"), ("far_player", "Far-end occupant")):
        item = players.get(key, {})
        if not isinstance(item, dict):
            item = {}
        average = item.get("average_baseline_distance_m")
        display = "N/A" if average is None else f"{float(average):+.2f} m"
        _text(canvas, label, panel_x, y, 0.53)
        _text(canvas, display, panel_x, y + 44, 0.9, (36, 140, 105), 2)
        coverage = float(item.get("coverage_pct", 0.0))
        _text(
            canvas,
            f"Samples: {item.get('samples', 0)}  |  Coverage: {coverage:.1f}%",
            panel_x,
            y + 75,
            0.43,
        )
        y += 145

    _text(canvas, "REVIEWED EVENT STATISTICS", panel_x, y, 0.65, (35, 51, 42), 2)
    y += 42
    serve = reviewed["serve_placement"]
    shot = reviewed["shot_placement"]
    rally = reviewed["rally_length"]
    winner_error = reviewed["winner_error_classification"]
    lines = [
        (
            "Serve attempts / in-rate",
            f"{serve['recorded_attempts']} / "
            f"{serve['serve_in_percentage'] if serve['serve_in_percentage'] is not None else 'N/A'}",
        ),
        (
            "First / second serves",
            f"{serve['first_serve_events']} / {serve['second_serve_events']}",
        ),
        (
            "Serve zones T / body / wide",
            f"{serve['zones']['T']} / {serve['zones']['body']} / {serve['zones']['wide']}",
        ),
        (
            "Shot depth short / mid / deep",
            f"{shot['depths']['short']} / {shot['depths']['mid_court']} / {shot['depths']['deep']}",
        ),
        (
            "Completed / incomplete points",
            f"{rally['completed_points']} / {rally['incomplete_points']}",
        ),
        (
            "Mean rally contacts",
            rally["average_contacts_per_point"]
            if rally["average_contacts_per_point"] is not None
            else "N/A",
        ),
        (
            "Winners / forced / unforced",
            f"{winner_error['winners']} / {winner_error['forced_errors']} / "
            f"{winner_error['unforced_errors']}",
        ),
    ]
    for label, value in lines:
        _text(canvas, label, panel_x, y, 0.43)
        _text(canvas, value, panel_x, y + 25, 0.54, (36, 140, 105), 1)
        y += 66

    footer_y = 1335
    _text(canvas, "RELIABILITY", 60, footer_y, 0.58, (35, 51, 42), 2)
    _text(
        canvas,
        "CV player/ball proposals remain unverified. Confirm court calibration and event labels.",
        60,
        footer_y + 34,
        0.46,
    )
    _text(
        canvas,
        f"Human-accepted events: {reviewed['accepted_event_count']}  |  "
        f"Review coverage: {reviewed['review_coverage_pct']:.1f}%",
        60,
        footer_y + 62,
        0.46,
    )
    _text(
        canvas,
        "Positive baseline distance = behind baseline; negative = inside court. Distances are metres.",
        60,
        footer_y + 90,
        0.43,
    )

    ok, encoded = cv2.imencode(".png", canvas)
    if not ok:
        raise RuntimeError("Could not encode analysis report PNG.")
    return encoded.tobytes()


def render_analysis_pdf(job: dict[str, Any]) -> bytes:
    """Create a one-page PDF containing the generated PNG as an embedded JPEG."""
    png_bytes = render_analysis_png(job)
    image = cv2.imdecode(np.frombuffer(png_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
    ok, jpeg = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, 92])
    if not ok:
        raise RuntimeError("Could not encode analysis report image for PDF.")

    image_bytes = jpeg.tobytes()
    height, width = image.shape[:2]
    content = f"q\n{width} 0 0 {height} 0 0 cm\n/Im0 Do\nQ\n".encode("ascii")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {width} {height}] "
            "/Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>"
        ).encode("ascii"),
        (
            f"<< /Type /XObject /Subtype /Image /Width {width} /Height {height} "
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode "
            f"/Length {len(image_bytes)} >>\nstream\n"
        ).encode("ascii")
        + image_bytes
        + b"\nendstream",
        f"<< /Length {len(content)} >>\nstream\n".encode("ascii") + content + b"endstream",
    ]

    pdf = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for number, body in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf.extend(f"{number} 0 obj\n".encode("ascii"))
        pdf.extend(body)
        pdf.extend(b"\nendobj\n")
    xref_offset = len(pdf)
    pdf.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    pdf.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        pdf.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    pdf.extend(
        (
            f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_offset}\n%%EOF\n"
        ).encode("ascii")
    )
    return bytes(pdf)
