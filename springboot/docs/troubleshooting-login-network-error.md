# 트러블슈팅 — 크롬 확장에서 로그인/회원가입 실패

작성일: 2026-06-14

최근 갱신: 2026-06-23 — 운영 origin 모델을 same-origin reverse proxy 기본안으로 확정하고 env matrix 연결을 추가했다.

확장 팝업에서 로그인·회원가입이 안 되던 문제를 추적한 기록. 증상 메시지가 **두 단계로 바뀌며**
원인이 둘로 드러났다. 결론부터: ① 빌드에 API 주소가 안 박힘, ② 떠 있는 백엔드가 확장 origin을
**Spring Security CORS 단계에서 403으로 거부**.

## 증상

확장 팝업의 로그인/회원가입에서 진행이 안 되고, 디버깅 과정에서 메시지가 순서대로 바뀌었다.

1. 처음: `네트워크 오류 — 잠시 후 다시 시도해 주세요.`
2. (①을 고친 뒤): `오류가 발생했어요. 잠시 후 다시 시도해 주세요.`

`localhost:5173`(Vite dev 서버)에서는 로그인/회원가입이 **정상 동작**하고, 크롬 확장에서만 실패했다.

## 메시지로 원인 좁히기 (코드 근거)

`vue/src/stores/auth.js`의 `authMessage()`가 에러 종류로 문구를 가른다.

```js
export function authMessage(e) {
  if (!(e instanceof ApiError)) return '네트워크 오류 — 잠시 후 다시 시도해 주세요.'
  switch (e.code) {
    case 'AUTH_INVALID_CREDENTIALS': ...
    case 'USER_EMAIL_CONFLICT': ...
    case 'VALIDATION_FAILED': ...
    default: return '오류가 발생했어요. 잠시 후 다시 시도해 주세요.'   // ← ApiError지만 모르는 code
  }
}
```

`vue/src/api/client.js`의 `raw()`는 **서버 응답을 받았을 때만** `ApiError(status, code, ...)`를 던진다.
따라서:

- **"네트워크 오류"** = `fetch()`가 응답을 못 받고 `TypeError`를 던짐 → 요청이 백엔드에 **도달조차 못함**.
- **"오류가 발생했어요"** = 서버가 응답을 돌려줬고(=`ApiError`), 그 `code`가 프론트 switch에 없음
  → 도달은 했는데 **거부당함**.

즉 메시지가 1→2로 바뀐 건 "안 닿던 게 → 닿았는데 거부됨"으로 진전한 신호였다.

## 원인 ① — 빌드에 API 주소(`VITE_API_BASE_URL`)가 비어 있었다

`vue/`에 `.env.local`이 없어서 Vite가 `VITE_API_BASE_URL`을 못 읽었다. (Vite는 레포 루트가 아니라
**`vue/` 폴더** 기준으로 env를 읽으므로 루트 `.env`는 무시된다.) `client.js`는
`const BASE = import.meta.env?.VITE_API_BASE_URL || ''`라, `BASE=''`이면 요청이 상대경로
`/api/auth/login`으로 나가고, 확장 페이지에서는 이게 **`chrome-extension://<id>/api/auth/login`**
(= 확장 자기 자신)으로 해석돼 무조건 실패 → "네트워크 오류".

**해결:** `vue/.env.local` 생성 후 재빌드.

```bash
# vue/.env.local
VITE_API_BASE_URL=http://localhost:8080
```

```bash
cd vue && npm run build      # dist 번들에 http://localhost:8080 이 박힘
```

> `.env.local`은 `.gitignore` 대상이라 각 개발자가 로컬에서 한 번 만들어야 한다(`.env.example` 참고).
> 확인: `grep -r "localhost:8080" vue/dist/assets` 에 결과가 나오면 주소가 박힌 것.

## 원인 ② — Spring Security가 확장 origin을 CORS 403으로 거부 (핵심)

①을 고치자 요청이 백엔드에 닿았고, 이번엔 **`status=403, code=none`** 이 떴다. 이 조합이 결정적
단서다.

