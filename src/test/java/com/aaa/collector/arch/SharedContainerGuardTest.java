package com.aaa.collector.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.aaa.collector.support.SharedMySqlContainer;
import com.tngtech.archunit.core.domain.AccessTarget.FieldAccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;

/**
 * 공유 MySQL Testcontainers 싱글턴 재발 방지 가드 (SPEC-COLLECTOR-DBGRANT-003 M2-T1, 3중 방어 중 방어 1).
 *
 * <p>{@link com.aaa.collector.support.SharedMySqlContainer#MYSQL}(싱글턴 컨테이너 패턴)을 참조하는 테스트 클래스에 대해 두
 * 규칙을 강제한다:
 *
 * <ul>
 *   <li><b>규칙 A</b>: 공유 컨테이너를 참조하는 필드에 {@code @Container}를 붙이면 안 된다. JUnit5 Testcontainers 확장은
 *       {@code static} {@code @Container} 필드에 클래스별 {@code @AfterAll} stop() 생명주기 계약을 걸어, 여러 클래스가 같은
 *       공유 인스턴스에 각자 이 계약을 맺으면 첫 클래스 종료 시 공유 컨테이너가 죽는다.
 *   <li><b>규칙 B</b>: 공유 컨테이너를 참조하는 클래스는 클래스 레벨 {@code @Transactional}(롤백 기반 격리)이 있거나, 본 가드의 {@link
 *       #TRANSACTIONAL_EXEMPT_ALLOWLIST}에 사유와 함께 등재되어 있어야 한다. 등재 없이 두 조건을 모두 만족하지 못하면 위반으로 판정한다 —
 *       격리 전략 없이 공유 컨테이너에 쓰기를 남기는 클래스가 조용히 추가되는 것을 막는다.
 * </ul>
 *
 * <p>실 격리 불가 사례(전용 컨테이너로 분리, 허용목록 미등재 — 애초에 공유 대상이 아님): {@code
 * OverseasDailyOhlcvCollectionServiceIntegrationTest}(Virtual Thread 별도 커넥션 실제 커밋 검증), {@code
 * FinraCdnShortSaleBackfillUpsertIT}({@code clearAutomatically} 없는 {@code @Modifying} 네이티브 쿼리 + 영속성
 * 컨텍스트 캐시 충돌), {@code BackfillStatusRepositoryTest}·{@code BackfillStatusSeederTest}·{@code
 * BackfillWindowExecutorTransactionTest}·{@code DailyOhlcvRepositoryAdtvIT}(테이블 전체 {@code
 * deleteAll}/{@code deleteAllInBatch} 픽스처 의존 — 격리 전략 재설계는 M2-T3에서 처리 예정). 이 6개 클래스는 공유 컨테이너를 아예
 * 참조하지 않으므로 본 가드의 스캔 대상에 포함되지 않는다.
 *
 * <p>이 가드는 순수 클래스파일 스캔(Testcontainers 컨테이너를 인스턴스화하지 않음)이므로 {@link IntegrationTagGuardTest}와 동일하게
 * {@code @Tag("integration")} 없이 단위 test 태스크에서 실행된다.
 *
 * @see <a
 *     href="https://testcontainers.com/guides/testcontainers-container-lifecycle/">Testcontainers —
 *     테스트가 전역 상태를 변경하면 공유하지 말 것</a>
 */
@DisplayName("공유 MySQL Testcontainers 싱글턴 재발 방지 가드 (SPEC-COLLECTOR-DBGRANT-003 M2-T1)")
class SharedContainerGuardTest {

    private static final String SHARED_CONTAINER_CLASS =
            "com.aaa.collector.support.SharedMySqlContainer";
    private static final String SHARED_CONTAINER_FIELD = "MYSQL";
    private static final String CONTAINER_ANNOTATION = "org.testcontainers.junit.jupiter.Container";
    private static final String TRANSACTIONAL_ANNOTATION =
            "org.springframework.transaction.annotation.Transactional";

