package com.aaa.collector.schedule;

import java.time.Duration;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 예상 실행(expected-run) 모델 편입 배치 20종의 단일 소스 레지스트리 (SPEC-COLLECTOR-EXPECTED-RUN-001 REQ-XR-002, DP-3).
 *
 * <p>{@link com.aaa.collector.schedule.catchup.CatchUpRunner#buildRegistry()}의 "단일 소스" 조립 스타일을 미러한다
 * — 스케줄러 {@code @Scheduled}와 드리프트가 없도록 cron·zone 값은 공유 상수({@link BatchCrons})를 참조한다. 이 레지스트리는
 * {@code expected_run}·{@code run_margin}·{@code enrolled_total} 게이지 산출의 유일 원천이 될 예정이나(T-003), 본
 * 단계는 <b>선언만</b> 한다.
 *
 * <p><b>Spring-managed 이유</b>: REQ-XR-002는 property-placeholder로 오버라이드 가능한 배치 ({@code
 * extended-hours-pre}/{@code -after})에 대해 레지스트리가 스케줄러와 <b>동일한</b> property를 읽을 것을 요구한다. 따라서 순수
 * static-상수 홀더가 아니라 생성자로 {@link Environment}를 주입받는 빈으로 구성한다. Environment는 필드로 보관하지 않고 생성자에서
 * property를 읽어 불변 리스트를 조립한 뒤 폐기한다.
 */
@Component
public class BatchRunRegistry {

    /**
     * {@code ExtendedHoursScheduler.collectPre()}의 {@code @Scheduled} placeholder와 동일
     * 키(REQ-XR-002).
     */
    static final String EXT_HOURS_PRE_CRON_KEY = "aaa.extended-hours.pre-cron";

    /** {@code ExtendedHoursScheduler.collectPre()}의 placeholder default 리터럴과 동일. */
    static final String EXT_HOURS_PRE_CRON_DEFAULT = "0 0 10 * * MON-FRI";

    /**
     * {@code ExtendedHoursScheduler.collectAfter()}의 {@code @Scheduled} placeholder와 동일
     * 키(REQ-XR-002).
     */
    static final String EXT_HOURS_AFTER_CRON_KEY = "aaa.extended-hours.after-cron";

    /** {@code ExtendedHoursScheduler.collectAfter()}의 placeholder default 리터럴과 동일. */
    static final String EXT_HOURS_AFTER_CRON_DEFAULT = "0 30 20 * * MON-FRI";

    /** extended-hours pre/after zone — 스케줄러 {@code @Scheduled(zone=...)}와 동일. */
    private static final String EXT_HOURS_ZONE = "America/New_York";

    private final List<BatchRunEntry> entryList;

    public BatchRunRegistry(Environment environment) {
        this.entryList = buildRegistry(environment);
    }

    /**
     * 편입 배치 20종 레지스트리 엔트리 목록.
     *
     * @return 불변 목록
     */
    public List<BatchRunEntry> entries() {
        return List.copyOf(entryList);
    }

    /**
     * 20개 엔트리를 조립한다(§3.1 표, 마진은 §9 시작 표).
     *
     * <p>extended-hours pre/after cron은 리터럴 하드코딩 대신 스케줄러와 동일한 property placeholder를 읽어 취한다
     * (REQ-XR-002) — 운영자가 배포 config에서 오버라이드해도 레지스트리와 실제 스케줄이 자동 정합한다.
     */
    private static List<BatchRunEntry> buildRegistry(Environment environment) {
        String preCron =
                environment.getProperty(EXT_HOURS_PRE_CRON_KEY, EXT_HOURS_PRE_CRON_DEFAULT);
        String afterCron =
                environment.getProperty(EXT_HOURS_AFTER_CRON_KEY, EXT_HOURS_AFTER_CRON_DEFAULT);

        return List.of(
                new BatchRunEntry(
                        "overseas-daily",
                        BatchCrons.OVERSEAS_DAILY_CRON,
                        BatchCrons.OVERSEAS_DAILY_ZONE,
                        Duration.ofHours(5).toSeconds()),
                // 국내 일봉 체인(16:00 KST)에 연쇄되는 3라벨 — 동일 cron/zone 공유(§3.1 비고).
                new BatchRunEntry(
                        "domestic-supply-investor",
                        BatchCrons.DOMESTIC_DAILY_CHAIN_CRON,
                        BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE,
                        Duration.ofHours(4).toSeconds()),
                new BatchRunEntry(
                        "domestic-supply-credit-balance",
                        BatchCrons.DOMESTIC_DAILY_CHAIN_CRON,
                        BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE,
                        Duration.ofHours(4).toSeconds()),
                new BatchRunEntry(
                        "domestic-supply-short-sale",
                        BatchCrons.DOMESTIC_DAILY_CHAIN_CRON,
                        BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE,
                        Duration.ofHours(4).toSeconds()),
                new BatchRunEntry(
                        "overseas-shortsale-daily",
                        BatchCrons.OVERSEAS_SHORTSALE_CRON,
                        BatchCrons.OVERSEAS_SHORTSALE_ZONE,
                        Duration.ofHours(6).toSeconds()),
                new BatchRunEntry(
                        "overseas-shortsale-interest",
                        BatchCrons.OVERSEAS_SHORTSALE_CRON,
                        BatchCrons.OVERSEAS_SHORTSALE_ZONE,
                        Duration.ofHours(6).toSeconds()),
                new BatchRunEntry(
                        "domestic-invest-opinion",
                        BatchCrons.DOMESTIC_INVEST_OPINION_CRON,
                        BatchCrons.DOMESTIC_INVEST_OPINION_ZONE,
                        Duration.ofHours(3).toSeconds()),
                new BatchRunEntry(
                        "domestic-financial-ratio",
                        BatchCrons.DOMESTIC_FINANCIAL_RATIO_CRON,
                        BatchCrons.DOMESTIC_FINANCIAL_RATIO_ZONE,
                        Duration.ofHours(8).toSeconds()),
                new BatchRunEntry(
                        "macro-external",
                        BatchCrons.MACRO_EXTERNAL_CRON,
                        BatchCrons.MACRO_EXTERNAL_ZONE,
                        Duration.ofHours(3).toSeconds()),
                new BatchRunEntry(
                        "domestic-news",
                        BatchCrons.DOMESTIC_NEWS_CRON,
                        BatchCrons.DOMESTIC_NEWS_ZONE,
                        Duration.ofHours(2).toSeconds()),
                new BatchRunEntry(
                        "domestic-etf-representative",
                        BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_CRON,
                        BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_ZONE,
                        Duration.ofHours(4).toSeconds()),
                new BatchRunEntry(
                        "watchlist-sync-krx",
                        BatchCrons.WATCHLIST_SYNC_KRX_CRON,
                        BatchCrons.WATCHLIST_SYNC_KRX_ZONE,
                        Duration.ofHours(2).toSeconds()),
                new BatchRunEntry(
                        "watchlist-sync-us",
                        BatchCrons.WATCHLIST_SYNC_US_CRON,
                        BatchCrons.WATCHLIST_SYNC_US_ZONE,
                        Duration.ofHours(4).toSeconds()),
                new BatchRunEntry(
                        "overseas-news",
                        BatchCrons.OVERSEAS_NEWS_CRON,
                        BatchCrons.OVERSEAS_NEWS_ZONE,
                        Duration.ofHours(5).toSeconds()),
                new BatchRunEntry(
                        "overseas-rights",
                        BatchCrons.OVERSEAS_RIGHTS_CRON,
                        BatchCrons.OVERSEAS_RIGHTS_ZONE,
                        Duration.ofHours(5).toSeconds()),
                new BatchRunEntry(
                        "corp-code",
                        BatchCrons.CORP_CODE_CRON,
                        BatchCrons.CORP_CODE_ZONE,
                        Duration.ofHours(3).toSeconds()),
                new BatchRunEntry(
                        "dart-backfill",
                        BatchCrons.DART_BACKFILL_CRON,
                        BatchCrons.DART_BACKFILL_ZONE,
                        Duration.ofHours(5).toSeconds()),
                // property placeholder — 스케줄러와 동일 키를 읽어 은묵 드리프트 방지(REQ-XR-002).
                new BatchRunEntry(
                        "extended-hours-pre",
                        preCron,
                        EXT_HOURS_ZONE,
                        Duration.ofHours(6).toSeconds()),
                new BatchRunEntry(
                        "extended-hours-after",
                        afterCron,
                        EXT_HOURS_ZONE,
                        Duration.ofHours(4).toSeconds()),
                new BatchRunEntry(
                        "overseas-split",
                        BatchCrons.OVERSEAS_SPLIT_CRON,
                        BatchCrons.OVERSEAS_SPLIT_ZONE,
                        Duration.ofHours(5).toSeconds()));
    }
}
