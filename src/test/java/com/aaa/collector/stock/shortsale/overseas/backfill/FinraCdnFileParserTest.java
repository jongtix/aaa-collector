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
 * FinraCdnFileParser 명세 (SPEC-COLLECTOR-BACKFILL-008 T3, DECIMAL 전환
 * SPEC-COLLECTOR-SHORTSALE-DECIMAL-001).
 *
 * <p>헤더 기반 5/6컬럼 단일 파싱 + TOTALS/빈값/음수/scale 초과 skip 규칙을 검증한다. 2026-02-23 FINRA 소수 전환 이후 소수부는 더 이상
 * skip 사유가 아니며 무손실 보존된다(REQ-SSD-007).
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

            ParsedRow row = result.rows().getFirst();
            assertThat(row.symbol()).isEqualTo("AAPL");
            assertThat(row.shortVolume()).isEqualByComparingTo("1000");
            assertThat(row.totalVolume()).isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("5컬럼(ShortExemptVolume 없음) 파일에서도 동일 로직으로 Symbol/ShortVolume/TotalVolume을 추출한다")
        void fiveColumnFile_extractsSymbolShortTotalByHeader() {
            ParsedFileResult result = parser.parse(fixture("FNSQshvol_5col_sample.txt"));

            ParsedRow row = result.rows().getFirst();
            assertThat(row.symbol()).isEqualTo("GOOG");
            assertThat(row.shortVolume()).isEqualByComparingTo("2000");
            assertThat(row.totalVolume()).isEqualByComparingTo("8000");
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
            assertThat(row.symbol()).isEqualTo("AAPL");
            assertThat(row.shortVolume()).isEqualByComparingTo("1000");
            assertThat(row.totalVolume()).isEqualByComparingTo("5000");
        }
    }

    @Nested
    @DisplayName("소수부 무손실 보존 (2026-02-23 FINRA 소수 전환, REQ-SSD-007)")
    class DecimalPreservation {

        @Test
        @DisplayName("소수 6자리 ShortVolume/TotalVolume 행은 skip하지 않고 무손실 보존한다 (기존 소수부 skip flip)")
        void sixColumnFile_preservesFractionalRow() {
            ParsedFileResult result = parser.parse(fixture("CNMSshvol_6col_sample.txt"));

            // 유효 행 2건 = AAPL(정수) + FRACV(소수 6자리, 종전엔 skip됐음)
            ParsedRow fractional =
                    result.rows().stream()
                            .filter(r -> "FRACV".equals(r.symbol()))
                            .findFirst()
                            .orElseThrow();
            assertThat(fractional.shortVolume()).isEqualByComparingTo("7546181.247857");
            assertThat(fractional.totalVolume()).isEqualByComparingTo("10000000");
        }

        @Test
        @DisplayName("scale(6)을 초과하는 소수(7자리)는 fail-loud로 skip한다 (조용한 반올림 금지, REQ-SSD-011)")
        void scaleOverflowRowIsSkipped() {
            String body =
                    "Date|Symbol|ShortVolume|TotalVolume|Market\n"
                            + "20260223|OVERSCALE|1.1234567|100|Q\n"
                            + "20260223|OKROW|500|1000|Q\n";

            ParsedFileResult result = parser.parse(body);

            // OKROW만 유효, OVERSCALE는 scale 초과로 skip
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().getFirst().symbol()).isEqualTo("OKROW");
            assertThat(result.skippedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("극단적 지수 입력(stripTrailingZeros scale 오버플로)은 예외 없이 skip한다")
        void extremeExponentRowIsSkippedWithoutThrowing() {
            String body =
                    "Date|Symbol|ShortVolume|TotalVolume|Market\n"
                            + "20260223|HUGEEXP|100E2147483647|100|Q\n"
                            + "20260223|OKROW|500|1000|Q\n";

            ParsedFileResult result = parser.parse(body);

            // OKROW만 유효, HUGEEXP는 scale 오버플로 예외 없이 skip
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().getFirst().symbol()).isEqualTo("OKROW");
            assertThat(result.skippedCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("TOTALS·빈값·음수 skip, 소수부는 보존 (AC-BF-07, REQ-SSD-007)")
    class SkipRules {

        @Test
        @DisplayName("6컬럼 파일: TOTALS·빈값·음수는 skip, 소수부(FRACV)는 유효로 승격 — skip 4→3")
        void sixColumnFile_skipsInvalidButKeepsFractional() {
            ParsedFileResult result = parser.parse(fixture("CNMSshvol_6col_sample.txt"));

            // 유효 행 2건(AAPL, FRACV) + skip 3건(EMPTYV 빈값, NEGV 음수, TOTALS 요약행)
            assertThat(result.rows()).hasSize(2);
            assertThat(result.skippedCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("5컬럼 파일: TOTALS·빈값·음수는 skip, 소수부(FRACV)는 유효로 승격 — skip 4→3")
        void fiveColumnFile_skipsInvalidButKeepsFractional() {
            ParsedFileResult result = parser.parse(fixture("FNSQshvol_5col_sample.txt"));

            assertThat(result.rows()).hasSize(2);
            assertThat(result.skippedCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("빈 문자열 입력은 예외 없이 빈 결과를 반환한다")
        void blankInput_returnsEmptyResultWithoutException() {
            ParsedFileResult result = parser.parse("");

            assertThat(result.rows()).isEmpty();
            assertThat(result.skippedCount()).isZero();
        }

        @Test
        @DisplayName("숫자로 변환 불가한 값(NumberFormatException)은 예외 없이 skip 처리한다")
        void nonNumericValueIsSkippedWithoutException() {
            String body =
                    "Date|Symbol|ShortVolume|TotalVolume|Market\n"
                            + "20260223|BADV|abc|100|Q\n"
                            + "20260223|OKROW|500|1000|Q\n";

            ParsedFileResult result = parser.parse(body);

            // OKROW만 유효, BADV는 ShortVolume이 숫자가 아니라 skip
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().getFirst().symbol()).isEqualTo("OKROW");
            assertThat(result.skippedCount()).isEqualTo(1);
        }
    }
}
