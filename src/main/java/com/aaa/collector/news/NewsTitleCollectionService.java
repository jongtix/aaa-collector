package com.aaa.collector.news;

import com.aaa.collector.kis.KisApiExecutor;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 뉴스 제목 수집 서비스 (TR FHKST01011800).
 *
 * <p>T0 실측 확정: {@code output}은 배열(페이지당 40건), {@code cntt_usiq_srno} 내림차순(최신 우선). SRNO 커서는
 * 포함적(inclusive) — page2.first == page1.last이며 중복 경계 행은 {@code uk_news_headlines_serial} 멱등이 흡수한다.
 *
 * <p>증분 수집(REQ-BATCH3-063): blank SRNO(최신)부터 fetch → 저장된 max serial_no 도달 시 정지. 페이지 종료 =
 * count&lt;40 또는 경계 행만 반환.
 *
 * <p>전체 시황/공시 저장(종목 한정 아님), iscd1~5 보존(REQ-BATCH3-061). stream:daily:complete 미발행(REQ-BATCH3-011).
 * 백필 미수행(REQ-BATCH3-012).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsTitleCollectionService {

    /** 페이지당 최대 행 수 — 종료 조건 판단 기준 (T0 실측). */
    static final int PAGE_SIZE = 40;

    /** 최대 페이지 수 — 무한 루프 방지 안전 상한. */
    private static final int MAX_PAGES = 500;

    private static final String TR_ID = "FHKST01011800";
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/news-title";

    private final KisApiExecutor kisApiExecutor;
    private final NewsHeadlineRepository newsHeadlineRepository;

    /**
     * 뉴스 제목 증분 수집을 실행하고 집계 결과를 반환한다.
     *
     * <p>blank SRNO(최신)부터 시작하여 저장된 max serial_no에 도달하거나 페이지가 종료되면 정지한다.
     *
     * @return attempted/succeeded/skipped 행 수 집계
     */
    public NewsCollectionResult collect() {
        // REQ-BATCH3-063: 저장된 최신 serial_no 조회 (없으면 null → 전체 수집)
        String storedMaxSerialNo = newsHeadlineRepository.findMaxSerialNo();

        int attempted = 0;
        int succeeded = 0;
        int skipped = 0;

        String srno = "";
        int pageCount = 0;
        boolean reachedStoredMax = false;

        while (pageCount < MAX_PAGES && !reachedStoredMax) {
            pageCount++;

            KisNewsTitleResponse response = fetchPage(srno);

            // REQ-BATCH3-073: 빈 output → 0건 성공, 페이지 종료
            if (response.output().isEmpty()) {
                log.info("[news-title] output 빈 응답 — 페이지 종료 (page={})", pageCount);
                break;
            }

            PageResult pageResult = processPage(response, storedMaxSerialNo);
            attempted += pageResult.attempted;
            succeeded += pageResult.succeeded;
            skipped += pageResult.skipped;
            reachedStoredMax = pageResult.reachedStoredMax;

            if (shouldStopPaging(response, pageResult, pageCount)) {
                break;
            }
            srno = pageResult.minSrno != null ? pageResult.minSrno : srno;
        }

        if (pageCount >= MAX_PAGES) {
            log.warn("[news-title] 최대 페이지({}) 도달 — 강제 종료", MAX_PAGES);
        }

        NewsCollectionResult result = new NewsCollectionResult(attempted, succeeded, skipped);
        log.info(
                "[news-title] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                result.attempted(),
                result.succeeded(),
                result.skipped());
        return result;
    }

    private KisNewsTitleResponse fetchPage(String srno) {
        // REQ-BATCH3-060: FID_INPUT_ISCD=공백, FID_INPUT_SRNO={커서}, 나머지 공백 필수
        return kisApiExecutor.executeGet(
                uri ->
                        uri.path(PATH)
                                .queryParam("FID_NEWS_OFER_ENTP_CODE", "")
                                .queryParam("FID_COND_MRKT_CLS_CODE", "")
                                .queryParam("FID_INPUT_ISCD", "")
                                .queryParam("FID_TITL_CNTT", "")
                                .queryParam("FID_INPUT_DATE_1", "")
                                .queryParam("FID_INPUT_HOUR_1", "")
                                .queryParam("FID_RANK_SORT_CLS_CODE", "")
                                .queryParam("FID_INPUT_SRNO", srno)
                                .build(),
                TR_ID,
                KisNewsTitleResponse.class);
    }

    private PageResult processPage(KisNewsTitleResponse response, String storedMaxSerialNo) {
        int attempted = 0;
        int succeeded = 0;
        int skipped = 0;
        boolean reachedStoredMax = false;
        String minSrno = null;

        for (KisNewsTitleResponse.NewsTitleRow row : response.output()) {
            attempted++;
            RowOutcome outcome = processRow(row, storedMaxSerialNo, minSrno);
            if (outcome.reachedStoredMax) {
                reachedStoredMax = true;
            }
            if (outcome.minSrno != null) {
                minSrno = outcome.minSrno;
            }
            succeeded += outcome.succeeded;
            skipped += outcome.skipped;
        }

        return new PageResult(attempted, succeeded, skipped, reachedStoredMax, minSrno);
    }

    /**
     * 단일 행을 처리하고 결과를 반환한다.
     *
     * <p>커서/minSrno 집계는 항상 실행되며 저장 실패(DataAccessException) 여부와 무관하다. (W1 영구 정체 방지)
     */
    private RowOutcome processRow(
            KisNewsTitleResponse.NewsTitleRow row,
            String storedMaxSerialNo,
            String currentMinSrno) {
        String srnoVal = row.cnttUsiqSrno();

        // REQ-BATCH3-070: null serial_no skip
        if (srnoVal == null || srnoVal.isBlank()) {
            log.debug("[news-title] 검증 실패 (serial_no null) — skip");
            return RowOutcome.skipped(false, currentMinSrno);
        }

        // REQ-BATCH3-063: 저장된 max serial_no 도달 시 정지
        boolean reachedStoredMax =
                storedMaxSerialNo != null && srnoVal.compareTo(storedMaxSerialNo) <= 0;

        // 현재 페이지의 최소 srno 추적 (inclusive SRNO 커서용)
        String newMinSrno =
                (currentMinSrno == null || srnoVal.compareTo(currentMinSrno) < 0)
                        ? srnoVal
                        : currentMinSrno;

        NewsHeadline entity = mapToEntity(row);
        if (entity == null) {
            return RowOutcome.skipped(reachedStoredMax, newMinSrno);
        }

        // REQ-BATCH3-062: uk_news_headlines_serial 멱등 저장
        if (tryInsert(entity, srnoVal)) {
            return RowOutcome.succeeded(reachedStoredMax, newMinSrno);
        }
        return RowOutcome.skipped(reachedStoredMax, newMinSrno);
    }

    /** 행 처리 결과 (내부 전달용). */
    private record RowOutcome(
            int succeeded, int skipped, boolean reachedStoredMax, String minSrno) {
        static RowOutcome succeeded(boolean reachedStoredMax, String minSrno) {
            return new RowOutcome(1, 0, reachedStoredMax, minSrno);
        }

        static RowOutcome skipped(boolean reachedStoredMax, String minSrno) {
            return new RowOutcome(0, 1, reachedStoredMax, minSrno);
        }
    }

    /**
     * 단건 삽입을 시도하고 성공 여부를 반환한다.
     *
     * <p>DataAccessException 발생 시 warn 로그 후 {@code false} 반환 — 커서/minSrno 집계는 호출 측에서 이미 완료되었으므로 이
     * 메서드 내에서는 영향 없음.
     *
     * <p>커서/minSrno 집계는 이 메서드 호출 전에 processPage에서 반드시 실행되며, 예외 발생 여부와 무관하게 진행된다. (W1 영구 정체
     * 방지) @MX:WARN: [AUTO] 독성 행 DataAccessException 흡수 — 영구 정체 방지 (W1) @MX:REASON:
     * insertIgnoreDuplicate 예외(예: MySQL "Data too long") 발생 시 커서 진행이 차단되어 동일 페이지가 무한 재시도되는 영구 정체를
     * 방지한다.
     */
    private boolean tryInsert(NewsHeadline entity, String srnoVal) {
        try {
            newsHeadlineRepository.insertIgnoreDuplicate(entity);
            return true;
        } catch (DataAccessException ex) {
            log.warn(
                    "[news-title] 행 저장 실패 — skip (serial_no={}, error={}: {})",
                    srnoVal,
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            return false;
        }
    }

    private boolean shouldStopPaging(
            KisNewsTitleResponse response, PageResult pageResult, int pageCount) {
        // REQ-BATCH3-063: 페이지 종료 조건
        // 1) count < PAGE_SIZE (마지막 페이지 — size==1 경계 행 포함)
        if (response.output().size() < PAGE_SIZE) {
            log.debug("[news-title] 페이지 종료 (count<{}) — page={}", PAGE_SIZE, pageCount);
            return true;
        }
        if (pageResult.reachedStoredMax) {
            log.debug("[news-title] 저장 max serial_no 도달 — 증분 정지 (page={})", pageCount);
            return true;
        }
        return pageResult.minSrno == null;
    }

    /**
     * NewsTitleRow → NewsHeadline 매핑.
     *
     * @return null 이면 skip
     */
    @SuppressWarnings({"PMD.GuardLogStatement"}) // debug 레벨 파라미터 구성은 런타임 비용 무시 가능
    private NewsHeadline mapToEntity(KisNewsTitleResponse.NewsTitleRow row) {
        String dataDt = row.dataDt();
        String dataTm = row.dataTm();

        // REQ-BATCH3-070: 일시 파싱 실패 skip
        if (dataDt == null || dataDt.isBlank()) {
            log.debug("[news-title] 검증 실패 (dataDt null) — serial_no={}", row.cnttUsiqSrno());
            return null;
        }

        LocalDateTime publishedAt;
        try {
            // data_dt(8) + data_tm(6) → LocalDateTime
            String tm = (dataTm != null && dataTm.length() == 6) ? dataTm : "000000";
            publishedAt =
                    LocalDateTime.parse(dataDt + tm, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        } catch (DateTimeParseException e) {
            log.debug(
                    "[news-title] 일시 파싱 실패 — serial_no={}, dataDt={}, dataTm={}, error={}",
                    row.cnttUsiqSrno(),
                    dataDt,
                    dataTm,
                    e.getMessage());
            return null;
        }

        // REQ-BATCH3-061: iscd1~5 보존, iscd6~10·kor_isnm1~10 무시
        return NewsHeadline.builder()
                .serialNo(row.cnttUsiqSrno())
                .publishedAt(publishedAt)
                .providerCode(row.newsOferEntpCode())
                .title(row.htsPbntTitlCntt())
                .categoryCode(row.newsLrdvCode())
                .source(row.dorg())
                .stockCode1(row.iscd1())
                .stockCode2(row.iscd2())
                .stockCode3(row.iscd3())
                .stockCode4(row.iscd4())
                .stockCode5(row.iscd5())
                .build();
    }

    /** 페이지 처리 결과 집계 (내부 전달용). */
    private record PageResult(
            int attempted, int succeeded, int skipped, boolean reachedStoredMax, String minSrno) {}
}
