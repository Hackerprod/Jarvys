#!/usr/bin/env python3
"""Reproducible text/token overlap audit for Jarvys UI sources vs. Kelivo reference.

The comparison is diagnostic rather than a proof of provenance: each reported
match must be reviewed in context. The tool reports exact user-visible copy and
cross-language token-shingle overlap, along with file hashes and thresholds.
"""

from __future__ import annotations

import argparse
import fnmatch
import html
import json
import re
import sys
import unicodedata
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


SOURCE_EXTENSIONS = {".kt", ".java", ".xml", ".md"}
REFERENCE_EXTENSIONS = {".dart", ".xml", ".json", ".arb", ".md", ".yaml", ".yml"}
STRING_RE = re.compile(r"(?:\"([^\"\n]*)\"|'([^'\n]*)')")
TOKEN_RE = re.compile(r"[A-Za-z][A-Za-z0-9]*|[\u00C0-\u024F]+|[0-9]+")
CAMEL_RE = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|[_\-./:]+")
COMMENT_RE = re.compile(r"(?s)/\*.*?\*/|//[^\n]*|<!--[\s\S]*?-->")

STOP_WORDS = {
    "a", "an", "and", "as", "at", "be", "by", "class", "composable", "const",
    "data", "default", "else", "false", "for", "fun", "function", "get", "if",
    "import", "in", "internal", "is", "it", "let", "modifier", "null", "object",
    "override", "private", "protected", "public", "return", "set", "static", "this",
    "true", "val", "var", "void", "widget", "with", "widgetbuilder", "widgetbuild",
    "context", "buildcontext", "state", "key", "child", "children", "padding",
    "alignment", "color", "modifier", "string", "bool", "int", "double", "list",
    "map", "unit", "dp", "sp", "compose", "flutter", "androidx", "material", "material3",
}
ALLOWED_COMMON_PHRASES = {
    "simplified chinese",
    "traditional chinese",
    "authorization required",
    "connection error",
    "get current location",
    "permission denied",
    "preparing export",
}


@dataclass(frozen=True)
class Document:
    path: Path
    relative: str
    text: str


def documents(root: Path, extensions: set[str], includes: list[str]) -> list[Document]:
    result = []
    for path in sorted(p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in extensions):
        relative = path.relative_to(root).as_posix()
        if includes and not any(fnmatch.fnmatch(relative, pattern) for pattern in includes):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        result.append(Document(path, relative, text))
    return result


def phrase_normalize(value: str) -> str:
    value = html.unescape(value).replace("\\n", " ").replace("\\t", " ")
    value = unicodedata.normalize("NFKC", value).lower()
    return " ".join(TOKEN_RE.findall(value))


def visible_strings(doc: Document) -> set[str]:
    source = COMMENT_RE.sub(" ", doc.text)
    found = set()
    suffix = doc.path.suffix.lower()
    if suffix == ".xml":
        values = re.findall(r"<string(?:\s[^>]*)?>(.*?)</string>", source, flags=re.S)
    elif suffix == ".arb":
        try:
            values = [value for key, value in json.loads(source).items()
                      if not key.startswith("@") and isinstance(value, str)]
        except (ValueError, TypeError):
            values = []
    else:
        values = [match.group(1) if match.group(1) is not None else match.group(2)
                  for match in STRING_RE.finditer(source)]
    for value in values:
        normalized = phrase_normalize(value)
        if len(normalized) >= 16 and sum(character.isalpha() for character in normalized) >= 10:
            found.add(normalized)
    return found


def canonical_tokens(text: str) -> list[str]:
    text = COMMENT_RE.sub(" ", text)
    output = []
    for raw in TOKEN_RE.findall(text):
        for part in CAMEL_RE.split(raw):
            token = part.lower()
            if len(token) > 2 and token not in STOP_WORDS and not token.isdigit():
                output.append(token)
    return output


def shingles(tokens: list[str], width: int) -> set[tuple[str, ...]]:
    if len(tokens) < width:
        return set()
    return {tuple(tokens[index:index + width]) for index in range(len(tokens) - width + 1)}


def overlap_scores(
    sources: list[Document], references: list[Document], width: int,
) -> dict[str, list[tuple[float, str, int, int]]]:
    reference_sets = {doc.relative: shingles(canonical_tokens(doc.text), width) for doc in references}
    index: dict[tuple[str, ...], set[str]] = defaultdict(set)
    for filename, token_set in reference_sets.items():
        for token_shingle in token_set:
            index[token_shingle].add(filename)

    results = {}
    for source in sources:
        source_shingles = shingles(canonical_tokens(source.text), width)
        intersections: dict[str, int] = defaultdict(int)
        for token_shingle in source_shingles:
            for filename in index.get(token_shingle, ()):
                intersections[filename] += 1
        ranked = []
        for filename, count in intersections.items():
            reference_size = len(reference_sets[filename])
            union = len(source_shingles) + reference_size - count
            score = count / union if union else 0.0
            ranked.append((score, filename, count, reference_size))
        results[source.relative] = sorted(ranked, reverse=True)[:3]
    return results


