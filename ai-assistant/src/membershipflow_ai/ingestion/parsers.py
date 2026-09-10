from __future__ import annotations

import re
from collections.abc import Iterable
from typing import ClassVar, Protocol

import tree_sitter_java
from tree_sitter import Language, Node, Parser

from membershipflow_ai.domain.documents import ParsedSection, SourceDocument, SourceType


class DocumentParser(Protocol):
    def parse(self, document: SourceDocument) -> list[ParsedSection]: ...


class MarkdownParser:
    _heading = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
    _fence = re.compile(r"^\s*(```|~~~)")

    def parse(self, document: SourceDocument) -> list[ParsedSection]:
        lines = document.content.splitlines()
        if not lines:
            return []

        sections: list[ParsedSection] = []
        heading_stack: list[str] = []
        section_start = 0
        section_path: tuple[str, ...] = (document.source_uri,)
        fence_marker: str | None = None

        for index, line in enumerate(lines):
            fence_match = self._fence.match(line)
            if fence_match:
                marker = fence_match.group(1)
                if fence_marker is None:
                    fence_marker = marker
                elif fence_marker == marker:
                    fence_marker = None
                continue
            if fence_marker is not None:
                continue

            heading_match = self._heading.match(line)
            if heading_match is None:
                continue

            self._append_section(
                sections,
                lines,
                section_start,
                index,
                section_path,
            )
            level = len(heading_match.group(1))
            title = heading_match.group(2).strip()
            heading_stack = heading_stack[: level - 1]
            heading_stack.append(title)
            section_path = tuple(heading_stack)
            section_start = index

        self._append_section(sections, lines, section_start, len(lines), section_path)
        return sections

    @staticmethod
    def _append_section(
        sections: list[ParsedSection],
        lines: list[str],
        start: int,
        end: int,
        path: tuple[str, ...],
    ) -> None:
        content = "\n".join(lines[start:end]).strip()
        if not content:
            return
        sections.append(
            ParsedSection(
                path=path,
                content=content,
                line_start=start + 1,
                line_end=end,
            )
        )


class JavaParser:
    _container_types: ClassVar[set[str]] = {
        "class_declaration",
        "interface_declaration",
        "enum_declaration",
        "record_declaration",
    }
    _member_types: ClassVar[set[str]] = {
        "constructor_declaration",
        "method_declaration",
    }

    def __init__(self) -> None:
        language = Language(tree_sitter_java.language())
        self._parser = Parser(language)

    def parse(self, document: SourceDocument) -> list[ParsedSection]:
        source_bytes = document.content.encode("utf-8")
        tree = self._parser.parse(source_bytes)
        if tree.root_node.has_error:
            raise ValueError(f"invalid Java syntax: {document.source_uri}")
        package_name = self._package_name(tree.root_node, source_bytes)
        sections: list[ParsedSection] = []

        for node in self._walk(tree.root_node):
            if node.type in self._member_types:
                sections.append(self._member_section(node, source_bytes, package_name))

        for node in self._walk(tree.root_node):
            if node.type in self._container_types and not self._has_members(node):
                sections.append(self._node_section(node, source_bytes, package_name))

        if not sections:
            sections.append(
                ParsedSection(
                    path=(package_name, document.source_uri),
                    content=document.content,
                    line_start=1,
                    line_end=max(1, len(document.content.splitlines())),
                )
            )
        return sorted(sections, key=lambda section: (section.line_start, section.line_end))

    def _member_section(
        self,
        node: Node,
        source_bytes: bytes,
        package_name: str,
    ) -> ParsedSection:
        containers = self._container_path(node, source_bytes)
        name_node = node.child_by_field_name("name")
        member_name = self._text(name_node, source_bytes) if name_node else node.type
        path = tuple(part for part in (package_name, *containers, member_name) if part)
        # Keep section content source-aligned; context is carried in path.
        start = node.start_byte
        line_start = node.start_point.row + 1
        previous = node.prev_named_sibling
        if previous is not None and previous.type == "block_comment":
            if self._text(previous, source_bytes).startswith("/**"):
                start = previous.start_byte
                line_start = previous.start_point.row + 1
        content = source_bytes[start : node.end_byte].decode("utf-8")
        return ParsedSection(
            path=path,
            content=content,
            line_start=line_start,
            line_end=node.end_point.row + 1,
        )

    def _node_section(
        self,
        node: Node,
        source_bytes: bytes,
        package_name: str,
    ) -> ParsedSection:
        name_node = node.child_by_field_name("name")
        name = self._text(name_node, source_bytes) if name_node else node.type
        return ParsedSection(
            path=tuple(part for part in (package_name, name) if part),
            content=self._text(node, source_bytes),
            line_start=node.start_point.row + 1,
            line_end=node.end_point.row + 1,
        )

    def _container_path(self, node: Node, source_bytes: bytes) -> tuple[str, ...]:
        names: list[str] = []
        current = node.parent
        while current is not None:
            if current.type in self._container_types:
                name_node = current.child_by_field_name("name")
                if name_node is not None:
                    names.append(self._text(name_node, source_bytes))
            current = current.parent
        names.reverse()
        return tuple(names)

    def _has_members(self, node: Node) -> bool:
        return any(descendant.type in self._member_types for descendant in self._walk(node))

    @staticmethod
    def _package_name(root: Node, source_bytes: bytes) -> str:
        for child in root.children:
            if child.type == "package_declaration":
                text = source_bytes[child.start_byte : child.end_byte].decode("utf-8")
                return text.removeprefix("package").removesuffix(";").strip()
        return ""

    @staticmethod
    def _walk(node: Node) -> Iterable[Node]:
        yield node
        for child in node.children:
            yield from JavaParser._walk(child)

    @staticmethod
    def _text(node: Node, source_bytes: bytes) -> str:
        return source_bytes[node.start_byte : node.end_byte].decode("utf-8")


class ParserRegistry:
    def __init__(self) -> None:
        self._parsers: dict[SourceType, DocumentParser] = {
            SourceType.MARKDOWN: MarkdownParser(),
            SourceType.JAVA: JavaParser(),
        }

    def parse(self, document: SourceDocument) -> list[ParsedSection]:
        return self._parsers[document.source_type].parse(document)
