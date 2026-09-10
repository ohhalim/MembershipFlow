from __future__ import annotations

import hashlib
import re
from typing import Protocol

from membershipflow_ai.domain.documents import DocumentChunk, ParsedSection, SourceDocument


class TokenCounter(Protocol):
    def count(self, text: str) -> int: ...


class RegexTokenCounter:
    _tokens = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|[가-힣]+|\d+|[^\s]")

    def count(self, text: str) -> int:
        return len(self._tokens.findall(text))


class StructureAwareChunker:
    def __init__(
        self,
        token_counter: TokenCounter,
        chunk_size: int = 512,
        overlap: int = 64,
    ) -> None:
        if chunk_size <= 0:
            raise ValueError("chunk_size must be positive")
        if overlap < 0 or overlap >= chunk_size:
            raise ValueError("overlap must be non-negative and smaller than chunk_size")
        self._token_counter = token_counter
        self._chunk_size = chunk_size
        self._overlap = overlap

    def chunk(
        self,
        document: SourceDocument,
        sections: list[ParsedSection],
    ) -> list[DocumentChunk]:
        chunks: list[DocumentChunk] = []
        ordinal = 0
        for section in sections:
            for content, line_start, line_end in self._split_section(document, section):
                prefix = self._prefix(document, section)
                chunk_content = f"{prefix}\n\n{content}" if prefix else content
                chunk_id = hashlib.sha256(
                    (
                        f"{document.source_uri}\n{document.content_hash}\n{ordinal}\n"
                        f"{chunk_content}"
                    ).encode()
                ).hexdigest()
                chunks.append(
                    DocumentChunk(
                        chunk_id=chunk_id,
                        source_uri=document.source_uri,
                        source_type=document.source_type,
                        source_hash=document.content_hash,
                        ordinal=ordinal,
                        path=section.path,
                        content=chunk_content,
                        line_start=line_start,
                        line_end=line_end,
                    )
                )
                ordinal += 1
        return chunks

    def _split_section(
        self,
        document: SourceDocument,
        section: ParsedSection,
    ) -> list[tuple[str, int, int]]:
        prefix = self._prefix(document, section)
        content_budget = max(1, self._chunk_size - self._token_counter.count(prefix))
        if self._token_counter.count(section.content) <= content_budget:
            return [(section.content, section.line_start, section.line_end)]

        lines = section.content.splitlines()
        parts: list[tuple[str, int, int]] = []
        start = 0
        while start < len(lines):
            end = start
            while end < len(lines):
                candidate = "\n".join(lines[start : end + 1])
                if end > start and self._token_counter.count(candidate) > content_budget:
                    break
                end += 1
                if self._token_counter.count(candidate) >= content_budget:
                    break
            end = max(start + 1, end)
            content = "\n".join(lines[start:end]).strip()
            parts.append(
                (
                    content,
                    section.line_start + start,
                    min(section.line_end, section.line_start + end - 1),
                )
            )
            if end >= len(lines):
                break
            overlap_start = end
            while overlap_start > start:
                candidate = "\n".join(lines[overlap_start - 1 : end])
                if self._token_counter.count(candidate) > self._overlap:
                    break
                overlap_start -= 1
            start = max(start + 1, overlap_start)
        return parts

    @staticmethod
    def _prefix(document: SourceDocument, section: ParsedSection) -> str:
        path = " > ".join(section.path)
        return f"source: {document.source_uri}\nsection: {path}" if path else ""
