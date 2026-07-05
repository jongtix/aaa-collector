package com.aaa.collector.warmstart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 워터마크 게이지 일 1회 자가치유 재동기화 (SPEC-OBSV-WATERMARK-001 REQ-WM-004).
 *
 * <p>{@code aaa.watermark.resync.enabled}가 {@code true}인 경우에만 활성화된다(기본값 {@code false}). 운영자가 수동으로
 * TRUNCATE/재구축한 뒤 DB {@code MAX(기준 날짜 컬럼)}이 현재 게이지보다 낮아진 상태를 자동으로 반영하기 위한 옵션이다(MI-03).
 *
 * <p>{@link WatermarkWarmStarter#resyncAll()}과 완전히 동일한 SELECT MAX 쿼리 집합을 재사용한다 — 부팅
 * warm-start(REQ-WM-003)와 이 스케줄러가 별도로 조회 로직을 중복 구현하지 않으며, 추가 DB 권한도 필요하지 않는다.
 */
// @MX:ANCHOR: [AUTO] watermark 일 1회 재동기화 진입점 — WatermarkWarmStarter.resyncAll() 재사용
// @MX:REASON: SPEC-OBSV-WATERMARK-001 REQ-WM-004 — 자가치유 재동기화와 부팅 warm-start가 동일 쿼리 집합 공유
// @MX:SPEC: SPEC-OBSV-WATERMARK-001
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aaa.watermark.resync.enabled", havingValue = "true")
public class WatermarkResyncScheduler {

    /**
     * 매일 03:10 KST — 다른 배치와 충돌하지 않는 새벽 시간대(REQ-WM-004). {@code MacroIndicatorBackfillScheduler} 기본
     * cron(03:00 KST, {@code aaa.macro.backfill.cron})과의 정면 충돌을 피하기 위해 10분 오프셋을 둔다.
     */
    static final String RESYNC_CRON = "0 10 3 * * *";

    private final WatermarkWarmStarter watermarkWarmStarter;

    /** DB {@code MAX(기준 날짜 컬럼)}으로 17 시계열 워터마크 게이지를 절대값 재동기화한다(REQ-WM-004). */
    @Scheduled(cron = RESYNC_CRON, zone = "Asia/Seoul")
    public void resync() {
        log.info("Watermark 일 1회 재동기화 시작 (SPEC-OBSV-WATERMARK-001 REQ-WM-004)");
        watermarkWarmStarter.resyncAll();
        log.info("Watermark 일 1회 재동기화 완료");
    }
}
