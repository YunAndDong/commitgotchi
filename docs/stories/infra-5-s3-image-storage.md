---
story: INFRA-5
status: draft
scope: infra/app (FastAPI S3 업로드 + Spring 조회 시 presigned GET, key 저장 방식)
phase: Phase 6
plan: ../mvp-cicd-pipeline-plan.md
refs:
  - ../../fastapi/docs/character-image/stories/character-image-3-storage-adapter-s3-ready-contract.md
related_files:
  - fastapi/app/image/                 # S3 업로드 adapter (app 측)
  - springboot/.../character/image/    # presign(S3Presigner) + 조회 발급
  - springboot/.../character/application/CharacterGameProjectionService.java
---

# Story INFRA-5: S3 이미지 저장 + 조회 시 presigned GET (key 저장 방식)

Status: draft

## Story

As a 운영자/개발자,
I want FastAPI가 생성한 1×3 sprite를 공용 S3 버킷에 저장하고, Spring이 조회 시점에 그 객체의 presigned GET URL을 발급해 프런트(확장)에 내려주도록,
so that 캐릭터 이미지가 영속되고(§4.4 HTTP 계약 유지) 만료 URL을 DB에 박지 않고도 안정적으로 표시된다.

## 전제 / 확정 설계

- **HTTP 계약 불변**: FastAPI↔Spring 응답 §4.4 shape(`userId,status,s3ObjectUrl,spriteSheetUrl,spriteMeta`) 유지.
- **공용 버킷 + prefix 분리**: dev/local=`dev/`, prod=`prod/`. 버킷명은 `commitgotchi-character-images-491013322019`(계정 접미사, INFRA-2 생성값).
- **object key는 characterId 기반 결정적**: `{S3_OBJECT_PREFIX}characters/{characterId}/sprite-sheet.png`. key 생성 책임은 Spring(`CharacterImageStorageUrlFactory`). FastAPI는 key 생성/반환 책임 없음.
- **업로드(write)**: FastAPI가 생성 sprite를 S3에 직접 저장.
  - S3 backend면 로컬 파일 안 쓰고 **메모리 bytes에서 바로 put_object**(로컬 잔여물 0). local backend는 기존 로컬 저장 유지(기본값 local, S3는 opt-in).
