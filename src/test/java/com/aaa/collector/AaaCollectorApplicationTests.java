package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.macro.MacroIndicatorRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles({"test", "smoke"})
// 컨텍스트 로드를 위해 DB/Redis 의존 빈을 일괄 모킹한다. 클래스 레벨 types 배열로 중복 필드 선언을 회피한다.
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
            ShortSaleOverseasScheduler.class
        })
class AaaCollectorApplicationTests {

    @Autowired private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}
