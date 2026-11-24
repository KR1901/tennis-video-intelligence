# Metrics and reliability rules

A metric is not valid merely because a chart can be drawn. Every metric must be traceable to video evidence, processing versions and an explicit denominator.

## Positioning relative to the baseline

For each eligible frame, project the player's ground-contact estimate into calibrated court coordinates. Compute signed perpendicular distance from the relevant baseline:

- positive: behind the baseline;
- zero: on the baseline;
- negative: inside the court.

Report the unit, player, side, sample count, timestamp coverage, calibration quality and uncertainty. Distinguish return stance at opponent serve contact, position at own serve contact, average position during live rallies, defensive recovery position and position at return contact. Do not mix these into one average without making the sampling rule explicit. Avoid counting changeovers, pauses or between-point time as rally positioning.

## Shot placement

Store the estimated landing coordinate separately from the contact coordinate. Mark whether a landing is directly observed, inferred from a bounce event, or unavailable. Distinguish a ball landing in court, landing out, touching a line, hitting the net and not being observable. Do not infer winner/error labels from landing coordinates alone.

## Serve placement

A serve event should include server identity, deuce/ad court, first/second serve classification, bounce point, outcome, serve speed if actually measured, and confidence. Map wide/body/T zones using a documented service-box coordinate definition. A ball that is occluded or leaves the camera frame should be labelled unobserved rather than forced into a zone.

## Rally length

Define the counted unit before reporting it. Recommended initial definition: number of racket-ball contacts in a point, with the serve counted as contact one. Report whether double faults, lets, service winners and video gaps are included or excluded. Keep point-level event sequences so the summary can be recalculated.

## Confidence and coverage

Every metric should expose numerator and denominator, observed / successfully tracked count, percentage of eligible footage covered, confidence summary or uncertainty range, count of manually reviewed/corrected events, analysis version and filter selection. If coverage is low, show a warning and do not rank players as if the sample were complete. Thresholds should be selected per metric after validation, not guessed globally.

## Validation plan

Build a labelled evaluation set spanning camera positions, court surfaces, lighting, player clothing, ball colours, camera pans/zooms, occlusions and different broadcast styles. Split by match (not random adjacent frames) to avoid leakage.

Track separate performance measures:

- court-keypoint reprojection error in pixels and metres;
- player detection precision/recall and identity switches;
- player ground-point error in metres;
- ball detection recall and track continuity;
- hit / bounce / serve event precision, recall and timing error;
- event-to-video alignment error;
- metric error against human-reviewed match samples.

Publish supported camera conditions and known failure modes with each release. A model update should not silently rewrite old results; store the model and pipeline version used for each analysis.
