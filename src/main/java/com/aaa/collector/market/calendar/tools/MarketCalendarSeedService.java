package com.aaa.collector.market.calendar.tools;

import com.aaa.collector.kis.holiday.KisHolidayClient;
import com.aaa.collector.market.calendar.CalendarCode;
import com.aaa.collector.market.calendar.CalendarSource;
import com.aaa.collector.market.calendar.NyseHolidayAlgorithm;
import com.aaa.collector.market.calendar.tools.MarketCalendarSqlWriter.Row;
import com.aaa.collector.market.session.UsMarketProperties;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code market_calendar} 초기 시딩 실제 절차(SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-040~049, TASK-012) —
 * {@link MarketCalendarSeedTool}(스프링 프로파일 게이트)에서 위임받는 순수 협력자. Spring 빈이 아니다 — {@code
 * MarketCalendarSeedTool}이 자신의 생성자 주입 의존성을 그대로 전달해 수동으로 생성한다.
 *
 * <p>절차: (1) {@code KRX}는 {@link KrxCalendarSourceFetcher}로 1985-01-01부터 오늘+20까지 순차 체이닝 조회, (2) 각
 * 날짜에 대해 {@code DERIVED}({@code daily_ohlcv} 국내 시장 전체 존재 여부) 계산, (3) KIS vs DERIVED 불일치를 {@link
 * MismatchClusterClassifier}로 군집/산발 판정, (4) 군집이면 {@code DERIVED} 자동 채택, 산발적이면 원본 값을 잠정 기록하고 리뷰 목록으로
 * 로그 출력, (5) {@code NYSE}는 {@link NyseHolidayAlgorithm} vs 미국 {@code daily_ohlcv} DERIVED로 동일 절차,
 * (6) {@link UsMarketProperties#getExtraHolidays()} 값을 {@code MANUAL} 소스로 반영(REQ-CAL-047), (7) 최종
 * INSERT SQL을 {@link MarketCalendarSqlWriter}로 절대경로 파일에 출력한다(레포 커밋 대상 아님, REQ-CAL-048).
 *
 * <p>KRX 조회({@link KrxCalendarSourceFetcher})와 SQL 출력({@link MarketCalendarSqlWriter})은 각자 단일 책임
 * 협력자로 분리했다(PMD CouplingBetweenObjects 완화 목적).
 */
// @MX:WARN: [AUTO] 오퍼레이터 전용 대량 배치 — 40년치 KIS 순차 호출(~625콜) + 전체 daily_ohlcv 스캔
// @MX:REASON: 일반 부팅·일일 갱신 배치와 분리된 1회성 도구(REQ-CAL-041). MarketCalendarSeedTool의 프로파일 게이트
// 뒤에서만 생성되어 프로덕션 경로에는 영향 없음.
@Slf4j
@RequiredArgsConstructor
class MarketCalendarSeedService {

    /** KRX/NYSE 공통 시딩 하한 — daily_ohlcv 실보유 최과거(REQ-CAL-040). */
    static final LocalDate SEED_START = LocalDate.of(1985, 1, 1);

    /** 시딩 상한 오프셋(오늘+20일, REQ-CAL-040). */
    static final int FORWARD_DAYS = 20;

    private final KisHolidayClient kisHolidayClient;
    private final StockRepository stockRepository;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final UsMarketProperties usMarketProperties;
    private final Clock clock;

    /**
     * 전체 시딩 절차를 실행하고 결과를 {@code outputPath}에 INSERT SQL로 출력한다.
     *
     * @param outputPath 산출 SQL 파일 절대경로(레포 밖, REQ-CAL-048)
     */
    void seed(Path outputPath) throws IOException {
        LocalDate today = LocalDate.now(clock);
        LocalDate seedEnd = today.plusDays(FORWARD_DAYS);
        log.info("[calendar-seed] 시딩 시작 — 범위=[{}, {}], 산출 파일={}", SEED_START, seedEnd, outputPath);

        List<MismatchClusterClassifier.Mismatch> allMismatches = new ArrayList<>();
        Map<String, Row> combined = new LinkedHashMap<>();

        Map<LocalDate, Boolean> krxSource =
                new KrxCalendarSourceFetcher(kisHolidayClient).fetch(SEED_START, seedEnd);
        Set<LocalDate> krxDerived =
                derivedTradeDates(stockRepository.findAllActiveDomesticTradable());
        for (Row row :
                resolveMarket(
                        CalendarCode.KRX,
                        krxSource,
                        krxDerived,
                        CalendarSource.KIS_API,
                        allMismatches)) {
            combined.put(key(row.calendarCode(), row.calDate()), row);
        }

        Map<LocalDate, Boolean> nyseSource = buildNyseSourceMap(seedEnd);
        Set<LocalDate> nyseDerived =
                derivedTradeDates(stockRepository.findAllActiveOverseasTradable());
        for (Row row :
                resolveMarket(
                        CalendarCode.NYSE,
                        nyseSource,
                        nyseDerived,
                        CalendarSource.ALGORITHM,
                        allMismatches)) {
            combined.put(key(row.calendarCode(), row.calDate()), row);
        }

        // REQ-CAL-047: extraHolidays는 MANUAL 소스로 반영하며, 동일 날짜의 알고리즘 계산값을 덮어쓴다(MANUAL 최우선순위).
        for (LocalDate extraHoliday : usMarketProperties.getExtraHolidays()) {
            Row row = new Row(CalendarCode.NYSE, extraHoliday, false, CalendarSource.MANUAL);
            combined.put(key(row.calendarCode(), row.calDate()), row);
        }

        logMismatchSummary(allMismatches);
        MarketCalendarSqlWriter.write(outputPath, combined.values());
        log.info(
                "[calendar-seed] 시딩 완료 — 총 {}행, 산출 파일={} (레포 커밋 대상 아님, 별도 배포 절차로 적용)",
                combined.size(),
                outputPath);
    }

    /** NYSE는 외부 호출 없이 {@link NyseHolidayAlgorithm}으로 전 범위를 순수 계산한다. */
    private Map<LocalDate, Boolean> buildNyseSourceMap(LocalDate seedEnd) {
        Map<LocalDate, Boolean> source = new LinkedHashMap<>();
        for (LocalDate date = SEED_START; !date.isAfter(seedEnd); date = date.plusDays(1)) {
            source.put(date, NyseHolidayAlgorithm.isOpenDay(date));
        }
        return source;
    }

    /** 지정 종목 목록의 합집합 거래일(daily_ohlcv 존재)을 DERIVED 값으로 조회한다. */
    private Set<LocalDate> derivedTradeDates(List<Stock> stocks) {
        List<Long> stockIds = stocks.stream().map(Stock::getId).toList();
        if (stockIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dailyOhlcvRepository.findDistinctTradeDatesByStockIds(stockIds));
    }

    /**
     * 원본 소스 값과 DERIVED 값을 대사하고, 불일치를 군집 판정 결과에 따라 최종 확정한다(REQ-CAL-042~046).
     *
     * @param calendarCode 대상 캘린더
     * @param sourceByDate 원본 소스(KIS_API/ALGORITHM) 값
     * @param derivedTradeDates DERIVED 거래일 집합
     * @param sourceLabel 불일치 없는 날짜에 부여할 원본 source 라벨
     * @param mismatchSink 발견된 불일치를 누적할 목록(호출자가 그룹 요약 로그 출력에 재사용)
     */
    private List<Row> resolveMarket(
            CalendarCode calendarCode,
            Map<LocalDate, Boolean> sourceByDate,
            Set<LocalDate> derivedTradeDates,
            CalendarSource sourceLabel,
            List<MismatchClusterClassifier.Mismatch> mismatchSink) {
        List<MismatchClusterClassifier.Mismatch> mismatches =
                collectMismatches(calendarCode, sourceByDate, derivedTradeDates);

        MismatchClusterClassifier classifier = new MismatchClusterClassifier();
        Map<LocalDate, MismatchClusterClassifier.Classification> classificationByDate =
                new HashMap<>();
        for (MismatchClusterClassifier.ClassifiedMismatch classified :
                classifier.classify(mismatches)) {
            classificationByDate.put(classified.mismatch().date(), classified.classification());
        }
        mismatchSink.addAll(mismatches);

        return buildRows(
                calendarCode, sourceByDate, derivedTradeDates, sourceLabel, classificationByDate);
    }

    private List<MismatchClusterClassifier.Mismatch> collectMismatches(
            CalendarCode calendarCode,
            Map<LocalDate, Boolean> sourceByDate,
            Set<LocalDate> derivedTradeDates) {
        List<MismatchClusterClassifier.Mismatch> mismatches = new ArrayList<>();
        for (Map.Entry<LocalDate, Boolean> entry : sourceByDate.entrySet()) {
            boolean sourceOpen = entry.getValue();
            boolean derivedOpen = derivedTradeDates.contains(entry.getKey());
            if (sourceOpen != derivedOpen) {
                mismatches.add(
                        new MismatchClusterClassifier.Mismatch(
                                calendarCode, entry.getKey(), sourceOpen, derivedOpen));
            }
        }
        return mismatches;
    }

    private List<Row> buildRows(
            CalendarCode calendarCode,
            Map<LocalDate, Boolean> sourceByDate,
            Set<LocalDate> derivedTradeDates,
            CalendarSource sourceLabel,
            Map<LocalDate, MismatchClusterClassifier.Classification> classificationByDate) {
        List<Row> rows = new ArrayList<>(sourceByDate.size());
        for (Map.Entry<LocalDate, Boolean> entry : sourceByDate.entrySet()) {
            LocalDate date = entry.getKey();
            boolean sourceOpen = entry.getValue();
            boolean derivedOpen = derivedTradeDates.contains(date);
            if (sourceOpen == derivedOpen) {
                rows.add(new Row(calendarCode, date, sourceOpen, sourceLabel));
                continue;
            }
            if (classificationByDate.get(date)
                    == MismatchClusterClassifier.Classification.CLUSTERED) {
                // REQ-CAL-044: 군집이면 DERIVED 자동 채택
                rows.add(new Row(calendarCode, date, derivedOpen, CalendarSource.DERIVED));
            } else {
                // REQ-CAL-045: 산발적이면 원본 값을 잠정 기록(자동 확정하지 않음, 리뷰 목록 별도 출력)
                rows.add(new Row(calendarCode, date, sourceOpen, sourceLabel));
            }
        }
        return rows;
    }

    /** 그룹별 요약을 로그로 출력하고, 산발적(ISOLATED) 불일치는 리뷰 목록으로 별도 경고 로그를 남긴다(REQ-CAL-045). */
    private void logMismatchSummary(List<MismatchClusterClassifier.Mismatch> allMismatches) {
        MismatchClusterClassifier classifier = new MismatchClusterClassifier();
        List<MismatchClusterClassifier.GroupSummary> summaries =
                classifier.summarize(allMismatches);
        log.info("[calendar-seed] 불일치 그룹 요약 — 총 {}개 그룹", summaries.size());
        for (MismatchClusterClassifier.GroupSummary summary : summaries) {
            log.info(
                    "[calendar-seed]   calendarCode={}, dayOfWeek={}, source={}, derived={}, count={},"
                            + " classification={}",
                    summary.key().calendarCode(),
                    summary.key().dayOfWeek(),
                    summary.key().sourceOpen(),
                    summary.key().derivedOpen(),
                    summary.count(),
                    summary.classification());
        }

        List<MismatchClusterClassifier.Mismatch> reviewList =
                classifier.classify(allMismatches).stream()
                        .filter(
                                cm ->
                                        cm.classification()
                                                == MismatchClusterClassifier.Classification
                                                        .ISOLATED)
                        .map(MismatchClusterClassifier.ClassifiedMismatch::mismatch)
                        .toList();
        if (!reviewList.isEmpty()) {
            log.warn(
                    "[calendar-seed] 산발적 불일치 {}건 — 사람 리뷰 필요(자동 확정되지 않음, 원본 값 잠정 기록): {}",
                    reviewList.size(),
                    reviewList);
        }
    }

    private static String key(CalendarCode calendarCode, LocalDate calDate) {
        return calendarCode.name() + "|" + calDate;
    }
}
