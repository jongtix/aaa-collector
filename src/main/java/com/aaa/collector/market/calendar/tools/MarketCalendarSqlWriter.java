package com.aaa.collector.market.calendar.tools;

import com.aaa.collector.market.calendar.CalendarCode;
import com.aaa.collector.market.calendar.CalendarSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * 확정된 캘린더 행을 {@code INSERT INTO market_calendar} SQL 파일로 출력한다(SPEC-COLLECTOR-CALENDAR-001
 * REQ-CAL-048, TASK-012) — 테이블이 시딩 시점에 비어 있다고 가정하는 순수 INSERT 문이다({@code ON DUPLICATE KEY UPDATE}
 * 불필요). {@link MarketCalendarSeedService}에서 위임받는 단일 책임 협력자(PMD CouplingBetweenObjects 완화 목적 분리).
 */
final class MarketCalendarSqlWriter {

    private MarketCalendarSqlWriter() {}

    /**
     * @param outputPath 산출 SQL 파일 절대경로(레포 밖, REQ-CAL-048)
     * @param rows 출력할 확정 행 목록
     */
    static void write(Path outputPath, Iterable<Row> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            for (Row row : rows) {
                writer.write(
                        "INSERT INTO market_calendar (calendar_code, cal_date, is_open, source) VALUES ('"
                                + row.calendarCode().name()
                                + "', '"
                                + row.calDate()
                                + "', "
                                + (row.isOpen() ? 1 : 0)
                                + ", '"
                                + row.source().name()
                                + "');");
                writer.newLine();
            }
        }
    }

    /** SQL INSERT 1행에 대응하는 최종 확정 값. */
    record Row(
            CalendarCode calendarCode, LocalDate calDate, boolean isOpen, CalendarSource source) {}
}
