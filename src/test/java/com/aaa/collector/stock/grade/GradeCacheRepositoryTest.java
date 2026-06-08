package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("GradeCacheRepository 단위 테스트")
class GradeCacheRepositoryTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private GradeCacheRepository repository;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repository = new GradeCacheRepository(redisTemplate, objectMapper);
    }

    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Nested
    @DisplayName("save — Redis에 등급 저장")
    class SaveGrade {

        @Test
        @DisplayName("정상 저장 — cache:grade:{symbol} 키로 TTL 없이 set 호출")
        void save_normalGrade_callsSetWithoutTtl() {
            // Arrange
            stubOpsForValue();
            ZonedDateTime gradedAt = ZonedDateTime.of(2026, 6, 7, 9, 0, 0, 0, KST);

            // Act
            repository.save("005930", Grade.A, gradedAt);

            // Assert
            verify(redisTemplate.opsForValue())
                    .set(
                            eq("cache:grade:005930"),
                            org.mockito.ArgumentMatchers.contains("\"grade\":\"A\""));
        }

        @Test
        @DisplayName("JSON에 grade 필드가 포함된다")
        void save_jsonContainsGradeField() {
            // Arrange
            stubOpsForValue();
            ZonedDateTime gradedAt = ZonedDateTime.of(2026, 6, 7, 9, 0, 0, 0, KST);
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

            // Act
            repository.save("000660", Grade.B, gradedAt);

            // Assert
            verify(valueOps).set(eq("cache:grade:000660"), captor.capture());
            assertThat(captor.getValue()).contains("\"grade\":\"B\"");
        }

        @Test
        @DisplayName("JSON에 gradedAt 필드가 포함된다")
        void save_jsonContainsGradedAtField() {
            // Arrange
            stubOpsForValue();
            ZonedDateTime gradedAt = ZonedDateTime.of(2026, 6, 7, 9, 0, 0, 0, KST);
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

            // Act
            repository.save("035420", Grade.C, gradedAt);

            // Assert
            verify(valueOps).set(eq("cache:grade:035420"), captor.capture());
            assertThat(captor.getValue()).contains("gradedAt");
        }

        @Test
        @DisplayName("Redis 예외 발생 시 — warn 로그만 남기고 예외 전파하지 않음 (non-fatal)")
        void save_redisThrows_noExceptionPropagated() {
            // Arrange
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            doThrow(new RuntimeException("Redis 연결 실패"))
                    .when(valueOps)
                    .set(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

            // Act & Assert
            assertThatNoException()
                    .isThrownBy(() -> repository.save("005930", Grade.A, ZonedDateTime.now(KST)));
        }

        @Test
        @DisplayName("직렬화 예외 발생 시 — warn 로그만 남기고 예외 전파하지 않음 (non-fatal)")
        void save_serializationFails_noExceptionPropagated() {
            // Arrange — 직렬화 실패를 유도하는 잘못된 ObjectMapper로 테스트
            ObjectMapper failingMapper = mock(ObjectMapper.class);
            try {
                when(failingMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                        .thenThrow(
                                new com.fasterxml.jackson.core.JsonProcessingException(
                                        "직렬화 실패") {});
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new AssertionError("Test setup failed: could not configure mock mapper", e);
            }
            GradeCacheRepository failingRepo =
                    new GradeCacheRepository(redisTemplate, failingMapper);

            // Act & Assert
            assertThatNoException()
                    .isThrownBy(() -> failingRepo.save("005930", Grade.A, ZonedDateTime.now(KST)));
        }
    }

    private static org.assertj.core.api.AbstractStringAssert<?> assertThat(String actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
