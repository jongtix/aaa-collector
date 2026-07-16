package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.support.SharedMySqlContainer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SPEC-COLLECTOR-DBPOOL-001 — HikariPool 풀 적정화 + 큐잉 흡수 검증.
 *
 * <p>두 각도로 검증한다. (1) {@code ConfiguredPool}: 실제 application.yml로 구성된 HikariDataSource의
 * 설정값(maximum-pool-size/minimum-idle/connection-timeout)이 SPEC 목표값과 일치하는지 — T1 설정 변경의 RED→GREEN
 * 증거(AC-1, AC-4). (2) {@code QueuingBehavior}: 풀 크기를 초과하는 동시 DB 작업을 Virtual Threads로 던졌을 때 초과분이
 * 타임아웃 없이 큐잉 후 전부 성공하는지(AC-2, AC-3). 큐잉 경로를 결정적으로 강제하기 위해 프로덕션 값이 아닌 테스트 전용 작은 풀을 같은 Testcontainers
 * MySQL에 별도로 연결한다 (plan.md "결정적 테스트 재현"). H2는 네이티브 풀 동작을 재현하지 못하므로 mysql:8.4 사용(REQ-DBPOOL-020).
 *
 * <p>SPEC-COLLECTOR-DBGRANT-003 M2-T1 격리 분류: {@code QueuingBehavior}가 전용 {@code
 * pool_queueing_probe} 테이블을 매 테스트 {@code DROP}+{@code CREATE}로 자체 격리하므로(공유 컨테이너 전환과 무관하게 이미 자기 완결적
 * 정리), 클래스 레벨 {@code @Transactional} 부착 대상에서 제외한다. 이 클래스는 stocks 등 공유 비즈니스 테이블을 건드리지 않는다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("HikariPool 풀 적정화 + 큐잉 흡수 검증 (SPEC-COLLECTOR-DBPOOL-001)")
