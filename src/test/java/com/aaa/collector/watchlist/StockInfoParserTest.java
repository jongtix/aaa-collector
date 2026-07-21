package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.ListingStatus;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001

class StockInfoParserTest {

    private final StockInfoParser parser = new StockInfoParser();

    /** 상폐/거래정지 필드는 기본값(미상폐·미정지)으로 채운 편의 오버로드 — 기존 호출부 전부 무변경. */
    private static KisDomesticStockInfoResponse.Output domesticOutWithMketId(
            String grp,
            String nameEn,
            String sctsDt,
            String kosdaqDt,
            String mketIdCd,
            String idxCode,
            String erngRt,
            String tpCd) {
        return domesticOutWithMketId(
                grp, nameEn, sctsDt, kosdaqDt, mketIdCd, idxCode, erngRt, tpCd, "", "N");
    }

    private static KisDomesticStockInfoResponse.Output domesticOutWithMketId(
            String grp,
            String nameEn,
            String sctsDt,
            String kosdaqDt,
            String mketIdCd,
            String idxCode,
            String erngRt,
            String tpCd,
            String lstgAbolDt,
            String trStopYn) {
        return new KisDomesticStockInfoResponse.Output(
                grp,
                nameEn,
                sctsDt,
                kosdaqDt,
                mketIdCd,
                idxCode,
                erngRt,
                tpCd,
                lstgAbolDt,
                trStopYn);
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
                            domesticOutWithMketId(
                                    "EF",
                                    "Leverage ETF",
                                    "20200101",
                                    "",
                                    "STK",
                                    "069500",
                                    "2.00",
                                    ""),
                            "069500");