    /**
     * 공유 컨테이너를 참조하지만 클래스 레벨 {@code @Transactional}(롤백 기반 격리)을 쓸 수 없는 클래스의 허용목록. 등재된 각 클래스는 자체 격리
     * 전략(수동 정리 또는 읽기 전용)을 이미 보유하고 있다 — 항목 추가 시 반드시 사유 주석을 함께 남길 것(SPEC-COLLECTOR-DBGRANT-003 M2-T1
     * 실측 분류).
     */
    private static final Set<String> TRANSACTIONAL_EXEMPT_ALLOWLIST =
            Set.of(
                    // 스키마/권한만 조회(flyway.info(), information_schema, SHOW GRANTS)하고 비즈니스
                    // 데이터 행을 생성하지 않는 읽기 전용 게이트다.
                    "com.aaa.collector.DatabaseMigrationIntegrationTest",
                    // stocks 등 공유 비즈니스 테이블을 건드리지 않고 자체 격리 테이블만 사용한다(클래스
                    // Javadoc에 명시).
                    "com.aaa.collector.HikariPoolQueueingTest",
                    // @AfterEach에서 jdbcTemplate로 investor_trend/stocks 행을 명시 DELETE한다 — 별도
                    // 커넥션 실제 COMMIT이라 클래스 레벨 @Transactional 롤백으로는 애초에 격리되지 않는다
                    // (클래스 주석에 이미 문서화됨).
                    "com.aaa.collector.stock.InvestorTrendRepositoryTest",
                    // 테스트 메서드가 1개뿐이며 그 메서드에 @Transactional이 붙어 있다(클래스 레벨과
                    // 동등한 격리 효과, 리네이밍/메서드 추가 시 클래스 레벨로 승격 검토 필요).
                    "com.aaa.collector.stock.RevSplitBackfillIdempotencyIT",
                    // AC-2 위반 마이그레이션 재현 테스트 — @AfterEach에서 flyway_schema_history의
                    // V9999 행만 삭제하고 비즈니스 데이터는 생성하지 않는다(읽기 전용에 가까운 게이트).
                    "com.aaa.collector.Tier1ViolationMigrationIntegrationTest",
                    // SPEC-COLLECTOR-DBGRANT-003 M2-T2 — collector 계정의 SHOW GRANTS만 조회하고
                    // 비즈니스 데이터 행을 생성하지 않는다(읽기 전용 게이트, DatabaseMigrationIntegrationTest와 동일 사유).
                    "com.aaa.collector.common.startup.Tier2GrantMigrationStrategyIT",
                    // aaa-infra#89 — RedisHealthConfig 자동 구성 순서 회귀 테스트. 전체 컨텍스트를 띄워
                    // "redisHealthIndicator" 빈 등록 여부만 조회하고 비즈니스 데이터 행을 생성하지 않는다
                    // (읽기 전용 게이트, DatabaseMigrationIntegrationTest와 동일 사유).
                    "com.aaa.collector.common.health.RedisHealthAutoConfigurationIntegrationTest");

    @Test
    @DisplayName("규칙 A — 공유 컨테이너 참조 필드에 @Container를 붙이면 안 된다")
    void sharedContainerReferences_neverHaveContainerAnnotation() {
        // Arrange
        Iterable<JavaClass> scannedClasses =
                new ClassFileImporter().importPackages("com.aaa.collector");

        List<String> violations = new ArrayList<>();
        int scannedCount = 0;

        // Act
        for (JavaClass javaClass : scannedClasses) {
            if (javaClass.getName().startsWith(SharedContainerGuardTest.class.getName())) {
                continue;
            }
            if (!referencesSharedContainer(javaClass)) {
                continue;
            }
            scannedCount++;
            if (hasContainerAnnotatedField(javaClass)) {
                violations.add(javaClass.getFullName());
            }
        }

        // Assert
        assertThat(scannedCount)
                .as("SharedMySqlContainer.MYSQL을 참조하는 클래스가 하나도 스캔되지 않으면 가드가 동작하지 않는다")
                .isGreaterThan(0);

        if (!violations.isEmpty()) {
            fail(
                    "공유 컨테이너(SharedMySqlContainer.MYSQL) 참조 필드에 @Container가 붙은 클래스 발견 ("
                            + violations.size()
                            + "건):\n"
                            + String.join("\n", violations)
                            + "\n\n[해결] @Container를 제거하고 @ServiceConnection만 유지할 것"
                            + " (SharedMySqlContainer의 static { MYSQL.start(); }가 생명주기를 단독 소유)."
                            + "\n[근거] SPEC-COLLECTOR-DBGRANT-003 M2-T1,"
                            + " https://testcontainers.com/guides/testcontainers-container-lifecycle/");
        }
    }

