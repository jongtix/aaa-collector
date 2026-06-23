package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code stock_grades} 테이블 저장소. */
public interface StockGradeRepository extends JpaRepository<StockGrade, Long> {

    /**
     * 종목으로 등급을 조회한다.
     *
     * @param stock 조회할 종목
     * @return 등급. 없으면 {@link Optional#empty()}
     */
    Optional<StockGrade> findByStock(Stock stock);

    /**
     * 지정 등급 종목의 symbol을 grade 오름차순으로 반환한다.
     *
     * <p>Stock 엔티티 로드 없이 symbol만 projection하여 LazyInitializationException을 방지한다.
     *
     * @param grades 조회할 등급 목록 (예: {@code List.of("A", "B")})
     * @return symbol 목록 (grade 오름차순)
     */
    @Query(
            "SELECT sg.stock.symbol FROM StockGrade sg WHERE sg.grade IN :grades ORDER BY sg.grade ASC")
    List<String> findSymbolsByGradeIn(@Param("grades") List<String> grades);
}
