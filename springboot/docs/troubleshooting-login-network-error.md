# 트러블슈팅 — 크롬 확장에서 로그인/회원가입 실패

작성일: 2026-06-14

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
  -H 'Origin: chrome-extension://llnclajenonklpnohgleabfmpaijbgie'

# 비교용: 5173 origin → Access-Control-Allow-Origin 정상 반환
curl -i http://localhost:8080/api/health \
  -H 'Origin: http://localhost:5173'
```

첫 번째에 `Access-Control-Allow-Origin: chrome-extension://...`이 안 오면 ②가 확정이다. 동시에
`chrome://extensions`에서 확장의 **실제 ID**가 `llnclajenonklpnohgleabfmpaijbgie`와 같은지도 확인한다
(다르면 그 실제 ID를 백엔드 allowlist에 넣어야 한다).

### 해결

떠 있는 백엔드를 **현재 코드로 다시 빌드·재기동**해서 확장 origin 허용 설정이 실제로 적용되게 한다.

```bash
docker compose up -d --build       # springboot 이미지 새로 빌드 후 재기동
# 또는 로컬 실행: cd springboot && ./gradlew bootRun
```

재기동 후 위 첫 번째 curl에서 `Access-Control-Allow-Origin: chrome-extension://llncl...`이 돌아오면
해결된 것이고, 확장에서 로그인/회원가입이 통과한다.

> 참고: 확장 ID가 `llncl...`이 아니라면 `CommitgotchiCorsConfiguration.TRUSTED_CHROME_EXTENSION_ORIGIN`
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

## 부록 — 다른 기기/경로에서도 확장 ID를 고정하려면

`llnclajenonklpnohgleabfmpaijbgie`는 **이 개발자 기기의 폴더 경로**에서 나온 값이라, 다른 사람이
다른 경로에서 `vue/dist`를 로드하면 ID가 달라진다(→ 다시 CORS 불일치). 기기/경로와 무관하게 ID를
고정하려면 `manifest.json`에 `key`(공개키 base64)를 넣는다.

```bash
openssl genrsa -out cg_ext_key.pem 2048
openssl rsa -in cg_ext_key.pem -pubout -outform DER | base64 -w0   # → manifest "key" 값
```

단, `key`를 넣으면 ID가 그 공개키에서 새로 유도되므로 `llncl...`이 아니게 된다. 그때는 백엔드의
`TRUSTED_CHROME_EXTENSION_ORIGIN`(과 관련 테스트)도 새 ID로 함께 바꿔야 한다. ID 유도 방식:
`SHA256(DER 공개키)` 앞 16바이트 → hex 32글자 → 각 hex 숫자 `0–f`를 `a–p`로 매핑(→ 백엔드 검증
정규식 `[a-p]{32}`와 일치).

## 체크리스트 (다음에 또 막히면)

1. `vue/.env.local`에 `VITE_API_BASE_URL=http://localhost:8080` 있는가 → `npm run build` → 확장 새로고침.
2. `curl http://localhost:8080/api/health` 200인가(백엔드 기동·Postgres 포함).
3. 확장 origin으로 `curl`했을 때 `Access-Control-Allow-Origin`이 돌아오는가 → 안 오면
   **백엔드를 현재 코드로 재빌드·재기동**.
4. `chrome://extensions`의 실제 ID == 백엔드 allowlist의 ID 인가.
5. 로그인 후 ~15분 뒤 끊김은 버그 아님(로컬 HTTP refresh 쿠키 한계) → HTTPS + `refresh-cookie-secure=true`.

관련 과거 변경 기록: [`cors-chrome-extension-allowlist.md`](./cors-chrome-extension-allowlist.md).
