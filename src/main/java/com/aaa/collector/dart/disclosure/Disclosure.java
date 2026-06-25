package com.aaa.collector.dart.disclosure;

import com.aaa.collector.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DART 공시 메타데이터 엔티티 (SPEC-COLLECTOR-DART-001).
 *
 * <p>OpenDART {@code list.json} 응답 행 1건을 저장한다. {@code rcept_no}(14자리)가 전역 UNIQUE 멱등 키이며, {@code
 * INSERT IGNORE}로만 삽입한다 — {@code ON DUPLICATE KEY UPDATE} 금지(ADR-026, Tier-1).
 *
 * <p>{@code stock_id}(FK)는 폴링 시 응답 {@code stock_code}를 watchlist 역해결로 채운다(REQ-DART-003).
 */
@Entity
@Table(
        name = "disclosures",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_disclosures_rcept_no",
                        columnNames = {"rcept_no"}),
        indexes =
                @Index(name = "idx_disclosures_stock_rcept_dt", columnList = "stock_id, rcept_dt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class Disclosure extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** stocks.id FK — watchlist 역해결로 채운다(REQ-DART-003). */
    @Column(name = "stock_id")
    private final Long stockId;

    /** DART 고유번호 8자리. */
    @Column(name = "corp_code", length = 8)
    private final String corpCode;

    /** DART 응답 stock_code 6자리. */
    @Column(name = "stock_code", length = 6)
    private final String stockCode;

    /** 법인구분 Y/K/N/E. */
    @Column(name = "corp_cls", length = 1)
    private final String corpCls;

    /** 보고서명 — 펀드명 접미로 매우 길 수 있어 512자 여유(REQ-DART-D8). */
    @Column(name = "report_nm", length = 512)
    private final String reportNm;

    /** 접수번호 14자리 — 전역 UNIQUE 멱등 키. */
    @Column(name = "rcept_no", length = 14)
    private final String rceptNo;

    /** 공시 제출인명. */
    @Column(name = "flr_nm", length = 255)
    private final String flrNm;

    /** 접수일자 (YYYYMMDD → DATE). */
    @Column(name = "rcept_dt")
    private final LocalDate rceptDt;

    /** 비고. */
    @Column(name = "rm", length = 255)
    private final String rm;

    /** 공시 유형 — 필터 옵션(REQ-DART-032). */
    @Column(name = "pblntf_ty", length = 16)
    private final String pblntfTy;

    @Builder
    private Disclosure(
            Long stockId,
            String corpCode,
            String stockCode,
            String corpCls,
            String reportNm,
            String rceptNo,
            String flrNm,
            LocalDate rceptDt,
            String rm,
            String pblntfTy) {
        super();
        this.stockId = stockId;
        this.corpCode = corpCode;
        this.stockCode = stockCode;
        this.corpCls = corpCls;
        this.reportNm = reportNm;
        this.rceptNo = rceptNo;
        this.flrNm = flrNm;
        this.rceptDt = rceptDt;
        this.rm = rm;
        this.pblntfTy = pblntfTy;
    }
}
