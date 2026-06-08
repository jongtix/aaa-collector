package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * A·B 등급 종목을 grade 오름차순(A 우선)으로 조회한다.
     *
     * @param grades 조회할 등급 목록 (예: {@code List.of("A", "B")})
     * @return 해당 등급 종목 목록 (grade 오름차순)
     */
    List<StockGrade> findByGradeInOrderByGradeAsc(List<String> grades);
}
