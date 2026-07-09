package com.aaa.collector.backfill;

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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 과거 데이터 백필 진행 상태 엔티티 (SPEC-COLLECTOR-BACKFILL-001 §4).
 *
 * <p>{@code (target_type, target_code, data_table)} 단위로 슬라이딩 윈도우 백필의 진행점·상태를
 * 관리한다(REQ-BACKFILL-005). 시딩(INSERT)은 Tier-1 권한으로 충분하고, 진행점 전진(UPDATE)만 Tier-2 권한이
 * 필요하다(REQ-BACKFILL-007).
 *
 * <p>T1 범위에서는 매핑·리포지토리만 제공하며, 상태 전이/진행점 갱신 로직은 후속 Task(T5/T6)에서 추가한다.
 */
@Entity
@Table(
        name = "backfill_status",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_backfill_status",
                        columnNames = {"target_type", "target_code", "data_table"}),
        indexes = @Index(name = "idx_backfill_status_status", columnList = "status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class BackfillStatus extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 대상 유형. 본 SPEC은 {@code STOCK} 고정 (forward-compat: {@code MACRO}, {@code FX} 등). */
    @Column(name = "target_type", length = 32)
    private final String targetType;

    /** 대상 코드 = 종목 symbol. */
    @Column(name = "target_code", length = 32)
    private final String targetCode;

    /**
     * 대상 데이터 테이블명.
     *
     * <p>{@code daily_ohlcv} / {@code investor_trend} / {@code short_sale_domestic} / {@code
     * credit_balance}.
     */
    @Column(name = "data_table", length = 64)
    private final String dataTable;

    /**
     * 진행 상태 ({@link BackfillStatusType}).
     *
     * <p>{@link BackfillStatusType#PENDING} / {@link BackfillStatusType#IN_PROGRESS} / {@link
     * BackfillStatusType#COMPLETED} / {@link BackfillStatusType#FAILED}. DB 컬럼 값은 {@link
     * BackfillStatusConverter}가 String↔enum 변환을 담당한다(autoApply = true). DB DEFAULT {@code
     * 'PENDING'}은 Flyway DDL이 단독 관리한다.
     */
    @Column(name = "status", length = 16)
    private BackfillStatusType status;

    /** 지금까지 도달한 최소(가장 과거) 거래일 = 재개 anchor. NULL=미착수. */
    @Column(name = "last_collected_date")
    private LocalDate lastCollectedDate;

    /** 그룹 B(공매도·investor·credit) 연속 무전진 횟수(REQ-BACKFILL-014). 전진 시 0 리셋. */
    @Column(name = "stale_count")
    private int staleCount;

    /** 직전 윈도우 응답 행 수. {@code last_collected_date}와 함께 동일하면 클램프 의심(REQ-BACKFILL-014a). */
    @Column(name = "last_row_count")
    private Integer lastRowCount;

    /** 누적 윈도우 시도 횟수(관측용). */
    @Column(name = "attempt_count")
    private int attemptCount;

    /** 마지막 실패 사유(REQ-BACKFILL-030). */
    @Column(name = "last_error", length = 512)
    private String lastError;

    /**
     * 종료 확인 프로브로 검증된 완료 시각(KST). NULL=미검증 (SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-151/-152).
     *
     * <p>검증된 완료({@code verified_at IS NOT NULL})의 {@code last_collected_date}만 그 종목의 신뢰 하한
     * 기준선(baseline)으로 승격된다(REQ-152). 미검증 COMPLETED의 도달 최과거는 오염 가능 증거이므로 기준선이 되지 않는다(REQ-153) —
     * 2026-06-28 재구축 사고처럼 오염 완료가 스스로를 신뢰 기준으로 축복하는 순환 신뢰를 차단한다.
     */
    // @MX:NOTE: [AUTO] 검증된 완료만 신뢰 기준선 — verified_at IS NOT NULL 완료의 last_collected_date만 baseline
    // @MX:REASON: SPEC-COLLECTOR-BACKFILL-010 REQ-152/-153 — 순환 신뢰 차단(2026-06-28 오염 완료 자축 실패 모드)
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-010
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /**
     * 윈도우 수집 성공 후 진행점·상태·stale_count·attempt_count를 갱신한다 (REQ-UPDATEDAT-002, REQ-UPDATEDAT-003).
     *
     * <p>JPA dirty-check 경로 — 관리 엔티티에서 호출해야 {@code @LastModifiedDate}(updated_at)가 발화한다. {@code
     * lastRowCount}가 {@code null}이면 직전값을 유지한다.
     *
     * @param status 새 상태 ({@link BackfillStatusType#IN_PROGRESS} 또는 {@link
     *     BackfillStatusType#COMPLETED})
     * @param lastCollectedDate 이번 윈도우 최소 거래일
     * @param staleCount 새 stale_count (전진 시 0, 무전진 시 현재값+1)
     * @param lastRowCount 이번 윈도우 행 수 (null이면 직전값 유지)
     */
    // @MX:ANCHOR: [AUTO] 백필 진행점 전진 — @Transactional 경계 내 관리 엔티티 상태 변경
    // @MX:REASON: [AUTO] JPA dirty-check 경로 필수 — @LastModifiedDate 발화는 MANAGED 상태에서만 보장. 7개 호출처.
    // @MX:SPEC: SPEC-COLLECTOR-UPDATEDAT-001
    @SuppressWarnings("PMD.NullAssignment") // lastError 초기화 의도적 null 대입 (오류 없음 = null)
    public void advance(
            BackfillStatusType status,
            LocalDate lastCollectedDate,
            int staleCount,
            Integer lastRowCount) {
        this.status = status;
        this.lastCollectedDate = lastCollectedDate;
        this.staleCount = staleCount;
        if (lastRowCount != null) {
            this.lastRowCount = lastRowCount;
        }
        this.attemptCount++;
        this.lastError = null;
    }

    /**
     * 윈도우 수집 실패 시 오류 메시지와 상태를 갱신한다 (REQ-UPDATEDAT-008).
     *
     * <p>JPA dirty-check 경로 — 관리 엔티티에서 호출해야 {@code @LastModifiedDate}(updated_at)가 발화한다. {@code
     * last_collected_date}는 변경하지 않는다.
     *
     * @param status 새 상태 ({@link BackfillStatusType#IN_PROGRESS} 또는 {@link
     *     BackfillStatusType#FAILED})
     * @param lastError 오류 메시지 (최대 512자)
     */
    // @MX:ANCHOR: [AUTO] 백필 오류 상태 기록 — @Transactional / TransactionTemplate 경계 내 관리 엔티티 변경
    // @MX:REASON: [AUTO] JPA dirty-check 경로 필수 — @LastModifiedDate 발화는 MANAGED 상태에서만 보장. 5개 호출처.
    // @MX:SPEC: SPEC-COLLECTOR-UPDATEDAT-001
    public void fail(BackfillStatusType status, String lastError) {
        this.status = status;
        this.lastError = lastError;
        this.attemptCount++;
    }

    /**
     * 종료 확인 프로브로 검증된 완료임을 표시한다 (SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-147/-150/-146a).
     *
     * <p>JPA dirty-check 경로 — {@link #advance(BackfillStatusType, LocalDate, int, Integer)}로 {@code
     * COMPLETED} 전이한 직후 동일 트랜잭션 안에서 호출해 {@code verified_at}을 설정한다. 검증된 완료만 신뢰 기준선으로 승격되므로(REQ-152)
     * 소진이 확인된 완료에만 호출해야 한다.
     *
     * @param verifiedAt 검증 시각(KST 기준)
     */
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-010
    public void markVerified(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    /**
     * 표적 재처리를 위해 진행점·검증 마커·이상 카운터를 초기화한다 (SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-160, §7 재복구 절차
     * 리셋 필드 집합 재사용).
     *
     * <p>{@code MIN(daily_ohlcv.trade_date) < listed_date} 증거로 {@code listed_date}가 하향 보정된 종목의
     * GROUP_A {@code daily_ohlcv} status를 표적 리셋해, 정정된(신뢰 가능해진) floor로 기존 오케스트레이터 cron이 재처리하게 한다.
     * {@code last_collected_date=NULL}까지 반드시 초기화해야 옛 진행점 위 구간을 재방문한다(§7 이음새 손상 교훈). {@code
     * stale_count}도 0으로 되돌려 리셋 후 첫 이상 사이클이 N 사이클 만에 종결되게 한다([R5-MI-01]).
     */
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-010
    @SuppressWarnings("PMD.NullAssignment") // 진행점·검증 마커·오류 의도적 null 초기화 (표적 재처리 리셋)
    public void resetForReprocess() {
        this.status = BackfillStatusType.PENDING;
        this.lastCollectedDate = null;
        this.verifiedAt = null;
        this.staleCount = 0;
        this.lastError = null;
    }

    @Builder
    private BackfillStatus(
            String targetType,
            String targetCode,
            String dataTable,
            BackfillStatusType status,
            LocalDate lastCollectedDate,
            int staleCount,
            Integer lastRowCount,
            int attemptCount,
            String lastError,
            LocalDateTime verifiedAt) {
        super();
        this.targetType = targetType;
        this.targetCode = targetCode;
        this.dataTable = dataTable;
        this.status = status;
        this.lastCollectedDate = lastCollectedDate;
        this.staleCount = staleCount;
        this.lastRowCount = lastRowCount;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
        this.verifiedAt = verifiedAt;
    }
}
