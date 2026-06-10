package com.aaa.collector.kis.batch;

import com.aaa.collector.kis.KisApiResponse;
import java.util.Optional;

/**
 * 배치 REST 호출 결과.
 *
 * <p>성공 시 응답 객체를 보유하고, EGW00201 재시도 소진(graceful skip) 시 skip 신호를 보유한다.
 *
 * @param <T> 응답 타입
 */
public final class BatchResult<T extends KisApiResponse> {

    private final T value;
    private final String skippedSymbol;

    private BatchResult(T value, String skippedSymbol) {
        this.value = value;
        this.skippedSymbol = skippedSymbol;
    }

    /** 성공 결과를 생성한다. */
    public static <T extends KisApiResponse> BatchResult<T> success(T value) {
        return new BatchResult<>(value, null);
    }

    /** EGW00201 재시도 소진 또는 graceful skip 결과를 생성한다. */
    public static <T extends KisApiResponse> BatchResult<T> skip(String symbol) {
        return new BatchResult<>(null, symbol);
    }

    /**
     * @return 호출 성공 여부
     */
    public boolean isSuccess() {
        return value != null;
    }

    /**
     * @return 성공 시 응답 객체 (skip 시 비어있음)
     */
    public Optional<T> getValue() {
        return Optional.ofNullable(value);
    }

    /**
     * @return skip된 종목 코드 (성공 시 비어있음)
     */
    public Optional<String> getSkippedSymbol() {
        return Optional.ofNullable(skippedSymbol);
    }
}
