# Public Nginx Reverse Proxy Runbook

작성일: 2026-06-23

> **2026-06-23 정렬 노트 (MVP 배포 = 확장 전용 / API-only):**
> MVP 배포 범위에서 **Vue는 Chrome 확장프로그램으로만 배포**되며, 웹앱을 서버에서 서빙하지 않는다
> (근거: `docs/mvp-cicd-pipeline-plan.md`). 따라서 공개 Nginx는 **API 전용**으로 운영한다 —
> `/api/**`·`/character-assets/**`만 Spring Boot로 프록시하고, **`/`(Vue 정적 서빙)는 적용하지 않는다.**
> FastAPI는 외부 비공개. 아래 문서의 same-origin **웹 서빙(Option A의 `/`→Vue) 부분은 "향후 웹앱을
> 추가할 경우의 옵션"** 으로만 남겨둔다. `/api/**`·`/character-assets/**` 프록시, 헤더 보존,
> CORS/스모크(확장 origin·거부 origin·PATCH/DELETE preflight·SSE), asset 경계 절은 그대로 유효하다.
> prod CORS 부팅 조건상 `CORS_ALLOWED_ORIGINS`에는 `https://commitgotchi.store` HTTPS origin 1개를
> placeholder로 유지하고, 확장 origin은 Spring 하드코딩 allowlist로 허용된다.

## 결정

COR-1.2의 운영 기본안은 **Option A: same-origin reverse proxy**다.

향후 웹앱을 서버에서 제공한다면 운영 웹 사용자는 `https://commitgotchi.store` 하나의 origin에서 Vue 정적 파일과
Spring Boot `/api/**`를 함께 받을 수 있다. 단, MVP 배포는 확장 전용/API-only이므로 현재 운영 Nginx는
`https://commitgotchi.store`에서 `/api/**`와 `/character-assets/**`만 Spring Boot로 프록시한다.
Chrome extension은 `chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn`에서 실행되므로
`https://commitgotchi.store`를 절대 API base URL로 사용한다.

Option B, 즉 `https://commitgotchi.store`와 `https://api.example.com`을 분리하는 cross-origin API 모델은
초기 운영 기본안이 아니다. 별도 API 도메인, 추가 TLS/프록시, 명확한 CORS allowlist 운영이 필요한 경우에만
새 decision record로 전환한다.

## 환경 매트릭스

| 환경 | Browser/extension origin | `VITE_API_BASE_URL` | `CORS_ALLOWED_ORIGINS` | `SPRING_PROFILES_ACTIVE` | Refresh cookie secure |
| --- | --- | --- | --- | --- | --- |
| Local Docker Compose | `http://localhost:5173` | `http://localhost:8080` | `http://localhost:5173` | `local` | `false`, `SameSite=Lax` |
| Local Vite dev | `http://localhost:5173` | `http://localhost:8080` | `http://localhost:5173` | `local` | `false`, `SameSite=Lax` |
| Production web (future option) | `https://commitgotchi.store` | empty string | `https://commitgotchi.store` | `prod` | `true`, `Secure; SameSite=None` |
| Production extension (MVP) | `chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn` | `https://commitgotchi.store` | `https://commitgotchi.store` plus trusted extension origin from code | `prod` | `true`, `Secure; SameSite=None` |

Notes:

- `CORS_ALLOWED_ORIGINS` must contain exact origins only. Do not include paths such as `/api`.
- Spring Boot automatically adds the trusted Chrome extension origin, but `prod` still requires at least one HTTPS web origin.
- Production web builds should leave `VITE_API_BASE_URL` empty so the browser calls `/api/**` on the current origin.
- MVP production extension builds use `VITE_API_BASE_URL=https://commitgotchi.store`. Relative `/api/**` would resolve under `chrome-extension://...`.
- Vue must not point `VITE_API_BASE_URL` at FastAPI. Browser-facing API traffic is owned by Spring Boot.

## Public Nginx Responsibilities

The checked-in `vue/nginx.conf` only serves built Vue files inside the Vue container. It is not the public TLS terminator.
For the MVP API-only deployment, the server-level public Nginx must:

1. Terminate HTTPS for `commitgotchi.store`.
2. Proxy `/api/**` to Spring Boot.
3. Proxy `/character-assets/**` to Spring Boot while assets are served there.
4. Return a non-SPA response for `/` because Vue is not served from prod Nginx.
5. Preserve `Host`, `X-Forwarded-*`, `Upgrade`, and `Connection` headers.
6. Never proxy browser traffic directly to FastAPI.

