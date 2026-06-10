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

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    @property
    def database_url(self) -> str:
        return (
            f"postgresql+psycopg://{self.db_user}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.fastapi_db_name}"
        )


settings = Settings()
