package com.aaa.collector.support;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 공유 MySQL Testcontainers 지원 클래스 (SPEC-COLLECTOR-DBGRANT-003 M1-T1).
 *
 * <p>프로덕션 계정 분리(ADR-026: {@code flyway}=DDL 전용, {@code collector}=DML 최소권한)를 재현하는 계정 미러 init
 * 스크립트({@code testcontainers/01-init-accounts-mirror.sql})를 컨테이너 {@code
 * /docker-entrypoint-initdb.d/}에 주입한다.
 *
 * <p>구성 방식(v2 — 합성 우선, 상속 지양): 이 클래스는 상속용 베이스 클래스가 아니라 순수 정적 유틸리티다. 컨테이너 인스턴스와 계정 조회 헬퍼를 한 곳에서
 * 소유하고, 이를 사용하는 테스트 클래스는 {@code @ServiceConnection} 필드(단, {@code @Container}는 절대 붙이지 않는다 — 아래 싱글턴
 * 컨테이너 패턴 참고)를 자신의 클래스에서 {@code SharedMySqlContainer.MYSQL}을 참조해 재선언한다(Spring의 리플렉션 스캔은 테스트 클래스 자신의
 * 필드 계층만 인식하므로 재선언이 필요하다). 동일 인스턴스를 참조하므로 다수의 테스트 클래스가 이 필드를 재선언해도 컨테이너는 1개만 기동된다(M2가 채택한 확장 지점).
 *
 * <p>M2 확장 지점(과설계 금지 — 지금은 구현하지 않음): 앱 datasource를 {@code collector} 계정으로 전환하는 프로퍼티 주입과, Flyway
 * migrate 이후 Tier-2 GRANT를 적용하는 순서 훅은 이 클래스에 정적 메서드로 추가한다.
 */
public final class SharedMySqlContainer {

    /** 계정 미러 init 스크립트의 클래스패스 경로(테스트 리소스 동기화 대상, REQ-DBGRANT3-005). */
    public static final String ACCOUNTS_MIRROR_INIT_SCRIPT =
            "testcontainers/01-init-accounts-mirror.sql";

    /** {@code 01-init-accounts-mirror.sql}의 계정 상수와 반드시 일치해야 한다. */
    public static final String FLYWAY_USERNAME = "flyway";

    public static final String FLYWAY_PASSWORD = "flyway-test-password";
    public static final String COLLECTOR_USERNAME = "collector";
    public static final String COLLECTOR_PASSWORD = "collector-test-password";

    /**
     * root 계정 접속 정보(M2-T2 — grant 순서 훅이 Tier-2 UPDATE GRANT를 적용할 때 사용). 이 프로젝트의 모든 Testcontainers
     * MySQL 컨테이너는 {@code withUsername}/{@code withPassword} 오버라이드 없이 기본값을 쓴다(실측 확인, grep 0건).
     * Testcontainers {@link MySQLContainer}는 root 이외 사용자를 쓸 때 {@code MYSQL_ROOT_PASSWORD} 환경변수를 해당
     * 사용자의 비밀번호와 동일하게 설정하므로, 기본 비밀번호("test")가 root 비밀번호와 같다. 프로덕션 시크릿이 아닌 테스트 전용 일회성 컨테이너 상수다.
     */
    private static final String ROOT_USERNAME = "root";

    private static final String ROOT_PASSWORD = "test";

