# Android client (planned)

The intended client is a native Kotlin + Jetpack Compose app. It will call the FastAPI service over HTTPS and should not run full-match computer vision locally in the first version.

## Initial screens

1. Match library and upload / authorised URL import.
2. Upload and analysis progress.
3. Match overview with processing coverage.
4. Interactive mini-court with player / ball tracks.
5. Positioning, serve, return, rally and shot-placement reports.
6. Video replay synchronised to selected events.
7. Export and retention controls.

## Client rules

- Upload resumably for large files once the backend supports multipart sessions.
- Use signed URLs for private media playback; do not embed permanent public video URLs.
- Display analysis state separately from upload state.
- Show coverage, confidence and unavailable events alongside every report.
- Keep all distances in metres internally; the UI may additionally display feet when useful.
