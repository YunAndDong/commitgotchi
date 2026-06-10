# Addendum — commitgotchi PRD

> PRD 본문에 넣지 않는 기술 상세(시스템 구성·메시지 스키마·내부 계약). 아키텍처 단계 입력. brief 단계 애드덤을 계승하고 PRD에서 확정된 결정을 반영했다.

## 확정된 결정 (이번 단계) — **개정 반영**

- **D1 — 퀴즈 채점 시점 [개정]:** **제출 즉시 동기 채점**(흐름 B). 사용자가 답안을 내면 곧바로 채점·피드백·점수 반영이 일어나고 결과가 즉시 표시된다. (기존: 자정 배치 → 변경)
- **D2 — 점수 누적 모델:** 일 단위 누적. 주간 점수 변화량은 표시·통계·AI 컨텍스트 용도로만 집계. 점수 출처는 흐름 A(학습 리포트)·흐름 B(퀴즈)로 분리하며 상호 배타(이중계상 금지). (brief 애드덤 Open Question 2 해소)
- **D3 — 진화 규칙:** 전투력 1,000점 도달 시 1회 진화. MVP는 스프라이트시트 행 전환(유아형→진화형) + 진화 상태 플래그만, 추가 능력치 보너스 없음(제안값). (brief 애드덤 Open Question 3 일부 해소)
- **D4 — 퀴즈 제출 처리 [개정]:** 제출 시 답안 저장(동기) → 곧바로 FastAPI 동기 채점 호출 → 점수 즉시 반영. 자정 배치는 퀴즈 채점을 하지 않고, 그날 채점된 결과를 종합 코멘트에만 활용.
- **D5 — 캐릭터 이미지 생성 [신규]:** 캐릭터 생성 직후 전용 큐로 비동기-즉시 처리(자정 배치 아님). 6프레임 2×3 스프라이트시트(진화 2 × 감정 3). 실패 시 기본 스프라이트 세트.

## 시스템 구성 및 담당

- **PostgreSQL** — 사용자, 캐릭터, 학습 리포트, 퀴즈 및 모범답안, 퀴즈 제출 결과, 공유 게시글, 리뷰 저장.
- **Spring Boot 서버 (담당: 김윤석)** — 가입·로그인·JWT 인증, 사용자/캐릭터 관리, 활성 캐릭터 단일성 보장, 학습 기록 저장, **퀴즈 즉시 채점 중계(동기)**, **캐릭터 이미지 생성 요청 적재(비동기-즉시)**, 자정 리포트 SQS 적재, FastAPI 처리 결과 수신, 캐릭터 능력치·감정·진화 반영, 랭킹·대시보드 API, 게시글·리뷰 CRUD.
- **FastAPI AI 서버 (담당: 신동운)** — 리포트 SQS 단건 처리·AI 일일 레포트 생성·다음 학습 추천·퀴즈 추천(흐름 A), **퀴즈 답안 즉시 채점·피드백(동기, 흐름 B)**, **캐릭터 스프라이트 이미지 생성(비동기-즉시, 흐름 C)**, 결과를 Spring Boot API로 전달.
- **AWS SQS** — 큐 2종: `report-request-queue`(자정 리포트), `character-image-queue`(이미지 생성). 퀴즈 채점은 큐 없이 동기 HTTP.

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

처리 단계(흐름 A):
1. Spring Boot → 그날 학습 리포트 + 이미 채점된 퀴즈 결과 요약을 모아 사용자별 요청을 `report-request-queue`에 적재.
2. FastAPI → SQS 메시지 조회.
3. FastAPI → AI로 레포트·점수 변화량(학습분)·추천 학습·추천 퀴즈 생성 + 그날 퀴즈 결과 종합 코멘트(채점 재수행 아님).
4. FastAPI → Spring Boot `POST /api/internal/reports/result` 호출.
5. Spring Boot → 결과 저장 + 활성 캐릭터에 학습 점수 일 단위 누적 반영.
6. 성공 시 FastAPI가 SQS 메시지 삭제.

### SQS 입력 메시지 예시 (흐름 A — 리포트)

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
    "personality": "칭찬을 많이 하지만 틀린 부분은 명확하게 지적하는 성격",
    "currentStats": { "db": 120, "algorithm": 200, "cs": 80, "network": 60, "framework": 140 }
  },
  "dailyReport": {
    "title": "오늘 학습 기록",
    "content": "Spring JPA의 N+1 문제와 해결 방법을 공부했다."
  },
  "todayQuizResults": [
    { "quizId": 55, "question": "JPA N+1 문제란?", "score": 7, "maxScore": 10, "feedback": "..." }
  ]
}
```

> 흐름 B(퀴즈 즉시 채점, `POST /api/internal/quizzes/grade`)와 흐름 C(이미지 생성, `character-image-queue` + `POST /api/internal/characters/image-result`)의 상세 스키마는 **아키텍처 §4.3·§4.4**에 확정돼 있다.

## 핵심 계약 — Architecture 단계에서 확정됨 (아키텍처 §4 참조)

- ✅ `POST /api/internal/reports/result` 요청/응답 스키마(흐름 A).
- ✅ 퀴즈 즉시 채점 동기 계약 `POST /api/internal/quizzes/grade`(흐름 B).
- ✅ 캐릭터 이미지 생성 계약: 큐 메시지 + `POST /api/internal/characters/image-result`(흐름 C).
- ✅ SQS 큐 2종 구성, 재시도·DLQ·멱등성(requestId/submissionId/imageRequestId).
- ✅ 점수 반영 트랜잭션·활성 단일성·이중계상 금지(흐름 A·B 출처 분리).
- ✅ Fallback 정책(이미지/채점/리포트 흐름별 구체 동작).

## 잔여 미해결 질문 (PRD §8과 연동)

- 같은 날 학습 리포트 재작성 정책(하루 1개·덮어쓰기 가정).
- 감정 산정 임계 + 흐름 A·B 동시 갱신 시 최종 감정 우선순위.
- 진화 시 능력치 보너스 구체값(MVP 보너스 없음 제안).
- 퀴즈 답안 재제출 마감·점수 롤백 정책(당일 자정 잠금 제안).
- 기본 스프라이트 세트의 개수(레이아웃은 6프레임 2×3로 확정).
