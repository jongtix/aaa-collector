package com.aaa.collector.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
 * 소유하고, 이를 사용하는 테스트 클래스는 {@code @Container @ServiceConnection} 필드를 자신의 클래스에서 {@code
 * SharedMySqlContainer.MYSQL}을 참조해 재선언한다(Testcontainers/Spring의 리플렉션 스캔은 테스트 클래스 자신의 필드 계층만 인식하므로
 * 재선언이 필요하다). 동일 인스턴스를 참조하므로 다수의 테스트 클래스가 이 필드를 재선언해도 컨테이너는 1개만 기동된다(M2가 채택할 확장 지점).
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
     * 계정 미러 init 스크립트가 주입된 공유 MySQL 컨테이너. 이 컨테이너를 사용하려는 테스트 클래스는 자신의
     * {@code @Container @ServiceConnection} 정적 필드를 이 상수로 초기화해 재선언한다.
     */
    public static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource(ACCOUNTS_MIRROR_INIT_SCRIPT),
                            "/docker-entrypoint-initdb.d/01-init-accounts-mirror.sql");

    private SharedMySqlContainer() {}

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
