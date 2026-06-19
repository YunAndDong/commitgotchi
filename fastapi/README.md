# Commit-Gotchi FastAPI 연동 가이드

이 문서는 Spring Boot 서버 개발자가 FastAPI AI 서버와 연동할 때 필요한 계약만 정리한다. Spring Boot는 System of Record이고, FastAPI는 AI 분석/채점/추천을 수행한 뒤 HTTP callback 또는 SQS 처리 결과로 Spring Boot에 돌려준다.

현재 캐릭터 이미지 생성 HTTP endpoint는 아직 없다.

## 빠른 확인

FastAPI Swagger:

```text
http://localhost:8000/docs
```

현재 FastAPI가 외부에서 받는 HTTP endpoint:

- `GET /api/health`
- `POST /api/internal/quizzes/grade`

FastAPI-only 계약 preview:

```bash
cd fastapi
.venv/bin/python scripts/integration_contract_preview.py
```

이 preview는 Spring Boot, AWS SQS, Gemini 없이 fake transport로 report callback payload와 quiz callback payload를 출력한다.

## 공통 원칙

- Spring Boot는 DB와 사용자/캐릭터/리포트/퀴즈 제출 상태의 원본이다.
- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- FastAPI callback destination은 env config로 만든다. 요청 body의 임의 `callbackUrl`은 destination으로 사용하지 않는다.
- internal auth를 켜면 양쪽 모두 `Authorization: Internal <secret>` 형식을 사용한다.
- FastAPI가 Spring Boot callback에서 `200 OK`를 받으면 성공으로 본다.
- report callback의 `scoreDelta`와 quiz callback의 `scoreDelta`는 서로 더하지 않는다.

## FastAPI env

```dotenv
SPRING_BOOT_INTERNAL_BASE_URL=http://localhost:8080
SPRING_INTERNAL_API_SECRET=
SPRING_REPORT_CALLBACK_PATH=/api/report
SPRING_QUIZ_GRADE_RESULT_PATH=/api/internal/quizzes/grade-result
SPRING_CALLBACK_TIMEOUT_SECONDS=10

AWS_REGION=ap-northeast-2
AWS_SQS_ENDPOINT=
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=
REPORT_REQUEST_QUEUE_URL=
REPORT_REQUEST_DLQ_URL=
```

Docker Compose 내부에서 FastAPI가 Spring Boot 컨테이너를 호출한다면 `SPRING_BOOT_INTERNAL_BASE_URL=http://springboot:8080`처럼 service name을 사용한다.

## 흐름 A: 일일 리포트 SQS

Spring Boot가 해야 할 일:

1. 일일 리포트 요청 message를 `REPORT_REQUEST_QUEUE_URL`에 보낸다.
2. FastAPI가 보낼 `POST /api/report` callback endpoint를 Spring Boot에 구현한다.
3. `requestId` 기준으로 멱등 처리한다.
4. callback 저장/점수 반영이 끝나면 `200 OK`를 반환한다.

FastAPI가 하는 일:

1. SQS message를 consume한다.
2. `generate_daily_report_result()`를 호출한다.
3. 결과에 `requestId`, `userId`, `characterId`, `targetDate`를 감싼다.
4. Spring Boot `POST /api/report`로 callback을 보낸다.
5. Spring Boot가 `200 OK`를 반환한 경우에만 SQS message를 삭제한다.

### SQS message

```json
{
  "requestId": "report-request-uuid",
  "userId": 1,
  "targetDate": "2026-06-16",
  "userMetadata": {
    "displayName": "user"
  },
  "characterMetadata": {
    "characterId": 10,
    "name": "커밋고치",
    "personality": "칭찬은 짧게, 부족한 부분은 명확히 짚어주는 성격",
    "currentStats": {
      "db": 120,
      "algorithm": 90,
      "cs": 80,
      "network": 60,
      "framework": 140
    }
  },
  "dailyReport": {
    "title": "JPA fetch join 복습",
    "content": "오늘은 JPA N+1 문제가 언제 생기는지 정리했다."
  }
}
```

필수 field:

- `requestId`
- `userId`
- `targetDate`
- `characterMetadata.characterId`
- `dailyReport.content`

