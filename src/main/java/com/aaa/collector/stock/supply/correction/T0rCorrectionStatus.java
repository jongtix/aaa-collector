package com.aaa.collector.stock.supply.correction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * T0R(근본원인 B) 소급 정정 완료 마커 — 단일 행 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 M7/M8,
 * REQ-T0R-043~046).
 *
 * <p>앱은 이 테이블을 SELECT만 한다 — UPDATE 경로를 갖지 않는다(REQ-T0R-043). 오퍼레이터가 (a) {@code short_sale_domestic}
 * 앱 배치와 (b) {@code daily_ohlcv}/{@code investor_trend} 수동 SQL 정정 양쪽 모두 닫히는 창 전체에 대해 완료됐음을 검증한 후,
 * root 권한으로 {@code UPDATE t0r_correction_status SET completed_at = NOW()}를 수동 실행한다(REQ-T0R-046).
 *
 * <p>Note: {@code BaseEntity}를 상속하지 않음 — {@code created_at}/{@code updated_at} 감사 컬럼이 스키마(V46)에 없다
 * ({@code EtfRepresentativeHistory}와 동일 근거).
 */
@Entity
@Table(name = "t0r_correction_status")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class T0rCorrectionStatus {

    /**
     * 이 테이블은 정의상 정확히 1행만 존재한다(V46 시딩 PK). 컬럼 타입이 {@code TINYINT}이므로 Hibernate schema-validation 정합을
     * 위해 Java {@code Byte}로 매핑한다(MySQL TINYINT ↔ Hibernate 기본 JavaType 매핑).
     */
    public static final byte SINGLETON_ID = 1;

    @Id private final Byte id;

    /** 근본원인 B 소급 정정 대상 구간의 종료일. {@code [2026-06-29, 이 값]} 구간이 M7 게이트 대상(REQ-T0R-043). */
    @Column(name = "closing_window_end_date")
    private final LocalDate closingWindowEndDate;

    /** NULL이면 게이트 활성(defer 발생). 오퍼레이터가 root 권한으로 수동 UPDATE — 앱은 UPDATE 경로 없음(REQ-T0R-045/-046). */
    @Column(name = "completed_at")
    private final LocalDateTime completedAt;

    T0rCorrectionStatus(Byte id, LocalDate closingWindowEndDate, LocalDateTime completedAt) {
        this.id = id;
        this.closingWindowEndDate = closingWindowEndDate;
        this.completedAt = completedAt;
    }
}
