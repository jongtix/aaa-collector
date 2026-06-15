package com.aaa.collector.news;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 뉴스 제목 리포지토리. */
public interface NewsHeadlineRepository extends JpaRepository<NewsHeadline, Long> {

    /**
     * 뉴스 제목 1건을 멱등 삽입한다 (REQ-BATCH3-062).
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id=id} no-op upsert. Unique Key {@code
     * uk_news_headlines_serial (serial_no)} 충돌 시 행 수 미증가·UPDATE 미발생(TECHSPEC 4절).
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
                    INSERT INTO news_headlines
                        (serial_no, published_at, provider_code, title,
                         category_code, source,
                         stock_code1, stock_code2, stock_code3, stock_code4, stock_code5,
                         created_at, updated_at)
                    VALUES
                        (:#{#e.serialNo}, :#{#e.publishedAt}, :#{#e.providerCode}, :#{#e.title},
                         :#{#e.categoryCode}, :#{#e.source},
                         :#{#e.stockCode1}, :#{#e.stockCode2}, :#{#e.stockCode3}, :#{#e.stockCode4}, :#{#e.stockCode5},
                         NOW(), NOW())
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") NewsHeadline e);

    /** 전체 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(n) FROM NewsHeadline n")
    long countAll();

    /** serial_no로 최대값 조회 (증분 커서용). */
    @Query("SELECT MAX(n.serialNo) FROM NewsHeadline n")
    String findMaxSerialNo();
}
