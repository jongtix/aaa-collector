package com.aaa.collector.kis;

import io.github.bucket4j.TimeMeter;
import java.util.concurrent.atomic.AtomicLong;

/** 가상 시간 제어를 위한 TimeMeter 구현체. currentTimeMs를 직접 조작할 수 있다. */
class ManualTimeMeter implements TimeMeter {

    private final AtomicLong currentMs;

    ManualTimeMeter(long initialMs) {
        this.currentMs = new AtomicLong(initialMs);
    }

    void advanceMs(long deltaMs) {
        currentMs.addAndGet(deltaMs);
    }

    @Override
    public long currentTimeNanos() {
        return currentMs.get() * 1_000_000L;
    }

    @Override
    public boolean isWallClockBased() {
        return false;
    }
}
