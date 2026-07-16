package com.aaa.collector.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("CoverageRatioRepository — 데이터 커버리지 비율 Redis 저장/조회 (REQ-WSR4-001)")
class CoverageRatioRepositoryTest {

    private static final String KEY_PREFIX = "observability:collector:data-coverage-ratio:";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private CoverageRatioRepository repository() {
        return new CoverageRatioRepository(redisTemplate);
    }

    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("save — TTL 없이 opsForValue().set(key, 비율문자열) 호출 (REQ-WSR4-001)")
    void save_callsSetWithoutTtl() {
        stubOpsForValue();

        repository().save("daily-ohlcv-krx", 0.955);

        verify(valueOps).set(KEY_PREFIX + "daily-ohlcv-krx", "0.955");
    }

    @Test
    @DisplayName("find — 부재 키는 Optional.empty() 반환")
    void find_whenAbsent_returnsEmpty() {
        stubOpsForValue();
        when(valueOps.get(KEY_PREFIX + "daily-ohlcv-krx")).thenReturn(null);

        Optional<Double> result = repository().find("daily-ohlcv-krx");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("find — 존재하는 값은 double로 환원해 반환")
    void find_whenPresent_returnsDouble() {
        stubOpsForValue();
        when(valueOps.get(KEY_PREFIX + "daily-ohlcv-us")).thenReturn("0.5");

        Optional<Double> result = repository().find("daily-ohlcv-us");

        assertThat(result).contains(0.5);
    }

    @Test
    @DisplayName("find — 비숫자(손상) 값이면 예외 없이 Optional.empty() 반환 (REQ-WSR4-001)")
    void find_whenCorrupted_returnsEmpty() {
        stubOpsForValue();
        when(valueOps.get(KEY_PREFIX + "daily-ohlcv-krx")).thenReturn("not-a-number");

        Optional<Double> result = repository().find("daily-ohlcv-krx");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("save/find 라운드트립 — write 키와 read 키가 동일 파생 소스 (REQ-WSR4-001)")
    void saveAndFind_useSameDerivedKey() {
        stubOpsForValue();
        CoverageRatioRepository repository = repository();

        repository.save("daily-ohlcv-us", 0.87);
        ArgumentCaptor<String> writeKey = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(writeKey.capture(), eq("0.87"));

        when(valueOps.get(writeKey.getValue())).thenReturn("0.87");
        Optional<Double> result = repository.find("daily-ohlcv-us");

        assertThat(result).contains(0.87);
    }
}
