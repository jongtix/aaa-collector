package com.aaa.collector.macro.fred;

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
    private final MacroIndicatorInserter macroIndicatorInserter;
    private final InserterProperties inserterProperties;

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

    /**
     * {@code indicator_code}로 지정된 단일 FRED 시리즈의 전체 이력을 백필한다(REQ-FREDFMT-005).
     *
     * <p>5개 시리즈 일괄 수집(collectAll())과 달리 해당 시리즈 1개만 호출하며(REQ-FREDFMT-004, 010), 반환 집계에 다른 시리즈의 수집분을
     * 합산하지 않는다(REQ-FREDFMT-006 — 오귀속 금지). {@code collectInternal()}의 시리즈 단위 예외 격리와 달리 예외를 흡수하지 않고
     * 호출자(백필 오케스트레이터)로 그대로 전파한다(REQ-FREDFMT-008 — 예외 경로가 FAILED 전이를 트리거하도록).
     *
     * @throws FredApiException {@code indicator_code}에 대응하는 시리즈 설정이 없으면 (REQ-FREDFMT-007), 또는 수집이
     *     예측 불가 응답으로 실패하면
     */
    public MacroCollectionResult collectAllForIndicator(String indicatorCode) {
        FredSeriesConfig.Series series = findSeries(indicatorCode);
        return collectSeries(series, true);
    }

    private static FredSeriesConfig.Series findSeries(String indicatorCode) {
        return FredSeriesConfig.ALL.stream()
                .filter(s -> s.indicatorCode().equals(indicatorCode))
                .findFirst()
                .orElseThrow(() -> new FredApiException(indicatorCode, "알 수 없는 indicator_code"));
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
                attempted++;
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

        if (response == null) {
            // 예측 불가 응답 — 정상 0건으로 흡수하지 않고 실패로 취급(REQ-FREDFMT-001, AC-7.1)
            throw new FredApiException(series.indicatorCode(), "응답 본문이 null");
        }

        if (response.observations().isEmpty()) {
            log.info("[fred] 빈 응답 — series={}", series.indicatorCode());
            return new MacroCollectionResult(0, 0, 0);
        }

        int attempted = 0;
        int skipped = 0;

        // REQ-INSERT-009: 유효 행 누적 후 청크 분할 배치 INSERT IGNORE (REQ-INSERT-010)
        List<MacroIndicator> batch = new ArrayList<>();
        for (FredObservationsResponse.Observation obs : response.observations()) {
            attempted++;
            MacroIndicator entity = buildIfValid(series, obs);
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
            return null;
        }

        try {
            LocalDate tradeDate = LocalDate.parse(dateStr, DATE_FMT);
            BigDecimal value = new BigDecimal(valueStr);

            return MacroIndicator.builder()
                    .indicatorCode(series.indicatorCode())
                    .source(MacroSource.FRED)
                    .tradeDate(tradeDate)
                    .value(value)
                    .build();
        } catch (Exception e) {
            log.warn(
                    "[fred] 파싱 실패 — series={}, date={}, value={}, error={}",
                    series.indicatorCode(),
                    dateStr,
                    valueStr,
                    e.getMessage());
            return null;
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
