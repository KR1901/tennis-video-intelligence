import json
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID, uuid4

import cv2
import numpy as np
from fastapi import APIRouter, BackgroundTasks, HTTPException, Query, Response, status

from app.core.config import get_settings
from app.schemas.analysis import (
    AnalysisJobResponse,
    AnalysisRequest,
    CourtDetectionResponse,
    EventReviewRequest,
    ManualEventCreateRequest,
)
from app.services.analysis_metrics import calculate_reviewed_match_metrics
from app.services.frame_extract import extract_video_frame
from app.services.report_export import render_analysis_svg
from app.services.report_raster import render_analysis_pdf, render_analysis_png
from app.services.vision_analysis import (
    detect_court_candidate,
    run_analysis_job,
)

router = APIRouter(tags=["analysis"])


def _video_paths(video_id: str) -> tuple[Path, dict[str, object]]:
    try:
        UUID(video_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail="Video not found.") from exc

    root = Path(get_settings().storage_dir)
    metadata_path = root / f"{video_id}.json"
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=404, detail="Video not found.") from exc

    if not isinstance(metadata, dict):
        raise HTTPException(status_code=404, detail="Video not found.")
    filename = Path(str(metadata.get("filename", ""))).name
    suffix = Path(filename).suffix.lower()
    if suffix not in {".mp4", ".mov", ".mkv", ".webm", ".avi"}:
        raise HTTPException(status_code=404, detail="Video not found.")
    video_path = root / f"{video_id}{suffix}"
    if not video_path.is_file():
        raise HTTPException(status_code=404, detail="Video file not found.")
    return video_path, metadata


def _job_path(analysis_id: str) -> Path:
    try:
        UUID(analysis_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail="Analysis not found.") from exc
    root = Path(get_settings().storage_dir) / "analyses"
    path = root / f"{analysis_id}.json"
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Analysis not found.")
    return path


def _read_job(path: Path) -> dict[str, object]:
    try:
        job = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=500, detail="Could not read analysis job.") from exc
    if not isinstance(job, dict):
        raise HTTPException(status_code=500, detail="Analysis job is malformed.")
    return job


def _write_job(path: Path, job: dict[str, object]) -> None:
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(job, indent=2), encoding="utf-8")
    temporary.replace(path)


def _run_safely(
    video_path: str,
    job_path: str,
    config: dict[str, object],
    metadata: dict[str, object],
) -> None:
    path = Path(job_path)
    try:
        run_analysis_job(video_path, job_path, config, metadata)
    except Exception as exc:
        try:
            job = _read_job(path)
            job["status"] = "failed"
            job["error"] = str(exc)[:1000]
            job["completed_at"] = datetime.now(UTC).isoformat()
            _write_job(path, job)
        except Exception:
            # The background task has no HTTP request left to report against.
            # Keep the original exception out of the response body.
            pass


