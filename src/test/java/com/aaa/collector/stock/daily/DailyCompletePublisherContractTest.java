package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code stream:daily:complete} 필드 맵 골든 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-015, AC-BOOT4-015).
 *
 * <p>analyzer가 시장별 추론을 분기하는 완료 이벤트 계약이다. 필드 맵 전체를 정확히 같은 맵으로 단언해 필드 이름 집합과 숫자 값의 문자열 표현을 함께 고정한다.
 */
@DisplayName("stream:daily:complete 필드 맵 계약 — 운영 DailyCompletePublisher 경유")
class DailyCompletePublisherContractTest {

    private StreamOperations<String, Object, Object> streamOps;
    private DailyCompletePublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        publisher = new DailyCompletePublisher(redisTemplate);
    }

    @ParameterizedTest(name = "market={0}")
    @ValueSource(strings = {"domestic", "overseas"})
    @DisplayName("market·attempted·succeeded·skipped 4필드만 문자열로 발행")
    @SuppressWarnings("unchecked")
    void completeEventFieldMap(String market) {
        // Arrange
        ArgumentCaptor<MapRecord<String, Object, Object>> captor =
                ArgumentCaptor.forClass(MapRecord.class);

        // Act
        publisher.publish(new CollectionResult(2_481, 2_470, 11), market);

        // Assert
        verify(streamOps).add(captor.capture(), any(XAddOptions.class));
        assertThat(captor.getValue().getStream()).isEqualTo("stream:daily:complete");
        assertThat(captor.getValue().getValue())
                .isEqualTo(
                        Map.of(
                                "market", market,
                                "attempted", "2481",
                                "succeeded", "2470",
                                "skipped", "11"));
    }
}