- **영속(DB)**: Spring은 FastAPI `status=OK`면 표시 URL이 아니라 **S3 object key(또는 s3://bucket/key 위치)** 를 `sprite_sheet_url` 컬럼에 저장한다(컬럼 의미를 key 저장으로 재정의 → **DB 마이그레이션 없음**, READY NOT NULL 제약 만족). **만료 presigned URL을 DB에 저장하지 않는다.**
- **조회(read)**: `CharacterGameProjectionService`가 저장된 key로 **매 조회 시 GET presigned URL을 발급**해 `spriteSheetUrl`로 내려준다(재발급, 만료 안전).
- **자격증명 = credential chain (정적 키 평문 의존 제거)**:
  - 이미 SQS는 AWS SDK v2 + DefaultCredentialsProvider(chain)로 동작 중. **S3 presign도 같은 방식으로 통일**한다.
  - 현재 `CharacterImageStorageUrlFactory`는 수동 SigV4로 정적 access key/secret을 `requireText`로 강제 → **AWS SDK v2 `S3Presigner` + `DefaultCredentialsProvider`로 전환**(정적 키 필수 제거).
  - 로컬=AWS profile, prod=EC2 instance role. **SSM에 AWS access/secret key를 넣지 않는다**(현재도 안 넣음; GEMINI_API_KEY만 SecureString).
  - FastAPI 업로드도 boto3 default chain.
- **버킷은 public block 유지**. public 전환/CloudFront는 구현하지 말고 후속 후보로만 문서화.

## 로컬 개발자 셋업 / 테스트 절차

SDK(boto3 / AWS SDK v2 S3)는 코드 의존성으로 포함된다(개발자가 따로 설치 안 함). 개발자는 자격증명 + 스위치만:

1. AWS 프로필 (MVP는 `commitgotchi-bootstrap` 재사용):
   ```bash
   aws sts get-caller-identity --profile commitgotchi-bootstrap --region ap-northeast-2
   ```
2. `.env`에 스위치만(키 평문 금지):
   ```bash
   CHARACTER_IMAGE_STORAGE_BACKEND=s3
   S3_BUCKET_NAME=commitgotchi-character-images-491013322019
   S3_OBJECT_PREFIX=dev/
   AWS_REGION=ap-northeast-2
   AWS_PROFILE=commitgotchi-bootstrap
   ```
3. compose가 host 프로필을 컨테이너에 전달(read-only 마운트):
   ```yaml
   environment:
     AWS_PROFILE: ${AWS_PROFILE:-}
     AWS_REGION: ${AWS_REGION:-ap-northeast-2}
   volumes:
     - ~/.aws:/home/appuser/.aws:ro   # 컨테이너 유저 홈에 맞게
   ```
4. 실행/확인:
   ```bash
   docker compose up --build
   aws s3 ls s3://commitgotchi-character-images-491013322019/dev/characters/ --profile commitgotchi-bootstrap
   ```

- **AWS 없는 개발자**: `CHARACTER_IMAGE_STORAGE_BACKEND=local`(기본) → S3 없이 앱 구동(생성 이미지는 기본 sprite).
- **자동화 테스트**: fake S3 client 주입 → AWS 자격증명 0으로 통과. 실제 S3는 수동 검증.

> ⚠️ `commitgotchi-bootstrap`은 풀권한 admin(부트스트랩 전용). 로컬 편의로 재사용하되, **운영 고정 전 S3 `dev/*`만 가능한 dev 전용 least-privilege 프로필로 축소 권장**(plan §7 원칙).

## Acceptance Criteria

### AC1 — S3 업로드 (FastAPI)
- **When** FastAPI가 캐릭터 이미지를 생성하면(S3 backend),
- **Then** `{prefix}characters/{characterId}/sprite-sheet.png` 키로 `ContentType=image/png` 업로드되고, 로컬 파일 잔여물이 없다.
- **And** object key에 designKeyword/prompt/사용자입력/`../`가 들어가지 않는다.

### AC2 — 영속은 key (Spring)
- **Then** `status=OK`면 `sprite_sheet_url` 컬럼에 **만료 URL이 아니라 key(또는 s3:// 위치)** 가 저장된다.

### AC3 — 조회 시 presigned GET (Spring)
- **When** 프런트가 캐릭터를 조회하면,
- **Then** Spring이 저장 key로 **그 시점에 GET presigned URL을 발급**해 `spriteSheetUrl`로 내려준다(매 조회 재발급).
- **And** presign 자격증명은 chain(로컬 profile/prod instance role)에서 오며 정적 키를 prod 필수로 만들지 않는다.

### AC4 — 계약/권한 경계
- **Then** §4.4 응답 shape, Spring이 읽는 `status/spriteSheetUrl/spriteMeta.frameMap(joy,sad,angry)` 유지.
- **And** S3 접근은 instance role의 버킷 한정 권한, dev/prod는 prefix 분리. 버킷 public block 유지.

### AC5 — 고도화 의사결정 기록
- **Then** RDS/ECS/ALB/Secrets Manager/CloudFront/버킷 분리/dev 전용 IAM 전환 여부와 트리거 조건이 문서로 남는다.

## 검증 / 보류
- fake/mock S3로: 업로드 key/contentType, dev·prod prefix, path traversal 차단, 실패 시 status=FAIL, 조회 presign 발급, **만료 URL DB 미저장**, §4.4 회귀.
- 🔶 실제 S3 업로드 + 조회 presign + 브라우저 표시 확인은 실 AWS/배포 필요 → **실 검증 전 done으로 닫지 말 것**.
- 🔶 운영 고도화(CloudFront/public 전환/RDS/ECS/Secrets Manager/버킷 분리)는 결정/트리거만, 구현은 후속.

> plan 말미와 동일: MVP 기준 계획이며, 운영 고도화 단계에서 ECS/RDS/ALB/Secrets Manager(및 S3 버킷 분리, dev 전용 IAM) 전환을 재검토한다.
