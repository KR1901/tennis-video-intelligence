from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="TVI_", env_file=".env", extra="ignore")

    environment: str = "development"
    storage_dir: Path = Path("./data/videos")
    max_upload_mb: int = Field(default=2048, ge=1, le=20_000)
    allowed_origins: str = "http://localhost:8080"

    @property
    def allowed_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.allowed_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
