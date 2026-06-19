# RAG Diversity Baseline

## Metadata

- Generated at: `2026-06-18T10:33:39.639772+00:00`
- Git commit: `e5622cf`
- Embedding mode: `fake`
- Catalog: `data/rag/catalog/chunks.jsonl` (870 chunks, 91 sources)
- Problem bank: `data/rag/catalog/problems.jsonl` (798 problems, 74 sources)
- Tier A fixture: `tests/fixtures/rag/eval/queries.jsonl` (91 queries)
- Tier C sweep: 300 generated/input queries
- topK: 5, problemTopK: 3, maxNeighborhoodChunks: 8

## Tier A Fixture Validation

- Records: 91
- Catalog sources: 91
- Missing sources: 0
- Extra sources: 0
- Duplicate sources: 0
- Sorted by sourcePath: True

## Tier A Source Coverage Guard

| Path | Avg distinct source | HHI | Field cov. | Folder cov. | Catalog cov. | Same-source neighbor | Recall@k | Hit@k | MRR | Source cov. |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Concept top-k | 4.2967 | 0.2642 | 3.0989 | 3.5165 | 0.3736 | 0.0000 | 0.1374 | 0.2418 | 0.1060 | 0.2198 |
| Concept evidence bundle | 4.2967 | 0.2732 | 3.8901 | 3.5165 | 0.3736 | 1.0000 | 0.1374 | 0.2418 | 0.1060 | 0.2198 |
| Problem bank top-k | 2.4176 | 0.4872 | 2.8681 | 1.2967 | 0.6351 | 0.0000 | 0.0824 | 0.1648 | 0.0824 | 0.1319 |

## Tier C Unlabeled Diversity Sweep

| Path | Avg distinct source | HHI | Field cov. | Folder cov. | Catalog cov. | Same-source neighbor |
| --- | --- | --- | --- | --- | --- | --- |
| Concept top-k | 4.0900 | 0.2835 | 3.2267 | 3.2600 | 0.4396 | 0.0000 |
| Concept evidence bundle | 4.0900 | 0.2899 | 4.1100 | 3.2600 | 0.4396 | 1.0000 |
| Problem bank top-k | 2.4233 | 0.4844 | 2.4800 | 2.1133 | 0.7838 | 0.0000 |

## Notes

- This Story 1 baseline only measures the current retrieval behavior; search, recommendation, and catalog logic were not modified.
- `fake` embedding mode is deterministic and does not call Gemini. Use `--embedding-mode sidecar` to evaluate with the checked-in sidecar embeddings and real query embeddings.
