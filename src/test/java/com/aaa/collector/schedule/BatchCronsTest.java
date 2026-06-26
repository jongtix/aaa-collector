package com.aaa.collector.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

@DisplayName("BatchCrons — cron/zone 상수 유효성 및 원본 리터럴 등가 검증 (AC-14)")
class BatchCronsTest {

    @Nested
    @DisplayName("CronExpression 유효성 — 각 cron 상수가 파싱 가능해야 한다")
    class CronValidation {

        @Test
        @DisplayName("DOMESTIC_DAILY_CHAIN_CRON은 유효한 cron 표현식이어야 한다")
        void domesticDailyChainCron_isValid() {
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.DOMESTIC_DAILY_CHAIN_CRON));
        }

        @Test
        @DisplayName("OVERSEAS_DAILY_CRON은 유효한 cron 표현식이어야 한다")
        void overseasDailyCron_isValid() {
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.OVERSEAS_DAILY_CRON));
        }

        @Test
        @DisplayName("OVERSEAS_SHORTSALE_CRON은 유효한 cron 표현식이어야 한다")
        void overseasShortsaleCron_isValid() {
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.OVERSEAS_SHORTSALE_CRON));
        }

        @Test
        @DisplayName("DOMESTIC_INVEST_OPINION_CRON은 유효한 cron 표현식이어야 한다")
        void domesticInvestOpinionCron_isValid() {
            assertThatNoException()
                    .isThrownBy(
                            () -> CronExpression.parse(BatchCrons.DOMESTIC_INVEST_OPINION_CRON));
        }

        @Test
        @DisplayName("DOMESTIC_FINANCIAL_RATIO_CRON은 유효한 cron 표현식이어야 한다")
        void domesticFinancialRatioCron_isValid() {
            assertThatNoException()
                    .isThrownBy(
                            () -> CronExpression.parse(BatchCrons.DOMESTIC_FINANCIAL_RATIO_CRON));
        }

        @Test
        @DisplayName("MACRO_EXTERNAL_CRON은 유효한 cron 표현식이어야 한다")
        void macroExternalCron_isValid() {
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.MACRO_EXTERNAL_CRON));
        }

        @Test
        @DisplayName("MARKET_INDICATORS_CRON은 유효한 cron 표현식이어야 한다")
        void marketIndicatorsCron_isValid() {
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.MARKET_INDICATORS_CRON));
        }

        @Test
        @DisplayName("DOMESTIC_ETF_REPRESENTATIVE_CRON은 유효한 cron 표현식이어야 한다")
        void domesticEtfRepresentativeCron_isValid() {
            assertThatNoException()
                    .isThrownBy(
                            () ->
                                    CronExpression.parse(
                                            BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_CRON));
        }
    }

    @Nested
    @DisplayName("ZoneId 유효성 — 각 zone 상수가 파싱 가능해야 한다")
    class ZoneValidation {

        @Test
        @DisplayName("DOMESTIC_DAILY_CHAIN_ZONE은 유효한 ZoneId이어야 한다")
        void domesticDailyChainZone_isValid() {
            assertThatNoException()
                    .isThrownBy(() -> ZoneId.of(BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE));
        }

        @Test
        @DisplayName("OVERSEAS_DAILY_ZONE은 유효한 ZoneId이어야 한다")
        void overseasDailyZone_isValid() {
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.OVERSEAS_DAILY_ZONE));
        }

        @Test
        @DisplayName("OVERSEAS_SHORTSALE_ZONE은 유효한 ZoneId이어야 한다")
        void overseasShortsaleZone_isValid() {
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.OVERSEAS_SHORTSALE_ZONE));
        }

        @Test
        @DisplayName("DOMESTIC_INVEST_OPINION_ZONE은 유효한 ZoneId이어야 한다")
        void domesticInvestOpinionZone_isValid() {
            assertThatNoException()
                    .isThrownBy(() -> ZoneId.of(BatchCrons.DOMESTIC_INVEST_OPINION_ZONE));
        }

        @Test
        @DisplayName("DOMESTIC_FINANCIAL_RATIO_ZONE은 유효한 ZoneId이어야 한다")
        void domesticFinancialRatioZone_isValid() {
            assertThatNoException()
                    .isThrownBy(() -> ZoneId.of(BatchCrons.DOMESTIC_FINANCIAL_RATIO_ZONE));
        }

        @Test
        @DisplayName("MACRO_EXTERNAL_ZONE은 유효한 ZoneId이어야 한다")
        void macroExternalZone_isValid() {
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.MACRO_EXTERNAL_ZONE));
        }

        @Test
        @DisplayName("MARKET_INDICATORS_ZONE은 유효한 ZoneId이어야 한다")
        void marketIndicatorsZone_isValid() {
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.MARKET_INDICATORS_ZONE));
        }

        @Test
        @DisplayName("DOMESTIC_ETF_REPRESENTATIVE_ZONE은 유효한 ZoneId이어야 한다")
        void domesticEtfRepresentativeZone_isValid() {
            assertThatNoException()
                    .isThrownBy(() -> ZoneId.of(BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_ZONE));
        }
    }

    @Nested
    @DisplayName("원본 리터럴 등가 단언 (AC-14) — 스케줄러 원본과 일치해야 드리프트를 차단한다")
    class LiteralEquality {

        @Test
        @DisplayName("DOMESTIC_DAILY_CHAIN_CRON은 DomesticDailyOhlcvScheduler 원본 리터럴과 일치해야 한다")
        void domesticDailyChainCron_matchesOriginalLiteral() {
            assertEquals("0 0 16 * * MON-FRI", BatchCrons.DOMESTIC_DAILY_CHAIN_CRON);
        }

        @Test
        @DisplayName("DOMESTIC_DAILY_CHAIN_ZONE은 DomesticDailyOhlcvScheduler 원본 리터럴과 일치해야 한다")
        void domesticDailyChainZone_matchesOriginalLiteral() {
            assertEquals("Asia/Seoul", BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE);
        }

        @Test
        @DisplayName("OVERSEAS_DAILY_CRON은 OverseasDailyOhlcvScheduler 원본 리터럴과 일치해야 한다")
        void overseasDailyCron_matchesOriginalLiteral() {
            assertEquals("0 30 16 * * MON-FRI", BatchCrons.OVERSEAS_DAILY_CRON);
        }

        @Test
        @DisplayName("OVERSEAS_DAILY_ZONE은 OverseasDailyOhlcvScheduler 원본 리터럴과 일치해야 한다")
        void overseasDailyZone_matchesOriginalLiteral() {
            assertEquals("America/New_York", BatchCrons.OVERSEAS_DAILY_ZONE);
        }

        @Test
        @DisplayName("OVERSEAS_SHORTSALE_CRON은 ShortSaleOverseasScheduler 원본 리터럴과 일치해야 한다")
        void overseasShortsaleCron_matchesOriginalLiteral() {
            assertEquals("0 0 10 * * MON-FRI", BatchCrons.OVERSEAS_SHORTSALE_CRON);
        }

        @Test
        @DisplayName("OVERSEAS_SHORTSALE_ZONE은 ShortSaleOverseasScheduler 원본 리터럴과 일치해야 한다")
        void overseasShortsaleZone_matchesOriginalLiteral() {
            assertEquals("America/New_York", BatchCrons.OVERSEAS_SHORTSALE_ZONE);
        }

        @Test
        @DisplayName("DOMESTIC_INVEST_OPINION_CRON은 InvestOpinionScheduler 원본 리터럴과 일치해야 한다")
        void domesticInvestOpinionCron_matchesOriginalLiteral() {
            assertEquals("0 0 18 * * MON-FRI", BatchCrons.DOMESTIC_INVEST_OPINION_CRON);
        }

        @Test
        @DisplayName("DOMESTIC_INVEST_OPINION_ZONE은 InvestOpinionScheduler 원본 리터럴과 일치해야 한다")
        void domesticInvestOpinionZone_matchesOriginalLiteral() {
            assertEquals("Asia/Seoul", BatchCrons.DOMESTIC_INVEST_OPINION_ZONE);
        }

        @Test
        @DisplayName("DOMESTIC_FINANCIAL_RATIO_CRON은 FinancialRatioScheduler 원본 리터럴과 일치해야 한다")
        void domesticFinancialRatioCron_matchesOriginalLiteral() {
            assertEquals("0 0 8 * * SAT", BatchCrons.DOMESTIC_FINANCIAL_RATIO_CRON);
        }

        @Test
        @DisplayName("DOMESTIC_FINANCIAL_RATIO_ZONE은 FinancialRatioScheduler 원본 리터럴과 일치해야 한다")
        void domesticFinancialRatioZone_matchesOriginalLiteral() {
            assertEquals("Asia/Seoul", BatchCrons.DOMESTIC_FINANCIAL_RATIO_ZONE);
        }

        @Test
        @DisplayName("MACRO_EXTERNAL_CRON은 MacroExternalScheduler 원본 리터럴과 일치해야 한다")
        void macroExternalCron_matchesOriginalLiteral() {
            assertEquals("0 0 19 * * MON-FRI", BatchCrons.MACRO_EXTERNAL_CRON);
        }

        @Test
        @DisplayName("MACRO_EXTERNAL_ZONE은 MacroExternalScheduler 원본 리터럴과 일치해야 한다")
        void macroExternalZone_matchesOriginalLiteral() {
            assertEquals("Asia/Seoul", BatchCrons.MACRO_EXTERNAL_ZONE);
        }

        @Test
        @DisplayName("MARKET_INDICATORS_CRON은 MarketBatchScheduler 원본 리터럴과 일치해야 한다")
        void marketIndicatorsCron_matchesOriginalLiteral() {
            assertEquals("0 0 17 * * MON-FRI", BatchCrons.MARKET_INDICATORS_CRON);
        }

        @Test
        @DisplayName("MARKET_INDICATORS_ZONE은 MarketBatchScheduler 원본 리터럴과 일치해야 한다")
        void marketIndicatorsZone_matchesOriginalLiteral() {
            assertEquals("Asia/Seoul", BatchCrons.MARKET_INDICATORS_ZONE);
        }

        @Test
        @DisplayName("DOMESTIC_ETF_REPRESENTATIVE_CRON은 EtfRepresentativeScheduler 원본 리터럴과 일치해야 한다")
        void domesticEtfRepresentativeCron_matchesOriginalLiteral() {
            assertEquals("0 50 7 * * MON", BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_CRON);
        }

        @Test
        @DisplayName("DOMESTIC_ETF_REPRESENTATIVE_ZONE은 EtfRepresentativeScheduler 원본 리터럴과 일치해야 한다")
        void domesticEtfRepresentativeZone_matchesOriginalLiteral() {
            assertEquals("Asia/Seoul", BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_ZONE);
        }

        @Test
        @DisplayName("cron 상수 값은 null이 아니어야 한다")
        void allCronConstants_areNotNull() {
            // 개별 등가 단언이 이미 위에서 수행됨 — null 아님은 파생적 불변식
            assertThat(BatchCrons.DOMESTIC_DAILY_CHAIN_CRON).isNotNull();
            assertThat(BatchCrons.OVERSEAS_DAILY_CRON).isNotNull();
            assertThat(BatchCrons.OVERSEAS_SHORTSALE_CRON).isNotNull();
            assertThat(BatchCrons.DOMESTIC_INVEST_OPINION_CRON).isNotNull();
            assertThat(BatchCrons.DOMESTIC_FINANCIAL_RATIO_CRON).isNotNull();
        }

        @Test
        @DisplayName("zone 상수 값은 null이 아니어야 한다")
        void allZoneConstants_areNotNull() {
            assertThat(BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE).isNotNull();
            assertThat(BatchCrons.OVERSEAS_DAILY_ZONE).isNotNull();
            assertThat(BatchCrons.OVERSEAS_SHORTSALE_ZONE).isNotNull();
            assertThat(BatchCrons.DOMESTIC_INVEST_OPINION_ZONE).isNotNull();
            assertThat(BatchCrons.DOMESTIC_FINANCIAL_RATIO_ZONE).isNotNull();
        }

        @Test
        @DisplayName("macro-external/market-indicators cron/zone 상수 값은 null이 아니어야 한다")
        void macroAndMarketConstants_areNotNull() {
            assertThat(BatchCrons.MACRO_EXTERNAL_CRON).isNotNull();
            assertThat(BatchCrons.MACRO_EXTERNAL_ZONE).isNotNull();
            assertThat(BatchCrons.MARKET_INDICATORS_CRON).isNotNull();
            assertThat(BatchCrons.MARKET_INDICATORS_ZONE).isNotNull();
        }

        @Test
        @DisplayName("domestic-etf-representative cron/zone 상수 값은 null이 아니어야 한다")
        void etfConstants_areNotNull() {
            assertThat(BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_CRON).isNotNull();
            assertThat(BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_ZONE).isNotNull();
        }
    }
}
