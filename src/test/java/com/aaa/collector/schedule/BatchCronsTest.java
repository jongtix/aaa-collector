package com.aaa.collector.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        @Test
        @DisplayName("USDKRW_DAILY_CRON은 유효한 cron 표현식이어야 한다 (SPEC-COLLECTOR-MARKETIND-004 TASK-C1)")
        void usdkrwDailyCron_isValid() {
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.USDKRW_DAILY_CRON));
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

        @Test
        @DisplayName("USDKRW_DAILY_ZONE은 유효한 ZoneId이어야 한다 (SPEC-COLLECTOR-MARKETIND-004 TASK-C1)")
        void usdkrwDailyZone_isValid() {
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.USDKRW_DAILY_ZONE));
        }
    }

    @Nested
    @DisplayName("원본 리터럴 등가 단언 (AC-14) — 스케줄러 원본과 일치해야 드리프트를 차단한다")
    @SuppressWarnings("PMD.TooManyMethods") // 15개 배치 단위 × cron/zone 리터럴 등가 검증 — 배치 단위 증가에 비례
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
            assertEquals("0 5 17 * * MON-FRI", BatchCrons.MARKET_INDICATORS_CRON);
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

        @Test
        @DisplayName(
                "USDKRW_DAILY_CRON은 평일 10:30 KST — MARKET_INDICATORS_CRON(17:05)과 독립"
                        + " (SPEC-COLLECTOR-MARKETIND-004 REQ-002)")
        void usdkrwDailyCron_matchesDedicatedLiteral() {
            assertEquals("0 30 10 * * MON-FRI", BatchCrons.USDKRW_DAILY_CRON);
            assertEquals("Asia/Seoul", BatchCrons.USDKRW_DAILY_ZONE);
            assertThat(BatchCrons.USDKRW_DAILY_CRON).isNotNull();
            assertThat(BatchCrons.USDKRW_DAILY_ZONE).isNotNull();
        }

        @Test
        @DisplayName("MARKET_INDICATORS_CRON(17:05)은 USDKRW 분리 이후에도 무변경이어야 한다 (D1 감사 리스크 격리)")
        void marketIndicatorsCron_unchangedAfterUsdkrwSplit() {
            assertEquals("0 5 17 * * MON-FRI", BatchCrons.MARKET_INDICATORS_CRON);
            assertEquals("Asia/Seoul", BatchCrons.MARKET_INDICATORS_ZONE);
        }
    }

    @Nested
    @DisplayName("T-001 신규 상수 값 (REQ-XR-002) — 스케줄러 원본 리터럴과 일치해야 한다")
    class NewConstantsValue {

        @Test
        @DisplayName("overseas-split cron/zone은 OverseasSplitScheduler 원본과 일치해야 한다")
        void overseasSplit_matchesOriginal() {
            assertEquals("0 0 17 * * MON-FRI", BatchCrons.OVERSEAS_SPLIT_CRON);
            assertEquals("America/New_York", BatchCrons.OVERSEAS_SPLIT_ZONE);
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.OVERSEAS_SPLIT_CRON));
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.OVERSEAS_SPLIT_ZONE));
        }

        @Test
        @DisplayName("overseas-rights cron/zone은 OverseasRightsScheduler 원본과 일치해야 한다")
        void overseasRights_matchesOriginal() {
            assertEquals("0 0 17 * * MON-FRI", BatchCrons.OVERSEAS_RIGHTS_CRON);
            assertEquals("America/New_York", BatchCrons.OVERSEAS_RIGHTS_ZONE);
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.OVERSEAS_RIGHTS_CRON));
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.OVERSEAS_RIGHTS_ZONE));
        }

        @Test
        @DisplayName("corp-code cron/zone은 CorpCodeUpdateScheduler 원본과 일치해야 한다")
        void corpCode_matchesOriginal() {
            assertEquals("0 30 7 * * *", BatchCrons.CORP_CODE_CRON);
            assertEquals("Asia/Seoul", BatchCrons.CORP_CODE_ZONE);
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.CORP_CODE_CRON));
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.CORP_CODE_ZONE));
        }

        @Test
        @DisplayName("dart-backfill cron/zone은 DartDisclosureBackfillScheduler 원본과 일치해야 한다")
        void dartBackfill_matchesOriginal() {
            assertEquals("0 30 4 * * *", BatchCrons.DART_BACKFILL_CRON);
            assertEquals("Asia/Seoul", BatchCrons.DART_BACKFILL_ZONE);
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.DART_BACKFILL_CRON));
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.DART_BACKFILL_ZONE));
        }

        @Test
        @DisplayName("domestic-news cron/zone은 NewsScheduler 원본과 일치해야 한다")
        void domesticNews_matchesOriginal() {
            assertEquals("0 0/10 9-15 * * MON-FRI", BatchCrons.DOMESTIC_NEWS_CRON);
            assertEquals("Asia/Seoul", BatchCrons.DOMESTIC_NEWS_ZONE);
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.DOMESTIC_NEWS_CRON));
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.DOMESTIC_NEWS_ZONE));
        }

        @Test
        @DisplayName("overseas-news cron/zone은 OverseasNewsScheduler 원본과 일치해야 한다")
        void overseasNews_matchesOriginal() {
            assertEquals("0 0/10 9-16 * * MON-FRI", BatchCrons.OVERSEAS_NEWS_CRON);
            assertEquals("America/New_York", BatchCrons.OVERSEAS_NEWS_ZONE);
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.OVERSEAS_NEWS_CRON));
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.OVERSEAS_NEWS_ZONE));
        }

        @Test
        @DisplayName("watchlist-sync-krx cron/zone은 WatchlistSyncScheduler.syncMorning 원본과 일치해야 한다")
        void watchlistSyncKrx_matchesOriginal() {
            assertEquals("0 20 8 * * *", BatchCrons.WATCHLIST_SYNC_KRX_CRON);
            assertEquals("Asia/Seoul", BatchCrons.WATCHLIST_SYNC_KRX_ZONE);
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.WATCHLIST_SYNC_KRX_CRON));
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.WATCHLIST_SYNC_KRX_ZONE));
        }

        @Test
        @DisplayName("watchlist-sync-us cron/zone은 WatchlistSyncScheduler.syncUs 원본과 일치해야 한다")
        void watchlistSyncUs_matchesOriginal() {
            assertEquals("0 50 8 * * *", BatchCrons.WATCHLIST_SYNC_US_CRON);
            assertEquals("America/New_York", BatchCrons.WATCHLIST_SYNC_US_ZONE);
            assertThatNoException()
                    .isThrownBy(() -> CronExpression.parse(BatchCrons.WATCHLIST_SYNC_US_CRON));
            assertThatNoException().isThrownBy(() -> ZoneId.of(BatchCrons.WATCHLIST_SYNC_US_ZONE));
        }
    }

    @Nested
    @DisplayName("T-001 스케줄러 배선 (REQ-XR-002) — @Scheduled가 리터럴이 아니라 BatchCrons 상수를 참조해야 한다")
    class SchedulerBindsConstant {

        // 소스 검사: 리플렉션은 컴파일 타임 상수 인라이닝 때문에 리터럴/상수를 구분하지 못하므로,
        // 실제 상수 참조(드리프트 차단)를 강제하려면 스케줄러 소스가 BatchCrons.<CONST>를 담고 있는지 확인한다.
        private static final String BASE = "src/main/java/com/aaa/collector/";

        @Test
        @DisplayName("OverseasSplitScheduler는 OVERSEAS_SPLIT_CRON/ZONE 상수를 참조해야 한다")
        void overseasSplitScheduler_bindsConstant() {
            assertBinds(
                    "stock/rights/OverseasSplitScheduler.java",
                    "OVERSEAS_SPLIT_CRON",
                    "OVERSEAS_SPLIT_ZONE");
        }

        @Test
        @DisplayName("OverseasRightsScheduler는 OVERSEAS_RIGHTS_CRON/ZONE 상수를 참조해야 한다")
        void overseasRightsScheduler_bindsConstant() {
            assertBinds(
                    "stock/rights/OverseasRightsScheduler.java",
                    "OVERSEAS_RIGHTS_CRON",
                    "OVERSEAS_RIGHTS_ZONE");
        }

        @Test
        @DisplayName("CorpCodeUpdateScheduler는 CORP_CODE_CRON/ZONE 상수를 참조해야 한다")
        void corpCodeScheduler_bindsConstant() {
            assertBinds(
                    "dart/corpcode/CorpCodeUpdateScheduler.java",
                    "CORP_CODE_CRON",
                    "CORP_CODE_ZONE");
        }

        @Test
        @DisplayName("DartDisclosureBackfillScheduler는 DART_BACKFILL_CRON/ZONE 상수를 참조해야 한다")
        void dartBackfillScheduler_bindsConstant() {
            assertBinds(
                    "dart/backfill/DartDisclosureBackfillScheduler.java",
                    "DART_BACKFILL_CRON",
                    "DART_BACKFILL_ZONE");
        }

        @Test
        @DisplayName("NewsScheduler는 DOMESTIC_NEWS_CRON/ZONE 상수를 참조해야 한다")
        void newsScheduler_bindsConstant() {
            assertBinds("news/NewsScheduler.java", "DOMESTIC_NEWS_CRON", "DOMESTIC_NEWS_ZONE");
        }

        @Test
        @DisplayName("OverseasNewsScheduler는 OVERSEAS_NEWS_CRON/ZONE 상수를 참조해야 한다")
        void overseasNewsScheduler_bindsConstant() {
            assertBinds(
                    "news/overseas/OverseasNewsScheduler.java",
                    "OVERSEAS_NEWS_CRON",
                    "OVERSEAS_NEWS_ZONE");
        }

        @Test
        @DisplayName("WatchlistSyncScheduler는 KRX/US cron·zone 상수를 모두 참조해야 한다")
        void watchlistSyncScheduler_bindsConstants() {
            String content = read("watchlist/WatchlistSyncScheduler.java");
            assertThat(content).contains("BatchCrons.WATCHLIST_SYNC_KRX_CRON");
            assertThat(content).contains("BatchCrons.WATCHLIST_SYNC_KRX_ZONE");
            assertThat(content).contains("BatchCrons.WATCHLIST_SYNC_US_CRON");
            assertThat(content).contains("BatchCrons.WATCHLIST_SYNC_US_ZONE");
        }

        private void assertBinds(String relativePath, String cronConst, String zoneConst) {
            String content = read(relativePath);
            assertThat(content)
                    .as("%s는 @Scheduled에서 BatchCrons.%s를 참조해야 한다", relativePath, cronConst)
                    .contains("BatchCrons." + cronConst);
            assertThat(content)
                    .as("%s는 @Scheduled에서 BatchCrons.%s를 참조해야 한다", relativePath, zoneConst)
                    .contains("BatchCrons." + zoneConst);
        }

        private String read(String relativePath) {
            try {
                return Files.readString(Path.of(BASE + relativePath));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
