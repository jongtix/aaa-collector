package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.enums.PeriodType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Stock 도메인 엔티티 스텁 builder 검증")
class EntityBuilderTest {

    private Stock sampleStock() {
        return Stock.builder()
                .symbol("005930")
                .nameKo("삼성전자")
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .listedDate(LocalDate.of(2000, 1, 1))
                .build();
    }

    // ─── CreditBalance ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("CreditBalance")
    class CreditBalanceTests {

        private CreditBalance sample() {
            return CreditBalance.builder()
                    .stock(sampleStock())
                    .tradeDate(LocalDate.of(2026, 1, 1))
                    .loanNewQty(100L)
                    .loanRepayQty(50L)
                    .loanBalanceQty(200L)
                    .loanNewAmt(1_000_000L)
                    .loanRepayAmt(500_000L)
                    .loanBalanceAmt(2_000_000L)
                    .loanBalanceRate(new BigDecimal("1.2345"))
                    .loanSupplyRate(new BigDecimal("0.5000"))
                    .lendNewQty(10L)
                    .lendRepayQty(5L)
                    .lendBalanceQty(20L)
                    .lendNewAmt(100_000L)
                    .lendRepayAmt(50_000L)
                    .lendBalanceAmt(200_000L)
                    .lendBalanceRate(new BigDecimal("0.1234"))
                    .lendSupplyRate(new BigDecimal("0.0500"))
                    .build();
        }

