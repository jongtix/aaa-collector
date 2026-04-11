package com.aaa.collector.stock;

import com.aaa.collector.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 종목 등급 (A/B/C/F). 종목당 현재 등급 1건 유지, 변경 시 UPDATE. */
@Entity
@Table(
        name = "stock_grades",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_stock_grades_stock",
                        columnNames = {"stock_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class StockGrade extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_stock_grades_stock"))
    private final Stock stock;

    @Column(name = "grade", length = 1)
    private final String grade;

    @Column(name = "graded_at")
    private final LocalDateTime gradedAt;

    @Builder
    private StockGrade(Stock stock, String grade, LocalDateTime gradedAt) {
        super();
        this.stock = stock;
        this.grade = grade;
        this.gradedAt = gradedAt;
    }
}