    @Test
    @DisplayName("규칙 B — 공유 컨테이너 참조 클래스는 @Transactional을 갖거나 허용목록에 등재되어야 한다")
    void sharedContainerReferences_haveTransactionalOrAreAllowlisted() {
        // Arrange
        Iterable<JavaClass> scannedClasses =
                new ClassFileImporter().importPackages("com.aaa.collector");

        List<String> violations = new ArrayList<>();
        int scannedCount = 0;

        // Act
        for (JavaClass javaClass : scannedClasses) {
            if (javaClass.getName().startsWith(SharedContainerGuardTest.class.getName())) {
                continue;
            }
            if (!referencesSharedContainer(javaClass)) {
                continue;
            }
            scannedCount++;
            boolean hasTransactional = javaClass.isAnnotatedWith(TRANSACTIONAL_ANNOTATION);
            boolean isAllowlisted =
                    TRANSACTIONAL_EXEMPT_ALLOWLIST.contains(javaClass.getFullName());
            if (!hasTransactional && !isAllowlisted) {
                violations.add(javaClass.getFullName());
            }
        }

        // Assert
        assertThat(scannedCount)
                .as("SharedMySqlContainer.MYSQL을 참조하는 클래스가 하나도 스캔되지 않으면 가드가 동작하지 않는다")
                .isGreaterThan(0);

        if (!violations.isEmpty()) {
            fail(
                    "공유 컨테이너(SharedMySqlContainer.MYSQL) 참조 클래스인데 격리 전략(@Transactional 또는 허용목록)이 없는 클래스"
                            + " 발견 ("
                            + violations.size()
                            + "건):\n"
                            + String.join("\n", violations)
                            + "\n\n[해결] 클래스 레벨 @Transactional을 추가하거나, 자체 정리 로직(@AfterEach/@BeforeEach"
                            + " DELETE 등)이 있다면 SharedContainerGuardTest.TRANSACTIONAL_EXEMPT_ALLOWLIST에 사유"
                            + " 주석과 함께 등재할 것."
                            + "\n[근거] SPEC-COLLECTOR-DBGRANT-003 M2-T1 — 공유 컨테이너는 격리 전략 없이 쓰기를 남기면"
                            + " 고정 심볼 등 다른 테스트 클래스와 충돌한다(실측 사례: AAPL/NASDAQ UNIQUE 제약 위반).");
        }
    }

    @Test
    @DisplayName("RED 입증 — 공유 컨테이너 참조 + @Container 부착 fixture를 규칙 A가 탐지한다")
    void ruleA_detectsContainerAnnotationOnSharedReference() {
        // Arrange
        JavaClass fixture =
                new ClassFileImporter().importClass(Fixtures.SharedWithContainerAnnotation.class);

        // Act & Assert
        assertThat(referencesSharedContainer(fixture)).isTrue();
        assertThat(hasContainerAnnotatedField(fixture))
                .as("@Container 부착 fixture는 규칙 A 위반으로 탐지되어야 한다 (RED 입증)")
                .isTrue();
    }

