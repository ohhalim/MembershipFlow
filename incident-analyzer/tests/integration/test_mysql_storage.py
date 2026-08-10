from collections.abc import Iterator
from datetime import UTC, datetime
import os

from alembic import command
from alembic.config import Config
import pytest
from sqlalchemy import URL, create_engine, event, func, select, text
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session, sessionmaker
from testcontainers.community.mysql import MySqlContainer

from app.domain import CreateIncidentCommand
from app.models import AnalysisJobModel, IncidentModel
from app.repositories import IncidentRepository


ROOT_PASSWORD = "root_test_password_2026"
RUNTIME_PASSWORD = "runtime_test_password_2026"
MIGRATION_PASSWORD = "migration_test_password_2026"


@pytest.fixture(scope="module")
def mysql_database() -> Iterator[dict[str, str | int]]:
    with MySqlContainer(
        image="mysql:8.0",
        dialect="pymysql",
        username="root",
        password=ROOT_PASSWORD,
        dbname="membershipflow",
    ) as mysql:
        host = mysql.get_container_host_ip()
        port = int(mysql.get_exposed_port(3306))
        root_engine = create_engine(mysql.get_connection_url())
        with root_engine.begin() as connection:
            connection.execute(
                text(
                    "CREATE DATABASE membershipflow_incident "
                    "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
                )
            )
            connection.execute(
                text(
                    "CREATE USER 'incident_analyzer_runtime'@'%' "
                    f"IDENTIFIED BY '{RUNTIME_PASSWORD}'"
                )
            )
            connection.execute(
                text(
                    "GRANT SELECT, INSERT, UPDATE, DELETE "
                    "ON membershipflow_incident.* "
                    "TO 'incident_analyzer_runtime'@'%'"
                )
            )
            connection.execute(
                text(
                    "CREATE USER 'incident_analyzer_migrator'@'%' "
                    f"IDENTIFIED BY '{MIGRATION_PASSWORD}'"
                )
            )
            connection.execute(
                text(
                    "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, "
                    "INDEX, REFERENCES ON membershipflow_incident.* "
                    "TO 'incident_analyzer_migrator'@'%'"
                )
            )
        root_engine.dispose()

        previous = {
            key: os.environ.get(key)
            for key in (
                "INCIDENT_DB_HOST",
                "INCIDENT_DB_PORT",
                "INCIDENT_DB_NAME",
                "INCIDENT_DB_MIGRATION_USERNAME",
                "INCIDENT_DB_MIGRATION_PASSWORD",
            )
        }
        os.environ.update(
            {
                "INCIDENT_DB_HOST": host,
                "INCIDENT_DB_PORT": str(port),
                "INCIDENT_DB_NAME": "membershipflow_incident",
                "INCIDENT_DB_MIGRATION_USERNAME": "incident_analyzer_migrator",
                "INCIDENT_DB_MIGRATION_PASSWORD": MIGRATION_PASSWORD,
            }
        )
        alembic_config = Config("alembic.ini")
        command.upgrade(alembic_config, "head")

        yield {"host": host, "port": port}

        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def runtime_url(database: dict[str, str | int], name: str) -> URL:
    return URL.create(
        "mysql+pymysql",
        username="incident_analyzer_runtime",
        password=RUNTIME_PASSWORD,
        host=str(database["host"]),
        port=int(database["port"]),
        database=name,
        query={"charset": "utf8mb4"},
    )


@pytest.mark.integration
def test_runtime_user_cannot_access_application_database(mysql_database) -> None:
    application_engine = create_engine(runtime_url(mysql_database, "membershipflow"))

    with pytest.raises(OperationalError):
        with application_engine.connect() as connection:
            connection.execute(text("SELECT 1"))

    application_engine.dispose()


@pytest.mark.integration
def test_migration_and_incident_job_transaction(mysql_database) -> None:
    runtime_engine = create_engine(runtime_url(mysql_database, "membershipflow_incident"))
    session_factory = sessionmaker(bind=runtime_engine, expire_on_commit=False)
    repository = IncidentRepository()
    command_data = CreateIncidentCommand(
        dedup_key="course-api-error-burst",
        started_at=datetime.now(UTC),
        masked_event={"alertname": "ApplicationErrorBurst", "route": "/api/v1/courses"},
    )

    with session_factory() as session:
        created = repository.create_with_job(session, command_data)

    with Session(runtime_engine) as session:
        assert session.scalar(select(func.count()).select_from(IncidentModel)) == 1
        assert session.scalar(select(func.count()).select_from(AnalysisJobModel)) == 1
        job = session.scalar(
            select(AnalysisJobModel).where(
                AnalysisJobModel.incident_id == created.incident_id
            )
        )
        assert job is not None
        assert job.status == "PENDING"
        assert job.analysis_revision == 1

    def fail_job_insert(*_args, **_kwargs) -> None:
        raise RuntimeError("controlled job insert failure")

    event.listen(AnalysisJobModel, "before_insert", fail_job_insert, once=True)
    with session_factory() as session:
        with pytest.raises(RuntimeError, match="controlled job insert failure"):
            repository.create_with_job(
                session,
                CreateIncidentCommand(
                    dedup_key="rollback-check",
                    started_at=datetime.now(UTC),
                    masked_event={"alertname": "RollbackCheck"},
                ),
            )

    with Session(runtime_engine) as session:
        assert session.scalar(select(func.count()).select_from(IncidentModel)) == 1
        assert session.scalar(select(func.count()).select_from(AnalysisJobModel)) == 1

    runtime_engine.dispose()
