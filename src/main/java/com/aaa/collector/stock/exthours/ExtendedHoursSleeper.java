package com.aaa.collector.stock.exthours;

/**
 * 종목 간 Yahoo API 요청 딜레이 추상화 인터페이스 (SPEC-COLLECTOR-EXTHOURS-001 REQ-EXTH-011).
 *
 * <p>프로덕션: {@code Thread.sleep(ms)}. 테스트: no-op 또는 mock으로 교체. Thread.sleep 값 직접 변경이 아닌 인터페이스 주입으로
 * 테스트 가능성을 보장한다.
 */
@FunctionalInterface
public interface ExtendedHoursSleeper {

    void sleep(long ms) throws InterruptedException;

    static ExtendedHoursSleeper noOp() {
        return ms -> {};
    }
}
