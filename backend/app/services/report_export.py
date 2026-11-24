import html

# ruff: noqa: E501
from typing import Any

from app.services.court_geometry import CourtDimensions


def render_analysis_svg(job: dict[str, Any]) -> str:
    summary = job.get("summary", {})
    if not isinstance(summary, dict):
        summary = {}
    players = summary.get("players", {})
    if not isinstance(players, dict):
        players = {}
    heatmaps = summary.get("position_heatmaps", {})
    if not isinstance(heatmaps, dict):
        heatmaps = {}

    court = CourtDimensions()
    court_left, court_top, court_width, court_height = 120, 120, 360, 720
    scale_x = court_width / court.doubles_width_m
    scale_y = court_height / court.length_m

    def to_x(value: float) -> float:
        return court_left + (value + court.doubles_width_m / 2) * scale_x

    def to_y(value: float) -> float:
        return court_top + (court.half_length_m - value) * scale_y

    def metric(player_id: str, field: str) -> str:
        item = players.get(player_id, {})
        if not isinstance(item, dict) or item.get(field) is None:
            return "N/A"
        return f"{float(item[field]):.2f}"

    cells: list[str] = []
    for player_id in ("near_player", "far_player"):
        grid = heatmaps.get(player_id, [])
        if not isinstance(grid, list):
            continue
        for row_index, row in enumerate(grid):
            if not isinstance(row, list):
                continue
            for column_index, raw_value in enumerate(row):
                value = int(raw_value)
                if value <= 0:
                    continue
                x = court_left + column_index * court_width / max(1, len(row))
                y = court_top + row_index * court_height / max(1, len(grid))
                alpha = min(0.72, 0.08 + value / max(1, sum(sum(int(v) for v in r) for r in grid)) * 2.5)
                cells.append(
                    f'<rect x="{x:.1f}" y="{y:.1f}" width="{court_width / max(1, len(row)):.1f}" '
                    f'height="{court_height / max(1, len(grid)):.1f}" fill="#0E8A63" '
                    f'fill-opacity="{alpha:.3f}"/>'
                )

    disclaimer = (
        "Automatic detections are unverified candidates. Confirm positions and event labels "
        "before treating metrics as match statistics."
    )
    video_id = html.escape(str(job.get("video_id", "")))
    created = html.escape(str(job.get("completed_at", "")))
    near_avg = metric("near_player", "average_baseline_distance_m")
    far_avg = metric("far_player", "average_baseline_distance_m")

    lines = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="900" height="1080" viewBox="0 0 900 1080">',
        '<rect width="900" height="1080" fill="#f7f9f8"/>',
        '<text x="56" y="66" font-family="Arial" font-size="30" font-weight="700" fill="#142b24">Tennis Video Intelligence</text>',
        '<text x="56" y="98" font-family="Arial" font-size="14" fill="#5d7068">Match positioning report · court coordinates in metres</text>',
        f'<text x="56" y="130" font-family="Arial" font-size="12" fill="#5d7068">Video: {video_id} · Generated: {created}</text>',
        f'<rect x="{court_left}" y="{court_top}" width="{court_width}" height="{court_height}" fill="#147E5E" stroke="#ffffff" stroke-width="3"/>',
        *cells,
        f'<line x1="{court_left}" y1="{to_y(0):.1f}" x2="{court_left + court_width}" y2="{to_y(0):.1f}" stroke="#ffffff" stroke-width="2"/>',
        f'<line x1="{court_left + (court_width - 2 * 4.115 * scale_x) / 2:.1f}" y1="{court_top}" '
        f'x2="{court_left + (court_width + 2 * 4.115 * scale_x) / 2:.1f}" y2="{court_top + court_height}" '
        'stroke="#ffffff" stroke-width="1.5"/>',
        f'<line x1="{court_left}" y1="{to_y(6.4):.1f}" x2="{court_left + court_width}" y2="{to_y(6.4):.1f}" stroke="#ffffff" stroke-width="1.3"/>',
        f'<line x1="{court_left}" y1="{to_y(-6.4):.1f}" x2="{court_left + court_width}" y2="{to_y(-6.4):.1f}" stroke="#ffffff" stroke-width="1.3"/>',
        f'<line x1="{court_left}" y1="{court_top + court_height / 2:.1f}" x2="{court_left + court_width}" y2="{court_top + court_height / 2:.1f}" stroke="#ffffff" stroke-width="2"/>',
        '<text x="550" y="190" font-family="Arial" font-size="17" font-weight="700" fill="#142b24">Baseline positioning</text>',
        '<text x="550" y="236" font-family="Arial" font-size="14" fill="#5d7068">Near player average</text>',
        f'<text x="550" y="268" font-family="Arial" font-size="26" font-weight="700" fill="#0E8A63">{near_avg} m</text>',
        '<text x="550" y="322" font-family="Arial" font-size="14" fill="#5d7068">Far player average</text>',
        f'<text x="550" y="354" font-family="Arial" font-size="26" font-weight="700" fill="#0E8A63">{far_avg} m</text>',
        '<text x="550" y="420" font-family="Arial" font-size="12" fill="#5d7068">Positive = behind baseline</text>',
        '<text x="550" y="442" font-family="Arial" font-size="12" fill="#5d7068">Negative = inside court</text>',
        '<text x="56" y="920" font-family="Arial" font-size="17" font-weight="700" fill="#142b24">Reliability and coverage</text>',
        f'<text x="56" y="954" font-family="Arial" font-size="13" fill="#5d7068">{html.escape(disclaimer)}</text>',
        '<text x="56" y="1010" font-family="Arial" font-size="11" fill="#5d7068">Court outline and player heatmaps depend on camera angle, court detection and calibration.</text>',
        '</svg>',
    ]
    return "\n".join(lines)
