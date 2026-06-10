# Addendum — commitgotchi

> 사용자가 제공한 "YunNDong MVP 백엔드 설계 요청" 문서에서 추출한 **다운스트림(PRD·Architecture) 전달용** 기술 상세. brief 본문에는 넣지 않지만 보존해 다음 단계로 넘긴다.

## 시스템 구성 및 담당

- **PostgreSQL** — 사용자, 캐릭터, 학습 리포트, 퀴즈 및 모범답안, 퀴즈 제출 결과, 공유 게시글, 리뷰 저장.
- **Spring Boot 서버 (담당: 김윤석)** — 가입·로그인·JWT 인증, 사용자/캐릭터 관리, 활성 캐릭터 단일성 보장, 학습 기록 저장, AWS SQS에 레포트 생성 요청 적재, FastAPI 처리 결과 수신, 캐릭터 능력치·감정·진화 반영, 랭킹·대시보드 API, 게시글·리뷰 CRUD.
- **FastAPI AI 서버 (담당: 신동운)** — SQS에서 레포트 생성 요청 단건 조회·처리, AI 일일 레포트 생성, 다음 학습 추천, 퀴즈 추천, 제출 답안 채점·피드백, 결과를 Spring Boot API로 전달.

## 캐릭터 시스템 규칙

- 능력치 5종: DB, 알고리즘, CS, 네트워크, 프레임워크. 전투력 = 5개 능력치 총합.
- 진화: 기본/진화 2단계. 능력치 총합 1,000점 이상 시 진화. 캐릭터당 최대 1회.
- 감정: 기쁨 / 슬픔 / 화남 중 하나.
- 캐릭터 생성: 사용자 입력 키워드·성격 기반 AI 이미지 생성. 실패 시 기본 이미지 세트 사용.
- 사용자당 최대 3개 보유, 동시 활성 1개. 학습 점수는 활성 캐릭터에만 반영.

## 일일 레포트 생성 흐름

처리 시점:
- 사용자는 하루 동안 학습 리포트 작성.
- 매일 자정 이후 Spring Boot가 레포트 생성 요청을 SQS에 적재.
- FastAPI가 SQS 메시지를 단건씩 처리.
- 생성 결과는 매일 오전 9시까지 사용자에게 제공.

처리 단계:
1. Spring Boot → 사용자별 레포트 생성 요청을 SQS에 적재.
2. FastAPI → SQS 메시지 조회.
3. FastAPI → AI로 레포트·점수 변화량·추천 학습·추천 퀴즈 생성.
4. FastAPI → Spring Boot `POST /api/internal/reports/result` 호출.
5. Spring Boot → 결과 저장 + 활성 캐릭터에 점수 반영.
6. 성공 시 FastAPI가 SQS 메시지 삭제.

### SQS 입력 메시지 예시

```json
{
  "requestId": "report-request-uuid",
  "userId": 1,
  "targetDate": "2026-06-06",
  "userMetadata": {
    "weeklyStudyStreak": "0100011",
    "weeklyScoreChanges": {
      "db": 0, "algorithm": 3, "cs": 0, "network": 1, "framework": 0
    }
  },
  "characterMetadata": {
    "characterId": 10,
    "name": "커밋 몬스터",
    "personality": "칭찬을 많이 하지만 틀린 부분은 명확하게 지적하는 성격"
  },
  "dailyReport": {
    "title": "오늘 학습 기록",
    "content": "Spring JPA의 N+1 문제와 해결 방법을 공부했다."
  }
}
```

## 다음 단계에서 설계할 핵심 계약 (Architecture 단계 입력)

- 내부 API `POST /api/internal/reports/result` 의 요청/응답 스키마.
- 퀴즈 제출 답안 채점 흐름의 동기/비동기 여부 및 API 계약.
- SQS 큐 구성, 재시도·DLQ·멱등성(requestId) 처리.
- 점수 반영 트랜잭션과 활성 캐릭터 단일성 보장 방식.
- AI 처리 실패 fallback 정책(이미지/채점/리포트 각각).

## 미해결 질문 (Open Questions)

- 퀴즈 채점은 일일 배치(SQS) 경로인가, 제출 즉시 동기 채점인가? (원문은 두 기능을 분리 명시하나 채점 트리거 시점이 불명확)
- "주간 점수 변화량"과 "일일 점수 반영"의 관계 — 점수는 일 단위 누적인가 주 단위 집계인가?
- 진화 시 능력치·이미지 변화 규칙의 구체값.
