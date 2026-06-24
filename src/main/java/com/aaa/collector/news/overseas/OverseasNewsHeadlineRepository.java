package com.aaa.collector.news.overseas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 해외 뉴스 제목 리포지토리. */
public interface OverseasNewsHeadlineRepository extends JpaRepository<OverseasNewsHeadline, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 overseas_news_headlines에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE
    // 사용 시 중복 충돌에서 SQL 1142 발생 (ADR-026 Tier-1, SPEC-COLLECTOR-DBGRANT-002, 국내 뉴스 24h 42,460 실패 선례)
    /**
     * 해외 뉴스 제목 1건을 멱등 삽입한다 (REQ-OVE-042/060).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_overseas_news_headlines_key (news_key)} 충돌 시
     * 해당 행을 무시하여 행 수가 증가하지 않으며 UPDATE를 발생시키지 않는다. {@code ON DUPLICATE KEY UPDATE}는 no-op이라도 MySQL이
     * 중복 충돌 시 UPDATE 경로를 밟아 UPDATE 권한을 검사하므로, UPDATE 권한이 없는 {@code collector} 사용자에게 SQL 1142를
     * 유발한다(ADR-026 Tier-1, 국내 뉴스 24h 42,460 실패 선례 — 재발 금지).
     *
     * <p>10분 간격 재실행 + DATA_DT/DATA_TM inclusive 커서로 페이지 경계 1건 중복이 발생해도 멱등이 흡수한다(REQ-OVE-043a). 종목
     * 무관 뉴스의 빈 문자열 {@code symbol}/{@code symbol_name}/{@code exchange_cd}도 그대로 저장된다(REQ-OVE-047).
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
                    INSERT IGNORE INTO overseas_news_headlines
                        (news_key, published_at, info_gb, class_cd, class_name, source,
                         nation_cd, exchange_cd, symbol, symbol_name, title,
                         created_at, updated_at)
                    VALUES
                        (:#{#e.newsKey}, :#{#e.publishedAt}, :#{#e.infoGb}, :#{#e.classCd}, :#{#e.className}, :#{#e.source},
                         :#{#e.nationCd}, :#{#e.exchangeCd}, :#{#e.symbol}, :#{#e.symbolName}, :#{#e.title},
                         NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") OverseasNewsHeadline e);

    /** 전체 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(n) FROM OverseasNewsHeadline n")
    long countAll();
}
