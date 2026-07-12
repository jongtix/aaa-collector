package com.aaa.collector;

import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.dart.backfill.DartDisclosureBackfillOrchestrator;
import com.aaa.collector.dart.backfill.DartDisclosureBackfillScheduler;
import com.aaa.collector.dart.backfill.DartDisclosureBackfillWindowService;
import com.aaa.collector.dart.corpcode.CorpCodeMappingRepository;
import com.aaa.collector.dart.corpcode.CorpCodeUpdateScheduler;
import com.aaa.collector.dart.corpcode.CorpCodeUpdateService;
import com.aaa.collector.dart.disclosure.DartDisclosurePollingScheduler;
import com.aaa.collector.dart.disclosure.DartDisclosurePollingService;
import com.aaa.collector.dart.disclosure.DisclosureRepository;
import com.aaa.collector.dart.external.DartCorpCodeClient;
import com.aaa.collector.dart.external.DartDisclosureClient;
import com.aaa.collector.macro.MacroExternalScheduler;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.macro.backfill.MacroIndicatorBackfillOrchestrator;
import com.aaa.collector.macro.backfill.MacroIndicatorBackfillScheduler;
import com.aaa.collector.macro.ecos.EcosCollectionService;
import com.aaa.collector.macro.fred.FredCollectionService;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.backfill.MarketIndicatorBackfillOrchestrator;
import com.aaa.collector.market.backfill.MarketIndicatorBackfillScheduler;
import com.aaa.collector.market.indicator.MarketIndicatorMetrics;
import com.aaa.collector.market.indicator.YahooFinanceClient;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximExchangeRateClient;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.indicator.vix.CboeVixClient;
import com.aaa.collector.market.indicator.vix.FredVixClient;
import com.aaa.collector.market.indicator.vix.VixCollectionService;
import com.aaa.collector.market.session.UsMarketSessionGate;
import com.aaa.collector.news.DomesticNewsHeadlineRepository;
import com.aaa.collector.news.overseas.OverseasNewsHeadlineRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
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
import com.aaa.collector.stock.exthours.ExtendedHoursCollectionService;
import com.aaa.collector.stock.exthours.ExtendedHoursRepository;
import com.aaa.collector.stock.exthours.ExtendedHoursScheduler;
import com.aaa.collector.stock.exthours.YahooExtendedHoursClient;
import com.aaa.collector.stock.grade.StockGradeRepository;
import com.aaa.collector.stock.shortsale.overseas.FinraShortSaleClient;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasDailyCollectionService;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasInterestCollectionService;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasScheduler;
import com.aaa.collector.stock.shortsale.overseas.backfill.FinraCdnDailyFileClient;
import com.aaa.collector.stock.shortsale.overseas.backfill.FinraCdnFileParser;
import com.aaa.collector.stock.shortsale.overseas.backfill.FinraCdnShortSaleBackfillOrchestrator;
import com.aaa.collector.stock.shortsale.overseas.backfill.FinraCdnShortSaleBackfillScheduler;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

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
            JdbcTemplate.class,
            StockRepository.class,
            EtfMetadataRepository.class,
            EtfRepresentativeHistoryRepository.class,
            DailyOhlcvRepository.class,
            StockGradeRepository.class,
            InvestorTrendRepository.class,
            ShortSaleDomesticRepository.class,
            CreditBalanceRepository.class,
            MacroIndicatorRepository.class,
            CorporateEventRepository.class,
            DomesticNewsHeadlineRepository.class,
            // SPEC-COLLECTOR-OVERSEAS-ETC-001 T9: 해외 뉴스 리포지토리 신규 빈 모킹 (smoke 회귀 방지)
            OverseasNewsHeadlineRepository.class,
            FinancialRepository.class,
            AnalystEstimateRepository.class,
            OverseasDailyOhlcvCollectionService.class,
            OverseasDailyOhlcvScheduler.class,
            ShortSaleOverseasRepository.class,
            FinraShortSaleClient.class,
            ShortSaleOverseasDailyCollectionService.class,
            ShortSaleOverseasInterestCollectionService.class,
            ShortSaleOverseasScheduler.class,
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
            KoreaeximExchangeRateClient.class,
            // SPEC-COLLECTOR-MACRO-EXT-001: 외부 거시경제 지표 신규 빈 모킹 (smoke 회귀 방지)
            EcosCollectionService.class,
            FredCollectionService.class,
            MacroExternalScheduler.class,
            MacroIndicatorBackfillOrchestrator.class,
            MacroIndicatorBackfillScheduler.class,
            // SPEC-COLLECTOR-EXTHOURS-001: 시간외 수집 신규 빈 모킹 (smoke 회귀 방지)
            ExtendedHoursRepository.class,
            YahooExtendedHoursClient.class,
            ExtendedHoursCollectionService.class,
            ExtendedHoursScheduler.class,
            // SPEC-COLLECTOR-DART-001: DART 공시 수집 신규 빈 모킹 (smoke 회귀 방지)
            DisclosureRepository.class,
            DartDisclosureClient.class,
            DartDisclosurePollingService.class,
            DartDisclosurePollingScheduler.class,
            // SPEC-COLLECTOR-DART-001 M2: corp_code 매핑 + 백필 신규 빈 모킹
            CorpCodeMappingRepository.class,
            DartCorpCodeClient.class,
            CorpCodeUpdateService.class,
            CorpCodeUpdateScheduler.class,
            DartDisclosureBackfillWindowService.class,
            DartDisclosureBackfillOrchestrator.class,
            DartDisclosureBackfillScheduler.class,
            // SPEC-COLLECTOR-USMKT-001: 미국 시장 게이트 신규 빈 모킹 (smoke 회귀 방지)
            UsMarketSessionGate.class,
            // SPEC-COLLECTOR-MARKETIND-002: 시장지표 메트릭 신규 빈 모킹 (smoke 회귀 방지)
            MarketIndicatorMetrics.class,
            // SPEC-COLLECTOR-BACKFILL-008: 미국 공매도 Daily 과거 백필(FINRA CDN) 신규 빈 모킹 (smoke 회귀 방지)
            FinraCdnDailyFileClient.class,
            FinraCdnFileParser.class,
            FinraCdnShortSaleBackfillOrchestrator.class,
            FinraCdnShortSaleBackfillScheduler.class,
            // SPEC-COLLECTOR-BACKFILL-010: CoverageRefresher가 listed_date 하향 보정+표적 리셋(REQ-159/-160)
            // 트랜잭션 경계용 TransactionTemplate을 신규 의존. smoke 프로파일은 DataSourceAutoConfiguration을
            // exclude해 PlatformTransactionManager가 없어 TransactionTemplate 자동구성도 없음(smoke 회귀 방지)
            TransactionTemplate.class,
            // SPEC-COLLECTOR-WARMSTART-REDIS-001: 배치 last-load Redis 영속화 신규 빈 모킹.
            // BatchMetricsWarmStarter가
            // ApplicationRunner라 컨텍스트 기동 시 자동 실행되며 find()를 호출하므로, mock StringRedisTemplate의
            // opsForValue() null 반환에 의한 NPE를 차단하려면 리포지토리 자체를 모킹해야 한다(smoke 회귀 방지)
            BatchLastLoadRepository.class
        })
class SmokeMockitoBase {

    protected SmokeMockitoBase() {}
}