    @Test
    @DisplayName("GREEN 입증 — 공유 컨테이너 참조 + @Container 미부착 fixture는 규칙 A 위반이 아니다")
    void ruleA_passesWithoutContainerAnnotation() {
        // Arrange
        JavaClass fixture =
                new ClassFileImporter()
                        .importClass(Fixtures.SharedWithTransactionalNoContainer.class);

        // Act & Assert
        assertThat(referencesSharedContainer(fixture)).isTrue();
        assertThat(hasContainerAnnotatedField(fixture))
                .as("@Container 미부착 fixture는 규칙 A 위반이 아니어야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("RED 입증 — 공유 컨테이너 참조 + @Transactional 없음 + 허용목록 미등재 fixture를 규칙 B가 탐지한다")
    void ruleB_detectsMissingTransactionalAndAllowlist() {
        // Arrange
        JavaClass fixture =
                new ClassFileImporter()
                        .importClass(Fixtures.SharedWithoutTransactionalOrAllowlist.class);

        // Act & Assert
        assertThat(referencesSharedContainer(fixture)).isTrue();
        assertThat(fixture.isAnnotatedWith(TRANSACTIONAL_ANNOTATION))
                .as("fixture는 @Transactional을 갖지 않아야 한다 (RED 입증)")
                .isFalse();
        assertThat(TRANSACTIONAL_EXEMPT_ALLOWLIST.contains(fixture.getFullName()))
                .as("fixture는 허용목록에 등재되지 않아야 한다 (RED 입증)")
                .isFalse();
    }

    @Test
    @DisplayName("GREEN 입증 — 공유 컨테이너 참조 + @Transactional 부착 fixture는 규칙 B 위반이 아니다")
    void ruleB_passesWithTransactional() {
        // Arrange
        JavaClass fixture =
                new ClassFileImporter()
                        .importClass(Fixtures.SharedWithTransactionalNoContainer.class);

        // Act & Assert
        assertThat(referencesSharedContainer(fixture)).isTrue();
        assertThat(fixture.isAnnotatedWith(TRANSACTIONAL_ANNOTATION))
                .as("@Transactional 부착 fixture는 규칙 B를 통과해야 한다")
                .isTrue();
    }

    /**
     * 클래스의 static 초기화 블록이 {@link com.aaa.collector.support.SharedMySqlContainer#MYSQL} 필드를 읽는지
     * 판별한다(정적 필드 초기화식 {@code static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;}은
     * 바이트코드상 {@code <clinit>}의 {@code getstatic} 접근으로 컴파일된다).
     *
     * <p>정의 클래스 자신({@link com.aaa.collector.support.SharedMySqlContainer})은 제외한다 — 그 클래스의 {@code
     * static { MYSQL.start(); }} 블록도 자기 필드를 읽는 {@code getstatic} 접근을 만들지만, 이는 "참조"가 아니라 정의 자체다.
     */
    private boolean referencesSharedContainer(JavaClass javaClass) {
        if (SHARED_CONTAINER_CLASS.equals(javaClass.getFullName())) {
            return false;
        }
        Optional<JavaStaticInitializer> initializer = javaClass.getStaticInitializer();
        if (initializer.isEmpty()) {
            return false;
        }
        for (JavaFieldAccess access : initializer.get().getFieldAccesses()) {
            FieldAccessTarget target = access.getTarget();
            if (SHARED_CONTAINER_CLASS.equals(target.getOwner().getFullName())
                    && SHARED_CONTAINER_FIELD.equals(target.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 클래스가 {@code @Container} 애노테이트 필드를 하나라도 갖는지 판별한다. */
    private boolean hasContainerAnnotatedField(JavaClass javaClass) {
        for (JavaField field : javaClass.getFields()) {
            if (field.isAnnotatedWith(CONTAINER_ANNOTATION)) {
                return true;
            }
        }
        return false;
    }

    /**
     * RED/GREEN 입증용 fixture. 실제 {@link com.aaa.collector.support.SharedMySqlContainer#MYSQL}을 참조하되,
     * ArchUnit {@link ClassFileImporter}는 바이트코드만 파싱하고({@code .class} 리터럴 참조는 JVM 클래스 초기화를 유발하지 않음)
     * 이 fixture들은 어떤 {@code @Test}에서도 실제로 실행되지 않으므로 컨테이너가 기동되지 않는다(REQ 참고:
     * IntegrationTagGuardTest.Fixtures와 동일 안전 원칙).
     */
    @SuppressWarnings("unused")
    static final class Fixtures {

        private Fixtures() {}

        /**
         * 공유 참조 + @Container 부착 — 규칙 A RED. {@code @Tag("integration")}는 본 규칙 A/B와 무관하지만, {@link
         * IntegrationTagGuardTest}가 프로젝트 전체를 스캔해 "@Container 필드 보유 + 태그 부재"를 별도로 검사하므로 이 fixture도 그
         * 규칙을 만족시켜야 한다.
         */
        @Tag("integration")
        static final class SharedWithContainerAnnotation {
            @Container private static final Object MYSQL = SharedMySqlContainer.MYSQL;
        }

        /** 공유 참조 + @Container 미부착 + @Transactional 부착 — 규칙 A/B 모두 GREEN. */
        @Transactional
        static final class SharedWithTransactionalNoContainer {
            private static final Object MYSQL = SharedMySqlContainer.MYSQL;
        }

        /** 공유 참조 + @Container 미부착 + @Transactional 없음 + 허용목록 미등재 — 규칙 B RED. */
        static final class SharedWithoutTransactionalOrAllowlist {
            private static final Object MYSQL = SharedMySqlContainer.MYSQL;
        }
    }
}