- `403` + 본문에 JSON `code`가 없음(`code=none`) = 스프링 시큐리티 CORS 처리기가 내는
  **`403 Invalid CORS request`** 평문 응답이다. (`raw()`는 본문이 JSON이 아니면 `code=undefined`로
  둔다 → `code=none`.)
- 즉 요청이 보안 필터 체인 앞단의 **CORS 검사에서 거부**됐다는 뜻. 인증·비밀번호 문제가 아니다.

소스(`CommitgotchiCorsConfiguration`)는 신뢰 확장 origin을 항상 allowlist에 추가하는데도 거부됐다는
건, **그때 떠 있던 백엔드가 그 코드(=확장 origin 허용)를 반영하지 않은 상태**로 돌고 있었다는
뜻이다. `localhost:5173`은 늘 허용 목록이라 동작했고, 확장 origin만 막혔다.

### 진단 (둘을 curl로 구분)

```bash
# 확장 origin → 거부면 403 / Access-Control-Allow-Origin 헤더 없음
curl -i http://localhost:8080/api/health \
  -H 'Origin: chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn'

# 비교용: 5173 origin → Access-Control-Allow-Origin 정상 반환
curl -i http://localhost:8080/api/health \
  -H 'Origin: http://localhost:5173'
```

첫 번째에 `Access-Control-Allow-Origin: chrome-extension://...`이 안 오면 ②가 확정이다. 동시에
`chrome://extensions`에서 확장의 **실제 ID**가 `daijhhcaecladkkpcjdlfgcokohehhmn`와 같은지도 확인한다
(다르면 그 실제 ID를 백엔드 allowlist에 넣어야 한다).

### 해결

떠 있는 백엔드를 **현재 코드로 다시 빌드·재기동**해서 확장 origin 허용 설정이 실제로 적용되게 한다.

```bash
docker compose up -d --build       # springboot 이미지 새로 빌드 후 재기동
# 또는 로컬 실행: cd springboot && ./gradlew bootRun
```

재기동 후 위 첫 번째 curl에서 `Access-Control-Allow-Origin: chrome-extension://daijhh...`이 돌아오면
해결된 것이고, 확장에서 로그인/회원가입이 통과한다.

> 참고: 확장 ID가 `daijhh...`이 아니라면 `CommitgotchiCorsConfiguration.TRUSTED_CHROME_EXTENSION_ORIGIN`
> (과 관련 테스트)을 실제 ID로 바꾼 뒤 재빌드해야 한다. ID 고정 방법은 아래 부록 참고.

## 남은 한계 — refresh cookie(SameSite)와 로컬 HTTP

로그인 자체와 화면 진입은 동작한다(액세스 토큰은 응답 body로 받아 localStorage에 저장하고,
`/api/users/me`는 Bearer 토큰으로 호출하므로 쿠키가 필요 없다).

하지만 **15분짜리 액세스 토큰 만료 후 자동 refresh**는 로컬 HTTP에서 동작하지 않는다.

- refresh 토큰은 HttpOnly 쿠키(`cg_refresh`)로 발급된다.
- `RefreshTokenCookie`는 비보안(local http)일 때 `SameSite=Lax`로 쿠키를 굽는다.
- 확장 페이지의 site(`chrome-extension://...`)는 백엔드와 **cross-site**라 `SameSite=Lax` 쿠키는
  전송되지 않는다 → `/api/auth/refresh-cookie`가 401 → 세션 종료.

즉 로컬 http로는 **로그인 후 약 15분까지** 정상이고 그 뒤 조용한 갱신은 안 된다. 완전히 풀려면
백엔드를 **HTTPS**로 띄우고 `commitgotchi.auth.refresh-cookie-secure=true`로 설정한다(쿠키가
`Secure; SameSite=None`으로 발급돼 확장의 cross-site 요청에 실린다). 발표용이면 로그인 화면의
"🎮 백엔드 없이 둘러보기 (데모)"로 전 화면을 둘러볼 수 있다.

## 원인 ③ — 확장 origin은 맞지만 `PATCH`/`DELETE` preflight가 거부됨

