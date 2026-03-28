package com.aaa.collector.common.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@ExtendWith(MockitoExtension.class)
class RedisPingHealthIndicatorTest {

    @Mock private RedisConnectionFactory connectionFactory;

    @Mock private RedisConnection connection;

    @InjectMocks private RedisPingHealthIndicator indicator;

    @Nested
    @DisplayName("health()")
    class HealthMethod {

        @Test
        @DisplayName("PING 성공 — Status UP, detail에 pong 값 포함")
        void ping_success_returnsUpWithPongDetail() {
            // Arrange
            when(connectionFactory.getConnection()).thenReturn(connection);
            when(connection.ping()).thenReturn("PONG");

            // Act
            Health health = indicator.health();

            // Assert
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("ping", "PONG");
        }

        @Test
        @DisplayName("PING 성공 — connection.close() 호출됨")
        void ping_success_closesConnection() {
            // Arrange
            when(connectionFactory.getConnection()).thenReturn(connection);
            when(connection.ping()).thenReturn("PONG");

            // Act
            indicator.health();

            // Assert
            verify(connection).close();
        }

        @Test
        @DisplayName("PING 실패 — Status DOWN, connection.close() 호출됨")
        void ping_failure_returnsDownAndClosesConnection() {
            // Arrange
            when(connectionFactory.getConnection()).thenReturn(connection);
            when(connection.ping()).thenThrow(new RuntimeException("connection refused"));

            // Act
            Health health = indicator.health();

            // Assert
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            verify(connection).close();
        }

        @Test
        @DisplayName("getConnection() 실패 — Status DOWN, connection.close() 미호출")
        void getConnection_failure_returnsDownWithoutClosingConnection() {
            // Arrange
            when(connectionFactory.getConnection())
                    .thenThrow(new RuntimeException("connection pool exhausted"));

            // Act
            Health health = indicator.health();

            // Assert
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            verify(connection, never()).close();
        }
    }
}
