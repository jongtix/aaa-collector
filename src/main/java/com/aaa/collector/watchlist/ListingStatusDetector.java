package com.aaa.collector.watchlist;

import com.aaa.collector.stock.enums.ListingStatus;
import java.time.LocalDate;
import java.util.function.Function;

// @MX:SPEC: SPEC-COLLECTOR-WLSYNC-008
/**
 * KIS 종목 기본정보 응답에서 상장폐지·거래정지 상태를 판정한다 (REQ-WLSYNC-142,143).
 *
 * <p>{@link StockInfoParser}에서 분리한 이유: 국내/해외 판정 로직을 파서 본체에 인라인하면 파서 클래스의 책임(자산유형·ETF메타·상장일 파싱 +
 * 상폐판정)이 과다해진다(PMD GodClass). 판정 자체는 순수 함수형 로직이라 독립 컴포넌트로 분리해 응집도를 높인다.
 */
final class ListingStatusDetector {

    private static final String FLAG_YES = "Y";
    private static final String OVERSEAS_NORMAL_DVSN_CODE = "01";

    private ListingStatusDetector() {}

    /**
     * 국내(CTPF1002R) 판정. 상폐 종목도 rt_cd=0 정상 응답으로 온다 — rt_cd가 아닌 {@code lstg_abol_dt} 채움 여부로 판정한다(실측
     * 2026-07-20).
     *
     * @param parseDate {@code StockInfoParser}의 sentinel 처리 파서(재사용 — "00000000"/공백은 null)
     */
    // @MX:NOTE: [AUTO] 상폐 종목도 rt_cd=0 정상 응답 — lstg_abol_dt 채움 여부로 판정, tr_stop_yn 단독 판정 금지
    // @MX:REASON: KIS CTPF1002R 실측(010620 HD현대미포, 2026-07-20) — rt_cd만으로는 상폐를 구분할 수 없음
    static Detection detectDomestic(
            String lstgAbolDt, String trStopYn, Function<String, LocalDate> parseDate) {
        LocalDate delistedAt = parseDate.apply(lstgAbolDt);
        if (delistedAt != null) {
            return new Detection(ListingStatus.DELISTED, delistedAt);
        }
        if (FLAG_YES.equals(trStopYn)) {
            return new Detection(ListingStatus.HALTED, null);
        }
        return new Detection(ListingStatus.NORMAL, null);
    }

    /**
     * 해외(CTPF1702R) 판정. 정상 케이스(N/01)만 실측 확인됐다 — 상폐/거래정지 분기는 명세 기반 가설이며, 해외 상폐일자 전용 필드가 미확보라
     * delistedAt은 채우지 않는다(§7 미해결 질문).
     */
    static Detection detectOverseas(String lstgAbolItemYn, String ovrsStckTrStopDvsnCd) {
        if (FLAG_YES.equals(lstgAbolItemYn)) {
            return new Detection(ListingStatus.DELISTED, null);
        }
        if (OVERSEAS_NORMAL_DVSN_CODE.equals(ovrsStckTrStopDvsnCd)) {
            return new Detection(ListingStatus.NORMAL, null);
        }
        return new Detection(ListingStatus.HALTED, null);
    }

    /** 판정 결과. {@code delistedAt}은 {@code status == DELISTED}이고 상폐일자가 확보된 경우에만 non-null. */
    record Detection(ListingStatus status, LocalDate delistedAt) {}
}