## Character Asset Serving Boundary

현재 character sprite asset은 Spring Boot가 `/character-assets/**`에서 제공한다.
구현 기준은 `springboot/src/main/java/com/commitgotchi/character/image/CharacterAssetWebConfig.java`이며,
이 설정은 Spring Boot 이미지에 포함된 `classpath:/character-assets/` 기본 sprite PNG를 정적 리소스로 노출한다. Spring Security는
`springboot/src/main/java/com/commitgotchi/security/SecurityConfig.java`에서 `/character-assets/**`를
`permitAll`로 열어 둔다.

이 경로는 Spring Boot API CORS 정책과 별개다. CORS source of truth인
`springboot/src/main/java/com/commitgotchi/security/CommitgotchiCorsConfiguration.java`는
`/api/**`에만 `CorsConfiguration`을 등록한다. 따라서 `/character-assets/**`는 현재 `/api/**`
CORS allowlist, method/header allowlist, credentials 정책의 적용 대상이 아니다. 이 경계를 유지하며,
asset CORS가 필요해질 때도 Spring Boot API CORS 범위를 `/character-assets/**`까지 넓히는 방식으로
처리하지 않는다.

Vue의 현재 sprite 사용 방식은 단순 표시다.

- `vue/src/api/client.js`의 `apiAssetUrl(...)`은 `/character-assets/...` 상대 URL을 web에서는 그대로 두고,
  extension처럼 `VITE_API_BASE_URL`이 절대 HTTPS origin인 build에서는 `https://commitgotchi.store/character-assets/...`
  형태로 정규화한다.
- `vue/src/stores/game.js`의 `normalizeSpriteSheetUrl(...)`은 API 응답의 `/character-assets/...` 값을
  `apiAssetUrl(...)`로 정규화한다.
- `vue/src/components/CgSprite.vue`는 숨김 `<img class="spr__probe">`로 load/error만 확인하고,
  실제 frame 표시는 CSS `backgroundImage`, `backgroundSize`, `backgroundPosition`으로 처리한다.
- 현재 Vue runtime은 sprite URL을 `fetch()`로 binary 처리하지 않고, canvas에 그린 뒤 pixel을 읽지도 않는다.

### Asset CORS Decision Rule

별도 asset CORS가 필요 없는 경우:

- Vue web이 향후 Option A same-origin reverse proxy에서 `/character-assets/**`를 같은 origin으로 표시한다.
- Chrome extension이나 cross-origin page가 공개 이미지를 `<img>` 또는 CSS `background-image`로 단순 표시한다.
- load/error fallback만 필요하고, JavaScript가 이미지 bytes나 pixel data를 읽지 않는다.

별도 asset CORS 결정이 필요한 경우:

- `fetch(spriteSheetUrl)`로 PNG bytes를 읽거나 Blob/File로 가공한다.
- `<canvas>`에 sprite를 그린 뒤 `getImageData()`, `toBlob()`, `toDataURL()`처럼 pixel/readback API를 호출한다.
- S3, CloudFront, CDN 등 `https://commitgotchi.store`가 아닌 asset origin으로 이동한다.
- extension에서 CDN asset을 사용하면서 load/error 표시를 넘어 binary 처리 또는 canvas readback을 지원한다.

이 경우 asset CORS는 API CORS와 분리된 정책으로 결정한다. 허용 origin은 실제 asset consumer만 포함하고,
credentials가 필요 없는 공개 이미지라면 `Access-Control-Allow-Credentials`를 사용하지 않는다.

예시 응답 header:

```http
Access-Control-Allow-Origin: https://commitgotchi.store
Vary: Origin
Access-Control-Allow-Methods: GET, HEAD, OPTIONS
Access-Control-Allow-Headers: Content-Type
Access-Control-Expose-Headers: ETag, Content-Length
Access-Control-Max-Age: 3600
```

Chrome extension도 asset bytes/canvas readback을 해야 한다면 CDN 또는 storage layer가 아래 origin을
별도로 허용해야 한다.

```http
Access-Control-Allow-Origin: chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn
Vary: Origin
Access-Control-Allow-Methods: GET, HEAD, OPTIONS
```

