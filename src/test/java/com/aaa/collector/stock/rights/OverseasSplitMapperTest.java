package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link OverseasSplitMapper} 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-SPLIT-001
 * REQ-OSPLIT-020/021/029~033/040~043).
 *
 * <p>÷100 정규화(AC-3)·병합 방향(AC-2)·AAPL 3행 주말 dedup(AC-4)·추적 심볼 필터(AC-5)·CRWD 별개 이벤트 미병합(AC-12)·확정여부
 * 필터(AC-14)·방어적 skip(AC-9)을 GuardedKisExecutor 없이 순수 검증한다.
 */
@DisplayName("OverseasSplitMapper 단위 테스트")
class OverseasSplitMapperTest {

    private static final String SPLIT = OverseasSplitMapper.RGHT_TYPE_SPLIT;
    private static final String MERGE = OverseasSplitMapper.RGHT_TYPE_MERGE;

    private final OverseasSplitMapper mapper = new OverseasSplitMapper();

    private Stock stock(String symbol, Market market) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo(symbol + "테스트")
                .market(market)
                .assetType(AssetType.STOCK)
                .active(true)
                .build();
    }

    private Map<String, Stock> trackedMap(Stock... stocks) {
        Map<String, Stock> map = new LinkedHashMap<>();
        for (Stock s : stocks) {
            map.put(s.getSymbol(), s);
        }
        return map;
    }

    /**
     * 분할/병합 행 생성 헬퍼. {@code prdtTypeCd}·{@code acplBassDt}는 이벤트 동일성 그룹핑에 영향을 준다/안 준다 검증용으로 파라미터화한다.
     */
    private KisPeriodRightsResponse.PeriodRightsRow row(
            String pdno,
            String bassDt,
            String acplBassDt,
            String stckAlctRt,
            String prdtTypeCd,
            String dfntYn) {
        return new KisPeriodRightsResponse.PeriodRightsRow(
                bassDt,
                SPLIT,
                pdno,
                pdno + " INC",
                prdtTypeCd,
                "US0000000000",
                acplBassDt,
                "",
                "",
                "0",
                stckAlctRt,
                "USD",
                "",
                "",
                "",
                "0",
                "0",
                "0",
                "0",
                dfntYn);
    }

    /** 확정(dfnt_yn=Y) 분할 행 — acpl_bass_dt=bass_dt, prdt_type_cd=512. */
    private KisPeriodRightsResponse.PeriodRightsRow confirmedSplit(
            String pdno, String bassDt, String stckAlctRt) {
        return row(pdno, bassDt, bassDt, stckAlctRt, "512", "Y");
    }

    @Nested
    @DisplayName("AC-3: ÷100 스케일 정규화 (REQ-OSPLIT-040)")
    class ScaleNormalization {

        @Test
        @DisplayName("AAPL 400.0 → 4.0000 (event_type=SPLIT, event_subtype='분할')")
        void aapl400_normalizedTo4() {
            Stock aapl = stock("AAPL", Market.NASDAQ);
            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(
                            List.of(confirmedSplit("AAPL", "20200831", "400.0")),
                            SPLIT,
                            trackedMap(aapl));

            assertThat(result.events()).hasSize(1);
            CorporateEvent e = result.events().getFirst();
            assertThat(e.getEventType()).isEqualTo(EventType.SPLIT);
            assertThat(e.getEventSubtype()).isEqualTo("분할");
            assertThat(e.getStockRate()).isEqualByComparingTo(new BigDecimal("4.0000"));
        }

        @Test
        @DisplayName("TSLA 300.0→3.0000, BKNG 2500.0→25.0000 (배율 다양성)")
        void variousRatios_normalized() {
            Stock tsla = stock("TSLA", Market.NASDAQ);
            Stock bkng = stock("BKNG", Market.NASDAQ);
            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(
                            List.of(
                                    confirmedSplit("TSLA", "20220825", "300.0"),
                                    confirmedSplit("BKNG", "20240603", "2500.0")),
                            SPLIT,
                            trackedMap(tsla, bkng));

            assertThat(result.events())
                    .extracting(CorporateEvent::getStockRate)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactlyInAnyOrder(new BigDecimal("3.0000"), new BigDecimal("25.0000"));
        }
    }

    @Nested
    @DisplayName("AC-2: 병합(리버스 스플릿) 방향 (REQ-OSPLIT-041)")
    class MergeDirection {

        @Test
        @DisplayName("GE 12.5(RGHT_TYPE_CD=15) → event_subtype='병합', stock_rate=0.1250")
        void ge_reverseSplit_mappedAsMerge() {
            Stock ge = stock("GE", Market.NYSE);
            KisPeriodRightsResponse.PeriodRightsRow geRow =
                    new KisPeriodRightsResponse.PeriodRightsRow(
                            "20210802",
                            MERGE,
                            "GE",
                            "GENERAL ELECTRIC",
                            "513",
                            "US3696043013",
                            "20210802",
                            "",
                            "",
                            "0",
                            "12.5",
                            "USD",
                            "",
                            "",
                            "",
                            "0",
                            "0",
                            "0",
                            "0",
                            "Y");

            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(List.of(geRow), MERGE, trackedMap(ge));

            assertThat(result.events()).hasSize(1);
            CorporateEvent e = result.events().getFirst();
            assertThat(e.getEventSubtype()).isEqualTo("병합");
            assertThat(e.getStockRate()).isEqualByComparingTo(new BigDecimal("0.1250"));
        }
    }

    @Nested
    @DisplayName("AC-4: AAPL 3행 중복 dedup — 주말 bass_dt 제외 + 평일 최댓값 (REQ-OSPLIT-029/030/031)")
    class AaplDedup {

        @Test
        @DisplayName("bass_dt 20200829(토)/20200830(일)/20200831(월) → 20200831 1건만 채택")
        void weekendRowsExcluded_maxWeekdayAdopted() {
            Stock aapl = stock("AAPL", Market.NASDAQ);
            // acpl_bass_dt를 서로 다르게 둬도 동일 그룹(그룹핑 키에서 제외됨)
            KisPeriodRightsResponse.PeriodRightsRow sat =
                    row("AAPL", "20200829", "20200827", "400.0", "512", "Y");
            KisPeriodRightsResponse.PeriodRightsRow sun =
                    row("AAPL", "20200830", "20200828", "400.0", "512", "Y");
            KisPeriodRightsResponse.PeriodRightsRow mon =
                    row("AAPL", "20200831", "20200831", "400.0", "512", "Y");

            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(List.of(sat, sun, mon), SPLIT, trackedMap(aapl));

            assertThat(result.events()).hasSize(1);
            assertThat(result.events().getFirst().getEventDate())
                    .isEqualTo(LocalDate.of(2020, 8, 31));
        }

        @Test
        @DisplayName("모든 행이 주말 bass_dt → 이벤트 skip (REQ-OSPLIT-032), skippedNoWeekday 집계")
        void allWeekend_skipEvent() {
            Stock aapl = stock("AAPL", Market.NASDAQ);
            KisPeriodRightsResponse.PeriodRightsRow sat =
                    row("AAPL", "20200829", "20200829", "400.0", "512", "Y");
            KisPeriodRightsResponse.PeriodRightsRow sun =
                    row("AAPL", "20200830", "20200830", "400.0", "512", "Y");

            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(List.of(sat, sun), SPLIT, trackedMap(aapl));

            assertThat(result.events()).isEmpty();
            assertThat(result.skippedNoWeekday()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AC-5: 시장 필터 — 추적 심볼만, prdt_type_cd 미사용 (REQ-OSPLIT-021)")
    class MarketFilter {

        @Test
        @DisplayName("ETF(prdt_type_cd=515) 추적 심볼 MGK는 저장, 비추적 중국(552)은 제외")
        void etfTracked_kept_untrackedExcluded() {
            Stock mgk = stock("MGK", Market.NYSE);
            KisPeriodRightsResponse.PeriodRightsRow mgkRow =
                    row("MGK", "20240603", "20240603", "500.0", "515", "Y");
            KisPeriodRightsResponse.PeriodRightsRow chinaRow =
                    row("00700", "20240603", "20240603", "200.0", "552", "Y");

            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(List.of(mgkRow, chinaRow), SPLIT, trackedMap(mgk));

            assertThat(result.events()).hasSize(1);
            assertThat(result.events().getFirst().getStock().getSymbol()).isEqualTo("MGK");
            assertThat(result.skippedUntracked()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AC-12: CRWD 별개 이벤트 미병합 — stck_alct_rt 다르면 별개 그룹 (REQ-OSPLIT-033)")
    class DistinctEventsNotMerged {

        @Test
        @DisplayName("같은 pdno·다른 stck_alct_rt(300↔400)·다른 평일 bass_dt → 2건 각각 보존")
        void sameSymbolDifferentRate_twoEvents() {
            Stock crwd = stock("CRWD", Market.NASDAQ);
            KisPeriodRightsResponse.PeriodRightsRow ev1 =
                    confirmedSplit("CRWD", "20230815", "300.0");
            KisPeriodRightsResponse.PeriodRightsRow ev2 =
                    confirmedSplit("CRWD", "20240819", "400.0");

            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(List.of(ev1, ev2), SPLIT, trackedMap(crwd));

            assertThat(result.events()).hasSize(2);
            assertThat(result.events())
                    .extracting(CorporateEvent::getStockRate)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactlyInAnyOrder(new BigDecimal("3.0000"), new BigDecimal("4.0000"));
            assertThat(result.events())
                    .extracting(CorporateEvent::getEventDate)
                    .containsExactlyInAnyOrder(
                            LocalDate.of(2023, 8, 15), LocalDate.of(2024, 8, 19));
        }
    }

    @Nested
    @DisplayName("AC-14: 확정여부(dfnt_yn=Y) 필터 (REQ-OSPLIT-020)")
    class ConfirmedFilter {

        @Test
        @DisplayName("dfnt_yn=N(예정) 행 제외, dfnt_yn=Y(확정) 행만 저장")
        void pendingExcluded_confirmedKept() {
            Stock aapl = stock("AAPL", Market.NASDAQ);
            KisPeriodRightsResponse.PeriodRightsRow pending =
                    row("AAPL", "20250815", "20250815", "200.0", "512", "N");
            KisPeriodRightsResponse.PeriodRightsRow confirmed =
                    confirmedSplit("AAPL", "20200831", "400.0");

            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(List.of(pending, confirmed), SPLIT, trackedMap(aapl));

            assertThat(result.events()).hasSize(1);
            assertThat(result.events().getFirst().getEventDate())
                    .isEqualTo(LocalDate.of(2020, 8, 31));
            assertThat(result.skippedUnconfirmed()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AC-9: 방어적 skip (REQ-OSPLIT-042/043) + 미사용 컬럼 null")
    class DefensiveSkip {

        @Test
        @DisplayName("정규화 stock_rate가 DECIMAL(12,4) 정수부 경계(10^8) 초과 → skip, skippedInvalidRate 집계")
        void rateOverflow_skipped() {
            Stock aapl = stock("AAPL", Market.NASDAQ);
            // stck_alct_rt = 10^11 → ÷100 = 10^9 (정수부 10자리 > 8자리 경계)
            KisPeriodRightsResponse.PeriodRightsRow overflow =
                    confirmedSplit("AAPL", "20200831", "100000000000");

            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(List.of(overflow), SPLIT, trackedMap(aapl));

            assertThat(result.events()).isEmpty();
            assertThat(result.skippedInvalidRate()).isEqualTo(1);
        }

        @Test
        @DisplayName("stck_alct_rt 파싱 불가 → skip")
        void unparsableRate_skipped() {
            Stock aapl = stock("AAPL", Market.NASDAQ);
            KisPeriodRightsResponse.PeriodRightsRow bad = confirmedSplit("AAPL", "20200831", "N/A");

            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(List.of(bad), SPLIT, trackedMap(aapl));

            assertThat(result.events()).isEmpty();
            assertThat(result.skippedInvalidRate()).isEqualTo(1);
        }

        @Test
        @DisplayName("bass_dt 파싱 불가 → skip, skippedUnparsableDate 집계")
        void unparsableBassDt_skipped() {
            Stock aapl = stock("AAPL", Market.NASDAQ);
            KisPeriodRightsResponse.PeriodRightsRow bad =
                    confirmedSplit("AAPL", "not-a-date", "400.0");

            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(List.of(bad), SPLIT, trackedMap(aapl));

            assertThat(result.events()).isEmpty();
            assertThat(result.skippedUnparsableDate()).isEqualTo(1);
        }

        @Test
        @DisplayName(
                "SPLIT 행 cash_amount·cash_rate·currency_code·face_value는 null (REQ-OSPLIT-042)")
        void unusedColumns_null() {
            Stock aapl = stock("AAPL", Market.NASDAQ);
            OverseasSplitMapper.MapResult result =
                    mapper.mapRows(
                            List.of(confirmedSplit("AAPL", "20200831", "400.0")),
                            SPLIT,
                            trackedMap(aapl));

            CorporateEvent e = result.events().getFirst();
            assertThat(e)
                    .extracting(
                            CorporateEvent::getCashAmount,
                            CorporateEvent::getCashRate,
                            CorporateEvent::getCurrencyCode,
                            CorporateEvent::getFaceValue)
                    .containsOnlyNulls();
        }
    }
}
