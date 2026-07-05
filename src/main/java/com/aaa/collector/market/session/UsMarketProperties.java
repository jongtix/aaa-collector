package com.aaa.collector.market.session;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 미국 시장 게이트 설정 (SPEC-COLLECTOR-USMKT-001 REQ-002).
 *
 * <p>{@code aaa.us-market.extra-holidays} — 알고리즘 결과에 추가 병합할 오버라이드 날짜 목록. 빈 목록이면 정상 동작한다(REQ-002).
 *
 * <p>{@code aaa.us-market.early-close-days} — 반일(조기폐장) 날짜 목록(연 ~3일: 7/3·Black Friday·12/24 수준).
 * {@code extra-holidays}(전일 휴장)와 별개 목록이다(SPEC-OBSV-WATERMARK-001 REQ-WM-030).
 */
@ConfigurationProperties(prefix = "aaa.us-market")
public class UsMarketProperties {

    /**
     * NYSE 표준 알고리즘 외 추가 오버라이드 휴장일 목록.
     *
     * <p>기본값: 빈 목록 — 알고리즘 결과만으로 Set 구성.
     */
    private List<LocalDate> extraHolidays = new ArrayList<>();

    /**
     * 반일(조기폐장, 13:00 ET) 날짜 목록(REQ-WM-030). 기본값: 빈 목록.
     *
     * <p>{@code extraHolidays}(전일 휴장)와 별개 — 이 목록의 날짜는 13:00 ET까지는 정상 개장이며 이후 폐장한다.
     */
    private List<LocalDate> earlyCloseDays = new ArrayList<>();

    /** 방어적 복사본 반환 — EI_EXPOSE_REP 방지. */
    public List<LocalDate> getExtraHolidays() {
        return Collections.unmodifiableList(extraHolidays);
    }

    /** 방어적 복사 저장 — EI_EXPOSE_REP2 방지. */
    public void setExtraHolidays(List<LocalDate> extraHolidays) {
        this.extraHolidays = new ArrayList<>(extraHolidays);
    }

    /** 방어적 복사본 반환 — EI_EXPOSE_REP 방지. */
    public List<LocalDate> getEarlyCloseDays() {
        return Collections.unmodifiableList(earlyCloseDays);
    }

    /** 방어적 복사 저장 — EI_EXPOSE_REP2 방지. */
    public void setEarlyCloseDays(List<LocalDate> earlyCloseDays) {
        this.earlyCloseDays = new ArrayList<>(earlyCloseDays);
    }
}
