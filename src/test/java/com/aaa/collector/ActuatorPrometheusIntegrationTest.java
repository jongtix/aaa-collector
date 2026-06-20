package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
class ActuatorPrometheusIntegrationTest extends SmokeContextTest {

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
