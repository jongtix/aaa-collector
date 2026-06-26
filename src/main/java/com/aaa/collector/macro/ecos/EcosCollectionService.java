package com.aaa.collector.macro.ecos;

import com.aaa.collector.common.config.InserterProperties;
import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.MacroIndicator;
import com.aaa.collector.macro.MacroIndicatorInserter;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.macro.enums.MacroSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 한국은행 ECOS 거시경제 지표 수집 서비스 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-001~020).
 *
 * <p>8개 시리즈를 순차 수집하고 INSERT IGNORE로 멱등 저장한다. 시리즈 단위 예외 격리 — 한 시리즈의 예외가 다음 시리즈 수집을 막지 않는다.
 *
 * <p>ECOS serviceKey는 URL 경로 세그먼트에 포함된다(쿼리 파라미터 아님, REQ-MACRO-EXT-003).
 *
 * <p>날짜 정규화 (REQ-MACRO-EXT-013~015):
 *
 * <ul>
 *   <li>D(YYYYMMDD) → 해당일
 *   <li>M(YYYYMM) → 해당 월 1일
 *   <li>Q(YYYYQN) → 분기 시작월 1일 (Q1=01-01, Q2=04-01, Q3=07-01, Q4=10-01)
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcosCollectionService {

    private static final DateTimeFormatter DAILY_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /** 당일 수집 윈도우 — 일별 최근 30일 */
    private static final int DAILY_WINDOW = 30;

    /** 당일 수집 윈도우 — 월별 최근 3개월 */
    private static final int MONTHLY_WINDOW = 3;

    /** 당일 수집 윈도우 — 분기별 최근 2분기 */
    private static final int QUARTERLY_WINDOW = 6;

    // @MX:ANCHOR: [AUTO] ECOS 수집 핵심 진입점 — 8개 시리즈 순차 처리
    // @MX:REASON: [AUTO] MacroExternalScheduler, MacroIndicatorBackfillOrchestrator 에서 호출됨
    // (fan_in=2)
    private final RestClient ecosRestClient;
    private final MacroIndicatorRepository macroIndicatorRepository;
    private final MacroIndicatorInserter macroIndicatorInserter;
    private final InserterProperties inserterProperties;

    @Value("${aaa.ecos.service-key:}")
    private String serviceKey;

    /**
     * 8개 ECOS 시리즈 당일 수집 실행.
     *
     * @return attempted/succeeded/skipped 집계
     */
    public MacroCollectionResult collect() {
        return collectInternal(false);
    }

    /**
     * 8개 ECOS 시리즈 전체 이력 백필.
     *
     * @return attempted/succeeded/skipped 집계
     */
    public MacroCollectionResult collectAll() {
        return collectInternal(true);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private MacroCollectionResult collectInternal(boolean backfill) {
        int attempted = 0;
        int succeeded = 0;
        int skipped = 0;

        for (EcosSeriesConfig.Series series : EcosSeriesConfig.ALL) {
            try {
                MacroCollectionResult result = collectSeries(series, backfill);
                attempted += result.attempted();
                succeeded += result.succeeded();
                skipped += result.skipped();
            } catch (Exception e) {
                log.error("[ecos] 시리즈 수집 예외 — series={}, 다음 시리즈 계속", series.indicatorCode(), e);
            }
        }

        log.info(
                "[ecos] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                attempted,
                succeeded,
                skipped);
        return new MacroCollectionResult(attempted, succeeded, skipped);
    }

    private MacroCollectionResult collectSeries(EcosSeriesConfig.Series series, boolean backfill) {
        String url = buildUrl(series, backfill);

        EcosStatisticSearchResponse response =
                ecosRestClient.get().uri(url).retrieve().body(EcosStatisticSearchResponse.class);

        // INFO-200 응답 (StatisticSearch 키 없음)
        if (response == null
                || response.statisticSearch() == null
                || response.statisticSearch().row() == null) {
            log.info("[ecos] INFO-200 응답 — series={}, 0건 처리", series.indicatorCode());
            return new MacroCollectionResult(0, 0, 0);
        }

        int attempted = 0;
        int skipped = 0;

        // REQ-INSERT-009: 유효 행 누적 후 청크 분할 배치 INSERT IGNORE (REQ-INSERT-010)
        List<MacroIndicator> batch = new ArrayList<>();
        for (EcosStatisticSearchResponse.Row row : response.statisticSearch().row()) {
            attempted++;
            MacroIndicator entity = buildIfValid(series, row);
            if (entity != null) {
                batch.add(entity);
            } else {
                skipped++;
            }
        }

        int chunkSize = inserterProperties.getChunkSize();
        for (int i = 0; i < batch.size(); i += chunkSize) {
            List<MacroIndicator> chunk = batch.subList(i, Math.min(i + chunkSize, batch.size()));
            macroIndicatorInserter.insertBatch(chunk);
        }

        return new MacroCollectionResult(attempted, batch.size(), skipped);
    }

    /**
     * 행을 검증·파싱하여 엔티티를 반환한다. skip 시 null 반환.
     *
     * <p>REQ-INSERT-009: 누적 배치 방식으로 전환 — 여기서는 엔티티만 빌드하고 저장하지 않는다.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 파싱 실패 graceful skip
    private MacroIndicator buildIfValid(
            EcosSeriesConfig.Series series, EcosStatisticSearchResponse.Row row) {
        String time = row.time();
        String rawValue = row.dataValue();

        if (time == null || time.isBlank() || rawValue == null || rawValue.isBlank()) {
            log.warn("[ecos] 빈 TIME/DATA_VALUE — series={}, time={}", series.indicatorCode(), time);
            return null;
        }

        try {
            LocalDate tradeDate = normalizeDate(series.period(), time);
            BigDecimal value = new BigDecimal(rawValue.replace(",", ""));

            return MacroIndicator.builder()
                    .indicatorCode(series.indicatorCode())
                    .source(MacroSource.ECOS)
                    .tradeDate(tradeDate)
                    .value(value)
                    .build();
        } catch (Exception e) {
            log.warn(
                    "[ecos] 파싱 실패 — series={}, time={}, value={}, error={}",
                    series.indicatorCode(),
                    time,
                    rawValue,
                    e.getMessage());
            return null;
        }
    }

    /**
     * ECOS TIME 필드를 주기 코드에 따라 LocalDate로 정규화한다 (REQ-MACRO-EXT-013~015).
     *
     * @param period 주기 코드: D/M/Q
     * @param time ECOS TIME 필드 값
     * @return 정규화된 LocalDate
     */
    static LocalDate normalizeDate(String period, String time) {
        return switch (period) {
            case "D" -> LocalDate.parse(time, DAILY_FMT);
            case "M" -> {
                int year = Integer.parseInt(time.substring(0, 4));
                int month = Integer.parseInt(time.substring(4, 6));
                yield LocalDate.of(year, month, 1);
            }
            case "Q" -> {
                int year = Integer.parseInt(time.substring(0, 4));
                int quarter = Integer.parseInt(time.substring(5));
                int startMonth = (quarter - 1) * 3 + 1;
                yield LocalDate.of(year, startMonth, 1);
            }
            default -> throw new IllegalArgumentException("지원하지 않는 주기 코드: " + period);
        };
    }

    private String buildUrl(EcosSeriesConfig.Series series, boolean backfill) {
        // ECOS URL 패턴:
        // /api/StatisticSearch/{serviceKey}/json/kr/{startNo}/{endNo}/{statCode}/{period}/{startDate}/{endDate}/{itemCode}
        LocalDate today = LocalDate.now();
        String startDate;
        String endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        int endNo;

        if (backfill) {
            // 전체 이력: 아주 오래된 날짜부터 현재까지
            startDate = "19000101";
            endNo = 99_999;
        } else {
            // 당일 수집 윈도우
            LocalDate from =
                    switch (series.period()) {
                        case "D" -> today.minusDays(DAILY_WINDOW);
                        case "M" -> today.minusMonths(MONTHLY_WINDOW).withDayOfMonth(1);
                        case "Q" -> today.minusMonths(QUARTERLY_WINDOW).withDayOfMonth(1);
                        default -> today.minusDays(DAILY_WINDOW);
                    };
            startDate = from.format(DateTimeFormatter.BASIC_ISO_DATE);
            endNo = 100;
        }

        return "/api/StatisticSearch/%s/json/kr/1/%d/%s/%s/%s/%s/%s"
                .formatted(
                        serviceKey,
                        endNo,
                        series.statCode(),
                        series.period(),
                        startDate,
                        endDate,
                        series.itemCode());
    }
}
