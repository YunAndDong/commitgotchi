# CORS: Chrome 확장 프로그램 허용 변경 사항

작성일: 2026-06-12

> 2026-06-14 메모: `vue/public/manifest.json`의 공개키가 신뢰 확장 ID를
> `daijhhcaecladkkpcjdlfgcokohehhmn`으로 고정한다. 로그인 시 "네트워크 오류"가 보일 때의 진단
> 절차는 [`troubleshooting-login-network-error.md`](./troubleshooting-login-network-error.md) 참고.
>
> 2026-06-19 메모: 게임 API가 사용하는 `PATCH`/`DELETE` preflight도 통과하도록 CORS 허용
> 메서드에 `PATCH`, `DELETE`를 추가했다.
>
> 2026-06-23 메모: COR-1.2에서 운영 기본 origin 모델을 same-origin reverse proxy로 확정했다.
> 운영 웹은 `https://app.example.com`에서 Vue와 `/api/**`를 함께 받고, extension build만
> `VITE_API_BASE_URL=https://app.example.com` 같은 절대 URL을 사용한다.

## 배경

기존 백엔드 CORS 정책은 `commitgotchi.cors.allowed-origins`(환경변수 `CORS_ALLOWED_ORIGINS`)에 등록된 origin만
`/api/**` 경로에 대해 허용했고, 운영상 `http://localhost:5173`(Vite dev 서버)만 사용 중이었다.

요구사항: Chrome 확장 프로그램(ID `daijhhcaecladkkpcjdlfgcokohehhmn`)이 백엔드 API에 접근할 수 있도록 허용한다.

Chrome 확장 프로그램은 브라우저 요청 시 `Origin: chrome-extension://<extension-id>` 헤더를 보낸다.
즉 허용해야 할 origin은 다음과 같다.

```
chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn
```

## 문제점

단순히 `CORS_ALLOWED_ORIGINS`에 위 origin을 추가하는 것만으로는 기존 코드에서 동작하지 않았다.
`CommitgotchiCorsConfiguration.validateOrigin(...)`이 **http / https 스킴만** 유효한 origin으로 인정하고,
그 외 스킴(`chrome-extension` 포함)은 다음 예외로 거부했기 때문이다.

```
IllegalArgumentException: CORS allowlist must contain exact permitted origins
```

따라서 검증 로직을 수정해야 했다.

## 수정 내용

백엔드 CORS 설정과 테스트를 아래와 같이 변경했다.

### 1. `src/main/java/com/commitgotchi/security/CommitgotchiCorsConfiguration.java`

지정된 확장 프로그램 origin을 `TRUSTED_CHROME_EXTENSION_ORIGIN`으로 선언하고 기존
`CORS_ALLOWED_ORIGINS` 값과 합쳐 최종 allowlist를 구성한다. 따라서 실행 환경의
`CORS_ALLOWED_ORIGINS`가 기존처럼 `http://localhost:5173`만 포함해도 확장 프로그램 origin이 허용된다.

또한 `validateOrigin(...)`에 `chrome-extension` 스킴 분기를 추가하고, 전용 검증 메서드
`validateExtensionOrigin(...)`을 신설했다.

확장 프로그램 origin은 다음 조건을 모두 만족할 때만 허용한다(엄격한 exact-origin 검증 유지).

- 스킴이 `chrome-extension`
- 호스트가 Chrome 확장 ID 형식(`[a-p]{32}`)과 일치
- 포트가 없음 (`-1`)
- userinfo / path / query / fragment 가 없음
- 와일드카드(`*`) 미포함

```java
String scheme = uri.getScheme();

// chrome-extension://<extension-id> 형태의 opaque origin 허용
if ("chrome-extension".equalsIgnoreCase(scheme)) {
    validateExtensionOrigin(origin, uri);
    return;
}
// ...기존 http/https 검증 유지...
```

확장 프로그램 origin은 네트워크로 접근 불가능하고 브라우저가 secure context로 취급하므로,
운영(prod) 프로파일을 포함한 **모든 프로파일**에서 허용된다. 단, prod에서는 확장 origin만으로
부팅할 수 없고 기존 정책대로 `CORS_ALLOWED_ORIGINS`에 최소 하나의 HTTPS origin이 있어야 한다.
나머지 http/https origin에 대한 검증 규칙도 그대로 유지된다.

### 2. `src/test/java/com/commitgotchi/security/CommitgotchiCorsConfigurationTest.java`

다음 테스트를 추가했다.

- `acceptsChromeExtensionOriginAlongsideWebOriginsInEveryProfile` — dev/prod 모두에서
  `chrome-extension://...` origin이 웹 origin과 함께 정상 파싱됨을 검증.
