# Commit-Gotchi

혼자 CS를 공부하는 사람의 매일 학습을 **가상 캐릭터 성장**으로 바꿔주는 학습 동반자 서비스입니다.

사용자는 하루 학습 내용을 리포트로 남기고 추천 퀴즈를 풉니다. Spring Boot가 사용자, 캐릭터, 리포트, 퀴즈, 점수 상태를 관리하고, FastAPI가 LLM 기반 리포트 분석, 퀴즈 채점, 추천, 캐릭터 이미지 생성을 담당합니다. 분석 결과는 캐릭터의 능력치, 감정, 진화 상태에 반영됩니다.

## 현재 상태

MVP는 Chrome 확장 클라이언트, Spring Boot 백엔드, FastAPI AI 서버, PostgreSQL, AWS 기반 배포 파이프라인으로 구성되어 있습니다.

- Vue는 Chrome 확장프로그램 클라이언트로 동작합니다.
- Spring Boot는 브라우저-facing API와 System of Record 역할을 맡습니다.
- FastAPI는 브라우저에 직접 노출하지 않고 Spring Boot와 서버 간 계약으로만 통신합니다.
- 운영 배포는 Spring Boot/FastAPI Docker image, API-only Nginx, PostgreSQL 컨테이너, SSM 기반 환경 주입, ECR/S3/SSM Run Command를 기준으로 구성했습니다.
- 일일 리포트, 퀴즈 채점, 캐릭터 이미지 생성 흐름은 로컬 통합 환경에서 end-to-end로 검증했습니다.

## 주요 기능

| 영역 | 내용 |
| --- | --- |
| 인증 | 회원가입, 로그인, JWT access token, refresh token rotation, 로그아웃, 역할 기반 인가 |
| 캐릭터 | 생성, 조회, 수정, 삭제, 활성 캐릭터 1개 보장, 사용자당 최대 3개 |
| 이미지 | 캐릭터 생성 시 FastAPI가 sprite sheet 생성, S3 업로드, Spring Boot가 조회 시 presigned GET URL 발급 |
| 일일 리포트 | 학습 리포트 저장, SQS 기반 비동기 분석, 점수 변화량과 다음 학습 추천 반영 |
| 퀴즈 | 추천 퀴즈 풀이, FastAPI 비동기 채점, 피드백과 점수 반영 |
| 성장 | DB, algorithm, CS, network, framework 5개 능력치와 전투력 계산 |
| 감정 | 캐릭터 감정(JOY/SAD/ANGRY)을 AI 피드백 말투와 sprite frame에 반영 |
| 랭킹/게시판 | 전투력 기반 랭킹, 캐릭터 공유와 리뷰 흐름 |
| Fallback | 이미지 생성, 리포트 분석, 퀴즈 채점 실패 시 저장 흐름을 끊지 않는 기본 결과 제공 |

## 시스템 구조

```text
Chrome Extension(Vue)
        |
        | HTTPS / JWT
        v
API-only Nginx
        |
        | /api/**, /character-assets/**
        v
Spring Boot (System of Record)
        |
        | PostgreSQL: users, characters, game/report/quiz state
        v
PostgreSQL

Spring Boot -- internal HTTP --> FastAPI
Spring Boot -- SQS report request --> FastAPI worker -- callback --> Spring Boot
FastAPI -- Gemini / RAG / image processing --> AI result
FastAPI -- S3 upload --> private S3 object
Spring Boot -- presigned GET --> sprite image URL
```

핵심 경계는 단순합니다.

- 브라우저는 Spring Boot만 호출합니다.
- FastAPI는 Spring Boot DB에 직접 접근하지 않습니다.
- 리포트 분석은 SQS at-least-once 전달과 `requestId` 멱등 처리로 중복 점수 반영을 막습니다.
- 퀴즈 채점은 `submissionId` 중심으로 결과를 매칭합니다.
- 이미지 URL은 만료되는 presigned URL이므로 조회 응답에서 새로 받은 값을 사용합니다.

## 담당 영역

