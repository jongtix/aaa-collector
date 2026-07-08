package com.aaa.collector.stock.rights;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * CTRGT011R(해외주식 기간별권리조회, 명세28) 액면분할·병합({@code RGHT_TYPE_CD ∈ {14, 15}}) 프리페치 전담 협력자
 * (SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-010~013, 070/071).
 *
 * <p>배당 {@link DividendAmountPrefetcher}와 구조는 같으나(전체조회 + 커서 페이징 + 유형 단위 fail-closed), 금액 맵을 만들지 않고
 * <b>원본 행 목록</b>을 그대로 반환한다 — dedup·정규화·매핑은 {@link OverseasSplitMapper}가 담당한다(관심사 분리, 배당 내부 결합 회피).
 *
 * <p>14·15를 각각 독립 페이징한다(REQ-OSPLIT-013) — 한 유형의 절단(TRUNCATED)/도중 실패(FAILED)가 다른 유형에 영향을 주지
 * 않는다(REQ-OSPLIT-071). 유형별로 빈 {@code output} 또는 빈 다음 커서 → 정상 종료(SUCCESS), MAX_PAGES 도달 시 커서 잔존 →
 * TRUNCATED(REQ-OSPLIT-012), 페이징 도중 재시도 소진/키 없음/토큰 실패 → FAILED(REQ-OSPLIT-071). {@code msg1} 문자열에
 * 의존하지 않는다(커서 기반 종료).
 *
 * <p>{@code PDNO}를 파라미터화해 정기 수집(공백=전체조회)과 종목지정 백필(심볼)이 동일 페이징·fail-closed 경로를 공유한다(REQ-OSPLIT-061).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OverseasSplitPrefetcher {

    private static final String PERIOD_RIGHTS_TR_ID = "CTRGT011R";
    private static final String PERIOD_RIGHTS_PATH =
            "/uapi/overseas-price/v1/quotations/period-rights";

    /** 조회구분코드 — 현지기준일(REQ-OSPLIT-010). */
    private static final String INQR_DVSN_LOCAL_DATE = "02";

    /**
     * 유형(14/15)당 페이지 안전 상한(REQ-OSPLIT-012). 3개월 윈도우 전체조회 표본이 유형당 45건(단일 페이지)이라 상한은 절단(fail-closed)
     * 안전망이며 정상 트래픽에서는 발동하지 않는다. 배당 프리페처와 동일 상한.
     */
    private static final int MAX_PREFETCH_PAGES_PER_TYPE = 200;

    private final GuardedKisExecutor guardedKisExecutor;

    /**
     * 14·15를 각각 독립 전체조회/종목지정 조회 + 커서 페이징하여 유형별 원본 행 목록을 구축한다(REQ-OSPLIT-010~013).
     *
     * <p>한 유형의 실패/절단이 다른 유형에 영향을 주지 않는다(REQ-OSPLIT-071). 두 유형 모두 페이징 개시 전에 실패하면(세션/토큰 등,
     * REQ-OSPLIT-070) 둘 다 FAILED로 반환된다 — 호출자가 이번 배치에서 SPLIT 행을 하나도 생성하지 않는다.
     *
     * @param session per-batch lease 세션(호출자가 연 세션을 그대로 상속)
     * @param pdno 종목코드 — 정기 수집은 공백(전체조회), 백필은 심볼(REQ-OSPLIT-061)
     * @param startDate 조회 시작일(yyyyMMdd)
     * @param endDate 조회 종료일(yyyyMMdd)
     * @return 14/15 유형별 프리페치 결과(status + 원본 행)
     */
    OverseasSplitPrefetch prefetch(
            LeaseSession session, String pdno, String startDate, String endDate) {
        TypeResult split =
                prefetchType(
                        session, OverseasSplitMapper.RGHT_TYPE_SPLIT, pdno, startDate, endDate);
        TypeResult merge =
                prefetchType(
                        session, OverseasSplitMapper.RGHT_TYPE_MERGE, pdno, startDate, endDate);
        return new OverseasSplitPrefetch(split, merge);
    }

    /**
     * 권리유형 1종에 대해 커서 페이징을 완주하며 원본 행을 누적한다(REQ-OSPLIT-011).
     *
     * <p>빈 {@code output} 또는 빈 다음 커서 → SUCCESS. MAX_PAGES 도달 시 커서 잔존 → TRUNCATED(REQ-OSPLIT-012).
     * 페이징 도중 재시도 소진/키 없음/토큰 실패 → FAILED(REQ-OSPLIT-071).
     */
    private TypeResult prefetchType(
            LeaseSession session,
            String rghtTypeCd,
            String pdno,
            String startDate,
            String endDate) {
        List<KisPeriodRightsResponse.PeriodRightsRow> rows = new ArrayList<>();
        String nk50 = "";
        String fk50 = "";

        for (int page = 1; page <= MAX_PREFETCH_PAGES_PER_TYPE; page++) {
            KisPeriodRightsResponse response;
            try {
                response =
                        fetchPeriodRightsPage(
                                session, rghtTypeCd, pdno, startDate, endDate, nk50, fk50, page);
            } catch (KisRateLimitException
                    | RestClientException
                    | NoHealthyKeyException
                    | KisTokenIssueException e) {
                log.warn(
                        "[overseas-split] CTRGT011R 페이징 실패(재시도 소진/키 없음/토큰) — rghtTypeCd={}, pdno={}, page={},"
                                + " error={}",
                        rghtTypeCd,
                        pdno,
                        page,
                        e.getMessage());
                return TypeResult.failed();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn(
                        "[overseas-split] CTRGT011R 페이징 인터럽트 — rghtTypeCd={}, page={}",
                        rghtTypeCd,
                        page);
                return TypeResult.failed();
            }

            List<KisPeriodRightsResponse.PeriodRightsRow> output = response.output();
            if (output.isEmpty()) {
                return TypeResult.success(rows);
            }
            rows.addAll(output);

            String nextNk50 = response.ctxAreaNk50();
            if (nextNk50 == null || nextNk50.isBlank()) {
                return TypeResult.success(rows);
            }
            nk50 = nextNk50;
            fk50 = response.ctxAreaFk50();
        }

        // MAX_PAGES 도달 시점에도 커서가 남아있음 — fail-closed 절단(REQ-OSPLIT-012)
        log.error(
                "[overseas-split] CTRGT011R 프리페치 MAX_PAGES({}) 절단 — rghtTypeCd={}, pdno={} 폐기(fail-closed)",
                MAX_PREFETCH_PAGES_PER_TYPE,
                rghtTypeCd,
                pdno);
        return TypeResult.truncated();
    }

    /**
     * 게이트를 경유해 CTRGT011R 1페이지를 조회한다(REQ-OSPLIT-010, 011).
     *
     * <p>SPEC-COLLECTOR-TRCONT-001 REQ-TRCONT-011 — 배당 프리페처와 동일 규약(REQ-TRCONT-012)으로 첫 페이지({@code
     * page == 1})는 {@code trCont=""}(헤더 미부착), 2페이지째부터는 {@code trCont="N"}을 전달한다.
     */
    private KisPeriodRightsResponse fetchPeriodRightsPage(
            LeaseSession session,
            String rghtTypeCd,
            String pdno,
            String startDate,
            String endDate,
            String nk50,
            String fk50,
            int page)
            throws InterruptedException {
        Function<UriBuilder, URI> uriCustomizer =
                uri ->
                        uri.path(PERIOD_RIGHTS_PATH)
                                .queryParam("RGHT_TYPE_CD", rghtTypeCd)
                                .queryParam("INQR_DVSN_CD", INQR_DVSN_LOCAL_DATE)
                                .queryParam("INQR_STRT_DT", startDate)
                                .queryParam("INQR_END_DT", endDate)
                                .queryParam("PDNO", pdno)
                                .queryParam("PRDT_TYPE_CD", "")
                                .queryParam("CTX_AREA_NK50", nk50)
                                .queryParam("CTX_AREA_FK50", fk50)
                                .build();
        String trCont = page == 1 ? "" : "N";
        return guardedKisExecutor.execute(
                session, uriCustomizer, PERIOD_RIGHTS_TR_ID, KisPeriodRightsResponse.class, trCont);
    }

    /** 권리유형 1종 프리페치 결과 상태(REQ-OSPLIT-012/071). */
    enum PrefetchStatus {
        SUCCESS,
        TRUNCATED,
        FAILED
    }

    /**
     * 권리유형 1종 프리페치 결과 — status + 원본 행. SUCCESS만 행을 담고, TRUNCATED/FAILED는 빈 행(폐기, fail-closed).
     *
     * @param status 프리페치 종료 상태
     * @param rows SUCCESS 시 원본 CTRGT011R 행, 그 외 빈 목록
     */
    record TypeResult(PrefetchStatus status, List<KisPeriodRightsResponse.PeriodRightsRow> rows) {

        TypeResult {
            rows = List.copyOf(rows);
        }

        static TypeResult success(List<KisPeriodRightsResponse.PeriodRightsRow> rows) {
            return new TypeResult(PrefetchStatus.SUCCESS, rows);
        }

        static TypeResult truncated() {
            return new TypeResult(PrefetchStatus.TRUNCATED, List.of());
        }

        static TypeResult failed() {
            return new TypeResult(PrefetchStatus.FAILED, List.of());
        }
    }

    /**
     * 14/15 유형별 프리페치 결과 묶음(REQ-OSPLIT-013).
     *
     * @param split RGHT_TYPE_CD=14(분할) 결과
     * @param merge RGHT_TYPE_CD=15(병합) 결과
     */
    record OverseasSplitPrefetch(TypeResult split, TypeResult merge) {}
}
