package com.aaa.collector.macro;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.macro.enums.MacroSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 국내 채권금리종합 수집 서비스 (TR FHPST07020000).
 *
 * <p>단일 호출로 output2(국내 한국 채권금리 8종, bcdt_code Y01xx) 전부를 {@code MacroIndicator(source=KIS)}로 멱등
 * 저장한다. 호출은 단일 보호 게이트 {@link GuardedKisExecutor}를 경유한다(SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001)
 * — 기존 3-arg 맨몸 호출(throttle❌·재시도❌·isa 단일키)에서 throttle✅·재시도✅·멀티키 lease✅로 바뀐 의도된 동작 변경(패턴 A). 단발
 * collect() = 1 batch이므로 자체 {@link LeaseSession}을 1회 연다(REQ-KISGATE-006a). 소진 시 게이트가 예외를 전파한다(패턴 A
 * 종단 = 예외 전파, REQ-KISGATE-022).
 *
 * <p>T0 실측 확정(v0.5.0, MA-01 해소): output2=국내 채권금리(Y01xx), output1=해외(Y02xx). 파라미터 라벨("해외금리지표")과 무관.
 * 유효 8종: Y0112/Y0113/Y0114/Y0115/Y0116/Y0117/Y0198/Y0199.
 *
 * <p>malformed 선두 행 graceful skip(REQ-BATCH3-070/031a): output2 선두 Y0101·Y0103~Y0111은 KIS가 필드 시프트로
 * 반환({@code hts_kor_isnm}에 bcdt_code가 들어있음). 탐지 규칙: {@code hts_kor_isnm}이 {@code ^Y0\d{3}$} 매칭 OR
 * {@code bond_mnrt_prpr}이 비숫자.
 *
 * <p>stream:daily:complete 미발행(REQ-BATCH3-011). 백필 미수행(REQ-BATCH3-012). indicator_code =
 * "KIS_RATE_{bcdt_code}" (예: KIS_RATE_Y0117).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompInterestCollectionService {

    /** malformed 행 탐지: hts_kor_isnm이 Y0XXX 패턴이면 필드 시프트 행 (REQ-BATCH3-031a/070). */
    private static final Pattern MALFORMED_ISNM_PATTERN = Pattern.compile("^Y0\\d{3}$");

    /** indicator_code 접두사. */
    static final String INDICATOR_CODE_PREFIX = "KIS_RATE_";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TR_ID = "FHPST07020000";
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/comp-interest";

    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final MacroIndicatorRepository macroIndicatorRepository;

    /**
     * 국내 채권금리 수집을 실행하고 집계 결과를 반환한다.
     *
     * @return attempted/succeeded/skipped 행 수 집계
     */
    public MacroCollectionResult collect() {
        // REQ-KISGATE-006a: 단발 collect() = 1 batch — per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();
        KisCompInterestResponse response = fetch(session);

        // REQ-BATCH3-073: 빈 output2 → 0건 성공
        if (response.output2().isEmpty()) {
            log.info("[comp-interest] output2 빈 응답 — 0건 성공");
            return new MacroCollectionResult(0, 0, 0);
        }

        int attempted = 0;
        int succeeded = 0;
        int skipped = 0;

        for (KisCompInterestResponse.CompInterestRow row : response.output2()) {
            attempted++;
            if (saveIfValid(row)) {
                succeeded++;
            } else {
                skipped++;
            }
        }

        MacroCollectionResult result = new MacroCollectionResult(attempted, succeeded, skipped);
        log.info(
                "[comp-interest] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                result.attempted(),
                result.succeeded(),
                result.skipped());
        return result;
    }

    /**
     * 게이트를 경유해 채권금리 단발 조회를 수행한다(REQ-BATCH3-030: 파라미터 FIXED).
     *
     * <p>인터럽트 수신 시 플래그를 복원한 뒤 {@link IllegalStateException}으로 전파한다(패턴 A 종단 = 예외 전파).
     */
    private KisCompInterestResponse fetch(LeaseSession session) {
        try {
            return guardedKisExecutor.execute(
                    session,
                    uri ->
                            uri.path(PATH)
                                    .queryParam("FID_COND_MRKT_DIV_CODE", "I")
                                    .queryParam("FID_COND_SCR_DIV_CODE", "20702")
                                    .queryParam("FID_DIV_CLS_CODE", "1")
                                    .queryParam("FID_DIV_CLS_CODE1", "")
                                    .build(),
                    TR_ID,
                    KisCompInterestResponse.class);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("comp-interest 수집 중 인터럽트", ie);
        }
    }

    private boolean saveIfValid(KisCompInterestResponse.CompInterestRow row) {
        String bcdtCode = row.bcdtCode();
        String htsKorIsnm = row.htsKorIsnm();
        String bondMnrtPrpr = row.bondMnrtPrpr();
        String stckBsopDate = row.stckBsopDate();

        // REQ-BATCH3-070/031a: malformed 행 탐지 (필드 시프트)
        // 1) hts_kor_isnm이 ^Y0\d{3}$ 패턴 → 필드 시프트 행
        if (htsKorIsnm != null && MALFORMED_ISNM_PATTERN.matcher(htsKorIsnm).matches()) {
            log.debug(
                    "[comp-interest] malformed 행 skip (hts_kor_isnm=코드패턴) — bcdtCode={}, htsKorIsnm={}",
                    bcdtCode,
                    htsKorIsnm);
            return false;
        }

        // 2) bond_mnrt_prpr이 비숫자 → 필드 시프트 행
        if (!isNumeric(bondMnrtPrpr)) {
            log.debug(
                    "[comp-interest] malformed 행 skip (bond_mnrt_prpr 비숫자) — bcdtCode={}, value={}",
                    bcdtCode,
                    bondMnrtPrpr);
            return false;
        }

        // REQ-BATCH3-070: null 키 필드 skip
        if (bcdtCode == null || bcdtCode.isBlank()) {
            log.warn("[comp-interest] 검증 실패 (bcdtCode null) — skip");
            return false;
        }
        if (stckBsopDate == null || stckBsopDate.isBlank()) {
            log.warn("[comp-interest] 검증 실패 (stckBsopDate null) — bcdtCode={}", bcdtCode);
            return false;
        }

        try {
            BigDecimal value = new BigDecimal(bondMnrtPrpr);
            LocalDate tradeDate = LocalDate.parse(stckBsopDate, DATE_FMT);

            // REQ-BATCH3-031: indicator_code = KIS_RATE_{bcdt_code}, % 무정규화
            String indicatorCode = INDICATOR_CODE_PREFIX + bcdtCode;
            MacroIndicator entity =
                    MacroIndicator.builder()
                            .indicatorCode(indicatorCode)
                            .source(MacroSource.KIS)
                            .tradeDate(tradeDate)
                            .value(value)
                            .build();

            // REQ-BATCH3-032: uk_macro_indicators 멱등 저장
            macroIndicatorRepository.insertIgnoreDuplicate(entity);
            return true;
        } catch (NumberFormatException | DateTimeParseException e) {
            log.warn(
                    "[comp-interest] 파싱 실패 — bcdtCode={}, value={}, date={}, error={}",
                    bcdtCode,
                    bondMnrtPrpr,
                    stckBsopDate,
                    e.getMessage());
            return false;
        }
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
