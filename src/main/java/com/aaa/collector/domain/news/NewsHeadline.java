package com.aaa.collector.domain.news;

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

/** 뉴스 제목 (KIS 종합시황공시제목). */
@Entity
@Table(
        name = "news_headlines",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_news_headlines_serial",
                        columnNames = {"serial_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class NewsHeadline extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "serial_no", length = 20)
    private final String serialNo;

    @Column(name = "published_at")
    private final LocalDateTime publishedAt;

    @Column(name = "provider_code", length = 1)
    private final String providerCode;

    @Column(name = "title", length = 400)
    private final String title;

    @Column(name = "category_code", length = 8)
    private final String categoryCode;

    @Column(name = "source", length = 20)
    private final String source;

    @Column(name = "stock_code1", length = 9)
    private final String stockCode1;

    @Column(name = "stock_code2", length = 9)
    private final String stockCode2;

    @Column(name = "stock_code3", length = 9)
    private final String stockCode3;

    @Column(name = "stock_code4", length = 9)
    private final String stockCode4;

    @Column(name = "stock_code5", length = 9)
    private final String stockCode5;

    @Builder
    private NewsHeadline(
            String serialNo,
            LocalDateTime publishedAt,
            String providerCode,
            String title,
            String categoryCode,
            String source,
            String stockCode1,
            String stockCode2,
            String stockCode3,
            String stockCode4,
            String stockCode5) {
        super();
        this.serialNo = serialNo;
        this.publishedAt = publishedAt;
        this.providerCode = providerCode;
        this.title = title;
        this.categoryCode = categoryCode;
        this.source = source;
        this.stockCode1 = stockCode1;
        this.stockCode2 = stockCode2;
        this.stockCode3 = stockCode3;
        this.stockCode4 = stockCode4;
        this.stockCode5 = stockCode5;
    }
}
