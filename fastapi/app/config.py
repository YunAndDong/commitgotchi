from urllib.parse import urlsplit, urlunsplit

from pydantic import SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """DB connection config sourced from environment variables (see .env.example).

    FastAPI (Intelligence service) owns its own database on the shared
    PostgreSQL instance, separate from Spring Boot's database.
    """

    db_host: str = "localhost"
    db_port: int = 5432
    db_user: str = "commitgotchi"
    db_password: str = "commitgotchi"
    fastapi_db_name: str = "commitgotchi_ai"

    spring_boot_internal_base_url: str = "http://localhost:8080"
    spring_internal_api_secret: SecretStr | None = None
    spring_report_callback_path: str = "/api/report"
    spring_quiz_grade_result_path: str = "/api/internal/quizzes/grade-result"
    spring_callback_timeout_seconds: float = 10.0

    aws_region: str = "ap-northeast-2"
    aws_sqs_endpoint: str | None = None
    aws_access_key_id: str | None = None
    aws_secret_access_key: SecretStr | None = None
    report_request_queue_url: str | None = None
    report_request_dlq_url: str | None = None

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    @field_validator("spring_boot_internal_base_url")
    @classmethod
    def _validate_spring_boot_internal_base_url(cls, value: str) -> str:
        stripped = str(value).strip()
        if not stripped:
            raise ValueError("SPRING_BOOT_INTERNAL_BASE_URL is required")

        parts = urlsplit(stripped)
        if parts.scheme not in {"http", "https"} or not parts.netloc:
            raise ValueError(
                "SPRING_BOOT_INTERNAL_BASE_URL must be an http(s) origin"
            )
        if parts.username or parts.password:
            raise ValueError(
                "SPRING_BOOT_INTERNAL_BASE_URL must not include user info"
            )
        try:
            parts.port
        except ValueError as exc:
            raise ValueError(
                "SPRING_BOOT_INTERNAL_BASE_URL has an invalid port"
            ) from exc
        if parts.path not in {"", "/"} or parts.query or parts.fragment:
            raise ValueError(
                "SPRING_BOOT_INTERNAL_BASE_URL must not include path, query, or fragment"
            )
        return urlunsplit((parts.scheme, parts.netloc, "", "", ""))

    @field_validator("spring_report_callback_path", "spring_quiz_grade_result_path")
    @classmethod
    def _validate_spring_callback_path(cls, value: str) -> str:
        stripped = str(value).strip()
        if not stripped:
            raise ValueError("Spring callback path is required")

        parts = urlsplit(stripped)
        if parts.scheme or parts.netloc:
            raise ValueError("Spring callback path must not be an absolute URL")
        if parts.query or parts.fragment:
            raise ValueError(
                "Spring callback path must not include query or fragment"
            )

        path = "/" + stripped.lstrip("/")
        segments = [segment for segment in path.split("/") if segment]
        if not segments:
            raise ValueError("Spring callback path must not be empty")
        return "/" + "/".join(segments)

    @field_validator("spring_callback_timeout_seconds")
    @classmethod
    def _validate_spring_callback_timeout_seconds(cls, value: float) -> float:
        if value <= 0:
            raise ValueError("SPRING_CALLBACK_TIMEOUT_SECONDS must be greater than 0")
        return value

    @field_validator("aws_region")
    @classmethod
    def _validate_aws_region(cls, value: str) -> str:
        stripped = str(value).strip()
        if not stripped:
            raise ValueError("AWS_REGION is required")
        return stripped

    @field_validator(
        "aws_sqs_endpoint",
        "aws_access_key_id",
        "report_request_queue_url",
        "report_request_dlq_url",
        mode="before",
    )
    @classmethod
    def _blank_optional_text_to_none(cls, value: str | None) -> str | None:
        if value is None:
            return None
        stripped = str(value).strip()
        return stripped or None

    @field_validator("spring_internal_api_secret", "aws_secret_access_key", mode="before")
    @classmethod
    def _blank_secret_to_none(cls, value: SecretStr | str | None) -> SecretStr | str | None:
        if value is None:
            return None
        if isinstance(value, SecretStr):
            return value if value.get_secret_value().strip() else None
        stripped = str(value).strip()
        return stripped or None

    @property
    def database_url(self) -> str:
        return (
            f"postgresql+psycopg://{self.db_user}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.fastapi_db_name}"
        )


settings = Settings()
