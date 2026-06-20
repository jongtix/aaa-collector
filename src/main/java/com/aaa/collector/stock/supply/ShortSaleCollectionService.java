package com.aaa.collector.stock.supply;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * 종목별 공매도 일별추이 수집 서비스 (TR FHPST04830000).
 *
 * <p>{@link InvestorTrendCollectionService}와 동일 골격. {@code FID_INPUT_DATE_1}(시작=today−14일)/{@code
 * FID_INPUT_DATE_2}(종료=today) 기간 조회로 14일 윈도우를 1회 호출에 충족한다(REQ-BATCH2-040).
 *
 * <p>매핑(REQ-BATCH2-041): 공매도 체결/거래대금 + 비율 + 누적. 금액은 원 단위 무변환(REQ-BATCH2-042 / OI-2).
 *
 * <p>검증(REQ-BATCH2-060~063): 비율 절댓값 ≥ 1000(DECIMAL(7,4) 경계) 초과·음수 수량/금액·null·파싱 실패 건별 skip. 14일 윈도우
 * 밖 행 제외. 빈 응답은 0건 succeeded.
 *
 * <p>침묵 드롭(REQ-OBSV-023): 검증 통과 행들을 {@link ShortSaleInserter}로 종목별 배치 삽입한다 — 인서터가 JDBC 경고 체인에서 비-중복
 * 드롭을 캡처하고 {@link com.aaa.collector.observability.BatchMetrics}에 기록한다. 본 서비스는 도메인 검증·매핑·skip 집계만
 * 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 공매도 일별추이 수집 진입점 — 게이트 경유 키 lease·매핑·비율 경계 검증·멱등 저장·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-002 REQ-BATCH2-010~012,-040~042,-060~063,
// SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-020,-024 — 게이트 경유 통합 진입점
// @MX:SPEC: SPEC-COLLECTOR-BATCH-002, SPEC-COLLECTOR-KISGATE-001
public class ShortSaleCollectionService {

    static final int LOOKBACK_CALENDAR_DAYS = 14;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TR_ID = "FHPST04830000";
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/daily-short-sale";

    private final StockRepository stockRepository;
    private final ShortSaleRowMapper mapper;
    private final ShortSaleInserter inserter;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * 공매도 일별추이 수집을 실행하고 집계 결과를 반환한다 (활성종목 자체 조회).
     *
     * @param today 수집 기준일
     * @return 시도/성공/skip 종목 수 집계
     */
    public SupplyDemandResult collect(LocalDate today) {
        return collect(today, stockRepository.findAllActiveTradable());
    }

    /**
     * 공매도 일별추이 수집을 실행하고 집계 결과를 반환한다 (활성종목 외부 주입 — 통합 진입점이 1회 조회를 공유).
     *
     * @param today 수집 기준일
     * @param activeStocks 활성 관심종목 목록
     * @return 시도/성공/skip 종목 수 집계
     */
    public SupplyDemandResult collect(LocalDate today, List<Stock> activeStocks) {
        if (activeStocks.isEmpty()) {
            log.info("[short-sale] 수집 대상 없음 — activeStocks=0");
            return new SupplyDemandResult(0, 0, 0);
        }

        // REQ-KISGATE-006a: per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();
        int total = activeStocks.size();

        // REQ-KISGATE-024, REQ-KEYDIST-020 보존: 빈 스냅샷 = 전 키 사망 → skip-all + ERROR + no fallback
        if (session.isEmpty()) {
            log.error(
                    "[short-sale] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new SupplyDemandResult(total, 0, total);
        }

        LocalDate windowStart = today.minusDays(LOOKBACK_CALENDAR_DAYS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        // 키 선택은 게이트가 세션 스냅샷에서 동적 lease한다(REQ-KISGATE-020). 모든 종목이 동일 세션 공유(REQ-KISGATE-031).
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Stock stock : activeStocks) {
                executor.submit(
                        () -> collectStock(stock, session, today, windowStart, succeeded, skipped));
            }
        }

        SupplyDemandResult result = new SupplyDemandResult(total, succeeded.get(), skipped.get());
        log.info(
                "[short-sale] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                result.attempted(),
                result.succeeded(),
                result.skipped());
        return result;
    }