| 담당자 | 담당 범위 | 주요 작업 |
| --- | --- | --- |
| 김윤석 | Spring Boot, Vue Chrome 확장 | 인증/인가, 캐릭터/게임 상태, 리포트/퀴즈 결과 반영, 랭킹/게시판, 확장 UI와 상태 연동 |
| 신동운 (`tlsdla1235`) | FastAPI, AI/RAG, 이미지 처리, CI/CD/인프라 | 리포트 분석, 퀴즈 채점, 추천/RAG, sprite 생성/후처리/S3 저장, SQS worker, ECR/SSM/EC2 배포 파이프라인 |

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Frontend | Vue 3, Vite, Chrome Extension |
| Backend | Spring Boot 3.3.5, Java 17, Spring Security, MyBatis, Flyway |
| AI Service | FastAPI 0.115, Python 3.11, SQLAlchemy, google-genai, Pillow |
| Database | PostgreSQL 16 |
| Async/Storage | AWS SQS, AWS S3 |
| Infra/Deploy | Docker, Docker Compose, Nginx, GitHub Actions, ECR, EC2, SSM Parameter Store |

## AI 서버 설계

FastAPI는 모델 호출을 그대로 외부로 노출하지 않고, 서비스 계약에 맞는 안전한 결과로 정규화합니다.

- 리포트 분석: 리포트 본문과 RAG evidence를 기반으로 주제, field evidence, 점수 변화량, 피드백, 다음 추천을 JSON으로 반환합니다.
- 퀴즈 채점: 문제, 모범답안, 사용자 답안, field별 배점을 입력으로 받아 field별 점수와 피드백을 반환합니다.
- 추천/RAG: concept catalog와 problem bank를 기반으로 학습 리포트와 연결되는 근거와 추천 문제를 찾습니다.
- 이미지 생성: 디자인 키워드를 canonical prompt로 정규화하고, 생성 이미지의 배경 제거, frame grid 정규화, 품질 게이트를 통과한 sprite만 READY로 처리합니다.
- 실패 처리: 모델 응답 파싱 실패, 낮은 confidence, 외부 API 오류, 이미지 품질 실패는 FALLBACK/UNGRADED 결과로 내려 흐름을 유지합니다.

관련 구현:

- [report_analyzer.py](fastapi/app/scoring/report_analyzer.py)
- [quiz_grader.py](fastapi/app/scoring/quiz_grader.py)
- [prompts.py](fastapi/app/image/prompts.py)
- [quality_gate.py](fastapi/app/image/quality_gate.py)
- [report_consumer.py](fastapi/app/integration/report_consumer.py)

## 운영 배포 구조

운영 배포는 Vue를 서버에서 서빙하지 않는 **backend/API-only 구성**입니다. Vue는 Chrome 확장프로그램으로 빌드/배포하고, 확장은 `https://commitgotchi.store` API를 호출합니다.

| 구성 요소 | 운영 방식 |
| --- | --- |
| Spring Boot | Docker image를 ECR에 push 후 EC2 compose에서 실행 |
| FastAPI | Docker image를 ECR에 push 후 EC2 compose에서 실행 |
| FastAPI worker | report queue가 켜진 경우 compose `worker` profile로 실행 |
| PostgreSQL | EC2 내부 PostgreSQL 16 컨테이너와 named volume |
| Nginx | API-only reverse proxy, `/api/**`와 `/character-assets/**`를 Spring Boot로 프록시 |
| Secret/config | EC2 instance role이 SSM Parameter Store에서 읽어 `.env.prod` 생성 |
| 배포 번들 | `docker-compose.prod.yml`, `nginx/api-only.conf`, init script, deploy script만 S3에 업로드 |
| 이미지 저장 | private S3 bucket + `dev/`/`prod/` prefix 분리 |

배포 흐름:

1. GitHub Actions가 Spring Boot/FastAPI 테스트를 실행합니다.
2. deploy workflow가 두 백엔드 Docker image를 `sha-<commit>` 태그로 ECR에 push합니다.
3. 최소 런타임 번들을 S3에 업로드합니다.
4. SSM Run Command로 EC2가 번들을 내려받고 `/opt/commitgotchi`에 배치합니다.
5. EC2의 `scripts/deploy.sh`가 SSM 값을 읽어 `.env.prod`를 만들고 compose를 재기동합니다.
6. `/api/health`와 기본 sprite asset으로 smoke check를 수행합니다.

관련 파일:

