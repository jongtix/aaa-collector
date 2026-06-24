package com.aaa.collector.kis.websocket;

import com.aaa.collector.observability.TickMetrics;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 파싱된 틱을 Redis Streams에 발행. */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisTickPublisher {

    private static final String DOMESTIC_STREAM = "stream:tick:domestic";
    private static final String OVERSEAS_STREAM = "stream:tick:overseas";
    private static final long MAX_LEN = 5_000L;

    private final StringRedisTemplate redisTemplate;
    private final TickMetrics tickMetrics;

    /**
     * 파싱된 틱을 Redis Streams에 발행한다.
     *
     * <p>국내 틱은 {@value DOMESTIC_STREAM}, 해외 틱은 {@value OVERSEAS_STREAM}에 발행된다. XADD MAXLEN ~ 5000
     * 옵션으로 스트림 크기를 제한한다.
     *
     * @param tick 발행할 틱 데이터
     */
    public void publish(ParsedTick tick) {
        // REQ-OBSV-010/012: 국내 구독 종목 틱만 종목 단위 계측 — 해외 틱은 라벨 미생성(카디널리티 가드)
        if (tick.isDomestic()) {
            tickMetrics.recordDomesticTick(tick.trKey());
        }

        String streamKey = tick.isDomestic() ? DOMESTIC_STREAM : OVERSEAS_STREAM;

        Map<String, String> fields =
                Map.of(
                        "symbol", tick.trKey(),
                        "trId", tick.trId(),
                        "data", tick.data(),
                        "trace_id", tick.traceId());

        MapRecord<String, String, String> record = MapRecord.create(streamKey, fields);

        XAddOptions options = XAddOptions.maxlen(MAX_LEN).approximateTrimming(true);

        try {
            redisTemplate.opsForStream().add(record, options);
        } catch (IllegalStateException e) {
            // Lettuce가 destroy된 후 WebSocket 틱이 도착하면 발생하는 shutdown 경쟁 조건.
            // SmartLifecycle.stop()으로 WebSocket을 먼저 닫도록 했으나 방어적으로 tick drop 처리.
            log.warn("[tick-publisher] Redis 연결 불가 — tick drop: {}", e.getMessage());
        }
    }
}
