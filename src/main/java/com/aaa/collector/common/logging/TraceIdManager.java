package com.aaa.collector.common.logging;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * trace_id MDC 관리 유틸리티 클래스.
 *
 * <p>Virtual Threads 환경에서 MDC는 부모 스레드에서 자식 스레드로 상속되지 않는다. 자식 스레드에서 trace_id가 필요하면 별도로 set()을 호출해야
 * 한다.
 *
 * <p>모든 메서드는 stateless이며 synchronized를 사용하지 않는다.
 */
public final class TraceIdManager {

    /** MDC trace_id 키 이름. Redis Streams 전파 시 동일한 키를 사용한다. */
    public static final String MDC_KEY_TRACE_ID = "trace_id";

    /** UUID 문자열 표준 길이 (하이픈 포함). 정규식 연산 전 빠른 탈출 조건으로 사용한다. */
    private static final int UUID_V4_LENGTH = 36;

    private static final Pattern UUID_V4_PATTERN =
            Pattern.compile(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private TraceIdManager() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 새 traceId를 생성하고 MDC에 설정한다.
     *
     * @return 생성된 traceId (Redis Streams 전파용)
     */
    public static String generate() {
        String traceId = UUID.randomUUID().toString();
        SafeMdc.put(MDC_KEY_TRACE_ID, traceId);
        return traceId;
    }

    /**
     * 외부에서 전파된 traceId를 MDC에 설정한다.
     *
     * <p>null이거나 UUID v4 형식이 아니면(blank, 잘못된 형식 포함) 신규 traceId를 생성한다.
     *
     * @param traceId 외부 전파 traceId
     */
    public static void set(String traceId) {
        if (traceId == null
                || traceId.length() != UUID_V4_LENGTH
                || !UUID_V4_PATTERN.matcher(traceId).matches()) {
            generate();
            return;
        }
        SafeMdc.put(MDC_KEY_TRACE_ID, traceId);
    }

    /**
     * MDC에서 traceId를 제거한다.
     *
     * <p>MDC.clear()가 아닌 MDC.remove()를 사용하여 다른 MDC 키를 보존한다.
     */
    public static void clear() {
        SafeMdc.remove(MDC_KEY_TRACE_ID);
    }

    /**
     * 현재 MDC에 설정된 traceId를 반환한다.
     *
     * @return traceId, MDC에 없으면 null
     */
    public static String current() {
        return SafeMdc.get(MDC_KEY_TRACE_ID);
    }
}
