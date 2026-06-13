package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("WindowCoverageChecker 단위 테스트 (REQ-BATCH2-025 경계 커버리지 WARN)")
class WindowCoverageCheckerTest {

    private static final LocalDate WINDOW_START = LocalDate.of(2026, 5, 30); // today-14

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(WindowCoverageChecker.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private List<ILoggingEvent> warnLogs() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Nested
    @DisplayName("경계 판정 (순수 캘린더 날짜 비교)")
    class BoundaryDecision {

        @Test
        @DisplayName("최소 trade_date ≤ 윈도우 시작일 — WARN 미발생 (하단 커버, S3-4a)")
        void minOnOrBeforeStart_noWarn() {
            // 최소 2026-05-28 ≤ 시작일 2026-05-30 → 커버
            WindowCoverageChecker.check(
                    "investor",
                    "005930",
                    List.of(LocalDate.of(2026, 5, 28), LocalDate.of(2026, 6, 12)),
                    WINDOW_START);

            assertThat(warnLogs()).isEmpty();
        }

        @Test
        @DisplayName("최소 trade_date = 윈도우 시작일 — WARN 미발생 (경계 동등)")
        void minEqualsStart_noWarn() {
            WindowCoverageChecker.check(
                    "investor", "005930", List.of(LocalDate.of(2026, 5, 30)), WINDOW_START);

            assertThat(warnLogs()).isEmpty();
        }

        @Test
        @DisplayName("최소 trade_date > 윈도우 시작일 — WARN 1건 발생 (하단 미커버, S3-4b)")
        void minAfterStart_warnOnce() {
            // 최소 2026-06-02 > 시작일 2026-05-30 → 하단 미커버
            WindowCoverageChecker.check(
                    "investor",
                    "005930",
                    List.of(LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 12)),
                    WINDOW_START);

            List<ILoggingEvent> warns = warnLogs();
            assertThat(warns).hasSize(1);
            String msg = warns.getFirst().getFormattedMessage();
            assertThat(msg).contains("005930").contains("investor");
            assertThat(msg).contains("2026-05-30").contains("2026-06-02");
        }
    }

    @Nested
    @DisplayName("빈 응답은 경계 검사 대상 아님 (REQ-063 no-op, S3-4c)")
    class EmptyResponse {

        @Test
        @DisplayName("빈 trade_date 목록 — WARN 미발생")
        void empty_noWarn() {
            WindowCoverageChecker.check("investor", "005930", List.of(), WINDOW_START);

            assertThat(warnLogs()).isEmpty();
        }
    }
}
