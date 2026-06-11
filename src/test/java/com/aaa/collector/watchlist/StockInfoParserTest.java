package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StockInfoParserTest {

    private final StockInfoParser parser = new StockInfoParser();

    private static KisDomesticStockInfoResponse.Output domesticOut(
            String grp, String nameEn, String sctsDt, String idxCode, String erngRt, String tpCd) {
        return new KisDomesticStockInfoResponse.Output(
                grp, nameEn, sctsDt, "", idxCode, erngRt, tpCd);
    }

    private static KisOverseasStockInfoResponse.Output overseasOut(
            String dvsn,
            String riskCd,
            String nameEn,
            String lstgDt,
            String idxCode,
            String erngRt) {
        return new KisOverseasStockInfoResponse.Output(
                dvsn, riskCd, nameEn, lstgDt, idxCode, erngRt);
    }

    @Nested
    @DisplayName("parseDomestic — 국내 ETF 메타 추출")
    class DomesticEtfMeta {

        @Test
        @DisplayName("레버리지 2배 ETF (erngRt=2.00) — leverage=2, inverse=false")
        void leverageEtf() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOut("EF", "Leverage ETF", "20200101", "069500", "2.00", ""),
                            Market.KOSPI);

            assertThat(info.assetType()).isEqualTo(AssetType.ETF);
            assertThat(info.etfMetaInfo().leverage()).isEqualTo(2);
            assertThat(info.etfMetaInfo().inverse()).isFalse();
        }

        @Test
        @DisplayName("인버스 ETF (erngRt=-1.00) — inverse=true")
        void inverseEtf() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOut("EF", "Inverse ETF", "20200101", "069500", "-1.00", ""),
                            Market.KOSPI);

            assertThat(info.etfMetaInfo().inverse()).isTrue();
            assertThat(info.etfMetaInfo().leverage()).isEqualTo(1);
        }

        @Test
        @DisplayName("erngRt=0 — leverage 0이면 1로 보정")
        void zeroRatioFallsBackToLeverageOne() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOut("EF", "ETF", "20200101", "069500", "0", ""), Market.KOSPI);

            assertThat(info.etfMetaInfo().leverage()).isEqualTo(1);
        }

        @Test
        @DisplayName("erngRt 파싱 불가 문자열 — leverage=1, inverse=false fallback")
        void malformedRatioFallback() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOut("EF", "ETF", "20200101", "069500", "abc", ""),
                            Market.KOSPI);

            assertThat(info.etfMetaInfo().leverage()).isEqualTo(1);
            assertThat(info.etfMetaInfo().inverse()).isFalse();
        }

        @Test
        @DisplayName("헤지 ETF (tpCd 끝에 H) — hedged=true")
        void hedgedEtfByHSuffix() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOut("EF", "Hedged ETF", "20200101", "069500", "1.00", "01H"),
                            Market.KOSPI);

            assertThat(info.etfMetaInfo().hedged()).isTrue();
        }

        @Test
        @DisplayName("헤지 ETF (tpCd에 HEDGE 포함) — hedged=true")
        void hedgedEtfByHedgeKeyword() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOut("EF", "Hedged ETF", "20200101", "069500", "1.00", "HEDGE1"),
                            Market.KOSPI);

            assertThat(info.etfMetaInfo().hedged()).isTrue();
        }

        @Test
        @DisplayName("비헤지 ETF (tpCd blank) — hedged=false")
        void notHedgedWhenBlankTpCd() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOut("EF", "ETF", "20200101", "069500", "1.00", ""),
                            Market.KOSPI);

            assertThat(info.etfMetaInfo().hedged()).isFalse();
        }
    }

    @Nested
    @DisplayName("parseOverseas — 해외 ETF 메타 추출")
    class OverseasEtfMeta {

        @Test
        @DisplayName("레버리지 해외 ETF (dvsn=03, erngRt=3.00) — leverage=3")
        void leverageOverseasEtf() {
            StockInfo info =
                    parser.parseOverseas(
                            overseasOut("03", "001", "TQQQ", "20100201", "NDX", "3.00"));

            assertThat(info.assetType()).isEqualTo(AssetType.ETF);
            assertThat(info.etfMetaInfo().leverage()).isEqualTo(3);
            assertThat(info.etfMetaInfo().inverse()).isFalse();
        }

        @Test
        @DisplayName("인버스 해외 ETF (erngRt=-2.00) — inverse=true, leverage=2")
        void inverseOverseasEtf() {
            StockInfo info =
                    parser.parseOverseas(
                            overseasOut("03", "001", "SQQQ", "20100201", "NDX", "-2.00"));

            assertThat(info.etfMetaInfo().inverse()).isTrue();
            assertThat(info.etfMetaInfo().leverage()).isEqualTo(2);
        }

        @Test
        @DisplayName("해외 ETF erngRt 파싱 불가 — leverage=1 fallback")
        void overseasMalformedRatioFallback() {
            StockInfo info =
                    parser.parseOverseas(overseasOut("03", "001", "ETF", "20100201", "NDX", "xx"));

            assertThat(info.etfMetaInfo().leverage()).isEqualTo(1);
        }

        @Test
        @DisplayName("해외 ETF erngRt=0 — leverage 1로 보정")
        void overseasZeroRatio() {
            StockInfo info =
                    parser.parseOverseas(overseasOut("03", "001", "ETF", "20100201", "NDX", "0"));

            assertThat(info.etfMetaInfo().leverage()).isEqualTo(1);
        }
    }
}
