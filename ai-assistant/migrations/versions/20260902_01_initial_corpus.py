"""Create versioned corpus and vector index."""

from collections.abc import Sequence

import pgvector.sqlalchemy
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260902_01"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.create_table(
        "ai_document_source",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("source_uri", sa.String(length=512), nullable=False),
        sa.Column("source_type", sa.String(length=32), nullable=False),
        sa.Column("current_version_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("source_uri"),
    )
    op.create_table(
        "ai_document_version",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("source_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("content_hash", sa.String(length=64), nullable=False),
        sa.Column("parser_version", sa.String(length=32), nullable=False),
        sa.Column("pipeline_version", sa.String(length=32), nullable=False),
        sa.Column("embedding_model", sa.String(length=255), nullable=False),
        sa.Column("embedding_revision", sa.String(length=255), nullable=True),
        sa.Column("embedding_revision_key", sa.String(length=255), nullable=False),
        sa.Column("corpus_version", sa.Integer(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["source_id"], ["ai_document_source.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "source_id",
            "content_hash",
            "parser_version",
            "pipeline_version",
            "embedding_model",
            "embedding_revision_key",
            name="uq_document_version_identity",
        ),
    )
    op.create_foreign_key(
        "fk_source_current_version",
        "ai_document_source",
        "ai_document_version",
        ["current_version_id"],
        ["id"],
    )
    op.create_table(
        "ai_document_chunk",
        sa.Column("chunk_id", sa.String(length=64), nullable=False),
        sa.Column("version_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("ordinal", sa.Integer(), nullable=False),
        sa.Column("path", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("line_start", sa.Integer(), nullable=False),
        sa.Column("line_end", sa.Integer(), nullable=False),
        sa.Column("embedding", pgvector.sqlalchemy.Vector(dim=1024), nullable=False),
        sa.ForeignKeyConstraint(["version_id"], ["ai_document_version.id"]),
        sa.PrimaryKeyConstraint("chunk_id"),
        sa.UniqueConstraint("version_id", "ordinal", name="uq_document_chunk_version_ordinal"),
    )
    op.create_index(
        "ix_ai_document_chunk_version_id", "ai_document_chunk", ["version_id"], unique=False
    )
    op.create_index(
        "ix_ai_document_chunk_embedding_hnsw",
        "ai_document_chunk",
        ["embedding"],
        unique=False,
        postgresql_using="hnsw",
        postgresql_ops={"embedding": "vector_cosine_ops"},
    )
    op.create_table(
        "ai_ingestion_run",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("corpus_version", sa.Integer(), nullable=False),
        sa.Column("pipeline_version", sa.String(length=32), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("scanned_count", sa.Integer(), nullable=False),
        sa.Column("changed_count", sa.Integer(), nullable=False),
        sa.Column("unchanged_count", sa.Integer(), nullable=False),
        sa.Column("removed_count", sa.Integer(), nullable=False),
        sa.Column("chunk_count", sa.Integer(), nullable=False),
        sa.Column("error", sa.Text(), nullable=True),
        sa.Column(
            "started_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )


def downgrade() -> None:
    op.drop_table("ai_ingestion_run")
    op.drop_index(
        "ix_ai_document_chunk_embedding_hnsw",
        table_name="ai_document_chunk",
        postgresql_using="hnsw",
    )
    op.drop_index("ix_ai_document_chunk_version_id", table_name="ai_document_chunk")
    op.drop_table("ai_document_chunk")
    op.drop_constraint("fk_source_current_version", "ai_document_source", type_="foreignkey")
    op.drop_table("ai_document_version")
    op.drop_table("ai_document_source")
