package com.aaa.collector.kis.holiday;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import com.aaa.collector.kis.token.KisTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisHolidayClient — 국내휴장일조회 (CTCA0903R, opnd_yn)")
class KisHolidayClientTest {

    private static final String HOLIDAY_PATH = "/uapi/domestic-stock/v1/quotations/chk-holiday";

    private WireMockServer wireMockServer;
    private KisHolidayClient client;

    @Mock private KisTokenService kisTokenService;
    @Mock private KisProperties kisProperties;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        ObjectMapper objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        RestClient restClient =
                RestClient.builder()
                        .baseUrl(wireMockServer.baseUrl())
                        .messageConverters(
                                converters -> {
                                    converters.clear();
                                    converters.add(
                                            new MappingJackson2HttpMessageConverter(objectMapper));
                                })
                        .build();

        KisAccountCredential credential =
                new KisAccountCredential("test", "12345678", "test-app-key", "test-app-secret");
        KisApiExecutor kisApiExecutor =
                new KisApiExecutor(restClient, kisProperties, kisTokenService);
        client = new KisHolidayClient(kisApiExecutor);

        Mockito.lenient().when(kisProperties.accounts()).thenReturn(List.of(credential));
        Mockito.lenient().when(kisTokenService.getValidToken("test")).thenReturn("test-token");
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private void stubHoliday(String responseBody) {
        wireMockServer.stubFor(
                get(urlPathEqualTo(HOLIDAY_PATH))
                        .withQueryParam("BASS_DT", equalTo("20260618"))
                        .withQueryParam("CTX_AREA_NK", equalTo(""))
                        .withQueryParam("CTX_AREA_FK", equalTo(""))
                        .withHeader("tr_id", equalTo("CTCA0903R"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(responseBody)));
    }

    @Nested
    @DisplayName("fetchCalendar — 기준일 캘린더 조회")
    class FetchCalendar {

        @Test
        @DisplayName("정상 응답 — 일자별 opnd_yn 행 목록 반환 (BASS_DT=yyyyMMdd 전송)")
        void fetchCalendar_normalResponse_returnsRows() {
            String body =
                    """
                    {
                        "ctx_area_nk": "20260711            ",
                        "ctx_area_fk": "20260618            ",
                        "rt_cd": "0",
                        "msg_cd": "KIOK0500",
                        "msg1": "조회가 계속됩니다.",
                        "output": [
                            {"bass_dt": "20260618", "wday_dvsn_cd": "05", "bzdy_yn": "Y", "tr_day_yn": "Y", "opnd_yn": "Y", "sttl_day_yn": "Y"},
                            {"bass_dt": "20260620", "wday_dvsn_cd": "07", "bzdy_yn": "N", "tr_day_yn": "Y", "opnd_yn": "N", "sttl_day_yn": "N"}
                        ]
                    }
                    """;
            stubHoliday(body);

            List<KisHolidayResponse.HolidayRow> rows =
                    client.fetchCalendar(LocalDate.of(2026, 6, 18));

            assertThat(rows).hasSize(2);
            assertThat(rows.getFirst().bassDt()).isEqualTo("20260618");
            assertThat(rows.getFirst().opndYn()).isEqualTo("Y");
            assertThat(rows.get(1).bassDt()).isEqualTo("20260620");
            assertThat(rows.get(1).opndYn()).isEqualTo("N");
        }

        @Test
        @DisplayName("빈 output 배열 — 빈 목록 반환 (방어)")
        void fetchCalendar_emptyOutput_returnsEmptyList() {
            String body =
                    """
                    {
                        "rt_cd": "0",
                        "msg_cd": "MCA00000",
                        "msg1": "조회되었습니다.",
                        "output": []
                    }
                    """;
            stubHoliday(body);

            List<KisHolidayResponse.HolidayRow> rows =
                    client.fetchCalendar(LocalDate.of(2026, 6, 18));

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("output 누락 응답 — 빈 목록 반환 (NPE 방지)")
        void fetchCalendar_missingOutput_returnsEmptyList() {
            String body =
                    """
                    {
                        "rt_cd": "0",
                        "msg_cd": "MCA00000",
                        "msg1": "조회되었습니다."
                    }
                    """;
            stubHoliday(body);

            List<KisHolidayResponse.HolidayRow> rows =
                    client.fetchCalendar(LocalDate.of(2026, 6, 18));

            assertThat(rows).isEmpty();
        }
    }
}
