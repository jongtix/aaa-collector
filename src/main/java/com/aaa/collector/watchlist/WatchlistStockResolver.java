package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisApiResponse;
import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.StockAssetTypeClassifier;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.net.URI;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * ②단계 종목 해석 조율자 — 종목별 기본정보를 건강 키 라운드로빈 멀티키 경로로 조회하여 {@link ResolvedStock} 목록을 산출한다
 * (WatchlistSyncService에서 추출, SPEC-COLLECTOR-WLSYNC-007 행위 보존).
 *
 * <p>SPEC-COLLECTOR-WLSYNC-006: 건강한 키 집합으로 5계좌 라운드로빈 분산하여 {@code BatchRestExecutor} 멀티키 경로로 호출한다. 키
 * 분산은 {@link HealthyKeyRoundRobinDistributor}(KEYDIST-001, 일봉 경로와 동일 패턴)에 위임한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistStockResolver {

    private final KisStockInfoClient kisStockInfoClient;
    private final StockAssetTypeClassifier stockAssetTypeClassifier;
    private final HealthyKeyRoundRobinDistributor distributor;
    private final BatchRestExecutor batchRestExecutor;

    /**
     * ②단계: 종목별 기본정보를 건강한 키 라운드로빈 멀티키 경로로 조회하여 {@link ResolvedStock} 목록을 산출한다
     * (REQ-WLSYNC-120,130~134).
     *
     * <p>입력 종목이 비어있으면 분산 없이 빈 목록을 반환한다(무로그, 레거시 보존). 분산은 {@link HealthyKeyRoundRobinDistributor}에
     * 위임한다. 건강한 키가 0개이면 distributor가 빈 맵을 반환하며, 이 경우 ②단계 분산을 graceful skip하고(경고 로깅, 예외 미전파) 빈 목록을
     * 반환한다(REQ-WLSYNC-134, D-3).
     */
    // @MX:ANCHOR: [AUTO] ②단계 멀티키 분산 진입점 — 건강 키 라운드로빈(distributor 위임) + BatchRestExecutor 호출
    // @MX:REASON: SPEC-COLLECTOR-WLSYNC-006 REQ-WLSYNC-120,131,132,134 — 5키 분산·죽은 키 제외·graceful
    // skip
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-006, SPEC-COLLECTOR-WLSYNC-007
    public List<ResolvedStock> resolve(List<KisStockListByGroupResponse.Stock> rawStocks) {
        // 빈 입력(빈 워치리스트 또는 ①단계 그룹조회 전체 실패) early guard:
        // 레거시는 건강 키가 있어도 rawStocks가 비면 IntStream.range(0,0)으로 아무 로그 없이 빈 목록을 반환했다.
        // distributor.distribute()는 빈 키와 빈 아이템을 모두 빈 맵으로 반환하므로, 이 가드가 없으면 빈 입력에도
        // "건강한 키 0개" WARN이 오인 발생한다. 이 가드로 레거시의 무로그 빈 입력 경로를 보존한다.
        if (rawStocks.isEmpty()) {
            return List.of();
        }

        Map<KisAccountCredential, List<KisStockListByGroupResponse.Stock>> allocation =
                distributor.distribute(rawStocks);
        if (allocation.isEmpty()) {
            log.warn("②단계 건강한 키 0개 — 멀티키 분산 graceful skip (REQ-WLSYNC-134)");
            return List.of();
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ResolveResult>> futures =
                    allocation.entrySet().stream()
                            .flatMap(
                                    entry ->
                                            entry.getValue().stream()
                                                    .map(
                                                            stock ->
                                                                    resolveAsync(
                                                                            stock,
                                                                            entry.getKey(),
                                                                            executor)))
                            .toList();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .peek(
                            r -> {
                                if (log.isDebugEnabled() && r instanceof ResolveResult.Skipped s) {
                                    log.debug("종목 skip — {}", s.reason());
                                }
                            })
                    .filter(r -> r instanceof ResolveResult.Success)
                    .map(r -> ((ResolveResult.Success) r).stock())
                    .toList();
        }
    }

    private CompletableFuture<ResolveResult> resolveAsync(
            KisStockListByGroupResponse.Stock stock,
            KisAccountCredential credential,
            ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> resolveOne(stock, credential), executor);
    }

    private ResolveResult resolveOne(
            KisStockListByGroupResponse.Stock stock, KisAccountCredential credential) {
        Market market =
                KisMarketResolver.resolve(
                        stock.fidMrktClsCode(), stock.exchCode(), stock.jongCode());
        if (market == null) {
            return new ResolveResult.Skipped(
                    "알 수 없는 마켓: fid=" + stock.fidMrktClsCode() + ", exch=" + stock.exchCode());
        }
        StockInfo info = fetchStockInfo(stock, market, credential);
        return new ResolveResult.Success(
                new ResolvedStock(stock.jongCode(), stock.htsKorIsnm(), market, info));
    }

    private StockInfo fetchStockInfo(
            KisStockListByGroupResponse.Stock stock,
            Market market,
            KisAccountCredential credential) {
        Optional<AssetType> staticAssetType =
                stockAssetTypeClassifier.classify(market, stock.jongCode());
        if (staticAssetType.isPresent()) {
            return new StockInfo(staticAssetType.get(), null, null);
        }
        try {
            return kisStockInfoClient.fetchStockInfo(
                    stock.jongCode(), market, multikeyFetcher(credential, stock.jongCode()));
        } catch (DateTimeParseException ex) {
            log.warn(
                    "날짜 파싱 실패로 종목 skip — symbol={}, market={}: {}",
                    stock.jongCode(),
                    market,
                    ex.getMessage());
            return null;
        } catch (KisTokenIssueException
                | KisApiBusinessException
                | IllegalStateException
                | RestClientException ex) {
            log.warn(
                    "종목 기본정보 조회 실패로 skip — symbol={}, market={}: {}",
                    stock.jongCode(),
                    market,
                    ex.getMessage());
            return null;
        }
    }

    /**
     * ②단계 멀티키 호출 전략: 지정된 키 자격증명으로 {@link BatchRestExecutor}를 경유한다(per-key rate limiter + EGW00201
     * 재시도 + graceful skip).
     */
    private KisStockInfoClient.StockInfoFetcher multikeyFetcher(
            KisAccountCredential credential, String symbol) {
        return new KisStockInfoClient.StockInfoFetcher() {
            @Override
            public <T extends KisApiResponse> BatchResult<T> fetch(
                    Function<UriBuilder, URI> uriCustomizer, String trId, Class<T> responseType) {
                return batchRestExecutor.execute(
                        credential, uriCustomizer, trId, responseType, symbol);
            }
        };
    }
}
