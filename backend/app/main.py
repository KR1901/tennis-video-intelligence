import hmac

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.analyses import router as analyses_router
from app.api.videos import router as videos_router
from app.core.config import get_settings

settings = get_settings()

app = FastAPI(
    title="Tennis Video Intelligence API",
    description="Video ingestion API for evidence-backed tennis match analysis.",
    version="0.1.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.allowed_origin_list,
    allow_credentials=False,
    allow_methods=["GET", "HEAD", "POST", "PATCH", "DELETE"],
    allow_headers=["Content-Type", "Authorization", "Upload-Offset", "Upload-Length"],
)


@app.middleware("http")
async def optional_api_token(request: Request, call_next):
    configured_token = get_settings().api_token.strip()
    public_paths = {"/health", "/docs", "/openapi.json", "/redoc"}
    if configured_token and request.url.path not in public_paths:
        supplied = request.headers.get("authorization", "")
        expected = f"Bearer {configured_token}"
        if not hmac.compare_digest(supplied, expected):
            return JSONResponse(
                status_code=401,
                content={"detail": "A valid bearer token is required."},
                headers={"WWW-Authenticate": "Bearer"},
            )
    return await call_next(request)


@app.get("/health", tags=["system"])
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "tennis-video-intelligence-api"}


app.include_router(videos_router, prefix="/api/v1")
app.include_router(analyses_router, prefix="/api/v1")
