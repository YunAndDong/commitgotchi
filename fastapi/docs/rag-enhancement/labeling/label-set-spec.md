---
title: RAG 평가 라벨셋 표준 인터페이스 명세 (문서당 1질의 · 91개)
status: ready-for-dev
created: 2026-06-18
owner: FastAPI AI 서버
epic: rag-enhancement
related_docs:
  - ../rag-enhancement-evaluation-methodology.md
  - ../stories/rag-enhancement-1-diversity-eval-baseline.md
---

# RAG 평가 라벨셋 표준 인터페이스 명세

Claude Code와 Codex가 **각자 독립적으로** 라벨셋을 생성한 뒤 사용자가 비교·머지할 수 있도록, 두 산출물이 **동일 스키마·동일 앵커·행 정렬 가능**하도록 고정하는 명세다. 이 문서가 양쪽 생성기의 SSOT다.

## 1. 목표와 설계

- 이 RAG 작업의 목적은 "프롬프팅 시 **많은 문서를 골고루** 끌어다 쓰기"다. 따라서 평가도 **문서 커버리지** 기준으로 짠다.
- 라벨 단위는 필드가 아니라 **문서(source)**다. 카탈로그의 **distinct source 91개 각각에 정확히 1개의 질의**를 앵커링한다.
- 결과: 라벨셋만으로 `Catalog Coverage`(검색된 distinct source / 91)가 **구조적으로 전수 측정**된다. 각 질의는 "이 문서가 검색에 닿는가?"를 문서별 recall로 검증한다.
- 필드(`db/algorithm/cs/network/framework`) 균등은 **목표가 아니다.** 필드 분포는 코퍼스를 그대로 반영(=framework-heavy)해도 정상이며, framework 쏠림 점검용 **보조 태그**로만 둔다.
- **이름·위치 명확화:** 이 91개는 "전체 평가셋"이 아니라 **source coverage golden set (Tier A)** 이다. 주 지표는 `Recall@k`·`Hit@k`·`MRR`·source coverage이고, **nDCG(순위 품질)는 이 셋으로 증명하지 않는다**(필요 시 별도 Tier B 자연질의 라벨셋으로 보강 — 선택). 비라벨 다양성 스윕(Tier C, 150~300)은 하니스 자동 생성으로 별도 유지. tier 구조는 [평가 방법론 §2](../rag-enhancement-evaluation-methodology.md) 참조.

## 2. 입력 (공통 SSOT)

- 카탈로그: `fastapi/data/rag/catalog/chunks.jsonl` (필드: `sourcePath`, `headingPath`, `text`, `fieldHints`, `chunkId`, `chunkIndex`)
- 대상 source 집합 = `chunks.jsonl`의 distinct `sourcePath` **91개**. 아래로 추출한다(둘 다 동일하게):

```bash
cd fastapi
python3 -c "import json;print('\n'.join(sorted({json.loads(l)['sourcePath'] for l in open('data/rag/catalog/chunks.jsonl')})))"
```

- 폴더 분포(참고): 11-cloud 21, 03-frontend 17, 01-cs 16, 02-backend 9, 04-system-design 8, 05-ai 7, 06~10·99 각 2, README.md 1.
- `README.md`도 1개 source로 포함(전수 91). 저가치라 판단되면 사용자가 머지 단계에서 제외 가능 — **생성 단계에서는 임의 제외 금지.**

## 3. 출력

- 파일: 각 에이전트가 자기 파일에만 쓴다. **상대 파일을 읽지 않는다(독립성).**
  - Claude Code → `fastapi/docs/rag-enhancement/labeling/queries.claude.jsonl`
  - Codex → `fastapi/docs/rag-enhancement/labeling/queries.codex.jsonl`
- 형식: **JSONL**, 한 줄 = 1 질의 record. 정확히 **91줄**.
- 정렬: `sourcePath` 오름차순(위 추출 명령의 정렬 순서와 동일)으로 출력 → 두 파일이 **행 단위로 정렬돼 diff·비교가 쉬움**.

## 4. Record 스키마 (고정)

```json
{
  "queryId": "q-<source-slug>",
  "sourcePath": "01-computer-science-fundamentals/network/qna-network.md",
  "sourceTier": "core",
  "reportText": "백엔드 면접 준비 중인데, 클라이언트가 보낸 요청이 서버까지 안정적으로 도착하는 과정이 헷갈린다. 연결을 처음 맺을 때 양쪽이 주고받는 신호 순서와, 네트워크가 막힐 때 전송량을 조절하는 원리를 알고 싶다.",
  "expectedTopics": ["TCP", "3-way handshake", "혼잡 제어"],
  "expectedFields": ["network"],
  "relevantSourcePaths": ["01-computer-science-fundamentals/network/qna-network.md"],
  "relevanceGrades": {},
  "notes": ""
}
```

