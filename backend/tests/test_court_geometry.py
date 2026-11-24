import numpy as np
import pytest

from app.services.court_geometry import (
    CourtDimensions,
    estimate_homography,
    project_point,
    signed_baseline_distance_m,
)


def test_estimated_homography_maps_court_corners() -> None:
    court = CourtDimensions()
    image_points = [(100, 100), (500, 100), (500, 500), (100, 500)]
    court_points = court.boundary_corners("doubles")

    matrix = estimate_homography(image_points, court_points)

    for image_point, expected in zip(image_points, court_points, strict=True):
        actual = project_point(image_point, matrix)
        assert actual == pytest.approx(expected, abs=1e-7)


def test_homography_projects_interior_point() -> None:
    image_points = [(100, 100), (500, 100), (500, 500), (100, 500)]
    court_points = [(-5.485, -11.885), (5.485, -11.885), (5.485, 11.885), (-5.485, 11.885)]
    matrix = estimate_homography(image_points, court_points)

    assert project_point((300, 300), matrix) == pytest.approx((0.0, 0.0), abs=1e-7)


def test_baseline_distance_sign_is_consistent_for_both_ends() -> None:
    assert signed_baseline_distance_m((0.0, 13.0), "near") == pytest.approx(1.115)
    assert signed_baseline_distance_m((0.0, 10.0), "near") == pytest.approx(-1.885)
    assert signed_baseline_distance_m((0.0, -13.0), "far") == pytest.approx(1.115)
    assert signed_baseline_distance_m((0.0, -10.0), "far") == pytest.approx(-1.885)


def test_homography_rejects_degenerate_correspondences() -> None:
    with pytest.raises(ValueError, match="stable homography"):
        estimate_homography(
            [(0, 0), (1, 0), (2, 0), (3, 0)],
            [(0, 0), (1, 0), (2, 0), (3, 0)],
        )


def test_court_dimensions_reject_invalid_widths() -> None:
    with pytest.raises(ValueError, match="Doubles width"):
        CourtDimensions(singles_width_m=9.0, doubles_width_m=8.0)
