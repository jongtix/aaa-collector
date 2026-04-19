package com.aaa.collector.watchlist;

sealed interface ResolveResult permits ResolveResult.Success, ResolveResult.Skipped {
    record Success(ResolvedStock stock) implements ResolveResult {}

    record Skipped(String reason) implements ResolveResult {}
}
