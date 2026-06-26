package com.aaa.collector.news.overseas;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 해외 뉴스 제목 수집 서비스 (TR HHPSTH60100C1, SPEC-COLLECTOR-OVERSEAS-ETC-001).
 *
 * <p>T0 실측 확정(2026-06-24, isa 단일키): {@code outblock1}은 배열(페이지당 10건), 시간 역순(최신→과거) 정렬. 페이징 커서는
 * {@code CTS}가 아니라 직전 응답 마지막 행의 {@code data_dt}/{@code data_tm}이다 — 이를 다음 요청의 {@code
 * DATA_DT}/{@code DATA_TM}에 주입한다(REQ-OVE-043). {@code CTS}는 무효이므로 항상 공백 고정한다(REQ-OVE-041). 커서가
 * inclusive하여 직전 페이지 마지막 행이 다음 페이지 첫 행으로 1건 중복 반환되므로 {@code news_key} Set + 멱등 {@code INSERT
 * IGNORE}로 흡수한다(REQ-OVE-043a).
 *
 * <p><strong>정지조건(REQ-OVE-044a, T0 실측):</strong> (1) {@code outblock1}이 빈 배열, (2) 직전 페이지와 비교해 신규
 * {@code news_key}가 0건이거나 커서가 전진하지 않는 경우(전진 0), (3) MAX_PAGES 도달(REQ-OVE-044). 실측상 빈 응답에는 응답 헤더
 * {@code tr_cont=E}(End — 데이터 있으면 {@code F})가 동반되나, 재사용 게이트 {@link GuardedKisExecutor}는 파싱된 바디만
 * 반환하고 HTTP 응답 헤더를 노출하지 않는다(read-only 인프라). 따라서 {@code tr_cont=E}의 바디 수준 동반 신호인 <b>빈 {@code
 * outblock1}</b>을 정지 신호로 사용한다 — 두 신호는 T0 실측상 공기(共起)하므로 기능적으로 동치이며 게이트 수정이 불필요하다.
 *
 * <p>호출은 단일 보호 게이트 {@link GuardedKisExecutor}를 경유한다(REQ-OVE-010). collect() 한 번의 페이지 루프 전체 = 1
 * batch이므로 루프 전 {@link LeaseSession}을 1회 연다(REQ-OVE-011). 무과금 TR이지만 게이트 throttle을 공유한다(REQ-OVE-014
 * — 서비스 코드에 별도 sleep/세마포어 없음). {@link com.aaa.collector.news.NewsTitleCollectionService} 패턴 답습.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 해외 뉴스 제목 수집 진입점 — 게이트 경유 페이징·DATA_DT/DATA_TM 커서·news_key dedup·멱등 저장·독성 행 흡수
// 담당
// @MX:REASON: SPEC-COLLECTOR-OVERSEAS-ETC-001 REQ-OVE-010/011/040~047, REQ-KISGATE-001 —
// 게이트·레지스트리·리포지토리가
// 수렴하는 단일 수집 경계
// @MX:SPEC: SPEC-COLLECTOR-OVERSEAS-ETC-001
public class OverseasNewsTitleCollectionService {

    /** 최대 페이지 수 — 무한 루프 방지 안전 상한(REQ-OVE-044). */
    private static final int MAX_PAGES = 500;

    private static final String TR_ID = "HHPSTH60100C1";
    private static final String PATH = "/uapi/overseas-price/v1/quotations/news-title";

    /** 국가코드 — 미국 한정(REQ-OVE-041, RD-6). */
    private static final String NATION_CODE = "US";

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final OverseasNewsHeadlineRepository repository;
    private final OverseasNewsHeadlineInserter overseasNewsHeadlineInserter;