@Tag("integration")
class HikariPoolQueueingTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;

    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;

    @Nested
    @DisplayName("구성된 풀 설정값 (AC-1, AC-4 / REQ-DBPOOL-001~003, -013)")
    class ConfiguredPool {

        // @ServiceConnection은 url/username/password만 주입하며 hikari.* 값은 application.yml이 결정한다.
        // 따라서 이 DataSource는 application.yml의 풀 설정을 그대로 반영한다.
        @Autowired private DataSource dataSource;

        @Test
        @DisplayName("maximum-pool-size = 10 (REQ-DBPOOL-001)")
        void maximumPoolSizeIs10() {
            assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("minimum-idle = 4 (REQ-DBPOOL-002)")
        void minimumIdleIs4() {
            assertThat(((HikariDataSource) dataSource).getMinimumIdle()).isEqualTo(4);
        }

        @Test
        @DisplayName("connection-timeout = 20000 (REQ-DBPOOL-003, -013: 무한 아님·합리적 상한)")
        void connectionTimeoutIs20000() {
            long timeout = ((HikariDataSource) dataSource).getConnectionTimeout();
            assertThat(timeout).isEqualTo(20_000L);
            assertThat(timeout).isPositive(); // 0(무한) 금지 — 장애 은폐 방지
        }
    }

    @Nested
    @DisplayName("풀 고갈 시 큐잉 동작 (AC-2, AC-3 / REQ-DBPOOL-010~012, -021)")
    class QueuingBehavior {

        private static final String CREATE_TABLE =
                "CREATE TABLE IF NOT EXISTS pool_queueing_probe ("
                        + "id BIGINT PRIMARY KEY AUTO_INCREMENT, task_no INT NOT NULL)";

        private HikariDataSource smallPool(int poolSize, long connectionTimeoutMs) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(MYSQL.getJdbcUrl());
            config.setUsername(MYSQL.getUsername());
            config.setPassword(MYSQL.getPassword());
            config.setMaximumPoolSize(poolSize);
            config.setConnectionTimeout(connectionTimeoutMs);
            config.setPoolName("test-queueing-pool-" + poolSize);
            return new HikariDataSource(config);
        }

        private void createProbeTable(DataSource ds) throws SQLException {
            try (Connection conn = ds.getConnection();
                    Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS pool_queueing_probe");
                stmt.execute(CREATE_TABLE);
            }
        }

        private long countProbeRows(DataSource ds) throws SQLException {
            try (Connection conn = ds.getConnection();
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pool_queueing_probe")) {
                if (!rs.next()) {
                    throw new SQLException("COUNT(*) returned no row");
                }
                return rs.getLong(1);
            }
        }

        // @MX:NOTE: [AUTO] 큐잉 경로 결정적 재현 — pool=2 < requests=12 이므로 10개 작업이
        // 슬롯을 대기(큐잉)해야 한다. 각 작업이 커넥션을 잠시 점유(barrier로 동시 진입 보장)한 뒤
        // INSERT하여, 초과 요청이 타임아웃 없이 큐잉 후 전부 성공함과 저장 누락 0건을 검증한다.
        @Test
        @DisplayName("풀(2)을 초과하는 동시 요청(12)이 타임아웃 없이 큐잉 후 전부 성공·전부 저장")
        void exceedingRequestsQueueWithoutTimeoutAndAllPersist() throws Exception {
            // Arrange: pool=2, timeout=15s(큐잉 흡수 충분). requests=12 ≫ pool=2 → 큐잉 강제.
            int poolSize = 2;
            int requestCount = 12;
            try (HikariDataSource pool = smallPool(poolSize, 15_000L)) {
                createProbeTable(pool);

                AtomicInteger successCount = new AtomicInteger();
                Queue<Exception> failures = new ConcurrentLinkedQueue<>();
                // 모든 작업이 동시에 커넥션을 요청하도록 출발선을 맞춘다(풀 고갈 보장).
                CountDownLatch startLine = new CountDownLatch(1);

                // Act
                try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<Future<?>> futures = new ArrayList<>();
                    for (int i = 0; i < requestCount; i++) {
                        final int taskNo = i;
                        futures.add(
                                vthreads.submit(
                                        () -> {
                                            try {
                                                startLine.await();
                                                try (Connection conn = pool.getConnection()) {
                                                    // 슬롯 점유 시간 — 후속 요청이 큐잉되도록 강제
                                                    Thread.sleep(50);
                                                    try (PreparedStatement ps =
                                                            conn.prepareStatement(
                                                                    "INSERT INTO pool_queueing_probe"
                                                                            + " (task_no) VALUES (?)")) {
                                                        ps.setInt(1, taskNo);
                                                        ps.executeUpdate();
                                                    }
                                                }
                                                successCount.incrementAndGet();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                failures.add(e);
                                            } catch (SQLException e) {
                                                failures.add(e);
                                            }
                                            return null;
                                        }));
                    }
                    startLine.countDown();
                    for (Future<?> f : futures) {
                        f.get();
                    }
                }

                // Assert: 풀 고갈 기인 실패 0건, 성공 == 요청, 저장 행 수 == 시도 수
                assertThat(failures).isEmpty();
                assertThat(successCount.get()).isEqualTo(requestCount);
                assertThat(countProbeRows(pool)).isEqualTo(requestCount);
            }
        }

        // @MX:NOTE: [AUTO] EC-1 경계 — timeout 자체를 제거하는 게 아니라 큐잉 여유를 주는 것임을 구분.
        // pool=1을 한 스레드가 장시간 점유한 채 짧은 timeout(500ms)으로 두 번째 요청을 보내면,
        // 큐잉 한도를 넘긴 요청은 SQLTransientConnectionException으로 가시화되어야 한다(REQ-DBPOOL-013).
        @Test
        @DisplayName("풀이 timeout을 넘겨 가득 차면 타임아웃 예외가 가시화된다 (EC-1, REQ-DBPOOL-013)")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void timeoutSurfacesWhenPoolStaysFullBeyondTimeout() throws Exception {
            try (HikariDataSource pool = smallPool(1, 500L)) {
                createProbeTable(pool);
                CountDownLatch holding = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);

                try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
                    Future<?> holder =
                            vthreads.submit(
                                    () -> {
                                        try (Connection conn = pool.getConnection()) {
                                            assertThat(conn.isValid(1)).isTrue();
                                            holding.countDown();
                                            release.await();
                                        }
                                        return null;
                                    });
                    holding.await(); // 단일 슬롯 점유 확정

                    try {
                        // 두 번째 요청은 500ms 내 슬롯 확보 불가 → 타임아웃 예외
                        assertThatThrownBy(
                                        () -> {
                                            try (Connection second = pool.getConnection()) {
                                                assertThat(second).isNull(); // 도달하면 안 됨
                                            }
                                        })
                                .isInstanceOf(SQLTransientConnectionException.class);
                    } finally {
                        // [W1] 단언이 실패(회귀: 타임아웃 미발생)해도 holder를 반드시 깨워야
                        // ExecutorService close()의 awaitTermination이 무한 대기(hang)하지 않고
                        // fail-fast로 단언 실패가 보고된다.
                        release.countDown();
                    }
                    holder.get(); // 정상 경로: holder 완료 대기·예외 전파
                }
            }
        }
    }
}
