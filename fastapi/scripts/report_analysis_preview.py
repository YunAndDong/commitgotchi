from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path
from typing import Any, Mapping


FASTAPI_ROOT = Path(__file__).resolve().parents[1]
if str(FASTAPI_ROOT) not in sys.path:
    sys.path.insert(0, str(FASTAPI_ROOT))

from app.rag.concept_embeddings import GeminiEmbeddingClient  # noqa: E402
from app.rag.concept_store import ConceptCatalogStore, load_concept_catalog_store  # noqa: E402
from app.rag.concept_search import build_report_evidence_bundles  # noqa: E402
from app.rag.embedding_store import (  # noqa: E402
    ConceptEmbeddingStore,
    EmbeddedConceptChunk,
    load_concept_embedding_store,
)
from app.rag.schemas import ConceptChunkRecord  # noqa: E402
from app.rag.text import extract_terms  # noqa: E402
from app.scoring.report_analyzer import (  # noqa: E402
    analyze_daily_report,
    build_report_analysis_prompt,
)
from app.scoring.report_chunker import chunk_daily_report  # noqa: E402


FAKE_EMBEDDING_MODEL = "fake-hash-embedding"
FAKE_EMBEDDING_DIMENSIONS = 32


class FakeEmbeddingClient:
    def __init__(self, dimensions: int = FAKE_EMBEDDING_DIMENSIONS):
        self.dimensions = dimensions

    def embed_document(self, text: str) -> tuple[float, ...]:
        return fake_embedding(text, dimensions=self.dimensions)

    def embed_query(self, text: str) -> tuple[float, ...]:
        return fake_embedding(text, dimensions=self.dimensions)


