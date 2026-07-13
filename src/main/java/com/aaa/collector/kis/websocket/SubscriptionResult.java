package com.aaa.collector.kis.websocket;

/**
 * 구독 목록 처리 결과 — 시도 건수 대비 성공 건수(REQ-WSRES-016~018).
 *
 * <p>{@code attempted}는 호출 시점 요청 목록 전체 크기로 정의한다. 세션 포화 등으로 조기 중단되어 실제 전송을 시도하지 못한 항목도 실패로 간주해 분모에
 * 포함한다(예: 10개 중 9번째에서 실패해 10번째가 미시도로 남아도 attempted=10, succeeded=8).
 *
 * @param attempted 시도 건수(요청 목록 전체 크기)
 * @param succeeded 성공 건수
 */
public record SubscriptionResult(int attempted, int succeeded) {}
