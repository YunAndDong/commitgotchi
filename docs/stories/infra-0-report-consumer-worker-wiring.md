---
story: INFRA-0
status: draft
scope: app/infra (report SQS consumer를 실행 가능한 워커로 배선)
phase: Phase 2 (앞단)
plan: ../mvp-cicd-pipeline-plan.md
refs:
  - ../../fastapi/docs/integration/stories/integration-3-report-sqs-consumer-callback.md
related_files:
  - fastapi/app/integration/report_consumer.py   # 기존 (run_report_worker 등)
  - fastapi/app/integration/report_worker.py      # 신규 (entrypoint)
  - docker-compose.yml                            # worker 서비스 추가
---

# Story INFRA-0: report consumer 워커 배선 (별도 프로세스 + compose 서비스)

Status: draft

## 배경 / 문제

`fastapi/app/integration/report_consumer.py`에 컨슈머 로직은 완성·테스트돼 있다
(`run_report_worker`(while 루프), `run_report_worker_once`, `poll_report_request_queue`,
`create_report_sqs_client`). **그러나 이를 실제로 기동하는 진입점이 없다:**
`app/main.py`는 uvicorn으로 HTTP 서버만 띄우고, `Dockerfile` CMD도 웹뿐이며,
`docker-compose.yml`에 worker 서비스가 없다. → 지금 `docker compose up` 하면 흐름 A(리포트 SQS 소비)가 돌지 않는다.

## Story

As a 운영자,
I want report consumer를 FastAPI HTTP 서버와 **분리된 프로세스**로 기동하는 entrypoint와 compose 서비스를,
so that 통합 실행/배포에서 리포트 SQS 흐름 A가 실제로 동작한다.

## 범위

- **워커 entrypoint**: `fastapi/app/integration/report_worker.py`(또는 동등). `__main__`/`main()`에서 `run_report_worker(...)`를 호출하는 **얇은 래퍼**. 큐 URL 누락은 safe config error로 보고(secret 비노출).
- **compose worker 서비스**: `docker-compose.yml`에 `fastapi-report-worker` 추가. **fastapi와 같은 이미지** + `command:`로 워커 entrypoint 실행(웹과 분리). DB/네트워크 공유, `REPORT_REQUEST_QUEUE_ENABLED`로 on/off.
- **SQS는 local 기존 큐 재사용**(plan §6.7). 로컬에서 실 SQS 자격증명 또는 localstack(`AWS_SQS_ENDPOINT`).

## 설계 주의사항 (확정)

1. **별도 프로세스로 기동** — uvicorn HTTP 앱이나 HTTP background task에 끼우지 않는다(`integration-3` AC33과 동일).
2. **VisibilityTimeout > 리포트 생성 + Spring callback 시간** — 처리 중 메시지가 재전달되지 않도록 큐 속성을 충분히 길게(SQS 큐 생성 시 = INFRA-2). 코드 기본 `WaitTimeSeconds=20`, `MaxNumberOfMessages=1`.
3. **중복 전달은 정상** — SQS Standard at-least-once. `requestId` 멱등은 **Spring Boot가 소유**(유지). 워커는 Spring `200 OK`에서만 메시지 삭제.
4. **처리량 확장은 후속** — 필요 시 `MaxNumberOfMessages`를 최대 10 batch 또는 worker 수 확장. MVP는 1 유지(각 메시지 delete 결정은 독립).

## Acceptance Criteria

### AC1 — 분리 기동
- **When** worker entrypoint를 실행하면(또는 compose worker 서비스 up),
- **Then** HTTP 서버와 **독립 프로세스**로 `run_report_worker` 루프가 돌고, HTTP 앱(uvicorn)은 영향받지 않는다.

### AC2 — 통합 실행에서 흐름 A 동작
- **When** `docker compose up`(REPORT_REQUEST_QUEUE_ENABLED=true, 로컬 큐 설정),
- **Then** 큐 메시지를 소비해 `generate_daily_report_result` → Spring `POST /api/report` 콜백까지 수행한다.

### AC3 — 안전성
- **Then** 큐 URL 누락 시 secret 노출 없이 config error로 종료. Spring `200`에서만 delete(5xx/timeout/4xx/invalid는 비삭제, 기존 분류 유지).
- **And** 기존 web 라우트/테스트는 회귀 없음.

## 검증 / 보류
- 로컬: 큐에 테스트 메시지 넣고 worker가 콜백까지 수행하는지 확인(또는 localstack).
- 🔶 prod에서도 같은 worker 서비스를 `docker-compose.prod.yml`에 둘지(권장) — INFRA-1 overlay에서 반영.
- VisibilityTimeout 실제 값은 INFRA-2 큐 생성 시 확정.
