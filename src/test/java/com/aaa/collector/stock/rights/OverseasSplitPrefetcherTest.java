package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.rights.OverseasSplitPrefetcher.OverseasSplitPrefetch;
import com.aaa.collector.stock.rights.OverseasSplitPrefetcher.PrefetchStatus;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@link OverseasSplitPrefetcher} 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-010~013,
 * 070/071).
 *
 * <p>14/15 독립 페이징·커서 종료·유형 단위 fail-closed(절단/도중 실패/전역 실패)·원본 행 반환·PDNO 파라미터화를 {@link
 * GuardedKisExecutor} mock으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasSplitPrefetcher 단위 테스트")
class OverseasSplitPrefetcherTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final String TR_ID = "CTRGT011R";
    private static final String SPLIT = OverseasSplitMapper.RGHT_TYPE_SPLIT;
    private static final String MERGE = OverseasSplitMapper.RGHT_TYPE_MERGE;

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    private OverseasSplitPrefetcher prefetcher;
    private LeaseSession session;

    @BeforeEach
    void setUp() {
        prefetcher = new OverseasSplitPrefetcher(guardedKisExecutor);
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        session = keyLeaseRegistry.openSession();
    }

    private KisPeriodRightsResponse.PeriodRightsRow splitRow(String pdno, String bassDt) {
        return new KisPeriodRightsResponse.PeriodRightsRow(
                bassDt,
                SPLIT,
                pdno,
                pdno + " INC",
                "512",
                "US0000000000",
                bassDt,
                "",
                "",
                "0",
                "400.0",
                "USD",
                "",
                "",
                "",
                "0",
                "0",
                "0",
                "0",
                "Y");
    }

    private KisPeriodRightsResponse response(
            List<KisPeriodRightsResponse.PeriodRightsRow> rows, String nk50, String fk50) {
        return new KisPeriodRightsResponse("0", "MCA00000", "정상", rows, nk50, fk50);
    }

    private KisPeriodRightsResponse empty() {
        return response(List.of(), null, null);
    }

    private ArgumentMatcher<Function<UriBuilder, URI>> rightTypeIs(String rghtTypeCd) {
        return uriCustomizer -> {
            if (uriCustomizer == null) {
                return false;
            }
            URI uri = uriCustomizer.apply(UriComponentsBuilder.newInstance());
            return uri.toString().contains("RGHT_TYPE_CD=" + rghtTypeCd);
        };
    }

    private void stubType(String rghtTypeCd, KisPeriodRightsResponse resp)
            throws InterruptedException {
        when(guardedKisExecutor.execute(
                        any(LeaseSession.class),
                        argThat(rightTypeIs(rghtTypeCd)),
                        eq(TR_ID),
                        eq(KisPeriodRightsResponse.class),
                        anyString()))
                .thenReturn(resp);
    }

    @Nested
    @DisplayName("정상 페이징 (REQ-OSPLIT-011)")
    class SuccessfulPaging {

        @Test
        @DisplayName("빈 output — 즉시 SUCCESS, 빈 행")
        void emptyOutput_success() throws Exception {
            stubType(SPLIT, empty());
            stubType(MERGE, empty());

            OverseasSplitPrefetch result = prefetcher.prefetch(session, "", "20260601", "20260901");

            assertThat(result.split().status()).isEqualTo(PrefetchStatus.SUCCESS);
            assertThat(result.split().rows()).isEmpty();
            assertThat(result.merge().status()).isEqualTo(PrefetchStatus.SUCCESS);
        }

        @Test
        @DisplayName("비공백 커서 → 계속, 빈 커서 → 종료. 두 페이지 원본 행 모두 반환")
        void multiPage_accumulatesRows() throws Exception {
            KisPeriodRightsResponse page1 =
                    response(List.of(splitRow("AAPL", "20200831")), "cursor-1", "fk-1");
            KisPeriodRightsResponse page2 =
                    response(List.of(splitRow("TSLA", "20220825")), null, null);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            argThat(rightTypeIs(SPLIT)),
                            eq(TR_ID),
                            eq(KisPeriodRightsResponse.class),
                            anyString()))
                    .thenReturn(page1, page2);
            stubType(MERGE, empty());

            OverseasSplitPrefetch result = prefetcher.prefetch(session, "", "20260601", "20260901");

            assertThat(result.split().status()).isEqualTo(PrefetchStatus.SUCCESS);
            assertThat(result.split().rows())
                    .extracting(KisPeriodRightsResponse.PeriodRightsRow::pdno)
                    .containsExactly("AAPL", "TSLA");
        }
    }

    @Nested
    @DisplayName("유형 단위 fail-closed (REQ-OSPLIT-012/071)")
    class FailClosed {

        @Test
        @DisplayName("MAX_PAGES 도달 시점 커서 잔존 — 해당 유형 TRUNCATED, 빈 행")
        void infiniteCursor_truncated() throws Exception {
            KisPeriodRightsResponse infinite =
                    response(List.of(splitRow("AAPL", "20200831")), "cursor-always", "fk-always");
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            argThat(rightTypeIs(SPLIT)),
                            eq(TR_ID),
                            eq(KisPeriodRightsResponse.class),
                            anyString()))
                    .thenReturn(infinite);
            stubType(MERGE, empty());

            OverseasSplitPrefetch result = prefetcher.prefetch(session, "", "20260601", "20260901");

            assertThat(result.split().status()).isEqualTo(PrefetchStatus.TRUNCATED);
            assertThat(result.split().rows()).isEmpty();
            assertThat(result.merge().status()).isEqualTo(PrefetchStatus.SUCCESS);
        }

        @Test
        @DisplayName("14 페이징 도중 재시도 소진 — 14만 FAILED, 15는 독립적으로 SUCCESS (REQ-OSPLIT-071)")
        void splitFailsMidway_mergeStillSucceeds() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            argThat(rightTypeIs(SPLIT)),
                            eq(TR_ID),
                            eq(KisPeriodRightsResponse.class),
                            anyString()))
                    .thenThrow(new RestClientException("boom"));
            stubType(MERGE, response(List.of(splitRow("GE", "20210802")), null, null));

            OverseasSplitPrefetch result = prefetcher.prefetch(session, "", "20260601", "20260901");

            assertThat(result.split().status()).isEqualTo(PrefetchStatus.FAILED);
            assertThat(result.merge().status()).isEqualTo(PrefetchStatus.SUCCESS);
            assertThat(result.merge().rows()).hasSize(1);
        }

        @Test
        @DisplayName("14·15 모두 페이징 개시 전 실패(세션/토큰) — 둘 다 FAILED (REQ-OSPLIT-070)")
        void bothFailBeforePaging_bothFailed() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            eq(TR_ID),
                            eq(KisPeriodRightsResponse.class),
                            anyString()))
                    .thenThrow(new RestClientException("session/token failure"));

            OverseasSplitPrefetch result = prefetcher.prefetch(session, "", "20260601", "20260901");

            assertThat(result.split().status()).isEqualTo(PrefetchStatus.FAILED);
            assertThat(result.merge().status()).isEqualTo(PrefetchStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("PDNO 파라미터화 (REQ-OSPLIT-061)")
    class PdnoParameterization {

        @Test
        @DisplayName("백필 조회 — PDNO=심볼이 요청 URI에 반영된다")
        void backfillPdno_appliedToUri() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            argThat(
                                    uriCustomizer -> {
                                        URI uri =
                                                uriCustomizer.apply(
                                                        UriComponentsBuilder.newInstance());
                                        return uri.toString().contains("PDNO=AAPL");
                                    }),
                            eq(TR_ID),
                            eq(KisPeriodRightsResponse.class),
                            anyString()))
                    .thenReturn(empty());

            OverseasSplitPrefetch result =
                    prefetcher.prefetch(session, "AAPL", "19500101", "20260901");

            assertThat(result.split().status()).isEqualTo(PrefetchStatus.SUCCESS);
        }
    }

    @Nested
    @DisplayName("tr_cont 연속조회 헤더 전달 (SPEC-COLLECTOR-TRCONT-001 REQ-TRCONT-011/012/040)")
    class TrContCursorPaging {

        @Test
        @DisplayName("첫 페이지 trCont=\"\", 2페이지째부터 trCont=\"N\" — 인자 캡처로 검증")
        void firstPageBlank_secondPageOnwardsN() throws Exception {
            KisPeriodRightsResponse page1 =
                    response(List.of(splitRow("AAPL", "20200831")), "cursor-1", "fk-1");
            KisPeriodRightsResponse page2 =
                    response(List.of(splitRow("TSLA", "20220825")), null, null);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            argThat(rightTypeIs(SPLIT)),
                            eq(TR_ID),
                            eq(KisPeriodRightsResponse.class),
                            anyString()))
                    .thenReturn(page1, page2);
            stubType(MERGE, empty());

            prefetcher.prefetch(session, "", "20260601", "20260901");

            ArgumentCaptor<String> trContCaptor = ArgumentCaptor.forClass(String.class);
            verify(guardedKisExecutor, times(2))
                    .execute(
                            any(LeaseSession.class),
                            argThat(rightTypeIs(SPLIT)),
                            eq(TR_ID),
                            eq(KisPeriodRightsResponse.class),
                            trContCaptor.capture());
            assertThat(trContCaptor.getAllValues()).containsExactly("", "N");
        }
    }
}
