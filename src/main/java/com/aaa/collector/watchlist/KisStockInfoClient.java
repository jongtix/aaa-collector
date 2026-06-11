package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiResponse;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.stock.enums.Market;
import java.net.URI;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriBuilder;

/**
 * KIS 종목 기본정보 API 클라이언트.
 *
 * <p>SPEC-COLLECTOR-WLSYNC-006: ②단계 멀티키 전환에 따라 단일키 직접 호출 책임을 제거하고, URI 빌드 + 응답 파싱 위임만 담당한다. 실제 HTTP
 * 호출(멀티키 + per-key rate limiter + EGW00201 재시도)은 호출부({@code WatchlistSyncService})가 {@link
 * StockInfoFetcher}로 주입하는 {@code BatchRestExecutor} 경유로 수행되며, 응답 파싱은 {@link StockInfoParser}가
 * 담당한다(C2 (b)안).
 */
@Component
@RequiredArgsConstructor
public class KisStockInfoClient {

    private static final String TR_ID_DOMESTIC = "CTPF1002R";
    private static final String TR_ID_OVERSEAS = "CTPF1702R";

    private final StockInfoParser stockInfoParser;

    /**
     * 종목 기본정보 REST 호출 전략. URI 커스터마이저 + TR ID + 응답 타입을 받아 {@link BatchResult}를 반환한다.
     *
     * <p>호출부가 {@code BatchRestExecutor.execute(credential, ...)}를 백킹하여 멀티키 경로(per-key rate limiter
     * + EGW00201 재시도 + graceful skip)를 적용한다.
     */
    @FunctionalInterface
    public interface StockInfoFetcher {
        <T extends KisApiResponse> BatchResult<T> fetch(
                Function<UriBuilder, URI> uriCustomizer, String trId, Class<T> responseType);
    }

    /**
     * 시장에 따라 국내/해외 종목 기본정보 API URI를 빌드하고, 주입된 {@link StockInfoFetcher}로 호출한 뒤 응답을 {@link
     * StockInfoParser}로 {@link StockInfo}에 파싱한다.
     *
     * <p>EGW00201 재시도 소진 등으로 {@link BatchResult}가 skip이면 {@code null}을 반환한다(graceful skip). 비즈니스
     * 오류({@link com.aaa.collector.kis.KisApiBusinessException}) 및 영구 오류는 즉시 전파되며 호출자({@code
     * WatchlistSyncService})에서 null로 처리된다.
     *
     * @param symbol 종목코드
     * @param market 시장
     * @param fetcher 멀티키 REST 호출 전략 (호출부가 credential을 바인딩하여 주입)
     * @return 종목 기본정보 (skip 시 {@code null})
     */
    // @MX:ANCHOR: [AUTO] ②단계 종목 기본정보 조회 진입점 — URI 빌드 + 응답 파싱 위임, 멀티키 호출은 fetcher 주입
    // @MX:REASON: SPEC-COLLECTOR-WLSYNC-006 REQ-WLSYNC-120 — ②단계 멀티키 전환((b)안), 단일키 경로 제거
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-006
    public StockInfo fetchStockInfo(String symbol, Market market, StockInfoFetcher fetcher) {
        return switch (market) {
            case KOSPI, KOSDAQ -> fetchDomesticInfo(symbol, market, fetcher);
            case NYSE, NASDAQ, AMEX -> fetchOverseasInfo(symbol, market, fetcher);
            default -> throw new IllegalArgumentException("종목 기본정보 조회를 지원하지 않는 시장: " + market);
        };
    }

    private StockInfo fetchDomesticInfo(String symbol, Market market, StockInfoFetcher fetcher) {
        BatchResult<KisDomesticStockInfoResponse> result =
                fetcher.fetch(
                        uri ->
                                uri.path("/uapi/domestic-stock/v1/quotations/search-stock-info")
                                        .queryParam("PRDT_TYPE_CD", "300")
                                        .queryParam("PDNO", symbol)
                                        .build(),
                        TR_ID_DOMESTIC,
                        KisDomesticStockInfoResponse.class);
        if (!result.isSuccess()) {
            return null;
        }
        return stockInfoParser.parseDomestic(result.getValue().orElseThrow().output(), market);
    }

    private StockInfo fetchOverseasInfo(String symbol, Market market, StockInfoFetcher fetcher) {
        String prdtTypeCd =
                switch (market) {
                    case NASDAQ -> "512";
                    case NYSE -> "513";
                    case AMEX -> "529";
                    default -> throw new IllegalArgumentException("지원하지 않는 해외 시장: " + market);
                };

        BatchResult<KisOverseasStockInfoResponse> result =
                fetcher.fetch(
                        uri ->
                                uri.path("/uapi/overseas-price/v1/quotations/search-info")
                                        .queryParam("PRDT_TYPE_CD", prdtTypeCd)
                                        .queryParam("PDNO", symbol)
                                        .build(),
                        TR_ID_OVERSEAS,
                        KisOverseasStockInfoResponse.class);
        if (!result.isSuccess()) {
            return null;
        }
        return stockInfoParser.parseOverseas(result.getValue().orElseThrow().output());
    }
}
