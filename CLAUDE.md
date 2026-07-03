# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Service Overview

한국/미국 주식 시장 데이터 수집 서비스. KIS Open API를 통해 실시간(WebSocket) 및 배치(REST) 데이터를 수집하여 MySQL + Redis에 저장한다.

## Tech Stack

- Java 21, Virtual Threads
- Spring Boot (버전: `gradle/libs.versions.toml` 참조)
- Spring Data JPA (MySQL 8.4 LTS)
- Spring Data Redis (Redis 8.6)
- Gradle 8.14.4

## Build & Run

```bash
./gradlew build
./gradlew bootRun
```

## Key Conventions

- 스케줄링: `@Scheduled` cron만 사용 (`fixedDelay` 금지 — Virtual Threads 버그)
- 시간대: KST 통일
- 프로파일 설정: 민감 값은 환경변수(`${VAR}`)로 주입, YAML에 하드코딩 금지
- 패키지 루트: `com.aaa.collector`
- 버전 관리: 의존성 버전은 `gradle/libs.versions.toml`을 단일 소스로 관리. README에 버전 중복 기재 금지 (stale 문서 방지)

## JPA Entity Conventions

- `@Column(name=...)` 명시 — naming strategy 의존 제거, Flyway SQL과 매핑 명확화
- `nullable` 미사용 — NOT NULL 제약은 Flyway DDL이 단독 관리 (`ddl-auto=validate`에서 미검증)
- `precision`, `scale`, `length` 명시 — `ddl-auto=validate` 검증 대상
- `BaseEntity`의 `createdAt`/`updatedAt`은 `nullable=false`, `updatable=false` 유지 — JPA Auditing 동작에 필요
- `DEFAULT` 값은 Flyway DDL이 단독 관리 — 엔티티에서 `columnDefinition`으로 중복 선언하지 않음
- `TINYINT`/`SMALLINT` 등 좁은 정수 컬럼은 `@JdbcTypeCode(Types.TINYINT)` + `int` 로 매핑 — Java 타입은 도메인 의미 기준(`int`), DB 타입 일치는 `@JdbcTypeCode`로 명시. `columnDefinition` 사용 금지
- **[HARD] enum 영속 필드 `@Enumerated` 사용 금지** — 전용 `AttributeConverter`(`autoApply = true`, 저장값 `enum.name()`)를 사용한다. 배치 규칙(ADR-010):
  - 추상 베이스 `AbstractStringEnumConverter<E>`는 `com.aaa.collector.common.converter` 패키지
  - 구체 컨버터는 해당 enum의 feature 패키지(`backfill/`, `macro/`, `market/`, `stock/`, `stock/exthours/` 등)
- **네이티브 `@Query` 주의**: 네이티브 SQL 경로는 `AttributeConverter`가 적용되지 않는다. enum 컬럼을 다루는 네이티브 쿼리에서는 SpEL `.name()`·리터럴·`String @Param`으로 변환을 명시해야 한다(`autoApply`에 의존 금지).

## API Specs (`api-specs/kis/`)

- KIS API 파라미터 Description의 `Unique key(N)` 표기는 해당 값(N)을 그대로 요청 파라미터로 사용한다는 의미

## DB Grant Tiers / Idempotent Insert (ADR-026)

collector DB 사용자는 `aaa.*`에 `SELECT, INSERT`를 가지며, `UPDATE`는 Tier-2 허용목록 테이블에만 테이블별로 부여된다.

| 티어 | 해당 테이블 예시 | 권한 | 멱등 삽입 규칙 |
|------|-----------------|------|---------------|
| **Tier-1** (시계열/이벤트/로그, 쓰기 전용) | `daily_ohlcv`, `domestic_news_headlines`, `overseas_news_headlines`, `macro_indicators`, `credit_balance`, `short_sale_domestic`, `corporate_events`, `investor_trend` 등 | `SELECT, INSERT` only | **반드시 `INSERT IGNORE` 사용. `ON DUPLICATE KEY UPDATE` 절대 금지.** |
| **Tier-2** (마스터/상태, in-place 갱신 허용) | `stocks`, `stock_grades`, `short_sale_overseas`, `etf_metadata` | `SELECT, INSERT, UPDATE` | UPDATE 경로 SQL 허용 |

