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
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * 공매도 거래량 비중({@code short_sell_vol_rate}) 2-트랙 정정 서비스
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-031~039, plan.md §M4).
 *
 * <p><b>Track 1(레거시·{@code acml_vol} 결측 가드)</b> — {@link #correctLegacyBacklog()}: {@code
 * short_sell_qty > 0 AND acml_vol IS NULL}인 행을 조회해 KIS TR04 단일 날짜 재조회 → {@link
 * AcmlVolReconciliationGuard} 3분기 판정 → MATCHED/EVENT_ADJUSTED면 {@code acml_vol} 채움 + {@code
 * daily_ohlcv} 조인 재계산(REQ-SSVC-011) + {@code vol_rate_verified_at} 기록을 단일 UPDATE로 원자적 수행한다.
 * REVISION_SUSPECTED면 둘 다 미충전 상태로 스킵한다(자동 재시도, REQ-SSVC-053).
 *
 * <p><b>Track 2(상시 재계산 스윕)</b> — {@link #verifyRecentInserts()}: {@code short_sell_qty > 0 AND
 * acml_vol IS NOT NULL AND vol_rate_verified_at IS NULL}인 행을 조회해 KIS 재조회 없이 {@code daily_ohlcv} 조인
 * 재계산만 수행한다 — 저장값과 다르면 UPDATE, 같아도 {@code vol_rate_verified_at}은 항상 기록한다(REQ-SSVC-035, -038).
 *
 * <p>두 트랙 모두 REQ-SSVC-011의 정정 공식({@code round(short_sell_qty / daily_ohlcv.volume × 100, 2)})을 동일하게
 * 사용한다 — 이 공식은 {@code acml_vol}을 입력으로 쓰지 않는다. {@code acml_vol}은 Track 1에서만 가드의 TR04 재조회 결과로 별도로
 * 채워지는 진단용 컬럼이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:NOTE: [AUTO] 2-트랙 정정 진입점 — ShortSaleDomesticCorrectionScheduler(M7)가 매 실행마다
// Track1→Track2 순서로 호출한다
// @MX:REASON: SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-030~039, plan.md §M4/§M7
public class ShortSaleVolRateCorrectionService {

    /** Track 1 페이지 크기 — 행마다 TR04 재조회(네트워크 호출)를 수반하므로 소규모로 제한(REQ-SSVC-032). */
    static final int TRACK1_BATCH_SIZE = 50;

    /** Track 2 페이지 크기 — KIS 재조회 없이 DB 조인·비교만 수행하므로 더 큰 배치를 허용(REQ-SSVC-032). */
    static final int TRACK2_BATCH_SIZE = 500;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int RATE_SCALE = 2;

    /**
     * WARN 관측 배율 하한 (REQ-SSVC-041) — 가드의 EVENT_ADJUSTED/REVISION_SUSPECTED 경계(0.5/2.0,
     * REQ-SSVC-054)와 목적·분모가 다른 별도 지표다(plan.md §M3 "D2 재감사 대응" 참조).
     */
    private static final BigDecimal WARN_LOWER_BOUND = new BigDecimal("0.67");

    private static final BigDecimal WARN_UPPER_BOUND = new BigDecimal("1.5");

