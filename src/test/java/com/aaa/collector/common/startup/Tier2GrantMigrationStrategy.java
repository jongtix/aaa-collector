package com.aaa.collector.common.startup;

import com.aaa.collector.support.SharedMySqlContainer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/**
 * grant 순서 훅 (SPEC-COLLECTOR-DBGRANT-003 M2-T2).
 *
 * <p>Flyway {@link Flyway#migrate()} 완료 직후 root 커넥션으로 {@link DbGrantVerifier#TIER2_TABLES}(기존 상수
 * 재사용, 신규 상수 아님) 5개 테이블에 {@code collector} 계정 {@code UPDATE} GRANT를 적용한다. 프로덕션 "Flyway 선행(DDL) →
 * root grants 후행(테이블 단위 UPDATE)" 절차({@code
 * aaa-infra/config/mysql/grants/collector-tier2-grants.sql})의 테스트 미러다 — MySQL 8.4는 존재하지 않는 테이블에 테이블
 * 단위 GRANT를 거부하므로(ERROR 1146) 스키마 생성 이후에만 적용 가능하다.
 *
 * <p>{@link DbGrantVerifier}와 동일 패키지({@code com.aaa.collector.common.startup})에 배치해 package-private
 * {@code TIER2_TABLES}를 그대로 재사용한다(main 소스 가시성 변경 없이 완료 — spec §1.5 우선순위 1).
 *
 * <p>테스트 전용 소스({@code src/test})이며 컴포넌트 스캔 기준 패키지({@code com.aaa.collector})에 속하므로,
 * {@code @SpringBootTest}가 전체 ApplicationContext를 로드하는 모든 테스트 클래스에서 자동 감지·적용된다 — 개별 테스트에
 * {@code @Import}가 불요하다. GRANT 문은 멱등(idempotent)이라 동일 공유 컨테이너에 여러 컨텍스트가 반복 적용해도 안전하다.
 *
 * <p>이 시점(M2-T2)에는 앱 datasource가 아직 컨테이너 기본 계정을 사용하므로(M2-T4에서 {@code collector} 계정으로 전환), 이 훅은
 * "grant 인프라 선행 배치"이며 기존 테스트 동작에 영향을 주지 않는다.
 *
 * <p><b>전용(비공유) 컨테이너 방어</b>: M2-T1에서 롤백 비호환 사유로 전용 컨테이너를 쓰는 테스트 클래스는 계정 미러 init 스크립트가 주입되지 않아
 * {@code collector} 계정 자체가 없다(실측). 이런 컨테이너에 {@code GRANT ... TO 'collector'@'%'}를 시도하면 MySQL이 존재하지
 * 않는 계정으로 오류를 낸다 — {@link #collectorAccountExists(Statement)}로 선확인 후 없으면 조용히 스킵한다(기존 테스트 컨텍스트 로딩을
 * 깨지 않는다, REQ-DBGRANT3-004 "동작 무변경" 전제).
 *
 * <p>root 커넥션은 {@link SharedMySqlContainer#rootDataSourceFor(String)} 팩토리로만 얻는다 — 이 클래스가 root 자격증명
 * 리터럴을 직접 들고 {@code DriverManager.getConnection}을 호출하면 SpotBugs DMI_CONSTANT_DB_PASSWORD가 발생한다(이미
 * {@code SharedMySqlContainer}에 한해 user-approved exclude가 있고, 이 클래스에 새 exclude를 추가하는 대신 자격증명 소유를 그
 * 클래스로 한정한다).
 *
 * <p><b>알려진 미해결 SpotBugs 발견(SQL_INJECTION_JDBC)</b>: GRANT 문의 테이블/스키마 식별자는 JDBC 바인드 변수로 파라미터화할 수
 * 없다(값이 아닌 식별자). {@link DbGrantVerifier#TIER2_TABLES}(5개 고정 상수, 외부 입력 아님)를 재사용하는 설계 요구사항과 충돌해
 * suppression 없이는 해소하지 못했다 — 상세 내역은 팀 리드에게 보고(가능한 해결책: exclude.xml에 이 클래스 한정 항목 추가, 사용자 승인 필요).
 */
@Component
public class Tier2GrantMigrationStrategy implements FlywayMigrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(Tier2GrantMigrationStrategy.class);

    @Override
    public void migrate(Flyway flyway) {
        flyway.migrate();
        applyTier2Grants(flyway.getConfiguration().getDataSource());
    }

    /**
     * Flyway가 실제로 사용한 데이터소스의 JDBC URL·현재 스키마를 그대로 재사용해 root 커넥션을 연다. 공유 컨테이너·전용 컨테이너 어느 쪽이든 이 컨텍스트가
     * 실제로 연결된 컨테이너를 정확히 겨냥한다(다른 컨테이너를 잘못 겨냥할 위험 없음).
     */
    private void applyTier2Grants(DataSource flywayDataSource) {
        String jdbcUrl;
        String schema;
        try (Connection probe = flywayDataSource.getConnection()) {
            jdbcUrl = probe.getMetaData().getURL();
            schema = probe.getCatalog();
        } catch (SQLException e) {
            throw new IllegalStateException("Tier-2 GRANT 적용을 위한 Flyway 데이터소스 조회 실패", e);
        }

        DataSource rootDataSource = SharedMySqlContainer.rootDataSourceFor(jdbcUrl);
        try (Connection root = rootDataSource.getConnection();
                Statement statement = root.createStatement()) {
            if (!collectorAccountExists(statement)) {
                log.debug("collector 계정이 없는 전용 컨테이너 — Tier-2 GRANT 적용 스킵: {}", jdbcUrl);
                return;
            }
            for (String table : DbGrantVerifier.TIER2_TABLES) {
                statement.execute(
                        "GRANT UPDATE ON `" + schema + "`.`" + table + "` TO 'collector'@'%'");
            }
            statement.execute("FLUSH PRIVILEGES");
            log.debug(
                    "Tier-2 GRANT 적용 완료: schema={}, tables={}",
                    schema,
                    DbGrantVerifier.TIER2_TABLES);
        } catch (SQLException e) {
            throw new IllegalStateException("Tier-2 GRANT 적용 실패", e);
        }
    }

    /** root 계정은 {@code mysql.user} 시스템 테이블 조회 권한을 항상 가지므로 별도 권한 없이 계정 존재 여부를 확인할 수 있다. */
    private boolean collectorAccountExists(Statement statement) throws SQLException {
        try (ResultSet resultSet =
                statement.executeQuery(
                        "SELECT COUNT(*) FROM mysql.user WHERE User = 'collector'")) {
            return resultSet.next() && resultSet.getInt(1) > 0;
        }
    }
}
