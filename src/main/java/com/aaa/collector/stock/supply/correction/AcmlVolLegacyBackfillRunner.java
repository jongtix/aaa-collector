package com.aaa.collector.stock.supply.correction;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.DailyOhlcv;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.supply.KisShortSaleResponse;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * 레거시 {@code acml_vol} 백필 — TR04 종목×기간 윈도우 청킹 재조회 루프
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 plan.md §M6, REQ-SSVC-031, -032, -070).
 *
 * <p>Track 1(M4, {@link ShortSaleVolRateCorrectionService#correctLegacyBacklog()})은 행마다 {@link
 * ShortSaleCollectionService#fetchSingleDate}로 단일 날짜씩 재조회한다 — 일일 증분(그날 신규 결측분만)에는 적합하나, 최초 실행 시 약
 * 274,539행 규모의 레거시 백로그 전체에 그대로 적용하면 행 수만큼(약 27만 회) TR04 호출이 발생해 rate limit 소진·소요 기간 문제를
 * 일으킨다(plan.md §6 R2). 본 컴포넌트는 <b>같은 대상 판별 조건(Track 1, {@code acml_vol IS NULL})과 같은 3분기 판정
 * 가드({@link AcmlVolReconciliationGuard})와 같은 원자적 쓰기 경로({@link
 * ShortSaleDomesticRepository#updateTrack1Correction})를 재사용</b>하되, 재조회 단계만 종목×기간 윈도우로 청킹한다 — 종목별로
 * 결측 거래일을 오름차순 정렬한 뒤 인접 날짜를 90일 ({@link
 * ShortSaleCollectionService#BACKFILL_LOOKBACK_CALENDAR_DAYS}, 기존 백필 윈도우 크기 재사용) 이내로 그리디 병합해 하나의
 * TR04 기간 조회로 묶는다(plan.md §M6 "TR04 기간 조회, 90일 윈도우당 ~60행 반환 실측 근거").
 *
 * <p>최초 1회 대량 실행(레거시 백로그 전체) 전용이며, M7(스케줄러, 미착수)의 일일 증분 처리는 계속 M4의 {@code correctLegacyBacklog()}가
 * 담당한다 — 두 컴포넌트는 같은 조회 조건·같은 가드·같은 쓰기 경로를 공유하므로 서로 중복 정정하지 않는다(먼저 처리된 행은 {@code acml_vol}이 채워져 두 조회
 * 조건에서 모두 자연히 제외된다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
// @MX:NOTE: [AUTO] M6 레거시 백로그 최초 1회 대량 실행 진입점 — 오퍼레이터가 §5 배포 순서(T0R 완료 마커 이후) 준수 하 수동/1회성 트리거
// @MX:REASON: SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 plan.md §M6, §5 — REQ-T0R-044 게이트가
// 닫히는 창
// 구간 행을 M4 정정 단계에서 defer하므로 조기 실행해도 데이터 오염 없음(무의미한 재시도만 발생)
public class AcmlVolLegacyBackfillRunner {

    /** 종목 id 커서 페이지 크기 — 유한 배치 단위 처리 원칙(REQ-SSVC-032)을 종목 단위로 확장 적용. */
    static final int STOCK_PAGE_SIZE = 20;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int RATE_SCALE = 2;

    private final ShortSaleDomesticRepository shortSaleDomesticRepository;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final AcmlVolReconciliationGuard guard;
    private final ShortSaleCollectionService shortSaleCollectionService;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * 레거시 백로그 전체를 종목×기간 윈도우로 청킹해 정정한다.
     *
     * <p>종목 id 커서로 유한 페이지 단위 순회하며(REQ-SSVC-032), 더 이상 대상 종목이 없을 때까지 반복한다 — 단일 트랜잭션으로 전체를 처리하지 않는다.
     *
     * @return 이번 실행의 처리 결과 집계
     */
    public AcmlVolLegacyBackfillResult run() {
        LeaseSession session = keyLeaseRegistry.openSession();
        if (session.isEmpty()) {
            log.error("[acml-vol-legacy-backfill] 모든 키 죽음 — 이번 실행 skip");
            return new AcmlVolLegacyBackfillResult(0, 0, 0, 0);
        }

        int corrected = 0;
        int revisionSuspected = 0;
        int skipped = 0;
        int windowFetchCount = 0;
        long afterStockId = 0;
        Pageable page = PageRequest.of(0, STOCK_PAGE_SIZE);

        List<Long> stockIds =
                shortSaleDomesticRepository.findTrack1LegacyBacklogStockIds(afterStockId, page);
        while (!stockIds.isEmpty()) {
            for (Long stockId : stockIds) {
                afterStockId = stockId;
                List<ShortSaleDomestic> rows =
                        shortSaleDomesticRepository.findTrack1LegacyBacklogByStock(stockId);
                if (rows.isEmpty()) {
                    continue;
                }
                String symbol = rows.getFirst().getStock().getSymbol();
                for (List<ShortSaleDomestic> window : chunkIntoWindows(rows)) {
                    WindowOutcome outcome = processWindow(session, symbol, window);
                    windowFetchCount += outcome.attempted() ? 1 : 0;
                    corrected += outcome.corrected();
                    revisionSuspected += outcome.revisionSuspected();
                    skipped += outcome.skipped();
                }
            }
            stockIds =
                    shortSaleDomesticRepository.findTrack1LegacyBacklogStockIds(afterStockId, page);
        }

        log.info(
                "[acml-vol-legacy-backfill] 실행 완료 —"
                        + " corrected={}, revisionSuspected={}, skipped={}, windowFetchCount={}",
                corrected,
                revisionSuspected,
                skipped,
                windowFetchCount);
        return new AcmlVolLegacyBackfillResult(
                corrected, revisionSuspected, skipped, windowFetchCount);
    }

    /**
     * 한 윈도우(같은 종목, ≤90일 구간으로 그리디 병합된 결측 거래일 묶음)를 처리한다 — TR04 기간 재조회 1회 → 윈도우 내 각 행에 M3 가드 3분기 판정 →
     * MATCHED/EVENT_ADJUSTED면 M4의 원자적 쓰기 경로 재사용.
     */
    private WindowOutcome processWindow(
            LeaseSession session, String symbol, List<ShortSaleDomestic> window) {
        LocalDate from = window.getFirst().getTradeDate();
        LocalDate to = window.getLast().getTradeDate();

        Map<LocalDate, KisShortSaleResponse.ShortSaleRow> byDate;
        try {
            KisShortSaleResponse response =
                    shortSaleCollectionService.fetchLegacyBackfillWindow(session, symbol, from, to);
            byDate = indexByTradeDate(response);
        } catch (KisRateLimitException | RestClientException e) {
            // REQ-KISGATE-022 계승: retryable 재시도 소진 → 이 윈도우 행 전부 graceful skip(M4와 동일 패턴)
            log.warn(
                    "[acml-vol-legacy-backfill] TR04 윈도우 재조회 실패(재시도 소진) —"
                            + " symbol={}, from={}, to={}, reason={}",
                    symbol,
                    from,
                    to,
                    e.getMessage());
            return WindowOutcome.attemptedSkippedAll(window.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "[acml-vol-legacy-backfill] 인터럽트 — symbol={}, from={}, to={} 윈도우 skip",
                    symbol,
                    from,
                    to);
            return WindowOutcome.attemptedSkippedAll(window.size());
        } catch (NoHealthyKeyException e) {
            log.warn(
                    "[acml-vol-legacy-backfill] 건강 키 0개로 skip — symbol={}, from={}, to={}",
                    symbol,
                    from,
                    to);
            return WindowOutcome.attemptedSkippedAll(window.size());
        } catch (KisTokenIssueException e) {
            log.warn(
                    "[acml-vol-legacy-backfill] 토큰 발급 실패로 skip —"
                            + " symbol={}, from={}, to={}, error={}",
                    symbol,
                    from,
                    to,
                    e.getMessage());
            return WindowOutcome.attemptedSkippedAll(window.size());
        }

        int corrected = 0;
        int revisionSuspected = 0;
        int skipped = 0;
        for (ShortSaleDomestic row : window) {
            KisShortSaleResponse.ShortSaleRow liveRow = byDate.get(row.getTradeDate());
            if (liveRow == null) {
                // EC-3류 — 상장폐지 등으로 TR04 윈도우 응답에 대상 거래일이 누락된 경우.
                log.warn(
                        "[acml-vol-legacy-backfill] TR04 윈도우 응답에 대상 거래일 없음(EC-3류) —"
                                + " symbol={}, date={}",
                        symbol,
                        row.getTradeDate());
                skipped++;
                continue;
            }

            long liveAcmlVol = parseLongOrZero(liveRow.acmlVol());
            long liveQty = parseLongOrZero(liveRow.sstsCntgQty());
            AcmlVolReconciliationResult reconciliation =
                    guard.reconcile(
                            row.getShortSellVolRate(), row.getShortSellQty(), liveAcmlVol, liveQty);

            if (reconciliation.outcome() == AcmlVolReconciliationOutcome.REVISION_SUSPECTED) {
                log.warn(
                        "[acml-vol-legacy-backfill] REVISION_SUSPECTED — 정정 스킵"
                                + "(acml_vol·vol_rate_verified_at 미충전, 자동 재시도) — symbol={}, date={}",
                        symbol,
                        row.getTradeDate());
                revisionSuspected++;
                continue;
            }

            Optional<BigDecimal> recomputedRate = recomputeRate(row);
            if (recomputedRate.isEmpty()) {
                skipped++;
                continue;
            }

            shortSaleDomesticRepository.updateTrack1Correction(
                    row.getId(),
                    reconciliation.acmlVol(),
                    recomputedRate.get(),
                    LocalDateTime.now());
            corrected++;
        }

        return new WindowOutcome(true, corrected, revisionSuspected, skipped);
    }

    /**
     * 종목별 결측 거래일(오름차순 정렬 전제)을 90일({@link
     * ShortSaleCollectionService#BACKFILL_LOOKBACK_CALENDAR_DAYS}) 이내 인접 구간으로 그리디 병합한다.
     *
     * <p>새 윈도우는 직전 윈도우 시작일로부터 90일을 초과하는 첫 날짜에서 시작한다 — 기존 백필 윈도우 크기(90일)를 그대로 재사용할 뿐, 새 청킹 알고리즘을 독자
     * 설계하지 않는다(plan.md §M6).
     *
     * @param sortedRows 거래일 오름차순 정렬된 한 종목의 대상 행 목록
     * @return 각 원소가 하나의 TR04 기간 조회에 대응하는 윈도우(행) 목록의 목록
     */
    static List<List<ShortSaleDomestic>> chunkIntoWindows(List<ShortSaleDomestic> sortedRows) {
        List<List<ShortSaleDomestic>> windows = new ArrayList<>();
        if (sortedRows.isEmpty()) {
            return windows;
        }

        // subList()는 원본 리스트의 뷰를 반환할 뿐 신규 객체를 할당하지 않는다 — 루프 안에서 new ArrayList<>()를
        // 반복 생성하지 않도록 인덱스 경계만 추적한 뒤 경계 확정 시점에만 뷰를 잘라낸다.
        int windowStartIdx = 0;
        LocalDate windowStart = sortedRows.getFirst().getTradeDate();
        for (int i = 1; i < sortedRows.size(); i++) {
            LocalDate tradeDate = sortedRows.get(i).getTradeDate();
            if (ChronoUnit.DAYS.between(windowStart, tradeDate)
                    > ShortSaleCollectionService.BACKFILL_LOOKBACK_CALENDAR_DAYS) {
                windows.add(sortedRows.subList(windowStartIdx, i));
                windowStartIdx = i;
                windowStart = tradeDate;
            }
        }
        windows.add(sortedRows.subList(windowStartIdx, sortedRows.size()));
        return windows;
    }

    /** TR04 윈도우 응답을 거래일(yyyyMMdd 파싱) 기준으로 색인한다. */
    private static Map<LocalDate, KisShortSaleResponse.ShortSaleRow> indexByTradeDate(
            KisShortSaleResponse response) {
        return response.output2().stream()
                .filter(row -> row.stckBsopDate() != null && !row.stckBsopDate().isBlank())
                .collect(
                        Collectors.toMap(
                                row -> LocalDate.parse(row.stckBsopDate(), DATE_FMT),
                                row -> row,
                                (first, second) -> first));
    }

    /**
     * REQ-SSVC-011 공식({@code round(short_sell_qty / daily_ohlcv.volume × 100, 2)})으로 {@code
     * short_sell_vol_rate}를 재계산한다 — {@code
     * ShortSaleVolRateCorrectionService.recomputeRate}(private, Track 1/Track 2 공용)와 동일한 SPEC 공식이다.
     * M4 파일은 리팩터링하지 않으므로(PRESERVE) 동일 공식만 독립 적용한다.
     */
    private Optional<BigDecimal> recomputeRate(ShortSaleDomestic row) {
        List<DailyOhlcv> matches =
                dailyOhlcvRepository.findByStockIdAndTradeDateIn(
                        row.getStock().getId(), List.of(row.getTradeDate()));
        if (matches.isEmpty()) {
            // EC-1 — 매칭되는 daily_ohlcv 행이 없는 방어적 케이스(이론상 0건 예상).
            log.warn(
                    "[acml-vol-legacy-backfill] daily_ohlcv 매칭 행 없음(EC-1) — stockId={}, date={}",
                    row.getStock().getId(),
                    row.getTradeDate());
            return Optional.empty();
        }
        long volume = matches.getFirst().getVolume();
        if (volume == 0) {
            log.warn(
                    "[acml-vol-legacy-backfill] daily_ohlcv.volume=0 — 재계산 분모 0, skip —"
                            + " stockId={}, date={}",
                    row.getStock().getId(),
                    row.getTradeDate());
            return Optional.empty();
        }
        BigDecimal rate =
                BigDecimal.valueOf(row.getShortSellQty())
                        .multiply(HUNDRED)
                        .divide(BigDecimal.valueOf(volume), RATE_SCALE, RoundingMode.HALF_UP);
        return Optional.of(rate);
    }

    private static long parseLongOrZero(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        return Long.parseLong(raw);
    }

    /** 윈도우 1건 처리 결과 — TR04 호출 시도 여부(재조회 실패 시에도 시도는 발생) + 행 단위 집계. */
    private record WindowOutcome(
            boolean attempted, int corrected, int revisionSuspected, int skipped) {

        static WindowOutcome attemptedSkippedAll(int rowCount) {
            return new WindowOutcome(true, 0, 0, rowCount);
        }
    }
}
