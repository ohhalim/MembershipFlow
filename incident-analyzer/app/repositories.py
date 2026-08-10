from datetime import UTC, datetime

import ulid
from sqlalchemy.orm import Session

from app.domain import CreateIncidentCommand, CreatedIncident
from app.models import AnalysisJobModel, IncidentModel


class IncidentRepository:
    def create_with_job(
        self, session: Session, command: CreateIncidentCommand
    ) -> CreatedIncident:
        now = datetime.now(UTC).replace(tzinfo=None)
        started_at = command.started_at.astimezone(UTC).replace(tzinfo=None)
        incident = IncidentModel(
            id=str(ulid.new()),
            external_fingerprint=command.external_fingerprint,
            dedup_key=command.dedup_key,
            episode_status="OPEN",
            started_at=started_at,
            resolved_at=None,
            payload_version=command.payload_version,
            masked_event_json=command.masked_event,
        )
        job = AnalysisJobModel(
            incident=incident,
            analysis_revision=1,
            status="PENDING",
            available_at=now,
            attempt_count=0,
            created_at=now,
            updated_at=now,
        )

        with session.begin():
            session.add(incident)
            session.add(job)
            session.flush()

        return CreatedIncident(
            incident_id=incident.id,
            job_id=job.id,
            analysis_revision=job.analysis_revision,
        )
