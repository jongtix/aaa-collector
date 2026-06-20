package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.news.NewsHeadlineRepository;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.CreditBalanceRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.FinancialRepository;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvScheduler;
import com.aaa.collector.stock.etf.EtfMetadataRepository;
import com.aaa.collector.stock.etf.EtfRepresentativeHistoryRepository;
import com.aaa.collector.stock.grade.StockGradeRepository;
import com.aaa.collector.stock.grade.snapshot.RankingSnapshotRepository;
import com.aaa.collector.stock.shortsale.overseas.FinraShortSaleClient;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasDailyCollectionService;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasInterestCollectionService;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasScheduler;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "smoke"})
@TestPropertySource(
        properties = {
            "management.server.port=0",
            "management.health.redis.enabled=false",
            // Spring Boot 테스트 컨텍스트는 기본적으로 metrics export를 끈다
            // (management.defaults.metrics.export.enabled=false). 프로덕션에서는 활성이므로,
            // /actuator/prometheus 노출을 검증하기 위해 prometheus export만 명시 활성화한다.
            "management.prometheus.metrics.export.enabled=true"
        })
@DisplayName("Actuator prometheus 엔드포인트 통합 테스트 (REQ-OBSV-001/002)")
// 컨텍스트 로드를 위해 DB/Redis 의존 빈을 일괄 모킹한다. 클래스 레벨 types 배열로 중복 필드 선언을 회피한다.
@MockitoBean(
        types = {
            StringRedisTemplate.class,
            JdbcTemplate.class,
            StockRepository.class,
            EtfMetadataRepository.class,
            EtfRepresentativeHistoryRepository.class,
            DailyOhlcvRepository.class,
            StockGradeRepository.class,
            RankingSnapshotRepository.class,
            InvestorTrendRepository.class,
            ShortSaleDomesticRepository.class,
            CreditBalanceRepository.class,
            MacroIndicatorRepository.class,
            CorporateEventRepository.class,
            NewsHeadlineRepository.class,
            FinancialRepository.class,
            AnalystEstimateRepository.class,
            // SPEC-COLLECTOR-OVERSEAS-OHLCV-001 REQ-OVOH-043: 신규 미국 일봉 서비스/스케줄러 빈 모킹 (BATCH-003 회귀
            // 방지)
            OverseasDailyOhlcvCollectionService.class,
            OverseasDailyOhlcvScheduler.class,
            // REQ-SSO-041 — BATCH-003·GRADE-003 회귀 방지: 미국 공매도 신규 빈 모킹
            ShortSaleOverseasRepository.class,
            FinraShortSaleClient.class,
            ShortSaleOverseasDailyCollectionService.class,
            ShortSaleOverseasInterestCollectionService.class,
            ShortSaleOverseasScheduler.class
        })
class ActuatorPrometheusIntegrationTest {

    @LocalManagementPort private int managementPort;

    @Autowired private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /actuator/prometheus → HTTP 200 + Prometheus/VM 텍스트 노출 포맷")
    void prometheus_returnsPrometheusTextFormat() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(actuatorUrl("/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Prometheus/OpenMetrics 텍스트 노출 포맷은 # HELP / # TYPE 주석 라인을 포함한다.
        assertThat(response.getBody()).contains("# HELP").contains("# TYPE");
    }

    @Test
    @DisplayName("노출되지 않은 엔드포인트 GET /actuator/beans → HTTP 404 (health,prometheus만 노출)")
    void beans_returnsNotFound() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(actuatorUrl("/actuator/beans"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("노출되지 않은 엔드포인트 GET /actuator/env → HTTP 404")
    void env_returnsNotFound() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(actuatorUrl("/actuator/env"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String actuatorUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }
}
