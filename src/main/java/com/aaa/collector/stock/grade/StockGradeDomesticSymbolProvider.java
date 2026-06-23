package com.aaa.collector.stock.grade;

import com.aaa.collector.kis.websocket.DomesticSymbolProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link DomesticSymbolProvider}의 stock.grade 기반 구현체.
 *
 * <p>A·B 등급 종목을 stock_grades 테이블에서 조회하여 최대 100종목을 반환한다. A등급 우선으로 절삭한다 (REQ-WS-053).
 */
@Component
@RequiredArgsConstructor
public class StockGradeDomesticSymbolProvider implements DomesticSymbolProvider {

    /** 최대 구독 종목 수 (200건 = 체결 + 호가). */
    private static final int MAX_SYMBOLS = 100;

    /** A등급 우선 정렬 기준. DB 쿼리가 grade ASC로 정렬하므로 A가 B보다 먼저 반환된다. */
    private static final List<String> GRADE_PRIORITY = List.of("A", "B");

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
        return stockGradeRepository.findSymbolsByGradeIn(GRADE_PRIORITY).stream()
                .limit(MAX_SYMBOLS)
                .toList();
    }
}