@router.post(
    "/videos/{video_id}/court-detection",
    response_model=CourtDetectionResponse,
    summary="Suggest court corners from a selected video frame",
)
async def detect_video_court(
    video_id: str,
    timestamp_seconds: float = Query(default=10.0, ge=0),
) -> CourtDetectionResponse:
    video_path, metadata = _video_paths(video_id)
    media_info = metadata.get("media_info")
    if isinstance(media_info, dict):
        duration = media_info.get("duration_seconds")
        if duration is not None and timestamp_seconds > float(duration):
            raise HTTPException(status_code=422, detail="Timestamp is beyond the video duration.")
    try:
        encoded = extract_video_frame(video_path, timestamp_seconds)
        frame = cv2.imdecode(np.frombuffer(encoded, dtype=np.uint8), cv2.IMREAD_COLOR)
        candidate = detect_court_candidate(frame)
    except (ValueError, OSError, RuntimeError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return CourtDetectionResponse(
        video_id=video_id,
        timestamp_seconds=timestamp_seconds,
        candidate=candidate,
        requires_user_confirmation=True,
    )


@router.post(
    "/videos/{video_id}/analyses",
    response_model=AnalysisJobResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Start asynchronous court, player and ball candidate analysis",
)
async def create_analysis(
    video_id: str,
    background_tasks: BackgroundTasks,
    request: AnalysisRequest,
) -> AnalysisJobResponse:
    video_path, metadata = _video_paths(video_id)
    analysis_id = str(uuid4())
    created_at = datetime.now(UTC).isoformat()
    analysis_root = Path(get_settings().storage_dir) / "analyses"
    analysis_root.mkdir(parents=True, exist_ok=True)
    path = analysis_root / f"{analysis_id}.json"
    job: dict[str, object] = {
        "analysis_id": analysis_id,
        "video_id": video_id,
        "status": "queued",
        "progress_pct": 0,
        "processed_samples": 0,
        "summary": None,
        "events": [],
        "created_at": created_at,
        "request": request.model_dump(),
    }
    _write_job(path, job)
    background_tasks.add_task(
        _run_safely,
        str(video_path),
        str(path),
        request.model_dump(),
        metadata,
    )
    return AnalysisJobResponse(
        analysis_id=analysis_id,
        video_id=video_id,
        status="queued",
        progress_pct=0,
        processed_samples=0,
        created_at=created_at,
    )


@router.get(
    "/analyses/{analysis_id}",
    response_model=AnalysisJobResponse,
    summary="Get analysis progress and positioning results",
)
async def get_analysis(analysis_id: str) -> AnalysisJobResponse:
    job = _read_job(_job_path(analysis_id))
    summary = job.get("summary")
    if isinstance(summary, dict):
        summary["reviewed_metrics"] = calculate_reviewed_match_metrics(job)
        summary["reviewed_events"] = sum(
            1
            for event in job.get("events", [])
            if isinstance(event, dict) and event.get("review_status") == "accepted"
        )
    return AnalysisJobResponse(
        analysis_id=str(job.get("analysis_id", analysis_id)),
        video_id=str(job.get("video_id", "")),
        status=str(job.get("status", "failed")),
        progress_pct=int(job.get("progress_pct", 0)),
        processed_samples=int(job.get("processed_samples", 0)),
        summary=summary,
        error=job.get("error"),
        created_at=str(job.get("created_at", "")),
        started_at=job.get("started_at"),
        completed_at=job.get("completed_at"),
    )


@router.get(
    "/analyses/{analysis_id}/events",
    summary="List automatic event candidates and reviewed labels",
)
async def list_analysis_events(analysis_id: str) -> dict[str, object]:
    job = _read_job(_job_path(analysis_id))
    events = job.get("events", [])
    return {
        "analysis_id": analysis_id,
        "events": events if isinstance(events, list) else [],
        "review_guidance": (
            "Automatic candidates are not official events. Verify each against the video "
            "and only accept a label when the event is visible."
        ),
    }


@router.post(
    "/analyses/{analysis_id}/events/{event_id}/review",
    summary="Accept, reject or relabel an event candidate",
)
async def review_analysis_event(
    analysis_id: str,
    event_id: str,
    request: EventReviewRequest,
) -> dict[str, object]:
    path = _job_path(analysis_id)
    job = _read_job(path)
    if job.get("status") != "completed":
        raise HTTPException(status_code=409, detail="Analysis must finish before reviewing events.")
    if request.review_status == "accepted" and request.event_type is None:
        raise HTTPException(
            status_code=422,
            detail="An accepted event must have an explicit event type.",
        )

    events = job.get("events", [])
    if not isinstance(events, list):
        raise HTTPException(status_code=500, detail="Analysis event list is malformed.")
    event = next(
        (item for item in events if isinstance(item, dict) and item.get("event_id") == event_id),
        None,
    )
    if event is None:
        raise HTTPException(status_code=404, detail="Event candidate not found.")

    event["review_status"] = request.review_status
    event["review_source"] = "human_review"
    event["reviewed_at"] = datetime.now(UTC).isoformat()
    if request.event_type is not None:
        event["event_type"] = request.event_type
    if request.court_x_m is not None:
        event["court_x_m"] = request.court_x_m
    if request.court_y_m is not None:
        event["court_y_m"] = request.court_y_m
    event["player_id"] = request.player_id
    if request.note is not None:
        event["review_note"] = request.note
    metadata_fields = (
        "serve_number",
        "serve_result",
        "shot_depth",
        "shot_direction",
        "point_id",
        "rally_contact_index",
    )
    for field in metadata_fields:
        value = getattr(request, field)
        if value is not None:
            event[field] = value

    _write_job(path, job)
    return {
        "analysis_id": analysis_id,
        "event": event,
        "reviewed_metrics": calculate_reviewed_match_metrics(job),
    }


@router.get(
    "/analyses/{analysis_id}/report.svg",
    summary="Export the positioning and reliability infographic as SVG",
)
async def export_analysis_report(analysis_id: str) -> Response:
    job = _read_job(_job_path(analysis_id))
    if job.get("status") != "completed":
        raise HTTPException(status_code=409, detail="Analysis has not completed.")
    svg = render_analysis_svg(job)
    return Response(
        content=svg,
        media_type="image/svg+xml",
        headers={"Content-Disposition": f'attachment; filename="match-{analysis_id}.svg"'},
    )


@router.post(
    "/analyses/{analysis_id}/events",
    status_code=status.HTTP_201_CREATED,
    summary="Add a manually verified match event",
)
async def create_manual_analysis_event(
    analysis_id: str,
    request: ManualEventCreateRequest,
) -> dict[str, object]:
    path = _job_path(analysis_id)
    job = _read_job(path)
    if job.get("status") != "completed":
        raise HTTPException(
            status_code=409,
            detail="Analysis must finish before adding reviewed events.",
        )

    summary = job.get("summary", {})
    source = summary.get("source", {}) if isinstance(summary, dict) else {}
    duration = source.get("duration_seconds") if isinstance(source, dict) else None
    if duration is not None and request.timestamp_seconds > float(duration):
        raise HTTPException(status_code=422, detail="Event timestamp exceeds video duration.")

    events = job.get("events", [])
    if not isinstance(events, list):
        raise HTTPException(status_code=500, detail="Analysis event list is malformed.")

    event = {
        "event_id": f"manual-{uuid4()}",
        "event_type": request.event_type,
        "timestamp_seconds": request.timestamp_seconds,
        "court_x_m": request.court_x_m,
        "court_y_m": request.court_y_m,
        "player_id": request.player_id,
        "confidence": 1.0,
        "review_status": "accepted",
        "review_source": "human_manual_entry",
        "reviewed_at": datetime.now(UTC).isoformat(),
        "review_note": request.note,
        "serve_number": request.serve_number,
        "serve_result": request.serve_result,
        "shot_depth": request.shot_depth,
        "shot_direction": request.shot_direction,
        "point_id": request.point_id,
        "rally_contact_index": request.rally_contact_index,
        "note": "Manually entered and explicitly confirmed by the user.",
    }
    events.append(event)
    job["events"] = events
    _write_job(path, job)
    return {
        "analysis_id": analysis_id,
        "event": event,
        "reviewed_metrics": calculate_reviewed_match_metrics(job),
    }


@router.get(
    "/analyses/{analysis_id}/report.png",
    summary="Export positioning and reviewed-event statistics as PNG",
)
async def export_analysis_report_png(analysis_id: str) -> Response:
    job = _read_job(_job_path(analysis_id))
    if job.get("status") != "completed":
        raise HTTPException(status_code=409, detail="Analysis has not completed.")
    image = render_analysis_png(job)
    return Response(
        content=image,
        media_type="image/png",
        headers={"Content-Disposition": f'attachment; filename="match-{analysis_id}.png"'},
    )


@router.get(
    "/analyses/{analysis_id}/report.pdf",
    summary="Export positioning and reviewed-event statistics as PDF",
)
async def export_analysis_report_pdf(analysis_id: str) -> Response:
    job = _read_job(_job_path(analysis_id))
    if job.get("status") != "completed":
        raise HTTPException(status_code=409, detail="Analysis has not completed.")
    document = render_analysis_pdf(job)
    return Response(
        content=document,
        media_type="application/pdf",
        headers={"Content-Disposition": f'attachment; filename="match-{analysis_id}.pdf"'},
    )


@router.delete(
    "/analyses/{analysis_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete a persisted analysis result",
)
async def delete_analysis(analysis_id: str) -> Response:
    path = _job_path(analysis_id)
    path.unlink(missing_ok=True)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