로그인이나 `GET /api/users/me`는 통과하는데, 확장 팝업에서 캐릭터 활성화·수정·삭제 또는 게시글/리뷰
수정·삭제를 누를 때만 `Error: Invalid CORS request`가 나오면 origin 문제가 아닐 수 있다.

Vue API 클라이언트는 게임 API에서 `PATCH`와 `DELETE`를 사용한다.

```js
setActiveCharacter: (id) => authed('PATCH', `/api/game/characters/${id}/active`)
deleteCharacter: (id) => authed('DELETE', `/api/game/characters/${id}`)
```

그런데 Spring CORS 설정이 `GET, POST, OPTIONS`만 허용하면 브라우저가 실제 요청 전에 보내는
preflight에서 `Access-Control-Request-Method: PATCH` 또는 `DELETE`가 거부된다. 이때 응답은
애플리케이션 JSON 오류가 아니라 Spring CORS 필터의 평문 `403 Invalid CORS request`다.

### 진단

```bash
# 실패 재현: 허용 메서드에 PATCH가 없으면 403 Invalid CORS request
curl -i -X OPTIONS http://localhost:8080/api/game/characters/1 \
  -H 'Origin: chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn' \
  -H 'Access-Control-Request-Method: PATCH' \
  -H 'Access-Control-Request-Headers: Authorization, Content-Type'

# 비교: GET preflight는 통과할 수 있다
curl -i -X OPTIONS http://localhost:8080/api/users/me \
  -H 'Origin: chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: Authorization, Content-Type'
```

첫 번째만 403이고 두 번째가 200이면, 확장 ID나 origin allowlist가 아니라 **CORS method allowlist**
누락이다.

### 해결

`CommitgotchiCorsConfiguration`의 허용 메서드를 실제 `/api/**` 계약에 맞춰 확장한다.

```java
configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
```

수정 후 Spring Boot를 재빌드·재기동하고, 위 `PATCH` preflight에서
`Access-Control-Allow-Methods: GET,POST,PATCH,DELETE,OPTIONS`가 돌아오는지 확인한다. 이 회귀는
`CorsConfigurationIntegrationTest.trustedChromeExtensionCanPreflightMutatingGameApiMethods`가 보호한다.

## 운영 배포에서 먼저 확인할 origin 모델

COR-1.2 운영 기본안은 **same-origin reverse proxy**다. 운영 웹은 `https://app.example.com`에서 Vue와
`/api/**`를 함께 받고, public Nginx가 `/api/**`를 Spring Boot로 넘긴다.

| 환경 | `VITE_API_BASE_URL` | `CORS_ALLOWED_ORIGINS` | `SPRING_PROFILES_ACTIVE` | refresh cookie |
| --- | --- | --- | --- | --- |
| Local Docker Compose | `http://localhost:8080` | `http://localhost:5173` | `local` | secure false, `SameSite=Lax` |
| Local Vite dev | `http://localhost:8080` | `http://localhost:5173` | `local` | secure false, `SameSite=Lax` |
| Production web | empty string | `https://app.example.com` | `prod` | secure true, `SameSite=None` |
| Production extension | `https://app.example.com` | `https://app.example.com` + 고정 extension origin | `prod` | secure true, `SameSite=None` |

운영에서 같은 `Invalid CORS request`가 나면 비밀번호나 인증 로직보다 먼저 위 네 값이 같은 모델을
가리키는지 확인한다. 현재 Spring Boot CORS 계약은 `/api/**`에 대해 `GET, POST, PATCH, DELETE, OPTIONS`와
`Authorization, Content-Type`만 허용하고 `Access-Control-Allow-Credentials: true`를 반환한다.
credentials가 켜져 있으므로 wildcard origin은 사용할 수 없다. 운영 `prod` 프로필에서는
`CORS_ALLOWED_ORIGINS`에 최소 하나의 정확한 HTTPS origin이 필요하며, path/query/fragment가 붙은 값은
부팅 단계에서 거부된다.

