package com.aaa.collector.kis.websocket;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/**
 * 국내·해외 장 운영 시간 판정 컴포넌트.
 *
 * <p>시각 비교는 주입된 {@link Clock}을 기반으로 하여 테스트 시 고정 시각을 사용할 수 있다.
 *
 * <ul>
 *   <li>국내(KRX): 평일 08:55(포함) ~ 15:35(미포함) Asia/Seoul
 *   <li>해외(NYSE/NASDAQ): 평일 09:25(포함) ~ 16:05(미포함) America/New_York — EDT/EST 자동 처리
 * </ul>
 */
@Component
public class KisMarketSchedule {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static final LocalTime DOMESTIC_OPEN = LocalTime.of(8, 55);
    private static final LocalTime DOMESTIC_CLOSE = LocalTime.of(15, 35);

    private static final LocalTime OVERSEAS_OPEN = LocalTime.of(9, 25);
    private static final LocalTime OVERSEAS_CLOSE = LocalTime.of(16, 5);

    private final Clock clock;

    public KisMarketSchedule(Clock clock) {
        this.clock = clock;
    }

    /**
     * 국내 장(KRX)이 열려 있는지 판정한다.
     *
     * <p>평일(월~금) 08:55:00(포함) ~ 15:35:00(미포함) Asia/Seoul 기준.
     *
     * @param now 판정 기준 시각
     * @return 장 운영 중이면 {@code true}
     */
    public boolean isDomesticOpen(ZonedDateTime now) {
        ZonedDateTime kstNow = now.withZoneSameInstant(KST);
        DayOfWeek dow = kstNow.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = kstNow.toLocalTime();
        return !time.isBefore(DOMESTIC_OPEN) && time.isBefore(DOMESTIC_CLOSE);
    }

    /**
     * 해외 장(NYSE/NASDAQ)이 열려 있는지 판정한다.
     *
     * <p>평일(월~금) 09:25:00(포함) ~ 16:05:00(미포함) America/New_York 기준. EDT(UTC-4)와 EST(UTC-5)는 ZoneId
     * 변환으로 자동 처리된다.
     *
     * @param now 판정 기준 시각
     * @return 장 운영 중이면 {@code true}
     */
    public boolean isOverseasOpen(ZonedDateTime now) {
        ZonedDateTime nyNow = now.withZoneSameInstant(NEW_YORK);
        DayOfWeek dow = nyNow.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = nyNow.toLocalTime();
        return !time.isBefore(OVERSEAS_OPEN) && time.isBefore(OVERSEAS_CLOSE);
    }
}