단일 응답에 여러 `Access-Control-Allow-Origin` 값을 동시에 넣지 않는다. 여러 origin을 허용해야 하면
CDN/storage 설정에서 allowlist 기반으로 요청 `Origin`을 검증한 뒤 해당 origin 하나를 반사하거나,
vendor가 제공하는 CORS rule 형식으로 `https://commitgotchi.store`와
`chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn`을 모두 등록한다.

S3/CloudFront 계열로 옮길 때의 CORS rule 예시:

```json
[
  {
    "AllowedOrigins": [
      "https://commitgotchi.store",
      "chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn"
    ],
    "AllowedMethods": ["GET", "HEAD"],
    "AllowedHeaders": ["Content-Type"],
    "ExposeHeaders": ["ETag", "Content-Length"],
    "MaxAgeSeconds": 3600
  }
]
```

운영자가 CDN/S3로 전환할 때는 실제 vendor가 `chrome-extension://...` origin을 허용 origin으로 받을 수
있는지 배포 전 smoke test로 확인한다. 지원하지 않는다면 asset readback을 web origin으로 제한하거나,
Spring Boot 또는 same-origin asset proxy를 별도 decision으로 검토한다.

## Example Public Nginx Server Block

Adjust upstream hostnames and ports to the actual server or Docker network. The checked-in
`nginx/api-only.conf` is the compose version of this API-only shape.

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}

