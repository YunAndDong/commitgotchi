# Commit-Gotchi — 크롬 확장으로 띄우기

이 Vue SPA를 크롬 확장 프로그램으로 감쌌다. **아이콘을 누르면 툴바 아래에 드롭다운 팝업으로 앱이 뜨고, 활성 커밋고치는 일반 웹페이지 뷰포트 하단을 돌아다닌다.**
확장 프로그램 UI 테마는 `cozy`로 고정되어 있으며 별도 테마 변경 버튼을 제공하지 않는다.

## 빌드 & 로드 (3단계)

```bash
cd vue
npm install
VITE_API_BASE_URL=http://localhost:8080 npm run build
# → vue/dist/  (이 폴더가 곧 압축 안 푼 확장 프로그램)
```

1. 크롬에서 `chrome://extensions` 접속
2. 오른쪽 위 **개발자 모드** 켜기
3. **압축해제된 확장 프로그램을 로드** 클릭 → `vue/dist` 폴더 선택

이제 툴바의 Commit-Gotchi 아이콘을 누르면 **780×400 드롭다운 팝업**으로 앱이 열린다.
코드를 고친 뒤에는 `npm run build` 다시 → 확장 카드의 새로고침(↻) 한 번이면 반영된다.

운영 release용 확장은 COR-1.2 same-origin reverse proxy 결정에 맞춰 public API origin을
`https://app.example.com`으로 고정해서 별도로 빌드한다.

```bash
cd vue
VITE_API_BASE_URL=https://app.example.com npm run build
```

운영 웹 build와 달리 extension build에서 `VITE_API_BASE_URL`을 비우면 안 된다.
`chrome-extension://.../api/**`로 해석되어 Spring Boot public API에 도달하지 못한다.

> 크롬 드롭다운 팝업은 최대 ~800×600으로 제한된다. 그래서 780×400 고정 크기로 맞췄고,
> 데스크톱 레이아웃(1180px)은 이 폭에서 자동으로 단일 컬럼으로 접힌다(반응형 브레이크포인트).

## 어떻게 동작하나

| 파일 | 역할 |
|---|---|
| `public/manifest.json` | MV3 매니페스트. `action.default_popup: "index.html"` → 아이콘 클릭 시 드롭다운 팝업으로 SPA를 띄움. |
| `public/content-script.js` | 일반 HTTP(S) 페이지에 Shadow DOM을 만들고 저장된 활성 커밋고치를 뷰포트 하단에 표시. Vue 번들에 의존하지 않음. |
| `public/ext-popup.js` | `chrome-extension:` 프로토콜일 때 `html.is-ext-popup` 클래스 부여. **외부 파일이어야 함**(아래 CSP 트러블슈팅 참고). |
| `src/extension/activeGotchi.js` | 팝업 SPA의 활성 커밋고치 최소 정보를 `chrome.storage.local`에 안전하게 저장/제거. |
| `index.html` | head 인라인 `<style>`로 팝업 780×400 고정 + `ext-popup.js`를 외부 `<script src>`로 로드. |
| `styles/base.css` | `.is-ext-popup`일 때 780×400 보조 규칙. |
| `vite.config.js` | `base: './'` — 빌드된 에셋 경로가 `chrome-extension://<id>/` 아래에서도 풀린다. |
| `router/index.js` | `createWebHashHistory()` — 확장 페이지(`index.html#/...`)에서 라우팅이 404 없이 동작. |

`public/` 안의 파일은 빌드 시 `dist/` 루트로 그대로 복사된다.

매니페스트의 `key`가 확장 ID를 `daijhhcaecladkkpcjdlfgcokohehhmn`으로 고정한다. 따라서 다른 PC나
다른 절대 경로에서 `vue/dist`를 로드해도 백엔드가 허용하는 CORS Origin이 바뀌지 않는다.

`host_permissions`는 extension이 직접 호출하는 API host만 최소로 허용한다.

