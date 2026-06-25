package com.aaa.collector.dart.disclosure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** DART 공시 메타데이터 리포지토리 (SPEC-COLLECTOR-DART-001). */
public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 disclosures에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시
    // 중복 충돌에서 SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DART-001). DailyOhlcvRepository 선례.
    /**
     * 공시 1건을 멱등 삽입한다 (REQ-DART-011, AC-I1).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_disclosures_rcept_no (rcept_no)} 충돌 시 해당 행을
     * 무시하여 행 수가 증가하지 않으며 UPDATE를 발생시키지 않는다(Tier-1, ADR-026).
     *
     * <p>네이티브 MySQL 쿼리 — H2 미동작, 반드시 MySQL Testcontainer 통합 테스트로 검증.
     *
     * @param row 삽입할 공시 파라미터 (rcept_no가 멱등 키)
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO disclosures
                        (stock_id, corp_code, stock_code, corp_cls, report_nm, rcept_no,
                         flr_nm, rcept_dt, rm, pblntf_ty, created_at, updated_at)
                    VALUES
                        (:#{#row.stockId}, :#{#row.corpCode}, :#{#row.stockCode}, :#{#row.corpCls},
                         :#{#row.reportNm}, :#{#row.rceptNo}, :#{#row.flrNm}, :#{#row.rceptDt},
                         :#{#row.rm}, :#{#row.pblntfTy}, NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnore(@Param("row") DisclosureRow row);

    /** 접수번호로 행 수를 반환한다 (통합 테스트용). */
    @Query("SELECT COUNT(d) FROM Disclosure d WHERE d.rceptNo = :rceptNo")
    long countByRceptNo(@Param("rceptNo") String rceptNo);

    /** 종목 ID로 행 수를 반환한다 (통합 테스트용). */
    @Query("SELECT COUNT(d) FROM Disclosure d WHERE d.stockId = :stockId")
    long countByStockId(@Param("stockId") Long stockId);
}