- [ci.yml](.github/workflows/ci.yml)
- [deploy.yml](.github/workflows/deploy.yml)
- [docker-compose.prod.yml](docker-compose.prod.yml)
- [deploy.sh](scripts/deploy.sh)
- [scripts README](scripts/README.md)
- [AWS bootstrap README](scripts/aws/README.md)

## 로컬 실행

루트 `.env.example`을 복사해 로컬 통합 실행 값을 준비합니다.

```bash
cp .env.example .env
openssl rand -base64 32
```

필수로 확인할 값:

| 변수 | 용도 |
| --- | --- |
| `JWT_SECRET_BASE64` | Spring Boot JWT 서명 키 |
| `CORS_ALLOWED_ORIGINS` | 로컬 Vue/확장 origin allowlist |
| `SPRING_INTERNAL_API_SECRET` | Spring Boot와 FastAPI internal auth |
| `GEMINI_API_KEY` | 실제 AI 분석/채점/이미지 생성을 사용할 때 필요 |
| `CHARACTER_IMAGE_STORAGE_BACKEND` | `local` 또는 `s3` |
| `REPORT_REQUEST_QUEUE_ENABLED` | SQS 리포트 분석 흐름 활성화 여부 |

전체 로컬 compose:

```bash
docker compose up --build
```

기본 포트:

| 서비스 | URL |
| --- | --- |
| Vue local preview | `http://localhost:5173` |
| Spring Boot | `http://localhost:8080` |
| FastAPI | `http://localhost:8000` |
| FastAPI Swagger | `http://localhost:8000/docs` |
| Spring Swagger(local/dev/test) | `http://localhost:8080/swagger-ui/index.html` |

SQS worker까지 함께 실행하려면:

```bash
docker compose --profile worker up --build
```

## 테스트

Spring Boot:

```bash
cd springboot
./gradlew test
```

FastAPI:

```bash
cd fastapi
python -m pytest
```

Vue:

```bash
cd vue
npm test
```

CI에서는 Spring Boot 테스트, FastAPI 테스트, Spring Boot/FastAPI Docker build validation을 실행합니다. 운영 deploy workflow는 수동 `workflow_dispatch`와 GitHub Environment `prod`를 기준으로 합니다.

## 보안과 운영 기준

- 운영 secret은 GitHub Secrets가 아니라 SSM Parameter Store에 둡니다.
- EC2 배포는 instance role credential chain을 사용하며, 정적 AWS access key를 `.env.prod`에 두지 않습니다.
- Spring Boot `prod` profile은 HTTPS origin 없는 CORS 설정, wildcard, origin pattern, path/query/fragment를 거부합니다.
- CORS는 Spring Boot가 `/api/**`에만 적용하며 Nginx가 중복 CORS 헤더를 붙이지 않습니다.
- FastAPI는 browser-facing CORS 대상이 아닙니다.
- refresh token은 DB에 SHA-256 hash로 저장하고 rotation/reuse revocation을 적용합니다.
- 운영 Swagger는 비활성화합니다.
- debug endpoint는 `COMMITGOTCHI_DEBUG_ENABLED=false`가 기본이며, 시연 시에만 켜는 임시 경로입니다.

## 디렉터리 구조

```text
commitgotchi/
├── springboot/              # Spring Boot System of Record
├── fastapi/                 # AI/RAG/image processing service
├── vue/                     # Vue Chrome extension/client source
├── postgres/init/           # local/prod PostgreSQL init scripts
├── nginx/                   # API-only Nginx config
├── scripts/                 # deploy, TLS, bundle, AWS bootstrap scripts
├── .github/workflows/       # backend CI and production deploy workflows
├── docs/                    # integration/deploy docs and infra stories
├── _bmad-output/            # planning and implementation records
├── docker-compose.yml       # local integrated runtime
└── docker-compose.prod.yml  # production API-only runtime
```

## 문서

- [통합 검증 & 프론트엔드 연동 가이드](docs/frontend-integration-guide.md)
- [MVP CI/CD 배포 계획](docs/mvp-cicd-pipeline-plan.md)
- [운영 배포 스크립트](scripts/README.md)
- [AWS 부트스트랩](scripts/aws/README.md)
- [FastAPI 연동 가이드](fastapi/README.md)
- [public Nginx reverse proxy runbook](springboot/docs/public-nginx-reverse-proxy-runbook.md)
