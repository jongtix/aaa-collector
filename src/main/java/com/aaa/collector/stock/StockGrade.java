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
import java.time.ZonedDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;

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
    private String grade;

    @Column(name = "graded_at")
    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE_UTC)
    private ZonedDateTime gradedAt;

    @Builder
    private StockGrade(Stock stock, String grade, ZonedDateTime gradedAt) {
        super();
        this.stock = stock;
        this.grade = grade;
        this.gradedAt = gradedAt;
    }

    /**
     * 등급과 등급 산정 시각을 갱신한다.
     *
     * <p>{@code grade}와 {@code gradedAt}만 변경되며, 다른 필드는 변경되지 않는다 (REQ-011).
     *
     * @param grade 새 등급 (A/B/C/F)
     * @param gradedAt 등급 산정 시각 (KST)
     */
    // @MX:NOTE: [AUTO] grade, gradedAt 두 필드만 변경 — stock, id, createdAt 등 나머지 필드는 불변 (REQ-011)
    public void updateGrade(String grade, ZonedDateTime gradedAt) {
        this.grade = grade;
        this.gradedAt = gradedAt;
    }
}
