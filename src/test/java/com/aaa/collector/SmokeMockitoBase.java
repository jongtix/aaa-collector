package com.aaa.collector;

import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.backfill.MarketIndicatorBackfillOrchestrator;
import com.aaa.collector.market.backfill.MarketIndicatorBackfillScheduler;
import com.aaa.collector.market.indicator.YahooFinanceClient;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximExchangeRateClient;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.indicator.vix.CboeVixClient;
import com.aaa.collector.market.indicator.vix.FredVixClient;
import com.aaa.collector.market.indicator.vix.VixCollectionService;
import com.aaa.collector.news.NewsHeadlineRepository;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.CreditBalanceRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.FinancialRepository;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.backfill.BackfillOrchestrator;
import com.aaa.collector.stock.backfill.BackfillScheduler;
import com.aaa.collector.stock.backfill.BackfillWindowExecutor;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvScheduler;
import com.aaa.collector.stock.etf.EtfMetadataRepository;
import com.aaa.collector.stock.etf.EtfRepresentativeHistoryRepository;
import com.aaa.collector.stock.grade.StockGradeRepository;
import com.aaa.collector.stock.grade.snapshot.RankingSnapshotRepository;
import com.aaa.collector.stock.shortsale.overseas.FinraShortSaleClient;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasDailyCollectionService;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasInterestCollectionService;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasScheduler;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 풀컨텍스트 smoke 테스트 공통 모킹 베이스.
 *
 * <p>DB/Redis/외부 HTTP 의존 빈을 일괄 모킹한다. 각 서브클래스는 고유한 {@code @SpringBootTest}
 * 설정(webEnvironment, @TestPropertySource 등)을 독립적으로 보유한다.
 */
// 컨텍스트 로드를 위해 DB/Redis/외부 HTTP 의존 빈을 일괄 모킹한다. 클래스 레벨 types 배열로 중복 필드 선언을 회피한다.
@SuppressWarnings("PMD.ExcessiveImports") // 신규 빈 추가로 import 수 증가 — 불가피
@MockitoBean(
        types = {
            StringRedisTemplate.class,
            // smoke 프로파일은 DataSource AutoConfiguration을 exclude하므로 JdbcTemplate 빈이 없다.
            // WarningCountingOhlcvInserter가 JdbcTemplate를 주입받으므로 모킹해 컨텍스트 로드를 보장한다.
            JdbcTemplate.class,
            StockRepository.class,
            EtfMetadataRepository.class,
            EtfRepresentativeHistoryRepository.class,
            DailyOhlcvRepository.class,
            StockGradeRepository.class,
            RankingSnapshotRepository.class,
            InvestorTrendRepository.class,
            ShortSaleDomesticRepository.class,
            CreditBalanceRepository.class,
            MacroIndicatorRepository.class,
            CorporateEventRepository.class,
            NewsHeadlineRepository.class,
            FinancialRepository.class,
            AnalystEstimateRepository.class,
            // SPEC-COLLECTOR-OVERSEAS-OHLCV-001 REQ-OVOH-043: 신규 미국 일봉 서비스/스케줄러 빈 모킹 (BATCH-003 회귀
            // 방지)
            OverseasDailyOhlcvCollectionService.class,
            OverseasDailyOhlcvScheduler.class,
            // REQ-SSO-041 — BATCH-003·GRADE-003 회귀 방지: 미국 공매도 신규 빈 모킹
            ShortSaleOverseasRepository.class,
            FinraShortSaleClient.class,
            ShortSaleOverseasDailyCollectionService.class,
            ShortSaleOverseasInterestCollectionService.class,
            ShortSaleOverseasScheduler.class,
            // SPEC-COLLECTOR-BACKFILL-001: 백필 도메인 빈 모킹 (BATCH-003/GRADE-003 회귀 방지)
            BackfillStatusRepository.class,
            BackfillOrchestrator.class,
            BackfillWindowExecutor.class,
            BackfillScheduler.class,
            // SPEC-COLLECTOR-MARKETIND-001: 시장 지표 신규 빈 모킹 (smoke 회귀 방지)
            MarketIndicatorRepository.class,
            VixCollectionService.class,
            UsdkrwCollectionService.class,
            MarketIndicatorBackfillOrchestrator.class,
            MarketIndicatorBackfillScheduler.class,
            CboeVixClient.class,
            FredVixClient.class,
            YahooFinanceClient.class,
            KoreaeximExchangeRateClient.class
        })
class SmokeMockitoBase {

    protected SmokeMockitoBase() {}
}
