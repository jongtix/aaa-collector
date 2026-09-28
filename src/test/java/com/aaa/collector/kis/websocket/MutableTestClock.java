package com.aaa.collector.kis.websocket;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트가 시각을 명시적으로 전진시키는 가변 시계 — 실제 시간 경과·벽시계에 의존하지 않아 느린 CI 머신에서도 결정적이다.
 *
 * <p>테스트 스레드가 {@link #advance}로만 시각을 바꾸고 같은 스레드가 읽는 용도다(스레드 안전하지 않음).
 */
final class MutableTestClock extends Clock {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private Instant current;

    MutableTestClock(Instant start) {
        super();
        this.current = start;
    }

    void advance(Duration duration) {
        current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return current;
    }
}
