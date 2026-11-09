FROM ghcr.io/astral-sh/uv:0.9.26-python3.13-bookworm-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

# 임베딩을 로컬에서 돌리려면 `models` extra(sentence-transformers)가 필요하다.
# 이게 빠져 있어서 AI_EMBEDDING_PROVIDER=sentence-transformers 로 띄우면
# 컨테이너가 "install the 'models' extra" 로 죽고 재시작을 반복했다.
# venv 에서는 extra 가 깔려 있어 로컬 실행만으로는 드러나지 않았다.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project --extra models

COPY src ./src
COPY config ./config
COPY migrations ./migrations
COPY alembic.ini ./
RUN uv sync --frozen --no-dev --extra models

CMD ["uv", "run", "uvicorn", "membershipflow_ai.api.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090"]
