package com.aaa.collector.macro;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.macro.enums.MacroSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 국내 증시자금종합 수집 서비스 (TR FHKST649100C0).
 *
 * <p>단일 호출로 일자별 한 행의 9개 금액 지표를 각각 별도 {@code MacroIndicator(source=KIS)}로 분해·멱등 저장한다
 * (REQ-BATCH3-040/041). 호출은 단일 보호 게이트 {@link GuardedKisExecutor}를 경유한다(SPEC-COLLECTOR-KISGATE-001
 * REQ-KISGATE-001) — 기존 3-arg 맨몸 호출(throttle❌·재시도❌·isa 단일키)에서 throttle✅·재시도✅·멀티키 lease✅로 바뀐 의도된 동작
 * 변경(패턴 A). 단발 collect() = 1 batch이므로 자체 {@link LeaseSession}을 1회 연다(REQ-KISGATE-006a). 소진 시 게이트가
 * 예외를 전파한다(패턴 A 종단 = 예외 전파, REQ-KISGATE-022).
 *
 * <p>원(KRW) 정규화: API 단위 "억원"(10^8 원)을 {@link #EOK_WON_TO_WON} 상수로 곱해 저장한다 (REQ-BATCH3-042). {@code
 * MacroIndicator.value}는 DECIMAL(24,8) (V21 마이그레이션 적용 후) 이므로 예탁금 ~10^14 원을 수용한다.
 *
 * <p>indicator_code 매핑은 §6.3 {@code MKTFUND_*} 규칙을 따른다. stream:daily:complete 미발행 (REQ-BATCH3-011).
 * 백필 미수행 (REQ-BATCH3-012). 기준일 1건 수집 (REQ-BATCH3-044).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketFundsCollectionService {

    /**
     * 억원 → 원 변환 상수 (10^8).
     *
     * <p>REQ-BATCH3-042: API 단위 "억원"을 원으로 정규화하기 위해 ×10^8 한다. 예탁금 최대 ~수십조원 = 10^13~10^14 은
     * DECIMAL(24,8)(V21) 정수부 16자리로 수용 가능.
     */
    static final long EOK_WON_TO_WON = 100_000_000L;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TR_ID = "FHKST649100C0";
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/mktfunds";

    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final MacroIndicatorRepository macroIndicatorRepository;

    /**
     * 증시자금 수집을 실행하고 집계 결과를 반환한다.
     *
     * @param baseDate 기준일 (yyyyMMdd 형식, D-1 일자 등 호출자가 지정)
     * @return attempted/succeeded/skipped 행 수 집계 (9개 지표 × output 행 수)
     */
    public MacroCollectionResult collect(String baseDate) {
        // REQ-KISGATE-006a: 단발 collect() = 1 batch — per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();
        KisMarketFundsResponse response = fetch(session, baseDate);

        // REQ-BATCH3-073: 빈 output → 0건 성공
        if (response.output().isEmpty()) {
            log.info("[mktfunds] output 빈 응답 — 0건 성공 (baseDate={})", baseDate);
            return new MacroCollectionResult(0, 0, 0);
        }

        int attempted = 0;
        int succeeded = 0;
        int skipped = 0;

        for (KisMarketFundsResponse.MarketFundsRow row : response.output()) {
            // 각 행에서 9개 지표를 분해하여 저장
            String bsopDate = row.bsopDate();
            if (bsopDate == null || bsopDate.isBlank()) {
                log.warn("[mktfunds] 검증 실패 (bsopDate null) — 행 전체 skip");
                attempted += 9;
                skipped += 9;
                continue;
            }

            LocalDate tradeDate;
            try {
                tradeDate = LocalDate.parse(bsopDate, DATE_FMT);
            } catch (DateTimeParseException e) {
                log.warn("[mktfunds] bsopDate 파싱 실패 — {}, error={}", bsopDate, e.getMessage());
                attempted += 9;
                skipped += 9;
                continue;
            }

            // REQ-BATCH3-041/042: 9개 금액 지표 분해 저장
            attempted += 9;
            int rowSucceeded = 0;
            rowSucceeded += saveIndicator("MKTFUND_CUST_DEPOSIT", row.custDpmnAmt(), tradeDate);
            rowSucceeded += saveIndicator("MKTFUND_CREDIT_LOAN", row.crdtLoanRmnd(), tradeDate);
            rowSucceeded += saveIndicator("MKTFUND_MMF", row.mmfAmt(), tradeDate);
            rowSucceeded += saveIndicator("MKTFUND_UNCOLLECTED", row.unclAmt(), tradeDate);
            rowSucceeded += saveIndicator("MKTFUND_FUTURES_DEPOSIT", row.futsTfamAmt(), tradeDate);
            rowSucceeded += saveIndicator("MKTFUND_EQUITY_TYPE", row.sttpAmt(), tradeDate);
            rowSucceeded += saveIndicator("MKTFUND_MIXED_TYPE", row.mxtpAmt(), tradeDate);
            rowSucceeded += saveIndicator("MKTFUND_BOND_TYPE", row.bntpAmt(), tradeDate);
            rowSucceeded += saveIndicator("MKTFUND_SECURED_LOAN", row.secuLendAmt(), tradeDate);

            succeeded += rowSucceeded;
            skipped += (9 - rowSucceeded);
        }

        MacroCollectionResult result = new MacroCollectionResult(attempted, succeeded, skipped);
        log.info(
                "[mktfunds] 수집 완료 — attempted={}, succeeded={}, skipped={} (baseDate={})",
                result.attempted(),
                result.succeeded(),
                result.skipped(),
                baseDate);
        return result;
    }

    /**
     * 게이트를 경유해 증시자금 단발 조회를 수행한다(REQ-BATCH3-040: FID_INPUT_DATE_1={기준일}).
     *
     * <p>인터럽트 수신 시 플래그를 복원한 뒤 {@link IllegalStateException}으로 전파한다(패턴 A 종단 = 예외 전파).
     */
    private KisMarketFundsResponse fetch(LeaseSession session, String baseDate) {
        try {
            return guardedKisExecutor.execute(
                    session,
                    uri -> uri.path(PATH).queryParam("FID_INPUT_DATE_1", baseDate).build(),
                    TR_ID,
                    KisMarketFundsResponse.class);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("mktfunds 수집 중 인터럽트", ie);
        }
    }

    /**
     * 단일 지표를 원 정규화 후 멱등 저장한다.
     *
     * @return 저장 성공 1, skip 0
     */
    private int saveIndicator(String indicatorCode, String rawValue, LocalDate tradeDate) {
        // REQ-BATCH3-070: null·비숫자 skip
        if (rawValue == null || rawValue.isBlank()) {
            log.debug("[mktfunds] 지표 skip (null 값) — indicatorCode={}", indicatorCode);
            return 0;
        }

        BigDecimal parsedAmt;
        try {
            parsedAmt = new BigDecimal(rawValue);
        } catch (NumberFormatException e) {
            log.debug(
                    "[mktfunds] 지표 skip (파싱 실패) — indicatorCode={}, value={}",
                    indicatorCode,
                    rawValue);
            return 0;
        }

        // REQ-BATCH3-042: 억원 → 원 정규화 (×EOK_WON_TO_WON)
        BigDecimal wonValue = parsedAmt.multiply(BigDecimal.valueOf(EOK_WON_TO_WON));

        MacroIndicator entity =
                MacroIndicator.builder()
                        .indicatorCode(indicatorCode)
                        .source(MacroSource.KIS)
                        .tradeDate(tradeDate)
                        .value(wonValue)
                        .build();

        // REQ-BATCH3-043: uk_macro_indicators 멱등 저장
        macroIndicatorRepository.insertIgnoreDuplicate(entity);
        return 1;
    }
}
