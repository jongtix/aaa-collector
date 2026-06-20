package com.aaa.collector.backfill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 백필 스케줄러·오케스트레이터 설정 프로퍼티 (SPEC-COLLECTOR-BACKFILL-001 T7).
 *
 * <p>{@code aaa.backfill.*} 프리픽스로 바인딩된다. 모든 필드는 기본값을 보유하므로 YAML 미설정 시에도 동작한다.
 *
 * <p>기본값 근거:
 *
 * <ul>
 *   <li>{@code cron}: 02:00 KST — 장 마감(16:00~21:00) 이후 비즈니스 임팩트 최소 시각(AC-7.2 회피 시각대).
 *   <li>{@code perRunStockCap}: 50 — 한 cron 실행에서 처리할 최대 종목 수(KIS rate-limit 보호).
 *   <li>{@code perRunWindowCap}: 100 — 한 cron 실행에서 처리할 최대 윈도우 수(DB 풀 점유 상한 ADR-027).
 *   <li>{@code staleWindowThreshold}: 3 — 그룹 B 연속 무전진 종료 임계(REQ-BACKFILL-014).
 *   <li>{@code spanCalendarDays}: 150 — 그룹 A SPAN 폭(REQ-BACKFILL-013a). 100거래일 충족 여유.
 *   <li>{@code anchorSkipMax}: 10 — investor_trend rt_cd=2 anchor 보정 최대 재시도(REQ-BACKFILL-016).
 * </ul>
 */
@ConfigurationProperties(prefix = "aaa.backfill")
public class BackfillProperties {

    /** cron 표현식 (기본: 02:00 KST 매일). fixedDelay 금지 — Virtual Threads 버그(CLAUDE.md). */
    private String cron = "0 0 2 * * *";

    /** 한 cron 실행에서 처리할 최대 종목 수 (KIS rate-limit 보호, AC-7.2b). */
    private int perRunStockCap = 50;

    /** 한 cron 실행에서 처리할 최대 윈도우 수 (DB 풀 점유 상한, ADR-027, AC-7.2b). */
    private int perRunWindowCap = 100;

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

    public int getPerRunStockCap() {
        return perRunStockCap;
    }

    public void setPerRunStockCap(int perRunStockCap) {
        this.perRunStockCap = perRunStockCap;
    }

    public int getPerRunWindowCap() {
        return perRunWindowCap;
    }

    public void setPerRunWindowCap(int perRunWindowCap) {
        this.perRunWindowCap = perRunWindowCap;
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
