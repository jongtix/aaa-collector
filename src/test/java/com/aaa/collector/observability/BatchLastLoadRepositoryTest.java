package com.aaa.collector.observability;

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
@DisplayName("BatchLastLoadRepository — 배치 마지막 성공 시각 Redis 저장/조회 (REQ-WSR-002/004/008)")
class BatchLastLoadRepositoryTest {

    private static final String KEY_PREFIX = "observability:collector:last-load:";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private BatchLastLoadRepository repository() {
        return new BatchLastLoadRepository(redisTemplate);
    }

    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("save — TTL 없이 opsForValue().set(key, epoch문자열) 호출 (REQ-WSR-002)")
    void save_callsSetWithoutTtl() {
        stubOpsForValue();

        repository().save("corp-code", 1_752_000_000L);

        // TTL 없는 2-arg set 오버로드가 호출되어야 한다(영속 저장).
        verify(valueOps).set(KEY_PREFIX + "corp-code", "1752000000");
    }

    @Test
    @DisplayName("find — 부재 키는 Optional.empty() 반환")
    void find_whenAbsent_returnsEmpty() {
        stubOpsForValue();
        when(valueOps.get(KEY_PREFIX + "corp-code")).thenReturn(null);

        Optional<Instant> result = repository().find("corp-code");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("find — 존재하는 값은 epoch 초를 Instant로 환원해 반환")
    void find_whenPresent_returnsInstant() {
        stubOpsForValue();
        when(valueOps.get(KEY_PREFIX + "corp-code")).thenReturn("1752000000");

        Optional<Instant> result = repository().find("corp-code");

        assertThat(result).contains(Instant.ofEpochSecond(1_752_000_000L));
    }

    @Test
    @DisplayName("find — 비숫자(손상) 값이면 예외 없이 Optional.empty() 반환(부팅 폴백, REQ-WSR-006)")
    void find_whenCorrupted_returnsEmpty() {
        stubOpsForValue();
        when(valueOps.get(KEY_PREFIX + "corp-code")).thenReturn("not-a-number");

        // 손상값은 "미가용"의 한 형태 — 예외를 전파하지 않고 프록시 폴백 경로가 작동하도록 empty를 반환해야 한다.
        Optional<Instant> result = repository().find("corp-code");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName(
            "find — 숫자지만 Instant epoch-second 유효범위 밖이면 예외 없이 Optional.empty() 반환(부팅 폴백, REQ-WSR-006)")
    void find_whenEpochOutOfRange_returnsEmpty() {
        stubOpsForValue();
        // 파싱은 되지만 Instant.ofEpochSecond의 유효 범위(약 ±3.15e16)를 초과 — DateTimeException 유발.
        when(valueOps.get(KEY_PREFIX + "corp-code")).thenReturn("9000000000000000000");

        // 범위 밖 값도 "미가용"의 한 형태 — 예외를 전파하지 않고 프록시 폴백 경로가 작동하도록 empty를 반환해야 한다.
        Optional<Instant> result = repository().find("corp-code");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("save/find 라운드트립 — write 키와 read 키가 동일 파생 소스 (REQ-WSR-008, AC-8)")
    void saveAndFind_useSameDerivedKey() {
        stubOpsForValue();
        BatchLastLoadRepository repository = repository();

        // Arrange: save가 사용한 키를 캡처
        repository.save("overseas-split", 1_752_345_678L);
        ArgumentCaptor<String> writeKey = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(writeKey.capture(), org.mockito.ArgumentMatchers.eq("1752345678"));

        // Act: 동일 라벨로 find 하면 캡처된 write 키를 그대로 되읽어야 한다
        when(valueOps.get(writeKey.getValue())).thenReturn("1752345678");
        Optional<Instant> result = repository.find("overseas-split");

        // Assert
        assertThat(result).contains(Instant.ofEpochSecond(1_752_345_678L));
    }
}