def build_preview_output(
    *,
    report_title: str | None,
    report_content: str | None,
    user_metadata: Mapping[str, Any] | None = None,
    character_personality: str | None = None,
    character_metadata: Mapping[str, Any] | None = None,
    embedding_mode: str = "fake",
    analysis_mode: str = "prompt-only",
    show_evidence: bool = False,
) -> dict[str, Any]:
    catalog_store = load_concept_catalog_store()
    embedding_store, embedding_client = _prepare_embedding_components(
        embedding_mode,
        catalog_store=catalog_store,
    )
    report_chunks = chunk_daily_report(report_title, report_content)
    evidence_bundles = build_report_evidence_bundles(
        report_chunks,
        store=catalog_store,
        embedding_store=embedding_store,
        client=embedding_client,
    )
    evidence_dicts = [bundle.to_dict() for bundle in evidence_bundles]
    prompt = build_report_analysis_prompt(
        report_title=report_title,
        report_content=report_content,
        report_chunks=report_chunks,
        evidence_bundles=evidence_dicts,
        user_metadata=user_metadata,
        character_personality=character_personality,
        character_metadata=character_metadata,
    )

    if analysis_mode == "prompt-only":
        analysis: Mapping[str, Any] = {
            "status": "PROMPT_ONLY",
            "message": "Gemini text generation was skipped.",
        }
    elif analysis_mode == "gemini":
        analysis = analyze_daily_report(
            report_title=report_title,
            report_content=report_content,
            user_metadata=user_metadata,
            character_personality=character_personality,
            character_metadata=character_metadata,
            report_chunks=report_chunks,
            evidence_bundles=evidence_bundles,
        )
    else:
        raise ValueError(f"unknown analysis mode: {analysis_mode}")

    output: dict[str, Any] = {
        "analysisMode": analysis_mode,
        "embeddingMode": embedding_mode,
        "analysis": dict(analysis),
        "prompt": prompt,
    }
    if show_evidence:
        output["reportChunks"] = [chunk.to_dict() for chunk in report_chunks]
        output["evidenceBundles"] = evidence_dicts
    return output


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Preview report chunking, RAG evidence, and Gemini report analysis JSON locally.",
    )
    parser.add_argument("--title", default=None, help="Report title.")
    parser.add_argument("--content", default=None, help="Report content.")
    parser.add_argument(
        "--input-json",
        type=Path,
        default=None,
        help="JSON file with reportTitle, reportContent, userMetadata, characterPersonality, characterMetadata.",
    )
    parser.add_argument(
        "--embedding-mode",
        choices=("fake", "sidecar", "real"),
        default="fake",
        help="fake avoids Gemini embeddings; sidecar/real use sidecar embeddings plus the current Gemini query embedder.",
    )
    parser.add_argument(
        "--analysis-mode",
        choices=("prompt-only", "gemini"),
        default="prompt-only",
        help="prompt-only skips Gemini text generation; gemini calls analyze_daily_report().",
    )
    parser.add_argument("--output-json", type=Path, default=None)
    parser.add_argument("--output-prompt", type=Path, default=None)
    parser.add_argument("--show-evidence", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report_input = _load_report_input(args.input_json)
    title = args.title if args.title is not None else _optional_text(report_input.get("reportTitle"))
    content = args.content if args.content is not None else _optional_text(report_input.get("reportContent"))

    output = build_preview_output(
        report_title=title,
        report_content=content,
        user_metadata=_optional_mapping(report_input.get("userMetadata")),
        character_personality=_optional_text(report_input.get("characterPersonality")),
        character_metadata=_optional_mapping(report_input.get("characterMetadata")),
        embedding_mode=args.embedding_mode,
        analysis_mode=args.analysis_mode,
        show_evidence=args.show_evidence,
    )

    if args.output_prompt is not None:
        args.output_prompt.parent.mkdir(parents=True, exist_ok=True)
        args.output_prompt.write_text(str(output["prompt"]), encoding="utf-8")
    if not args.show_evidence:
        output = {
            key: value
            for key, value in output.items()
            if key not in {"reportChunks", "evidenceBundles"}
        }
    if args.output_json is not None:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(_json_dumps(output), encoding="utf-8")

    print(_json_dumps(output))
    return 0


def _prepare_embedding_components(
    embedding_mode: str,
    *,
    catalog_store: ConceptCatalogStore,
) -> tuple[ConceptEmbeddingStore, Any | None]:
    if embedding_mode == "fake":
        store = _generate_fake_embedding_store(catalog_store)
        return store, FakeEmbeddingClient(store.expected_dimensionality)
    if embedding_mode in {"sidecar", "real"}:
        return load_concept_embedding_store(store=catalog_store), GeminiEmbeddingClient()
    raise ValueError(f"unknown embedding mode: {embedding_mode}")


def _generate_fake_embedding_store(
    store: ConceptCatalogStore,
    *,
    dimensions: int = FAKE_EMBEDDING_DIMENSIONS,
) -> ConceptEmbeddingStore:
    items = tuple(
        EmbeddedConceptChunk(
            chunk=chunk,
            embedding=fake_embedding(_fake_embedding_input(chunk), dimensions=dimensions),
        )
        for chunk in store.records
    )
    return ConceptEmbeddingStore(
        items=items,
        expected_model=FAKE_EMBEDDING_MODEL,
        expected_dimensionality=dimensions,
    )


def fake_embedding(text: str, *, dimensions: int = FAKE_EMBEDDING_DIMENSIONS) -> tuple[float, ...]:
    if dimensions <= 0:
        raise ValueError("dimensions must be positive")
    vector = [0.0] * dimensions
    terms = extract_terms(text)
    if not terms:
        vector[0] = 1.0
        return tuple(vector)
    for term in terms:
        digest = hashlib.sha256(term.encode("utf-8")).digest()
        bucket = int.from_bytes(digest[:4], "big") % dimensions
        vector[bucket] += 1.0
    norm = math.sqrt(sum(value * value for value in vector))
    if norm == 0.0:
        vector[0] = 1.0
        return tuple(vector)
    return tuple(value / norm for value in vector)


def _fake_embedding_input(chunk: ConceptChunkRecord) -> str:
    return "\n".join(
        (
            chunk.source_path,
            " > ".join(chunk.heading_path),
            ", ".join(chunk.field_hints),
            chunk.text,
        )
    )


def _load_report_input(path: Path | None) -> dict[str, Any]:
    if path is None:
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("--input-json must contain a JSON object")
    return payload


def _optional_mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _optional_text(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)


def _json_dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    raise SystemExit(main())