| 키 | 타입 | 규칙 |
| --- | --- | --- |
| `queryId` | string | `"q-" + slug(sourcePath)`. **slug 규칙(고정)**: 소문자화 → 영숫자/한글 외 문자를 `-`로 치환 → 연속 `-` 축약 → 양끝 `-` 제거. 두 에이전트가 같은 source에 대해 **반드시 동일한 queryId**를 만든다(행 정렬 키). |
| `sourcePath` | string | 앵커 문서. 91개 중 하나, **중복·누락 없이 1회씩**. |
| `sourceTier` | string | `"core"` / `"low"` / `"appendix"` 중 하나. 그 문서가 면접/학습 가치가 높은 핵심 자료면 `core`, 짧거나 주변적이면 `low`, README·인덱스·메타 성격이면 `appendix`. **aggregate 점수를 전수 vs core-subset으로 나눠 보기 위한 태그** — 저가치 문서가 품질 판단을 흐리지 않게 한다. |
| `reportText` | string | **사용자 고민/질문형** 한국어, 1~3문장. 아래 §5 작성 규칙을 따른다. |
| `expectedTopics` | string[] | 그 문서의 실제 주제 2~4개(heading/내용 기반). |
| `expectedFields` | string[] | `{db,algorithm,cs,network,framework}` 중. 문서 `fieldHints`/판단 기반. 보조 태그. 비어도 허용. |
| `relevantSourcePaths` | string[] | **반드시 앵커 `sourcePath` 포함.** 명백히 같은 주제를 다루는 타 문서가 있으면 추가 가능(최대 3개). |
| `relevanceGrades` | object | **빈 객체 `{}`로 둔다.** 등급(0~3)은 이후 pooling 단계에서 부여. |
| `notes` | string | 선택. 짧은 근거/주의. 없으면 `""`. |

## 5. 제약·품질 기준

- 정확히 91 record, `sourcePath` 집합 == 카탈로그 91개와 **완전 일치**(중복·누락 0).
- 가상의 주제 금지 — 반드시 그 문서에 실제로 있는 내용으로.
- 결정적: 같은 입력에서 같은 queryId. JSON은 UTF-8, ensure_ascii=False 권장.

### reportText 작성 규칙 (self-retrieval 순환 방지 — 중요)

질의를 "문서를 요약/인용한 문장"으로 쓰면 검색이 당연히 그 문서를 찾아 평가가 뻥튀기된다. **"그 문서가 답해줄 사용자의 실제 고민/질문"** 처럼 써야 한다.

- **사용자 문제 상황형으로 작성.** "오늘 ~를 정리했다"보다 "~가 헷갈린다 / ~를 어떻게 해야 하나" 같은 학습자의 막힌 지점.
- **청크 본문 문장·희귀 구문·heading 제목을 직인용 금지.** 연속 15자 이상 동일 문자열 금지.
- **정답 문서명/파일명/경로를 암시하지 말 것.** (예: "qna-network 문서에 따르면" 금지)
- **lexical overlap 자가 점검:** `reportText`와 앵커 청크 본문의 토큰 겹침이 과도하면(예: 내용어 50%+ 그대로) 다시 쓴다. 동의어·일반어로 풀어쓸 것.
- 한국어 문장(영어 기술 용어 혼용 허용). 1~3문장.

좋은 예 / 나쁜 예:
- ✗ (요약·인용형) "TCP 3-way handshake는 SYN, SYN-ACK, ACK 순으로 연결을 맺는다."
- ✓ (고민형) "서버에 연결할 때 패킷을 몇 번 주고받아야 연결이 맺어지는지, 중간에 막히면 전송량을 어떻게 줄이는지 잘 모르겠다."

## 6. 독립 생성 & 병렬 수행

- Claude Code와 Codex는 **동시·독립**으로 생성한다. 서로의 출력 파일을 참조하지 않는다.
- 동일 스키마·동일 queryId 규칙 → 두 파일은 91행이 같은 순서로 정렬돼 **행 단위 비교** 가능.
- 상관된 오류(둘 다 흔한 주제로 쏠림)는 사용자 판단으로 거른다. 비교 관점:
  - 같은 source에 대해 `reportText`/`expectedTopics`가 더 그 문서를 잘 대표하는 쪽,
  - `relevantSourcePaths`가 타당한 쪽,
  - 필요 시 두 안을 머지.

## 7. 검증 (생성 후 자가 점검)

```bash
cd fastapi
python3 - <<'PY'
import json
src={json.loads(l)['sourcePath'] for l in open('data/rag/catalog/chunks.jsonl')}
rows=[json.loads(l) for l in open('docs/rag-enhancement/labeling/queries.<agent>.jsonl')]
got=[r['sourcePath'] for r in rows]
assert len(rows)==len(src)==91, (len(rows),len(src))
assert set(got)==src, "source 집합 불일치"
assert len(got)==len(set(got)), "source 중복"
for r in rows:
    assert r['sourcePath'] in r['relevantSourcePaths'], r['queryId']
    assert r['sourceTier'] in {'core','low','appendix'}, r['queryId']
    assert isinstance(r['relevanceGrades'],dict) and not r['relevanceGrades']
print("OK 91 records, source 전수 일치")
PY
```

## 8. 후속 (이 명세 범위 밖)

- 머지: 사용자가 두 파일을 비교해 최종 `fastapi/tests/fixtures/rag/eval/queries.jsonl` 확정.
- pooling 등급: baseline+개선본 top-k를 풀링해 `relevanceGrades`를 채움(Story 1/7).
- (선택) 비라벨 다양성 스윕 150~300: HHI/분포를 더 크게 보고 싶을 때 heading 자동 생성으로 추가.
