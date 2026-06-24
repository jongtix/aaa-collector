package com.aaa.collector.news;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 국내 뉴스 제목 리포지토리. */
public interface DomesticNewsHeadlineRepository extends JpaRepository<DomesticNewsHeadline, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 domestic_news_headlines에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE
    // 사용 시
    // 중복
    // 충돌에서
    // SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DBGRANT-002)
    /**
     * 국내 뉴스 제목 1건을 멱등 삽입한다 (REQ-BATCH3-062).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_domestic_news_headlines_serial (serial_no)} 충돌
     * 시 해당 행을 무시하여 행 수가 증가하지 않으며 UPDATE를 발생시키지 않는다.
     *
     * <p>기존 {@code ON DUPLICATE KEY UPDATE id = id}는 no-op이라도 MySQL이 중복 충돌 시 UPDATE 경로를 밟아 UPDATE
     * 권한을 검사하므로, UPDATE 권한이 없는 {@code collector} 사용자에게 SQL 1142({@code UPDATE command denied to
     * user 'collector'@'%' for table 'news_headlines'})를 유발했다(ADR-026 Tier-1, ADR-025 §맥락 1).
     * production 24시간 42,460회 실패 확인.
     *
     * <p>10분 간격 재실행 시 inclusive SRNO 커서로 중복 경계 행이 발생해도 멱등이 흡수한다(REQ-BATCH3-063).
     *
     * <p>엔티티 필드를 SpEL로 참조하여 단일 파라미터로 전달한다. 네이티브 MySQL 쿼리 — MySQL Testcontainer 통합 테스트로 검증한다.
     *
     * @param e 저장 대상 엔티티
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO domestic_news_headlines
                        (serial_no, published_at, provider_code, title,
                         category_code, source,
                         stock_code1, stock_code2, stock_code3, stock_code4, stock_code5,
                         created_at, updated_at)
                    VALUES
                        (:#{#e.serialNo}, :#{#e.publishedAt}, :#{#e.providerCode}, :#{#e.title},
                         :#{#e.categoryCode}, :#{#e.source},
                         :#{#e.stockCode1}, :#{#e.stockCode2}, :#{#e.stockCode3}, :#{#e.stockCode4}, :#{#e.stockCode5},
                         NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") DomesticNewsHeadline e);

    /** 전체 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(n) FROM DomesticNewsHeadline n")
    long countAll();

    /** serial_no로 최대값 조회 (증분 커서용). */
    @Query("SELECT MAX(n.serialNo) FROM DomesticNewsHeadline n")
    String findMaxSerialNo();
}
