package com.aaa.collector.news.overseas;

import com.aaa.collector.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 해외 뉴스 제목 (KIS 해외뉴스종합, TR HHPSTH60100C1).
 *
 * <p>outblock1 매핑(RD-1): {@code news_key}(유니크 키)·{@code published_at}({@code data_dt}+{@code
 * data_tm} 합성)·{@code info_gb}·{@code class_cd}·{@code class_name}·{@code source}·{@code
 * nation_cd}·{@code exchange_cd}·{@code symbol}({@code symb})·{@code symbol_name}({@code
 * symb_name})·{@code title}. Tier-1, {@code INSERT IGNORE}. V27 DDL과 정합. 종목 무관 뉴스는 {@code
 * symbol}/{@code symbol_name}/{@code exchange_cd}가 빈 문자열로 저장된다(REQ-OVE-047 — NULL 정규화하지 않음).
 */
@Entity
@Table(
        name = "overseas_news_headlines",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_overseas_news_headlines_key",
                        columnNames = {"news_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class OverseasNewsHeadline extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "news_key", nullable = false, length = 20)
    private final String newsKey;

    @Column(name = "published_at")
    private final LocalDateTime publishedAt;

    @Column(name = "info_gb", length = 1)
    private final String infoGb;

    @Column(name = "class_cd", length = 2)
    private final String classCd;

    @Column(name = "class_name", length = 20)
    private final String className;

    @Column(name = "source", length = 20)
    private final String source;

    @Column(name = "nation_cd", length = 2)
    private final String nationCd;

    @Column(name = "exchange_cd", length = 3)
    private final String exchangeCd;

    @Column(name = "symbol", length = 20)
    private final String symbol;

    @Column(name = "symbol_name", length = 48)
    private final String symbolName;

    @Column(name = "title", nullable = false, length = 255)
    private final String title;

    @Builder
    private OverseasNewsHeadline(
            String newsKey,
            LocalDateTime publishedAt,
            String infoGb,
            String classCd,
            String className,
            String source,
            String nationCd,
            String exchangeCd,
            String symbol,
            String symbolName,
            String title) {
        super();
        this.newsKey = newsKey;
        this.publishedAt = publishedAt;
        this.infoGb = infoGb;
        this.classCd = classCd;
        this.className = className;
        this.source = source;
        this.nationCd = nationCd;
        this.exchangeCd = exchangeCd;
        this.symbol = symbol;
        this.symbolName = symbolName;
        this.title = title;
    }
}