server {
    listen 80;
    server_name commitgotchi.store;

    location ^~ /.well-known/acme-challenge/ {
        root /var/www/certbot;
        default_type text/plain;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl http2;
    server_name commitgotchi.store;

    ssl_certificate /etc/letsencrypt/live/commitgotchi.store/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/commitgotchi.store/privkey.pem;

    location ~ ^/(api|character-assets)(/|$) {
        proxy_pass http://springboot:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 1h;
        proxy_buffering off;
    }

    location / {
        return 404;
    }
}
```

## Deployment Checklist

1. Start Spring Boot with production profile and the API HTTPS origin:
   ```bash
   SPRING_PROFILES_ACTIVE=prod
   CORS_ALLOWED_ORIGINS=https://commitgotchi.store
   ```
2. Do not set `REFRESH_COOKIE_SECURE=false` in production. The `prod` profile sets secure refresh cookies.
3. Configure public Nginx to route `/api/**` and `/character-assets/**` to Spring Boot, and do not serve Vue at `/`.
4. Keep FastAPI reachable only from Spring Boot or internal infrastructure.
5. Build the production extension separately with:
   ```bash
   cd vue
   VITE_API_BASE_URL=https://commitgotchi.store npm run build
   ```
6. For the extension release, keep `vue/public/manifest.json` `host_permissions` aligned with the public API origin:
   `https://commitgotchi.store/*`.
7. Run the CORS regression matrix before release:
   ```bash
   cd springboot
   ./gradlew test --tests com.commitgotchi.security.CorsConfigurationIntegrationTest --tests com.commitgotchi.security.CommitgotchiCorsConfigurationTest
   ```
8. After deployment, run the smoke tests below against the public origin and attach the result to the release checklist.

## Release CORS Matrix Checklist

Treat this as a required CI/release gate whenever `CommitgotchiCorsConfiguration`, `SecurityConfig`, Vue API access,
extension manifest permissions, or public Nginx routing changes.

- Spring Boot CORS source of truth remains `CommitgotchiCorsConfiguration`.
- CORS registration remains scoped to `/api/**`; `/character-assets/**` is not folded into the API CORS policy.
- Allowed origins cover production/API origin `https://commitgotchi.store` and the trusted extension origin
  `chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn`.
- A rejected origin returns no `Access-Control-Allow-Origin`.
- JSON API preflight verifies `Authorization, Content-Type`.
- `PATCH` and `DELETE` preflight pass for web and extension origins.
- SSE `GET` preflight/actual request keeps CORS headers for `/api/game/characters/{id}/events`.
- FastAPI still has no browser-facing CORS unless a separate decision record and regression tests are added first.

## Smoke Tests

Set these shell variables to the deployed origin and, for the SSE actual request, a real user access token.

```bash
APP_ORIGIN=https://commitgotchi.store
EXTENSION_ORIGIN=chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn
REJECTED_ORIGIN=https://evil.example.com
ACCESS_TOKEN=<user-access-token>
CHARACTER_ID=<owned-character-id>
```

Basic routing:

```bash
curl -i "$APP_ORIGIN/"
curl -i "$APP_ORIGIN/api/health"
curl -I "$APP_ORIGIN/character-assets/default_image1.png"
```

Allowed production web origin:

```bash
curl -i "$APP_ORIGIN/api/health" \
  -H "Origin: $APP_ORIGIN"

curl -i -X OPTIONS "$APP_ORIGIN/api/game/characters/1" \
  -H "Origin: $APP_ORIGIN" \
  -H 'Access-Control-Request-Method: PATCH' \
  -H 'Access-Control-Request-Headers: Authorization, Content-Type'

curl -i -X OPTIONS "$APP_ORIGIN/api/game/characters/1" \
  -H "Origin: $APP_ORIGIN" \
  -H 'Access-Control-Request-Method: DELETE' \
  -H 'Access-Control-Request-Headers: Authorization, Content-Type'
```

Allowed Chrome extension origin:

```bash
curl -i -X OPTIONS "$APP_ORIGIN/api/users/me" \
  -H "Origin: $EXTENSION_ORIGIN" \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: Authorization, Content-Type'

curl -i -X OPTIONS "$APP_ORIGIN/api/game/characters/1" \
  -H "Origin: $EXTENSION_ORIGIN" \
  -H 'Access-Control-Request-Method: PATCH' \
  -H 'Access-Control-Request-Headers: Authorization, Content-Type'
```

Rejected origin:

```bash
curl -i -X OPTIONS "$APP_ORIGIN/api/users/me" \
  -H "Origin: $REJECTED_ORIGIN" \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: Authorization'
```

SSE CORS:

```bash
curl -i -X OPTIONS "$APP_ORIGIN/api/game/characters/$CHARACTER_ID/events" \
  -H "Origin: $APP_ORIGIN" \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: Authorization'

curl -i -N "$APP_ORIGIN/api/game/characters/$CHARACTER_ID/events" \
  -H "Origin: $APP_ORIGIN" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Accept: text/event-stream'
```

Expected:

- `/` does not serve Vue HTML in MVP API-only prod.
- `/api/health` reaches Spring Boot through the public proxy.
- `/character-assets/default_image1.png` returns a PNG through Spring Boot while assets are served there.
- The API origin CORS probe returns `Access-Control-Allow-Origin: https://commitgotchi.store`.
- The extension preflight returns `Access-Control-Allow-Origin: chrome-extension://daijhhcaecladkkpcjdlfgcokohehhmn`.
- The rejected origin preflight is forbidden and does not return `Access-Control-Allow-Origin`.
- `PATCH` and `DELETE` preflight return `Access-Control-Allow-Methods: GET,POST,PATCH,DELETE,OPTIONS`.
- JSON API preflight returns `Access-Control-Allow-Headers: Authorization, Content-Type`.
- SSE preflight returns `Access-Control-Allow-Origin: https://commitgotchi.store`; the actual SSE request returns
  `Content-Type: text/event-stream` for a valid token and keeps the same CORS origin header.
- Current Spring Boot `/character-assets/**` responses are not expected to emit the `/api/**` CORS headers.

## Common Wrong Combinations

| Wrong combination | Symptom | Fix |
| --- | --- | --- |
| Production web build uses `VITE_API_BASE_URL=http://localhost:8080` | Users call their own machine or hit mixed-content/CORS errors | Rebuild web with empty `VITE_API_BASE_URL` |
| Production extension build uses empty `VITE_API_BASE_URL` | Requests resolve to `chrome-extension://.../api/**` and fail before reaching Spring Boot | Rebuild extension with `VITE_API_BASE_URL=https://commitgotchi.store` |
| `CORS_ALLOWED_ORIGINS=https://commitgotchi.store/api` | Spring Boot fails fast in `prod` because origin includes a path | Use `https://commitgotchi.store` only |
| `SPRING_PROFILES_ACTIVE` is not `prod` in production | Refresh cookie may be issued without `Secure; SameSite=None` | Run Spring Boot with `SPRING_PROFILES_ACTIVE=prod` |
| Public Nginx serves Vue but does not proxy `/api/**` | Production web returns 404 or Vue fallback HTML for API calls | Add `/api/` proxy to Spring Boot |
| Vue points at FastAPI | Login/game API requests fail or tempt adding FastAPI browser CORS | Point Vue at Spring Boot; keep FastAPI server-to-server |
