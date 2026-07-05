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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
@DisplayName("KisTickPublisher")
class KisTickPublisherTest {

    @Mock private StringRedisTemplate redisTemplate;

    // StringRedisTemplate.opsForStream()은 StreamOperations<String,Object,Object> 반환
    private StreamOperations<String, Object, Object> streamOps;

    private SimpleMeterRegistry meterRegistry;
    private KisTickPublisher publisher;

    @BeforeEach
    void setUp() {
        streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        meterRegistry = new SimpleMeterRegistry();
        TickMetrics tickMetrics = new TickMetrics(meterRegistry, Clock.systemDefaultZone());
        publisher = new KisTickPublisher(redisTemplate, tickMetrics);
    }

    @Nested
    @DisplayName("publish — 국내 틱")
    class DomesticTick {

        @Test
        @DisplayName("isDomestic=true → stream:tick:domestic 스트림에 발행")
        void publishesToDomesticStream() {
            // Arrange
            ParsedTick tick = new ParsedTick("H0STCNT0", "005930", "005930|...", true, "trace-001");

            // Act
            publisher.publish(tick);

            // Assert
            ArgumentCaptor<MapRecord<String, Object, Object>> recordCaptor =
                    ArgumentCaptor.forClass(MapRecord.class);
            verify(streamOps).add(recordCaptor.capture(), any(XAddOptions.class));
            assertThat(recordCaptor.getValue().getStream()).isEqualTo("stream:tick:domestic");
        }

        @Test
        @DisplayName("국내 틱 — symbol, trId, data, trace_id 4개 필드 포함")
        void containsAllFourFields() {
            // Arrange
            ParsedTick tick =
                    new ParsedTick("H0STCNT0", "005930", "rawData|100|200", true, "trace-abc");

            // Act
            publisher.publish(tick);

            // Assert
            ArgumentCaptor<MapRecord<String, Object, Object>> recordCaptor =
                    ArgumentCaptor.forClass(MapRecord.class);
            verify(streamOps).add(recordCaptor.capture(), any(XAddOptions.class));

            Map<Object, Object> fields = recordCaptor.getValue().getValue();
            assertThat(fields).containsEntry("symbol", "005930");
            assertThat(fields).containsEntry("trId", "H0STCNT0");
            assertThat(fields).containsEntry("data", "rawData|100|200");
            assertThat(fields).containsEntry("trace_id", "trace-abc");
        }

        @Test
        @DisplayName("국내 틱 발행 시 TickMetrics 수신 카운터 증가 (REQ-OBSV-010)")
        void recordsTickMetricForDomestic() {
            // Arrange
            ParsedTick tick = new ParsedTick("H0STCNT0", "005930", "data", true, "trace-001");

            // Act
            publisher.publish(tick);

            // Assert
            double count =
                    meterRegistry
                            .get("aaa_collector_tick_received_total")
                            .tags("symbol", "005930", "market", "domestic")
                            .counter()
                            .count();
            assertThat(count).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("publish — 해외 틱")
    class OverseasTick {

        @Test
        @DisplayName("isDomestic=false → stream:tick:overseas 스트림에 발행")
        void publishesToOverseasStream() {
            // Arrange
            ParsedTick tick = new ParsedTick("HDFSCNT0", "AAPL", "AAPL|...", false, "trace-002");

            // Act
            publisher.publish(tick);

            // Assert
            ArgumentCaptor<MapRecord<String, Object, Object>> recordCaptor =
                    ArgumentCaptor.forClass(MapRecord.class);
            verify(streamOps).add(recordCaptor.capture(), any(XAddOptions.class));
            assertThat(recordCaptor.getValue().getStream()).isEqualTo("stream:tick:overseas");
        }

        @Test
        @DisplayName("해외 틱은 symbol 라벨 없는 시계열을 생성하지 않는다 (카디널리티 가드, REQ-OBSV-012)")
        void doesNotRecordSymbolTaggedMetricForOverseas() {
            // Arrange
            ParsedTick tick = new ParsedTick("HDFSCNT0", "AAPL", "AAPL|...", false, "trace-002");

            // Act
            publisher.publish(tick);

            // Assert — symbol=AAPL 태그의 시계열은 생성되지 않음
            boolean hasSymbolTag =
                    meterRegistry.find("aaa_collector_tick_received_total").counters().stream()
                            .anyMatch(c -> c.getId().getTag("symbol") != null);
            assertThat(hasSymbolTag).isFalse();
        }

        @Test
        @DisplayName(
                "해외 틱은 market=\"overseas\" 단일 집계 시계열로 계측된다 (SPEC-OBSV-WATERMARK-001 REQ-WM-015)")
        void recordsOverseasAggregateMetric() {
            // Arrange
            ParsedTick tick = new ParsedTick("HDFSCNT0", "AAPL", "AAPL|...", false, "trace-002");

            // Act
            publisher.publish(tick);

            // Assert
            double count =
                    meterRegistry
                            .get("aaa_collector_tick_received_total")
                            .tags("market", "overseas")
                            .counter()
                            .count();
            assertThat(count).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("publish — XAddOptions MAXLEN 설정")
    class XAddOptionsSpec {

        @Test
        @DisplayName("maxlen=5000, approximate trimming이 적용된 XAddOptions로 발행")
        void usesApproximateTrimmingWithMaxlen5000() {
            // Arrange
            ParsedTick tick = new ParsedTick("H0STCNT0", "005930", "data", true, "trace-003");

            // Act
            publisher.publish(tick);

            // Assert
            ArgumentCaptor<XAddOptions> optionsCaptor = ArgumentCaptor.forClass(XAddOptions.class);
            verify(streamOps).add(any(MapRecord.class), optionsCaptor.capture());
            XAddOptions options = optionsCaptor.getValue();
            assertThat(options.getMaxlen()).isEqualTo(5000L);
            assertThat(options.isApproximateTrimming()).isTrue();
        }
    }
}
