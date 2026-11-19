import os
from dataclasses import dataclass


def _load_dotenv(path: str = ".env") -> None:
    """Tiny .env reader so the registry needs no extra dependency.

    Real environment variables win over the file (Docker Compose passes them in).
    """
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.split(" #", 1)[0].strip().strip('"').strip("'")
            os.environ.setdefault(key.strip(), value)


@dataclass(frozen=True)
class Settings:
    database_url: str
    host_api_key: str
    owner_email: str
    token_ttl_hours: int
    offline_after_seconds: int


def load_settings() -> Settings:
    _load_dotenv(os.environ.get("ASTRA_ENV_FILE", ".env"))
    return Settings(
        database_url=os.environ.get("DATABASE_URL", "sqlite:///./registry.db"),
        host_api_key=os.environ.get("HOST_API_KEY", ""),
        owner_email=os.environ.get("OWNER_EMAIL", "owner@example.com"),
        token_ttl_hours=int(os.environ.get("SESSION_TOKEN_TTL_HOURS", "720")),
        offline_after_seconds=int(os.environ.get("OFFLINE_AFTER_SECONDS", "90")),
    )
