from typing import Literal

from pydantic import BaseModel, Field


class AnalysisRequest(BaseModel):
    sample_interval_seconds: float = Field(default=0.333, ge=0.1, le=2.0)
    max_samples: int = Field(default=32_000, ge=100, le=32_000)
    calibration_id: str | None = None
    enable_ball_tracking: bool = True


class CourtDetectionResponse(BaseModel):
    video_id: str
    timestamp_seconds: float
    candidate: dict[str, object] | None
    requires_user_confirmation: bool = True


class EventReviewRequest(BaseModel):
    review_status: Literal["accepted", "rejected"]
    event_type: Literal[
        "serve_bounce",
        "shot_bounce",
        "racket_contact",
        "point_start",
        "point_end",
        "net_contact",
        "manual_note",
    ] | None = None
    court_x_m: float | None = Field(default=None, ge=-20, le=20)
    court_y_m: float | None = Field(default=None, ge=-30, le=30)
    player_id: Literal["near_player", "far_player", "unknown"] = "unknown"
    note: str | None = Field(default=None, max_length=500)


class AnalysisJobResponse(BaseModel):
    analysis_id: str
    video_id: str
    status: Literal["queued", "running", "completed", "failed"]
    progress_pct: int = 0
    processed_samples: int = 0
    summary: dict[str, object] | None = None
    error: str | None = None
    created_at: str
    started_at: str | None = None
    completed_at: str | None = None
