package com.aaa.collector.stock.supply.correction;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.supply.KisShortSaleResponse;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * {@code short_sale_domestic} T+0 예비치 소급 정정 서비스 (근본원인 B, aaa-infra#133,
 * SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-010~012, -020~022, -030, plan.md §M5).
 *
 * <p>REQ-T0R-010~012 대상 행({@code DATE(created_at) = trade_date}인 T+0 시그니처 행, {@code [2026-06-29,
 * closingWindowEndDate]} 구간)을 선택해 KIS TR04({@code FHPST04830000}) 단일 날짜 라이브 재조회 → {@code
 * short_sell_qty}·{@code short_sell_vol_rate}를 확정치로 원자적 UPDATE한다(REQ-T0R-020, -021).
 *
 * <p>M3의 {@link AcmlVolReconciliationGuard}(Track 1 전용 3분기 판정)와는 별개 흐름이다 — 가드는 "acml_vol 채움용 재조회"이고
 * 이 서비스는 "T+0 확정치 재조회"다. 둘 다 TR04를 호출하지만, 이 서비스는 판정 로직 없이 라이브 값을 그대로 채택한다(REQ-T0R-020 — "저장된 값을 다른
 * 컬럼으로부터 재계산하지 않고 ... 라이브 재조회하여 확정치로 UPDATE").
 *
 * <p><b>대상 재확인 절차</b>(plan.md §M5): 상한({@code closingWindowEndDate})은 호출자가 매 실행 시 그 시점의 실제
 * REQ-T0R-001 배포 확정일을 다시 계산해 전달해야 한다 — 이 서비스는 값을 캐시하지 않고 호출마다 인자로 받은 범위를 그대로 조회에 사용한다 ("2026-06-29
 * ~ 오늘"이 아니라 "2026-06-29 ~ 배포 확정 시점"의 실제 누적 구간, REQ-T0R-011). 이 서비스는 {@code
 * t0r_correction_status}(M8/M7 게이트 범위)를 직접 다루지 않는다 — 호출자({@code
 * ShortSaleDomesticT0RevisionScheduler}, M7)가 그 테이블에서 상한을 읽어 전달할 책임을 진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:NOTE: [AUTO] T+0 소급 정정 진입점 — ShortSaleDomesticT0RevisionScheduler(M7)가
// t0r_correction_status 조회 후 상한을 전달해 호출한다
// @MX:REASON: SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-010~012,-020~022,-030,
// plan.md
// §M5/§M7
public class ShortSaleDomesticT0RevisionCorrectionService {

    /** 배치 페이지 크기 — 행마다 TR04 재조회(네트워크 호출)를 수반하므로 Track 1과 동일하게 소규모로 제한(REQ-T0R-030). */
    static final int BATCH_SIZE = 50;

    /** REQ-T0R-011 하한(리터럴) — 근본원인 B 소급 정정 대상 구간의 시작일. */
    static final LocalDate CLOSING_WINDOW_START_DATE = LocalDate.of(2026, 6, 29);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final ShortSaleDomesticRepository shortSaleDomesticRepository;
    private final ShortSaleCollectionService shortSaleCollectionService;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * REQ-T0R-010~012 대상 T+0 소급 정정 배치를 실행한다.
     *
     * <p>조회 조건에 더 이상 걸리는 행이 없을 때까지 유한 배치 단위로 순차 처리한다 — 단일 트랜잭션으로 전체를 처리하지 않는다(Track 1/Track 2와 동일
     * 근거).
     *
     * @param closingWindowEndDate 상한(inclusive, REQ-T0R-001 실배포일) — 호출자가 매 실행 재계산해 전달(대상 재확인 절차,
     *     plan.md §M5)
     * @return 이번 실행의 처리 결과 집계
     */
    public ShortSaleT0RevisionCorrectionResult correctT0Revisions(LocalDate closingWindowEndDate) {
        LeaseSession session = keyLeaseRegistry.openSession();
        if (session.isEmpty()) {
            log.error("[t0-revision-correction] 모든 키 죽음 — 이번 실행 skip");
            return new ShortSaleT0RevisionCorrectionResult(0, 0);
        }

        int corrected = 0;
        int skipped = 0;
        long afterId = 0;
        Pageable page = PageRequest.of(0, BATCH_SIZE);

        List<ShortSaleDomestic> batch =
                shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                        CLOSING_WINDOW_START_DATE, closingWindowEndDate, afterId, page);
        while (!batch.isEmpty()) {
            for (ShortSaleDomestic row : batch) {
                afterId = row.getId();
                if (correctRow(session, row)) {
                    corrected++;
                } else {
                    skipped++;
                }
            }
            batch =
                    shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                            CLOSING_WINDOW_START_DATE, closingWindowEndDate, afterId, page);
        }

        log.info(
                "[t0-revision-correction] 실행 완료 — corrected={}, skipped={}, window=[{}, {}]",
                corrected,
                skipped,
                CLOSING_WINDOW_START_DATE,
                closingWindowEndDate);
        return new ShortSaleT0RevisionCorrectionResult(corrected, skipped);
    }

    /** 단일 행 처리 — TR04 단일 날짜 재조회 → (성공 시) 확정치로 원자적 UPDATE. 재계산·가드 판정 없음(REQ-T0R-020). */
    private boolean correctRow(LeaseSession session, ShortSaleDomestic row) {
        String symbol = row.getStock().getSymbol();
        LocalDate tradeDate = row.getTradeDate();

        Optional<KisShortSaleResponse.ShortSaleRow> liveRow;
        try {
            liveRow = findLiveRow(session, symbol, tradeDate);
        } catch (KisRateLimitException | RestClientException e) {
            // REQ-KISGATE-022 계승: retryable 재시도 소진 → graceful
            // skip(ShortSaleVolRateCorrectionService와
            // 동일 패턴)
            log.warn(
                    "[t0-revision-correction] TR04 재조회 실패(재시도 소진) — symbol={}, date={}, reason={}",
                    symbol,
                    tradeDate,
                    e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[t0-revision-correction] 인터럽트 — symbol={}, date={} skip", symbol, tradeDate);
            return false;
        } catch (NoHealthyKeyException e) {
            log.warn(
                    "[t0-revision-correction] 건강 키 0개로 skip — symbol={}, date={}",
                    symbol,
                    tradeDate);
            return false;
        } catch (KisTokenIssueException e) {
            log.warn(
                    "[t0-revision-correction] 토큰 발급 실패로 skip — symbol={}, date={}, error={}",
                    symbol,
                    tradeDate,
                    e.getMessage());
            return false;
        }

        if (liveRow.isEmpty()) {
            // EC — 상장폐지 등으로 TR04 재조회가 빈 응답을 반환하는 경우(Track 1 EC-3와 동일 사유).
            log.warn(
                    "[t0-revision-correction] TR04 재조회 결과 없음 — symbol={}, date={}",
                    symbol,
                    tradeDate);
            return false;
        }

        long liveQty;
        BigDecimal liveRate;
        try {
            liveQty = parseLongOrZero(liveRow.get().sstsCntgQty());
            liveRate = new BigDecimal(liveRow.get().sstsVolRlim());
        } catch (NumberFormatException e) {
            log.warn(
                    "[t0-revision-correction] 라이브 응답 숫자 파싱 실패 — symbol={}, date={}",
                    symbol,
                    tradeDate);
            return false;
        }

        shortSaleDomesticRepository.updateT0RevisionCorrection(row.getId(), liveQty, liveRate);
        return true;
    }

    /** TR04 단일 날짜 재조회 응답에서 대상 거래일에 일치하는 행을 찾는다. */
    private Optional<KisShortSaleResponse.ShortSaleRow> findLiveRow(
            LeaseSession session, String symbol, LocalDate tradeDate) throws InterruptedException {
        KisShortSaleResponse response =
                shortSaleCollectionService.fetchSingleDate(session, symbol, tradeDate);
        String target = tradeDate.format(DATE_FMT);
        return response.output2().stream().filter(r -> target.equals(r.stckBsopDate())).findFirst();
    }

    private static long parseLongOrZero(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        return Long.parseLong(raw);
    }
}
