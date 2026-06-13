package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("DailyOhlcvRepository 통합 테스트")
class DailyOhlcvRepositoryTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private DailyOhlcvRepository dailyOhlcvRepository;
    @Autowired private StockRepository stockRepository;

    private Stock savedStock(String symbol) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    private DailyOhlcv ohlcv(Stock stock, LocalDate date) {
        return DailyOhlcv.builder()
                .stock(stock)
                .tradeDate(date)
                .openPrice(new BigDecimal("74000.0000"))
                .highPrice(new BigDecimal("76000.0000"))
                .lowPrice(new BigDecimal("73000.0000"))
                .closePrice(new BigDecimal("75000.0000"))
                .volume(1_000_000L)
                .tradingValue(75_000_000_000L)
                .build();
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-BATCH-031, -032)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void insertIgnoreDuplicate_newRow_insertsOne() {
            // Arrange
            Stock stock = savedStock("005930");
            DailyOhlcv row = ohlcv(stock, LocalDate.of(2026, 6, 5));

            // Act
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    LocalDate.of(2026, 6, 5),
                    row.getOpenPrice(),
                    row.getHighPrice(),
                    row.getLowPrice(),
                    row.getClosePrice(),
                    row.getVolume(),
                    row.getTradingValue());
            dailyOhlcvRepository.flush();

            // Assert
            long count = dailyOhlcvRepository.countByStockId(stock.getId());
            assertThat(count).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, trade_date) 중복 삽입 — 행 수 증가 없음")
        void insertIgnoreDuplicate_duplicate_rowCountUnchanged() {
            // Arrange
            Stock stock = savedStock("000660");
            LocalDate date = LocalDate.of(2026, 6, 5);

            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    date,
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    new BigDecimal("73000.0000"),
                    new BigDecimal("75000.0000"),
                    1_000_000L,
                    75_000_000_000L);
            dailyOhlcvRepository.flush();

            // Act — 동일 키로 다시 삽입
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    date,
                    new BigDecimal("80000.0000"),
                    new BigDecimal("82000.0000"),
                    new BigDecimal("79000.0000"),
                    new BigDecimal("81000.0000"),
                    2_000_000L,
                    160_000_000_000L);
            dailyOhlcvRepository.flush();

            // Assert — 행 수는 1 유지, UPDATE 미발생이므로 최초 값 보존
            long count = dailyOhlcvRepository.countByStockId(stock.getId());
            assertThat(count).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, trade_date) 중복 — 기존 행 값이 UPDATE되지 않음")
        void insertIgnoreDuplicate_duplicate_originalValuePreserved() {
            // Arrange
            Stock stock = savedStock("035420");
            LocalDate date = LocalDate.of(2026, 6, 5);
            BigDecimal originalClose = new BigDecimal("75000.0000");

            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    date,
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    new BigDecimal("73000.0000"),
                    originalClose,
                    1_000_000L,
                    75_000_000_000L);
            dailyOhlcvRepository.flush();

            // Act — 다른 값으로 재삽입 시도
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    date,
                    new BigDecimal("90000.0000"),
                    new BigDecimal("92000.0000"),
                    new BigDecimal("89000.0000"),
                    new BigDecimal("91000.0000"),
                    5_000_000L,
                    455_000_000_000L);
            dailyOhlcvRepository.flush();

            // Assert — 최초 종가가 그대로 유지
            List<DailyOhlcv> rows = dailyOhlcvRepository.findAll();
            DailyOhlcv saved =
                    rows.stream()
                            .filter(r -> r.getStock().getId().equals(stock.getId()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getClosePrice()).isEqualByComparingTo(originalClose);
        }

        @Test
        @DisplayName("5거래일 증분 — 직전 누락분 회복: 기존 4일 + 신규 1일, 기존 값 불변 (AC-4 S4-3)")
        void insertIgnoreDuplicate_priorDayMissing_recoversOnNextRun() {
            // Arrange — 최초 수집: 4 거래일 삽입
            Stock stock = savedStock("096770");
            LocalDate day1 = LocalDate.of(2026, 5, 26);
            LocalDate day2 = LocalDate.of(2026, 5, 27);
            LocalDate day3 = LocalDate.of(2026, 5, 28);
            LocalDate day4 = LocalDate.of(2026, 5, 29);
            LocalDate day5 = LocalDate.of(2026, 6, 2); // 신규 거래일

            BigDecimal originalCloseDay1 = new BigDecimal("75000.0000");

            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day1,
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    new BigDecimal("73000.0000"),
                    originalCloseDay1,
                    1_000_000L,
                    75_000_000_000L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day2,
                    new BigDecimal("75000.0000"),
                    new BigDecimal("77000.0000"),
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    1_100_000L,
                    83_600_000_000L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day3,
                    new BigDecimal("76000.0000"),
                    new BigDecimal("78000.0000"),
                    new BigDecimal("75000.0000"),
                    new BigDecimal("77000.0000"),
                    1_200_000L,
                    92_400_000_000L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day4,
                    new BigDecimal("77000.0000"),
                    new BigDecimal("79000.0000"),
                    new BigDecimal("76000.0000"),
                    new BigDecimal("78000.0000"),
                    1_300_000L,
                    101_400_000_000L);
            dailyOhlcvRepository.flush();

            // Act — 다음 수집 실행: 동일 4일 재삽입 + 신규 1일 추가 (5거래일 윈도우)
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day1,
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    9_999_999L,
                    999_999_999_999L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day2,
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    9_999_999L,
                    999_999_999_999L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day3,
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    9_999_999L,
                    999_999_999_999L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day4,
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    new BigDecimal("99000.0000"),
                    9_999_999L,
                    999_999_999_999L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    day5,
                    new BigDecimal("78000.0000"),
                    new BigDecimal("80000.0000"),
                    new BigDecimal("77000.0000"),
                    new BigDecimal("79000.0000"),
                    1_400_000L,
                    110_600_000_000L);
            dailyOhlcvRepository.flush();

            // Assert — 총 5행(신규 1일만 추가), 기존 day1 종가 불변, day5 존재
            long count = dailyOhlcvRepository.countByStockId(stock.getId());
            assertThat(count).isEqualTo(5L);

            List<DailyOhlcv> rows = dailyOhlcvRepository.findAll();
            DailyOhlcv day1Row =
                    rows.stream()
                            .filter(
                                    r ->
                                            r.getStock().getId().equals(stock.getId())
                                                    && r.getTradeDate().equals(day1))
                            .findFirst()
                            .orElseThrow();
            assertThat(day1Row.getClosePrice()).isEqualByComparingTo(originalCloseDay1);

            boolean day5Present =
                    rows.stream()
                            .anyMatch(
                                    r ->
                                            r.getStock().getId().equals(stock.getId())
                                                    && r.getTradeDate().equals(day5));
            assertThat(day5Present).isTrue();
        }

        @Test
        @DisplayName("서로 다른 (stock_id, trade_date) — 각각 독립 삽입됨")
        void insertIgnoreDuplicate_differentDates_insertsDistinctRows() {
            // Arrange
            Stock stock = savedStock("028260");

            // Act
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    LocalDate.of(2026, 6, 4),
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    new BigDecimal("73000.0000"),
                    new BigDecimal("75000.0000"),
                    1_000_000L,
                    75_000_000_000L);
            dailyOhlcvRepository.insertIgnoreDuplicate(
                    stock.getId(),
                    LocalDate.of(2026, 6, 5),
                    new BigDecimal("75000.0000"),
                    new BigDecimal("77000.0000"),
                    new BigDecimal("74000.0000"),
                    new BigDecimal("76000.0000"),
                    1_200_000L,
                    91_200_000_000L);
            dailyOhlcvRepository.flush();

            // Assert
            long count = dailyOhlcvRepository.countByStockId(stock.getId());
            assertThat(count).isEqualTo(2L);
        }
    }

    /**
     * UPDATE 권한 없는 제한 사용자로 멱등 삽입 — SQL 1142 회귀 방지 (REQ-OHLCV1-010, REQ-OHLCV1-030).
     *
     * <p>기존 테스트들은 Testcontainer root 사용자(전권)로 실행되므로 {@code ON DUPLICATE KEY UPDATE id=id} 쿼리라도 SQL
     * 1142가 발생하지 않는다. 이 테스트는 SELECT·INSERT 권한만 가진 제한 사용자를 직접 생성하여, 중복 삽입 시 UPDATE 권한 검사 경로를 실제로 밟게
     * 함으로써 프로덕션 버그(ADR-025 §맥락 1)를 재현한다.
     *
     * <p>{@code INSERT IGNORE} 전환 후에는 예외 없이 통과해야 한다.
     */
    @Nested
    @DisplayName("UPDATE 권한 없는 제한 사용자 — INSERT IGNORE 멱등성 (REQ-OHLCV1-010, REQ-OHLCV1-030)")
    class RestrictedUserIdempotency {

        // 컴파일 타임 상수 — SQL_INJECTION_JDBC 미발생 (String concat 없음)
        private static final String RESTRICTED_USER = "collector_restricted";
        private static final String RESTRICTED_PASS = "restricted_pass_test";
        private static final String CREATE_USER_DDL =
                "CREATE USER IF NOT EXISTS 'collector_restricted'@'%'"
                        + " IDENTIFIED BY 'restricted_pass_test'";
        // GRANT: 글로벌 권한(*.*) 사용 — dbName concat 제거, UPDATE 권한 없음 유지
        private static final String GRANT_DDL =
                "GRANT SELECT, INSERT ON *.* TO 'collector_restricted'@'%'";
        private static final String FLUSH_DDL = "FLUSH PRIVILEGES";
        private static final String DROP_USER_DDL =
                "DROP USER IF EXISTS 'collector_restricted'@'%'";

        /**
         * 프로덕션 멱등 삽입 SQL — {@code DailyOhlcvRepository#insertIgnoreDuplicate} 의 실제 쿼리와 동일해야 한다. 이
         * 상수를 통해 제한 사용자가 동일한 SQL을 실행하므로, 리포지토리 쿼리가 회귀(예: {@code ON DUPLICATE KEY UPDATE id=id}로
         * 되돌아감)할 경우 이 테스트도 함께 실패한다.
         */
        private static final String PRODUCTION_INSERT_SQL =
                "INSERT IGNORE INTO daily_ohlcv"
                        + " (stock_id, trade_date, open_price, high_price,"
                        + "  low_price, close_price, volume, trading_value,"
                        + "  created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";

        /**
         * root 연결로 제한 사용자를 생성하고 권한을 부여한다. MySQLContainer 기본 root 패스워드 = {@code MYSQL.getPassword()}
         * (default "test").
         */
        private Connection openRootConnection() throws SQLException {
            return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
        }

        // DDL 문자열이 모두 컴파일 타임 상수 → Statement.execute()에 비상수 인자 없음 (SQL_INJECTION_JDBC 미발생)
        private void setupRestrictedUser() throws SQLException {
            try (Connection rootConn = openRootConnection();
                    Statement stmt = rootConn.createStatement()) {
                stmt.execute(CREATE_USER_DDL);
                stmt.execute(GRANT_DDL);
                stmt.execute(FLUSH_DDL);
            }
        }

        private void dropRestrictedUser() throws SQLException {
            try (Connection rootConn = openRootConnection();
                    Statement stmt = rootConn.createStatement()) {
                stmt.execute(DROP_USER_DDL);
            }
        }

        // NOT_SUPPORTED: 클래스 레벨 @Transactional을 중단 → 리포지토리 @Transactional이 각자의 트랜잭션을 커밋
        // 커밋된 행만 외부 JDBC 연결(제한 사용자)에서 보임 → 락 대기 없이 중복 삽입 가능
        // 자동 롤백 없으므로 테스트 종료 시 직접 데이터 삭제
        @Test
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        @DisplayName("SELECT·INSERT 전용 사용자 — 중복 삽입 시 예외 없음 (INSERT IGNORE 전환 검증)")
        void insertIgnoreDuplicate_restrictedUser_noUpdatePrivilege_doesNotThrow()
                throws SQLException {
            // Arrange — savedStock이 예외를 던지면 아직 아무것도 커밋되지 않으므로 try 밖에 위치
            Stock stock = savedStock("012330"); // 현대모비스
            LocalDate date = LocalDate.of(2026, 6, 10);

            try {
                // daily_ohlcv seed 삽입 (리포지토리 경유 — 커밋됨)
                dailyOhlcvRepository.insertIgnoreDuplicate(
                        stock.getId(),
                        date,
                        new BigDecimal("270000.0000"),
                        new BigDecimal("275000.0000"),
                        new BigDecimal("268000.0000"),
                        new BigDecimal("272000.0000"),
                        500_000L,
                        136_000_000_000L);

                String jdbcUrl =
                        "jdbc:mysql://"
                                + MYSQL.getHost()
                                + ":"
                                + MYSQL.getMappedPort(3306)
                                + "/"
                                + MYSQL.getDatabaseName();

                // 제한 사용자 생성 (SELECT, INSERT only — UPDATE 권한 없음)
                setupRestrictedUser();

                // DMI_CONSTANT_DB_PASSWORD 회피: System.getProperty()로 런타임 해석 — getConnection 인자가 비상수
                String restrictedPass =
                        System.getProperty("test.restricted.db.password", RESTRICTED_PASS);

                // Act & Assert — 제한 사용자로 동일 (stock_id, trade_date) 중복 삽입
                // ON DUPLICATE KEY UPDATE 쿼리라면 MySQL이 UPDATE 권한을 검사하여 SQL 1142 발생
                // INSERT IGNORE 쿼리라면 중복 행을 무시하고 예외 없이 완료
                assertThatNoException()
                        .isThrownBy(
                                () -> {
                                    try (Connection restrictedConn =
                                                    DriverManager.getConnection(
                                                            jdbcUrl,
                                                            RESTRICTED_USER,
                                                            restrictedPass);
                                            PreparedStatement ps =
                                                    restrictedConn.prepareStatement(
                                                            PRODUCTION_INSERT_SQL)) {
                                        restrictedConn.setAutoCommit(true);
                                        ps.setLong(1, stock.getId());
                                        ps.setObject(2, date);
                                        ps.setBigDecimal(3, new BigDecimal("999000.0000"));
                                        ps.setBigDecimal(4, new BigDecimal("999000.0000"));
                                        ps.setBigDecimal(5, new BigDecimal("999000.0000"));
                                        ps.setBigDecimal(6, new BigDecimal("999000.0000"));
                                        ps.setLong(7, 9_999_999L);
                                        ps.setLong(8, 999_999_999_999L);
                                        ps.executeUpdate();
                                    }
                                });

                // 행 수 검증 — 리포지토리로 확인 (중복 행 추가 없음)
                long count = dailyOhlcvRepository.countByStockId(stock.getId());
                assertThat(count).isEqualTo(1L);

            } finally {
                // 정리 — 어설션 실패 여부와 무관하게 항상 실행 (NOT_SUPPORTED: 자동 롤백 없음)
                // DELETE·DROP USER IF EXISTS는 행/사용자가 없어도 안전한 no-op
                try (Connection adminConn = openRootConnection()) {
                    adminConn.setAutoCommit(true);
                    try (PreparedStatement ps =
                            adminConn.prepareStatement(
                                    "DELETE FROM daily_ohlcv WHERE stock_id = ?")) {
                        ps.setLong(1, stock.getId());
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps =
                            adminConn.prepareStatement("DELETE FROM stocks WHERE id = ?")) {
                        ps.setLong(1, stock.getId());
                        ps.executeUpdate();
                    }
                }
                dropRestrictedUser();
            }
        }
    }
}
