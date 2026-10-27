package com.aaa.collector.stock.grade;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.SmokeMockitoBase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code cache:grade:{symbol}} JSON 쓰기 형식 계약 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-010·016, AC-BOOT4-010
 * (i)·016 (b)).
 *
 * <p>읽는 주체가 세 서비스 어디에도 없으므로(research.md §5.2) 쓰기 형식만 고정한다. 운영 {@link GradeCacheRepository} 빈이 주입받은
 * Spring 관리 매퍼가 {@code ZonedDateTime}을 {@code spring.jackson.time-zone}(Asia/Seoul) 오프셋의 ISO 문자열로
 * 쓰는 동작이 판정 대상이다. 입력을 UTC로 주는 경우도 함께 단언해 시간대 조정 동작까지 고정한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "smoke"})
@DisplayName("cache:grade:* 쓰기 형식 계약 — Spring 관리 매퍼 경유")
class GradeCacheFormatContractTest extends SmokeMockitoBase {

    @Autowired private GradeCacheRepository repository;

    @Autowired private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private String savedJson(ZonedDateTime gradedAt) {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        repository.save("005930", Grade.A, gradedAt);
        verify(valueOps).set(eq("cache:grade:005930"), json.capture());
        return json.getValue();
    }

    private static String fixture() throws IOException {
        return new ClassPathResource("contract/grade-boot35.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("KST 입력 — 3.5 형식 픽스처와 JSON 의미상 같다")
    void writesBoot35FormatFromKst() throws IOException, JSONException {
        ZonedDateTime gradedAt = ZonedDateTime.of(2026, 6, 7, 9, 0, 0, 0, ZoneId.of("Asia/Seoul"));

        JSONAssert.assertEquals(fixture(), savedJson(gradedAt), JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("UTC 입력 — Asia/Seoul 오프셋으로 조정되어 같은 픽스처가 나온다")
    void writesBoot35FormatFromUtc() throws IOException, JSONException {
        ZonedDateTime gradedAt = ZonedDateTime.of(2026, 6, 7, 0, 0, 0, 0, ZoneId.of("UTC"));

        JSONAssert.assertEquals(fixture(), savedJson(gradedAt), JSONCompareMode.STRICT);
    }
}