| 잘못된 조합 | 대표 증상 | 수정 |
| --- | --- | --- |
| 운영 웹 build에 `VITE_API_BASE_URL=http://localhost:8080`이 박힘 | 사용자 브라우저가 자기 PC의 localhost를 호출하거나 mixed content/CORS 오류가 난다. | 운영 웹은 빈 `VITE_API_BASE_URL`로 다시 build한다. |
| 운영 extension build의 `VITE_API_BASE_URL`이 비어 있음 | 요청이 `chrome-extension://.../api/**`로 해석되어 백엔드에 도달하지 못한다. | extension은 `VITE_API_BASE_URL=https://app.example.com`으로 다시 build한다. |
| `CORS_ALLOWED_ORIGINS=https://app.example.com/api` | `prod` 부팅 단계에서 origin 검증 실패 또는 CORS 응답 누락 | path 없이 `https://app.example.com`만 넣는다. |
| 운영 Spring Boot가 `local` 프로필로 실행됨 | refresh cookie가 `Secure; SameSite=None`으로 발급되지 않아 extension 자동 refresh가 끊긴다. | `SPRING_PROFILES_ACTIVE=prod`로 실행하고 `REFRESH_COOKIE_SECURE=false`를 설정하지 않는다. |
| public Nginx가 `/api/**`를 Vue로 보내거나 누락 | API 호출이 404 또는 HTML 응답으로 돌아온다. | `/api/` location을 Spring Boot upstream으로 프록시한다. |

상세 public Nginx 예시는 [`public-nginx-reverse-proxy-runbook.md`](./public-nginx-reverse-proxy-runbook.md)를
따른다.

Vue의 `VITE_API_BASE_URL`을 FastAPI로 지정하는 것은 현재 지원 경로가 아니다. Vue는 FastAPI를 직접
호출하지 않고 Spring Boot를 통해 AI/채점 흐름에 연결된다. FastAPI에 browser CORS가 없는 것은 의도된
상태이므로, FastAPI CORS를 추가해서 해결하려 하지 말고 Spring Boot API origin과 allowlist를 먼저
맞춘다.

## 확장 ID 고정

`vue/public/manifest.json`의 `key`가 확장 ID를 `daijhhcaecladkkpcjdlfgcokohehhmn`으로 고정한다.
따라서 다른 기기나 경로에서 `vue/dist`를 로드해도 CORS Origin이 바뀌지 않는다.

`key`를 제거하거나 변경하면 ID가 달라지므로 백엔드의 `TRUSTED_CHROME_EXTENSION_ORIGIN` 및
관련 테스트도 함께 변경해야 한다. Vue의 `extension-manifest.mjs` 테스트가 공개키로 계산한 ID와
기대 ID의 일치를 검증한다.

## 체크리스트 (다음에 또 막히면)

1. `vue/.env.local`에 `VITE_API_BASE_URL=http://localhost:8080` 있는가 → `npm run build` → 확장 새로고침.
2. 운영이면 same-origin reverse proxy 기본안 기준으로 `VITE_API_BASE_URL`, `CORS_ALLOWED_ORIGINS`,
   `SPRING_PROFILES_ACTIVE`, refresh cookie secure 값이 맞는가.
3. `curl http://localhost:8080/api/health` 200인가(백엔드 기동·Postgres 포함).
4. 확장 origin으로 `curl`했을 때 `Access-Control-Allow-Origin`이 돌아오는가 → 안 오면
   **백엔드를 현재 코드로 재빌드·재기동**.
5. `chrome://extensions`의 실제 ID == 백엔드 allowlist의 ID 인가.
6. 로그인은 되는데 캐릭터/게시글 수정·삭제에서만 `Invalid CORS request`가 나는가 → `PATCH`/`DELETE`
   preflight가 허용되는지 확인.
7. 로그인 후 ~15분 뒤 끊김은 버그 아님(로컬 HTTP refresh 쿠키 한계) → HTTPS + `refresh-cookie-secure=true`.

관련 과거 변경 기록: [`cors-chrome-extension-allowlist.md`](./cors-chrome-extension-allowlist.md).
