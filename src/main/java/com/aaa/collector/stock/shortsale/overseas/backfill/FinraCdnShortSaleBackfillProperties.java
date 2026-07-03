package com.aaa.collector.stock.shortsale.overseas.backfill;

import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 미국 공매도 Daily 과거 백필(FINRA CDN) 전용 설정 프로퍼티 (SPEC-COLLECTOR-BACKFILL-008 T1).
 *
 * <p>{@code aaa.shortsale-overseas.backfill.*} 프리픽스로 바인딩된다. 기존 KIS 종목×테이블 백필({@code
 * aaa.backfill.*})과 구조적으로 무관한 독립 프로퍼티다 — {@code max-windows-per-target}(160)을 상속하지 않는다
 * (REQ-BACKFILL-122a).
 *
 * <p>기본값 근거:
 *
 * <ul>
 *   <li>{@code cron}: 05:00 KST — 기존 백필(02:00/03:00/04:30 KST)에 이어지는 다음 빈 슬롯(plan.md OQ1).
 *   <li>{@code zone}: {@code Asia/Seoul} — CDN 파일 가용성은 zone 무관하고 과거 방향 순회라 레이스가 없어, 운영자 모니터링 편의를 위해
 *       KST로 통일(plan.md OQ1).
 *   <li>{@code floorDate}: 2009-08-03 — FINRA CDN 공매도 Daily 파일 데이터 하한(실측).
 *   <li>{@code perCronDateCap}: 1000(calendar days/cron) — 크론 1회 실행을 20~35분대로 바운드하며 전체 소급 범위를 약 7회
 *       크론(≈1주) 내 완료(plan.md OQ3). KIS 유산 {@code max-windows-per-target}(160)은 상속하지 않는다
 *       (REQ-BACKFILL-122a).
 *   <li>{@code facilityCodes}: {@code [FNSQ, FNYX, FNQC, FORF, FNRA]} — 시설 시대(2009~2018-07-31, FNQC
 *       부재·FNRA 존재)와 CNMS 오버랩일(2018-08-01+, FNQC 존재) 전 시대의 합집합. 그날 HTTP 200인 파일만 합산한다(plan.md OQ5).
 *   <li>{@code cdnBaseUrl}: {@code https://cdn.finra.org} — 무인증 정적 파일 CDN.
 *   <li>{@code maxFileSizeBytes}: 20,000,000(20MB) — 최대 규모 통합 CNMS 파일도 평문·수천 종목 규모라 여유 있게 잡은
 *       상한(코드리뷰 Fix 2).
 * </ul>
 */
// @MX:NOTE: [AUTO] 전용 캡 — KIS max-windows-per-target(160) 미상속, 무인증 CDN이라 캡을 크게 잡음(1000일/크론);
// facilityCodes는 시설 시대·CNMS 오버랩일 전 시대 합집합(5원소), 그날 200인 파일만 합산
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-008
@ConfigurationProperties(prefix = "aaa.shortsale-overseas.backfill")
public class FinraCdnShortSaleBackfillProperties {

    /** cron 표현식 (기본: 05:00 KST 매일). fixedDelay 금지 — Virtual Threads 버그(CLAUDE.md). */
    private String cron = "0 0 5 * * *";

    /** cron zone (기본: Asia/Seoul). */
    private String zone = "Asia/Seoul";

    /** FINRA CDN 공매도 Daily 데이터 하한(전역 앵커 종료 지점, REQ-BACKFILL-114). */
    private LocalDate floorDate = LocalDate.of(2009, 8, 3);

    /** 크론 1회 처리 최대 거래일 수(calendar days). KIS {@code max-windows-per-target}은 상속하지 않는다. */
    private int perCronDateCap = 1000;

    /** 시도할 FINRA CDN 시설 코드 집합(전 시대 합집합) — 200인 파일만 합산한다. */
    private List<String> facilityCodes = List.of("FNSQ", "FNYX", "FNQC", "FORF", "FNRA");

    /** FINRA CDN 베이스 URL(무인증). */
    private String cdnBaseUrl = "https://cdn.finra.org";

    /**
     * CDN 파일 1건당 허용 최대 응답 크기(bytes, 기본 20MB). 가장 큰 시대의 통합 CNMS 파일도 평문·수천 종목 규모라 여유 있게 잡은 상한이며, 이를
     * 초과하면 비정상 응답으로 간주해 {@link FinraCdnFetchResult.AbsenceReason#TRANSIENT_ERROR}로 흡수한다(코드리뷰 Fix
     * 2).
     */
    private int maxFileSizeBytes = 20_000_000;

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public LocalDate getFloorDate() {
        return floorDate;
    }

    public void setFloorDate(LocalDate floorDate) {
        this.floorDate = floorDate;
    }

    public int getPerCronDateCap() {
        return perCronDateCap;
    }

    public void setPerCronDateCap(int perCronDateCap) {
        this.perCronDateCap = perCronDateCap;
    }

    public List<String> getFacilityCodes() {
        return List.copyOf(facilityCodes);
    }

    public void setFacilityCodes(List<String> facilityCodes) {
        this.facilityCodes = List.copyOf(facilityCodes);
    }

    public String getCdnBaseUrl() {
        return cdnBaseUrl;
    }

    public void setCdnBaseUrl(String cdnBaseUrl) {
        this.cdnBaseUrl = cdnBaseUrl;
    }

    public int getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(int maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }
}
