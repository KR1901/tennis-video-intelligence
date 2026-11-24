from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

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
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "Authorization"],
)


@app.get("/health", tags=["system"])
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "tennis-video-intelligence-api"}


app.include_router(videos_router, prefix="/api/v1")
app.include_router(analyses_router, prefix="/api/v1")
