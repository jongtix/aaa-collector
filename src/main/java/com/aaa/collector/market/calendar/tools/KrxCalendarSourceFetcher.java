package com.aaa.collector.market.calendar.tools;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.holiday.KisHolidayClient;
import com.aaa.collector.kis.holiday.KisHolidayResponse.HolidayRow;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;

/**
 * KRX 휴장일 원본 소스 값을 {@link KisHolidayClient} 순차 체이닝으로 조회한다(SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-013과
 * 동일한 체이닝 방식, D3 — 커서 페이징 미구현이므로 독립 재호출로 동일 효과를 얻는다, TASK-012). {@link MarketCalendarSeedService}에서
 * 위임받는 단일 책임 협력자(PMD CouplingBetweenObjects 완화 목적 분리).
 */
@Slf4j
@RequiredArgsConstructor
class KrxCalendarSourceFetcher {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** KRX 순차 체이닝 최대 호출 횟수 tripwire(예상 ~625콜의 넉넉한 상한, 무한루프 방지). */
    static final int MAX_KRX_CALLS = 1000;

    private final KisHolidayClient kisHolidayClient;

    /**
     * {@code seedStart}부터 {@code seedEnd}까지 KRX 원본 소스 값(KIS {@code opnd_yn})을 조회한다.
     *
     * @param seedStart 조회 하한(포함)
     * @param seedEnd 조회 상한(포함)
     * @return 날짜 → 개장 여부 맵(응답 실패 지점까지만 채워짐)
     */
    Map<LocalDate, Boolean> fetch(LocalDate seedStart, LocalDate seedEnd) {
        Map<LocalDate, Boolean> source = new LinkedHashMap<>();
        LocalDate baseDate = seedStart;
        int calls = 0;
        while (!baseDate.isAfter(seedEnd) && calls < MAX_KRX_CALLS) {
            Optional<List<HolidayRow>> rows = fetchOneCall(baseDate);
            if (rows.isEmpty()) {
                break;
            }
            calls++;
            baseDate = applyRows(source, baseDate, rows.get());
        }
        log.info("[calendar-seed] KRX 조회 완료 — calls={}, dates={}", calls, source.size());
        return source;
    }

    /** 단일 KRX 조회를 수행한다. 실패 시 {@link Optional#empty()}(호출자가 체이닝을 중단). */
    private Optional<List<HolidayRow>> fetchOneCall(LocalDate baseDate) {
        try {
            return Optional.of(kisHolidayClient.fetchCalendar(baseDate));
        } catch (RestClientException
                | KisApiBusinessException
                | KisRateLimitException
                | IllegalStateException ex) {
            log.warn(
                    "[calendar-seed] KRX 조회 실패 — baseDate={}, exceptionType={}, 이 지점에서 중단",
                    baseDate,
                    ex.getClass().getSimpleName(),
                    ex);
            return Optional.empty();
        }
    }

    /** 한 회차의 KRX 응답을 {@code source}에 반영하고 다음 체이닝 기준일을 반환한다. */
    private LocalDate applyRows(
            Map<LocalDate, Boolean> source, LocalDate baseDate, List<HolidayRow> rows) {
        if (rows.isEmpty()) {
            return baseDate.plusDays(1); // 무한루프 방지 안전장치
        }
        LocalDate maxDate = baseDate;
        for (HolidayRow row : rows) {
            LocalDate date;
            try {
                date = LocalDate.parse(row.bassDt(), DATE_FORMAT);
            } catch (DateTimeParseException e) {
                continue;
            }
            source.put(date, "Y".equals(row.opndYn()));
            if (date.isAfter(maxDate)) {
                maxDate = date;
            }
        }
        return maxDate.isAfter(baseDate) ? maxDate.plusDays(1) : baseDate.plusDays(1);
    }
}
