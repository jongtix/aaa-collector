package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * DividendAmountPrefetcher 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001 REQ-ODA-010~017,
 * 030, 060~062).
 *
 * <p>CTRGT011R 03(일반배당)·75(특별배당) 독립 페이징·fail-closed(절단/도중 실패)·경로 A 리스트 병합·동일유형 last-confirmed-wins·
 * 확정(dfnt_yn)/추적종목/유형일치 필터·금액vs율 파서 분리를 {@link GuardedKisExecutor} mock으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DividendAmountPrefetcher 단위 테스트")
class DividendAmountPrefetcherTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    private static final String PERIOD_RIGHTS_TR_ID = "CTRGT011R";
    private static final String RIGHT_TYPE_GENERAL = DividendAmountPrefetcher.RIGHT_TYPE_GENERAL;
    private static final String RIGHT_TYPE_SPECIAL = DividendAmountPrefetcher.RIGHT_TYPE_SPECIAL;

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private HealthyKeySelector healthyKeySelector;

    private DividendAmountPrefetcher prefetcher;
    private LeaseSession session;

    @BeforeEach
    void setUp() {
        prefetcher = new DividendAmountPrefetcher(guardedKisExecutor);
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        session = keyLeaseRegistry.openSession();
    }

    private KisPeriodRightsResponse.PeriodRightsRow periodRightsRow(
            String pdno,
            String rghtTypeCd,
            String acplBassDt,
            String alctFrcrUnpr,
            String cashAlctRt,
            String stckAlctRt,
            String crcyCd,
            String dfntYn) {
        return new KisPeriodRightsResponse.PeriodRightsRow(
                acplBassDt,
                rghtTypeCd,
                pdno,
                "TEST NAME",
                "512",
                "US0000000000",
                acplBassDt,
                "",
                "",
                cashAlctRt,
                stckAlctRt,
                crcyCd,
                "",
                "",
                "",
                alctFrcrUnpr,
                "0.00000",
                "0.00000",
                "0.00000",
                dfntYn);
    }

    private KisPeriodRightsResponse.PeriodRightsRow confirmedRow(
            String pdno, String rghtTypeCd, String acplBassDt, String amount, String crcyCd) {
        return periodRightsRow(pdno, rghtTypeCd, acplBassDt, amount, "0", "0", crcyCd, "Y");
    }

    private KisPeriodRightsResponse periodRightsResponse(
            List<KisPeriodRightsResponse.PeriodRightsRow> rows, String nk50, String fk50) {
        return new KisPeriodRightsResponse("0", "MCA00000", "정상", rows, nk50, fk50);
    }

    private KisPeriodRightsResponse emptyPeriodRightsResponse() {
        return periodRightsResponse(List.of(), null, null);
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

    private void stubType(String rghtTypeCd, KisPeriodRightsResponse response)
            throws InterruptedException {
        when(guardedKisExecutor.execute(
                        any(LeaseSession.class),
                        argThat(rightTypeIs(rghtTypeCd)),
                        eq(PERIOD_RIGHTS_TR_ID),
                        eq(KisPeriodRightsResponse.class)))
                .thenReturn(response);
    }

    @Nested
    @DisplayName("정상 페이징 완주 (REQ-ODA-011)")
    class SuccessfulPaging {

        @Test
        @DisplayName("빈 output — 즉시 종료, 빈 맵")
        void emptyOutput_endsImmediately() throws Exception {
            stubType(RIGHT_TYPE_GENERAL, emptyPeriodRightsResponse());
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            assertThat(result.amountsByKey()).isEmpty();
            assertThat(result.degraded()).isFalse();
        }

        @Test
        @DisplayName("비공백 커서 → 계속, 빈 커서 → 종료. 두 페이지 모두 순회해 후순위 종목도 매칭")
        void nonBlankCursor_continuesUntilBlankCursor() throws Exception {
            KisPeriodRightsResponse page1 =
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.26000",
                                            "USD")),
                            "cursor-1",
                            "fk-1");
            KisPeriodRightsResponse page2 =
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "MSFT",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.68000",
                                            "USD")),
                            null,
                            null);
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            argThat(rightTypeIs(RIGHT_TYPE_GENERAL)),
                            eq(PERIOD_RIGHTS_TR_ID),
                            eq(KisPeriodRightsResponse.class)))
                    .thenReturn(page1, page2);
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL", "MSFT"));

            assertThat(result.amountsByKey()).hasSize(2);
            assertThat(result.degraded()).isFalse();
        }
    }

    @Nested
    @DisplayName("fail-closed — MAX_PAGES 절단 / 도중 실패 (REQ-ODA-012, 017, 062)")
    class FailClosed {

        @Test
        @DisplayName("MAX_PAGES 도달 시점에도 커서 잔존 — 해당 유형 폐기(TRUNCATED), prefetchTruncated=1")
        void maxPagesReachedWithCursorRemaining_truncated() throws Exception {
            // 매 페이지 비공백 커서 반환(무한 페이지 시뮬레이션)
            KisPeriodRightsResponse infinitePage =
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "20260101",
                                            "1.00000",
                                            "USD")),
                            "cursor-always",
                            "fk-always");
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            argThat(rightTypeIs(RIGHT_TYPE_GENERAL)),
                            eq(PERIOD_RIGHTS_TR_ID),
                            eq(KisPeriodRightsResponse.class)))
                    .thenReturn(infinitePage);
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            assertThat(result.prefetchTruncated()).isEqualTo(1);
            assertThat(result.prefetchFailed()).isZero();
            assertThat(result.amountsByKey()).isEmpty();
            assertThat(result.degraded()).isTrue();
        }

        @Test
        @DisplayName("75 페이징 도중 재시도 소진 — 75만 폐기(FAILED), 03은 독립적으로 완주(REQ-ODA-017, 062)")
        void specialTypeFailsMidway_generalStillSucceeds() throws Exception {
            stubType(
                    RIGHT_TYPE_GENERAL,
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.26000",
                                            "USD")),
                            null,
                            null));
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            argThat(rightTypeIs(RIGHT_TYPE_SPECIAL)),
                            eq(PERIOD_RIGHTS_TR_ID),
                            eq(KisPeriodRightsResponse.class)))
                    .thenThrow(new RestClientException("boom"));

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            assertThat(result.prefetchFailed()).isEqualTo(1);
            assertThat(result.prefetchTruncated()).isZero();
            assertThat(result.amountsByKey())
                    .containsKey(
                            new DividendAmountKey("AAPL", java.time.LocalDate.of(2026, 5, 11)));
        }

        @Test
        @DisplayName("(반대 방향) 03 페이징 도중 실패 — 03만 폐기, 75는 독립적으로 완주")
        void generalTypeFailsMidway_specialStillSucceeds() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            argThat(rightTypeIs(RIGHT_TYPE_GENERAL)),
                            eq(PERIOD_RIGHTS_TR_ID),
                            eq(KisPeriodRightsResponse.class)))
                    .thenThrow(new RestClientException("boom"));
            stubType(
                    RIGHT_TYPE_SPECIAL,
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "SD",
                                            RIGHT_TYPE_SPECIAL,
                                            "20260520",
                                            "0.20000",
                                            "USD")),
                            null,
                            null));

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("SD"));

            assertThat(result.prefetchFailed()).isEqualTo(1);
            assertThat(result.amountsByKey())
                    .containsKey(new DividendAmountKey("SD", java.time.LocalDate.of(2026, 5, 20)));
        }

        @Test
        @DisplayName("03·75 둘 다 페이징 개시 전 실패 — 빈 맵, prefetchFailed=2(REQ-ODA-060)")
        void bothTypesFailBeforePagingStarts_emptyMap() throws Exception {
            when(guardedKisExecutor.execute(
                            any(LeaseSession.class),
                            any(),
                            eq(PERIOD_RIGHTS_TR_ID),
                            eq(KisPeriodRightsResponse.class)))
                    .thenThrow(new RestClientException("session/token failure"));

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            assertThat(result.prefetchFailed()).isEqualTo(2);
            assertThat(result.amountsByKey()).isEmpty();
        }
    }

    @Nested
    @DisplayName("필터링 (REQ-ODA-013, 030, Exclusion #8)")
    class Filtering {

        @Test
        @DisplayName("dfnt_yn=N(예정) 행 — 맵에서 제외")
        void pendingRow_excluded() throws Exception {
            stubType(
                    RIGHT_TYPE_GENERAL,
                    periodRightsResponse(
                            List.of(
                                    periodRightsRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.26000",
                                            "0",
                                            "0",
                                            "USD",
                                            "N")),
                            null,
                            null));
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            assertThat(result.amountsByKey()).isEmpty();
        }

        @Test
        @DisplayName("AC-4: 비추적 시장(홍콩·중국) 혼입 — 추적 종목만 맵에 반영")
        void untrackedSymbols_filteredOut() throws Exception {
            stubType(
                    RIGHT_TYPE_GENERAL,
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "01698",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.12000",
                                            "USD"),
                                    confirmedRow(
                                            "03466",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.13000",
                                            "HKD"),
                                    confirmedRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.26000",
                                            "USD")),
                            null,
                            null));
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            assertThat(result.amountsByKey()).hasSize(1);
            assertThat(result.amountsByKey())
                    .containsOnlyKeys(
                            new DividendAmountKey("AAPL", java.time.LocalDate.of(2026, 5, 11)));
        }

        @Test
        @DisplayName("AC-1c: 배당옵션(74) 행이 03 응답에 방어적으로 섞여도 rghtTypeCd 불일치로 제외")
        void dividendOptionRowMixedIntoGeneralType_excludedDefensively() throws Exception {
            stubType(
                    RIGHT_TYPE_GENERAL,
                    periodRightsResponse(
                            List.of(
                                    periodRightsRow(
                                            "FCT",
                                            "74",
                                            "20260324",
                                            "0.00000",
                                            "0",
                                            "0.993",
                                            "USD",
                                            "Y")),
                            null,
                            null));
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("FCT"));

            assertThat(result.amountsByKey()).isEmpty();
        }

        @Test
        @DisplayName("acpl_bass_dt 파싱 실패 행 — 맵에서 제외(매칭 불가)")
        void unparsableAcplBassDt_excluded() throws Exception {
            stubType(
                    RIGHT_TYPE_GENERAL,
                    periodRightsResponse(
                            List.of(
                                    periodRightsRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "",
                                            "0.26000",
                                            "0",
                                            "0",
                                            "USD",
                                            "Y")),
                            null,
                            null));
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            assertThat(result.amountsByKey()).isEmpty();
        }
    }

    @Nested
    @DisplayName("동일유형 last-confirmed-wins (REQ-ODA-016, RD-12)")
    class LastConfirmedWins {

        @Test
        @DisplayName("동일 (symbol, acpl_bass_dt, rght_type_cd) 중복 — 마지막 관측값으로 덮어씀")
        void duplicateSameTypeSameKey_overwrittenByLast() throws Exception {
            KisPeriodRightsResponse response =
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.10000",
                                            "USD"),
                                    confirmedRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.26000",
                                            "USD")),
                            null,
                            null);
            stubType(RIGHT_TYPE_GENERAL, response);
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            List<DividendAmountItem> items =
                    result.amountsByKey()
                            .get(
                                    new DividendAmountKey(
                                            "AAPL", java.time.LocalDate.of(2026, 5, 11)));
            assertThat(items).hasSize(1);
            assertThat(items.getFirst().cashAmount()).isEqualByComparingTo("0.26000");
        }

        @Test
        @DisplayName("동일일자 03+75 병존 — 서로 덮어쓰지 않고 리스트에 둘 다 보존(경로 A)")
        void differentTypesSameKey_bothPreserved() throws Exception {
            stubType(
                    RIGHT_TYPE_GENERAL,
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.50000",
                                            "USD")),
                            null,
                            null));
            stubType(
                    RIGHT_TYPE_SPECIAL,
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "AAPL",
                                            RIGHT_TYPE_SPECIAL,
                                            "20260511",
                                            "2.00000",
                                            "USD")),
                            null,
                            null));

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            List<DividendAmountItem> items =
                    result.amountsByKey()
                            .get(
                                    new DividendAmountKey(
                                            "AAPL", java.time.LocalDate.of(2026, 5, 11)));
            assertThat(items).hasSize(2);
            assertThat(items)
                    .extracting(DividendAmountItem::rghtTypeCd, DividendAmountItem::cashAmount)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(
                                    RIGHT_TYPE_GENERAL, new java.math.BigDecimal("0.50000")),
                            org.assertj.core.groups.Tuple.tuple(
                                    RIGHT_TYPE_SPECIAL, new java.math.BigDecimal("2.00000")));
        }
    }

    @Nested
    @DisplayName("금액 파서 vs 율 파서 분리 (D5)")
    class ParserSeparation {

        @Test
        @DisplayName("금액 파서(scale 5, 정수부 10자리 경계) — 경계 초과 시 null")
        void amountParser_exceedsIntegerDigits_null() throws Exception {
            // 정수부 11자리(경계 10자리 초과)
            stubType(
                    RIGHT_TYPE_GENERAL,
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "12345678901.00000",
                                            "USD")),
                            null,
                            null));
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            DividendAmountItem item =
                    result.amountsByKey()
                            .get(new DividendAmountKey("AAPL", java.time.LocalDate.of(2026, 5, 11)))
                            .getFirst();
            assertThat(item.cashAmount()).isNull();
        }

        @Test
        @DisplayName("금액 정상 범위(정수부 10자리 이내) — scale 5로 정확히 파싱")
        void amountParser_withinBoundary_parsedWithScale5() throws Exception {
            stubType(
                    RIGHT_TYPE_GENERAL,
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "AAPL", RIGHT_TYPE_GENERAL, "20260511", "0.26", "USD")),
                            null,
                            null));
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            DividendAmountItem item =
                    result.amountsByKey()
                            .get(new DividendAmountKey("AAPL", java.time.LocalDate.of(2026, 5, 11)))
                            .getFirst();
            assertThat(item.cashAmount()).isEqualByComparingTo("0.26000");
            assertThat(item.cashAmount().scale()).isEqualTo(5);
        }

        @Test
        @DisplayName("율 파서(scale 4, 정수부 8자리 경계) — 경계 초과 시 null, 정상은 scale 4")
        void rateParser_boundaryAndScale() throws Exception {
            stubType(
                    RIGHT_TYPE_GENERAL,
                    periodRightsResponse(
                            List.of(
                                    confirmedRow(
                                            "AAPL",
                                            RIGHT_TYPE_GENERAL,
                                            "20260511",
                                            "0.26000",
                                            "USD")),
                            null,
                            null));
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("AAPL"));

            DividendAmountItem item =
                    result.amountsByKey()
                            .get(new DividendAmountKey("AAPL", java.time.LocalDate.of(2026, 5, 11)))
                            .getFirst();
            // confirmedRow 헬퍼는 cash_alct_rt="0" 고정 — scale 4로 정규화되는지만 확인
            assertThat(item.cashRate()).isEqualByComparingTo("0.0000");
            assertThat(item.cashRate().scale()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("AC-9: acpl_bass_dt로만 매칭 (bass_dt 아님)")
    class AcplBassDtMatching {

        @Test
        @DisplayName("bass_dt(KST)가 acpl_bass_dt와 어긋나도 acpl_bass_dt로 키가 구성된다")
        void keyBuiltFromAcplBassDt_notBassDt() throws Exception {
            // PEP 실측: bass_dt=20260608(KST) ≠ acpl_bass_dt=20260605(현지)
            stubType(
                    RIGHT_TYPE_GENERAL,
                    periodRightsResponse(
                            List.of(
                                    periodRightsRow(
                                            "PEP",
                                            RIGHT_TYPE_GENERAL,
                                            "20260605",
                                            "1.48000",
                                            "0",
                                            "0",
                                            "USD",
                                            "Y")),
                            null,
                            null));
            stubType(RIGHT_TYPE_SPECIAL, emptyPeriodRightsResponse());

            DividendAmountPrefetch result = prefetcher.prefetch(session, Set.of("PEP"));

            assertThat(result.amountsByKey())
                    .containsOnlyKeys(
                            new DividendAmountKey("PEP", java.time.LocalDate.of(2026, 6, 5)));
        }
    }
}