### SQL 1142 실패 양상

`ON DUPLICATE KEY UPDATE id = id`는 no-op처럼 보이지만, MySQL은 Unique Key 충돌 시 UPDATE 경로를 밟아 **SET 이전에** UPDATE 권한을 검사한다. Tier-1 테이블에는 UPDATE 권한이 없으므로 다음 오류가 발생한다:

```
ERROR 1142 (42000): UPDATE command denied to user 'collector'@'%' for table '<table>'
```

`INSERT IGNORE`는 동일한 "중복 무시" 결과를 내면서 INSERT 권한만 필요로 하므로 SQL 1142를 발생시키지 않는다.

### 규칙

- **[HARD]** Tier-1 테이블에 대한 멱등 삽입 네이티브 `@Query`는 반드시 `INSERT IGNORE INTO ... VALUES (...)` 형태를 사용한다.
- **[HARD]** Tier-1 SQL 1142를 GRANT 추가로 해결하지 않는다 — ADR-026 Tier-1 불변성 위반이다.
- Tier-1 멱등 삽입 메서드에는 `DailyOhlcvRepository` 형식의 `@MX:WARN`/`@MX:REASON` 가드 주석을 추가한다.
- Tier-1/Tier-2 분류 기준: "행이 삽입 후 in-place로 갱신되어야 하는가?" Yes → Tier-2 (ADR-026·이 허용목록 수정 필요), No → Tier-1 (INSERT IGNORE).
- 빌드 게이트 `./gradlew check`는 회귀 가드 테스트(`Tier1InsertIgnoreGuardTest`)로 Tier-1 리포지토리의 `ON DUPLICATE KEY UPDATE` 사용을 자동 탐지하여 빌드를 실패시킨다.

### 참조

- **ADR-026** (`aaa-infra/docs/ADR/ADR-026-collector-grant-two-tier-model.md`) — 2-tier 모델, Tier-2 허용목록
- **ADR-025** (`aaa-infra/docs/ADR/ADR-025-daily-ohlcv-raw-price-storage.md`) — SQL 1142 원인 최초 진단
- **SPEC-COLLECTOR-OHLCV-001** — `daily_ohlcv` INSERT IGNORE 전환 선례
- **SPEC-INFRA-DBGRANT-001** — Tier-2 UPDATE grant 복원 + 기동 self-check
- **SPEC-COLLECTOR-DBGRANT-002** — news/macro 및 Tier-1 전체 INSERT IGNORE 전환 + 회귀 가드
- **룰 문서** — `aaa/.claude/rules/moai/development/collector-db-grant-tiers.md`

## Test Tagging Convention (SPEC-COLLECTOR-TESTLAYER-001)

`@Container`(Testcontainers) 사용 = 통합 테스트. 클래스 레벨 `@Tag("integration")` 필수.

- **[HARD]** `@Container` 애노테이트 필드를 가진 테스트 클래스는 반드시 클래스 레벨 `@Tag("integration")`를 부여한다.
- `pre-push`는 단위 테스트만 실행한다(`./gradlew test` — 통합 태그 제외 필터, 컨테이너 기동 없음). 통합 테스트(`integrationTest` 태스크)는 CI(`./gradlew check`)에서만 실행된다.
- 태그 누락은 `arch/IntegrationTagGuardTest`(순수 클래스파일 스캔, 컨테이너 불필요)가 빌드 타임에 자동 탐지해 `./gradlew check`(및 태그 누락 클래스가 이미 존재하는 `./gradlew test`)를 실패시킨다.
- `check`가 `test`+`integrationTest`+`jacocoTestReport`+`jacocoTestCoverageVerification`을 전부 실행하므로 85% 커버리지 게이트는 단위+통합 합산 기준으로 CI에서 계속 강제된다.
- 참조: `.moai/specs/SPEC-COLLECTOR-TESTLAYER-001/spec.md`

## Project Documents

- 프로젝트 전체 문서: `aaa-infra/docs/` — 상위 `aaa/CLAUDE.md` 참고
- 서비스 특화 문서: `docs/`
