import hashlib
import json
from datetime import UTC, datetime, timedelta

import ulid
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.domain import ClaimedAnalysisJob, CreatedIncident, CreateIncidentCommand
from app.evidence import EvidenceBundle
from app.llm import LlmAnalysis
from app.models import (
    AnalysisJobModel,
    AnalysisResultModel,
    EvidenceBundleModel,
    IncidentModel,
)


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

    def create_many_with_jobs(
        self, session: Session, commands: list[CreateIncidentCommand]
    ) -> list[CreatedIncident]:
        created: list[CreatedIncident] = []
        with session.begin():
            for command in commands:
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
                session.add_all([incident, job])
                session.flush()
                created.append(
                    CreatedIncident(
                        incident_id=incident.id,
                        job_id=job.id,
                        analysis_revision=job.analysis_revision,
                    )
                )
        return created


class AnalysisJobRepository:
    def claim_next(
        self, session: Session, worker_id: str, lease_seconds: int
    ) -> ClaimedAnalysisJob | None:
        now = datetime.now(UTC).replace(tzinfo=None)
        with session.begin():
            job = session.scalar(
                select(AnalysisJobModel)
                .where(
                    AnalysisJobModel.available_at <= now,
                    or_(
                        AnalysisJobModel.status == "PENDING",
                        (
                            (AnalysisJobModel.status == "ANALYZING")
                            & (AnalysisJobModel.lease_until < now)
                        ),
                    ),
                )
                .order_by(AnalysisJobModel.available_at, AnalysisJobModel.id)
                .with_for_update(skip_locked=True)
                .limit(1)
            )
            if job is None:
                return None
            incident = session.get(IncidentModel, job.incident_id)
            if incident is None:
                raise RuntimeError("analysis job incident is missing")
            job.status = "ANALYZING"
            job.attempt_count += 1
            job.lease_owner = worker_id
            job.lease_until = now + timedelta(seconds=lease_seconds)
            job.failure_code = None
            job.updated_at = now
            return ClaimedAnalysisJob(
                job_id=job.id,
                incident_id=job.incident_id,
                analysis_revision=job.analysis_revision,
                started_at=incident.started_at.replace(tzinfo=UTC),
                masked_event=incident.masked_event_json,
            )

    def complete(
        self,
        session: Session,
        claimed: ClaimedAnalysisJob,
        worker_id: str,
        evidence: EvidenceBundle,
        analysis: LlmAnalysis,
    ) -> None:
        evidence_json = evidence.model_dump(mode="json", by_alias=True)
        analysis_json = analysis.result.model_dump(mode="json", by_alias=True)
        canonical = json.dumps(
            evidence_json, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode()
        now = datetime.now(UTC).replace(tzinfo=None)
        with session.begin():
            job = session.scalar(
                select(AnalysisJobModel)
                .where(AnalysisJobModel.id == claimed.job_id)
                .with_for_update()
            )
            if job is None or job.lease_owner != worker_id:
                raise RuntimeError("analysis job lease ownership lost")
            existing_result = session.scalar(
                select(AnalysisResultModel).where(
                    AnalysisResultModel.incident_id == claimed.incident_id,
                    AnalysisResultModel.analysis_revision == claimed.analysis_revision,
                )
            )
            if existing_result is None:
                session.add(
                    EvidenceBundleModel(
                        incident_id=claimed.incident_id,
                        analysis_revision=claimed.analysis_revision,
                        schema_version=evidence.schema_version,
                        collector_version=evidence.collector_version,
                        window_start=evidence.window_start.astimezone(UTC).replace(
                            tzinfo=None
                        ),
                        window_end=evidence.window_end.astimezone(UTC).replace(
                            tzinfo=None
                        ),
                        content_json=evidence_json,
                        content_sha256=hashlib.sha256(canonical).digest(),
                    )
                )
                session.add(
                    AnalysisResultModel(
                        incident_id=claimed.incident_id,
                        analysis_revision=claimed.analysis_revision,
                        schema_version="1",
                        prompt_version=analysis.prompt_version,
                        provider=analysis.provider,
                        model=analysis.model,
                        content_json=analysis_json,
                        input_tokens=analysis.input_tokens,
                        output_tokens=analysis.output_tokens,
                        latency_ms=analysis.latency_ms,
                    )
                )
            job.status = "SUCCEEDED"
            job.lease_owner = None
            job.lease_until = None
            job.failure_code = None
            job.updated_at = now

    def fail(
        self,
        session: Session,
        claimed: ClaimedAnalysisJob,
        worker_id: str,
        failure_code: str,
        retryable: bool,
        max_attempts: int,
    ) -> None:
        now = datetime.now(UTC).replace(tzinfo=None)
        with session.begin():
            job = session.scalar(
                select(AnalysisJobModel)
                .where(AnalysisJobModel.id == claimed.job_id)
                .with_for_update()
            )
            if job is None or job.lease_owner != worker_id:
                return
            should_retry = retryable and job.attempt_count < max_attempts
            job.status = "PENDING" if should_retry else "FAILED"
            job.available_at = now + timedelta(seconds=5 * job.attempt_count)
            job.lease_owner = None
            job.lease_until = None
            job.failure_code = failure_code[:64]
            job.updated_at = now
