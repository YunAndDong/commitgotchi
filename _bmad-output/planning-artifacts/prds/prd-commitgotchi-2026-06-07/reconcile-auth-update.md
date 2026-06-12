# Input Reconciliation — 인증·인가 PRD 업데이트

## 결과

2026-06-11 사용자 입력의 10개 요구사항 묶음을 `prd.md`, `addendum.md`, `.decision-log.md`와 대조했다. 인증·인가 구현 증분에 필요한 요구사항은 모두 반영됐다.

## 반영 위치

- 회원가입, 로그인/JWT, Refresh Token, 로그아웃, Role 인가, 검증 API, 실패 응답: `prd.md` §4.1 FR-1~2, FR-24~28
- 인증·인가 테스트 승인 기준: `prd.md` §4.1 인증·인가 통합 승인 기준
- `USERS`, `REFRESH_TOKENS`, 1:N 관계: `prd.md` §4.1 인증 범위 데이터 모델 및 `addendum.md` 인증 데이터 모델
- 첫 구현 증분 범위와 비범위: `prd.md` §5, §6.3 및 `addendum.md` 첫 번째 구현 증분
- 변경 결정과 이유: `.decision-log.md` D6~D10

## 미해결로 명시한 항목

- Access Token 및 Refresh Token의 정확한 유효기간
- JWT 서명 알고리즘과 운영 비밀키 최소 길이
- 초기 `ADMIN` 계정 프로비저닝 방식

위 항목은 요청 입력에 확정값이 없어 `[ASSUMPTION]` 또는 Open Question으로 남겼다.
