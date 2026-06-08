package com.aaa.collector.stock;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 종목 등급 리포지토리.
 *
 * <p>StockGrade는 final 필드(setter 없음)이므로 등급 변경은 native upsert query를 사용한다 (결정 1).
 */
public interface StockGradeRepository extends JpaRepository<StockGrade, Long> {

    // @MX:ANCHOR: [AUTO] delta upsert entry point — fan_in: EtfRepresentativeService + tests
    // @MX:REASON: StockGrade has final fields (no setters); native ON DUPLICATE KEY UPDATE is
    // the only safe upsert path. Callers must not attempt JPA merge.

    /**
     * Upserts the grade for a stock using MySQL ON DUPLICATE KEY UPDATE.
     *
     * <p>If the stock already has a grade row, updates grade and graded_at. Otherwise inserts a new
     * row. This is safe because stock_id has a UNIQUE constraint.
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO stock_grades (stock_id, grade, graded_at, created_at, updated_at) "
                            + "VALUES (:stockId, :grade, :gradedAt, NOW(6), NOW(6)) "
                            + "ON DUPLICATE KEY UPDATE "
                            + "grade = VALUES(grade), graded_at = VALUES(graded_at), updated_at = NOW(6)",
            nativeQuery = true)
    void upsertGrade(
            @Param("stockId") Long stockId,
            @Param("grade") String grade,
            @Param("gradedAt") LocalDateTime gradedAt);
}
