from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from pydantic import ValidationError
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import engine, get_session, verify_database_ready
from app.repositories import IncidentRepository
from app.webhook import GrafanaWebhook, to_create_commands, verify_webhook_signature


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

    @application.post("/internal/incidents", status_code=status.HTTP_202_ACCEPTED)
    async def create_incidents(
        request: Request,
        signature: Annotated[str, Header(alias="X-Grafana-Alerting-Signature")],
        sent_at: Annotated[str, Header(alias="X-Grafana-Alerting-Timestamp")],
        session: Annotated[Session, Depends(get_session)],
    ) -> dict[str, object]:
        settings = get_settings()
        raw_body = await request.body()
        if len(raw_body) > settings.incident_payload_max_bytes:
            raise HTTPException(
                status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                detail="incident payload too large",
            )
        try:
            verify_webhook_signature(
                raw_body,
                sent_at,
                signature,
                settings.incident_webhook_secret.get_secret_value(),
                settings.incident_webhook_tolerance_seconds,
            )
            payload = GrafanaWebhook.model_validate_json(raw_body)
        except (ValueError, ValidationError):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid incident webhook",
            ) from None

        created = IncidentRepository().create_many_with_jobs(
            session, to_create_commands(payload)
        )
        return {
            "accepted": len(created),
            "incidentIds": [item.incident_id for item in created],
        }

    return application


app = create_app()
