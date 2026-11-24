# Model and dependency provenance

## Computer-vision components currently used

| Component | Source | Purpose | Status / limitation |
|---|---|---|---|
| Green-region court suggestion | Original project code using OpenCV image operations | Suggests a court-shaped region and four image corners | Heuristic only; user confirmation required; not a trained court-keypoint model |
| Person proposals | OpenCV built-in default HOG pedestrian detector | Produces generic person bounding boxes | Not tennis-specific; can miss players and detect spectators / officials |
| Ground point | Original project code | Uses the bottom-centre of the person bounding box as a foot-point approximation | Not pose estimation; occlusion and box errors affect court coordinates |
| Ball candidates | Original project HSV colour and compact-blob filter | Proposes yellow / white small objects in sampled frames | Not a trained ball detector; false positives and fast-ball misses are expected |
| Trajectory-change candidates | Original project code using sampled image-point direction changes | Suggests possible abrupt ball-direction changes | Does not establish a racket contact, bounce, serve or point boundary |
| Court-plane conversion | Original project code using NumPy and normalised DLT homography | Projects manually confirmed corners / image points into court metres | Accuracy depends on correct keypoints, stable camera view and valid planar geometry |

No external model weights or third-party tennis datasets are downloaded by the current pipeline. This avoids importing weights whose licence or training provenance has not been checked.

## Dependency licensing

The OpenCV Python package is distributed under Apache-2.0 for OpenCV 4.5.0 and later. NumPy is distributed under its BSD-style licence. Other direct dependencies have their own package licences and must continue to be reviewed before adopting models, datasets or services.

- OpenCV licensing: https://opencv.org/license/
- NumPy licensing: https://numpy.org/license/
- Project source: Apache-2.0 in the repository root.

A permissive licence for source code does not grant rights to match footage, tournament broadcasts, datasets, trademarks or model weights supplied by other parties.

## Accuracy and publication policy

The pipeline currently has no labelled held-out tennis-match benchmark. It must not claim tracking accuracy, precision, recall, court reprojection error, official winner/error counts or verified ball trajectories.

Keep the following distinctions in all APIs and UIs:

1. **Observation candidate** — a visual detector proposal with a confidence value.
2. **Human-confirmed event** — a reviewer explicitly accepts or manually enters an event and its type.
3. **Derived statistic** — a count or aggregate calculated only from eligible, human-confirmed events.

Do not silently promote candidate detections to confirmed events. Future tennis-specific models must include licence provenance, versioned weights, reproducible evaluation, match-level train/test separation, camera/surface coverage and confidence reporting before being enabled by default.