            assertThat(info.assetType()).isEqualTo(AssetType.ETF);
            assertThat(info.etfMetaInfo().leverage()).isEqualTo(2);
            assertThat(info.etfMetaInfo().inverse()).isFalse();
        }

        @Test
        @DisplayName("인버스 ETF (erngRt=-1.00) — inverse=true")
        void inverseEtf() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOutWithMketId(
                                    "EF",
                                    "Inverse ETF",
                                    "20200101",
                                    "",
                                    "STK",
                                    "069500",
                                    "-1.00",
                                    ""),
                            "069500");

            assertThat(info.etfMetaInfo().inverse()).isTrue();
            assertThat(info.etfMetaInfo().leverage()).isEqualTo(1);
        }

        @Test
        @DisplayName("erngRt=0 — leverage 0이면 1로 보정")
        void zeroRatioFallsBackToLeverageOne() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOutWithMketId(
                                    "EF", "ETF", "20200101", "", "STK", "069500", "0", ""),
                            "069500");

            assertThat(info.etfMetaInfo().leverage()).isEqualTo(1);
        }

        @Test
        @DisplayName("erngRt 파싱 불가 문자열 — leverage=1, inverse=false fallback")
        void malformedRatioFallback() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOutWithMketId(
                                    "EF", "ETF", "20200101", "", "STK", "069500", "abc", ""),
                            "069500");

            assertThat(info.etfMetaInfo().leverage()).isEqualTo(1);
            assertThat(info.etfMetaInfo().inverse()).isFalse();
        }

        @Test
        @DisplayName("헤지 ETF (tpCd 끝에 H) — hedged=true")
        void hedgedEtfByHSuffix() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOutWithMketId(
                                    "EF",
                                    "Hedged ETF",
                                    "20200101",
                                    "",
                                    "STK",
                                    "069500",
                                    "1.00",
                                    "01H"),
                            "069500");

            assertThat(info.etfMetaInfo().hedged()).isTrue();
        }

        @Test
        @DisplayName("헤지 ETF (tpCd에 HEDGE 포함) — hedged=true")
        void hedgedEtfByHedgeKeyword() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOutWithMketId(
                                    "EF",
                                    "Hedged ETF",
                                    "20200101",
                                    "",
                                    "STK",
                                    "069500",
                                    "1.00",
                                    "HEDGE1"),
                            "069500");

            assertThat(info.etfMetaInfo().hedged()).isTrue();
        }

        @Test
        @DisplayName("비헤지 ETF (tpCd blank) — hedged=false")
        void notHedgedWhenBlankTpCd() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOutWithMketId(
                                    "EF", "ETF", "20200101", "", "STK", "069500", "1.00", ""),
                            "069500");

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
                            overseasOut("03", "001", "TQQQ", "20100201", "NDX", "3.00"),
                            Market.NASDAQ);

            assertThat(info.assetType()).isEqualTo(AssetType.ETF);
            assertThat(info.etfMetaInfo().leverage()).isEqualTo(3);
            assertThat(info.etfMetaInfo().inverse()).isFalse();
        }

        @Test
        @DisplayName("인버스 해외 ETF (erngRt=-2.00) — inverse=true, leverage=2")
        void inverseOverseasEtf() {
            StockInfo info =
                    parser.parseOverseas(
                            overseasOut("03", "001", "SQQQ", "20100201", "NDX", "-2.00"),
                            Market.NASDAQ);

            assertThat(info.etfMetaInfo().inverse()).isTrue();
            assertThat(info.etfMetaInfo().leverage()).isEqualTo(2);
        }

        @Test
        @DisplayName("해외 ETF erngRt 파싱 불가 — leverage=1 fallback")
        void overseasMalformedRatioFallback() {
            StockInfo info =
                    parser.parseOverseas(
                            overseasOut("03", "001", "ETF", "20100201", "NDX", "xx"),
                            Market.NASDAQ);

            assertThat(info.etfMetaInfo().leverage()).isEqualTo(1);
        }

        @Test
        @DisplayName("해외 ETF erngRt=0 — leverage 1로 보정")
        void overseasZeroRatio() {
            StockInfo info =
                    parser.parseOverseas(
                            overseasOut("03", "001", "ETF", "20100201", "NDX", "0"), Market.NASDAQ);

            assertThat(info.etfMetaInfo().leverage()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("parseDomestic — mket_id_cd 기반 시장 확정 (REQ-STOCKMETA-001, AC-1, AC-2)")
    class DomesticMarketFromMketIdCd {

        @Test
        @DisplayName("mket_id_cd=STK → KOSPI, scts_mket_lstg_dt 선택 (AC-1 — NAVER fid=UN 케이스)")
        void parseDomestic_mketIdCdStk_returnsKospiAndSctsDate() {
            // Arrange: NAVER fid=UN이지만 mket_id_cd=STK(KOSPI)인 케이스
            KisDomesticStockInfoResponse.Output out =
                    domesticOutWithMketId(
                            "ST", "NAVER Corp", "20020606", "20020101", "STK", "", "", "");

            // Act
            StockInfo info = parser.parseDomestic(out, "035420");

            // Assert
            assertThat(info).isNotNull();
            assertThat(info.market()).isEqualTo(Market.KOSPI); // mket_id_cd=STK → KOSPI
            assertThat(info.listedDate()).isEqualTo(LocalDate.of(2002, 6, 6)); // sctsMketLstgDt 선택
        }

        @Test
        @DisplayName("mket_id_cd=KSQ → KOSDAQ, kosdaq_mket_lstg_dt 선택 (AC-2 — 에코프로비엠 fid=J 케이스)")
        void parseDomestic_mketIdCdKsq_returnsKosdaqAndKosdaqDate() {
            // Arrange: 에코프로비엠 fid=J이지만 mket_id_cd=KSQ(KOSDAQ)인 케이스
            KisDomesticStockInfoResponse.Output out =
                    domesticOutWithMketId(
                            "ST", "EcoPro BM", "19990101", "20190628", "KSQ", "", "", "");

            // Act
            StockInfo info = parser.parseDomestic(out, "247540");

            // Assert
            assertThat(info).isNotNull();
            assertThat(info.market()).isEqualTo(Market.KOSDAQ); // mket_id_cd=KSQ → KOSDAQ
            assertThat(info.listedDate())
                    .isEqualTo(LocalDate.of(2019, 6, 28)); // kosdaqMketLstgDt 선택
        }

        @Test
        @DisplayName("mket_id_cd=KNX(코넥스) 미매핑 → null 반환 (기존 WARN-드롭 정책)")
        void parseDomestic_mketIdCdKnx_returnsNull() {
            KisDomesticStockInfoResponse.Output out =
                    domesticOutWithMketId("ST", "코넥스종목", "20200101", "", "KNX", "", "", "");

            StockInfo info = parser.parseDomestic(out, "KONEX001");

            assertThat(info).isNull();
        }

        @Test
        @DisplayName("mket_id_cd 공백 → null 반환")
        void parseDomestic_mketIdCdBlank_returnsNull() {
            KisDomesticStockInfoResponse.Output out =
                    domesticOutWithMketId("ST", "종목", "20200101", "", "", "", "", "");

            StockInfo info = parser.parseDomestic(out, "TEST001");

            assertThat(info).isNull();
        }

        @Test
        @DisplayName("mket_id_cd=STK + ETF — ETF 메타 포함, KOSPI 반환")
        void parseDomestic_mketIdCdStkEtf_returnsEtfWithMarket() {
            KisDomesticStockInfoResponse.Output out =
                    domesticOutWithMketId(
                            "EF", "KODEX 200", "20020414", "", "STK", "069500", "1.00", "");

            StockInfo info = parser.parseDomestic(out, "069500");

            assertThat(info).isNotNull();
            assertThat(info.market()).isEqualTo(Market.KOSPI);
            assertThat(info.assetType()).isEqualTo(AssetType.ETF);
            assertThat(info.etfMetaInfo()).isNotNull();
        }
    }

    @Nested
    @DisplayName("parseOverseas — 해외 종목 market 필드 포함 (REQ-STOCKMETA-001)")
    class OverseasMarket {

        @Test
        @DisplayName("NASDAQ 종목 — StockInfo.market()=NASDAQ")
        void parseOverseas_nasdaqMarket_returnedInStockInfo() {
            StockInfo info =
                    parser.parseOverseas(
                            overseasOut("01", "000", "Apple", "19801212", "", ""), Market.NASDAQ);

            assertThat(info.market()).isEqualTo(Market.NASDAQ);
        }

        @Test
        @DisplayName("NYSE 종목 상장일 공백 — listedDate=null, market=NYSE")
        void parseOverseas_nyseBlankDate_nullDateWithMarket() {
            StockInfo info =
                    parser.parseOverseas(
                            overseasOut("01", "000", "JPMorgan", "    /  /", "", ""), Market.NYSE);

            assertThat(info.listedDate()).isNull();
            assertThat(info.market()).isEqualTo(Market.NYSE);
        }
    }

    @Nested
    @DisplayName("parseDate — 상장일 파싱 (KIS '날짜 없음' sentinel 처리)")
    class DateParsing {

        @Test
        @DisplayName("해외 정상 상장일 (YYYYMMDD) — 정확히 파싱")
        void overseasValidDate() {
            StockInfo info =
                    parser.parseOverseas(
                            overseasOut("01", "000", "Apple", "19801212", "", ""), Market.NASDAQ);

            assertThat(info.listedDate()).isEqualTo(LocalDate.of(1980, 12, 12));
        }

        @Test
        @DisplayName("해외 상장일 슬래시 템플릿 '    /  /' — listedDate=null, 종목은 유지 (JPM·GS 케이스)")
        void overseasBlankSlashTemplate() {
            StockInfo info =
                    parser.parseOverseas(
                            overseasOut("01", "000", "JPMorgan", "    /  /", "", ""), Market.NYSE);

            assertThat(info.listedDate()).isNull();
            assertThat(info.assetType()).isEqualTo(AssetType.STOCK);
            assertThat(info.nameEn()).isEqualTo("JPMorgan");
        }

        @Test
        @DisplayName("해외 상장일 전부 0 (00000000) — listedDate=null")
        void overseasAllZeros() {
            StockInfo info =
                    parser.parseOverseas(
                            overseasOut("01", "000", "X", "00000000", "", ""), Market.NYSE);

            assertThat(info.listedDate()).isNull();
        }

        @Test
        @DisplayName("국내 상장일 정상 — 정확히 파싱")
        void domesticValidDate() {
            StockInfo info =
                    parser.parseDomestic(
                            domesticOutWithMketId(
                                    "ST", "Samsung", "19750611", "", "STK", "", "", ""),
                            "005930");

            assertThat(info.listedDate()).isEqualTo(LocalDate.of(1975, 6, 11));
        }
    }

    @Nested
    @DisplayName("parseDomestic — 상장폐지·거래정지 판정 (REQ-WLSYNC-142, 실측 010620 HD현대미포)")
    class DomesticDelistingDetection {

        @Test
        @DisplayName("시나리오 2 — lstg_abol_dt=20251215 채워짐 → 상장폐지, rt_cd=0이라도 판정 무관")
        void lstgAbolDtFilled_returnsDelistedWithDate() {
            KisDomesticStockInfoResponse.Output out =
                    domesticOutWithMketId(
                            "ST",
                            "HD Hyundai Mipo",
                            "19960101",
                            "",
                            "STK",
                            "",
                            "",
                            "",
                            "20251215",
                            "Y");

            StockInfo info = parser.parseDomestic(out, "010620");

            assertThat(info.listingStatus()).isEqualTo(ListingStatus.DELISTED);
            assertThat(info.delistedAt()).isEqualTo(LocalDate.of(2025, 12, 15));
        }

        @Test
        @DisplayName("시나리오 3 — lstg_abol_dt 빈 값 + tr_stop_yn=Y → 거래정지(가역), 상폐일자 없음")
        void trStopYnOnlyWithoutAbolDate_returnsHaltedNotDelisted() {
            KisDomesticStockInfoResponse.Output out =
                    domesticOutWithMketId(
                            "ST", "Halted Stock", "20100101", "", "STK", "", "", "", "", "Y");

            StockInfo info = parser.parseDomestic(out, "999001");

            assertThat(info.listingStatus()).isEqualTo(ListingStatus.HALTED);
            assertThat(info.delistedAt()).isNull();
        }

        @Test
        @DisplayName("시나리오 4 — lstg_abol_dt 빈 값 + tr_stop_yn=N(삼성전자) → 정상, 기존 필드 무영향")
        void normalStock_returnsNormalAndPreservesExistingFields() {
            KisDomesticStockInfoResponse.Output out =
                    domesticOutWithMketId(
                            "ST",
                            "Samsung Electronics",
                            "19750611",
                            "",
                            "STK",
                            "",
                            "",
                            "",
                            "",
                            "N");

            StockInfo info = parser.parseDomestic(out, "005930");

            assertThat(info.listingStatus()).isEqualTo(ListingStatus.NORMAL);
            assertThat(info.delistedAt()).isNull();
            assertThat(info.assetType()).isEqualTo(AssetType.STOCK);
            assertThat(info.market()).isEqualTo(Market.KOSPI);
            assertThat(info.listedDate()).isEqualTo(LocalDate.of(1975, 6, 11));
        }

        @Test
        @DisplayName("Edge case — lstg_abol_dt='00000000'(전부 0) sentinel → 상폐 아님(빈 값 취급)")
        void allZeroSentinelDate_notTreatedAsDelisted() {
            KisDomesticStockInfoResponse.Output out =
                    domesticOutWithMketId(
                            "ST", "종목", "20100101", "", "STK", "", "", "", "00000000", "N");

            StockInfo info = parser.parseDomestic(out, "999002");

            assertThat(info.listingStatus()).isEqualTo(ListingStatus.NORMAL);
            assertThat(info.delistedAt()).isNull();
        }

        @Test
        @DisplayName("Edge case — 이미 상폐(lstg_abol_dt 채워짐) 종목에 tr_stop_yn=Y 동시 — 상폐 판정 우선")
        void delistedTakesPriorityOverHaltFlag() {
            KisDomesticStockInfoResponse.Output out =
                    domesticOutWithMketId(
                            "EF",
                            "PLUS TDF2050",
                            "20200101",
                            "",
                            "STK",
                            "",
                            "",
                            "",
                            "20251230",
                            "Y");

            StockInfo info = parser.parseDomestic(out, "433870");

            assertThat(info.listingStatus()).isEqualTo(ListingStatus.DELISTED);
            assertThat(info.delistedAt()).isEqualTo(LocalDate.of(2025, 12, 30));
        }
    }
}
