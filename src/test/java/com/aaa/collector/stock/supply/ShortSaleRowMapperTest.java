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

    @Nested
    @DisplayName("SPEC-COLLECTOR-ASSETSCOPE-001 REQ-ASSETSCOPE-012 — ETN·COMMODITY 공매도 사실적 0 특성화")
    class EtnCommodityFactualZero {

        private Stock etnStock(String symbol) {
            return Stock.builder()
                    .symbol(symbol)
                    .nameKo("테스트ETN_" + symbol)
                    .market(Market.KOSPI)
                    .assetType(AssetType.ETN)
                    .listedDate(LocalDate.of(2015, 1, 1))
                    .build();
        }

        private Stock commodityStock(String symbol) {
            return Stock.builder()
                    .symbol(symbol)
                    .nameKo("테스트금현물_" + symbol)
                    .market(Market.KOSPI)
                    .assetType(AssetType.COMMODITY)
                    .listedDate(LocalDate.of(2015, 1, 1))
                    .build();
        }

        private KisShortSaleResponse.ShortSaleRow allZeroQuantityRow(String date) {
            // 날짜·가격류는 유효(비-null)하되 공매도 수량·거래대금 축은 전부 사실적 0 —
            // 제도상 공매도가 불가한 자산(ETN·COMMODITY)의 정상 응답 형태다(위조·쓰레기 행이 아님).
            return new KisShortSaleResponse.ShortSaleRow(
                    date, "0", "0.0", "0", "0.0", "0", "0.0", "0", "0.0");
        }

        @Test
        @DisplayName("ETN(Q760009) 공매도 전량 0 행 — 정상 저장 경로로 매핑됨(위조·쓰레기 행으로 오판 안 함)")
        void etn_allZeroShortSaleRow_mappedAsValidEntity() {
            List<ShortSaleDomestic> result =
                    mapper.collectValid(
                            etnStock("Q760009"),
                            "Q760009",
                            response(List.of(allZeroQuantityRow("20260612"))),
                            TODAY,
                            WINDOW_START);

            assertThat(result).hasSize(1);
            ShortSaleDomestic row = result.get(0);
            assertThat(row.getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 12));
            assertThat(row.getShortSellQty()).isZero();
            assertThat(row.getShortSellAmt()).isZero();
            assertThat(row.getShortSellAccQty()).isZero();
            assertThat(row.getShortSellAccAmt()).isZero();
        }

        @Test
        @DisplayName("COMMODITY(M04020000) 공매도 전량 0 행 — 정상 저장 경로로 매핑됨(제도상 공매도 불가 자산)")
        void commodity_allZeroShortSaleRow_mappedAsValidEntity() {
            List<ShortSaleDomestic> result =
                    mapper.collectValid(
                            commodityStock("M04020000"),
                            "M04020000",
                            response(List.of(allZeroQuantityRow("20260612"))),
                            TODAY,
                            WINDOW_START);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getShortSellQty()).isZero();
        }
    }
}
