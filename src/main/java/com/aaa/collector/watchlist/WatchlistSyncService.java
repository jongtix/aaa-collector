package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisApiResponse;
import com.aaa.collector.kis.KisRateLimiter;
import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.StockAssetTypeClassifier;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.net.URI;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * KIS 관심종목을 {@code stocks} 테이블에 동기화하는 서비스.
 *
 * <p>SPEC-COLLECTOR-WLSYNC-006: ②단계(종목별 기본정보 조회)는 건강한 키 집합으로 5계좌 라운드로빈 분산하여 {@code
 * BatchRestExecutor} 멀티키 경로로 호출한다. ①단계(그룹/종목 목록 조회)는 isa 단일키 + 전역 {@code kisRateLimiter}를 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("PMD.CouplingBetweenObjects") // 동기화 오케스트레이터 특성상 다수 협력 객체 의존 불가피
public class WatchlistSyncService {

    private final KisRateLimiter kisRateLimiter;
    private final KisWatchlistClient kisWatchlistClient;
    private final KisStockInfoClient kisStockInfoClient;
    private final WatchlistWriter watchlistWriter;
    private final StockAssetTypeClassifier stockAssetTypeClassifier;
    private final HealthyKeySelector healthyKeySelector;
    private final BatchRestExecutor batchRestExecutor;

    /** KIS 관심종목 전체를 조회하여 {@code stocks} 테이블에 upsert한다. */
    public void sync() {
        List<KisGroupListResponse.Group> groups = kisWatchlistClient.fetchGroups();
        log.info("관심 그룹 조회 완료 — {}개 그룹", groups.size());

        AtomicInteger failedGroupCount = new AtomicInteger(0);
        List<KisStockListByGroupResponse.Stock> rawStocks =
                collectUniqueStocks(groups, failedGroupCount);
        log.info("중복 제거 후 종목 수: {}", rawStocks.size());

        List<ResolvedStock> resolved = resolveStocks(rawStocks);
        watchlistWriter.upsertAll(resolved, failedGroupCount.get());
    }

    private List<KisStockListByGroupResponse.Stock> collectUniqueStocks(
            List<KisGroupListResponse.Group> groups, AtomicInteger failedGroupCount) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<KisStockListByGroupResponse.Stock>>> futures =
                    groups.stream()
                            .map(group -> fetchStocksAsync(group, executor, failedGroupCount))
                            .toList();

            Map<String, KisStockListByGroupResponse.Stock> unique = new ConcurrentHashMap<>();
            futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(Collection::stream)
                    .forEach(s -> unique.putIfAbsent(s.jongCode() + ":" + s.exchCode(), s));
            return List.copyOf(unique.values());
        }
    }

    private CompletableFuture<List<KisStockListByGroupResponse.Stock>> fetchStocksAsync(
            KisGroupListResponse.Group group,
            ExecutorService executor,
            AtomicInteger failedGroupCount) {
        return CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                kisRateLimiter.consume();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                log.warn(
                                        "rate limit 대기 중 인터럽트 — group={}({})",
                                        group.interGrpName(),
                                        group.interGrpCode());
                                failedGroupCount.incrementAndGet();
                                return List.<KisStockListByGroupResponse.Stock>of();
                            }
                            // 세마포어 획득 완료 — 반드시 finally에서 반환해야 한다
                            try {
                                return kisWatchlistClient.fetchStocksByGroup(group.interGrpCode());
                            } finally {
                                kisRateLimiter.release();
                            }
                        },
                        executor)
                .exceptionally(
                        ex -> {
                            log.warn(
                                    "그룹 {}({}) 조회 실패, skip — {}",
                                    group.interGrpName(),
                                    group.interGrpCode(),
                                    ex.getMessage());
                            failedGroupCount.incrementAndGet();
                            return List.of();
                        });
    }

    /**
     * ②단계: 종목별 기본정보를 건강한 키 라운드로빈 멀티키 경로로 조회하여 {@link ResolvedStock} 목록을 산출한다
     * (REQ-WLSYNC-120,130~134).
     *
     * <p>분산 직전 {@link HealthyKeySelector}로 키별 병렬 헬스 사전점검을 수행하여 건강한 키 집합을 산출한다. 건강한 키가 0개이면 ②단계 분산을
     * graceful skip하고(경고 로깅, 예외 미전파) ①단계 결과 기반으로 ResolvedStock을 만든다(REQ-WLSYNC-134, D-3).
     */
    // @MX:ANCHOR: [AUTO] ②단계 멀티키 분산 진입점 — 건강 키 라운드로빈 + BatchRestExecutor 호출
    // @MX:REASON: SPEC-COLLECTOR-WLSYNC-006 REQ-WLSYNC-120,131,132,134 — 5키 분산·죽은 키 제외·graceful
    // skip
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-006
    private List<ResolvedStock> resolveStocks(List<KisStockListByGroupResponse.Stock> rawStocks) {
        List<KisAccountCredential> healthyKeys = healthyKeySelector.selectHealthy();
        if (healthyKeys.isEmpty()) {
            log.warn("②단계 건강한 키 0개 — 멀티키 분산 graceful skip (REQ-WLSYNC-134)");
            return List.of();
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ResolveResult>> futures =
                    IntStream.range(0, rawStocks.size())
                            .mapToObj(
                                    i ->
                                            resolveAsync(
                                                    rawStocks.get(i),
                                                    healthyKeys.get(i % healthyKeys.size()),
                                                    executor))
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
        Market market = KisMarketResolver.resolve(stock.fidMrktClsCode(), stock.exchCode());
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
