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

### 테스트 미러 동기화 체크리스트 (SPEC-COLLECTOR-DBGRANT-003)

aaa-infra grant 스크립트(`01-init-collector.sh`, `collector-tier2-grants.sql`) 변경 시 아래 둘을 대조·갱신한다. **canonical(aaa-infra)이 항상 우선** — 미러/상수를 canonical에 맞게 갱신하며, 역방향 수정은 금지:

1. **미러 init SQL**: `src/test/resources/testcontainers/01-init-accounts-mirror.sql` (Testcontainers 계정 미러 — 상단 동기화 헤더의 기준일도 함께 갱신)
2. **`DbGrantVerifier.TIER2_TABLES`** (main 소스): Tier-2 테이블 목록의 코드 단일 소스(5개, `backfill_status` 포함). 테스트 유지보수 시 aaa-infra grant SQL보다 이 상수를 대조 기준으로 우선 사용한다(`DbGrantVerifierTest`가 정확히 5개를 고정).

**대조 비대상**: `Tier1InsertIgnoreGuardTest.TIER2_TABLE_ALLOWLIST`(4개)는 이 체크리스트와 무관한 별개 검사 목록이다. ADR-026 2026-06-20 개정이 `backfill_status`를 의도적으로 제외했다 — 가드는 `ON DUPLICATE KEY UPDATE` 패턴만 검사하는데 `backfill_status`는 그 패턴을 쓰지 않는다(시딩=`INSERT IGNORE`, 진행점 갱신=평이한 JPA UPDATE). "가드 4개 vs GRANT 5개 = 표류" 진단은 오진이므로 허용목록을 "수정"하지 말 것. 상세 절차는 룰 문서(`aaa/.claude/rules/moai/development/collector-db-grant-tiers.md`)의 Sync Checklist 참조.

### GRANT 문맥(5개) vs 가드 허용목록 문맥(4개) — 서로 다른 목적의 별개 목록

아래 두 상수는 이름이 비슷해 보이지만 **검사 대상과 목적이 다른 별개 목록**이다. 실제 리스트(`DbGrantVerifier.java`, `Tier1InsertIgnoreGuardTest.java`, `SharedContainerGuardTest.java`를 직접 대조한 결과):

| 목록 | 위치 | 개수 | 대상 | 목적 |
|------|------|------|------|------|
| `DbGrantVerifier.TIER2_TABLES` | `src/main/java/.../common/startup/DbGrantVerifier.java` (main 소스) | 5개: `stocks`, `stock_grades`, `short_sale_overseas`, `etf_metadata`, `backfill_status` | 프로덕션 부팅 self-check가 검사하는 **실제 UPDATE GRANT 대상 테이블** | "이 테이블들에 UPDATE 권한이 실제로 부여됐는가"를 기동 시점에 검증(ADR-026 CR-01) |
| `Tier1InsertIgnoreGuardTest.TIER2_TABLE_ALLOWLIST` | `src/test/java/.../arch/Tier1InsertIgnoreGuardTest.java` (테스트 소스) | 4개: `stocks`, `stock_grades`, `short_sale_overseas`, `etf_metadata` (`backfill_status` **제외**) | 정적 스캔이 `ON DUPLICATE KEY UPDATE` 리터럴 사용을 허용할 테이블 화이트리스트 | "이 SQL 패턴이 어느 테이블에 나타나면 허용/금지인가"를 빌드 타임에 정적 검사(ADR-026 결정 2) |

**차이가 나는 정확한 이유**: `backfill_status`는 GRANT 목록(5개)에는 있지만 가드 허용목록(4개)에는 없다. `backfill_status`의 UPDATE 경로는 시딩(`INSERT IGNORE`)과 진행점 갱신(JPA dirty-check를 통한 평이한 `UPDATE ... SET ... WHERE id = ?`)으로 나뉘는데, 가드가 스캔하는 패턴은 오직 `ON DUPLICATE KEY UPDATE`뿐이다 — `backfill_status`는 이 패턴을 **애초에 쓰지 않으므로** 가드 허용목록에 없어도 오탐(false negative)이 발생하지 않는다(ADR-026 2026-06-20 개정 근거). 반대로 GRANT 목록은 "UPDATE 권한을 실제로 갖는가"만 따지므로 SQL 형태와 무관하게 `backfill_status`가 포함된다. 즉 4개 vs 5개는 표류(drift)가 아니라 **두 검사의 스코프가 다르기 때문에 발생하는 의도된 차이**다.