    /**
     * 해외 뉴스 제목 수집을 실행하고 집계 결과를 반환한다.
     *
     * <p>DATA_DT/DATA_TM 커서를 공백부터 시작(최신)하여 과거로 페이징하며, {@code news_key} Set으로 페이지 경계 중복을 흡수한다. 빈
     * 응답·전진 0·MAX_PAGES 중 하나에서 정지한다.
     *
     * @return attempted/succeeded/skipped 행 수 집계
     */
    public OverseasNewsCollectionResult collect() {
        // REQ-OVE-011: collect() 페이지 루프 전체 = 1 batch — 루프 전 per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();

        // REQ-OVE-012: 빈 스냅샷 = 전 키 사망 → 수집 0회, 전체 skip, ERROR + no fallback
        if (session.isEmpty()) {
            log.error("[overseas-news] 모든 키 죽음 — 수집 0회, 전체 skip");
            return new OverseasNewsCollectionResult(0, 0, 0);
        }

        Set<String> seenKeys = new HashSet<>();
        int attempted = 0;
        int succeeded = 0;
        int skipped = 0;

        // DATA_DT/DATA_TM 커서 — 공백부터 시작(최신). 직전 응답 마지막 행으로 갱신.
        String cursorDate = "";
        String cursorTime = "";
        int pageCount = 0;

        while (pageCount < MAX_PAGES) {
            pageCount++;

            KisOverseasNewsTitleResponse response = fetchPage(session, cursorDate, cursorTime);

            // REQ-OVE-044a (1): 빈 outblock1 = tr_cont=E 동반 신호 → 정지
            if (response.outblock1().isEmpty()) {
                log.info("[overseas-news] outblock1 빈 응답 — 페이징 종료 (page={})", pageCount);
                break;
            }

            PageOutcome outcome = processPage(response, seenKeys);
            attempted += outcome.attempted;
            succeeded += outcome.succeeded;
            skipped += outcome.skipped;

            // REQ-OVE-044a (2): 신규 news_key 0건(전진 0) → 정지(직전 페이지와 동일 데이터만 반환)
            if (outcome.newKeys == 0) {
                log.info("[overseas-news] 신규 news_key 0건 — 전진 0, 페이징 종료 (page={})", pageCount);
                break;
            }

            // 커서 전진 가드 — 마지막 행 data_dt/data_tm이 직전과 동일하면 정지(REQ-OVE-044a (2))
            if (cursorDate.equals(outcome.lastDataDt) && cursorTime.equals(outcome.lastDataTm)) {
                log.info("[overseas-news] 커서 미전진 — 페이징 종료 (page={})", pageCount);
                break;
            }
            cursorDate = outcome.lastDataDt;
            cursorTime = outcome.lastDataTm;
        }

        // REQ-OVE-044: 안전 상한 도달 경고
        if (pageCount >= MAX_PAGES) {
            log.warn("[overseas-news] 최대 페이지({}) 도달 — 강제 종료", MAX_PAGES);
        }

        OverseasNewsCollectionResult result =
                new OverseasNewsCollectionResult(attempted, succeeded, skipped);
        log.info(
                "[overseas-news] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                result.attempted(),
                result.succeeded(),
                result.skipped());
        return result;
    }

    /**
     * 게이트를 경유해 뉴스 제목 한 페이지를 조회한다(REQ-OVE-041: NATION_CD=US, CTS 공백 고정, 커서는 DATA_DT/DATA_TM).
     *
     * <p>인터럽트 수신 시 플래그를 복원한 뒤 {@link IllegalStateException}으로 전파한다.
     *
     * @param session collect() 작업 단위당 1회 연 per-batch 헬스 스냅샷 세션(REQ-OVE-011)
     * @param dataDt DATA_DT 커서(공백=최신)
     * @param dataTm DATA_TM 커서(공백=최신)
     */
    private KisOverseasNewsTitleResponse fetchPage(
            LeaseSession session, String dataDt, String dataTm) {
        try {
            return guardedKisExecutor.execute(
                    session,
                    uri ->
                            uri.path(PATH)
                                    .queryParam("INFO_GB", "")
                                    .queryParam("CLASS_CD", "")
                                    .queryParam("NATION_CD", NATION_CODE)
                                    .queryParam("EXCHANGE_CD", "")
                                    .queryParam("SYMB", "")
                                    .queryParam("DATA_DT", dataDt)
                                    .queryParam("DATA_TM", dataTm)
                                    .queryParam("CTS", "")
                                    .build(),
                    TR_ID,
                    KisOverseasNewsTitleResponse.class);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("overseas-news 수집 중 인터럽트", ie);
        }
    }

