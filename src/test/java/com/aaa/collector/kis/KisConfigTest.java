package com.aaa.collector.kis;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class KisConfigTest {

    private static final List<KisAccountCredential> DUMMY_ACCOUNTS =
            List.of(new KisAccountCredential("test", "12345678", "appkey", "appsecret"));
    private static final KisProperties.RateLimit DUMMY_RATE_LIMIT =
            new KisProperties.RateLimit(20, 20, 10);

    private final KisConfig kisConfig = new KisConfig();

    @Test
    @DisplayName("kisRestClient — KisProperties baseUrl로 RestClient가 생성된다 (non-null)")
    void kisRestClient_withValidProperties_returnsNonNullRestClient() {
        KisProperties props =
                new KisProperties(
                        "https://openapi.koreainvestment.com",
                        "user",
                        DUMMY_ACCOUNTS,
                        DUMMY_RATE_LIMIT);

        RestClient restClient = kisConfig.kisRestClient(RestClient.builder(), props);

        assertThat(restClient).isNotNull();
    }
}
