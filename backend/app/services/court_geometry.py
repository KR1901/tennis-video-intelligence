from collections.abc import Sequence
from dataclasses import dataclass
from typing import Literal

import numpy as np

CourtEnd = Literal["near", "far"]
CourtArea = Literal["singles", "doubles"]
Point2D = tuple[float, float]


@dataclass(frozen=True)
class CourtDimensions:
    """Regulation tennis court dimensions, in metres."""

    length_m: float = 23.77
    singles_width_m: float = 8.23
    doubles_width_m: float = 10.97

    def __post_init__(self) -> None:
        if self.length_m <= 0 or self.singles_width_m <= 0 or self.doubles_width_m <= 0:
            raise ValueError("Court dimensions must be positive.")
        if self.doubles_width_m < self.singles_width_m:
            raise ValueError("Doubles width cannot be smaller than singles width.")

    @property
    def half_length_m(self) -> float:
        return self.length_m / 2

    def boundary_corners(self, area: CourtArea = "doubles") -> tuple[Point2D, ...]:
        """Return corners in perimeter order: far-left, far-right, near-right, near-left."""
        width = self.doubles_width_m if area == "doubles" else self.singles_width_m
        half_width = width / 2
        half_length = self.half_length_m
        return (
            (-half_width, -half_length),
            (half_width, -half_length),
            (half_width, half_length),
            (-half_width, half_length),
        )


def _normalise_points(points: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    centroid = points.mean(axis=0)
    distances = np.linalg.norm(points - centroid, axis=1)
    mean_distance = float(distances.mean())
    if mean_distance <= np.finfo(float).eps:
        raise ValueError("Point coordinates are degenerate.")
    scale = np.sqrt(2.0) / mean_distance
    transform = np.array(
        [
            [scale, 0.0, -scale * centroid[0]],
            [0.0, scale, -scale * centroid[1]],
            [0.0, 0.0, 1.0],
        ],
        dtype=float,
    )
    homogeneous = np.column_stack((points, np.ones(len(points))))
    normalised = (transform @ homogeneous.T).T[:, :2]
    return normalised, transform


def estimate_homography(
    image_points: Sequence[Point2D],
    court_points: Sequence[Point2D],
) -> np.ndarray:
    """Estimate an image-to-court-plane homography with normalised DLT.

    At least four non-collinear point correspondences are required. The order
    of image_points must correspond exactly to the order of court_points.
    """
    source = np.asarray(image_points, dtype=float)
    destination = np.asarray(court_points, dtype=float)

    valid_shape = (
        source.ndim == 2
        and destination.ndim == 2
        and source.shape[1:] == (2,)
        and destination.shape[1:] == (2,)
    )
    if not valid_shape:
        raise ValueError("Each point must contain exactly two coordinates.")
    if source.shape != destination.shape or len(source) < 4:
        raise ValueError("At least four matching image/court point pairs are required.")
    if not np.isfinite(source).all() or not np.isfinite(destination).all():
        raise ValueError("Point coordinates must be finite numbers.")

    source_n, source_transform = _normalise_points(source)
    destination_n, destination_transform = _normalise_points(destination)

    rows: list[list[float]] = []
    for (x, y), (u, v) in zip(source_n, destination_n, strict=True):
        rows.append([-x, -y, -1.0, 0.0, 0.0, 0.0, u * x, u * y, u])
        rows.append([0.0, 0.0, 0.0, -x, -y, -1.0, v * x, v * y, v])

    design = np.asarray(rows, dtype=float)
    if np.linalg.matrix_rank(design) < 8:
        raise ValueError("Point correspondences do not define a stable homography.")

    _, _, right_vectors = np.linalg.svd(design, full_matrices=True)
    normalised_h = right_vectors[-1].reshape(3, 3)
    homography = np.linalg.inv(destination_transform) @ normalised_h @ source_transform

    scale = homography[2, 2]
    if abs(scale) <= np.finfo(float).eps:
        scale = np.linalg.norm(homography)
    if scale <= np.finfo(float).eps:
        raise ValueError("Could not normalise the homography matrix.")

    homography = homography / scale
    if not np.isfinite(homography).all() or abs(np.linalg.det(homography)) <= 1e-12:
        raise ValueError("Estimated homography is singular or non-finite.")
    return homography


def project_point(point: Point2D, homography: np.ndarray) -> Point2D:
    """Project one image-space point into calibrated court-plane coordinates."""
    matrix = np.asarray(homography, dtype=float)
    if matrix.shape != (3, 3) or not np.isfinite(matrix).all():
        raise ValueError("Homography must be a finite 3x3 matrix.")
    x, y = point
    projected = matrix @ np.array([x, y, 1.0], dtype=float)
    if abs(projected[2]) <= 1e-12:
        raise ValueError("Point projects to infinity under this homography.")
    return float(projected[0] / projected[2]), float(projected[1] / projected[2])


def signed_baseline_distance_m(
    court_point: Point2D,
    player_end: CourtEnd,
    court: CourtDimensions | None = None,
) -> float:
    """Signed baseline distance: positive behind, zero on, negative inside."""
    dimensions = court or CourtDimensions()
    y = float(court_point[1])
    half_length = dimensions.half_length_m
    if player_end == "near":
        return y - half_length
    if player_end == "far":
        return -half_length - y
    raise ValueError("player_end must be 'near' or 'far'.")
