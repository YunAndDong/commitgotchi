from __future__ import annotations

import unittest

from app.rag.concept_search import ConceptSearchHit
from app.rag.concept_store import ConceptCatalogStore
from app.rag.schemas import ConceptChunkRecord, ConceptNeighbors
from app.rag.source_neighborhood import build_source_neighborhood


class SourceNeighborhoodTest(unittest.TestCase):
    def test_expands_previous_next_parent_sibling_nearby_and_same_folder_related(self) -> None:
        store = ConceptCatalogStore(_neighborhood_chunks())
        seed = store.get("concept:sha256:n-plus-one")
        self.assertIsNotNone(seed)
        hits = [
            ConceptSearchHit(
                chunk=seed,
                score=9.5,
                search_mode="embedding",
                matched_terms=("jpa", "n+1", "lazy"),
            )
        ]

        neighborhood = build_source_neighborhood(
            hits,
            store=store,
            query_terms=("jpa", "n+1", "lazy", "fetch"),
            field_hints=("db", "framework"),
            max_chunks=8,
            max_chars=5000,
            max_text_chars_per_item=600,
        )

        by_id = {item.chunk_id: item for item in neighborhood}
        self.assertIn("concept:sha256:lazy-loading", by_id)
        self.assertEqual(by_id["concept:sha256:lazy-loading"].reason, "previous_chunk")
        self.assertIn("concept:sha256:fetch-join", by_id)
        self.assertEqual(by_id["concept:sha256:fetch-join"].reason, "next_chunk")
        reasons = {item.reason for item in neighborhood}
        self.assertIn("parent_heading", reasons)
        self.assertIn("sibling_heading", reasons)
        self.assertIn("same_source_nearby", reasons)
        self.assertIn("same_folder_related", reasons)
        self.assertNotIn("concept:sha256:n-plus-one", by_id)
        self.assertEqual(len(by_id), len(neighborhood))
        self.assertNotIn("concept:sha256:unrelated", by_id)

    def test_same_folder_related_requires_signal(self) -> None:
        store = ConceptCatalogStore(_neighborhood_chunks())
        seed = store.get("concept:sha256:n-plus-one")
        hits = [ConceptSearchHit(seed, 9.5, "embedding", ())]

        neighborhood = build_source_neighborhood(
            hits,
            store=store,
            query_terms=(),
            field_hints=(),
            max_chunks=8,
            max_chars=5000,
        )

        self.assertNotIn("same_folder_related", {item.reason for item in neighborhood})

    def test_caps_neighborhood_chunks_and_character_budget(self) -> None:
        store = ConceptCatalogStore(_neighborhood_chunks())
        seed = store.get("concept:sha256:n-plus-one")
        hits = [ConceptSearchHit(seed, 9.5, "embedding", ("jpa",))]

        neighborhood = build_source_neighborhood(
            hits,
            store=store,
            query_terms=("jpa",),
            field_hints=("framework",),
            max_chunks=2,
            max_chars=180,
            max_text_chars_per_item=80,
        )

        self.assertLessEqual(len(neighborhood), 2)
        total_chars = len(seed.text[:80]) + sum(len(item.text) for item in neighborhood)
        self.assertLessEqual(total_chars, 180)
        self.assertTrue(all(len(item.text) <= 80 for item in neighborhood))


def _neighborhood_chunks() -> tuple[ConceptChunkRecord, ...]:
    return (
        _chunk(
            "jpa-parent",
            "03-framework/jpa.md",
            ("Spring Framework", "JPA"),
            0,
            "JPA parent heading explains ORM, persistence context, transaction boundaries, and entity mapping.",
            ("db", "framework"),
            next_id="concept:sha256:lazy-loading",
            siblings=(("Spring Framework", "EntityGraph"),),
        ),
        _chunk(
            "lazy-loading",
            "03-framework/jpa.md",
            ("Spring Framework", "JPA", "Lazy Loading"),
            1,
            "Lazy Loading delays database access and can hide additional SQL queries.",
            ("db", "framework"),
            prev_id="concept:sha256:jpa-parent",
            next_id="concept:sha256:n-plus-one",
            parent=("Spring Framework", "JPA"),
            siblings=(("Spring Framework", "JPA", "N+1"), ("Spring Framework", "JPA", "Fetch Join")),
        ),
        _chunk(
            "n-plus-one",
            "03-framework/jpa.md",
            ("Spring Framework", "JPA", "N+1"),
            2,
            "N+1 happens when Lazy Loading repeatedly fetches related rows for each entity.",
            ("db", "framework"),
            prev_id="concept:sha256:lazy-loading",
            next_id="concept:sha256:fetch-join",
            parent=("Spring Framework", "JPA"),
            siblings=(("Spring Framework", "JPA", "Lazy Loading"), ("Spring Framework", "JPA", "Fetch Join")),
        ),
        _chunk(
            "fetch-join",
            "03-framework/jpa.md",
            ("Spring Framework", "JPA", "Fetch Join"),
            3,
            "Fetch join loads associations with the first query and is a common N+1 mitigation.",
            ("db", "framework"),
            prev_id="concept:sha256:n-plus-one",
            next_id="concept:sha256:jpa-nearby",
            parent=("Spring Framework", "JPA"),
            siblings=(("Spring Framework", "JPA", "Lazy Loading"), ("Spring Framework", "JPA", "N+1")),
        ),
        _chunk(
            "jpa-nearby",
            "03-framework/jpa.md",
            ("Spring Framework", "JPA", "Batch Size"),
            4,
            "Batch size and EntityGraph can reduce SQL round trips in ORM workloads.",
            ("db", "framework"),
            prev_id="concept:sha256:fetch-join",
            parent=("Spring Framework", "JPA"),
        ),
        _chunk(
            "entitygraph",
            "03-framework/jpa.md",
            ("Spring Framework", "EntityGraph"),
            5,
            "EntityGraph selects association fetch plans for JPA queries.",
            ("db", "framework"),
            parent=("Spring Framework",),
            siblings=(("Spring Framework", "JPA"),),
        ),
        _chunk(
            "spring-related",
            "03-framework/spring-data.md",
            ("Spring Framework", "Spring Data JPA"),
            0,
            "Spring Data JPA repository methods can trigger N+1 unless fetch plans are explicit.",
            ("db", "framework"),
        ),
        _chunk(
            "unrelated",
            "03-framework/react.md",
            ("Frontend", "React"),
            0,
            "React component rendering, hooks, virtual DOM updates, and frontend state management.",
            ("framework",),
        ),
    )


def _chunk(
    suffix: str,
    source_path: str,
    heading_path: tuple[str, ...],
    index: int,
    text: str,
    field_hints: tuple[str, ...],
    *,
    prev_id: str | None = None,
    next_id: str | None = None,
    parent: tuple[str, ...] = (),
    siblings: tuple[tuple[str, ...], ...] = (),
) -> ConceptChunkRecord:
    return ConceptChunkRecord(
        chunk_id=f"concept:sha256:{suffix}",
        source_path=source_path,
        source_root="/tmp/source",
        heading_path=heading_path,
        heading_level=len(heading_path),
        chunk_index=index,
        text=text,
        content_hash=f"sha256:{suffix}",
        field_hints=field_hints,
        neighbors=ConceptNeighbors(
            previous_chunk_id=prev_id,
            next_chunk_id=next_id,
            parent_heading_path=parent,
            sibling_heading_paths=siblings,
        ),
    )


if __name__ == "__main__":
    unittest.main()