    /**
     * 한 페이지를 처리한다 — 행별 검증·dedup·멱등 저장, 마지막 행 커서 추출.
     *
     * <p>마지막 행의 {@code data_dt}/{@code data_tm}은 검증 통과 여부와 무관하게 응답 순서상 마지막 행(시간 역순 → 가장 과거)에서 추출해
     * 다음 커서로 쓴다(전진 보장).
     *
     * <p>REQ-INSERT-011: 유효 행 누적 후 {@code insertBatchIsolated}로 격리 삽입 — 독성 행 skip·잔여 행 계속.
     */
    private PageOutcome processPage(KisOverseasNewsTitleResponse response, Set<String> seenKeys) {
        int attempted = 0;
        int skipped = 0;
        int newKeys = 0;
        String lastDataDt = "";
        String lastDataTm = "";

        // REQ-INSERT-011: 유효 행 누적
        List<OverseasNewsHeadline> batch = new ArrayList<>();

        for (KisOverseasNewsTitleResponse.NewsRow row : response.outblock1()) {
            attempted++;

            // 커서 추출은 검증/dedup과 무관하게 응답상 마지막 행 값으로 항상 갱신(전진 보장)
            if (row.dataDt() != null && !row.dataDt().isBlank()) {
                lastDataDt = row.dataDt();
                lastDataTm = row.dataTm() != null ? row.dataTm() : "";
            }

            // REQ-OVE-045: news_key/data_dt null·공백 skip
            if (isInvalidRow(row)) {
                log.debug("[overseas-news] 검증 실패 (news_key/data_dt null) — skip");
                skipped++;
                continue;
            }

            String newsKey = row.newsKey();

            // REQ-OVE-043a: 페이지 경계 inclusive 1건 중복 → Set dedup
            if (!seenKeys.add(newsKey)) {
                skipped++;
                continue;
            }

            // REQ-OVE-042/060: 유효 행 누적
            OverseasNewsHeadline entity = mapToEntity(row);
            batch.add(entity);
            newKeys++;
        }

        // REQ-INSERT-011: 격리 삽입 — 독성 행 skip·잔여 행 계속·커넥션 중단 없음
        int failures = insertIsolated(batch);
        int succeeded = batch.size() - failures;
        skipped += failures;

        return new PageOutcome(attempted, succeeded, skipped, newKeys, lastDataDt, lastDataTm);
    }

    /** 필수 필드(news_key, data_dt) 누락 여부를 판정한다(REQ-OVE-045). */
    private boolean isInvalidRow(KisOverseasNewsTitleResponse.NewsRow row) {
        String newsKey = row.newsKey();
        return newsKey == null
                || newsKey.isBlank()
                || row.dataDt() == null
                || row.dataDt().isBlank();
    }

    /** 해외 뉴스 배치를 격리 삽입하고 실패 건수를 반환한다. */
    private int insertIsolated(List<OverseasNewsHeadline> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        AtomicInteger failures = new AtomicInteger();
        overseasNewsHeadlineInserter.insertBatchIsolated(
                batch,
                (entity, ex) -> {
                    log.warn(
                            "[overseas-news] 행 저장 실패 — skip (news_key={}, error={})",
                            entity.getNewsKey(),
                            ex.getMessage());
                    failures.incrementAndGet();
                });
        return failures.get();
    }

    /**
     * NewsRow → OverseasNewsHeadline 매핑(REQ-OVE-047).
     *
     * <p>{@code symb}/{@code symb_name}/{@code exchange_cd}가 빈 문자열이어도 그대로 보존한다(NULL 정규화 안 함).
     * {@code published_at}은 {@code data_dt}+{@code data_tm} 합성, 파싱 실패 시 null로 두고 행은 저장한다.
     *
     * <p>필수 필드({@code news_key}/{@code data_dt})는 {@link #processRow}에서 선검증되므로 항상 비-null 엔티티를 반환한다.
     */
    private OverseasNewsHeadline mapToEntity(KisOverseasNewsTitleResponse.NewsRow row) {
        LocalDateTime publishedAt = parsePublishedAt(row.dataDt(), row.dataTm());

        return OverseasNewsHeadline.builder()
                .newsKey(row.newsKey())
                .publishedAt(publishedAt)
                .infoGb(row.infoGb())
                .classCd(row.classCd())
                .className(row.className())
                .source(row.source())
                .nationCd(row.nationCd())
                .exchangeCd(row.exchangeCd())
                .symbol(row.symb())
                .symbolName(row.symbName())
                .title(row.title())
                .build();
    }

    /** data_dt(8) + data_tm(6) → LocalDateTime. 파싱 실패 시 null 반환(행은 저장). */
    @SuppressWarnings("PMD.GuardLogStatement")
    private LocalDateTime parsePublishedAt(String dataDt, String dataTm) {
        try {
            String tm = (dataTm != null && dataTm.length() == 6) ? dataTm : "000000";
            return LocalDateTime.parse(dataDt + tm, TS_FMT);
        } catch (DateTimeParseException e) {
            log.debug(
                    "[overseas-news] 일시 파싱 실패 — dataDt={}, dataTm={}, error={}",
                    dataDt,
                    dataTm,
                    e.getMessage());
            return null;
        }
    }

    /** 페이지 처리 결과 집계 (내부 전달용). */
    private record PageOutcome(
            int attempted,
            int succeeded,
            int skipped,
            int newKeys,
            String lastDataDt,
            String lastDataTm) {}
}
