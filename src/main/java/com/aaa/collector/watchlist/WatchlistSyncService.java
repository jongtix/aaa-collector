package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisRateLimiter;
import com.aaa.collector.stock.StockAssetTypeClassifier;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/** KIS 관심종목을 {@code stocks} 테이블에 동기화하는 서비스. */
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
                                log.warn("rate limit 대기 중 인터럽트 — group={}", group.interGrpCode());
                                failedGroupCount.incrementAndGet();
                                return List.<KisStockListByGroupResponse.Stock>of();
                            }
                            return kisWatchlistClient.fetchStocksByGroup(group.interGrpCode());
                        },
                        executor)
                .exceptionally(
                        ex -> {
                            log.warn(
                                    "그룹 {} 조회 실패, skip — {}",
                                    group.interGrpCode(),
                                    ex.getMessage());
                            failedGroupCount.incrementAndGet();
                            return List.of();
                        });
    }

    private List<ResolvedStock> resolveStocks(List<KisStockListByGroupResponse.Stock> rawStocks) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ResolveResult>> futures =
                    rawStocks.stream().map(stock -> resolveAsync(stock, executor)).toList();
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
            KisStockListByGroupResponse.Stock stock, ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> resolveOne(stock), executor);
    }

    private ResolveResult resolveOne(KisStockListByGroupResponse.Stock stock) {
        Market market = KisMarketResolver.resolve(stock.fidMrktClsCode(), stock.exchCode());
        if (market == null) {
            return new ResolveResult.Skipped(
                    "알 수 없는 마켓: fid=" + stock.fidMrktClsCode() + ", exch=" + stock.exchCode());
        }
        StockInfo info = fetchStockInfo(stock, market);
        return new ResolveResult.Success(
                new ResolvedStock(stock.jongCode(), stock.htsKorIsnm(), market, info));
    }

    private StockInfo fetchStockInfo(KisStockListByGroupResponse.Stock stock, Market market) {
        Optional<AssetType> staticAssetType =
                stockAssetTypeClassifier.classify(market, stock.jongCode());
        if (staticAssetType.isPresent()) {
            return new StockInfo(staticAssetType.get(), null, null);
        }
        try {
            kisRateLimiter.consume();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("rate limit 대기 중 인터럽트 — symbol={}", stock.jongCode());
            return null;
        }
        // 세마포어 획득 완료 — 반드시 finally에서 반환해야 함
        try {
            return kisStockInfoClient.fetchStockInfo(stock.jongCode(), market);
        } catch (DateTimeParseException ex) {
            log.warn(
                    "날짜 파싱 실패로 종목 skip — symbol={}, market={}: {}",
                    stock.jongCode(),
                    market,
                    ex.getMessage());
            return null;
        } catch (KisApiBusinessException | IllegalStateException | RestClientException ex) {
            log.warn(
                    "종목 기본정보 조회 실패 — symbol={}, market={}: {}",
                    stock.jongCode(),
                    market,
                    ex.getMessage());
            return null;
        } finally {
            kisRateLimiter.release();
        }
    }
}
