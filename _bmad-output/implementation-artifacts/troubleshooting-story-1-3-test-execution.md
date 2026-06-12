# 트러블슈팅: Story 1.3 테스트 실행 (Gradle / JDK 환경)

작성일: 2026-06-12
대상 모듈: `springboot/`
최종 결과: **55 tests / 0 failures 통과**, Story 1.3 `done`

## 요약

Story 1.3(Role 기반 관리자 인가)의 코드 자체에는 결함이 없었다. `gradle clean test`가 통과하기까지 **빌드/실행 환경 이슈 3건**을 순차적으로 해결해야 했다. 세 건 모두 애플리케이션 코드가 아니라 Gradle 버전·호스트 JDK 버전에서 비롯된 문제다.

| # | 증상 | 근본 원인 | 해결 | 변경 파일 |
|---|---|---|---|---|
| 1 | 테스트가 시작조차 못 함 — "Failed to load JUnit Platform" | Gradle 9에서 JUnit Platform launcher 자동 등록 제거 | `testRuntimeOnly junit-platform-launcher` 추가 | `build.gradle` |
| 2 | 14개 단위 테스트가 Mockito 예외로 실패 | 호스트 기본 JDK가 너무 최신 → Mockito(ByteBuddy)가 바이트코드 못 읽음 | Gradle Java toolchain을 17로 고정 + foojay 자동 프로비저닝 | `build.gradle`, `settings.gradle` |
| 3 | (부수) Gradle 10 비호환 deprecation 경고 | Spring Boot 3.3.5 플러그인은 Gradle 8.x 기준 | 현재는 경고일 뿐 — 무시 가능 (권장 대응 아래) | — |

---

## 이슈 1 — "Failed to load JUnit Platform"

### 증상
```
> Could not start Gradle Test Executor 1.
   > Failed to load JUnit Platform.  Please ensure that all JUnit Platform
     dependencies are available on the test's runtime classpath,
     including the JUnit Platform launcher.
BUILD FAILED
```
테스트가 한 개도 실행되지 않고 executor 기동 단계에서 실패.

### 근본 원인
Gradle 8.3 이후, 특히 **Gradle 9**부터 `junit-platform-launcher`를 테스트 런타임 클래스패스에 자동으로 추가해 주던 동작이 제거되었다. 과거에는 Gradle 내부가 launcher를 암묵적으로 주입했지만, 이제는 프로젝트가 명시적으로 선언해야 한다. `spring-boot-starter-test`는 JUnit Jupiter(API/엔진)는 가져오지만 launcher 런타임 의존성은 포함하지 않는다.

이 환경은 `gradle 9.5.1`을 사용 중이라 정확히 이 변경에 걸렸다.

### 해결
`springboot/build.gradle`의 `dependencies` 블록에 한 줄 추가:
```gradle
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```
버전은 Spring Boot 의존성 관리(`io.spring.dependency-management`)가 정해 주므로 명시하지 않는다.

### 확인
재실행하면 executor가 정상 기동되어 테스트가 실제로 돌기 시작한다(→ 이슈 2가 드러남).

---

## 이슈 2 — Mockito mock 생성 실패 (JDK 버전 불일치)

### 증상
`AuthServiceTest`, `JwtAuthenticationFilterTest`의 단위 테스트 14개가 모두 동일한 스택으로 실패:
```
org.mockito.exceptions.base.MockitoException at MockitoExtension.java:160
  Caused by: org.mockito.exceptions.base.MockitoException at TypeCache.java:168
    Caused by: java.lang.IllegalStateException at InlineBytecodeGenerator.java:285
      Caused by: java.lang.IllegalArgumentException at OpenedClassReader.java:100
```
실행 로그 상단에 다음 경고도 동반:
```
WARNING: A restricted method in java.lang.System has been called ...
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes ...
```

핵심 단서: 실패는 **mock을 만드는 순간**(`InlineBytecodeGenerator`, `OpenedClassReader`)에 발생하며, 테스트 단언(assert)에 도달하지 못한다. 즉 테스트 로직 실패가 아니라 mock 생성 인프라 실패다. Testcontainers를 쓰는 **통합 테스트(`AdminAuthorizationIntegrationTest` 등)는 모두 통과**했는데, 이들은 Mockito inline mock을 쓰지 않기 때문이다.

### 근본 원인
Mockito의 inline mock maker는 ByteBuddy → ASM(`OpenedClassReader`)으로 대상 클래스의 바이트코드를 읽는다. 실행 중인 **JDK가 프로젝트 기준(Java 17)보다 훨씬 최신**이라, 번들된 ASM이 그 최신 클래스 파일 메이저 버전을 인식하지 못해 `IllegalArgumentException`을 던진다. 로그의 "restricted method / class data sharing" 경고도 최신 JDK 런타임의 특징이다.

