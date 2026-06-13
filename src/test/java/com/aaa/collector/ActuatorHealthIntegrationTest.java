package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.CreditBalanceRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.etf.EtfMetadataRepository;
import com.aaa.collector.stock.etf.EtfRepresentativeHistoryRepository;
import com.aaa.collector.stock.grade.StockGradeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "smoke"})
@TestPropertySource(
        properties = {"management.server.port=0", "management.health.redis.enabled=false"})
@DisplayName("Actuator health 엔드포인트 통합 테스트")
class ActuatorHealthIntegrationTest {

    @LocalManagementPort private int managementPort;

    @Autowired private TestRestTemplate restTemplate;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    @SuppressWarnings("unused")
    private StockRepository stockRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private EtfMetadataRepository etfMetadataRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private EtfRepresentativeHistoryRepository etfRepresentativeHistoryRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DailyOhlcvRepository dailyOhlcvRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private StockGradeRepository stockGradeRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private InvestorTrendRepository investorTrendRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private ShortSaleDomesticRepository shortSaleDomesticRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private CreditBalanceRepository creditBalanceRepository;

    @Test
    @DisplayName("GET /actuator/health → HTTP 200 반환")
    void health_returnsOk() {
        ResponseEntity<String> response = restTemplate.getForEntity(healthUrl(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /actuator/health 응답 body에 status:UP 포함")
    void health_bodyContainsStatusUp() {
        ResponseEntity<String> response = restTemplate.getForEntity(healthUrl(), String.class);

        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("show-details: never — 응답 body에 components 없음")
    void health_bodyDoesNotContainComponents() {
        ResponseEntity<String> response = restTemplate.getForEntity(healthUrl(), String.class);

        assertThat(response.getBody()).doesNotContain("components");
    }

    @Test
    @DisplayName("노출되지 않은 엔드포인트 GET /actuator/beans → HTTP 404 반환")
    void beans_returnsNotFound() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(actuatorUrl("/actuator/beans"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String healthUrl() {
        return actuatorUrl("/actuator/health");
    }

    private String actuatorUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }
}