넣지 말아야 할 field:

- quiz submission payload
- quiz grading result
- `gradings`

### Spring Boot가 받을 report callback

```http
POST /api/report
Authorization: Internal <SPRING_INTERNAL_API_SECRET>
Content-Type: application/json
```

```json
{
  "requestId": "report-request-uuid",
  "userId": 1,
  "characterId": 10,
  "targetDate": "2026-06-16",
  "status": "SUCCESS",
  "scoreDelta": {
    "db": 3,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 2
  },
  "emotion": "JOY",
  "statusMessage": "좋아요, 오늘 학습 내용이 캐릭터 성장에 반영됐어요.",
  "dailyReport": {
    "text": "분석 결과 본문",
    "feedback": "다음 학습 피드백"
  },
  "nextRecommendation": {
    "topics": ["fetch join", "batch size"],
    "rationale": "추천 이유"
  },
  "recommendedQuizzes": []
}
```

Spring Boot 응답 규칙:

- `200 OK`: FastAPI가 SQS message를 삭제한다.
- `5xx` 또는 timeout: FastAPI가 삭제하지 않는다. SQS 재전달/DLQ 정책에 맡긴다.
- `4xx`: poison message로 본다. FastAPI는 silent delete하지 않는다.

## 흐름 B: 퀴즈 채점

Spring Boot가 해야 할 일:

1. 사용자 quiz answer를 저장하고 submission 상태를 `GRADING` 계열로 둔다.
2. FastAPI `POST /api/internal/quizzes/grade`를 호출한다.
3. FastAPI가 보낼 `POST /api/internal/quizzes/grade-result` callback endpoint를 Spring Boot에 구현한다.
4. `submissionId` 기준으로 멱등 처리한다.

### Spring Boot가 FastAPI에 보낼 요청

```http
POST /api/internal/quizzes/grade
Authorization: Internal <SPRING_INTERNAL_API_SECRET>
Content-Type: application/json
```

```json
{
  "submissionId": "quiz-submission-uuid",
  "userId": 1,
  "characterId": 10,
  "quizId": 55,
  "problemId": 101,
  "question": "JPA N+1 문제란 무엇인가?",
  "modelAnswer": "연관 엔티티를 지연 로딩할 때 추가 쿼리가 반복해서 발생하는 문제다.",
  "userAnswer": "연관 데이터를 조회할 때 쿼리가 N번 더 나가는 문제입니다.",
  "scoreAllocation": {
    "db": 3,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 2
  },
  "characterMetadata": {
    "personality": "칭찬은 짧게, 부족한 부분은 명확히 알려주는 성격",
    "currentStats": {
      "db": 120,
      "algorithm": 90,
      "cs": 80,
      "network": 60,
      "framework": 140
    }
  },
  "callbackUrl": "http://localhost:8080/api/internal/quizzes/grade-result"
}
```

필수 field:

- `submissionId`
- `userId`
- `characterId`
- `quizId`
- `question`
- `modelAnswer`
- `userAnswer`
- `scoreAllocation`

FastAPI 즉시 응답:

```http
202 Accepted
```

```json
{
  "accepted": true,
  "submissionId": "quiz-submission-uuid"
}
```

`callbackUrl`은 호환성/관찰용 field로만 취급한다. FastAPI callback destination은 `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_QUIZ_GRADE_RESULT_PATH`다.

### Spring Boot가 받을 quiz callback

```http
POST /api/internal/quizzes/grade-result
Authorization: Internal <SPRING_INTERNAL_API_SECRET>
Content-Type: application/json
```

성공 예:

```json
{
  "submissionId": "quiz-submission-uuid",
  "userId": 1,
  "quizId": 55,
  "status": "GRADED",
  "scoreAllocation": {
    "db": 3,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 2
  },
  "scoreDelta": {
    "db": 3,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 2
  },
  "feedback": "N+1의 핵심을 잘 잡았습니다.",
  "emotion": "JOY",
  "statusMessage": "좋아요, 핵심은 잡았어요!"
}
```

실패/fallback 예:

