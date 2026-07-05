package com.aaa.collector.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 데이터 워터마크 관측성 설정 (SPEC-OBSV-WATERMARK-001).
 *
 * <p>{@code aaa.watermark.krx-calendar-lookback-days} — {@code MarketSessionGateRefresher}가 KRX
 * 캘린더를 후방 조회할 기준일 오프셋(REQ-WM-007). 기본값 14일 — KRX 최장 연속 휴장(명절+주말)을 상회하는 마진(MI-04).
 *
 * <p>{@code aaa.watermark.resync.enabled} — 하루 1회 DB {@code MAX}를 재조회해 워터마크를 재동기화할지 여부(REQ-WM-004,
 * 운영자 수동 TRUNCATE/재구축 후 자가치유 목적). 기본값 {@code false}.
 */
@ConfigurationProperties(prefix = "aaa.watermark")
public class WatermarkProperties {

    /** KRX 캘린더 후방 조회 lookback 일수(REQ-WM-007). 기본값 14. */
    private int krxCalendarLookbackDays = 14;

    /** 일 1회 재동기화 옵션(REQ-WM-004). */
    private Resync resync = new Resync();

    public int getKrxCalendarLookbackDays() {
        return krxCalendarLookbackDays;
    }

    public void setKrxCalendarLookbackDays(int krxCalendarLookbackDays) {
        this.krxCalendarLookbackDays = krxCalendarLookbackDays;
    }

    public Resync getResync() {
        // 방어적 복사 — EI_EXPOSE_REP 방지 (SpotBugs)
        Resync copy = new Resync();
        copy.setEnabled(resync.isEnabled());
        return copy;
    }

    public void setResync(Resync resync) {
        // 방어적 복사 — EI_EXPOSE_REP2 방지 (SpotBugs)
        Resync copy = new Resync();
        copy.setEnabled(resync.isEnabled());
        this.resync = copy;
    }

    /** 재동기화 하위 설정. */
    public static class Resync {

        /** 재동기화 활성 여부(REQ-WM-004). 기본값 {@code false}. */
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
