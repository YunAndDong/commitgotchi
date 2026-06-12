# Deferred Work

## Deferred from: code review of 1-3-role-based-admin-authorization (2026-06-12)

- prod/default 프로필에서 `/api/admin/ping`가 Swagger/`/v3/api-docs`에 노출되지 않음을 검증하는 전용 테스트가 없음. 현재는 prod/default에서 Swagger 자체가 비활성(기존 `ProdSwaggerDisabledIntegrationTest` 등)이라 전이적으로 커버되지만, admin 경로 추가에 대한 회귀 단언이 명시적이지 않음. AC7 후반 요건. 위험 낮음.
