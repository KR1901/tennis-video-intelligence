from functools import lru_cache
from pathlib import Path

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="TVI_", env_file=".env", extra="ignore")

    environment: str = "development"
    storage_dir: Path = Path("./data/videos")
    max_upload_mb: int = Field(default=4_768, ge=1, le=4_768)
    allowed_origins: str = "http://localhost:8080"
    api_token: str = ""

    @model_validator(mode="after")
    def require_token_in_production(self) -> "Settings":
        if self.environment.lower() == "production" and not self.api_token.strip():
            raise ValueError("TVI_API_TOKEN must be configured in production.")
        return self

    @property
    def allowed_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.allowed_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
