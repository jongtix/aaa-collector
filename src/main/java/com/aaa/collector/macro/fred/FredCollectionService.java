package com.aaa.collector.macro.fred;

import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.MacroIndicator;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.macro.enums.MacroSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * FRED(Federal Reserve Economic Data) 거시경제 지표 수집 서비스 (SPEC-COLLECTOR-MACRO-EXT-001
 * REQ-MACRO-EXT-021~040).
 *
 * <p>5개 시리즈를 순차 수집하고 INSERT IGNORE로 멱등 저장한다. 시리즈 단위 예외 격리.
 *
 * <p>value="." 행은 skip한다 (REQ-MACRO-EXT-032). DFF 주말 행은 그대로 저장한다 (REQ-MACRO-EXT-033).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FredCollectionService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 당일 수집 최근 N건 */
    private static final int DAILY_LIMIT = 30;

    // @MX:ANCHOR: [AUTO] FRED 수집 핵심 진입점 — 5개 시리즈 순차 처리
    // @MX:REASON: [AUTO] MacroExternalScheduler, MacroIndicatorBackfillOrchestrator 에서 호출됨
    // (fan_in=2)
    private final RestClient macroFredRestClient;
    private final MacroIndicatorRepository macroIndicatorRepository;

    @Value("${aaa.fred.api-key:}")
    private String apiKey;

    /**
     * 5개 FRED 시리즈 당일 수집 실행 (최근 30건).
     *
     * @return attempted/succeeded/skipped 집계
     */
    public MacroCollectionResult collect() {
        return collectInternal(false);
    }

    /**
     * 5개 FRED 시리즈 전체 이력 백필 (limit=100000).
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

        for (FredSeriesConfig.Series series : FredSeriesConfig.ALL) {
            try {
                MacroCollectionResult result = collectSeries(series, backfill);
                attempted += result.attempted();
                succeeded += result.succeeded();
                skipped += result.skipped();
            } catch (Exception e) {
                log.error("[fred] 시리즈 수집 예외 — series={}, 다음 시리즈 계속", series.indicatorCode(), e);
            }
        }

        log.info(
                "[fred] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                attempted,
                succeeded,
                skipped);
        return new MacroCollectionResult(attempted, succeeded, skipped);
    }

    private MacroCollectionResult collectSeries(FredSeriesConfig.Series series, boolean backfill) {
        String url = buildUrl(series, backfill);

        FredObservationsResponse response =
                macroFredRestClient.get().uri(url).retrieve().body(FredObservationsResponse.class);

        if (response == null || response.observations() == null) {
            log.info("[fred] 빈 응답 — series={}", series.indicatorCode());
            return new MacroCollectionResult(0, 0, 0);
        }

        int attempted = 0;
        int succeeded = 0;
        int skipped = 0;

        for (FredObservationsResponse.Observation obs : response.observations()) {
            attempted++;
            if (saveIfValid(series, obs)) {
                succeeded++;
            } else {
                skipped++;
            }
        }

        return new MacroCollectionResult(attempted, succeeded, skipped);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 파싱 실패 graceful skip
    private boolean saveIfValid(
            FredSeriesConfig.Series series, FredObservationsResponse.Observation obs) {
        String dateStr = obs.date();
        String valueStr = obs.value();

        // REQ-MACRO-EXT-032: value="." → skip
        if (valueStr == null || ".".equals(valueStr) || valueStr.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[fred] value='.' skip — series={}, date={}",
                        series.indicatorCode(),
                        dateStr);
            }
            return false;
        }

        try {
            LocalDate tradeDate = LocalDate.parse(dateStr, DATE_FMT);
            BigDecimal value = new BigDecimal(valueStr);

            MacroIndicator entity =
                    MacroIndicator.builder()
                            .indicatorCode(series.indicatorCode())
                            .source(MacroSource.FRED)
                            .tradeDate(tradeDate)
                            .value(value)
                            .build();

            macroIndicatorRepository.insertIgnoreDuplicate(entity);
            return true;
        } catch (Exception e) {
            log.warn(
                    "[fred] 파싱/저장 실패 — series={}, date={}, value={}, error={}",
                    series.indicatorCode(),
                    dateStr,
                    valueStr,
                    e.getMessage());
            return false;
        }
    }

    private String buildUrl(FredSeriesConfig.Series series, boolean backfill) {
        // FRED API: GET /fred/series/observations?series_id={id}&api_key={key}&file_type=json
        String base =
                "/fred/series/observations?series_id=%s&api_key=%s&file_type=json"
                        .formatted(series.seriesId(), apiKey);

        if (backfill) {
            return base + "&limit=100000";
        } else {
            return base + "&sort_order=desc&limit=" + DAILY_LIMIT;
        }
    }
}
