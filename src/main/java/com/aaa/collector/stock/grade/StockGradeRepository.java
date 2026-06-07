package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
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
}
