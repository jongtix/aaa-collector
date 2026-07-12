package com.aaa.collector.warmstart;

import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.grade.StockGradeRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code watchlist-sync-krx}/{@code watchlist-sync-us} last_load warm-start seed 해석기
 * (SPEC-COLLECTOR-EXPECTED-RUN-001 §13 O-3).
 *
 * <p>{@link BatchMetricsWarmStarter}에서 시장 필터 조회 로직을 추출한 협력자다({@link ExtendedHoursWarmSource}와 동일 목적
 * — warm-starter의 import/coupling 상한(ExcessiveImports/CouplingBetweenObjects) 회피).
 *
 * <p>sync 대상 {@code stocks}는 Tier-2 in-place UPDATE라 실행마다 전진하는 per-run ts가 없다. sync+classify 원자
 * 배치(Pattern C)의 마지막 단계인 classify가 남기는 {@code stock_grades.graded_at} MAX를 대리 seed로 쓴다 — {@link
 * com.aaa.collector.stock.StockGrade#updateGrade} 계약(등급 불변이어도 gradedAt을 무조건 갱신)에 의존한다. 계약 상세는
 * {@link StockGradeRepository#findMaxGradedAtByMarketsIn} Javadoc 참조.
 */
@Component
@RequiredArgsConstructor
public class WatchlistSyncWarmSource {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final List<Market> DOMESTIC_MARKETS =
            List.of(Market.KOSPI, Market.KOSDAQ, Market.KRX);
    private static final List<Market> OVERSEAS_MARKETS =
            List.of(Market.NYSE, Market.NASDAQ, Market.AMEX, Market.US);

    private final StockGradeRepository stockGradeRepository;

    /** 국내 시장 최신 등급 산정 시각을 KST 벽시계로 변환해 반환한다. 데이터 없으면 {@link Optional#empty()}. */
    public Optional<LocalDateTime> krxLastLoad() {
        return stockGradeRepository
                .findMaxGradedAtByMarketsIn(DOMESTIC_MARKETS)
                .map(zdt -> zdt.withZoneSameInstant(KST).toLocalDateTime());
    }

    /** 해외 시장 최신 등급 산정 시각을 KST 벽시계로 변환해 반환한다. 데이터 없으면 {@link Optional#empty()}. */
    public Optional<LocalDateTime> usLastLoad() {
        return stockGradeRepository
                .findMaxGradedAtByMarketsIn(OVERSEAS_MARKETS)
                .map(zdt -> zdt.withZoneSameInstant(KST).toLocalDateTime());
    }
}