    private void collectStock(
            Stock stock,
            LeaseSession session,
            LocalDate today,
            LocalDate windowStart,
            AtomicInteger succeeded,
            AtomicInteger skipped) {

        String symbol = stock.getSymbol();
        try {
            KisShortSaleResponse response = fetch(session, symbol, windowStart, today);
            saveValidRows(stock, symbol, response, today, windowStart);
            succeeded.incrementAndGet();

        } catch (KisRateLimitException | RestClientException e) {
            // REQ-KISGATE-022: retryable 재시도 소진 → graceful skip
            log.warn("[short-sale] skip (재시도 소진) — symbol={}, reason={}", symbol, e.getMessage());
            skipped.incrementAndGet();
        } catch (InterruptedException e) {
            // RETRY-001 REQ-RETRY-017 보존: 인터럽트 플래그 복원 후 skip
            Thread.currentThread().interrupt();
            log.warn("[short-sale] 인터럽트 — symbol={} skip", symbol);
            skipped.incrementAndGet();
        } catch (NoHealthyKeyException e) {
            log.warn("[short-sale] 건강 키 0개로 skip — symbol={}", symbol);
            skipped.incrementAndGet();
        } catch (KisTokenIssueException e) {
            log.warn("[short-sale] 토큰 발급 실패로 skip — symbol={}, error={}", symbol, e.getMessage());
            skipped.incrementAndGet();
        }
    }

    /**
     * 백필용 윈도우 1구간 수집 — 당일 수집과 동일한 매핑·검증·INSERT IGNORE 경로를 재사용한다 (REQ-BACKFILL-002).
     *
     * <p>당일 경로({@link #collect(LocalDate)})와 차이는 두 가지뿐이다: (1) {@code (from, to)} 기간을 호출자가 직접 지정해 과거
     * 윈도우를 요청할 수 있고, (2) 종료 판정 입력인 {@link BackfillWindowResult}를 반환한다. {@link ShortSaleRowMapper}의
     * 윈도우 필터·검증·매핑을 그대로 거치므로 새 파싱 분기를 만들지 않는다.
     *
     * <p>[CR-02] 공매도는 fetch가 기간 윈도우(그룹 A식)이나 종료 판정은 그룹 B(0건/연속 무전진)다 — 종료는 T5/T6 책임이며 본 메서드는 최소
     * 거래일·행 수만 노출한다. 예외는 호출자(T6)가 상태 전이로 처리하도록 전파한다.
     *
     * @param stock 백필 대상 종목 (활성, REQ-BACKFILL-006)
     * @param session 호출자가 1회 고정한 per-run 헬스 스냅샷 세션
     * @param from 윈도우 하단 조회 시작일 ({@code FID_INPUT_DATE_1})
     * @param to 윈도우 상단 조회 종료일 ({@code FID_INPUT_DATE_2}, anchor)
     * @return 적재 대상 행의 최소 거래일 + 행 수 (적재 대상 없으면 {@link BackfillWindowResult#EMPTY})
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    public BackfillWindowResult collectWindow(
            Stock stock, LeaseSession session, LocalDate from, LocalDate to)
            throws InterruptedException {
        String symbol = stock.getSymbol();
        KisShortSaleResponse response = fetch(session, symbol, from, to);
        List<ShortSaleDomestic> validEntities = saveValidRows(stock, symbol, response, to, from);
        if (validEntities.isEmpty()) {
            return BackfillWindowResult.EMPTY;
        }
        // 매퍼가 이미 LocalDate로 파싱한 거래일에서 최소값을 직접 도출한다 — 2차 재파싱 없음.
        LocalDate oldest =
                validEntities.stream()
                        .map(ShortSaleDomestic::getTradeDate)
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        return new BackfillWindowResult(oldest, validEntities.size());
    }

    private KisShortSaleResponse fetch(
            LeaseSession session, String symbol, LocalDate from, LocalDate to)
            throws InterruptedException {
        String fromDate = from.format(DATE_FMT);
        String toDate = to.format(DATE_FMT);
        Function<UriBuilder, URI> uriCustomizer =
                uri ->
                        uri.path(PATH)
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_INPUT_ISCD", symbol)
                                .queryParam("FID_INPUT_DATE_1", fromDate)
                                .queryParam("FID_INPUT_DATE_2", toDate)
                                .build();
        return guardedKisExecutor.execute(
                session, uriCustomizer, TR_ID, KisShortSaleResponse.class);
    }

    /**
     * 검증·매핑·윈도우 필터·경계 커버리지 관측은 mapper에 위임하고, 결과 엔티티를 배치 삽입한 뒤 반환한다.
     *
     * <p>당일 경로({@link #collectStock})는 반환값을 무시하고, 백필 경로({@link #collectWindow})는 최소 거래일·행 수 도출에
     * 사용한다 — 동일 매핑·검증·적재 경로를 공유한다(REQ-BACKFILL-002).
     *
     * @return 적재한 검증 통과 엔티티 목록 (없으면 빈 목록)
     */
    private List<ShortSaleDomestic> saveValidRows(
            Stock stock,
            String symbol,
            KisShortSaleResponse response,
            LocalDate today,
            LocalDate windowStart) {
        List<ShortSaleDomestic> validEntities =
                mapper.collectValid(stock, symbol, response, today, windowStart);
        if (validEntities.isEmpty()) {
            return validEntities;
        }
        inserter.insertBatch(validEntities);
        return validEntities;
    }
}