    /**
     * 계정 미러 init 스크립트가 주입된 공유 MySQL 컨테이너. 이 컨테이너를 사용하려는 테스트 클래스는 자신의 {@code @ServiceConnection} 정적
     * 필드({@code @Container}는 절대 붙이지 않는다)를 이 상수로 초기화해 재선언한다.
     */
    public static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource(ACCOUNTS_MIRROR_INIT_SCRIPT),
                            "/docker-entrypoint-initdb.d/01-init-accounts-mirror.sql");

    /**
     * "싱글턴 컨테이너 패턴"(Singleton Containers Pattern, M2-T1 REQ-DBGRANT3-0xx). 이 컨테이너를 참조하는 테스트 클래스는 절대
     * 자신의 필드에 {@code @Container}를 붙이면 안 되고 {@code @ServiceConnection}만 사용해야 한다. JUnit5
     * Testcontainers 확장은 {@code static} 필드의 {@code @Container}에 대해 "그 클래스의 모든 테스트가 끝나면(@AfterAll)
     * stop()"이라는 생명주기 계약을 건다 — 여러 클래스가 같은 공유 인스턴스에 대해 각자 이 계약을 맺으면, 첫 클래스가 끝나는 순간 공유 컨테이너가 죽어 나머지
     * 클래스들이 {@code ConnectException}을 겪는다. 생명주기(시작)는 이 static 블록이 단독으로 소유하며, 컨테이너 자체는 JVM 종료 시
     * Testcontainers Ryuk 리소스 리퍼가 회수한다.
     *
     * <p><b>격리 규칙(M2-T1)</b>: 이 컨테이너를 참조하는 테스트 클래스는 클래스 레벨 {@code @Transactional}(롤백 기반 격리)을 기본으로
     * 붙인다. 다음처럼 트랜잭션 롤백으로 격리할 수 없는 경우에는 이 컨테이너를 공유하지 말고 {@code @Container @ServiceConnection static
     * final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");}로 전용 컨테이너를 쓴다: (1) 프로덕션
     * 코드가 Virtual Thread 등 별도 스레드/커넥션으로 실제 커밋하는 동작 자체를 검증하는 테스트, (2) {@code clearAutomatically}가 없는
     * {@code @Modifying} 네이티브 쿼리를 하나의 트랜잭션(영속성 컨텍스트)에서 반복 호출·재조회해 스테일 리드가 발생하는 테스트. 위 두 경우가 아니라면(예:
     * 자체 {@code @BeforeEach}/{@code @AfterEach} DELETE 정리, 읽기 전용 게이트) 공유는 유지하되 {@link
     * com.aaa.collector.arch.SharedContainerGuardTest#TRANSACTIONAL_EXEMPT_ALLOWLIST}에 사유와 함께 등재해야
     * 한다 — 등재 없이 격리 전략 없는 공유는 {@code SharedContainerGuardTest} 규칙 B가 빌드를 막는다.
     *
     * @see <a
     *     href="https://docs.docker.com/guides/testcontainers-java-lifecycle/singleton-containers/">Testcontainers
     *     Java Lifecycle — Singleton Containers</a>
     * @see <a
     *     href="https://spring.io/blog/2023/06/23/improved-testcontainers-support-in-spring-boot-3-1/">Improved
     *     Testcontainers Support in Spring Boot 3.1</a>
     * @see <a
     *     href="https://testcontainers.com/guides/testcontainers-container-lifecycle/">Testcontainers
     *     — 테스트가 전역 상태를 변경하면 공유하지 말 것</a>
     */
    static {
        MYSQL.start();
    }

    private SharedMySqlContainer() {}

    /**
     * flyway 계정으로 연결하는 {@link DataSource}를 반환한다(M1-T2 — 위반 마이그레이션 Flyway 인스턴스 구성과 {@code
     * flyway_schema_history} 정리에 사용).
     *
     * <p>{@link #FLYWAY_USERNAME}/{@link #FLYWAY_PASSWORD}는 컴파일타임 String 상수라 호출자 클래스 바이트코드에 인라인되므로,
     * 호출자가 직접 {@code DriverManager.getConnection(url, user, password)} 형태로 쓰면 SpotBugs
     * DMI_CONSTANT_DB_PASSWORD/HARD_CODE_PASSWORD가 호출자 쪽에서 재발한다. 자격증명 사용을 (사용자 승인 exclude가 적용된) 이
     * 클래스 내부로 한정하기 위해 DataSource 팩토리 형태로만 노출한다.
     *
     * <p>컨테이너 기동 이후에만 호출해야 한다({@link MySQLContainer#getJdbcUrl()}이 매핑 포트를 요구).
     *
     * @return flyway 계정 자격증명이 설정된 비풀링 DataSource
     */
    public static DataSource flywayDataSource() {
        return new DriverManagerDataSource(MYSQL.getJdbcUrl(), FLYWAY_USERNAME, FLYWAY_PASSWORD);
    }

    /**
     * 지정된 JDBC URL(공유 컨테이너·전용 컨테이너 어느 쪽이든 호출자가 실제로 연결된 컨테이너를 그대로 전달)에 root 계정으로 연결하는 {@link
     * DataSource}를 반환한다(M2-T2 — grant 순서 훅이 Tier-2 UPDATE GRANT를 적용할 때 사용).
     *
     * <p>{@link #flywayDataSource()}와 동일한 이유로 자격증명 사용을 이 클래스 내부로 한정하기 위해 DataSource 팩토리 형태로만
     * 노출한다(호출자가 {@code DriverManager.getConnection(url, "root", ...)}를 직접 쓰면 SpotBugs
     * DMI_CONSTANT_DB_PASSWORD가 호출자 쪽에서 재발한다).
     *
     * @param jdbcUrl root로 연결할 대상 컨테이너의 JDBC URL
     * @return root 계정 자격증명이 설정된 비풀링 DataSource
     */
    public static DataSource rootDataSourceFor(String jdbcUrl) {
        return new DriverManagerDataSource(jdbcUrl, ROOT_USERNAME, ROOT_PASSWORD);
    }

    /**
     * 지정된 JDBC URL에 collector 계정으로 연결하는 {@link DataSource}를 반환한다(M2-T4 — 앱 datasource를 collector
     * 계정으로 전환하는 {@code CollectorAccountDataSourceSwitcher}가 사용).
     *
     * <p>{@link #flywayDataSource()}/{@link #rootDataSourceFor(String)}와 동일한 이유로 자격증명 사용을 이 클래스 내부로
     * 한정하기 위해 DataSource 팩토리 형태로만 노출한다.
     *
     * @param jdbcUrl collector 계정으로 연결할 대상 컨테이너의 JDBC URL
     * @return collector 계정 자격증명이 설정된 비풀링 DataSource
     */
    public static DataSource collectorDataSourceFor(String jdbcUrl) {
        return new DriverManagerDataSource(jdbcUrl, COLLECTOR_USERNAME, COLLECTOR_PASSWORD);
    }

    /**
     * 이미 생성된 {@link HikariDataSource} 빈의 자격증명을 collector 계정으로 교체한다(M2-T4 — {@code
     * CollectorAccountDataSourceSwitcher}가 사용). 풀 인스턴스 자체(타입·최대풀크기 등 구성값)는 그대로 유지하고
     * username/password만 바꾼다 — {@code HikariPoolQueueingTest}처럼 앱 datasource를 {@code
     * HikariDataSource}로 캐스팅해 풀 설정값을 검증하는 기존 테스트가 깨지지 않도록, 완전히 새로운 {@link DataSource}로 치환하지 않는다.
     *
     * <p>HikariCP는 풀이 처음 사용되기 전(첫 {@code getConnection()} 호출 전)까지는 {@code setUsername}/{@code
     * setPassword}를 허용한다(그 이후에는 {@code IllegalStateException}로 봉인). 호출자는 이 메서드 호출 전에 해당 {@code
     * HikariDataSource}로 커넥션을 열지 않아야 한다.
     *
     * @param dataSource 자격증명을 교체할 대상 {@link HikariDataSource} 빈
     */
    public static void applyCollectorCredentials(HikariDataSource dataSource) {
        dataSource.setUsername(COLLECTOR_USERNAME);
        dataSource.setPassword(COLLECTOR_PASSWORD);
    }

    /**
     * flyway 계정으로 직접 연결하여 {@code SHOW GRANTS}(FOR 절 없음 — 접속 계정 자신의 권한) 결과를 반환한다. 자기 자신의 GRANT 조회는
     * 별도 권한 없이 MySQL이 허용한다(다른 계정 조회는 {@code mysql.*} 스키마 SELECT 권한이 필요).
     *
     * <p>계정 자격증명은 이 메서드 내부에만 존재한다 — 호출자에게 {@link #FLYWAY_PASSWORD} 컴파일타임 상수 값이 인라인되어 전파되지 않도록 의도적으로
     * 무인자(no-arg) 시그니처로 설계했다(AC-3 검증 테스트가 자격증명을 몰라도 되게 함).
     *
     * @return {@code SHOW GRANTS} 결과 행 목록
     * @throws SQLException 연결 또는 조회 실패 시
     */
    public static List<String> showFlywayGrants() throws SQLException {
        return showGrantsFor(FLYWAY_USERNAME, FLYWAY_PASSWORD);
    }

    /**
     * collector 계정으로 직접 연결하여 {@code SHOW GRANTS}(FOR 절 없음 — 접속 계정 자신의 권한) 결과를 반환한다(M2-T2 — grant 순서
     * 훅이 적용한 Tier-2 테이블 UPDATE GRANT 검증에 사용).
     *
     * @return {@code SHOW GRANTS} 결과 행 목록
     * @throws SQLException 연결 또는 조회 실패 시
     */
    public static List<String> showCollectorGrants() throws SQLException {
        return showGrantsFor(COLLECTOR_USERNAME, COLLECTOR_PASSWORD);
    }

    /**
     * 지정된 테스트 계정으로 직접 연결하여 {@code SHOW GRANTS}(FOR 절 없음 — 접속 계정 자신의 권한) 결과를 반환한다.
     *
     * <p>username/password는 이 클래스가 미러 init 스크립트({@code 01-init-accounts-mirror.sql})와 함께 소유하는 테스트
     * 전용 고정 상수({@link #FLYWAY_USERNAME}/{@link #FLYWAY_PASSWORD} 등)이며, 프로덕션 시크릿이 아니다 — 이 메서드가 관리하는
     * MySQL 컨테이너는 테스트 실행 동안만 존재하는 일회용 인스턴스다. 클래스 외부로 자격증명이 새어나가지 않도록 private으로 제한한다.
     *
     * @param username 조회 대상이자 접속 계정
     * @param password 접속 계정의 비밀번호
     * @return {@code SHOW GRANTS} 결과 행 목록
     * @throws SQLException 연결 또는 조회 실패 시
     */
    private static List<String> showGrantsFor(String username, String password)
            throws SQLException {
        List<String> grants = new ArrayList<>();
        try (Connection connection =
                        DriverManager.getConnection(MYSQL.getJdbcUrl(), username, password);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SHOW GRANTS")) {
            while (resultSet.next()) {
                grants.add(resultSet.getString(1));
            }
        }
        return grants;
    }
}
