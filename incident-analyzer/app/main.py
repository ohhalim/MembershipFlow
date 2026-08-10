from fastapi import FastAPI, HTTPException, status
from sqlalchemy.exc import SQLAlchemyError

from app.config import get_settings
from app.database import engine, verify_database_ready


def create_app() -> FastAPI:
    application = FastAPI(
        title="MembershipFlow Incident Analyzer",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    application.state.database_engine = engine

    @application.get("/health/live")
    def live() -> dict[str, str]:
        return {"status": "UP"}

    @application.get("/health/ready")
    def ready() -> dict[str, str]:
        settings = get_settings()
        try:
            verify_database_ready(
                application.state.database_engine, settings.expected_db_revision
            )
        except (SQLAlchemyError, RuntimeError):
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="database not ready",
            ) from None
        return {"status": "UP"}

    return application


app = create_app()
