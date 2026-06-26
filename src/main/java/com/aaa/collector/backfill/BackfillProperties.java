package com.aaa.collector.backfill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 백필 스케줄러·오케스트레이터 설정 프로퍼티 (SPEC-COLLECTOR-BACKFILL-001 T7, SPEC-COLLECTOR-BACKFILL-002 T1).
 *
 * <p>{@code aaa.backfill.*} 프리픽스로 바인딩된다. 모든 필드는 기본값을 보유하므로 YAML 미설정 시에도 동작한다.
 *
 * <p>기본값 근거:
 *
 * <ul>
 *   <li>{@code cron}: 02:00 KST — 장 마감(16:00~21:00) 이후 비즈니스 임팩트 최소 시각(AC-7.2 회피 시각대).
 *   <li>{@code perTableCompletionCap}: 10 — 한 테이블 스트림이 한 회차에 완주할 status 슬롯 수 = 테이블별 런타임 바운드 (window
 *       수 아님, KIS rate limit과 무관 — rate는 KisRateLimiter 책임)(REQ-BACKFILL-064).
 *       SPEC-COLLECTOR-BACKFILL-004 도입으로 perRunCompletionCap에서 리네임됨.
 *   <li>{@code maxWindowsPerTarget}: 120 — status당 inner 루프 최대 윈도우 횟수 공통 하드 캡(REQ-BACKFILL-053a).
 *       SPAN 150 달력일 × 120 window ≈ 18,000 달력일 ≈ 49년(~9,000 거래일) 커버 — 최장 상장 종목도 단일 회차 완성 여유. 정상 종료는
 *       그룹별 종료 정책으로 상한 도달 전에 발생한다. GROUP_A·GROUP_B 모두 적용되는 최종 방어선.
 *   <li>{@code staleWindowThreshold}: 3 — 그룹 B 연속 무전진 종료 임계(REQ-BACKFILL-014).
 *   <li>{@code spanCalendarDays}: 150 — 그룹 A SPAN 폭(달력일). 100거래일 충족 여유(REQ-BACKFILL-013a).
 *   <li>{@code anchorSkipMax}: 10 — investor_trend rt_cd=2 anchor 보정 최대 재시도(REQ-BACKFILL-016).
 * </ul>
 */
@ConfigurationProperties(prefix = "aaa.backfill")
public class BackfillProperties {

    /** cron 표현식 (기본: 02:00 KST 매일). fixedDelay 금지 — Virtual Threads 버그(CLAUDE.md). */
    private String cron = "0 0 2 * * *";

    /**
     * 한 테이블 스트림이 한 회차에 완주할 최대 status 슬롯 수 — 테이블별 런타임 바운드 (REQ-BACKFILL-064).
     *
     * <p>캡은 window 수가 아닌 status(종목×데이터테이블) 수 단위다. inner 루프를 개시한 status 1개가 슬롯 1개를 소비한다. KIS rate
     * limit과 무관(rate는 KisRateLimiter 책임). SPEC-COLLECTOR-BACKFILL-004 도입, perRunCompletionCap
     * 리네임(REQ-BACKFILL-064a).
     */
    private int perTableCompletionCap = 10;

    /**
     * status당 inner 루프 최대 윈도우 반복 횟수 — 무한 루프 하드 방어 공통 상한 (REQ-BACKFILL-053a).
     *
     * <p>GROUP_A·GROUP_B 모두 적용되는 최종 방어선. 이 상한에 도달하면 status를 IN_PROGRESS로 남긴 채 다음 cron에서 재개한다. SPAN
     * 150 달력일 × 120 window ≈ 49년 커버.
     */
    private int maxWindowsPerTarget = 120;

    /** 그룹 B 연속 무전진 종료 임계 (REQ-BACKFILL-014). */
    private int staleWindowThreshold = 3;

    /** 그룹 A 기간 윈도우 from-date 폭(달력일). 100거래일 충족 위해 ≥150 (REQ-BACKFILL-013a). */
    private int spanCalendarDays = 150;

    /** 그룹 B rt_cd=2 anchor 보정 최대 시도 횟수 (REQ-BACKFILL-016). */
    private int anchorSkipMax = 10;

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getPerTableCompletionCap() {
        return perTableCompletionCap;
    }

    public void setPerTableCompletionCap(int perTableCompletionCap) {
        this.perTableCompletionCap = perTableCompletionCap;
    }

    public int getMaxWindowsPerTarget() {
        return maxWindowsPerTarget;
    }

    public void setMaxWindowsPerTarget(int maxWindowsPerTarget) {
        this.maxWindowsPerTarget = maxWindowsPerTarget;
    }

    public int getStaleWindowThreshold() {
        return staleWindowThreshold;
    }

    public void setStaleWindowThreshold(int staleWindowThreshold) {
        this.staleWindowThreshold = staleWindowThreshold;
    }

    public int getSpanCalendarDays() {
        return spanCalendarDays;
    }

    public void setSpanCalendarDays(int spanCalendarDays) {
        this.spanCalendarDays = spanCalendarDays;
    }

    public int getAnchorSkipMax() {
        return anchorSkipMax;
    }

    public void setAnchorSkipMax(int anchorSkipMax) {
        this.anchorSkipMax = anchorSkipMax;
    }
}
