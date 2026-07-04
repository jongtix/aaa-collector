package com.aaa.collector.support;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 앱 datasource collector 계정 전환 훅 (SPEC-COLLECTOR-DBGRANT-003 M2-T4).
 *
 * <p>{@code @ServiceConnection}은 컨테이너 기본 계정({@code test}/{@code test}, 사실상 전체 권한)만 주입하므로, 제한 권한
 * {@code collector} 계정을 재현하려면 앱 {@link DataSource} 빈의 자격증명만 교체해야 한다. {@code
 * MySQLContainer.withUsername("collector")}는 MySQL 공식 이미지 entrypoint가 지정된 {@code MYSQL_USER}에게 자동으로
 * DB 전체 권한(ALL PRIVILEGES)을 부여하므로 부적합하다(spec §1.5 D5 명시) — 이후 미러 init 스크립트의 제한 GRANT는 이 선행 권한을 축소하지
 * 못하고 누적될 뿐이다.
 *
 * <p>이 {@link BeanPostProcessor}는 초기화가 끝난 {@link HikariDataSource} 빈에 대해서만 개입한다(완전히 새로운 {@link
 * DataSource}로 치환하지 않고 자격증명만 교체 — {@code HikariPoolQueueingTest}처럼 앱 datasource를 {@code
 * HikariDataSource}로 캐스팅해 풀 설정값(maximum-pool-size 등)을 검증하는 기존 테스트가 깨지지 않도록 타입·풀 구성을 보존한다):
 *
 * <ul>
 *   <li>collector 계정 접속 가능(계정 미러 init 스크립트가 주입된 컨테이너 — 현재는 {@link SharedMySqlContainer#MYSQL} 공유
 *       컨테이너만 해당) — 같은 빈의 username/password를 collector 계정으로 교체한다.
 *   <li>접속 불가(계정 미러가 없는 전용 컨테이너 — M2-T1에서 롤백 비호환 사유로 분리된 파일 등) — 빈을 그대로 둔다(동작 무변경).
 * </ul>
 *
 * <p>이 방식은 "어느 컨테이너를 쓰는 테스트 클래스인지" 컴파일타임에 구분할 필요가 없다 — 실제 접속 가능 여부로 런타임에 판정하므로 개별 테스트 클래스를 수정하지 않고도
 * 공유 컨테이너 소비자 전체에 자동 적용된다(REQ-DBGRANT3-003).
 *
 * <p>접속 가능 여부 프로브는 대상 {@code HikariDataSource} 자체가 아니라 {@link
 * SharedMySqlContainer#collectorDataSourceFor(String)}이 반환하는 별도의 일회성 {@link DataSource}로 수행한다 —
 * HikariCP는 풀이 처음 사용되기 전까지만 자격증명 변경을 허용하므로(그 이후 {@code IllegalStateException}), 대상 빈으로 직접 {@code
 * getConnection()}을 호출해 풀을 조기에 봉인시키지 않는다. URL도 {@code getConnection()} 대신 {@link
 * HikariDataSource#getJdbcUrl()} getter로 얻는다(풀을 건드리지 않음).
 *
 * <p>Flyway 마이그레이션은 이 스위처의 영향을 받지 않는다 — {@code FlywayAutoConfiguration}이 자체적으로 별도 데이터소스를 구성하기
 * 때문이다({@code Tier2GrantMigrationStrategy}가 사용하는 {@code flyway.getConfiguration().getDataSource()}도
 * 이 앱 {@code DataSource} 빈과 무관한 별개 인스턴스). Hibernate {@code ddl-auto=validate}·JPA 리포지토리는 이 자격증명 교체
 * 이후에 처음 커넥션을 열므로 collector 계정으로 정상 동작한다.
 */
@Component
public class CollectorAccountDataSourceSwitcher implements BeanPostProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(CollectorAccountDataSourceSwitcher.class);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof HikariDataSource hikariDataSource)) {
            return bean;
        }

        String jdbcUrl = hikariDataSource.getJdbcUrl();
        if (jdbcUrl == null || !isCollectorAccountConnectable(jdbcUrl)) {
            return bean;
        }

        log.debug("앱 datasource({}) collector 계정으로 전환: {}", beanName, jdbcUrl);
        SharedMySqlContainer.applyCollectorCredentials(hikariDataSource);
        return hikariDataSource;
    }

    private boolean isCollectorAccountConnectable(String jdbcUrl) {
        DataSource probe = SharedMySqlContainer.collectorDataSourceFor(jdbcUrl);
        try (Connection connection = probe.getConnection()) {
            return connection.isValid(1);
        } catch (SQLException e) {
            return false;
        }
    }
}