    private final ShortSaleDomesticRepository shortSaleDomesticRepository;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final AcmlVolReconciliationGuard guard;
    private final ShortSaleCollectionService shortSaleCollectionService;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * Track 1(레거시·{@code acml_vol} 결측 가드) 정정 배치를 실행한다 (REQ-SSVC-031, -032).
     *
     * <p>T0R 완료 마커 게이트를 거치지 않는 호출자용 편의 오버로드 — {@link T0rGateState#inactive()}(구간 검사 생략)로 {@link
     * #correctLegacyBacklog(T0rGateState)}에 위임한다. 기존(M4) 단위 테스트 호환을 위해 시그니처를 보존한다.
     *
     * @return 이번 실행의 처리 결과 집계
     */
    public ShortSaleVolRateCorrectionResult correctLegacyBacklog() {
        return correctLegacyBacklog(T0rGateState.inactive());
    }

    /**
     * Track 1(레거시·{@code acml_vol} 결측 가드) 정정 배치를 실행한다 (REQ-SSVC-031, -032, REQ-T0R-043~045).
     *
     * <p>조회 조건에 더 이상 걸리는 행이 없을 때까지 유한 배치 단위로 순차 처리한다 — 단일 트랜잭션으로 전체를 처리하지 않는다. {@code gate}가
     * 활성(completed_at IS NULL)이면 ②단계(가드 판정 이후 원자적 쓰기 직전) 직전에 대상 행의 거래일이 닫히는 창 구간에 속하는지 검사해
     * defer한다(REQ-T0R-044) — defer된 행은 {@link ShortSaleVolRateCorrectionResult#skipped()}에 집계된다.
     *
     * @param gate {@code ShortSaleDomesticCorrectionScheduler}가 매 실행 시작 시 1회 조회해 전달하는 T0R 게이트 상태
     * @return 이번 실행의 처리 결과 집계
     */
    public ShortSaleVolRateCorrectionResult correctLegacyBacklog(T0rGateState gate) {
        LeaseSession session = keyLeaseRegistry.openSession();
        if (session.isEmpty()) {
            log.error("[vol-rate-correction][track1] 모든 키 죽음 — 이번 실행 skip");
            return new ShortSaleVolRateCorrectionResult(0, 0, 0);
        }

        int corrected = 0;
        int revisionSuspected = 0;
        int skipped = 0;
        long afterId = 0;
        Pageable page = PageRequest.of(0, TRACK1_BATCH_SIZE);

        List<ShortSaleDomestic> batch =
                shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(afterId, page);
        while (!batch.isEmpty()) {
            for (ShortSaleDomestic row : batch) {
                afterId = row.getId();
                Track1Outcome outcome = correctTrack1Row(session, row, gate);
                switch (outcome) {
                    case CORRECTED -> corrected++;
                    case REVISION_SUSPECTED -> revisionSuspected++;
                    case SKIPPED, DEFERRED -> skipped++;
                }
            }
            batch = shortSaleDomesticRepository.findTrack1LegacyBacklogBatch(afterId, page);
        }

        log.info(
                "[vol-rate-correction][track1] 실행 완료 —"
                        + " corrected={}, revisionSuspected={}, skipped={}",
                corrected,
                revisionSuspected,
                skipped);
        return new ShortSaleVolRateCorrectionResult(corrected, revisionSuspected, skipped);
    }

    /**
     * Track 2(상시 재계산 스윕) 정정 배치를 실행한다 (REQ-SSVC-034~038).
     *
     * <p>T0R 완료 마커 게이트를 거치지 않는 호출자용 편의 오버로드 — {@link T0rGateState#inactive()}(구간 검사 생략)로 {@link
     * #verifyRecentInserts(T0rGateState)}에 위임한다. 기존(M4) 단위 테스트 호환을 위해 시그니처를 보존한다.
     *
     * @return 이번 실행의 처리 결과 집계(revisionSuspected는 Track 2에 해당 없으므로 항상 0)
     */
    public ShortSaleVolRateCorrectionResult verifyRecentInserts() {
        return verifyRecentInserts(T0rGateState.inactive());
    }

    /**
     * Track 2(상시 재계산 스윕) 정정 배치를 실행한다 (REQ-SSVC-034~038, REQ-T0R-043~045).
     *
     * <p>KIS 재조회·가드 호출 없이 {@code daily_ohlcv} 조인 재계산만 수행한다(REQ-SSVC-038, -057). {@code gate}가 활성이면
     * recompute 호출 직전에 대상 행의 거래일이 닫히는 창 구간에 속하는지 검사해 defer한다(REQ-T0R-044).
     *
     * @param gate {@code ShortSaleDomesticCorrectionScheduler}가 매 실행 시작 시 1회 조회해 전달하는 T0R 게이트 상태
     * @return 이번 실행의 처리 결과 집계(revisionSuspected는 Track 2에 해당 없으므로 항상 0)
     */
    public ShortSaleVolRateCorrectionResult verifyRecentInserts(T0rGateState gate) {
        int corrected = 0;
        int skipped = 0;
        long afterId = 0;
        Pageable page = PageRequest.of(0, TRACK2_BATCH_SIZE);

        List<ShortSaleDomestic> batch =
                shortSaleDomesticRepository.findTrack2PendingVerificationBatch(afterId, page);
        while (!batch.isEmpty()) {
            for (ShortSaleDomestic row : batch) {
                afterId = row.getId();
                if (verifyTrack2Row(row, gate)) {
                    corrected++;
                } else {
                    skipped++;
                }
            }
            batch = shortSaleDomesticRepository.findTrack2PendingVerificationBatch(afterId, page);
        }

        log.info(
                "[vol-rate-correction][track2] 실행 완료 — corrected={}, skipped={}",
                corrected,
                skipped);
        return new ShortSaleVolRateCorrectionResult(corrected, 0, skipped);
    }

    /** Track 1 단일 행 처리 — TR04 재조회 → 가드 판정 → (T0R 게이트 통과 시) 재계산·원자적 UPDATE. */
    private Track1Outcome correctTrack1Row(
            LeaseSession session, ShortSaleDomestic row, T0rGateState gate) {
        String symbol = row.getStock().getSymbol();
        LocalDate tradeDate = row.getTradeDate();

        Optional<KisShortSaleResponse.ShortSaleRow> liveRow;
        try {
            liveRow = findLiveRow(session, symbol, tradeDate);
        } catch (KisRateLimitException | RestClientException e) {
            // REQ-KISGATE-022 계승: retryable 재시도 소진 → graceful skip(ShortSaleCollectionService와 동일
            // 패턴)
            log.warn(
                    "[vol-rate-correction][track1] TR04 재조회 실패(재시도 소진) — symbol={}, date={}, reason={}",
                    symbol,
                    tradeDate,
                    e.getMessage());
            return Track1Outcome.SKIPPED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "[vol-rate-correction][track1] 인터럽트 — symbol={}, date={} skip",
                    symbol,
                    tradeDate);
            return Track1Outcome.SKIPPED;
        } catch (NoHealthyKeyException e) {
            log.warn(
                    "[vol-rate-correction][track1] 건강 키 0개로 skip — symbol={}, date={}",
                    symbol,
                    tradeDate);
            return Track1Outcome.SKIPPED;
        } catch (KisTokenIssueException e) {
            log.warn(
                    "[vol-rate-correction][track1] 토큰 발급 실패로 skip — symbol={}, date={}, error={}",
                    symbol,
                    tradeDate,
                    e.getMessage());
            return Track1Outcome.SKIPPED;
        }

        if (liveRow.isEmpty()) {
            // EC-3 — 상장폐지 등으로 TR04 재조회가 빈 응답을 반환하는 경우. REVISION_SUSPECTED와 구분되는 별도 스킵.
            log.warn(
                    "[vol-rate-correction][track1] TR04 재조회 결과 없음(EC-3) — symbol={}, date={}",
                    symbol,
                    tradeDate);
            return Track1Outcome.SKIPPED;
        }

        long liveAcmlVol = parseLongOrZero(liveRow.get().acmlVol());
        long liveQty = parseLongOrZero(liveRow.get().sstsCntgQty());
        AcmlVolReconciliationResult reconciliation =
                guard.reconcile(
                        row.getShortSellVolRate(), row.getShortSellQty(), liveAcmlVol, liveQty);

        if (reconciliation.outcome() == AcmlVolReconciliationOutcome.REVISION_SUSPECTED) {
            log.warn(
                    "[vol-rate-correction][track1] REVISION_SUSPECTED — 정정 스킵"
                            + "(acml_vol·vol_rate_verified_at 미충전, 자동 재시도) — symbol={}, date={}",
                    symbol,
                    tradeDate);
            return Track1Outcome.REVISION_SUSPECTED;
        }

        // ②단계(가드 판정 이후 원자적 쓰기 직전) — T0R 완료 마커 게이트(REQ-T0R-044). gate가 활성이고 대상 거래일이
        // 닫히는 창 구간에 속하면 이번 실행에서 defer한다 — acml_vol·vol_rate_verified_at 둘 다 미기록.
        if (gate.shouldDefer(tradeDate)) {
            log.info(
                    "[vol-rate-correction][track1] T0R 완료 마커 게이트 defer(REQ-T0R-044) —"
                            + " symbol={}, date={}",
                    symbol,
                    tradeDate);
            return Track1Outcome.DEFERRED;
        }

        Optional<BigDecimal> recomputedRate = recomputeRate(row);
        if (recomputedRate.isEmpty()) {
            return Track1Outcome.SKIPPED;
        }

        warnIfOutOfBand(symbol, tradeDate, row.getShortSellVolRate(), recomputedRate.get());
        shortSaleDomesticRepository.updateTrack1Correction(
                row.getId(), reconciliation.acmlVol(), recomputedRate.get(), LocalDateTime.now());
        return Track1Outcome.CORRECTED;
    }

