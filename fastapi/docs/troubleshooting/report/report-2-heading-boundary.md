# Report 2 Troubleshooting - Markdown Heading Boundary

## Symptom

Report chunks may merge two Markdown heading sections when the heading text is short or not recognized as a topic hint.

Example:

```md
# 트랜잭션 격리
격리 수준별 현상을 정리했다.

## 정규화
1NF와 2NF 차이를 정리했다.
```

Expected behavior is two chunks: one for `트랜잭션 격리`, one for `정규화`.

## Cause

The chunk merge logic previously allowed a short current section to merge with the next heading when the current text had no strong topic signal. That made heading boundaries depend on topic hint extraction, even though Markdown headings should be strong boundaries by themselves.

## Fix

`fastapi/app/scoring/report_chunker.py` now only allows a short prefix paragraph, such as a report title, to merge into the first heading. Once a heading section has started, the next heading opens a new chunk regardless of topic hint strength.

## Regression Test

Covered by `test_markdown_heading_boundary_does_not_depend_on_topic_hints` in:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_report_chunker
```
