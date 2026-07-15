package com.aaa.collector.market.indicator.vix;

import com.aaa.collector.common.config.InserterProperties;
import com.aaa.collector.market.MarketIndicator;
import com.aaa.collector.market.MarketIndicatorInserter;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSourceChain;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * VIX 일봉 수집 서비스 (SPEC-COLLECTOR-MARKETIND-001, REQ-020~022; SPEC-COLLECTOR-MARKETIND-003,
 * REQ-001~006).
 *
 * <p>체인 = [CBOE, Yahoo^VIX](SPEC-COLLECTOR-MARKETIND-003 REQ-030 — FRED 제거). 행별 검증 skip(REQ-034). 종
 * 단위 예외 흡수(REQ-003).
 *
 * <p>일봉 수집은 KST 기준 {@code [today - WINDOW_LOOKBACK_DAYS, today - WINDOW_END_OFFSET_DAYS]} 윈도우 범위
 * 조회로 전환됐다(REQ-001~003) — 단일 날짜(today) 조회는 CBOE/FRED 게시 지연·Yahoo 진행 중 부분 일봉과 구조적으로 불일치하기
 * 때문(aaa-infra#87).
 *
 * <p>REQ-INSERT-009, REQ-INSERT-010: 유효 행을 누적한 뒤 {@code chunkSize}로 분할하여 청크마다 단일 커넥션 배치 INSERT
 * IGNORE.
 */
@Slf4j
@Service
public class VixCollectionService {

    /** 윈도우 하한 lookback 일수 (REQ-003, 기본 14). */
    static final int WINDOW_LOOKBACK_DAYS = 14;

    /** 윈도우 상한 offset 일수 (REQ-003, 기본 1 — 오늘 배제). */
    static final int WINDOW_END_OFFSET_DAYS = 1;

    /** 비확정 행 배제 기준 시간대 — 거래일 확정성이 미국 시장 시각에 종속(REQ-006, REQ-050). */
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final MarketIndicatorSourceChain vixChain;
    private final MarketIndicatorRepository marketIndicatorRepository;
    private final MarketIndicatorInserter marketIndicatorInserter;
    private final InserterProperties inserterProperties;

    public VixCollectionService(
            @Qualifier("vixChain") MarketIndicatorSourceChain vixChain,
            MarketIndicatorRepository marketIndicatorRepository,
            MarketIndicatorInserter marketIndicatorInserter,
            InserterProperties inserterProperties) {
        this.vixChain = vixChain;
        this.marketIndicatorRepository = marketIndicatorRepository;
        this.marketIndicatorInserter = marketIndicatorInserter;
        this.inserterProperties = inserterProperties;
    }

    /**
     * VIX 일봉 윈도우 수집 (SPEC-COLLECTOR-MARKETIND-003, REQ-001~005).
     *
     * <p>{@code today}에서 KST 기준 윈도우 {@code [today - WINDOW_LOOKBACK_DAYS, today -
     * WINDOW_END_OFFSET_DAYS]}(양끝 포함)를 파생해 VIX 체인으로 범위 조회한다. 상한이 항상 {@code today -
     * WINDOW_END_OFFSET_DAYS}(기본 어제)로 고정되므로 당일이 구조적으로 배제된다(REQ-002).
     *
     * @param today 배치 트리거 시점의 KST 기준 오늘 날짜
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 종 단위 예외 격리 (REQ-003)
    public void collectDaily(LocalDate today) {
        LocalDate from = today.minusDays(WINDOW_LOOKBACK_DAYS);
        LocalDate to = today.minusDays(WINDOW_END_OFFSET_DAYS);
        try {
            List<MarketIndicatorRow> rows = vixChain.fetchRange(from, to);
            int saved = saveRows(rows);
            log.info("[vix] 윈도우 수집 완료 — window=[{},{}], saved={}", from, to, saved);
        } catch (Exception e) {
            log.error("[vix] 윈도우 수집 예외 — window=[{},{}]", from, to, e);
        }
    }

    /**
     * 전체 이력 수집 (백필용).
     *
     * @return 저장된 행 수
     */
    public int collectHistory() {
        List<MarketIndicatorRow> rows = vixChain.fetchHistory();
        return saveRows(rows);
    }

    /**
     * 유효 행을 누적해 배치 저장한다. 일봉 윈도우·백필/히스토리 양 경로가 공유하는 저장 지점이다.
     *
     * <p>REQ-006: 미국 동부시각(NY) 기준 당일({@code >= LocalDate.now(NEW_YORK)}) 이후 {@code trade_date} 행은
     * 비확정(진행 중 부분 일봉 가능성)으로 간주해 행 단위로 배제한다 — 전부-아니면-전무가 아니다.
     */
    private int saveRows(List<MarketIndicatorRow> rows) {
        LocalDate todayEt = LocalDate.now(NEW_YORK);

        // Accumulate valid rows
        List<MarketIndicator> batch = new ArrayList<>();
        for (MarketIndicatorRow row : rows) {
            if (!isValid(row)) {
                log.warn("[vix] 행 검증 실패 — skip: {}", row);
                continue;
            }
            if (!row.tradeDate().isBefore(todayEt)) {
                log.warn(
                        "[vix] 비확정 행(당일 이후, NY 기준) 배제 — tradeDate={}, todayEt={}",
                        row.tradeDate(),
                        todayEt);
                continue;
            }
            batch.add(toEntity(row));
        }

        if (batch.isEmpty()) {
            return 0;
        }

        // REQ-INSERT-010: 대용량 배치는 chunkSize로 분할하여 청크마다 단일 커넥션 INSERT IGNORE
        int chunkSize = inserterProperties.getChunkSize();
        for (int i = 0; i < batch.size(); i += chunkSize) {
            List<MarketIndicator> chunk = batch.subList(i, Math.min(i + chunkSize, batch.size()));
            marketIndicatorInserter.insertBatch(chunk);
        }
        return batch.size();
    }

    private boolean isValid(MarketIndicatorRow row) {
        return row.closeValue() != null && row.closeValue().signum() > 0;
    }

    private MarketIndicator toEntity(MarketIndicatorRow row) {
        return MarketIndicator.builder()
                .indicatorCode(IndicatorCode.VIX)
                .tradeDate(row.tradeDate())
                .openValue(row.openValue())
                .highValue(row.highValue())
                .lowValue(row.lowValue())
                .closeValue(row.closeValue())
                .source(row.source())
                .build();
    }
}
