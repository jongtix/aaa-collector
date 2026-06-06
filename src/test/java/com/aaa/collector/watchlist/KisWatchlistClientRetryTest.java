package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.token.KisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.client.RestClientException;

/**
 * @Retryable 재시도 횟수 검증 테스트.
 *
 * <p>Spring AOP 프록시가 활성화된 상태에서 비즈니스 오류와 일시적 오류에 대한 재시도 동작을 검증한다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = KisWatchlistClientRetryTest.Config.class)
class KisWatchlistClientRetryTest {

    @Autowired KisWatchlistClient kisWatchlistClient;
    @Autowired KisApiExecutor kisApiExecutor;

    @Configuration
    @EnableRetry
    static class Config {
        @Bean
        KisApiExecutor kisApiExecutor() {
            return Mockito.mock(KisApiExecutor.class);
        }

        @Bean
        KisProperties kisProperties() {
            return Mockito.mock(KisProperties.class);
        }

        @Bean
        KisWatchlistClient kisWatchlistClient(
                KisApiExecutor kisApiExecutor, KisProperties kisProperties) {
            return new KisWatchlistClient(kisApiExecutor, kisProperties);
        }
    }

    @BeforeEach
    void setUp() {
        Mockito.reset(kisApiExecutor);
    }

    @Nested
    @DisplayName("fetchGroups — 재시도 정책")
    class FetchGroupsRetry {

        @Test
        @DisplayName("비즈니스 오류(KisApiBusinessException) — 재시도 없이 즉시 전파 (API 1회 호출)")
        void fetchGroups_businessError_noRetry() {
            // Arrange
            when(kisApiExecutor.executeGet(any(), any(), any()))
                    .thenThrow(new KisApiBusinessException("1", "EGW00123", "인증 오류"));

            // Act & Assert
            assertThatThrownBy(kisWatchlistClient::fetchGroups)
                    .isInstanceOf(KisApiBusinessException.class);

            verify(kisApiExecutor, times(1)).executeGet(any(), any(), any());
        }

        @Test
        @DisplayName("일시적 오류(RestClientException) — maxAttempts=3 재시도")
        void fetchGroups_restClientException_retriesMaxAttempts() {
            // Arrange
            when(kisApiExecutor.executeGet(any(), any(), any()))
                    .thenThrow(new RestClientException("서버 오류"));

            // Act & Assert
            assertThatThrownBy(kisWatchlistClient::fetchGroups)
                    .isInstanceOf(RestClientException.class);

            verify(kisApiExecutor, times(3)).executeGet(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("fetchStocksByGroup — 재시도 정책")
    class FetchStocksByGroupRetry {

        @Test
        @DisplayName("비즈니스 오류(KisApiBusinessException) — 재시도 없이 즉시 전파 (API 1회 호출)")
        void fetchStocksByGroup_businessError_noRetry() {
            // Arrange
            when(kisApiExecutor.executeGet(any(), any(), any()))
                    .thenThrow(new KisApiBusinessException("1", "EGW00456", "그룹코드 오류"));

            // Act & Assert
            assertThatThrownBy(() -> kisWatchlistClient.fetchStocksByGroup("001"))
                    .isInstanceOf(KisApiBusinessException.class);

            verify(kisApiExecutor, times(1)).executeGet(any(), any(), any());
        }

        @Test
        @DisplayName("일시적 오류(RestClientException) — maxAttempts=3 재시도")
        void fetchStocksByGroup_restClientException_retriesMaxAttempts() {
            // Arrange
            when(kisApiExecutor.executeGet(any(), any(), any()))
                    .thenThrow(new RestClientException("서버 오류"));

            // Act & Assert
            assertThatThrownBy(() -> kisWatchlistClient.fetchStocksByGroup("001"))
                    .isInstanceOf(RestClientException.class);

            verify(kisApiExecutor, times(3)).executeGet(any(), any(), any());
        }
    }
}
