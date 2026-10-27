package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.TickMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code stream:tick:domestic}·{@code stream:tick:overseas} 필드 맵 골든 (SPEC-INFRA-BOOT4-001
 * REQ-BOOT4-015, AC-BOOT4-015).
 *
 * <p>analyzer가 소비하는 틱 스트림 계약이다. Boot 전환 전후로 스트림 키, 필드 이름 집합, 각 값의 문자열 표현이 그대로여야 하므로 필드 맵 전체를 정확히 같은
 * 맵으로 단언한다(필드 추가·누락도 실패).
 */
@DisplayName("stream:tick:* 필드 맵 계약 — 운영 KisTickPublisher 경유")
class KisTickPublisherContractTest {

    private StreamOperations<String, Object, Object> streamOps;
    private KisTickPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        TickMetrics tickMetrics =
                new TickMetrics(new SimpleMeterRegistry(), Clock.systemDefaultZone());
        publisher = new KisTickPublisher(redisTemplate, tickMetrics);
    }

    @SuppressWarnings("unchecked")
    private MapRecord<String, Object, Object> capturePublished() {
        ArgumentCaptor<MapRecord<String, Object, Object>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture(), any(XAddOptions.class));
        return captor.getValue();
    }

    @Test
    @DisplayName("국내 틱 — stream:tick:domestic에 symbol·trId·data·trace_id 4필드만 발행")
    void domesticTickFieldMap() {
        // Arrange
        ParsedTick tick =
                new ParsedTick(
                        "H0STCNT0", "005930", "005930^093015^71500^2^500", true, "trace-dom-1");

        // Act
        publisher.publish(tick);

        // Assert
        MapRecord<String, Object, Object> record = capturePublished();
        assertThat(record.getStream()).isEqualTo("stream:tick:domestic");
        assertThat(record.getValue())
                .isEqualTo(
                        Map.of(
                                "symbol", "005930",
                                "trId", "H0STCNT0",
                                "data", "005930^093015^71500^2^500",
                                "trace_id", "trace-dom-1"));
    }

    @Test
    @DisplayName("해외 틱 — stream:tick:overseas에 symbol·trId·data·trace_id 4필드만 발행")
    void overseasTickFieldMap() {
        // Arrange
        ParsedTick tick =
                new ParsedTick(
                        "HDFSCNT0", "DNASAAPL", "DNASAAPL^AAPL^4^20260619", false, "trace-ovs-1");

        // Act
        publisher.publish(tick);

        // Assert
        MapRecord<String, Object, Object> record = capturePublished();
        assertThat(record.getStream()).isEqualTo("stream:tick:overseas");
        assertThat(record.getValue())
                .isEqualTo(
                        Map.of(
                                "symbol", "DNASAAPL",
                                "trId", "HDFSCNT0",
                                "data", "DNASAAPL^AAPL^4^20260619",
                                "trace_id", "trace-ovs-1"));
    }
}
