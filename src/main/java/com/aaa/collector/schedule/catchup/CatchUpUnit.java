package com.aaa.collector.schedule.catchup;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * catch-up 레지스트리의 단위 항목 (SPEC-COLLECTOR-CATCHUP-001 T4).
 *
 * @param name 배치 단위명 (로깅용 식별자)
 * @param cron Spring cron 표현식 (6필드)
 * @param zone 타임존 문자열
 * @param freshness freshness 판정 방식 ({@link Freshness#INSTANT} 또는 {@link Freshness#DATE})
 * @param lastLoadSuppliers lastLoad 공급자 목록 — chain 단위는 여러 개, 단일 단위는 1개
 * @param trigger 배치 재실행 진입점
 */
public record CatchUpUnit(
        String name,
        String cron,
        String zone,
        Freshness freshness,
        List<Supplier<Optional<LocalDateTime>>> lastLoadSuppliers,
        Runnable trigger) {}
