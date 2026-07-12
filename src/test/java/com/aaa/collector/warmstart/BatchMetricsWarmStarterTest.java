package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.dart.corpcode.CorpCodeMappingRepository;
import com.aaa.collector.dart.disclosure.DisclosureRepository;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.news.DomesticNewsHeadlineRepository;
import com.aaa.collector.news.overseas.OverseasNewsHeadlineRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.CreditBalanceRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.FinancialRepository;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.etf.EtfRepresentativeHistoryRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName("BatchMetricsWarmStarter — 부팅 시 last-load gauge 초기화 (SPEC-OBSV-WARMSTART-001)")
class BatchMetricsWarmStarterTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 개별 테스트에서 per-test 동작(값 반환/예외/검증)을 제어하는 foreground 협력자만 @Mock으로 둔다(strict stubbing 유지).
    @Mock private BatchMetrics batchMetrics;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private ShortSaleOverseasRepository shortSaleOverseasRepository;
    @Mock private DisclosureRepository disclosureRepository;
    @Mock private CorporateEventRepository corporateEventRepository;
    @Mock private CorpCodeMappingRepository corpCodeMappingRepository;
    @Mock private DomesticNewsHeadlineRepository domesticNewsHeadlineRepository;
    @Mock private OverseasNewsHeadlineRepository overseasNewsHeadlineRepository;
    @Mock private ExtendedHoursWarmSource extendedHoursWarmSource;

    // 이 8종은 개별 테스트에서 참조하지 않고 항상 empty(Mockito 기본값)만 반환한다. final 초기화 mock으로 두어
    // PMD TooManyFields(비-final 필드만 계수)의 임계 아래로 유지한다 — @SuppressWarnings 미사용 root-cause 회피.
    private final InvestorTrendRepository investorTrendRepository =
            mock(InvestorTrendRepository.class);
    private final CreditBalanceRepository creditBalanceRepository =
            mock(CreditBalanceRepository.class);
    private final ShortSaleDomesticRepository shortSaleDomesticRepository =
            mock(ShortSaleDomesticRepository.class);
    private final AnalystEstimateRepository analystEstimateRepository =
            mock(AnalystEstimateRepository.class);
    private final FinancialRepository financialRepository = mock(FinancialRepository.class);
    private final MacroIndicatorRepository macroIndicatorRepository =
            mock(MacroIndicatorRepository.class);
    private final MarketIndicatorRepository marketIndicatorRepository =
            mock(MarketIndicatorRepository.class);
    private final EtfRepresentativeHistoryRepository etfRepresentativeHistoryRepository =
            mock(EtfRepresentativeHistoryRepository.class);

    /**
     * 매 호출마다 새 인스턴스를 생성한다 — {@code @InjectMocks} 필드를 두지 않아 mock 필드 수를 생성자 인자 수만큼만 유지한다. 필드 순서는
     * {@link BatchMetricsWarmStarter} 생성자(@RequiredArgsConstructor) 필드 선언 순서와 일치해야 한다.
     */
    private BatchMetricsWarmStarter warmStarter() {
        return new BatchMetricsWarmStarter(
                batchMetrics,
                dailyOhlcvRepository,
                investorTrendRepository,
                creditBalanceRepository,
                shortSaleDomesticRepository,
                shortSaleOverseasRepository,
                analystEstimateRepository,
                financialRepository,
                macroIndicatorRepository,
                marketIndicatorRepository,
                etfRepresentativeHistoryRepository,
                disclosureRepository,
                corporateEventRepository,
                corpCodeMappingRepository,
                domesticNewsHeadlineRepository,
                overseasNewsHeadlineRepository,
                extendedHoursWarmSource);
    }

    @Nested
    @DisplayName("정상 set — 조회 결과 있으면 warmLastLoad 호출됨")
    class NormalSet {

        @Test
        @DisplayName("domestic-daily 조회 결과가 있으면 warmLastLoad('domestic-daily', instant) 호출")
        void warmsDomesticDailyWhenResultPresent() throws Exception {
            // Arrange
            LocalDateTime kstTime = LocalDateTime.of(2026, 6, 24, 16, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();

            stubAllEmpty();
            when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(any()))
                    .thenReturn(
                            Optional.of(kstTime), // domestic-daily (KOSPI/KOSDAQ/KRX)
                            Optional.empty() // overseas-daily (NYSE/NASDAQ/AMEX/US)
                            );

            // Act
            warmStarter().run(null);

            // Assert
            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(batchMetrics).warmLastLoad(eq("domestic-daily"), captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("빈 테이블 skip — Optional.empty() 반환 시 warmLastLoad 미호출")
    class EmptyTableSkip {

        @Test
        @DisplayName("모든 리포지토리가 empty를 반환하면 warmLastLoad가 한 번도 호출되지 않는다")
        void skipsAllWhenAllRepositoriesEmpty() throws Exception {
            // Arrange
            stubAllEmpty();

            // Act
            warmStarter().run(null);

            // Assert
            verify(batchMetrics, never()).warmLastLoad(any(), any());
        }
    }

    @Nested
    @DisplayName("실패 격리 — 한 배치 조회 실패 시 나머지 계속 처리")
    class FailureIsolation {

        @Test
        @DisplayName("domestic-daily 조회에서 DataAccessException 발생해도 run()이 예외 없이 완료된다")
        void continuesWhenOneBatchThrows() {
            // Arrange
            LocalDateTime kstTime = LocalDateTime.of(2026, 6, 24, 10, 0, 0);
            when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(any()))
                    .thenThrow(new QueryTimeoutException("DB 연결 실패"))
                    .thenReturn(Optional.of(kstTime)); // overseas-daily는 정상
            stubNonDailyEmpty();

            // Act & Assert
            assertThatNoException().isThrownBy(() -> warmStarter().run(null));
        }

        @Test
        @DisplayName("한 배치 조회가 실패해도 나머지 배치는 warmLastLoad 호출이 계속된다")
        void warmsContinuingBatchesAfterOneFailure() throws Exception {
            // Arrange
            LocalDateTime kstTime = LocalDateTime.of(2026, 6, 24, 10, 0, 0);
            when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(any()))
                    .thenThrow(new QueryTimeoutException("DB 오류")) // domestic-daily 실패
                    .thenReturn(Optional.of(kstTime)); // overseas-daily 성공
            stubNonDailyEmpty();

            // Act
            warmStarter().run(null);

            // Assert: overseas-daily는 정상 호출
            verify(batchMetrics).warmLastLoad(eq("overseas-daily"), any(Instant.class));
        }
    }

    @Nested
    @DisplayName("제외 2종 미포함 — watchlist-sync-krx/us는 warm-start 후에도 미호출 (§13 O-3)")
    class ExcludedBatches {

        @Test
        @DisplayName("watchlist-sync-krx로 warmLastLoad가 호출된 적 없다")
        void doesNotWarmWatchlistSyncKrx() throws Exception {
            stubAllEmpty();
            warmStarter().run(null);
            verify(batchMetrics, never()).warmLastLoad(eq("watchlist-sync-krx"), any());
        }

        @Test
        @DisplayName("watchlist-sync-us로 warmLastLoad가 호출된 적 없다")
        void doesNotWarmWatchlistSyncUs() throws Exception {
            stubAllEmpty();
            warmStarter().run(null);
            verify(batchMetrics, never()).warmLastLoad(eq("watchlist-sync-us"), any());
        }
    }

    @Nested
    @DisplayName("신규 4라벨 warm-start (SPEC-OBSV-WATERMARK-001 REQ-WM-014)")
    class NewLabelsWarmStart {

        @Test
        @DisplayName("dart-disclosure 조회 결과가 있으면 warmLastLoad('dart-disclosure', instant) 호출")
        void warmsDartDisclosure() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 7, 3, 22, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(disclosureRepository.findMaxCreatedAt()).thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmLastLoad(eq("dart-disclosure"), eq(expected));
        }

        @Test
        @DisplayName(
                "dart-backfill은 disclosures 최신 적재 시각으로 seed된다"
                        + " (SPEC-COLLECTOR-EXPECTED-RUN-001, REQ-WM-013 배선 누락 교정)")
        void warmsDartBackfill() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 7, 3, 22, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(disclosureRepository.findMaxCreatedAt()).thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmLastLoad(eq("dart-backfill"), eq(expected));
        }

        @Test
        @DisplayName("overseas-rights 조회 결과가 있으면 warmLastLoad('overseas-rights', instant) 호출")
        void warmsOverseasRights() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 7, 3, 22, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(corporateEventRepository.findMaxCreatedAtByMarketsIn(any()))
                    .thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmLastLoad(eq("overseas-rights"), eq(expected));
        }

        @Test
        @DisplayName("corp-code 조회 결과가 있으면 warmLastLoad('corp-code', instant) 호출")
        void warmsCorpCode() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 7, 3, 22, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(corpCodeMappingRepository.findMaxCreatedAt()).thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmLastLoad(eq("corp-code"), eq(expected));
        }
    }

    @Nested
    @DisplayName(
            "REQ-XR-018 warm-start 범위 확장 — news 2종 + overseas-split + extended-hours pre/after")
    class ExpectedRunWarmStart {

        @Test
        @DisplayName("(a) domestic-news 최신 게시 시각이 있으면 warmLastLoad('domestic-news', instant) 호출")
        void warmsDomesticNews() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 7, 10, 14, 30, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(domesticNewsHeadlineRepository.findMaxPublishedAt())
                    .thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmLastLoad(eq("domestic-news"), eq(expected));
        }

        @Test
        @DisplayName("(a) overseas-news 최신 게시 시각이 있으면 warmLastLoad('overseas-news', instant) 호출")
        void warmsOverseasNews() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 7, 10, 5, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(overseasNewsHeadlineRepository.findMaxPublishedAt())
                    .thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmLastLoad(eq("overseas-news"), eq(expected));
        }

        @Test
        @DisplayName("(b) overseas-split은 corporate_events 해외 시장 최신 적재 시각으로 seed된다")
        void warmsOverseasSplit() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 7, 10, 6, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(corporateEventRepository.findMaxCreatedAtByMarketsIn(any()))
                    .thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmLastLoad(eq("overseas-split"), eq(expected));
        }

        @Test
        @DisplayName("(b) extended-hours-pre는 PRE 세션 seed로 독립 warm된다")
        void warmsExtendedHoursPre() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 7, 3, 0, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(extendedHoursWarmSource.preLastLoad()).thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmLastLoad(eq("extended-hours-pre"), eq(expected));
        }

        @Test
        @DisplayName("(b) extended-hours-after는 AFTER 세션 seed로 독립 warm된다")
        void warmsExtendedHoursAfter() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 7, 3, 0, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(extendedHoursWarmSource.afterLastLoad()).thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmLastLoad(eq("extended-hours-after"), eq(expected));
        }

        @Test
        @DisplayName("pre/after 세션 seed가 다르면 각각 독립적으로 warm된다 (죽은 PRE가 AFTER에 가려지지 않음)")
        void warmsPreAndAfterIndependently() throws Exception {
            LocalDateTime preTime = LocalDateTime.of(2026, 7, 1, 0, 0, 0);
            LocalDateTime afterTime = LocalDateTime.of(2026, 7, 3, 0, 0, 0);
            stubAllEmpty();
            when(extendedHoursWarmSource.preLastLoad()).thenReturn(Optional.of(preTime));
            when(extendedHoursWarmSource.afterLastLoad()).thenReturn(Optional.of(afterTime));

            warmStarter().run(null);

            verify(batchMetrics)
                    .warmLastLoad(eq("extended-hours-pre"), eq(preTime.atZone(KST).toInstant()));
            verify(batchMetrics)
                    .warmLastLoad(
                            eq("extended-hours-after"), eq(afterTime.atZone(KST).toInstant()));
        }
    }

    @Nested
    @DisplayName("interest last_data seed (REQ-XR-017, DP-5)")
    class InterestDataSeed {

        @Test
        @DisplayName("findMaxInterestCollectedAt 결과가 있으면 warmDataArrival(interest, instant) 호출")
        void seedsInterestLastData() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 4, 16, 6, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(shortSaleOverseasRepository.findMaxInterestCollectedAt())
                    .thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmDataArrival(eq("overseas-shortsale-interest"), eq(expected));
        }

        @Test
        @DisplayName("interest last_data seed는 기존 last_load warm-start도 그대로 유지한다")
        void keepsInterestLastLoadWarm() throws Exception {
            LocalDateTime kstTime = LocalDateTime.of(2026, 4, 16, 6, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            stubAllEmpty();
            when(shortSaleOverseasRepository.findMaxInterestCollectedAt())
                    .thenReturn(Optional.of(kstTime));

            warmStarter().run(null);

            verify(batchMetrics).warmLastLoad(eq("overseas-shortsale-interest"), eq(expected));
        }

        @Test
        @DisplayName("interest 조회 결과가 없으면 warmDataArrival를 호출하지 않는다")
        void skipsSeedWhenEmpty() throws Exception {
            stubAllEmpty();

            warmStarter().run(null);

            verify(batchMetrics, never()).warmDataArrival(eq("overseas-shortsale-interest"), any());
        }
    }

    /** 모든 리포지토리를 Optional.empty() 반환으로 stub. */
    private void stubAllEmpty() {
        when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(any())).thenReturn(Optional.empty());
        stubNonDailyEmpty();
    }

    /**
     * DailyOhlcvRepository 제외 foreground 협력자를 Optional.empty()로 stub. 8종 background mock은 Mockito
     * 기본값 (Optional.empty())을 그대로 쓰므로 명시 stub하지 않는다.
     */
    private void stubNonDailyEmpty() {
        when(shortSaleOverseasRepository.findMaxDailyCollectedAt()).thenReturn(Optional.empty());
        when(shortSaleOverseasRepository.findMaxInterestCollectedAt()).thenReturn(Optional.empty());
        when(disclosureRepository.findMaxCreatedAt()).thenReturn(Optional.empty());
        when(corporateEventRepository.findMaxCreatedAtByMarketsIn(any()))
                .thenReturn(Optional.empty());
        when(corpCodeMappingRepository.findMaxCreatedAt()).thenReturn(Optional.empty());
        when(domesticNewsHeadlineRepository.findMaxPublishedAt()).thenReturn(Optional.empty());
        when(overseasNewsHeadlineRepository.findMaxPublishedAt()).thenReturn(Optional.empty());
        when(extendedHoursWarmSource.preLastLoad()).thenReturn(Optional.empty());
        when(extendedHoursWarmSource.afterLastLoad()).thenReturn(Optional.empty());
    }
}
