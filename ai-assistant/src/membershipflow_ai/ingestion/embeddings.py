from __future__ import annotations

import hashlib
import importlib
import math
from typing import Protocol, cast


class ArrayLike(Protocol):
    def tolist(self) -> list[list[float]] | list[float]: ...


class SentenceTransformerModel(Protocol):
    def get_sentence_embedding_dimension(self) -> int | None: ...

    def encode(self, texts: list[str], *, normalize_embeddings: bool) -> ArrayLike: ...


class EmbeddingProvider(Protocol):
    @property
    def model_id(self) -> str: ...

    @property
    def revision(self) -> str | None: ...

    @property
    def dimension(self) -> int: ...

    def embed_documents(self, texts: list[str]) -> list[list[float]]: ...

    def embed_query(self, text: str) -> list[float]: ...


class DeterministicHashEmbedding:
    def __init__(self, dimension: int = 1024) -> None:
        if dimension <= 0:
            raise ValueError("dimension must be positive")
        self._dimension = dimension

    @property
    def model_id(self) -> str:
        return "fake-sha256-v1"

    @property
    def revision(self) -> str:
        return "1"

    @property
    def dimension(self) -> int:
        return self._dimension

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [self._embed(text) for text in texts]

    def embed_query(self, text: str) -> list[float]:
        return self._embed(text)

    def _embed(self, text: str) -> list[float]:
        values: list[float] = []
        block = 0
        while len(values) < self._dimension:
            digest = hashlib.sha256(f"{block}:{text}".encode()).digest()
            values.extend((byte - 127.5) / 127.5 for byte in digest)
            block += 1
        vector = values[: self._dimension]
        norm = math.sqrt(sum(value * value for value in vector))
        return [value / norm for value in vector]


class SentenceTransformerEmbedding:
    def __init__(self, model_id: str, revision: str | None = None) -> None:
        try:
            module = importlib.import_module("sentence_transformers")
        except ModuleNotFoundError as exc:
            raise RuntimeError("install the 'models' extra to use local embeddings") from exc
        model_type = cast(type[SentenceTransformerModel], module.SentenceTransformer)
        self._model = model_type(model_id, revision=revision)  # type: ignore[call-arg]
        self._model_id = model_id
        self._revision = revision
        dimension = self._model.get_sentence_embedding_dimension()
        if dimension is None:
            raise RuntimeError(f"embedding dimension unavailable for {model_id}")
        self._dimension = dimension

    @property
    def model_id(self) -> str:
        return self._model_id

    @property
    def revision(self) -> str | None:
        return self._revision

    @property
    def dimension(self) -> int:
        return self._dimension

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        embeddings = self._model.encode(texts, normalize_embeddings=True)
        values = embeddings.tolist()
        if not values or isinstance(values[0], float):
            raise RuntimeError("document embedding provider returned an invalid shape")
        return values

    def embed_query(self, text: str) -> list[float]:
        embeddings = self._model.encode([text], normalize_embeddings=True)
        values = embeddings.tolist()
        if not values or isinstance(values[0], float):
            raise RuntimeError("query embedding provider returned an invalid shape")
        return values[0]
