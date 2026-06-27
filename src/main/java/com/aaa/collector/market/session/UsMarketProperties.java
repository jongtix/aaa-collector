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
 */
@ConfigurationProperties(prefix = "aaa.us-market")
public class UsMarketProperties {

    /**
     * NYSE 표준 알고리즘 외 추가 오버라이드 휴장일 목록.
     *
     * <p>기본값: 빈 목록 — 알고리즘 결과만으로 Set 구성.
     */
    private List<LocalDate> extraHolidays = new ArrayList<>();

    /** 방어적 복사본 반환 — EI_EXPOSE_REP 방지. */
    public List<LocalDate> getExtraHolidays() {
        return Collections.unmodifiableList(extraHolidays);
    }

    /** 방어적 복사 저장 — EI_EXPOSE_REP2 방지. */
    public void setExtraHolidays(List<LocalDate> extraHolidays) {
        this.extraHolidays = new ArrayList<>(extraHolidays);
    }
}
