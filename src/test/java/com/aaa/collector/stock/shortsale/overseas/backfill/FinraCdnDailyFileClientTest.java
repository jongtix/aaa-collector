package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("FinraCdnDailyFileClient")
class FinraCdnDailyFileClientTest {

    private static final String CDN_BASE = "https://cdn.finra.org";

    private MockRestServiceServer mockServer;
    private FinraCdnDailyFileClient client;
    private FinraCdnShortSaleBackfillProperties properties;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CDN_BASE);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        properties = new FinraCdnShortSaleBackfillProperties();
        client = new FinraCdnDailyFileClient(builder.build(), properties);
    }

    private static String cnmsUrl(String yyyymmdd) {
        return CDN_BASE + "/equity/regsho/daily/CNMSshvol" + yyyymmdd + ".txt";
    }

    private static String facilityUrl(String facility, String yyyymmdd) {
        return CDN_BASE + "/equity/regsho/daily/" + facility + "shvol" + yyyymmdd + ".txt";
    }

    @Nested
    @DisplayName("URL·헤더 (AC-BF-01)")
    class UrlAndHeaders {

        @Test
        @DisplayName(
                "URL 형식 = https://cdn.finra.org/equity/regsho/daily/{FACILITY}shvol{yyyyMMdd}.txt")
        void requestsExpectedUrlFormat() {
            mockServer
                    .expect(requestTo(cnmsUrl("20180801")))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("body", MediaType.TEXT_PLAIN));

            client.fetch(LocalDate.of(2018, 8, 1));

            mockServer.verify();
        }

        @Test
        @DisplayName("Authorization 헤더를 포함하지 않는다")
        void sendsNoAuthorizationHeader() {
            mockServer
                    .expect(requestTo(cnmsUrl("20180801")))
                    .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                    .andRespond(withSuccess("body", MediaType.TEXT_PLAIN));

            client.fetch(LocalDate.of(2018, 8, 1));

            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("CNMS 우선 선택 (AC-BF-02)")
    class CnmsPriority {

        @Test
        @DisplayName("CNMS 200이면 CNMS만 요청하고 시설 파일은 요청하지 않는다")
        void cnms200_onlyCnmsRequested() {
            mockServer
                    .expect(requestTo(cnmsUrl("20180801")))
                    .andRespond(withSuccess("CNMS-BODY", MediaType.TEXT_PLAIN));

            FinraCdnFetchResult result = client.fetch(LocalDate.of(2018, 8, 1));

            mockServer.verify();
            assertThat(result).isInstanceOf(FinraCdnFetchResult.Found.class);
            assertThat(((FinraCdnFetchResult.Found) result).fileBodies())
                    .containsExactly("CNMS-BODY");
        }
    }

    @Nested
    @DisplayName("시설 폴백 (AC-BF-03)")
    class FacilityFallback {

        @Test
        @DisplayName("CNMS 403이면 시설 코드 각각 요청해 200인 파일만 결과에 포함한다")
        void cnms403_onlyPresentFacilitiesIncluded() {
            properties.setFacilityCodes(List.of("FNSQ", "FNYX", "FNQC", "FORF", "FNRA"));

            mockServer
                    .expect(requestTo(cnmsUrl("20130102")))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN));
            mockServer
                    .expect(requestTo(facilityUrl("FNSQ", "20130102")))
                    .andRespond(withSuccess("FNSQ-BODY", MediaType.TEXT_PLAIN));
            mockServer
                    .expect(requestTo(facilityUrl("FNYX", "20130102")))
                    .andRespond(withSuccess("FNYX-BODY", MediaType.TEXT_PLAIN));
            mockServer
                    .expect(requestTo(facilityUrl("FNQC", "20130102")))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN));
            mockServer
                    .expect(requestTo(facilityUrl("FORF", "20130102")))
                    .andRespond(withSuccess("FORF-BODY", MediaType.TEXT_PLAIN));
            mockServer
                    .expect(requestTo(facilityUrl("FNRA", "20130102")))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));

            FinraCdnFetchResult result = client.fetch(LocalDate.of(2013, 1, 2));

            mockServer.verify();
            assertThat(result).isInstanceOf(FinraCdnFetchResult.Found.class);
            assertThat(((FinraCdnFetchResult.Found) result).fileBodies())
                    .containsExactlyInAnyOrder("FNSQ-BODY", "FNYX-BODY", "FORF-BODY");
        }
    }

    @Nested
    @DisplayName("전부 부재 — 403/404 구분 (AC-BF-16/-17)")
    class AllAbsent {

        @Test
        @DisplayName("CNMS 403 + 전 시설 부재 → Absent(FLOOR_BEFORE_403)")
        void cnms403_allFacilitiesAbsent_returnsFloorBefore403() {
            properties.setFacilityCodes(List.of("FNSQ"));

            mockServer
                    .expect(requestTo(cnmsUrl("20090101")))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN));
            mockServer
                    .expect(requestTo(facilityUrl("FNSQ", "20090101")))
                    .andRespond(withStatus(HttpStatus.FORBIDDEN));

            FinraCdnFetchResult result = client.fetch(LocalDate.of(2009, 1, 1));

            mockServer.verify();
            assertThat(result).isInstanceOf(FinraCdnFetchResult.Absent.class);
            assertThat(((FinraCdnFetchResult.Absent) result).reason())
                    .isEqualTo(FinraCdnFetchResult.AbsenceReason.FLOOR_BEFORE_403);
        }

        @Test
        @DisplayName("CNMS 404(주말) + 전 시설 부재 → Absent(NOT_GENERATED_404)")
        void cnms404_allFacilitiesAbsent_returnsNotGenerated404() {
            properties.setFacilityCodes(List.of("FNSQ"));

            mockServer
                    .expect(requestTo(cnmsUrl("20260704")))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));
            mockServer
                    .expect(requestTo(facilityUrl("FNSQ", "20260704")))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));

            FinraCdnFetchResult result = client.fetch(LocalDate.of(2026, 7, 4));

            mockServer.verify();
            assertThat(result).isInstanceOf(FinraCdnFetchResult.Absent.class);
            assertThat(((FinraCdnFetchResult.Absent) result).reason())
                    .isEqualTo(FinraCdnFetchResult.AbsenceReason.NOT_GENERATED_404);
        }
    }

    @Nested
    @DisplayName("일시적 오류 — 5xx·타임아웃 (코드리뷰 Fix 1)")
    class TransientErrors {

        @Test
        @DisplayName("CNMS 503 → Absent(TRANSIENT_ERROR)를 반환하고 예외를 전파하지 않는다")
        void cnms503_returnsTransientErrorWithoutThrowing() {
            mockServer
                    .expect(requestTo(cnmsUrl("20180801")))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

            FinraCdnFetchResult result = client.fetch(LocalDate.of(2018, 8, 1));

            mockServer.verify();
            assertThat(result).isInstanceOf(FinraCdnFetchResult.Absent.class);
            assertThat(((FinraCdnFetchResult.Absent) result).reason())
                    .isEqualTo(FinraCdnFetchResult.AbsenceReason.TRANSIENT_ERROR);
        }

        @Test
        @DisplayName("CNMS 타임아웃/연결 실패 → Absent(TRANSIENT_ERROR)를 반환하고 예외를 전파하지 않는다")
        void cnmsTimeout_returnsTransientErrorWithoutThrowing() {
            mockServer
                    .expect(requestTo(cnmsUrl("20180801")))
                    .andRespond(
                            request -> {
                                throw new IOException("connect timed out");
                            });

            FinraCdnFetchResult result = client.fetch(LocalDate.of(2018, 8, 1));

            mockServer.verify();
            assertThat(result).isInstanceOf(FinraCdnFetchResult.Absent.class);
            assertThat(((FinraCdnFetchResult.Absent) result).reason())
                    .isEqualTo(FinraCdnFetchResult.AbsenceReason.TRANSIENT_ERROR);
        }

        @Test
        @DisplayName("CNMS 일시적 오류 시 시설 폴백을 시도하지 않고 즉시 중단한다")
        void cnmsTransientError_doesNotAttemptFacilityFallback() {
            mockServer
                    .expect(requestTo(cnmsUrl("20180801")))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

            client.fetch(LocalDate.of(2018, 8, 1));

            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("응답 크기 상한 (코드리뷰 Fix 2)")
    class ResponseSizeGuard {

        @Test
        @DisplayName("응답 본문이 maxFileSizeBytes를 초과하면 Absent(TRANSIENT_ERROR)로 흡수하고 본문을 반환하지 않는다")
        void oversizedResponse_rejectedAsTransientError() {
            properties.setMaxFileSizeBytes(10);
            String oversizedBody = "0123456789ABCDEF";

            mockServer
                    .expect(requestTo(cnmsUrl("20180801")))
                    .andRespond(withSuccess(oversizedBody, MediaType.TEXT_PLAIN));

            FinraCdnFetchResult result = client.fetch(LocalDate.of(2018, 8, 1));

            mockServer.verify();
            assertThat(result).isInstanceOf(FinraCdnFetchResult.Absent.class);
            assertThat(((FinraCdnFetchResult.Absent) result).reason())
                    .isEqualTo(FinraCdnFetchResult.AbsenceReason.TRANSIENT_ERROR);
        }

        @Test
        @DisplayName("응답 본문이 maxFileSizeBytes 이내면 정상적으로 Found를 반환한다")
        void withinLimitResponse_returnsFound() {
            properties.setMaxFileSizeBytes(10);
            String body = "SMALL";

            mockServer
                    .expect(requestTo(cnmsUrl("20180801")))
                    .andRespond(withSuccess(body, MediaType.TEXT_PLAIN));

            FinraCdnFetchResult result = client.fetch(LocalDate.of(2018, 8, 1));

            mockServer.verify();
            assertThat(result).isInstanceOf(FinraCdnFetchResult.Found.class);
            assertThat(((FinraCdnFetchResult.Found) result).fileBodies()).containsExactly(body);
        }
    }
}
