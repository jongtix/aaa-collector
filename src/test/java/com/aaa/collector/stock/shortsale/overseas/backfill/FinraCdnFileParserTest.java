package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FinraCdnFileParser 명세 (SPEC-COLLECTOR-BACKFILL-008 T3, AC-BF-05/-06/-07).
 *
 * <p>헤더 기반 5/6컬럼 단일 파싱 + TOTALS/빈값/음수/소수부 skip 규칙을 검증한다.
 */
@DisplayName("FinraCdnFileParser")
class FinraCdnFileParserTest {

    private final FinraCdnFileParser parser = new FinraCdnFileParser();

    private static String fixture(String name) {
        try (InputStream is =
                FinraCdnFileParserTest.class.getResourceAsStream("/finra/cdn/" + name)) {
            if (is == null) {
                throw new IllegalStateException("fixture not found: " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    @DisplayName("헤더 기반 5/6컬럼 단일 처리 (AC-BF-05)")
    class HeaderBasedColumnMapping {

        @Test
        @DisplayName("6컬럼(ShortExemptVolume 포함) 파일에서 Symbol/ShortVolume/TotalVolume을 컬럼명 기준 추출한다")
        void sixColumnFile_extractsSymbolShortTotalByHeader() {
            ParsedFileResult result = parser.parse(fixture("CNMSshvol_6col_sample.txt"));

            assertThat(result.rows()).hasSize(1);
            ParsedRow row = result.rows().getFirst();
            assertThat(row.symbol()).isEqualTo("AAPL");
            assertThat(row.shortVolume()).isEqualTo(1000L);
            assertThat(row.totalVolume()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("5컬럼(ShortExemptVolume 없음) 파일에서도 동일 로직으로 Symbol/ShortVolume/TotalVolume을 추출한다")
        void fiveColumnFile_extractsSymbolShortTotalByHeader() {
            ParsedFileResult result = parser.parse(fixture("FNSQshvol_5col_sample.txt"));

            assertThat(result.rows()).hasSize(1);
            ParsedRow row = result.rows().getFirst();
            assertThat(row.symbol()).isEqualTo("GOOG");
            assertThat(row.shortVolume()).isEqualTo(2000L);
            assertThat(row.totalVolume()).isEqualTo(8000L);
        }
    }

    @Nested
    @DisplayName("ShortExemptVolume 무시 (AC-BF-06)")
    class ShortExemptVolumeIgnored {

        @Test
        @DisplayName("6컬럼 파일 파싱 결과에 ShortExemptVolume 값이 노출되지 않는다")
        void sixColumnFile_shortExemptVolumeNotExposed() {
            ParsedFileResult result = parser.parse(fixture("CNMSshvol_6col_sample.txt"));

            // ParsedRow는 symbol/shortVolume/totalVolume 3개 필드만 보유 — ShortExemptVolume(50)을
            // 담을 필드 자체가 없음을 레코드 계약으로 보증한다.
            ParsedRow row = result.rows().getFirst();
            assertThat(row)
                    .extracting("symbol", "shortVolume", "totalVolume")
                    .containsExactly("AAPL", 1000L, 5000L);
        }
    }

    @Nested
    @DisplayName("TOTALS·빈값·음수·소수부 skip (AC-BF-07)")
    class SkipRules {

        @Test
        @DisplayName("6컬럼 파일: TOTALS·빈값·음수·소수부(FINRA 실측 케이스) 행은 skip 집계되고 예외 없이 처리된다")
        void sixColumnFile_skipsInvalidRowsWithoutException() {
            ParsedFileResult result = parser.parse(fixture("CNMSshvol_6col_sample.txt"));

            // 유효 행 1건(AAPL) + skip 4건(EMPTYV 빈값, NEGV 음수, FRACV 소수부, TOTALS 요약행)
            assertThat(result.rows()).hasSize(1);
            assertThat(result.skippedCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("5컬럼 파일: TOTALS·빈값·음수·소수부 행은 skip 집계되고 예외 없이 처리된다")
        void fiveColumnFile_skipsInvalidRowsWithoutException() {
            ParsedFileResult result = parser.parse(fixture("FNSQshvol_5col_sample.txt"));

            assertThat(result.rows()).hasSize(1);
            assertThat(result.skippedCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("빈 문자열 입력은 예외 없이 빈 결과를 반환한다")
        void blankInput_returnsEmptyResultWithoutException() {
            ParsedFileResult result = parser.parse("");

            assertThat(result.rows()).isEmpty();
            assertThat(result.skippedCount()).isZero();
        }
    }
}
