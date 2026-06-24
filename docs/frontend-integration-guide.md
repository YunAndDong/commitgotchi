# Commit-Gotchi 통합 검증 & 프론트엔드 연동 가이드

> 이 문서는 **로컬 통합 환경(docker compose)에서 architecture §4의 서버 간 계약 3종이
> 실제로 동작함을 엔드포인트 호출로 검증**한 결과 + 프론트엔드(Chrome 확장)가 소비할
> API 응답 형태를 정리한 것이다. 모든 예시는 실제 호출 응답을 캡처한 것이다.
>
> 검증일: 2026-06-24 / 환경: `docker compose up`(Spring Boot + FastAPI + PostgreSQL + worker + S3)

---

## 0. 검증 요약

| 흐름(계약) | 내용 | 상태 |
|-----------|------|------|
| **C** 캐릭터 이미지 (동기 HTTP) | 캐릭터 생성 시 FastAPI가 sprite 생성 → S3 업로드 → Spring이 조회 시 presigned GET URL 발급 | ✅ 검증 |
| **A** 일일 리포트 (비동기 SQS) | 리포트 작성 → SQS → FastAPI 워커가 Gemini 분석 → `/api/report` 콜백 → 점수·추천퀴즈 반영 | ✅ 검증 |
| **B** 퀴즈 채점 (비동기 웹훅) | 답안 제출 → FastAPI 채점(202) → `grade-result` 웹훅 → 점수·피드백 반영 | ✅ 검증 |
| **감정 연동** | 캐릭터 감정(JOY/SAD/ANGRY)에 따라 AI 답변 말투가 달라짐 | ✅ 검증 (JOY vs ANGRY 톤 차이 확인) |

> **effectively-once**: SQS at-least-once + `requestId`/`submissionId` 멱등으로 점수 중복 누적 방지(검증됨 — 같은 requestId 재처리 시 결과 미갱신).

---

## 1. 인증

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/auth/signup` | `{email, password}` → USER 생성 (201) |
| POST | `/api/auth/login` | `{email, password}` → `{accessToken, refreshToken, ...}` |

이후 모든 보호 API는 `Authorization: Bearer <accessToken>`.

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"LocalTestPw123!"}'
# → {"tokenType":"Bearer","accessToken":"eyJ...","accessTokenExpiresAt":"...","refreshToken":"..."}
```

---

## 2. 게임 상태 조회 (대시보드 핵심)

```
GET /api/game/state          (Bearer)
```

응답은 `{ "state": { characters, quizzes, reports, dailyReport, boardPosts, ... } }` 형태.
프론트는 `state` 안을 읽는다. 캐릭터 객체 실제 형태:

```json
{
  "id": 2,
  "name": "투덜이",
  "keyword": "grumpy red bird",
  "personality": "솔직하고 직설적인 성격",
  "stats": { "algo": 0, "cs": 0, "db": 0, "net": 2, "fw": 1 },
  "battlePower": 3,
  "emotion": "joy",
  "isEvolved": false,
  "imageStatus": "READY",
  "spriteSheetUrl": "https://commitgotchi-character-images-...s3...amazonaws.com/dev/characters/2/sprite-sheet.png?X-Amz-Algorithm=...&X-Amz-Signature=...",
  "spriteMeta": {
    "rows": 1, "columns": 3,
    "frameMap": { "joy": [0,0], "happy": [0,0], "sad": [0,1], "angry": [0,2] },
    "transparent": true
  },
  "active": true,
  "message": "흥, 6일이나 어디 갔었어? 오랜만에 왔으니 봐주는 거야. 네트워크 공부는 좀 했네?",
  "createdAt": "2026-06-24T05:54:11Z"
}
```

### 프론트 렌더 포인트
- **스프라이트**: `spriteSheetUrl`(1×3 시트)을 `spriteMeta.frameMap[emotion]`의 `[row, col]`로 잘라 현재 감정 프레임 표시.
  - `imageStatus`: `READY`(실제 생성 이미지) / `FALLBACK`(기본 sprite) / `PENDING`.
  - `spriteSheetUrl`은 **presigned URL이라 만료된다(기본 10분)**. 따라서 **항상 `GET /api/game/state` 조회 응답의 값을 그대로 사용**하고, 캐싱해 재사용하지 말 것(매 조회 시 새로 발급됨).
- **감정**: `emotion`(`joy`/`sad`/`angry`)에 맞는 프레임 + 말투. `message`도 감정 반영됨.
- **능력치/전투력**: `stats` 5종 + `battlePower`(=합).

---

## 3. 흐름 C — 캐릭터 생성 + 이미지 (동기)

```
POST /api/game/characters    (Bearer)
body: { "name", "keyword", "personality" }
```

- `keyword`로 FastAPI가 sprite 생성 → S3 `dev/characters/{id}/sprite-sheet.png` 업로드 → 응답/조회 시 presigned URL.
- **이미지 생성은 ~10초** 동기 소요. 첫 캐릭터는 자동 활성.
- 응답은 `state` + `item`(생성된 캐릭터) 형태. `item.imageStatus`가 `READY`면 성공, `FALLBACK`이면 기본 sprite.

> 검증: 생성 → S3 업로드 확인 → presigned URL로 `GET` 시 `200 image/png`로 실제 이미지 수신.

---

## 4. 흐름 A — 일일 리포트 (비동기)

### 4-1. 리포트 작성
```
POST /api/game/reports       (Bearer)
body: { "title", "content", "characterId", "tags": [..] }
```
- 작성 직후 `dailyReport.status = "analyzing"`(또는 pending). 분석은 비동기.
- 운영에서는 자정 스케줄러가 처리. **로컬/시연은 §6 디버그 엔드포인트로 즉시 처리**.

