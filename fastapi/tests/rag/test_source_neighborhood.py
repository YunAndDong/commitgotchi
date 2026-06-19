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
            max_same_source_neighbors=5,
            min_cross_source=2,
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
        self.assertLessEqual(_source_count(neighborhood, seed.source_path), 5)
        self.assertGreaterEqual(_cross_source_count(neighborhood, seed.source_path), 2)
        self.assertNotIn("concept:sha256:unrelated", by_id)
        self.assertNotIn("concept:sha256:weak-db-related", by_id)

    def test_defaults_cap_same_source_neighbors_and_fill_cross_source_quota(self) -> None:
        store = ConceptCatalogStore(_neighborhood_chunks())
        seed = store.get("concept:sha256:n-plus-one")
        hits = [ConceptSearchHit(seed, 9.5, "embedding", ("jpa", "n+1", "lazy"))]

        neighborhood = build_source_neighborhood(
            hits,
            store=store,
            query_terms=("jpa", "n+1", "lazy", "fetch"),
            field_hints=("db", "framework"),
            max_chunks=8,
            max_chars=5000,
            max_text_chars_per_item=600,
        )

        self.assertLessEqual(_source_count(neighborhood, seed.source_path), 4)
        self.assertGreaterEqual(_cross_source_count(neighborhood, seed.source_path), 2)
        self.assertNotIn(
            "concept:sha256:n-plus-one",
            {item.chunk_id for item in neighborhood},
        )

    def test_does_not_fill_cross_source_quota_with_weak_signal(self) -> None:
        store = ConceptCatalogStore(_neighborhood_chunks())
        seed = store.get("concept:sha256:n-plus-one")
        hits = [ConceptSearchHit(seed, 9.5, "embedding", ())]

        neighborhood = build_source_neighborhood(
            hits,
            store=store,
            query_terms=("transaction",),
            field_hints=("db",),
            max_chunks=6,
            max_same_source_neighbors=2,
            min_cross_source=2,
            max_chars=5000,
        )

        self.assertEqual(_cross_source_count(neighborhood, seed.source_path), 0)
        self.assertLessEqual(_source_count(neighborhood, seed.source_path), 2)

    def test_cross_source_quota_does_not_count_other_match_sources(self) -> None:
        chunks = (
            _chunk(
                "seed-a",
                "03-framework/a.md",
                ("Framework", "A"),
                0,
                "Shared relational topic seed A.",
                ("db",),
                next_id="concept:sha256:a-nearby",
            ),
            _chunk(
                "a-nearby",
                "03-framework/a.md",
                ("Framework", "A", "Nearby"),
                1,
                "Shared relational topic around seed A.",
                ("db",),
            ),
            _chunk(
                "seed-b",
                "03-framework/b.md",
                ("Framework", "B"),
                0,
                "Shared relational topic seed B.",
                ("db",),
                next_id="concept:sha256:b-nearby",
            ),
            _chunk(
                "b-nearby",
                "03-framework/b.md",
                ("Framework", "B", "Nearby"),
                1,
                "Shared relational topic around seed B.",
                ("db",),
            ),
            _chunk(
                "external-related",
                "04-api/rest.md",
                ("API", "Related"),
                0,
                "Shared relational topic from an external source.",
                ("db", "network"),
            ),
        )
        store = ConceptCatalogStore(chunks)
        seed_a = store.get("concept:sha256:seed-a")
        seed_b = store.get("concept:sha256:seed-b")
        self.assertIsNotNone(seed_a)
        self.assertIsNotNone(seed_b)
        hits = [
            ConceptSearchHit(seed_a, 9.5, "embedding", ("shared", "relational")),
            ConceptSearchHit(seed_b, 9.0, "embedding", ("shared", "relational")),
        ]

        neighborhood = build_source_neighborhood(
            hits,
            store=store,
            query_terms=("shared", "relational"),
            field_hints=("db",),
            max_chunks=4,
            max_same_source_neighbors=1,
            min_cross_source=2,
            max_chars=5000,
        )

        seed_source_paths = {seed_a.source_path, seed_b.source_path}
        seed_source_count = sum(
            1 for item in neighborhood if item.source_path in seed_source_paths
        )
        self.assertLessEqual(seed_source_count, 1)
        self.assertIn(
            "concept:sha256:external-related",
            {item.chunk_id for item in neighborhood},
        )

    def test_common_metadata_terms_do_not_create_related_signal(self) -> None:
        store = ConceptCatalogStore(_neighborhood_chunks())
        seed = store.get("concept:sha256:n-plus-one")
        hits = [ConceptSearchHit(seed, 9.5, "embedding", ("framework",))]

        neighborhood = build_source_neighborhood(
            hits,
            store=store,
            query_terms=("report", "chunk", "field", "hints", "framework"),
            field_hints=("framework",),
            max_chunks=4,
            max_same_source_neighbors=0,
            min_cross_source=2,
            max_chars=5000,
        )

        self.assertEqual(neighborhood, [])

    def test_falls_back_to_same_source_cap_when_cross_source_missing(self) -> None:
        store = ConceptCatalogStore(_neighborhood_chunks())
        seed = store.get("concept:sha256:n-plus-one")
        hits = [ConceptSearchHit(seed, 9.5, "embedding", ())]

        neighborhood = build_source_neighborhood(
            hits,
            store=store,
            query_terms=("nonexistent",),
            field_hints=(),
            max_chunks=6,
            max_same_source_neighbors=3,
            min_cross_source=2,
            max_chars=5000,
        )

        self.assertEqual(_source_count(neighborhood, seed.source_path), 3)
        self.assertEqual(_cross_source_count(neighborhood, seed.source_path), 0)

    def test_selection_is_deterministic_and_preserves_output_shape(self) -> None:
        store = ConceptCatalogStore(_neighborhood_chunks())
        seed = store.get("concept:sha256:n-plus-one")
        hits = [ConceptSearchHit(seed, 9.5, "embedding", ("jpa", "n+1", "lazy"))]
        kwargs = {
            "store": store,
            "query_terms": ("jpa", "n+1", "lazy", "fetch"),
            "field_hints": ("db", "framework"),
            "max_chunks": 8,
            "max_chars": 5000,
            "max_text_chars_per_item": 600,
        }

        first = build_source_neighborhood(hits, **kwargs)
        second = build_source_neighborhood(hits, **kwargs)

        self.assertEqual([item.to_dict() for item in first], [item.to_dict() for item in second])
        self.assertTrue(first)
        self.assertEqual(
            set(first[0].to_dict()),
            {"reason", "chunkId", "sourcePath", "headingPath", "fieldHints", "text"},
        )

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
            "hibernate-related",
            "03-framework/hibernate.md",
            ("Spring Framework", "Hibernate Batch Fetch"),
            0,
            "Hibernate batch fetching mitigates JPA N+1 lazy loading with grouped SQL selects.",
            ("db", "framework"),
        ),
        _chunk(
            "api-related",
            "04-api/rest.md",
            ("API", "Pagination"),
            0,
            "API pagination can avoid N+1 query patterns when DTO mapping touches lazy associations.",
            ("db", "network"),
        ),
        _chunk(
            "weak-db-related",
            "03-framework/database-basics.md",
            ("Database", "Transactions"),
            0,
            "Database indexes and transactions improve relational storage performance.",
            ("db",),
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


def _source_count(neighborhood, source_path: str) -> int:
    return sum(1 for item in neighborhood if item.source_path == source_path)


def _cross_source_count(neighborhood, source_path: str) -> int:
    return sum(1 for item in neighborhood if item.source_path != source_path)


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