- `rejectsMalformedChromeExtensionOrigins` — 포트/경로/쿼리/프래그먼트/와일드카드가 붙은
  잘못된 확장 origin은 거부됨을 검증.
- `productionRequiresAtLeastOneHttpsOrigin` — prod에서 확장 origin만으로 HTTPS 필수 조건을
  우회할 수 없음을 검증.

### 3. `src/test/java/com/commitgotchi/security/CorsConfigurationIntegrationTest.java`

실행 환경의 `CORS_ALLOWED_ORIGINS`에 확장 origin이 없어도 지정된 확장 프로그램의 preflight 요청에
정확한 `Access-Control-Allow-Origin` 응답이 반환되는지 검증한다. 또한 확장 프로그램에서 호출하는
캐릭터 수정·삭제 흐름이 브라우저 preflight에서 막히지 않도록 `PATCH`와 `DELETE` 요청 메서드도
검증한다.

## 적용(설정) 방법

별도 설정 변경은 필요 없다. 기존 `CORS_ALLOWED_ORIGINS` 값은 유지하며, 백엔드가 지정된 확장 프로그램
origin을 추가로 허용한다. prod에서는 기존 정책대로 `CORS_ALLOWED_ORIGINS`에 HTTPS origin이 필요하다.

운영 기본안은 same-origin reverse proxy다. 따라서 production web은 `VITE_API_BASE_URL`을 비워
`/api/**` 상대 경로를 호출하고, Spring Boot는 `CORS_ALLOWED_ORIGINS=https://app.example.com`,
`SPRING_PROFILES_ACTIVE=prod`로 실행한다. production extension은 같은 public origin을 절대 URL로
호출해야 하므로 별도 build에서 `VITE_API_BASE_URL=https://app.example.com`을 사용한다.
상세 매트릭스와 public Nginx 예시는
[`public-nginx-reverse-proxy-runbook.md`](./public-nginx-reverse-proxy-runbook.md)를 참고한다.

## 동작 확인 포인트

- 허용된 CORS 응답: `Access-Control-Allow-Origin: chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn`
- 허용 메서드: `GET, POST, PATCH, DELETE, OPTIONS` / 허용 헤더: `Authorization, Content-Type`
- `Access-Control-Allow-Credentials: true`를 반환해 HttpOnly refresh cookie 흐름을 허용한다.
- 운영(`prod`) refresh cookie는 `Secure; SameSite=None`으로 발급되어 확장 프로그램의 cross-site fetch에
  포함된다. 비보안 로컬 환경은 `SameSite=Lax`를 유지하므로 확장 프로그램에서는 데모 모드 또는 HTTPS
  백엔드를 사용해야 한다.
- 쿠키 refresh/logout 엔드포인트는 `Content-Type: application/json`만 허용한다. 따라서 단순 HTML form
  POST는 거부되고, 브라우저의 CORS preflight를 통과한 허용 origin만 쿠키 작업을 수행할 수 있다.

## Vue와 FastAPI 경계

Vue 브라우저 런타임은 FastAPI를 직접 호출하지 않는다. `VITE_API_BASE_URL`은 Spring Boot API origin을
가리켜야 하며, 브라우저-facing CORS는 Spring Boot의 `/api/**` 정책이 소유한다.

FastAPI는 현재 Spring Boot와의 서버 간 계약 및 health endpoint 용도이며 `CORSMiddleware`를 설정하지
않은 상태가 의도된 구성이다. browser-facing FastAPI endpoint가 필요해지기 전까지 FastAPI에 CORS를
선제 추가하지 않는다. 그런 요구가 생기면 Vue 호출 경로, 허용 origin, credentials 정책, 회귀 테스트를
포함한 별도 decision record를 먼저 남긴다.

## 변경하지 않은 것 (범위 준수)

- 2026-06-19 코드 변경 당시 `/springboot` 외 다른 폴더(프론트엔드, FastAPI 등)는 읽기만 했고
  수정하지 않았다.
- 2026-06-19 코드 변경 당시 CORS 헤더 정책은 그대로 유지하고, 메서드 정책은 현재 `/api/**` 계약
  (`GET`, `POST`, `PATCH`, `DELETE`)에 맞췄다. refresh cookie 흐름을 위해 credentials를 허용했다.
- 2026-06-19 코드 변경 당시 prod에서 최소 1개 HTTPS origin 필요, 와일드카드 금지 등 기존 보안 규칙은
  그대로 유지했다.
- 2026-06-23 문서 정렬에서는 런타임 동작을 변경하지 않고, Vue가 FastAPI를 직접 호출하지 않는 결정과
  FastAPI browser CORS 미설정 guardrail만 명시했다.