### 테스트 패리티 규칙 (SPEC-COLLECTOR-DBGRANT-003 M1+M2 요약)

이 SPEC은 프로덕션 계정 분리 권한 모델(ADR-016/ADR-026)을 Testcontainers 통합 테스트 환경에서 재현해, 권한 위반이 NAS 프로덕션 배포가 아니라 로컬 `./gradlew check`에서 먼저 실패하게 만든다. 4개 메커니즘이 함께 동작한다:

1. **계정 미러 + 공유 컨테이너**(M1-T1, M2-T1): `SharedMySqlContainer`가 `flyway`/`collector` 계정을 미러링하는 init 스크립트를 주입한 싱글턴 MySQL 컨테이너를 제공한다. 이 컨테이너를 참조하는 테스트 클래스는 클래스 레벨 `@Transactional` 또는 `arch/SharedContainerGuardTest` 허용목록 등재가 필요하다(위 "Shared MySQL Container Convention" 참조).
2. **grant 순서 훅**(M2-T2): `Tier2GrantMigrationStrategy`(`common/startup` 테스트 소스, `@Component`로 자동 감지)가 Flyway migrate 직후 root 커넥션으로 `DbGrantVerifier.TIER2_TABLES`(5개)에 UPDATE GRANT를 적용한다. 계정 미러가 없는 전용(비공유) 컨테이너에서는 `collector` 계정 자체가 없으므로 조용히 스킵한다.
3. **fixture root 커넥션 분리**(M2-T3): DELETE/TRUNCATE가 필요한 fixture 정리는 앱 datasource가 아니라 `RootFixtureCleaner`(테이블 전체 정리) 또는 `RootFixtureCleaner.rootJdbcTemplate(...)`(스코프 한정 정리)로 수행한다 — `collector` 계정에는 DELETE 권한이 전혀 없다.
4. **앱 datasource collector 계정 전환**(M2-T4): `CollectorAccountDataSourceSwitcher`(`support` 패키지, `@Component`인 `BeanPostProcessor`)가 앱 `HikariDataSource` 빈 초기화 직후 collector 계정 접속 가능 여부를 실제로 확인하고, 가능하면(계정 미러가 주입된 컨테이너) 같은 빈의 자격증명을 collector로 교체한다(풀 타입·설정은 보존). 계정 미러가 없는 전용 컨테이너에서는 접속 시도가 실패하므로 원본 빈을 그대로 둔다 — 개별 테스트 클래스를 수정하지 않고도 공유 컨테이너 소비자 전체에 자동 적용된다. 런타임 RED 회귀 테스트(`CollectorAccountRuntimeIntegrationTest`)가 Tier-1 UPDATE의 1142 실패와 Tier-2 UPDATE의 성공을 함께 고정한다.

**핵심 불변식(HARD)**: fixture 정리(2·3)에 필요한 DELETE/UPDATE를 이유로 `collector` 계정에 새 GRANT를 추가하지 않는다 — root 커넥션으로 우회한다(ADR-026 불변성).

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

## Shared MySQL Container Convention (SPEC-COLLECTOR-DBGRANT-003 M2-T1)

`SharedMySqlContainer.MYSQL` 참조 클래스는 `@Container` 금지(싱글턴 컨테이너 패턴, `@ServiceConnection`만 사용) / 클래스 레벨 `@Transactional` 기본, 롤백 격리가 불가능하면(별도 스레드 실제 커밋, `@Modifying` 네이티브 쿼리 캐시 충돌 등) 전용 컨테이너로 분리하거나 `arch/SharedContainerGuardTest`의 허용목록에 사유와 함께 등재 / 근거는 `SharedMySqlContainer` Javadoc과 SPEC-COLLECTOR-DBGRANT-003 참조.

## Project Documents

- 프로젝트 전체 문서: `aaa-infra/docs/` — 상위 `aaa/CLAUDE.md` 참고
- 서비스 특화 문서: `docs/`
