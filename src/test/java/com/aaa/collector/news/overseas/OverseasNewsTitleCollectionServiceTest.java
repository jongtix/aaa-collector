package com.aaa.collector.news.overseas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OverseasNewsTitleCollectionService 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-ETC-001).
 *
 * <p>T0 실측 설계 검증: NATION_CD=US·CTS 공백 고정, DATA_DT/DATA_TM 커서 페이징, news_key dedup(경계 inclusive 1건
 * 중복), 빈 outblock1(tr_cont=E 동반 신호)/전진 0/MAX_PAGES 정지, 빈 symb 정상 저장, 독성 행 흡수, 전 키 사망 단락. {@code
 * OverseasRightsCollectionServiceTest} 패턴 답습 — 실제 {@link KeyLeaseRegistry} + mock {@link
 * HealthyKeySelector}로 openSession()을 구동한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasNewsTitleCollectionService 단위 테스트")
class OverseasNewsTitleCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;
    @Mock private OverseasNewsHeadlineRepository repository;
    @Mock private OverseasNewsHeadlineInserter overseasNewsHeadlineInserter;

    @Captor private ArgumentCaptor<List<OverseasNewsHeadline>> inserterCaptor;

    private OverseasNewsTitleCollectionService service;

    @BeforeEach
    void setUp() {
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        service =
                new OverseasNewsTitleCollectionService(
                        guardedKisExecutor,
                        keyLeaseRegistry,
                        repository,
                        overseasNewsHeadlineInserter);
    }

    private KisOverseasNewsTitleResponse.NewsRow row(
            String newsKey, String dataDt, String dataTm, String symb) {
        return new KisOverseasNewsTitleResponse.NewsRow(
                "e",
                newsKey,
                dataDt,
                dataTm,
                "01",
                "Market",
                "src",
                "US",
                "NAS",
                symb,
                symb.isEmpty() ? "" : symb + " Inc",
                "제목 " + newsKey);
    }

    private KisOverseasNewsTitleResponse page(List<KisOverseasNewsTitleResponse.NewsRow> rows) {
        return new KisOverseasNewsTitleResponse("0", "MCA00000", "정상", rows);
    }

    private KisOverseasNewsTitleResponse emptyPage() {
        return page(List.of());
    }

    @Nested
    @DisplayName("collect — 게이트 진입 / 요청 파라미터 (REQ-OVE-041)")
    class RequestParams {

        @Test
        @DisplayName("전 키 사망(빈 스냅샷) — 게이트 미호출, 0건")
        void collect_allKeysDead_noGateCall() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

            OverseasNewsCollectionResult result = service.collect();

            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("NATION_CD=US + CTS 공백 고정, 1차 DATA_DT/DATA_TM 공백으로 게이트 호출")
        void collect_buildsUsRequest_blankCursorAndCts() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class), uriCaptor.capture(), anyString(), any()))
                    .thenReturn(emptyPage());

            service.collect();

            URI uri = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance());
            assertThat(uri.toString())
                    .contains("/uapi/overseas-price/v1/quotations/news-title")
                    .contains("NATION_CD=US")
                    .contains("DATA_DT=")
                    .contains("DATA_TM=")
                    .contains("CTS=");
        }

        @Test
        @DisplayName("TR ID = HHPSTH60100C1")
        void collect_usesCorrectTrId() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            ArgumentCaptor<String> trIdCaptor = ArgumentCaptor.forClass(String.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class), any(), trIdCaptor.capture(), any()))
                    .thenReturn(emptyPage());

            service.collect();

            assertThat(trIdCaptor.getValue()).isEqualTo("HHPSTH60100C1");
        }
    }

    @Nested
    @DisplayName("collect — 페이징 커서 (REQ-OVE-043)")
    class CursorPaging {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("2차 요청 — 1차 마지막 행 data_dt/data_tm을 DATA_DT/DATA_TM 커서로 주입")
        void collect_injectsLastRowCursorIntoNextRequest() throws Exception {
            // Arrange — page1 2건(마지막=112235), page2 빈 배열로 정지
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            KisOverseasNewsTitleResponse page1 =
                    page(
                            List.of(
                                    row("K1", "20260624", "122617", "AAPL"),
                                    row("K2", "20260624", "112235", "")));
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class), uriCaptor.capture(), anyString(), any()))
                    .thenReturn(page1)
                    .thenReturn(emptyPage());

            // Act
            service.collect();

            // Assert — 2차 요청 URI에 마지막 행 커서 주입
            List<Function<UriBuilder, URI>> calls = uriCaptor.getAllValues();
            assertThat(calls).hasSize(2);
            URI secondUri = calls.get(1).apply(UriComponentsBuilder.newInstance());
            assertThat(secondUri.toString())
                    .contains("DATA_DT=20260624")
                    .contains("DATA_TM=112235");
        }
    }

    @Nested
    @DisplayName("collect — news_key dedup (REQ-OVE-043a)")
    class Dedup {

        @Test
        @DisplayName("경계 inclusive 1건 중복 — 중복 news_key는 재삽입하지 않음(dedup Set)")
        void collect_boundaryDuplicate_dedupedNotReinserted() throws Exception {
            // Arrange — page1 마지막 행(K2)이 page2 첫 행으로 1건 중복(inclusive 경계)
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            KisOverseasNewsTitleResponse page1 =
                    page(
                            List.of(
                                    row("K1", "20260624", "122617", "AAPL"),
                                    row("K2", "20260624", "112235", "MSFT")));
            KisOverseasNewsTitleResponse page2 =
                    page(
                            List.of(
                                    row("K2", "20260624", "112235", "MSFT"),
                                    row("K3", "20260624", "102141", "NVDA")));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(page1)
                    .thenReturn(page2)
                    .thenReturn(emptyPage());

            // Act
            OverseasNewsCollectionResult result = service.collect();

            // Assert — K1/K2/K3 각 1회 저장(K2 중복은 dedup), succeeded=3
            // page1(K1+K2) → 1회, page2(K3만) → 1회 = 총 2회 insertBatchIsolated
            verify(overseasNewsHeadlineInserter, times(2)).insertBatchIsolated(any(), any());
            assertThat(result.succeeded()).isEqualTo(3);
            // attempted = page1(2) + page2(2) = 4, K2 중복 1건 skip
            assertThat(result.attempted()).isEqualTo(4);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — 정지조건 (REQ-OVE-044a)")
    class StopConditions {

        @Test
        @DisplayName("빈 outblock1(tr_cont=E 동반 신호) — 즉시 정지")
        void collect_emptyOutblock_stops() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(emptyPage());

            OverseasNewsCollectionResult result = service.collect();

            verify(overseasNewsHeadlineInserter, never()).insertBatchIsolated(any(), any());
            assertThat(result.attempted()).isZero();
            verify(guardedKisExecutor, times(1))
                    .execute(any(LeaseSession.class), any(), anyString(), any());
        }

        @Test
        @DisplayName("신규 news_key 0건(전부 중복) — 전진 0으로 정지")
        void collect_allDuplicates_noForwardProgress_stops() throws Exception {
            // Arrange — page2가 page1과 완전히 동일(전부 dedup) → 신규 키 0건 → 정지
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            KisOverseasNewsTitleResponse samePage =
                    page(
                            List.of(
                                    row("K1", "20260624", "122617", "AAPL"),
                                    row("K2", "20260624", "112235", "MSFT")));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(samePage)
                    .thenReturn(samePage);

            // Act
            OverseasNewsCollectionResult result = service.collect();

            // Assert — page1에서 K1/K2 저장, page2 전부 중복 → 전진 0 정지. 게이트 정확히 2회 호출
            assertThat(result.succeeded()).isEqualTo(2);
            verify(guardedKisExecutor, times(2))
                    .execute(any(LeaseSession.class), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("collect — 행 검증 / 빈 symb / 독성 행")
    class RowHandling {

        @Test
        @DisplayName("news_key null 행 — skip (REQ-OVE-045)")
        void collect_nullNewsKey_skipped() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            KisOverseasNewsTitleResponse page1 =
                    page(
                            List.of(
                                    row(null, "20260624", "122617", "AAPL"),
                                    row("K2", "20260624", "112235", "MSFT")));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(page1)
                    .thenReturn(emptyPage());

            OverseasNewsCollectionResult result = service.collect();

            // null news_key 1건 skip, K2만 저장
            verify(overseasNewsHeadlineInserter, times(1)).insertBatchIsolated(any(), any());
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("빈 symb 행(거시·원자재) — 정상 저장 (skip 안 함, REQ-OVE-047)")
        void collect_blankSymbRow_persistedNormally() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            KisOverseasNewsTitleResponse page1 =
                    page(List.of(row("KMACRO", "20260624", "112235", "")));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(page1)
                    .thenReturn(emptyPage());

            OverseasNewsCollectionResult result = service.collect();

            verify(overseasNewsHeadlineInserter)
                    .insertBatchIsolated(inserterCaptor.capture(), any());
            OverseasNewsHeadline saved =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getSymbol()).isEmpty();
            assertThat(saved.getNewsKey()).isEqualTo("KMACRO");
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("독성 행 DataAccessException — 흡수하고 해당 행만 skip, 커서 진행 유지 (W1)")
        void collect_toxicRow_absorbed() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            KisOverseasNewsTitleResponse page1 =
                    page(List.of(row("KTOXIC", "20260624", "112235", "AAPL")));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(page1)
                    .thenReturn(emptyPage());
            // REQ-INSERT-011: 독성 행에 대해 콜백 호출 시뮬레이션
            final java.sql.SQLException toxicEx = new java.sql.SQLException("toxic", "22001", 1406);
            doAnswer(
                            invocation -> {
                                List<OverseasNewsHeadline> rows = invocation.getArgument(0);
                                @SuppressWarnings("unchecked")
                                com.aaa.collector.observability.RowFailureHandler<
                                                OverseasNewsHeadline>
                                        handler = invocation.getArgument(1);
                                for (OverseasNewsHeadline entity : rows) {
                                    handler.onFailure(entity, toxicEx);
                                }
                                return null;
                            })
                    .when(overseasNewsHeadlineInserter)
                    .insertBatchIsolated(any(), any());

            // Act & Assert — 예외 전파 없이 흡수
            OverseasNewsCollectionResult result = service.collect();

            assertThat(result.succeeded()).isZero();
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — 멀티 페이지 정상 누적")
    class MultiPage {

        @Test
        @DisplayName("3페이지 — 커서 전진하며 신규 키 누적, 빈 페이지에서 정지")
        void collect_threePages_accumulatesUntilEmpty() throws Exception {
            when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
            KisOverseasNewsTitleResponse p1 =
                    page(List.of(row("K1", "20260624", "120000", "AAPL")));
            KisOverseasNewsTitleResponse p2 =
                    page(List.of(row("K2", "20260624", "110000", "MSFT")));
            KisOverseasNewsTitleResponse p3 =
                    page(List.of(row("K3", "20260624", "100000", "NVDA")));
            when(guardedKisExecutor.execute(any(LeaseSession.class), any(), anyString(), any()))
                    .thenReturn(p1)
                    .thenReturn(p2)
                    .thenReturn(p3)
                    .thenReturn(emptyPage());

            OverseasNewsCollectionResult result = service.collect();

            assertThat(result.succeeded()).isEqualTo(3);
            assertThat(result.attempted()).isEqualTo(3);
            // 3페이지 각 1행 → insertBatchIsolated 3회 (페이지당 1회)
            verify(overseasNewsHeadlineInserter, times(3)).insertBatchIsolated(any(), any());
            verify(guardedKisExecutor, times(4))
                    .execute(any(LeaseSession.class), any(), anyString(), any());
        }
    }
}
