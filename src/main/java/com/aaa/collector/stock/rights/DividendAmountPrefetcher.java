package com.aaa.collector.stock.rights;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * CTRGT011R(해외주식 기간별권리조회, 명세28) 금액 프리페치 전담 협력자 (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001
 * REQ-ODA-010~017, 030, 040~046, 060~062).
 *
 * <p>{@link OverseasRightsCollectionService}의 종목별 rights-by-ice 루프 전에 단일 스레드로 호출되어, 권리유형
 * 03(일반배당)·75(특별배당)를 각각 독립 전체조회(PDNO 공백) + {@code CTX_AREA_NK50}/{@code FK50} 커서 페이징으로 완주하며 {@code
 * (symbol, acpl_bass_dt) → List<금액항목>} 맵을 구축한다(경로 A — 동일일자 03+75 병존 시 리스트에 둘 다 보존).
 *
 * <p>한 유형의 페이징 실패/절단(fail-closed)이 다른 유형에 영향을 주지 않는다(REQ-ODA-017). {@link GuardedKisExecutor}를
 * 경유하므로 throttle·재시도·멀티키 lease는 {@link OverseasRightsCollectionService}가 연 {@link LeaseSession}을
 * 그대로 상속한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DividendAmountPrefetcher {

    private static final String PERIOD_RIGHTS_TR_ID = "CTRGT011R";
    private static final String PERIOD_RIGHTS_PATH =
            "/uapi/overseas-price/v1/quotations/period-rights";

    /** 조회구분코드 — 현지기준일(REQ-ODA-010). */
    private static final String INQR_DVSN_LOCAL_DATE = "02";

    /** 권리유형코드 — 일반배당(REQ-ODA-010, RD-11). */
    static final String RIGHT_TYPE_GENERAL = "03";

    /** 권리유형코드 — 특별배당(REQ-ODA-010, RD-11). 배당옵션(74)은 제외(Exclusion #8). */
    static final String RIGHT_TYPE_SPECIAL = "75";

    private static final String DFNT_YN_CONFIRMED = "Y";

    /** 조회 윈도우 폭 — rights-by-ice 오늘±3개월 재사용(RD-4). */
    private static final long WINDOW_MONTHS = 3L;

    /** KST/ET 기준시각 비대칭 흡수용 경계 패딩(D6, RD-4). */
    private static final long WINDOW_PADDING_DAYS = 1L;

    /**
     * 프리페치 유형(03/75)당 페이지 안전 상한(REQ-ODA-012). 전세계 6개월 배당 이벤트는 분기말(3·6·9·12월 말) 특정 이틀에 수백 건 집중되며
     * 100건/페이지 기준 유형당 수십 페이지까지 관찰될 수 있다. 관찰 최대치의 수 배로 여유를 두어 절단(fail-closed)이 정상 분기말 트래픽에서는 발동하지
     * 않도록 한다. 절단은 조용한 누락이 아니라 명시적 fail-closed이므로 상한은 안전망 역할이다(plan.md M2).
     */
    private static final int MAX_PREFETCH_PAGES_PER_TYPE = 200;

    /** DECIMAL(15,5) 최대 정수부 자릿수 (10^10). 금액 전용 파서 경계(D5). */
    private static final int MAX_AMOUNT_INTEGER_DIGITS = 10;

    private static final int AMOUNT_SCALE = 5;

    /** DECIMAL(12,4) 최대 정수부 자릿수 (10^8). 율 전용 파서 경계(D5) — 금액 파서와 분리. */
    private static final int MAX_RATE_INTEGER_DIGITS = 8;

    private static final int RATE_SCALE = 4;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final GuardedKisExecutor guardedKisExecutor;

    /**
     * CTRGT011R을 03·75 각각 독립 전체조회+페이징하여 금액 맵을 구축한다(REQ-ODA-010, 017).
     *
     * <p>한 유형의 페이징 실패/절단이 다른 유형에 영향을 주지 않는다. 두 유형 모두 페이징 개시 전에 실패하면(세션/토큰 등, REQ-ODA-060) 빈 맵으로
     * 진행한다 — 결과적으로 이번 배치는 해외 배당 행을 하나도 생성하지 않는다(REQ-ODA-022 경유, 호출자 책임).
     *
     * @param session per-batch lease 세션(호출자가 연 세션을 그대로 상속)
     * @param trackedSymbols 활성 미국 추적 종목 심볼 집합(REQ-ODA-013)
     * @return 병합된 금액 맵과 프리페치 관측 카운터
     */
    @SuppressWarnings("PMD.UseConcurrentHashMap") // 단일 스레드 빌드 후 Map.copyOf로 동결, 이후 읽기 전용 공유
    DividendAmountPrefetch prefetch(LeaseSession session, Set<String> trackedSymbols) {
        LocalDate nyToday = LocalDate.now(NEW_YORK);
        // D6: KST/ET 윈도우 기준시각 비대칭을 경계 ±1일 패딩으로 흡수(RD-4)
        String startDate =
                nyToday.minusMonths(WINDOW_MONTHS).minusDays(WINDOW_PADDING_DAYS).format(DATE_FMT);
        String endDate =
                nyToday.plusMonths(WINDOW_MONTHS).plusDays(WINDOW_PADDING_DAYS).format(DATE_FMT);

        TypePrefetchOutcome general =
                prefetchType(session, RIGHT_TYPE_GENERAL, trackedSymbols, startDate, endDate);
        TypePrefetchOutcome special =
                prefetchType(session, RIGHT_TYPE_SPECIAL, trackedSymbols, startDate, endDate);

        Map<DividendAmountKey, List<DividendAmountItem>> merged = new HashMap<>();
        AtomicInteger prefetchTruncated = new AtomicInteger();
        AtomicInteger prefetchFailed = new AtomicInteger();

        applyOutcome(merged, general, RIGHT_TYPE_GENERAL, prefetchTruncated, prefetchFailed);
        applyOutcome(merged, special, RIGHT_TYPE_SPECIAL, prefetchTruncated, prefetchFailed);

        return new DividendAmountPrefetch(
                Map.copyOf(merged), prefetchTruncated.get(), prefetchFailed.get());
    }

    /**
     * 유형 1종의 프리페치 결과를 병합 맵에 반영하거나(SUCCESS), 폐기하며 관측 카운터를 증가시킨다(TRUNCATED/FAILED, REQ-ODA-012, 062).
     */
    @SuppressWarnings("PMD.GuardLogStatement") // debug 파라미터 구성 비용은 무시 가능
    private void applyOutcome(
            Map<DividendAmountKey, List<DividendAmountItem>> merged,
            TypePrefetchOutcome outcome,
            String rghtTypeCd,
            AtomicInteger prefetchTruncated,
            AtomicInteger prefetchFailed) {
        switch (outcome.status()) {
            case SUCCESS -> {
                outcome.amountsByKey()
                        .forEach(
                                (key, item) ->
                                        merged.computeIfAbsent(key, k -> new ArrayList<>())
                                                .add(item));
                log.debug(
                        "[overseas-rights] CTRGT011R 프리페치 성공 — rghtTypeCd={}, entries={}",
                        rghtTypeCd,
                        outcome.amountsByKey().size());
            }
            case TRUNCATED -> {
                prefetchTruncated.incrementAndGet();
                log.error(
                        "[overseas-rights] CTRGT011R 프리페치 MAX_PAGES({}) 절단 — rghtTypeCd={} 폐기(fail-closed)",
                        MAX_PREFETCH_PAGES_PER_TYPE,
                        rghtTypeCd);
            }
            case FAILED -> {
                prefetchFailed.incrementAndGet();
                log.error(
                        "[overseas-rights] CTRGT011R 프리페치 페이징 실패 — rghtTypeCd={} 폐기(fail-closed)",
                        rghtTypeCd);
            }
        }
    }

    /**
     * 권리유형 1종에 대해 커서 페이징을 완주하며 금액 맵을 구축한다(REQ-ODA-011, 013~016).
     *
     * <p>빈 {@code output} 또는 빈 다음 커서 → 정상 종료(SUCCESS). MAX_PAGES 도달 시 커서 잔존 →
     * TRUNCATED(REQ-ODA-012). 페이징 도중 재시도 소진 → FAILED(REQ-ODA-062).
     */
    @SuppressWarnings("PMD.UseConcurrentHashMap") // 단일 스레드 페이징 루프 내부 빌드, 결과만 반환(공유 없음)
    private TypePrefetchOutcome prefetchType(
            LeaseSession session,
            String rghtTypeCd,
            Set<String> trackedSymbols,
            String startDate,
            String endDate) {
        Map<DividendAmountKey, DividendAmountItem> typeMap = new HashMap<>();
        String nk50 = "";
        String fk50 = "";

        for (int page = 1; page <= MAX_PREFETCH_PAGES_PER_TYPE; page++) {
            KisPeriodRightsResponse response;
            try {
                response =
                        fetchPeriodRightsPage(session, rghtTypeCd, startDate, endDate, nk50, fk50);
            } catch (KisRateLimitException
                    | RestClientException
                    | NoHealthyKeyException
                    | KisTokenIssueException e) {
                log.warn(
                        "[overseas-rights] CTRGT011R 페이징 실패(재시도 소진/키 없음/토큰) — rghtTypeCd={}, page={},"
                                + " error={}",
                        rghtTypeCd,
                        page,
                        e.getMessage());
                return new TypePrefetchOutcome(PrefetchStatus.FAILED, Map.of());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn(
                        "[overseas-rights] CTRGT011R 페이징 인터럽트 — rghtTypeCd={}, page={}",
                        rghtTypeCd,
                        page);
                return new TypePrefetchOutcome(PrefetchStatus.FAILED, Map.of());
            }

            List<KisPeriodRightsResponse.PeriodRightsRow> output = response.output();
            if (output.isEmpty()) {
                return new TypePrefetchOutcome(PrefetchStatus.SUCCESS, typeMap);
            }

            for (KisPeriodRightsResponse.PeriodRightsRow row : output) {
                accumulateRow(typeMap, row, trackedSymbols, rghtTypeCd);
            }

            String nextNk50 = response.ctxAreaNk50();
            if (nextNk50 == null || nextNk50.isBlank()) {
                return new TypePrefetchOutcome(PrefetchStatus.SUCCESS, typeMap);
            }
            nk50 = nextNk50;
            fk50 = response.ctxAreaFk50();
        }

        // MAX_PAGES 도달 시점에도 커서가 남아있음 — fail-closed 절단(REQ-ODA-012)
        return new TypePrefetchOutcome(PrefetchStatus.TRUNCATED, Map.of());
    }

    /**
     * 확정(dfnt_yn=Y)·추적 종목·요청 유형 일치 행만 맵에 반영한다(REQ-ODA-013). 동일 {@code (symbol, acpl_bass_dt)}(동일
     * 유형) 중복은 마지막 관측값으로 덮어쓴다(REQ-ODA-016, RD-12 — 동일유형 last-confirmed-wins, {@link Map#put} 자연 구현).
     * {@code rghtTypeCd} 불일치(예: 74 배당옵션 혼입) 방어적 제외(Exclusion #8).
     */
    private void accumulateRow(
            Map<DividendAmountKey, DividendAmountItem> typeMap,
            KisPeriodRightsResponse.PeriodRightsRow row,
            Set<String> trackedSymbols,
            String expectedRghtTypeCd) {
        if (!DFNT_YN_CONFIRMED.equals(row.dfntYn())) {
            return;
        }
        if (!expectedRghtTypeCd.equals(row.rghtTypeCd())) {
            return;
        }
        String symbol = row.pdno();
        if (symbol == null || !trackedSymbols.contains(symbol)) {
            return;
        }
        LocalDate acplBassDt = parseDateOrNull(row.acplBassDt());
        if (acplBassDt == null) {
            return;
        }

        BigDecimal cashAmount = parseAmountOrNull(row.alctFrcrUnpr());
        BigDecimal cashRate = parseRateOrNull(row.cashAlctRt());
        BigDecimal stockRate = parseRateOrNull(row.stckAlctRt());

        DividendAmountKey key = new DividendAmountKey(symbol, acplBassDt);
        DividendAmountItem item =
                new DividendAmountItem(
                        row.rghtTypeCd(), cashAmount, cashRate, stockRate, row.crcyCd());
        typeMap.put(key, item);
    }

    /** 게이트를 경유해 CTRGT011R 1페이지를 조회한다(REQ-ODA-010, 011). */
    private KisPeriodRightsResponse fetchPeriodRightsPage(
            LeaseSession session,
            String rghtTypeCd,
            String startDate,
            String endDate,
            String nk50,
            String fk50)
            throws InterruptedException {
        Function<UriBuilder, URI> uriCustomizer =
                uri ->
                        uri.path(PERIOD_RIGHTS_PATH)
                                .queryParam("RGHT_TYPE_CD", rghtTypeCd)
                                .queryParam("INQR_DVSN_CD", INQR_DVSN_LOCAL_DATE)
                                .queryParam("INQR_STRT_DT", startDate)
                                .queryParam("INQR_END_DT", endDate)
                                .queryParam("PDNO", "")
                                .queryParam("PRDT_TYPE_CD", "")
                                .queryParam("CTX_AREA_NK50", nk50)
                                .queryParam("CTX_AREA_FK50", fk50)
                                .build();
        return guardedKisExecutor.execute(
                session, uriCustomizer, PERIOD_RIGHTS_TR_ID, KisPeriodRightsResponse.class);
    }

    /**
     * 금액 전용 파서 — {@code alct_frcr_unpr} → {@code cash_amount}(DECIMAL(15,5), D5). 정수부 10자리 초과 시
     * null(skip). 율 파서({@link #parseRateOrNull})와 분리 — scale/경계가 다르다.
     */
    private BigDecimal parseAmountOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal bd = new BigDecimal(raw.trim());
            BigDecimal scaled = bd.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            if (scaled.precision() - scaled.scale() > MAX_AMOUNT_INTEGER_DIGITS) {
                return null;
            }
            return scaled;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /**
     * 율 전용 파서 — {@code cash_alct_rt}/{@code stck_alct_rt} → {@code cash_rate}/{@code
     * stock_rate}(DECIMAL(12,4), D5). 정수부 8자리 초과 시 null(skip). 금액 파서와 분리.
     */
    private BigDecimal parseRateOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal bd = new BigDecimal(raw.trim());
            BigDecimal scaled = bd.setScale(RATE_SCALE, RoundingMode.HALF_UP);
            if (scaled.precision() - scaled.scale() > MAX_RATE_INTEGER_DIGITS) {
                return null;
            }
            return scaled;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /** yyyyMMdd 날짜 파싱 — null/공백/형식 오류 시 null 반환. */
    private LocalDate parseDateOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 권리유형 1종 프리페치 결과 상태. */
    private enum PrefetchStatus {
        SUCCESS,
        TRUNCATED,
        FAILED
    }

    /** 권리유형 1종 프리페치 중간 결과(병합 전, 동일유형 dedup 완료 상태). */
    private record TypePrefetchOutcome(
            PrefetchStatus status, Map<DividendAmountKey, DividendAmountItem> amountsByKey) {}
}
