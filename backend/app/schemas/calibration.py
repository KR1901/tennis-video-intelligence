from typing import Literal

from pydantic import BaseModel, Field

ImagePoint = tuple[float, float]


class CourtCalibrationRequest(BaseModel):
    frame_timestamp_seconds: float = Field(ge=0)
    court_area: Literal["singles", "doubles"] = "doubles"
    # Tap order: far-left, far-right, near-right, near-left court corners.
    image_points: list[ImagePoint] = Field(min_length=4, max_length=4)


class CourtCalibrationResponse(BaseModel):
    calibration_id: str
    frame_timestamp_seconds: float
    court_area: Literal["singles", "doubles"]
    image_points: list[ImagePoint]
    court_points: list[ImagePoint]
    homography_image_to_court: list[list[float]]
    coordinate_convention: str = "x across court; y from net toward near baseline; metres"
    status: Literal["manual_calibration"] = "manual_calibration"


class CourtProjectionRequest(BaseModel):
    image_point: ImagePoint
    player_end: Literal["near", "far"]


class CourtProjectionResponse(BaseModel):
    image_point: ImagePoint
    court_x_m: float
    court_y_m: float
    signed_baseline_distance_m: float
    baseline_distance_convention: str = "positive behind baseline; negative inside court"