| 용도 | API origin | manifest permission |
| --- | --- | --- |
| Local extension dev | `http://localhost:8080` | `http://localhost:8080/*` |
| Production extension | `https://app.example.com` | `https://app.example.com/*` |

운영 API host가 실제 배포 도메인으로 바뀌면 `https://app.example.com/*`를 실제 public API
origin으로 교체한다. path는 Chrome permission 문법상 `/*`가 필요하지만, origin 자체는
Spring Boot CORS allowlist와 같은 `https://app.example.com` 기준으로 맞춘다.

### 웹페이지 커밋고치 동기화

- 저장 키: `commitgotchi.activeGotchi`
- 표시 설정 키: `commitgotchi.gotchiVisible` (값이 없으면 기본적으로 표시)
- 저장 데이터: `id`, `name`, `emotion`, `isEvolved`와 하단 렌더링에 필요한 `spriteSheetUrl`, `spriteMeta`, `imageStatus`만 포함한다. 인증 토큰과 전체 게임 상태는 저장하지 않는다.
- 팝업에서 로그인·활성 선택·생성·삭제·이름/감정/진화 변경이 발생하면 저장 값을 갱신한다.
- 확장 팝업 상단의 **웹페이지 커밋고치** 토글로 열린 일반 웹페이지의 커밋고치를 즉시 숨기거나 다시 표시할 수 있다. 숨겨도 활성 커밋고치 데이터는 유지된다.
- content script는 `chrome.storage.onChanged`를 구독하므로 이미 열린 일반 웹페이지도 선택 변경과 로그아웃을 가능한 즉시 반영한다.
- 렌더링 루트는 Shadow DOM이며 `position: fixed`, `pointer-events: none`으로 페이지 스크롤·클릭을 방해하지 않는다.
- `prefers-reduced-motion: reduce` 환경에서는 좌우 이동을 중지하고 하단에 정적으로 표시한다.
- `chrome://`, Chrome 웹 스토어, 다른 확장 페이지처럼 content script 실행이 금지된 화면에는 표시되지 않는다.

## 수동 테스트

1. `npm run build` 후 `vue/dist`를 압축 해제 확장으로 로드하거나 기존 확장을 새로고침한다.
2. `https://example.com` 같은 일반 웹페이지를 열고 확장 팝업에서 로그인 또는 데모 로그인을 한다.
3. 활성 커밋고치가 페이지 하단에서 좌우 이동하고 방향 전환 시 캐릭터만 반전되는지 확인한다.
4. 페이지를 길게 스크롤해도 커밋고치가 뷰포트 하단에 고정되는지, 링크 클릭과 스크롤을 방해하지 않는지 확인한다.
5. 다른 캐릭터를 활성화하거나 활성 캐릭터의 이름/감정을 바꾸고 열린 탭이 새로고침 없이 갱신되는지 확인한다.
6. 로그아웃하거나 마지막 캐릭터를 삭제하면 열린 탭에서 커밋고치가 제거되는지 확인한다.
7. 팝업 상단의 **웹페이지 커밋고치** 토글을 끄고 켜며 열린 탭에서 즉시 숨김·복원되는지 확인한다.
8. 새 일반 웹페이지 탭과 새로고침된 탭에도 저장된 활성 커밋고치와 표시 설정이 반영되는지 확인한다.
9. 운영체제의 동작 줄이기 설정을 켜고 이동이 중지되는지 확인한다.
10. `chrome://extensions`에서 content script 관련 오류가 없는지 확인한다.

## 실제 로그인과 데모 모드

확장 페이지의 Origin은 아래 값으로 고정되어 있으며 Spring Boot CORS 허용 목록에도 등록되어 있다.

```text
chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn
```

백엔드가 실행 중이면 확장에서도 실제 로그인/회원가입을 사용할 수 있다. 백엔드 없이 UI만 확인할
때는 로그인 화면의 **“🎮 백엔드 없이 둘러보기 (데모)”** 버튼으로 바로 대시보드에 들어간다.
캐릭터·리포트·퀴즈·랭킹·게시판 데이터는 어차피 mock이라 전 화면을 그대로 체험할 수 있다.

