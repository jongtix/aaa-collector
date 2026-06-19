package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("DailyCompletePublisher 단위 테스트")
class DailyCompletePublisherTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private StreamOperations<String, Object, Object> streamOps;

    private DailyCompletePublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Mockito.when(redisTemplate.opsForStream()).thenReturn(streamOps);
        publisher = new DailyCompletePublisher(redisTemplate);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<MapRecord<String, String, String>> recordCaptor() {
        return ArgumentCaptor.forClass(MapRecord.class);
    }

    @Nested
    @DisplayName("publish — 이벤트 발행")
    class PublishEvent {

        @Test
        @DisplayName("market=domestic 필드 포함 — 국내 발행 회귀 보존 (AC-5 S5-1, AC-EVT-4)")
        void publish_containsDomesticMarketField() {
            // Arrange
            ArgumentCaptor<MapRecord<String, String, String>> captor = recordCaptor();

            // Act
            publisher.publish(new CollectionResult(10, 9, 1), "domestic");

            // Assert
            verify(streamOps).add(captor.capture(), any(XAddOptions.class));
            assertThat(captor.getValue().getValue()).containsEntry("market", "domestic");
        }

        @Test
        @DisplayName("market=overseas 필드 포함 — 해외 발행 (AC-EVT-1, REQ-OVOH-011)")
        void publish_containsOverseasMarketField() {
            // Arrange
            ArgumentCaptor<MapRecord<String, String, String>> captor = recordCaptor();

            // Act
            publisher.publish(new CollectionResult(10, 9, 1), "overseas");

            // Assert
            verify(streamOps).add(captor.capture(), any(XAddOptions.class));
            assertThat(captor.getValue().getValue()).containsEntry("market", "overseas");
        }

        @Test
        @DisplayName("완전성 메타 — 시도/성공/skip 수 포함 (AC-5 S5-3, REQ-BATCH-042)")
        void publish_containsCompletionMeta() {
            // Arrange
            ArgumentCaptor<MapRecord<String, String, String>> captor = recordCaptor();

            // Act
            publisher.publish(new CollectionResult(10, 8, 2), "domestic");

            // Assert
            verify(streamOps).add(captor.capture(), any(XAddOptions.class));
            assertThat(captor.getValue().getValue())
                    .containsEntry("attempted", "10")
                    .containsEntry("succeeded", "8")
                    .containsEntry("skipped", "2");
        }

        @Test
        @DisplayName("전 종목 성공 — skip=0 (AC-5 S5-4)")
        void publish_allSuccess_zeroSkip() {
            ArgumentCaptor<MapRecord<String, String, String>> captor = recordCaptor();

            publisher.publish(new CollectionResult(5, 5, 0), "domestic");

            verify(streamOps).add(captor.capture(), any(XAddOptions.class));
            assertThat(captor.getValue().getValue()).containsEntry("skipped", "0");
        }

        @Test
        @DisplayName("스트림 키 — stream:daily:complete")
        void publish_usesCorrectStreamKey() {
            ArgumentCaptor<MapRecord<String, String, String>> captor = recordCaptor();

            publisher.publish(new CollectionResult(1, 1, 0), "domestic");

            verify(streamOps).add(captor.capture(), any(XAddOptions.class));
            assertThat(captor.getValue().getStream()).isEqualTo("stream:daily:complete");
        }
    }

    @Nested
    @DisplayName("publish — MAXLEN 100 exact (REQ-BATCH-041)")
    class MaxlenExact {

        @Test
        @DisplayName("MAXLEN 100 exact trimming — approximateTrimming false (AC-5 S5-2)")
        void publish_usesExactMaxlen100() {
            // Arrange
            ArgumentCaptor<XAddOptions> optionsCaptor = ArgumentCaptor.forClass(XAddOptions.class);

            // Act
            publisher.publish(new CollectionResult(1, 1, 0), "domestic");

            // Assert
            verify(streamOps).add(any(), optionsCaptor.capture());
            XAddOptions options = optionsCaptor.getValue();
            assertThat(options.getMaxlen()).isEqualTo(100L);
            assertThat(options.isApproximateTrimming()).isFalse();
        }
    }

    @Nested
    @DisplayName("publish — XADD 실패 처리 (REQ-BATCH-043)")
    class XAddFailure {

        @Test
        @DisplayName("XADD 실패 — 예외 흡수, 수집 결과 무효화 안 함")
        void publish_xaddFails_exceptionAbsorbed() {
            // Arrange — QueryTimeoutException extends DataAccessException (Redis timeout)
            doThrow(new QueryTimeoutException("Redis 연결 실패"))
                    .when(streamOps)
                    .add(any(), any(XAddOptions.class));

            // Act & Assert — 예외가 외부로 전파되지 않아야 함
            assertThatCode(() -> publisher.publish(new CollectionResult(5, 4, 1), "domestic"))
                    .doesNotThrowAnyException();
        }
    }
}
