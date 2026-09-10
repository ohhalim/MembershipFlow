FROM ghcr.io/astral-sh/uv:0.9.26-python3.13-bookworm-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src ./src
COPY config ./config
COPY migrations ./migrations
COPY alembic.ini ./
RUN uv sync --frozen --no-dev

CMD ["uv", "run", "uvicorn", "membershipflow_ai.api.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090"]
