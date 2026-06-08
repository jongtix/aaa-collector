package com.aaa.collector.kis.websocket;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 파싱된 틱을 Redis Streams에 발행. */
@Component
@RequiredArgsConstructor
public class KisTickPublisher {

    private static final String DOMESTIC_STREAM = "stream:tick:domestic";
    private static final String OVERSEAS_STREAM = "stream:tick:overseas";
    private static final long MAX_LEN = 5_000L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 파싱된 틱을 Redis Streams에 발행한다.
     *
     * <p>국내 틱은 {@value DOMESTIC_STREAM}, 해외 틱은 {@value OVERSEAS_STREAM}에 발행된다. XADD MAXLEN ~ 5000
     * 옵션으로 스트림 크기를 제한한다.
     *
     * @param tick 발행할 틱 데이터
     */
    public void publish(ParsedTick tick) {
        String streamKey = tick.isDomestic() ? DOMESTIC_STREAM : OVERSEAS_STREAM;

        Map<String, String> fields =
                Map.of(
                        "symbol", tick.trKey(),
                        "trId", tick.trId(),
                        "data", tick.data(),
                        "trace_id", tick.traceId());

        MapRecord<String, String, String> record = MapRecord.create(streamKey, fields);

        XAddOptions options = XAddOptions.maxlen(MAX_LEN).approximateTrimming(true);

        redisTemplate.opsForStream().add(record, options);
    }
}
