package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.SmokeMockitoBase;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
 * {@code cache:stock:list} JSON 형식 계약 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-010·016, AC-BOOT4-010 (i)·016
 * (a)).
 *
 * <p>매퍼를 직접 만들지 않고 smoke 컨텍스트의 운영 {@link StockListCacheRepository} 빈을 쓴다 — 그 빈이 주입받은 Spring 관리
 * 매퍼(전역 {@code SNAKE_CASE}, 날짜 문자열)가 판정 대상이다. Redis는 smoke 베이스의 {@code StringRedisTemplate} 목으로
 * 가로챈다. 픽스처 {@code contract/stock-list-boot35.json}은 Boot 3.5.16 빌드가 쓴 형식이며, 전환 후에도 같은 픽스처를 읽고 같은
 * 형식으로 써야 한다(구버전 쓰기 → 신버전 읽기, 신버전 쓰기 → 구버전 읽기 양방향).
 */
@SpringBootTest
@ActiveProfiles({"test", "smoke"})
@DisplayName("cache:stock:list 형식 계약 — Spring 관리 매퍼 경유")
class StockListCacheFormatContractTest extends SmokeMockitoBase {

    private static final String CACHE_KEY = "cache:stock:list";

    private static final List<CachedStock> FIXTURE_STOCKS =
            List.of(
                    new CachedStock(
                            "005930",
                            "삼성전자",
                            "Samsung Electronics",
                            Market.KOSPI,
                            AssetType.STOCK,
                            LocalDate.of(1975, 6, 11)),
                    new CachedStock("AAPL", "애플", null, Market.NASDAQ, AssetType.STOCK, null));

    @Autowired private StockListCacheRepository repository;

    @Autowired private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private static String fixture() throws IOException {
        return new ClassPathResource("contract/stock-list-boot35.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("3.5 형식 픽스처를 예외 없이 같은 값으로 읽는다")
    void readsBoot35Fixture() throws IOException {
        // Arrange
        when(valueOps.get(CACHE_KEY)).thenReturn(fixture());

        // Act
        Optional<List<CachedStock>> result = repository.findAll();

        // Assert
        assertThat(result).contains(FIXTURE_STOCKS);
    }

    @Test
    @DisplayName("같은 입력을 쓰면 3.5 형식 픽스처와 JSON 의미상 같다")
    void writesBoot35Format() throws IOException, JSONException {
        // Arrange
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

        // Act
        repository.save(FIXTURE_STOCKS);

        // Assert
        verify(valueOps).set(eq(CACHE_KEY), json.capture());
        JSONAssert.assertEquals(fixture(), json.getValue(), JSONCompareMode.STRICT);
    }
}