    /**
     * Track 2 단일 행 처리 — (T0R 게이트 통과 시) {@code daily_ohlcv} 조인 재계산 후 원자적 UPDATE(값 동일 여부와 무관하게 항상
     * 기록).
     */
    private boolean verifyTrack2Row(ShortSaleDomestic row, T0rGateState gate) {
        // recompute 호출 직전 — T0R 완료 마커 게이트(REQ-T0R-044). Track 1과 동일 근거.
        if (gate.shouldDefer(row.getTradeDate())) {
            log.info(
                    "[vol-rate-correction][track2] T0R 완료 마커 게이트 defer(REQ-T0R-044) —"
                            + " symbol={}, date={}",
                    row.getStock().getSymbol(),
                    row.getTradeDate());
            return false;
        }

        Optional<BigDecimal> recomputedRate = recomputeRate(row);
        if (recomputedRate.isEmpty()) {
            return false;
        }
        warnIfOutOfBand(
                row.getStock().getSymbol(),
                row.getTradeDate(),
                row.getShortSellVolRate(),
                recomputedRate.get());
        shortSaleDomesticRepository.updateTrack2Verification(
                row.getId(), recomputedRate.get(), LocalDateTime.now());
        return true;
    }

    /** REQ-SSVC-011 공식으로 {@code short_sell_vol_rate}를 재계산한다 — {@code acml_vol}을 입력으로 쓰지 않는다. */
    private Optional<BigDecimal> recomputeRate(ShortSaleDomestic row) {
        List<DailyOhlcv> matches =
                dailyOhlcvRepository.findByStockIdAndTradeDateIn(
                        row.getStock().getId(), List.of(row.getTradeDate()));
        if (matches.isEmpty()) {
            // EC-1 — 매칭되는 daily_ohlcv 행이 없는 방어적 케이스(이론상 0건 예상).
            log.warn(
                    "[vol-rate-correction] daily_ohlcv 매칭 행 없음(EC-1) — stockId={}, date={}",
                    row.getStock().getId(),
                    row.getTradeDate());
            return Optional.empty();
        }
        long volume = matches.getFirst().getVolume();
        if (volume == 0) {
            log.warn(
                    "[vol-rate-correction] daily_ohlcv.volume=0 — 재계산 분모 0, skip — stockId={}, date={}",
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

    /** REQ-SSVC-041 — 저장rate÷재계산rate 배율이 {@code [0.67, 1.5]} 밖이면 WARN(게이트 아님, 정정은 그대로 진행). */
    private void warnIfOutOfBand(
            String symbol, LocalDate tradeDate, BigDecimal storedRate, BigDecimal recomputedRate) {
        if (recomputedRate.compareTo(BigDecimal.ZERO) == 0) {
            // 반올림으로 재계산값이 0.00이 될 수 있다(qty≪volume) — 배율 분모가 0이라 WARN 판정을 생략한다.
            return;
        }
        BigDecimal ratio = storedRate.divide(recomputedRate, 10, RoundingMode.HALF_UP);
        if (ratio.compareTo(WARN_LOWER_BOUND) < 0 || ratio.compareTo(WARN_UPPER_BOUND) > 0) {
            log.warn(
                    "[vol-rate-correction] 배율 이상 관측(WARN, REQ-SSVC-041) — symbol={}, date={},"
                            + " storedRate={}, recomputedRate={}, ratio={}",
                    symbol,
                    tradeDate,
                    storedRate,
                    recomputedRate,
                    ratio);
        }
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

    /** Track 1 단일 행 처리 결과. */
    private enum Track1Outcome {
        CORRECTED,
        REVISION_SUSPECTED,
        SKIPPED,
        /** T0R 완료 마커 게이트로 defer됨(REQ-T0R-044) — 집계 시 {@code skipped} 버킷에 합산된다. */
        DEFERRED
    }
}