        @Test
        @DisplayName("stock과 tradeDate가 설정된다")
        void creditBalance_stockAndTradeDateSet() {
            CreditBalance cb = sample();
            assertThat(cb.getStock().getSymbol()).isEqualTo("005930");
            assertThat(cb.getTradeDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        }

        @Test
        @DisplayName("대출 수량 필드들이 설정된다")
        void creditBalance_loanQtyFieldsSet() {
            CreditBalance cb = sample();
            assertThat(cb.getLoanNewQty()).isEqualTo(100L);
            assertThat(cb.getLoanRepayQty()).isEqualTo(50L);
            assertThat(cb.getLoanBalanceQty()).isEqualTo(200L);
        }

        @Test
        @DisplayName("대출 금액 필드들이 설정된다")
        void creditBalance_loanAmtFieldsSet() {
            CreditBalance cb = sample();
            assertThat(cb.getLoanNewAmt()).isEqualTo(1_000_000L);
            assertThat(cb.getLoanRepayAmt()).isEqualTo(500_000L);
            assertThat(cb.getLoanBalanceAmt()).isEqualTo(2_000_000L);
        }

        @Test
        @DisplayName("대출 비율 필드들이 설정된다")
        void creditBalance_loanRateFieldsSet() {
            CreditBalance cb = sample();
            assertThat(cb.getLoanBalanceRate()).isEqualByComparingTo("1.2345");
            assertThat(cb.getLoanSupplyRate()).isEqualByComparingTo("0.5000");
        }

        @Test
        @DisplayName("대여 필드들이 설정된다")
        void creditBalance_lendFieldsSet() {
            CreditBalance cb = sample();
            assertThat(cb.getLendNewQty()).isEqualTo(10L);
            assertThat(cb.getLendBalanceAmt()).isEqualTo(200_000L);
            assertThat(cb.getLendBalanceRate()).isEqualByComparingTo("0.1234");
        }

        @Test
        @DisplayName("nullable 비율 필드에 null을 설정할 수 있다")
        void creditBalance_nullableRatesAcceptNull() {
            CreditBalance cb =
                    CreditBalance.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 1, 2))
                            .loanNewQty(0L)
                            .loanRepayQty(0L)
                            .loanBalanceQty(0L)
                            .loanNewAmt(0L)
                            .loanRepayAmt(0L)
                            .loanBalanceAmt(0L)
                            .loanBalanceRate(null)
                            .loanSupplyRate(null)
                            .lendNewQty(0L)
                            .lendRepayQty(0L)
                            .lendBalanceQty(0L)
                            .lendNewAmt(0L)
                            .lendRepayAmt(0L)
                            .lendBalanceAmt(0L)
                            .lendBalanceRate(null)
                            .lendSupplyRate(null)
                            .build();
            assertThat(cb.getLoanBalanceRate()).isNull();
            assertThat(cb.getLendBalanceRate()).isNull();
        }
    }

    // ─── CorporateEvent ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("CorporateEvent")
    class CorporateEventTests {

        @Test
        @DisplayName("DIVIDEND 이벤트의 stock/eventType/eventDate가 설정된다")
        void corporateEvent_stockEventTypeEventDateSet() {
            Stock stock = sampleStock();
            LocalDate eventDate = LocalDate.of(2026, 3, 15);

            CorporateEvent event =
                    CorporateEvent.builder()
                            .stock(stock)
                            .eventType(EventType.DIVIDEND)
                            .eventDate(eventDate)
                            .build();

            assertThat(event.getStock()).isSameAs(stock);
            assertThat(event.getEventType()).isEqualTo(EventType.DIVIDEND);
            assertThat(event.getEventDate()).isEqualTo(eventDate);
        }

        @Test
        @DisplayName("DIVIDEND 이벤트의 금액/비율 필드들이 설정된다")
        void corporateEvent_dividendAmountFieldsSet() {
            CorporateEvent event =
                    CorporateEvent.builder()
                            .stock(sampleStock())
                            .eventType(EventType.DIVIDEND)
                            .eventDate(LocalDate.of(2026, 3, 15))
                            .cashAmount(500L)
                            .cashRate(new BigDecimal("1.5000"))
                            .faceValue(1000L)
                            .highDividendFlag("Y")
                            .build();

            assertThat(event.getCashAmount()).isEqualTo(500L);
            assertThat(event.getCashRate()).isEqualByComparingTo("1.5000");
            assertThat(event.getFaceValue()).isEqualTo(1000L);
            assertThat(event.getHighDividendFlag()).isEqualTo("Y");
        }

        @Test
        @DisplayName("SPLIT eventType을 설정하면 eventType이 SPLIT이다")
        void corporateEvent_splitEventType() {
            CorporateEvent event =
                    CorporateEvent.builder()
                            .stock(sampleStock())
                            .eventType(EventType.SPLIT)
                            .eventDate(LocalDate.of(2026, 1, 1))
                            .build();

            assertThat(event.getEventType()).isEqualTo(EventType.SPLIT);
        }
    }

    // ─── AnalystEstimate ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("AnalystEstimate")
    class AnalystEstimateTests {

        @Test
        @DisplayName("기본 필드들(stock, tradeDate, institutionName)이 설정된다")
        void analystEstimate_baseFieldsSet() {
            Stock stock = sampleStock();
            LocalDate tradeDate = LocalDate.of(2026, 6, 1);

            AnalystEstimate estimate =
                    AnalystEstimate.builder()
                            .stock(stock)
                            .tradeDate(tradeDate)
                            .institutionName("미래에셋")
                            .build();

            assertThat(estimate.getStock()).isSameAs(stock);
            assertThat(estimate.getTradeDate()).isEqualTo(tradeDate);
            assertThat(estimate.getInstitutionName()).isEqualTo("미래에셋");
        }

        @Test
        @DisplayName("투자의견 코드 필드들이 설정된다")
        void analystEstimate_opinionFieldsSet() {
            AnalystEstimate estimate =
                    AnalystEstimate.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 6, 1))
                            .opinion("BUY")
                            .opinionCode("01")
                            .prevOpinion("HOLD")
                            .prevOpinionCode("02")
                            .build();

            assertThat(estimate.getOpinion()).isEqualTo("BUY");
            assertThat(estimate.getOpinionCode()).isEqualTo("01");
            assertThat(estimate.getPrevOpinion()).isEqualTo("HOLD");
        }

        @Test
        @DisplayName("가격/갭 필드들이 설정된다")
        void analystEstimate_priceAndGapFieldsSet() {
            AnalystEstimate estimate =
                    AnalystEstimate.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 6, 1))
                            .targetPrice(80_000L)
                            .prevClose(70_000L)
                            .gapNDay(new BigDecimal("10000.0000"))
                            .build();

            assertThat(estimate.getTargetPrice()).isEqualTo(80_000L);
            assertThat(estimate.getPrevClose()).isEqualTo(70_000L);
            assertThat(estimate.getGapNDay()).isEqualByComparingTo("10000.0000");
        }

        @Test
        @DisplayName("nullable 필드가 null인 경우에도 생성이 성공한다")
        void analystEstimate_nullableFieldsAcceptNull() {
            AnalystEstimate estimate =
                    AnalystEstimate.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 1, 1))
                            .institutionName("DB금융")
                            .build();

            assertThat(estimate.getTargetPrice()).isNull();
            assertThat(estimate.getGapNDay()).isNull();
        }
    }

    // ─── Financial ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Financial")
    class FinancialTests {

        @Test
        @DisplayName("periodType과 periodDate가 설정된다")
        void financial_periodFieldsSet() {
            Financial financial =
                    Financial.builder()
                            .stock(sampleStock())
                            .periodType(PeriodType.ANNUAL)
                            .periodDate(LocalDate.of(2025, 12, 31))
                            .build();

            assertThat(financial.getPeriodType()).isEqualTo(PeriodType.ANNUAL);
            assertThat(financial.getPeriodDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        }

        @Test
        @DisplayName("성장률 필드들이 설정된다")
        void financial_growthFieldsSet() {
            Financial financial =
                    Financial.builder()
                            .stock(sampleStock())
                            .periodType(PeriodType.ANNUAL)
                            .periodDate(LocalDate.of(2025, 12, 31))
                            .revenueGrowth(new BigDecimal("15.2000"))
                            .operatingProfitGrowth(new BigDecimal("20.1000"))
                            .netIncomeGrowth(new BigDecimal("18.5000"))
                            .build();

            assertThat(financial.getRevenueGrowth()).isEqualByComparingTo("15.2000");
            assertThat(financial.getOperatingProfitGrowth()).isEqualByComparingTo("20.1000");
            assertThat(financial.getNetIncomeGrowth()).isEqualByComparingTo("18.5000");
        }

        @Test
        @DisplayName("주당 지표 필드들이 설정된다")
        void financial_perShareFieldsSet() {
            Financial financial =
                    Financial.builder()
                            .stock(sampleStock())
                            .periodType(PeriodType.ANNUAL)
                            .periodDate(LocalDate.of(2025, 12, 31))
                            .eps(5000L)
                            .sps(100_000L)
                            .bps(60_000L)
                            .build();

            assertThat(financial.getEps()).isEqualTo(5000L);
            assertThat(financial.getSps()).isEqualTo(100_000L);
            assertThat(financial.getBps()).isEqualTo(60_000L);
        }

        @Test
        @DisplayName("분기 결산 periodType이 QUARTERLY이다")
        void financial_quarterlyPeriodType() {
            Financial financial =
                    Financial.builder()
                            .stock(sampleStock())
                            .periodType(PeriodType.QUARTERLY)
                            .periodDate(LocalDate.of(2025, 9, 30))
                            .build();

            assertThat(financial.getPeriodType()).isEqualTo(PeriodType.QUARTERLY);
        }
    }

    // ─── InvestorTrend ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("InvestorTrend")
    class InvestorTrendTests {

        @Test
        @DisplayName("stock과 tradeDate가 설정된다")
        void investorTrend_stockAndTradeDateSet() {
            Stock stock = sampleStock();
            LocalDate tradeDate = LocalDate.of(2026, 6, 10);

            InvestorTrend trend =
                    InvestorTrend.builder()
                            .stock(stock)
                            .tradeDate(tradeDate)
                            .foreignNetQty(0L)
                            .institutionNetQty(0L)
                            .individualNetQty(0L)
                            .foreignNetValue(0L)
                            .institutionNetValue(0L)
                            .individualNetValue(0L)
                            .totalVolume(0L)
                            .totalTradingValue(0L)
                            .build();

            assertThat(trend.getStock()).isSameAs(stock);
            assertThat(trend.getTradeDate()).isEqualTo(tradeDate);
        }

        @Test
        @DisplayName("순매수 수량 필드들이 설정된다")
        void investorTrend_netQtyFieldsSet() {
            InvestorTrend trend =
                    InvestorTrend.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 6, 10))
                            .foreignNetQty(10_000L)
                            .institutionNetQty(-5_000L)
                            .individualNetQty(-5_000L)
                            .foreignNetValue(0L)
                            .institutionNetValue(0L)
                            .individualNetValue(0L)
                            .totalVolume(0L)
                            .totalTradingValue(0L)
                            .build();

            assertThat(trend.getForeignNetQty()).isEqualTo(10_000L);
            assertThat(trend.getInstitutionNetQty()).isEqualTo(-5_000L);
            assertThat(trend.getIndividualNetQty()).isEqualTo(-5_000L);
        }

        @Test
        @DisplayName("거래대금 필드들이 설정된다")
        void investorTrend_tradingValueFieldsSet() {
            InvestorTrend trend =
                    InvestorTrend.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 6, 10))
                            .foreignNetQty(0L)
                            .institutionNetQty(0L)
                            .individualNetQty(0L)
                            .foreignNetValue(800_000_000L)
                            .institutionNetValue(-400_000_000L)
                            .individualNetValue(-400_000_000L)
                            .totalVolume(2_000_000L)
                            .totalTradingValue(160_000_000_000L)
                            .build();

            assertThat(trend.getTotalVolume()).isEqualTo(2_000_000L);
            assertThat(trend.getTotalTradingValue()).isEqualTo(160_000_000_000L);
        }
    }

    // ─── ShortSaleDomestic ───────────────────────────────────────────────────

    @Nested
    @DisplayName("ShortSaleDomestic")
    class ShortSaleDomesticTests {

        @Test
        @DisplayName("stock과 tradeDate가 설정된다")
        void shortSaleDomestic_stockAndTradeDateSet() {
            Stock stock = sampleStock();
            LocalDate tradeDate = LocalDate.of(2026, 6, 5);

            ShortSaleDomestic ssd =
                    ShortSaleDomestic.builder()
                            .stock(stock)
                            .tradeDate(tradeDate)
                            .shortSellQty(0L)
                            .shortSellVolRate(BigDecimal.ZERO)
                            .shortSellAmt(0L)
                            .shortSellAmtRate(BigDecimal.ZERO)
                            .shortSellAccQty(0L)
                            .shortSellAccQtyRate(BigDecimal.ZERO)
                            .shortSellAccAmt(0L)
                            .shortSellAccAmtRate(BigDecimal.ZERO)
                            .build();

            assertThat(ssd.getStock()).isSameAs(stock);
            assertThat(ssd.getTradeDate()).isEqualTo(tradeDate);
        }

        @Test
        @DisplayName("공매도 수량/금액 필드들이 설정된다")
        void shortSaleDomestic_shortSellFieldsSet() {
            ShortSaleDomestic ssd =
                    ShortSaleDomestic.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 6, 5))
                            .shortSellQty(50_000L)
                            .shortSellVolRate(new BigDecimal("2.5000"))
                            .shortSellAmt(3_500_000_000L)
                            .shortSellAmtRate(new BigDecimal("1.8000"))
                            .shortSellAccQty(1_000_000L)
                            .shortSellAccQtyRate(new BigDecimal("5.0000"))
                            .shortSellAccAmt(70_000_000_000L)
                            .shortSellAccAmtRate(new BigDecimal("4.5000"))
                            .build();

            assertThat(ssd.getShortSellQty()).isEqualTo(50_000L);
            assertThat(ssd.getShortSellVolRate()).isEqualByComparingTo("2.5000");
            assertThat(ssd.getShortSellAmt()).isEqualTo(3_500_000_000L);
        }

        @Test
        @DisplayName("누적 공매도 필드들이 설정된다")
        void shortSaleDomestic_accShortSellFieldsSet() {
            ShortSaleDomestic ssd =
                    ShortSaleDomestic.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 6, 5))
                            .shortSellQty(0L)
                            .shortSellVolRate(BigDecimal.ZERO)
                            .shortSellAmt(0L)
                            .shortSellAmtRate(BigDecimal.ZERO)
                            .shortSellAccQty(1_000_000L)
                            .shortSellAccQtyRate(new BigDecimal("5.0000"))
                            .shortSellAccAmt(70_000_000_000L)
                            .shortSellAccAmtRate(new BigDecimal("4.5000"))
                            .build();

            assertThat(ssd.getShortSellAccQty()).isEqualTo(1_000_000L);
            assertThat(ssd.getShortSellAccAmtRate()).isEqualByComparingTo("4.5000");
        }
    }

    // ─── ShortSaleOverseas ───────────────────────────────────────────────────

    @Nested
    @DisplayName("ShortSaleOverseas")
    class ShortSaleOverseasTests {

        @Test
        @DisplayName("stock과 tradeDate가 설정된다")
        void shortSaleOverseas_stockAndTradeDateSet() {
            Stock stock = sampleStock();
            LocalDate tradeDate = LocalDate.of(2026, 6, 5);

            ShortSaleOverseas sso =
                    ShortSaleOverseas.builder()
                            .stock(stock)
                            .tradeDate(tradeDate)
                            .shortVolume(100L)
                            .totalVolume(10_000L)
                            .build();

            assertThat(sso.getStock()).isSameAs(stock);
            assertThat(sso.getTradeDate()).isEqualTo(tradeDate);
        }

        @Test
        @DisplayName("볼륨 필드들이 설정된다")
        void shortSaleOverseas_volumeFieldsSet() {
            ShortSaleOverseas sso =
                    ShortSaleOverseas.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 6, 5))
                            .shortVolume(200_000L)
                            .totalVolume(5_000_000L)
                            .build();

            assertThat(sso.getShortVolume()).isEqualTo(200_000L);
            assertThat(sso.getTotalVolume()).isEqualTo(5_000_000L);
        }

        @Test
        @DisplayName("공매도 잔고 필드들이 설정된다")
        void shortSaleOverseas_shortInterestFieldsSet() {
            LocalDate shortInterestDate = LocalDate.of(2026, 5, 31);

            ShortSaleOverseas sso =
                    ShortSaleOverseas.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 6, 5))
                            .shortVolume(100L)
                            .totalVolume(10_000L)
                            .shortInterest(1_000_000L)
                            .floatShares(50_000_000L)
                            .siPctFloat(new BigDecimal("2.0000"))
                            .shortInterestDate(shortInterestDate)
                            .build();

            assertThat(sso.getShortInterest()).isEqualTo(1_000_000L);
            assertThat(sso.getFloatShares()).isEqualTo(50_000_000L);
            assertThat(sso.getSiPctFloat()).isEqualByComparingTo("2.0000");
            assertThat(sso.getShortInterestDate()).isEqualTo(shortInterestDate);
        }

        @Test
        @DisplayName("수집 시각 필드들이 설정된다")
        void shortSaleOverseas_collectedAtFieldsSet() {
            LocalDateTime collectedAt = LocalDateTime.of(2026, 6, 6, 2, 0, 0);

            ShortSaleOverseas sso =
                    ShortSaleOverseas.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 6, 5))
                            .shortVolume(100L)
                            .totalVolume(10_000L)
                            .dailyCollectedAt(collectedAt)
                            .interestCollectedAt(collectedAt)
                            .build();

            assertThat(sso.getDailyCollectedAt()).isEqualTo(collectedAt);
            assertThat(sso.getInterestCollectedAt()).isEqualTo(collectedAt);
        }

        @Test
        @DisplayName("nullable 잔고 필드에 null을 설정할 수 있다")
        void shortSaleOverseas_nullableFieldsAcceptNull() {
            ShortSaleOverseas sso =
                    ShortSaleOverseas.builder()
                            .stock(sampleStock())
                            .tradeDate(LocalDate.of(2026, 1, 1))
                            .shortVolume(100L)
                            .totalVolume(10_000L)
                            .shortInterest(null)
                            .siPctFloat(null)
                            .build();

            assertThat(sso.getShortInterest()).isNull();
            assertThat(sso.getSiPctFloat()).isNull();
        }
    }
}
