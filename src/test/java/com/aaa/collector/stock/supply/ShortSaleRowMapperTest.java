package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ShortSaleRowMapper 단위 테스트")
class ShortSaleRowMapperTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);
    private static final LocalDate WINDOW_START = TODAY.minusDays(14);

    private final ShortSaleRowMapper mapper = new ShortSaleRowMapper();

    private Stock stock(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo("테스트_" + symbol)
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .listedDate(LocalDate.of(2015, 1, 1))
                .build();
    }

    private KisShortSaleResponse.ShortSaleRow validRow(String date) {
        return new KisShortSaleResponse.ShortSaleRow(
                date, "12000", "3.5", "900000000", "4.2", "50000", "5.1", "3750000000", "6.3");
    }

    private KisShortSaleResponse.ShortSaleRow nullDateRow() {
        return new KisShortSaleResponse.ShortSaleRow(
                null, null, null, null, null, null, null, null, null);
    }

    private KisShortSaleResponse.ShortSaleRow blankDateRow() {
        return new KisShortSaleResponse.ShortSaleRow(
                "", null, null, null, null, null, null, null, null);
    }

    private KisShortSaleResponse response(List<KisShortSaleResponse.ShortSaleRow> rows) {
        return new KisShortSaleResponse("0", "MCA00000", "정상", rows);
    }

    @Nested
    @DisplayName("전체-null 응답 (공매도 데이터 없는 심볼)")
    class AllNullResponse {

        @Test
        @DisplayName("응답 전체가 null date — 빈 목록 반환")
        void allNull_returnsEmpty() {
            List<ShortSaleDomestic> result =
                    mapper.collectValid(
                            stock("433870"),
                            "433870",
                            response(List.of(nullDateRow(), nullDateRow(), nullDateRow())),
                            TODAY,
                            WINDOW_START);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("응답 전체가 blank date — 빈 목록 반환")
        void allBlank_returnsEmpty() {
            List<ShortSaleDomestic> result =
                    mapper.collectValid(
                            stock("433870"),
                            "433870",
                            response(List.of(blankDateRow(), blankDateRow())),
                            TODAY,
                            WINDOW_START);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("부분-null 응답 (일부 행만 이상)")
    class PartialNullResponse {

        @Test
        @DisplayName("유효 행은 엔티티로 매핑, null 행은 제외")
        void partialNull_validRowsMapped() {
            String validDate = "20260612";
            List<ShortSaleDomestic> result =
                    mapper.collectValid(
                            stock("005930"),
                            "005930",
                            response(
                                    List.of(
                                            validRow(validDate),
                                            nullDateRow(),
                                            validRow("20260611"))),
                            TODAY,
                            WINDOW_START);

            assertThat(result).hasSize(2);
            assertThat(result)
                    .extracting(ShortSaleDomestic::getTradeDate)
                    .containsExactlyInAnyOrder(
                            LocalDate.of(2026, 6, 12), LocalDate.of(2026, 6, 11));
        }
    }

    @Nested
    @DisplayName("정상 응답")
    class NormalResponse {

        @Test
        @DisplayName("빈 응답 — 빈 목록 반환")
        void emptyResponse_returnsEmpty() {
            List<ShortSaleDomestic> result =
                    mapper.collectValid(
                            stock("005930"), "005930", response(List.of()), TODAY, WINDOW_START);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("윈도우 내 유효 행 — 엔티티 반환")
        void validRows_withinWindow_returned() {
            List<ShortSaleDomestic> result =
                    mapper.collectValid(
                            stock("005930"),
                            "005930",
                            response(List.of(validRow("20260612"), validRow("20260611"))),
                            TODAY,
                            WINDOW_START);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("윈도우 밖 행 — 저장 제외")
        void validRows_outsideWindow_excluded() {
            List<ShortSaleDomestic> result =
                    mapper.collectValid(
                            stock("005930"),
                            "005930",
                            response(List.of(validRow("20260501"))), // WINDOW_START(5/30)보다 이전
                            TODAY,
                            WINDOW_START);

            assertThat(result).isEmpty();
        }
    }
}