## 운영 API 연결 계약

COR-1.2 운영 기본안은 **Option A: same-origin reverse proxy**다. 운영 웹은
`https://app.example.com`에서 Vue와 `/api/**`를 함께 받지만, extension은
`chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn`에서 실행되므로 같은 API를 절대 URL로
호출해야 한다.

운영 extension 연결 값:

| 항목 | 값 |
| --- | --- |
| Extension origin | `chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn` |
| Production API origin | `https://app.example.com` |
| Build env | `VITE_API_BASE_URL=https://app.example.com` |
| Manifest host permission | `https://app.example.com/*` |
| Spring Boot CORS | `CORS_ALLOWED_ORIGINS=https://app.example.com` + 코드에 고정된 extension origin |
| Spring profile | `SPRING_PROFILES_ACTIVE=prod` |

Vue extension은 FastAPI를 직접 호출하지 않는다. 운영 API origin은 Spring Boot Public API를 노출하는
public Nginx/reverse proxy origin이어야 한다.

### Refresh cookie 동작

Vue API client는 모든 요청에 `credentials: 'include'`를 사용하고, access token이 401을 받으면
`POST /api/auth/refresh-cookie`로 HttpOnly refresh cookie를 회전한다.

| 환경 | Cookie 속성 | extension refresh 동작 |
| --- | --- | --- |
| Local HTTP Spring Boot | `Secure=false`, `SameSite=Lax` | 최초 로그인과 access token 사용은 확인할 수 있지만, `chrome-extension://`에서 `http://localhost:8080`으로 보내는 cross-site POST refresh cookie 흐름은 신뢰하지 않는다. access token 만료 뒤에는 다시 로그인해서 테스트한다. |
| Production HTTPS Spring Boot | `Secure`, `SameSite=None` | `https://app.example.com`에서 발급된 refresh cookie가 extension의 credentialed fetch에 포함되어 자동 refresh가 동작해야 한다. |

운영에서 refresh가 실패하면 Network 탭에서 `Set-Cookie: cg_refresh=...; Secure; SameSite=None; Path=/api/auth`
응답과 이후 `/api/auth/refresh-cookie` 요청의 Cookie 포함 여부를 먼저 확인한다.

### CORS preflight smoke test

운영 배포 후 extension origin 기준 preflight가 통과하는지 확인한다. 아래 명령은 FastAPI가 아니라
Spring Boot `/api/**` public origin을 대상으로 실행한다.

```bash
curl -i -X OPTIONS https://app.example.com/api/auth/login \
  -H 'Origin: chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: Content-Type'

curl -i -X OPTIONS https://app.example.com/api/users/me \
  -H 'Origin: chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: Authorization, Content-Type'
```

기대값:

- `Access-Control-Allow-Origin: chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn`
- `Access-Control-Allow-Credentials: true`
- `Access-Control-Allow-Methods`에 요청 method 포함
- `Access-Control-Allow-Headers`에 `Authorization`, `Content-Type` 포함

거부 origin 진단도 함께 남긴다.

```bash
curl -i -X OPTIONS https://app.example.com/api/users/me \
  -H 'Origin: https://evil.example' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: Authorization, Content-Type'
```

거부 origin에는 `Access-Control-Allow-Origin`이 없어야 정상이다.

## 아이콘 추가(선택)

16/48/128px PNG를 `public/`에 넣고 manifest에 추가:

```json
"icons": { "16": "icon16.png", "48": "icon48.png", "128": "icon128.png" },
"action": {
  "default_title": "Commit-Gotchi 열기",
  "default_popup": "index.html",
  "default_icon": { "16": "icon16.png", "48": "icon48.png", "128": "icon128.png" }
}
```

## 트러블슈팅 — CSP로 인라인 스크립트 차단 (해결됨)