def audit(args: argparse.Namespace) -> tuple[str, bool]:
    source_root = args.source.resolve()
    reference_root = args.reference.resolve()
    if not source_root.is_dir():
        raise SystemExit(f"Source path is not a directory: {source_root}")
    if not reference_root.is_dir():
        raise SystemExit(f"Reference path is not a directory: {reference_root}")

    source_docs = documents(source_root, SOURCE_EXTENSIONS, args.include)
    reference_docs = documents(reference_root, REFERENCE_EXTENSIONS, [])
    if not source_docs or not reference_docs:
        raise SystemExit("Both source and reference trees must contain readable text files")

    strings_by_reference: dict[str, set[str]] = defaultdict(set)
    for doc in reference_docs:
        for value in visible_strings(doc):
            strings_by_reference[value].add(doc.relative)

    allowed_phrases = ALLOWED_COMMON_PHRASES | {phrase_normalize(value) for value in args.allow_exact}
    exact_matches = []
    unreviewed_exact_matches = []
    for doc in source_docs:
        for value in sorted(visible_strings(doc)):
            refs = strings_by_reference.get(value)
            if refs:
                match = (doc.relative, value, sorted(refs))
                exact_matches.append(match)
                if value not in allowed_phrases:
                    unreviewed_exact_matches.append(match)

    scores = overlap_scores(source_docs, reference_docs, args.shingle_width)
    high_overlap = []
    for filename, ranked in scores.items():
        for score, refname, shared, reference_size in ranked:
            if score >= args.fail_threshold:
                high_overlap.append((filename, score, refname, shared, reference_size))

    lines = [
        "# Kelivo/Jarvys UI similarity audit",
        "",
        f"Source root: `{source_root}`",
        f"Reference root: `{reference_root}`",
        f"Included source patterns: {', '.join(f'`{pattern}`' for pattern in args.include) or 'all supported source files'}",
        f"Source documents: {len(source_docs)}; reference documents: {len(reference_docs)}",
        f"Token shingle width: {args.shingle_width}; review threshold: {args.fail_threshold:.3f}",
        "",
        "## Exact visible-copy overlaps",
        "",
    ]
    if exact_matches:
        for source, phrase, refs in exact_matches:
            category = "allowed common label" if phrase in allowed_phrases else "needs review"
            lines.append(f"- {category}: `{source}` ↔ {', '.join(f'`{ref}`' for ref in refs)} — “{phrase}”")
    else:
        lines.append("- None.")
    if unreviewed_exact_matches:
        lines.append("")
        lines.append("Exact overlaps not in the common-label allowlist:")
        for source, phrase, refs in unreviewed_exact_matches:
            lines.append(f"- `{source}` ↔ {', '.join(f'`{ref}`' for ref in refs)} — “{phrase}”")

    lines.extend(["", "## Highest token-shingle overlaps per source file", ""])
    for source in sorted(scores):
        candidates = [item for item in scores[source] if item[0] >= args.report_threshold]
        if not candidates:
            continue
        for score, reference, shared, reference_size in candidates:
            lines.append(f"- `{source}` ↔ `{reference}` — Jaccard {score:.3f}; shared {shared} shingles")

    lines.extend([
        "",
        "## Review notes",
        "",
        "Exact phrases and high-overlap pairs require contextual review; common product terms, required protocol labels, and platform APIs can be false positives.",
        "The report deliberately does not treat a low lexical score as legal or provenance proof.",
        "",
    ])
    failed = bool(high_overlap or unreviewed_exact_matches)
    if high_overlap or unreviewed_exact_matches:
        lines.append("Threshold status: **REVIEW REQUIRED**")
        for source, score, reference, shared, reference_size in sorted(high_overlap, reverse=True):
            lines.append(f"- `{source}` ↔ `{reference}` — Jaccard {score:.3f} ({shared} shared shingles)")
    else:
        lines.append("Threshold status: no pair met the configured token-overlap threshold.")
    return "\n".join(lines) + "\n", failed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("app/src/main"),
                        help="Jarvys main source/resource root (default: app/src/main)")
    parser.add_argument("--reference", type=Path, required=True,
                        help="Kelivo source/resource tree to compare (for example lib)")
    parser.add_argument("--include", action="append", default=[],
                        help="source-root-relative glob; may be repeated to limit the audit scope")
    parser.add_argument("--allow-exact", action="append", default=[],
                        help="known common phrase to accept (normalized before comparison)")
    parser.add_argument("--report", type=Path, help="write the Markdown report to this path")
    parser.add_argument("--shingle-width", type=int, default=5)
    parser.add_argument("--report-threshold", type=float, default=0.04)
    parser.add_argument("--fail-threshold", type=float, default=0.22)
    args = parser.parse_args()
    if args.shingle_width < 2:
        parser.error("--shingle-width must be at least 2")
    report, failed = audit(args)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report, encoding="utf-8")
    sys.stdout.write(report)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
