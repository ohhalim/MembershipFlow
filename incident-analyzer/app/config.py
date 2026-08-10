from functools import lru_cache

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import URL


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    incident_db_host: str = "mysql"
    incident_db_port: int = Field(default=3306, ge=1, le=65535)
    incident_db_name: str = "membershipflow_incident"
    incident_db_username: str = "incident_analyzer_runtime"
    incident_db_password: SecretStr
    db_pool_size: int = Field(default=2, ge=1, le=2)
    db_max_overflow: int = Field(default=0, ge=0, le=0)
    db_pool_timeout_seconds: int = Field(default=2, ge=1, le=5)
    expected_db_revision: str = "0001_incident_storage"

    @field_validator("incident_db_name")
    @classmethod
    def require_incident_database(cls, value: str) -> str:
        if value != "membershipflow_incident":
            raise ValueError("incident analyzer must use membershipflow_incident")
        return value

    def database_url(self) -> URL:
        return URL.create(
            drivername="mysql+pymysql",
            username=self.incident_db_username,
            password=self.incident_db_password.get_secret_value(),
            host=self.incident_db_host,
            port=self.incident_db_port,
            database=self.incident_db_name,
            query={"charset": "utf8mb4"},
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
