package com.aaa.collector.domain.macro;

import com.aaa.collector.common.entity.BaseEntity;
import com.aaa.collector.domain.macro.enums.MacroSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 거시경제 지표 (KIS 금리/증시자금, ECOS, FRED). */
@Entity
@Table(
        name = "macro_indicators",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_macro_indicators",
                        columnNames = {"indicator_code", "trade_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class MacroIndicator extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "indicator_code", length = 32)
    private final String indicatorCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10)
    private final MacroSource source;

    @Column(name = "trade_date")
    private final LocalDate tradeDate;

    @Column(name = "value", precision = 18, scale = 8)
    private final BigDecimal value;

    @Builder
    private MacroIndicator(
            String indicatorCode, MacroSource source, LocalDate tradeDate, BigDecimal value) {
        super();
        this.indicatorCode = indicatorCode;
        this.source = source;
        this.tradeDate = tradeDate;
        this.value = value;
    }
}
