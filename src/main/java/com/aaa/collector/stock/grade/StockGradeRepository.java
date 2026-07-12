package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
import com.aaa.collector.stock.enums.Market;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code stock_grades} 테이블 저장소. */
public interface StockGradeRepository extends JpaRepository<StockGrade, Long> {

    /** 해외 WebSocket 구독 대상 조회 시 symbol과 market을 함께 전달하는 projection. */
    record SymbolWithMarket(String symbol, Market market) {}

    /**
     * 종목으로 등급을 조회한다.
     *
     * @param stock 조회할 종목
     * @return 등급. 없으면 {@link Optional#empty()}
     */
    Optional<StockGrade> findByStock(Stock stock);

    /**
     * 지정 시장·등급 종목의 symbol을 grade 오름차순으로 반환한다.
     *
     * <p>Stock 엔티티 로드 없이 symbol만 projection하여 LazyInitializationException을 방지한다. {@code
     * stock_grades}는 국내·해외 종목 등급을 한 테이블에 함께 저장하므로, market 필터가 없으면 해외 A·B등급 종목이 국내 구독 대상에
     * 혼입된다(aaa-infra#69).
     *
     * @param markets 조회할 시장 목록 (예: {@code List.of(Market.KOSPI, Market.KOSDAQ)})
     * @param grades 조회할 등급 목록 (예: {@code List.of("A", "B")})
     * @return symbol 목록 (grade 오름차순)
     */
    @Query(
            "SELECT sg.stock.symbol FROM StockGrade sg "
                    + "WHERE sg.stock.market IN :markets AND sg.grade IN :grades "
                    + "ORDER BY sg.grade ASC")
    List<String> findSymbolsByGradeIn(
            @Param("markets") List<Market> markets, @Param("grades") List<String> grades);

    /**
     * 미국 한정 종목의 symbol과 market을 grade 오름차순으로 반환한다.
     *
     * <p>해외 WebSocket 구독 대상(tr_key) 조립에 필요한 market 정보를 함께 projection한다.
     *
     * @param markets 조회할 시장 목록 (예: {@code List.of(Market.NYSE, Market.NASDAQ, Market.AMEX)})
     * @param grades 조회할 등급 목록 (예: {@code List.of("A", "B")})
     * @return (symbol, market) 쌍 목록 (grade 오름차순)
     */
    @Query(
            "SELECT new com.aaa.collector.stock.grade.StockGradeRepository$SymbolWithMarket("
                    + "sg.stock.symbol, sg.stock.market) "
                    + "FROM StockGrade sg "
                    + "WHERE sg.stock.market IN :markets AND sg.grade IN :grades "
                    + "ORDER BY sg.grade ASC")
    List<SymbolWithMarket> findUsSymbolsWithMarketByGradeIn(
            @Param("markets") List<Market> markets, @Param("grades") List<String> grades);

    /**
     * 시장 필터로 최대 등급 산정 시각을 조회한다 (SPEC-COLLECTOR-EXPECTED-RUN-001 O-3, {@code
     * watchlist-sync-krx}/{@code watchlist-sync-us} warm-start seed용).
     *
     * <p>{@link StockGrade#updateGrade}는 등급 불변 여부와 무관하게 매 classify 실행마다 {@code gradedAt}을 실행 시각으로
     * 무조건 덮어쓴다(REQ-011) — 이 계약이 성립하는 한 {@code MAX(gradedAt)}은 해당 시장 watchlist-sync 배치의 마지막 성공 실행
     * 시각의 신뢰 가능한 프록시다. 계약이 깨지면(예: 등급 불변 시 UPDATE 스킵) 이 seed도 함께 무효화된다 — {@code
     * StockGradeTest#updateGrade는_등급_불변이어도_gradedAt을_갱신한다} 회귀 테스트가 계약을 고정한다.
     *
     * @param markets 조회할 시장 목록
     * @return MAX(gradedAt) — 한 건도 없으면 {@link Optional#empty()}
     */
    @Query("SELECT MAX(sg.gradedAt) FROM StockGrade sg WHERE sg.stock.market IN :markets")
    Optional<ZonedDateTime> findMaxGradedAtByMarketsIn(
            @Param("markets") Collection<Market> markets);
}
