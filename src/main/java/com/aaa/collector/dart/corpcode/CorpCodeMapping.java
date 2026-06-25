package com.aaa.collector.dart.corpcode;

import com.aaa.collector.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DART corp_code ↔ stock_code 매핑 캐시 엔티티 (SPEC-COLLECTOR-DART-001 방안 A).
 *
 * <p>corpCode.xml에서 추출한 상장사({@code stock_code} 비어 있지 않은 항목)만 적재한다(REQ-DART-002). 백필 전용 lookup — 폴링은
 * 이 매핑을 사용하지 않는다.
 *
 * <p>{@code stock_code}(6자리)가 UNIQUE 멱등 키이며, {@code INSERT IGNORE}로만 삽입한다(Tier-1, ADR-026).
 */
@Entity
@Table(
        name = "corp_code_mapping",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_corp_code_mapping_stock_code",
                        columnNames = {"stock_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class CorpCodeMapping extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** DART stock_code 6자리 — UNIQUE 멱등 키. */
    @Column(name = "stock_code", length = 6)
    private final String stockCode;

    /** DART 고유번호 8자리. */
    @Column(name = "corp_code", length = 8)
    private final String corpCode;

    /** 기업명. */
    @Column(name = "corp_name", length = 255)
    private final String corpName;

    /** corpCode.xml의 modify_date — 최종 변경일. */
    @Column(name = "modify_date")
    private final LocalDate modifyDate;

    @Builder
    private CorpCodeMapping(
            String stockCode, String corpCode, String corpName, LocalDate modifyDate) {
        super();
        this.stockCode = stockCode;
        this.corpCode = corpCode;
        this.corpName = corpName;
        this.modifyDate = modifyDate;
    }
}