### 4-2. 분석 결과 (`state.dailyReport`)
처리 완료 후(`status: "ready"`) 실제 응답:
```json
{
  "status": "ready",
  "summary": "SSE와 JWT를 함께 사용할 때 프론트엔드에서 EventSource 객체로 인증 정보를 전달하기 어려운 문제에 대해 학습했습니다.",
  "feedback": "SSE는 기본적으로 표준 EventSource API를 사용하는데, 이 API는 커스텀 헤더를 지원하지 않아 JWT 인증을 구현할 때 까다로운 점이 있죠. 아주 중요한 네트워크적 고민을 하셨네요!",
  "deltas": { "algo": 0, "cs": 1, "db": 0, "net": 2, "fw": 1 },
  "nextRecommendation": {
    "topics": ["SSE 인증 우회 방법", "Query Parameter를 이용한 토큰 전달", "WebSocket 대안 검토"],
    "rationale": "EventSource의 헤더 제약 문제를 해결하기 위해 쿼리 파라미터로 토큰을 전달하는 방식이나, ..."
  }
}
```
- `deltas`: 학습 리포트 분석분 점수 변화량(필드별 0~10). 활성 캐릭터 능력치에 가산됨.
- `summary`/`feedback`/`nextRecommendation`: AI 생성 텍스트(감정 톤 반영, §5).
- 추천 퀴즈는 `state.quizzes`에 추가됨(§5 흐름 B 대상).

---

## 5. 흐름 B — 퀴즈 채점 (비동기 웹훅)

### 5-1. 답안 제출
```
POST /api/game/quizzes/{quizId}/submit   (Bearer)
body: { "userAnswer": "..." }
```
- 제출 → Spring이 FastAPI에 채점 요청(202) → 백그라운드 채점 → 웹훅으로 결과 반영. 비동기(수초).

### 5-2. 채점 결과 (`state.quizzes[]`)
```json
{
  "id": "q102", "tag": "net",
  "question": "TCP 3-way handshake에서 ...",
  "submitted": true, "scored": true, "correct": true,
  "feedback": "우와, 정말 완벽하게 대답해 주었네요! 마지막 세그먼트가 ACK라는 점과 그 역할까지 ... 정말 기특해요!",
  "deltaStat": "net", "deltaAmount": 2
}
```
- 프론트는 `scored`가 true가 될 때까지 폴링(또는 SSE) 후 `correct`/`feedback` 표시.
- `deltaStat`/`deltaAmount`: 획득 점수(해당 퀴즈 배점 범위 내).

---

## 6. 감정 연동 (검증 포인트)

캐릭터 `emotion`(JOY/SAD/ANGRY)이 AI 답변 **말투**에 반영된다. 같은 리포트, 감정만 다르게 실측:

| emotion | dailyReport.feedback (같은 학습 내용) |
|---------|----------------------------------------|
| **JOY** | "...네트워크 통신 구조와 인증 방식의 충돌을 **잘 짚어냈어요!**" (밝고 귀여운 칭찬) |
| **ANGRY** | "...고민해본 점은 좋은 시도예요. 네트워크 통신 구조에 대한 **이해가 더 필요하겠는걸요?**" (삐진 듯 지적하는 투덜 톤) |

캐릭터 `message`도 동일하게 감정 반영(예: ANGRY = "흥, 6일이나 어디 갔었어? ... 봐주는 거야"). 프론트는 별도 처리 없이 받은 텍스트를 그대로 표시하면 된다.

---

## 7. 시연/디버그 엔드포인트 (로컬 전용)

운영에서 리포트는 자정 배치로 처리되지만, **로컬/발표에서는 "오늘 리포트 작성 → 즉시 결과"** 를 위해 디버그 트리거를 둔다.

```
POST /api/debug/{token}/report/run-now?date=YYYY-MM-DD&emotion=JOY|SAD|ANGRY
```
- `token`: 고정 난수 경로 토큰(코드 `DebugController.DEMO_TOKEN`). **인증 없음, local/dev 프로파일에서만 존재**(`@Profile`). prod엔 빈 자체가 없음.
- `date` 생략 시 오늘. `emotion` 지정 시 감정 톤 시연(생략 시 작성 시점 감정).
- 동작: 해당 날짜 리포트 요청을 outbox 적재 → 강제 PENDING 재설정(재시연 가능) → SQS dispatch. 이후 워커가 분석·콜백.

```bash
curl -X POST "http://localhost:8080/api/debug/<DEMO_TOKEN>/report/run-now?emotion=ANGRY"
# → {"targetDate":"2026-06-24","emotionOverride":"ANGRY","dispatch":{"sentCount":1,...}, ...}
```

> ⚠️ **이 엔드포인트는 시연 전용 임시물이다.** 발표 후 내부 인증/ADMIN 가드로 교체하거나
> `DebugController` + SecurityConfig 의 `/api/debug/**` permitAll 을 함께 제거할 것
> (코드 주석의 TODO 참고).

---

## 8. 프론트엔드 체크리스트

- [ ] `spriteSheetUrl`은 **매 `/api/game/state` 응답값 사용**(presigned, 만료됨 — DB/로컬 캐싱 금지).
- [ ] `imageStatus`로 READY/FALLBACK/PENDING 분기 렌더.
- [ ] `spriteMeta.frameMap[emotion]`으로 현재 감정 프레임 슬라이스.
- [ ] 리포트/퀴즈는 비동기 → `status`/`scored` 폴링(또는 SSE `/api/game/...events`) 후 표시.
- [ ] AI 텍스트(`message`/`feedback`/`summary`)는 감정 톤이 이미 반영되어 있으니 그대로 표시.
- [ ] 확장프로그램 origin은 Spring CORS allowlist에 등록됨(API 도메인 + `chrome-extension://<고정 id>`).
</content>
