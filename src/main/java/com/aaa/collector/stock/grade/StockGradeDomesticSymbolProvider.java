package com.aaa.collector.stock.grade;

import com.aaa.collector.kis.websocket.DomesticSymbolProvider;
import com.aaa.collector.stock.enums.Market;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link DomesticSymbolProvider}의 stock.grade 기반 구현체.
 *
 * <p>A·B 등급 국내(KOSPI/KOSDAQ) 종목을 stock_grades 테이블에서 조회하여 최대 100종목을 반환한다. A등급 우선으로 절삭한다
 * (REQ-WS-053).
 */
@Component
@RequiredArgsConstructor
public class StockGradeDomesticSymbolProvider implements DomesticSymbolProvider {

    /** 최대 구독 종목 수 (200건 = 체결 + 호가). */
    private static final int MAX_SYMBOLS = 100;

    /** A등급 우선 정렬 기준. DB 쿼리가 grade ASC로 정렬하므로 A가 B보다 먼저 반환된다. */
    private static final List<String> GRADE_PRIORITY = List.of("A", "B");

    /** 국내 시장 한정 필터. stock_grades는 국내·해외 등급을 함께 저장하므로 명시적으로 걸러야 한다(aaa-infra#69). */
    private static final List<Market> KRX_MARKETS = List.of(Market.KOSPI, Market.KOSDAQ);

    private final StockGradeRepository stockGradeRepository;

    /**
     * A·B 등급 국내 종목 코드를 최대 100개 반환한다.
     *
     * <p>101종목 이상이면 A등급 우선으로 절삭한다 (REQ-WS-053).
     *
     * @return 구독 대상 종목 코드 목록 (최대 100개)
     */
    @Override
    public List<String> getDomesticSymbols() {
        return stockGradeRepository.findSymbolsByGradeIn(KRX_MARKETS, GRADE_PRIORITY).stream()
                .limit(MAX_SYMBOLS)
                .toList();
    }
}
