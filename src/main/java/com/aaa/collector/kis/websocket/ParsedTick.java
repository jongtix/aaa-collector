package com.aaa.collector.kis.websocket;

/** Type A 메시지 파싱 결과. */
public record ParsedTick(
        String trId, String trKey, String data, boolean isDomestic, String traceId) {}