### 증상
확장 팝업을 여는 순간 콘솔/오류에 다음이 찍히고, 팝업이 좁게 뜨거나 빈 화면처럼 보였다.

```
Refused to execute inline script because it violates the following Content
Security Policy directive: "script-src 'self'". Either the 'unsafe-inline'
keyword, a hash (sha256-...), or a nonce is required to enable inline execution.
```

### 원인
Manifest V3 확장 페이지의 **기본 CSP는 `script-src 'self'`** 다. 즉 HTML에 직접 박힌
`<script> ...코드... </script>` (인라인 스크립트)와 `onclick="..."` 같은 인라인 이벤트
핸들러를 전부 차단한다. 초기 구현에서 팝업 크기 훅을 `index.html` `<head>`의 **인라인
`<script>`** 로 넣었는데, 이게 CSP에 막혀 실행되지 않았다. 그 결과 `.is-ext-popup`
클래스가 안 붙어 780×400 고정 크기가 적용되지 못하고 팝업이 좁게/비어 보였다.

> 참고: 인라인 `<style>` 과 외부에서 import 되는 JS 번들(`/assets/*.js`, `main.js`)은
> `script-src 'self'` 에 부합하므로 차단되지 않는다. 문제는 오직 **인라인 `<script>`** 였다.

### 해결
인라인 스크립트를 **외부 파일로 분리**했다 (MV3 정석).

- 추가: `public/ext-popup.js` — `location.protocol === 'chrome-extension:'` 검사 후
  `html.is-ext-popup` 클래스를 붙인다.
- `index.html`: 인라인 `<script>{...}</script>` → `<script src="/ext-popup.js"></script>` 로 교체.
- 크기 지정 `<style>` 은 인라인 그대로 둬도 된다(스타일은 `script-src` 대상이 아님).

```html
<!-- before (차단됨) -->
<script>
  if (location.protocol === 'chrome-extension:')
    document.documentElement.classList.add('is-ext-popup')
</script>

<!-- after (허용됨) -->
<script src="/ext-popup.js"></script>
```

`ext-popup.js` 는 `public/` 에 있어 빌드 시 `dist/` 루트로 복사되고, `<head>` 에서 동기
실행되어 첫 페인트 전에 클래스를 붙이므로 팝업 크기 측정에 제때 반영된다.

> 인라인 핸들러(`onclick` 등)도 같은 이유로 금지다 — 이 앱의 버튼은 모두 Vue의
> `@click`(= `addEventListener` 바인딩)을 쓰므로 추가 작업은 없다.
> 해시(`sha256-...`)를 CSP에 등록하는 우회법도 있으나 MV3에선 외부 파일 분리가 권장이다.

## 트러블슈팅 — 열린 탭에 커밋고치가 즉시 보이지 않음

### 증상

확장을 처음 설치하거나 `chrome://extensions`에서 확장을 새로고침한 직후, 이미 열려 있던
일반 웹페이지에 커밋고치가 보이지 않는다.

### 원인

Chrome은 확장 설치·새로고침 전에 열려 있던 탭에 새 content script를 소급 주입하지 않는다.
반면 content script가 이미 실행 중인 탭에서는 `chrome.storage.onChanged`를 통해 활성 선택과
로그아웃이 즉시 반영된다.

### 해결

확장 설치·새로고침 직후에만 기존 일반 웹페이지를 한 번 새로고침한다. 이후 팝업에서 발생하는
활성 커밋고치 변경은 탭 새로고침 없이 반영된다. `chrome://` 및 다른 확장 페이지는 Chrome
보안 정책상 content script를 실행할 수 없으므로 표시되지 않는 것이 정상이다.

### 적용
`npm run build` → `chrome://extensions` 에서 해당 확장 새로고침(↻) → 아이콘 다시 클릭.

## 폰트 메모

Galmuri·Gasoek One은 CDN에서 로드한다. 오프라인/사내망에서 폰트가 막히면 폰트 파일을
`public/`에 동봉하고 `index.html`의 `<link>`를 로컬 경로로 바꾸면 된다.