```json
{
  "submissionId": "quiz-submission-uuid",
  "userId": 1,
  "quizId": 55,
  "status": "UNGRADED",
  "scoreAllocation": {
    "db": 3,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 2
  },
  "scoreDelta": {
    "db": 0,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 0
  },
  "feedback": "Gemini 호출 또는 구조화 출력 변환에 실패하여 채점하지 않았습니다.",
  "emotion": "SAD",
  "statusMessage": "AI가 잠깐 쉬는 중이에요. 답안은 저장됐어요.",
  "failedReason": "GRADER_ERROR"
}
```

Spring Boot는 `GRADED`일 때만 점수를 반영하고, `UNGRADED`에서는 submission 상태와 feedback/failure reason만 반영한다.

## 로컬 검증 방법

FastAPI-only preview:

```bash
cd fastapi
.venv/bin/python scripts/integration_contract_preview.py
```

## RAG Enhancement Benchmark

Story 7 benchmark는 A안 기준으로 실행했다. 즉, 최종 답변 문장 생성 품질을 평가하지 않고, 검색 결과가 어떤 문서/source를 얼마나 골고루 끌어오는지와 관련성 회귀 가드를 측정한다.

재현 명령:

```bash
cd fastapi
python3 scripts/rag_benchmark.py
```

산출물:

- `data/rag/reports/rag-enhancement-benchmark.md`
- `data/rag/reports/rag-enhancement-benchmark.json`

Headline 결과(embeddingMode=`fake`, callsGemini=`false`, 32차원 fake-hash embedding):

- Tier A concept top-k: B0 대비 final은 distinct source 4.2967 → 4.4505, ILD 0.2882 → 0.3563, source HHI 0.2642 → 0.2440으로 다양성/집중도는 일부 개선됐다.
- Tier A evidence bundle: distinct source 4.2967 → 7.4505, catalog coverage 0.3736 → 0.8352, same-source neighbor ratio 1.0000 → 0.5000으로 source-neighborhood 분포 개선은 강하게 확인됐다.
- Problem bank top-k: distinct source 2.4176 → 2.4615, MRR 0.0824 → 0.0989로 소폭 개선됐지만 catalog coverage는 0.6351 → 0.6081로 낮아졌다.
- Primary top-k pass/fail gate는 FAIL이다. 유의 개선은 ILD 1개뿐이고, Recall@k가 0.1374 → 0.1099로 epsilon 0.02보다 크게 하락했으며, worsened query ratio가 0.1758로 기준 0.10을 넘었다.
- 이 결과는 "분포/다양성 개선, 특히 evidence bundle의 소스 쏠림 완화"는 방어 가능하게 보여주지만, 현재 `relevanceGrades`가 비어 있어 relevance 지표는 `relevantSourcePaths` 기반 binary relevance다. 실제 의미 검색 정확도와 답변 생성 품질 평가는 후속 real embedding + graded relevance 작업이 필요하다.

Story 7에서 실행한 테스트:

```bash
cd fastapi
python3 -m unittest tests.rag.test_eval_metrics
python3 -m unittest tests.rag.test_rag_benchmark
python3 -m unittest tests.rag.test_diversity_eval
python3 -m unittest tests.rag.test_problem_bank_search
python3 -m unittest tests.rag.test_quiz_recommender
python3 -m unittest tests.scoring.test_daily_report_service
```

위 테스트는 2026-06-19에 모두 통과했다.

Integration tests:

```bash
cd fastapi
.venv/bin/python -m unittest \
  tests.integration.test_spring_client \
  tests.integration.test_spring_payloads \
  tests.integration.test_quiz_grading_endpoint \
  tests.integration.test_quiz_grading_webhook \
  tests.integration.test_report_consumer
```

FastAPI 서버 실행:

```bash
cd fastapi
.venv/bin/uvicorn app.main:app --reload
```

## 현재 제외 범위

- FastAPI `POST /api/report` endpoint는 만들지 않는다.
- Spring Boot DB 직접 접근은 하지 않는다.
- 캐릭터 이미지 생성 HTTP endpoint는 아직 없다.
- 실제 AWS SQS queue 생성, DLQ redrive policy, Spring Boot DB transaction 구현은 Spring Boot/infra 범위다.