프로젝트의 의도된 런타임은 Java 17이다(`build.gradle` `sourceCompatibility = '17'`, `Dockerfile`의 `gradle:8.10-jdk17` 빌드 스테이지와 `eclipse-temurin:17-jre` 런타임 스테이지). 단지 호스트의 기본 `gradle`이 최신 JDK 위에서 돌고 있었던 것이 원인.

### 해결
호스트 JDK가 무엇이든 **테스트를 항상 JDK 17에서 실행**하도록 Gradle Java toolchain을 고정한다.

`springboot/build.gradle`:
```gradle
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
```
(기존 `sourceCompatibility = '17'`을 toolchain 블록으로 대체)

`springboot/settings.gradle` — JDK 17이 설치돼 있지 않은 환경에서도 자동으로 받아오도록 foojay resolver 추가:
```gradle
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.8.0'
}

rootProject.name = 'commitgotchi-springboot'
```

동작 방식: Gradle이 시스템에서 JDK 17을 탐지하면 그것을 사용하고, 없으면 foojay가 Temurin 17을 1회 다운로드해 사용한다. 이후 컴파일·테스트가 모두 JDK 17에서 수행되어 Mockito가 정상 동작한다.

### 대안 (참고)
- **JAVA_HOME 수동 지정:** JDK 17이 설치돼 있다면 `JAVA_HOME=$(/usr/libexec/java_home -v 17) gradle clean test`. 단, 머신마다 재현성이 떨어져 toolchain 방식보다 비권장.
- **Mockito 버전 상향:** 최신 JDK를 지원하는 Mockito로 올리면 JDK를 그대로 두고도 통과 가능. 다만 Spring Boot가 버전을 관리하므로 오버라이드가 필요하고, 정확한 호스트 JDK 버전 의존성이 생겨 결정성이 낮다. 프로젝트 기준이 Java 17이므로 toolchain 고정이 더 적절.

### 확인
재실행 시 Gradle이 JDK 17 toolchain으로 테스트를 수행하고 14개 단위 테스트가 통과 → **55 tests / 0 failures**.

---

## 이슈 3 (부수) — Gradle 10 비호환 deprecation 경고

### 증상
```
Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.
```

### 원인
Spring Boot 3.3.5 / `io.spring.dependency-management` 플러그인은 공식적으로 Gradle 8.x 기준으로 검증되어 있다. Gradle 9.5.1에서 실행하면 일부 API가 deprecated로 보고된다.

### 대응
현재는 **경고일 뿐 빌드를 막지 않으므로 무시 가능**. 다만 재현성과 공식 지원 범위를 맞추려면 다음 중 하나를 권장:
- **(권장) Gradle wrapper 도입 + Gradle 8.10.x 고정.** 현재 이 모듈에는 `gradlew` 래퍼가 없어 호스트 전역 `gradle`(9.5.1)에 의존한다. `gradle wrapper --gradle-version 8.10.2`로 래퍼를 생성하면 Dockerfile(`gradle:8.10-jdk17`)과 버전이 일치하고, 팀원 간 빌드 재현성이 확보되며 이슈 1·3이 애초에 발생하지 않는다.
- 또는 Spring Boot 플러그인이 Gradle 9를 공식 지원하는 버전으로 올릴 때까지 대기.

---

## 재발 방지 체크리스트

- [x] `springboot/`에 Gradle wrapper(`gradlew`, `gradle/wrapper/`) 추가, 버전 8.10.2로 고정 — 호스트 `gradle` 버전 편차 제거 (이슈 1·3 근절). 2026-06-12 `gradle wrapper --gradle-version 8.10.2`로 생성, `./gradlew clean test` 통과 확인.
- [x] `build.gradle`에 `testRuntimeOnly junit-platform-launcher` 유지
- [x] `build.gradle` Java toolchain 17 고정 + `settings.gradle` foojay resolver — 호스트 JDK 편차와 무관하게 JDK 17에서 테스트 (이슈 2 근절)
- [ ] CI에서도 동일 toolchain/Gradle 버전으로 `clean test` 수행해 로컬과 일치 보장

## 빠른 실행 가이드

```bash
cd /Users/kimyunseok/Desktop/hackaton/commitgotchi/springboot
# Docker Desktop이 켜져 있어야 함 (Testcontainers PostgreSQL 16)
./gradlew clean test     # wrapper 사용(8.10.2 고정) — 호스트 gradle 버전과 무관
# 기대: BUILD SUCCESSFUL, 55 tests, 0 failures
```
