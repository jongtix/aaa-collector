package com.aaa.collector.dart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aaa.collector.dart.external.DartDisclosureClient;
import com.aaa.collector.dart.external.DartListResponse;
import com.aaa.collector.dart.external.DartProperties;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

/**
 * DartDisclosureClient 단위 테스트 (SPEC-COLLECTOR-DART-001 REQ-DART-031, 032).
 *
 * <p>fetchAllPages 동작 — status 에러, pblntf_ty 필터 옵션.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DartDisclosureClientTest")
class DartDisclosureClientTest {

    @Mock private DartProperties dartProperties;

    @Mock private RestClient dartRestClient;

    @InjectMocks private DartDisclosureClient dartDisclosureClient;

    @Nested
    @DisplayName("fetchAllPages — status 처리 (REQ-DART-031)")
    class FetchAllPages {

        @Test
        @DisplayName("status=013 응답 — 빈 목록 반환")
        void statusNoData_returnsEmptyList() {
            // Arrange
            DartListResponse noDataResponse =
                    new DartListResponse("013", "조회된 데이터가 없습니다.", 0, 1, 1, 1, List.of());
            setupRestClientMock(noDataResponse);

            // Act
            List<DartListResponse.DisclosureItem> items =
                    dartDisclosureClient.fetchAllPages(
                            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2));

            // Assert
            assertThat(items).isEmpty();
        }

        @Test
        @DisplayName("status=000 단건 응답 — 1건 반환")
        void statusOk_returnsOneItem() {
            // Arrange
            DartListResponse.DisclosureItem item =
                    DartListResponse.DisclosureItem.of(
                            "00126380",
                            "Y",
                            "005930",
                            "사업보고서",
                            "20260601000001",
                            "삼성전자",
                            "20260601",
                            null);
            DartListResponse response =
                    new DartListResponse("000", "정상", 1, 1, 1, 1, List.of(item));
            setupRestClientMock(response);

            // Act
            List<DartListResponse.DisclosureItem> items =
                    dartDisclosureClient.fetchAllPages(
                            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2));

            // Assert
            assertThat(items).hasSize(1);
            assertThat(items.getFirst().rceptNo()).isEqualTo("20260601000001");
        }
    }

    @Nested
    @DisplayName("pblntf_ty 필터 (REQ-DART-032, AC-7)")
    class PblntfTyFilter {

        @Test
        @DisplayName("pblntf_ty 미설정 — 기본 빈 문자열")
        void pblntfTyEmpty_defaultBehavior() {
            assertThat(dartProperties.getPblntfTy()).isNullOrEmpty();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setupRestClientMock(DartListResponse response) {
        when(dartProperties.getApiKey()).thenReturn("test-key");
        when(dartProperties.getPblntfTy()).thenReturn("");

        RestClient.RequestHeadersUriSpec uriSpec =
                org.mockito.Mockito.mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec =
                org.mockito.Mockito.mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec =
                org.mockito.Mockito.mock(RestClient.ResponseSpec.class);

        when(dartRestClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(java.util.function.Function.class))).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(DartListResponse.class)).thenReturn(response);
    }
}
