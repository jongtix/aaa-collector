package com.aaa.collector.stock.grade;

import com.aaa.collector.kis.websocket.DomesticSymbolProvider;
import com.aaa.collector.stock.StockGrade;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
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

    /** A등급 우선 정렬 기준. */
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
        List<StockGrade> grades = stockGradeRepository.findByGradeInOrderByGradeAsc(GRADE_PRIORITY);
        // A등급 우선으로 최대 MAX_SYMBOLS개 선택
        return grades.stream()
                .sorted(
                        Comparator.comparingInt(
                                g -> {
                                    int idx = GRADE_PRIORITY.indexOf(g.getGrade());
                                    // 목록에 없는 등급은 맨 뒤로
                                    return idx < 0 ? Integer.MAX_VALUE : idx;
                                }))
                .limit(MAX_SYMBOLS)
                .map(sg -> sg.getStock().getSymbol())
                .collect(Collectors.toList());
    }
}
