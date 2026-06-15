package com.aaa.collector.watchlist;

import com.aaa.collector.stock.enums.Market;
import lombok.extern.slf4j.Slf4j;

// @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
/**
 * 관심종목조회 {@code fid_mrkt_cls_code}를 1차 라우팅 시장으로 변환한다.
 *
 * <p>국내({@code J}/{@code UN}) 종목의 KOSPI/KOSDAQ 확정은 이 클래스 범위 밖이다. {@code fid_mrkt_cls_code}는 실제
 * 상장시장과 무관하므로(NAS 실측 2026-06-15: KOSPI 종목 NAVER류=UN, KOSDAQ 종목 에코프로비엠=J) KOSPI/KOSDAQ 단정에 사용하지
 * 않는다(REQ-STOCKMETA-020, REQ-STOCKMETA-022). 국내 KOSPI/KOSDAQ 최종 확정은 CTPF1002R 응답의 {@code
 * mket_id_cd}({@link StockInfoParser#parseDomestic})에서 수행한다(REQ-STOCKMETA-001).
 *
 * <p>{@code J}/{@code UN}은 모두 동일 국내 엔드포인트({@code fetchDomesticInfo})로 라우팅되므로 coarse 값(KOSPI)을 반환해도
 * 무해하며, 실제 시장은 {@code mket_id_cd}로 덮어쓰인다.
 */
@Slf4j
final class KisMarketResolver {

    private KisMarketResolver() {}

    /**
     * {@code fid_mrkt_cls_code}를 1차 라우팅 시장으로 변환한다.
     *
     * <p>국내({@code J}/{@code UN}): {@link Market#KOSPI}를 coarse 라우팅 값으로 반환한다. 실제 KOSPI/KOSDAQ 확정은
     * {@code parseDomestic}에서 {@code mket_id_cd}로 수행되므로 여기서 단정하지 않는다 (REQ-STOCKMETA-020,022).
     * {@code UN→KOSDAQ} 매핑 제거 — ADR-021 결정 1 무효화(SPEC §7).
     */
    static Market resolve(String fidMrktClsCode, String exchCode, String symbol) {
        return switch (fidMrktClsCode) {
            // J와 UN 모두 국내 엔드포인트로 라우팅. KOSPI/KOSDAQ 단정은 parseDomestic(mket_id_cd)에서 수행.
            // UN→KOSDAQ 매핑 제거(REQ-STOCKMETA-020): fid_mrkt_cls_code는 실제 상장시장과 무관(NAS 실측
            // 2026-06-15).
            case "J", "UN" -> Market.KOSPI; // coarse 라우팅 값 — mket_id_cd로 덮어쓰인다
            case "U" -> Market.KRX;
            case "N" -> Market.US;
            case "FS" ->
                    switch (exchCode) {
                        case "NAS" -> Market.NASDAQ;
                        case "NYS" -> Market.NYSE;
                        case "AMS" -> Market.AMEX;
                        default -> {
                            log.warn(
                                    "알 수 없는 해외 거래소 코드 — symbol={} 종목 드롭: exchCode={}",
                                    symbol,
                                    exchCode);
                            yield null;
                        }
                    };
            default -> {
                log.warn(
                        "알 수 없는 시장 코드 — symbol={} 종목 드롭: fidMrktClsCode={}",
                        symbol,
                        fidMrktClsCode);
                yield null;
            }
        };
    }
}
