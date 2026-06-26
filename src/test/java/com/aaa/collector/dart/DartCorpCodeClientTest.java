package com.aaa.collector.dart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aaa.collector.dart.corpcode.CorpCodeEntry;
import com.aaa.collector.dart.external.DartCorpCodeClient;
import com.aaa.collector.dart.external.DartProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
 * DartCorpCodeClient 단위 테스트 (SPEC-COLLECTOR-DART-001 REQ-DART-002).
 *
 * <p>ZIP→XML 파싱 기본 동작 + null 응답 처리.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DartCorpCodeClientTest")
class DartCorpCodeClientTest {

    @Mock private DartProperties dartProperties;

    @Mock private RestClient dartRestClient;

    @InjectMocks private DartCorpCodeClient dartCorpCodeClient;

    @Nested
    @DisplayName("정상 응답 — ZIP→XML 파싱")
    class NormalResponse {

        @Test
        @DisplayName("상장사 1건 + 비상장 1건 ZIP → 상장사만 반환")
        void zipWithListedAndUnlisted_returnsListedOnly() throws Exception {
            // Arrange
            String xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <result>
                      <list>
                        <corp_code>00000001</corp_code>
                        <corp_name>삼성전자</corp_name>
                        <stock_code>005930</stock_code>
                        <modify_date>20260101</modify_date>
                      </list>
                      <list>
                        <corp_code>00000002</corp_code>
                        <corp_name>비상장사</corp_name>
                        <stock_code> </stock_code>
                        <modify_date>20260101</modify_date>
                      </list>
                    </result>
                    """;
            byte[] zipBytes = createZip("CORPCODE.xml", xml);
            setupRestClientMock(zipBytes);

            // Act
            List<CorpCodeEntry> result = dartCorpCodeClient.fetchListedEntries();

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().stockCode()).isEqualTo("005930");
            assertThat(result.getFirst().corpCode()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("상장사 2건 ZIP → 2건 반환")
        void zipWithTwoListedEntries_returnsBoth() throws Exception {
            // Arrange
            String xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <result>
                      <list>
                        <corp_code>00000001</corp_code>
                        <corp_name>삼성전자</corp_name>
                        <stock_code>005930</stock_code>
                        <modify_date>20260101</modify_date>
                      </list>
                      <list>
                        <corp_code>00000002</corp_code>
                        <corp_name>SK하이닉스</corp_name>
                        <stock_code>000660</stock_code>
                        <modify_date>20260101</modify_date>
                      </list>
                    </result>
                    """;
            byte[] zipBytes = createZip("CORPCODE.xml", xml);
            setupRestClientMock(zipBytes);

            // Act
            List<CorpCodeEntry> result = dartCorpCodeClient.fetchListedEntries();

            // Assert
            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("ZIP 응답 null — 빈 목록")
    class NullZipResponse {

        @Test
        @DisplayName("RestClient null 반환 → 빈 목록 반환, 예외 미전파")
        void nullZipBytes_returnsEmptyList() {
            setupRestClientMock(null);

            List<CorpCodeEntry> result = dartCorpCodeClient.fetchListedEntries();

            assertThat(result).isEmpty();
        }
    }

    /** 지정 엔트리명으로 XML 문자열을 담은 ZIP 바이트를 생성한다. */
    private static byte[] createZip(String entryName, String xmlContent) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            zos.write(xmlContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setupRestClientMock(byte[] response) {
        when(dartProperties.getApiKey()).thenReturn("test-key");

        RestClient.RequestHeadersUriSpec uriSpec =
                org.mockito.Mockito.mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec =
                org.mockito.Mockito.mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec =
                org.mockito.Mockito.mock(RestClient.ResponseSpec.class);

        when(dartRestClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(java.util.function.Function.class))).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(byte[].class)).thenReturn(response);
    }
}
