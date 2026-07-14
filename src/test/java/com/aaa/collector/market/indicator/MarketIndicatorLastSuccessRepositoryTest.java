package com.aaa.collector.market.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
@DisplayName("MarketIndicatorLastSuccessRepository — 지표×소스 마지막 성공 시각 Redis 저장/조회 (REQ-WSR-017/020)")
class MarketIndicatorLastSuccessRepositoryTest {

    private static final String KEY_PREFIX =
            "observability:collector:market-indicator-last-success:";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private MarketIndicatorLastSuccessRepository repository() {
        return new MarketIndicatorLastSuccessRepository(redisTemplate);
    }

    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("save — TTL 없이 opsForValue().set(key, epoch문자열) 호출 (REQ-WSR-017)")
    void save_callsSetWithoutTtl() {
        stubOpsForValue();

        repository().save("VIX", "CBOE", 1_752_000_000L);

        verify(valueOps).set(KEY_PREFIX + "VIX:CBOE", "1752000000");
    }

    @Test
    @DisplayName("find — 부재 키는 Optional.empty() 반환")
    void find_whenAbsent_returnsEmpty() {
        stubOpsForValue();
        when(valueOps.get(KEY_PREFIX + "VIX:CBOE")).thenReturn(null);

        Optional<Instant> result = repository().find("VIX", "CBOE");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("find — 존재하는 값은 epoch 초를 Instant로 환원해 반환")
    void find_whenPresent_returnsInstant() {
        stubOpsForValue();
        when(valueOps.get(KEY_PREFIX + "VIX:CBOE")).thenReturn("1752000000");

        Optional<Instant> result = repository().find("VIX", "CBOE");

        assertThat(result).contains(Instant.ofEpochSecond(1_752_000_000L));
    }

    @Test
    @DisplayName("find — 비숫자(손상) 값이면 예외 없이 Optional.empty() 반환(REQ-WSR-020)")
    void find_whenCorrupted_returnsEmpty() {
        stubOpsForValue();
        when(valueOps.get(KEY_PREFIX + "USDKRW:KOREAEXIM")).thenReturn("not-a-number");

        Optional<Instant> result = repository().find("USDKRW", "KOREAEXIM");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("find — 숫자지만 Instant epoch-second 유효범위 밖이면 예외 없이 Optional.empty() 반환(REQ-WSR-020)")
    void find_whenEpochOutOfRange_returnsEmpty() {
        stubOpsForValue();
        when(valueOps.get(KEY_PREFIX + "VIX:FRED")).thenReturn("9000000000000000000");

        Optional<Instant> result = repository().find("VIX", "FRED");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("save/find 라운드트립 — write 키와 read 키가 동일 파생 소스 (REQ-WSR-017)")
    void saveAndFind_useSameDerivedKey() {
        stubOpsForValue();
        MarketIndicatorLastSuccessRepository repository = repository();

        repository.save("USDKRW", "YAHOO_USDKRW", 1_752_345_678L);
        ArgumentCaptor<String> writeKey = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(writeKey.capture(), org.mockito.ArgumentMatchers.eq("1752345678"));

        when(valueOps.get(writeKey.getValue())).thenReturn("1752345678");
        Optional<Instant> result = repository.find("USDKRW", "YAHOO_USDKRW");

        assertThat(result).contains(Instant.ofEpochSecond(1_752_345_678L));
    }
}
