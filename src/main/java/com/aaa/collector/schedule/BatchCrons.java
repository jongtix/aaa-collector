package com.aaa.collector.schedule;

/**
 * 8개 배치 단위의 cron·zone 공유 상수 (SPEC-COLLECTOR-CATCHUP-001 T1).
 *
 * <p>단일 소스 원칙: {@code @Scheduled} 어노테이션과 {@link com.aaa.collector.schedule.catchup.CatchUpRunner}
 * 레지스트리가 동일 값을 참조하여 드리프트를 원천 제거한다(AC-14).
 *
 * <p>인스턴스화 불필요 — 모든 상수는 {@code public static final String}.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals") // zone 문자열 "Asia/Seoul"은 단일 소스 원칙 구현을 위해 의도적으로 반복
public final class BatchCrons {

    // ─── domestic-daily-chain ────────────────────────────────────────────────
    /** 국내 일봉 배치 cron — 평일 16:00 KST. */
    public static final String DOMESTIC_DAILY_CHAIN_CRON = "0 0 16 * * MON-FRI";

    /** 국내 일봉 배치 zone. */
    public static final String DOMESTIC_DAILY_CHAIN_ZONE = "Asia/Seoul";

    // ─── overseas-daily ──────────────────────────────────────────────────────
    /** 미국 일봉 배치 cron — 평일 16:30 ET. */
    public static final String OVERSEAS_DAILY_CRON = "0 30 16 * * MON-FRI";

    /** 미국 일봉 배치 zone. */
    public static final String OVERSEAS_DAILY_ZONE = "America/New_York";

    // ─── overseas-shortsale ──────────────────────────────────────────────────
    /** 미국 공매도 배치 cron — 평일 10:00 ET. */
    public static final String OVERSEAS_SHORTSALE_CRON = "0 0 10 * * MON-FRI";

    /** 미국 공매도 배치 zone. */
    public static final String OVERSEAS_SHORTSALE_ZONE = "America/New_York";

    // ─── domestic-invest-opinion ─────────────────────────────────────────────
    /** 투자의견 배치 cron — 평일 18:00 KST. */
    public static final String DOMESTIC_INVEST_OPINION_CRON = "0 0 18 * * MON-FRI";

    /** 투자의견 배치 zone. */
    public static final String DOMESTIC_INVEST_OPINION_ZONE = "Asia/Seoul";

    // ─── domestic-financial-ratio ────────────────────────────────────────────
    /** 재무비율 배치 cron — 토요일 08:00 KST (주1회). */
    public static final String DOMESTIC_FINANCIAL_RATIO_CRON = "0 0 8 * * SAT";

    /** 재무비율 배치 zone. */
    public static final String DOMESTIC_FINANCIAL_RATIO_ZONE = "Asia/Seoul";

    // ─── macro-external ──────────────────────────────────────────────────────
    /** 외부 거시경제 지표 배치 cron — 평일 19:00 KST. */
    public static final String MACRO_EXTERNAL_CRON = "0 0 19 * * MON-FRI";

    /** 외부 거시경제 지표 배치 zone. */
    public static final String MACRO_EXTERNAL_ZONE = "Asia/Seoul";

    // ─── market-indicators ───────────────────────────────────────────────────
    /** 시장지표 묶음 배치 cron — 평일 17:05 KST(aaa-infra#105, KOREAEXIM 장주기 재시도 여유 확보). */
    public static final String MARKET_INDICATORS_CRON = "0 5 17 * * MON-FRI";

    /** 시장지표 묶음 배치 zone. */
    public static final String MARKET_INDICATORS_ZONE = "Asia/Seoul";

    // ─── usdkrw-daily ────────────────────────────────────────────────────────
    // @MX:NOTE: [AUTO] USDKRW 전용 cron — D-1 파생 근거 및 통합 배치 분리 사유
    // @MX:REASON: [AUTO] SPEC-COLLECTOR-MARKETIND-004 REQ-002 — 10:30 KST 시점에는 KOREAEXIM(11:00 확정
    // 이전 최종 게시)과 Yahoo 폴백 모두 D-1(전 거래일) 값이 이미 확정되어 있어 미확정 당일 부분바 오염(aaa-infra#104)이
    // 구조적으로 불가능하다. market-indicators(17:05, 6종)와 분리해 통합 배치·다른 6종 스케줄을 절대 건드리지 않는다.
    // @MX:NOTE: [AUTO] cron MON-FRI→TUE-SAT 전환 사유(SPEC-COLLECTOR-MARKETIND-004 후속) — 월요일 실행의
    // target=D-1=일요일은 KOREAEXIM·Yahoo 모두 데이터가 없어 MarketIndicatorSourceChain이 recordSuccess를
    // 남기지 않는다. 실행 앵커(batch_last_load)만 전진하고 소스 성공 앵커는 정지해 vmalert
    // MarketIndicatorPrimaryStale이 매주 월요일 오발한다. TUE-SAT로 전환하면 D-1이 항상 영업일(월~금)이 되어 이 헛발이
    // 구조적으로 소멸하고, 금요일 데이터를 토요일 10:30에 라이브로 추가 수집한다(기존에는 02:00 백필 갭워크 단일 의존).
    /** USDKRW 전용 배치 cron — 화~토 10:30 KST(SPEC-COLLECTOR-MARKETIND-004, D-1 파생). */
    public static final String USDKRW_DAILY_CRON = "0 30 10 * * TUE-SAT";

    /** USDKRW 전용 배치 zone. */
    public static final String USDKRW_DAILY_ZONE = "Asia/Seoul";

    // ─── domestic-etf-representative ─────────────────────────────────────────
    /** ETF 대표 종목 재계산 배치 cron — 매주 월요일 07:50 KST. */
    public static final String DOMESTIC_ETF_REPRESENTATIVE_CRON = "0 50 7 * * MON";

    /** ETF 대표 종목 재계산 배치 zone. */
    public static final String DOMESTIC_ETF_REPRESENTATIVE_ZONE = "Asia/Seoul";

    // ─── overseas-split ──────────────────────────────────────────────────────
    /** 해외 액면분할·병합 수집 배치 cron — 평일 17:00 ET. */
    public static final String OVERSEAS_SPLIT_CRON = "0 0 17 * * MON-FRI";

    /** 해외 액면분할·병합 수집 배치 zone. */
    public static final String OVERSEAS_SPLIT_ZONE = "America/New_York";

    // ─── overseas-rights ─────────────────────────────────────────────────────
    /** 해외 현금배당 수집 배치 cron — 평일 17:00 ET. */
    public static final String OVERSEAS_RIGHTS_CRON = "0 0 17 * * MON-FRI";

    /** 해외 현금배당 수집 배치 zone. */
    public static final String OVERSEAS_RIGHTS_ZONE = "America/New_York";

    // ─── corp-code ───────────────────────────────────────────────────────────
    /** DART corp_code 매핑 갱신 배치 cron — 매일 07:30 KST. */
    public static final String CORP_CODE_CRON = "0 30 7 * * *";

    /** DART corp_code 매핑 갱신 배치 zone. */
    public static final String CORP_CODE_ZONE = "Asia/Seoul";

    // ─── dart-backfill ───────────────────────────────────────────────────────
    /** DART 공시 백필 배치 cron — 매일 04:30 KST. */
    public static final String DART_BACKFILL_CRON = "0 30 4 * * *";

    /** DART 공시 백필 배치 zone. */
    public static final String DART_BACKFILL_ZONE = "Asia/Seoul";

    // ─── domestic-news ───────────────────────────────────────────────────────
    /** 국내 뉴스 제목 증분 수집 배치 cron — 평일 장중 10분 간격(09~15시 KST). */
    public static final String DOMESTIC_NEWS_CRON = "0 0/10 9-15 * * MON-FRI";

    /** 국내 뉴스 제목 증분 수집 배치 zone. */
    public static final String DOMESTIC_NEWS_ZONE = "Asia/Seoul";

    // ─── overseas-news ───────────────────────────────────────────────────────
    /** 해외 뉴스 제목 수집 배치 cron — 미국 장중 10분 간격(09~16시 ET). */
    public static final String OVERSEAS_NEWS_CRON = "0 0/10 9-16 * * MON-FRI";

    /** 해외 뉴스 제목 수집 배치 zone. */
    public static final String OVERSEAS_NEWS_ZONE = "America/New_York";

    // ─── watchlist-sync-krx ──────────────────────────────────────────────────
    /** KRX 관심종목 동기화+등급 분류 배치 cron — 매일 08:20 KST. */
    public static final String WATCHLIST_SYNC_KRX_CRON = "0 20 8 * * *";

    /** KRX 관심종목 동기화+등급 분류 배치 zone. */
    public static final String WATCHLIST_SYNC_KRX_ZONE = "Asia/Seoul";

    // ─── watchlist-sync-us ───────────────────────────────────────────────────
    /** US 관심종목 동기화+등급 분류 배치 cron — 매일 08:50 ET. */
    public static final String WATCHLIST_SYNC_US_CRON = "0 50 8 * * *";

    /** US 관심종목 동기화+등급 분류 배치 zone. */
    public static final String WATCHLIST_SYNC_US_ZONE = "America/New_York";

    private BatchCrons() {}
}
