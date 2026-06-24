package com.aaa.collector.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * SPEC-COLLECTOR-KISGATE-001 M6(T15) — {@link NewsTitleCollectionService} 게이트 경유 단위 테스트.
 *
 * <p>패턴 A는 Behavior:Changed이므로 신규 게이트 라우팅(throttle-on 4-arg 경유·collect() 페이지 루프 전체 = 1 batch이므로 세션
 * 1회 open)과 기존 수집 의미(증분 SRNO 커서·inclusive 경계 멱등·null skip·페이지 종료)를 함께 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NewsTitleCollectionService — 게이트 경유 단위 테스트")
class NewsTitleCollectionServiceTest {

    private static final String TR_ID = "FHKST01011800";

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private DomesticNewsHeadlineRepository newsHeadlineRepository;
    @Mock private LeaseSession session;

    private NewsTitleCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new NewsTitleCollectionService(
                        guardedKisExecutor, keyLeaseRegistry, newsHeadlineRepository);
        Mockito.lenient().when(keyLeaseRegistry.openSession()).thenReturn(session);
    }

    private KisNewsTitleResponse.NewsTitleRow row(String srno, String dataDt, String dataTm) {
        return new KisNewsTitleResponse.NewsTitleRow(
                srno,
                "2",
                dataDt,
                dataTm,
                "테스트 뉴스 제목",
                "1:0",
                "연합뉴스",
                "005930",
                null,
                null,
                null,
                null);
    }

    private KisNewsTitleResponse.NewsTitleRow rowWithIscd(
            String srno, String iscd1, String iscd2, String iscd3, String iscd4, String iscd5) {
        return new KisNewsTitleResponse.NewsTitleRow(
                srno,
                "2",
                "20260613",
                "090000",
                "이슈 뉴스",
                "1:0",
                "연합뉴스",
                iscd1,
                iscd2,
                iscd3,
                iscd4,
                iscd5);
    }

    /** 40건으로 채운 페이지 응답. srno는 startSrno부터 내림차순 */
    private KisNewsTitleResponse fullPage(long startSrno) {
        List<KisNewsTitleResponse.NewsTitleRow> rows = new ArrayList<>();
        for (int i = 0; i < NewsTitleCollectionService.PAGE_SIZE; i++) {
            long srnoVal = startSrno - i;
            rows.add(row(String.valueOf(srnoVal), "20260613", "090000"));
        }
        return new KisNewsTitleResponse("0", "MCA00000", "정상처리", rows);
    }

    private KisNewsTitleResponse pageOf(List<KisNewsTitleResponse.NewsTitleRow> rows) {
        return new KisNewsTitleResponse("0", "MCA00000", "정상처리", rows);
    }

    // ────────────────────────────────────────────────────────────────────
    // 정상 수집 — 매핑
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 수집 — 매핑 검증")
    class HappyPath {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts") // iscd1~5 매핑 전체 검증을 한 테스트에서 수행
        @DisplayName("단일 행 → DomesticNewsHeadline 필드 매핑 정확 (serial_no, published_at, iscd1~5)")
        void singleRow_fieldsMappedCorrectly() throws Exception {
            // Arrange
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            KisNewsTitleResponse.NewsTitleRow testRow =
                    rowWithIscd("1000000000000000001", "005930", "000660", null, null, null);
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(pageOf(List.of(testRow)));

            // Act
            service.collect();

            // Assert
            ArgumentCaptor<DomesticNewsHeadline> captor =
                    ArgumentCaptor.forClass(DomesticNewsHeadline.class);
            verify(newsHeadlineRepository).insertIgnoreDuplicate(captor.capture());

            DomesticNewsHeadline saved = captor.getValue();
            assertThat(saved.getSerialNo()).isEqualTo("1000000000000000001");
            assertThat(saved.getStockCode1()).isEqualTo("005930");
            assertThat(saved.getStockCode2()).isEqualTo("000660");
            assertThat(saved.getStockCode3()).isNull();
            assertThat(saved.getStockCode4()).isNull();
            assertThat(saved.getStockCode5()).isNull();
        }

        @Test
        @DisplayName("data_dt + data_tm → published_at 합성 정확")
        void publishedAt_composedFromDateAndTime() throws Exception {
            // Arrange
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            KisNewsTitleResponse.NewsTitleRow testRow =
                    row("1000000000000000001", "20260613", "143022");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(pageOf(List.of(testRow)));

            // Act
            service.collect();

            // Assert
            ArgumentCaptor<DomesticNewsHeadline> captor =
                    ArgumentCaptor.forClass(DomesticNewsHeadline.class);
            verify(newsHeadlineRepository).insertIgnoreDuplicate(captor.capture());
            assertThat(captor.getValue().getPublishedAt())
                    .isEqualTo(LocalDateTime.of(2026, 6, 13, 14, 30, 22));
        }

        @Test
        @DisplayName("배열 응답 처리 — 3건 → 3회 저장")
        void arrayResponse_storesAll() throws Exception {
            // Arrange
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(
                            pageOf(
                                    List.of(
                                            row("1000000000000000003", "20260613", "090000"),
                                            row("1000000000000000002", "20260613", "085930"),
                                            row("1000000000000000001", "20260613", "085900"))));

            // Act
            NewsCollectionResult result = service.collect();

            // Assert
            assertThat(result.succeeded()).isEqualTo(3);
            verify(newsHeadlineRepository, times(3)).insertIgnoreDuplicate(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // inclusive SRNO 커서 페이징
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("inclusive SRNO 커서 페이징")
    class SrnoCursorPaging {

        @Test
        @DisplayName("40건 1페이지 → 마지막 srno로 2페이지 요청, 3건 페이지에서 종료")
        void multiPage_cursorsCorrectly() throws Exception {
            // Arrange — 1페이지: 40건 (srno 1040~1001), 2페이지: 3건(srno 1001~999, 페이지 종료)
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(fullPage(1040L)) // page1: 40건, 마지막 srno=1001
                    .thenReturn(
                            pageOf(
                                    List.of( // page2: 3건 (count<40 → 종료)
                                            row("1001", "20260613", "090000"),
                                            row("1000", "20260613", "090000"),
                                            row("999", "20260613", "090000"))));

            // Act
            NewsCollectionResult result = service.collect();

            // Assert — 2페이지 호출, 40+3=43건 시도
            verify(guardedKisExecutor, times(2))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class));
            assertThat(result.attempted()).isEqualTo(43);
        }

        @Test
        @DisplayName("inclusive 커서 경계 중복 행 — 멱등 흡수 (insertIgnoreDuplicate 2회 호출)")
        void inclusiveCursorBoundaryDuplicate_idempotent() throws Exception {
            // Arrange — srno=1001이 page1 마지막이자 page2 첫 번째 (inclusive cursor)
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            // page1: 40건 (srno 1040~1001)
            // page2: boundary row 포함 3건 (srno 1001~999) — 1001 중복
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(fullPage(1040L))
                    .thenReturn(
                            pageOf(
                                    List.of(
                                            row("1001", "20260613", "090000"),
                                            row("1000", "20260613", "090000"),
                                            row("999", "20260613", "090000"))));

            // Act
            service.collect();

            // Assert — srno 1001이 2번 삽입 시도됨 (DB가 IGNORE 처리)
            // 총 43번 insertIgnoreDuplicate 호출
            verify(newsHeadlineRepository, times(43)).insertIgnoreDuplicate(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 증분 수집 — 저장 max serial_no 도달 시 정지
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("증분 수집 — 저장 max serial_no 도달 시 정지")
    class IncrementalStop {

        @Test
        @DisplayName("저장 max=1020 도달 시 정지 — 현재 페이지 내 모든 행(1030·1025·1020·1010) 저장")
        void stopsAtStoredMax() throws Exception {
            // Arrange — 저장된 최신: 1020. 페이지에 4건(1030, 1025, 1020, 1010)
            // 1020 도달 시 reachedStoredMax=true, 페이지 종료 후 다음 페이지 미요청.
            // processPage는 전 행을 순회하므로 4건 모두 insertIgnoreDuplicate 호출됨.
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn("1000000000000001020");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(
                            pageOf(
                                    List.of(
                                            row("1000000000000001030", "20260613", "090000"),
                                            row("1000000000000001025", "20260613", "090000"),
                                            row("1000000000000001020", "20260613", "090000"),
                                            row("1000000000000001010", "20260613", "090000"))));

            // Act
            NewsCollectionResult result = service.collect();

            // Assert — 페이지 내 4건 전부 저장 (processPage가 전 행 순회 후 reachedStoredMax 반영)
            assertThat(result.succeeded()).isEqualTo(4);
            // 1페이지만 요청 (증분 정지로 2페이지 요청 없음)
            verify(guardedKisExecutor, times(1))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class));
        }

        @Test
        @DisplayName("저장 max 없으면 (null) 전량 수집 — 정지하지 않음")
        void noStoredMax_collectsAll() throws Exception {
            // Arrange
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(
                            pageOf(
                                    List.of(
                                            row("1000000000000000003", "20260613", "090000"),
                                            row("1000000000000000002", "20260613", "090000"))));

            // Act
            NewsCollectionResult result = service.collect();

            // Assert
            assertThat(result.succeeded()).isEqualTo(2);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 검증 실패 skip (REQ-BATCH3-070)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("검증 실패 skip (REQ-BATCH3-070)")
    class ValidationSkip {

        @Test
        @DisplayName("serial_no null 행은 skip")
        void nullSerialNo_skip() throws Exception {
            // Arrange
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            KisNewsTitleResponse.NewsTitleRow nullSrno =
                    new KisNewsTitleResponse.NewsTitleRow(
                            null,
                            "2",
                            "20260613",
                            "090000",
                            "테스트",
                            "1:0",
                            "연합뉴스",
                            null,
                            null,
                            null,
                            null,
                            null);
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(pageOf(List.of(nullSrno)));

            // Act
            NewsCollectionResult result = service.collect();

            // Assert
            assertThat(result.skipped()).isEqualTo(1);
            verify(newsHeadlineRepository, never()).insertIgnoreDuplicate(any());
        }

        @Test
        @DisplayName("dataDt null 행은 skip")
        void nullDataDt_skip() throws Exception {
            // Arrange
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            KisNewsTitleResponse.NewsTitleRow nullDate =
                    new KisNewsTitleResponse.NewsTitleRow(
                            "1000000000000000001",
                            "2",
                            null,
                            "090000",
                            "테스트",
                            "1:0",
                            "연합뉴스",
                            null,
                            null,
                            null,
                            null,
                            null);
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(pageOf(List.of(nullDate)));

            // Act
            NewsCollectionResult result = service.collect();

            // Assert
            assertThat(result.skipped()).isEqualTo(1);
            verify(newsHeadlineRepository, never()).insertIgnoreDuplicate(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 빈 output
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("빈 output — 0건 성공 (REQ-BATCH3-073)")
    class EmptyOutput {

        @Test
        @DisplayName("빈 output → 저장 없음, 0건 성공")
        void emptyOutput_zeroSuccess() throws Exception {
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(pageOf(List.of()));

            NewsCollectionResult result = service.collect();

            assertThat(result.succeeded()).isZero();
            verify(newsHeadlineRepository, never()).insertIgnoreDuplicate(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // MAX_PAGES 안전 상한 (FIX 4)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MAX_PAGES 안전 상한 — 무한 루프 방지 (FIX 4)")
    class MaxPagesSafety {

        @Test
        @DisplayName("MAX_PAGES(500) 도달 시 강제 종료 — 501번째 페이지 미호출")
        void maxPages_forcedStop() throws Exception {
            // Arrange — 매번 40건(PAGE_SIZE) fullPage를 반환 → count==PAGE_SIZE, reachedStoredMax=false
            // storedMax=null → storedMax 도달 없음 → shouldStopPaging=false → 계속 루프
            // MAX_PAGES(500) 도달 시 while 조건 false → 강제 종료
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(fullPage(999_999L));

            // Act
            NewsCollectionResult result = service.collect();

            // Assert — 정확히 500회(MAX_PAGES) 요청
            verify(guardedKisExecutor, times(500))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class));
            // 500페이지 × PAGE_SIZE(40)건 = 20000건 시도
            assertThat(result.attempted()).isEqualTo(500 * NewsTitleCollectionService.PAGE_SIZE);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // datetime 파싱 실패 skip (FIX 4)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("dataDt 파싱 실패 — DateTimeParseException skip (FIX 4)")
    class DatetimeParseSkip {

        @Test
        @DisplayName("dataDt 유효하지 않은 날짜 형식 — 파싱 실패 skip")
        void invalidDateFormat_skip() throws Exception {
            // Arrange — dataDt가 날짜 형식이 아닌 값
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            KisNewsTitleResponse.NewsTitleRow badDateRow =
                    new KisNewsTitleResponse.NewsTitleRow(
                            "1000000000000000001",
                            "2",
                            "NOT_A_DATE", // dataDt: 유효하지 않음
                            "090000",
                            "테스트",
                            "1:0",
                            "연합뉴스",
                            null,
                            null,
                            null,
                            null,
                            null);
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(pageOf(List.of(badDateRow)));

            // Act
            NewsCollectionResult result = service.collect();

            // Assert — 파싱 실패로 skip
            assertThat(result.skipped()).isEqualTo(1);
            verify(newsHeadlineRepository, never()).insertIgnoreDuplicate(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 독성 행 skip — 영구 정체 방지 (W1)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("독성 행 skip — insertIgnoreDuplicate 예외 시 커서 진행 보장 (W1)")
    class PoisonRowSkip {

        @Test
        @DisplayName("1건 삽입 예외 → 나머지 행 저장, minSrno에 독성 행 반영, 루프 종료")
        void oneRowThrows_otherRowsInserted_cursorAdvances_loopTerminates() throws Exception {
            // Arrange — 3건 페이지: srno 1003(독성), 1002(정상), 1001(정상)
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            KisNewsTitleResponse.NewsTitleRow poisonRow = row("1003", "20260615", "090000");
            KisNewsTitleResponse.NewsTitleRow row2 = row("1002", "20260615", "090000");
            KisNewsTitleResponse.NewsTitleRow row3 = row("1001", "20260615", "090000");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(pageOf(List.of(poisonRow, row2, row3)));

            // insertIgnoreDuplicate: srno=1003 독성 행에서 예외 발생, 나머지는 정상
            // lenient 필요: 엄격 모드에서는 argThat 미일치 호출에 PotentialStubbingProblem 발생
            Mockito.lenient()
                    .doThrow(
                            new DataIntegrityViolationException("Data too long for column 'title'"))
                    .when(newsHeadlineRepository)
                    .insertIgnoreDuplicate(
                            org.mockito.ArgumentMatchers.argThat(
                                    e -> "1003".equals(e.getSerialNo())));

            // Act
            NewsCollectionResult result = service.collect();

            // Assert
            // (a) 정상 행 2건 삽입됨
            verify(newsHeadlineRepository, times(3)).insertIgnoreDuplicate(any());
            assertThat(result.succeeded()).isEqualTo(2);
            // (b) 독성 행은 skipped 집계
            assertThat(result.skipped()).isEqualTo(1);
            // (c) KisApiExecutor: 페이지 1회만 호출 (루프 종료)
            verify(guardedKisExecutor, times(1))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class));
        }

        @Test
        @DisplayName("독성 행의 srno=1003이 페이지 최솟값 — minSrno에 반영되어 커서 진행")
        void poisonRow_isMinSrno_cursorRefectsIt() throws Exception {
            // Arrange — 독성 행(1003)이 이 페이지의 유일한 최솟값
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            KisNewsTitleResponse.NewsTitleRow poison = row("1003", "20260615", "090000");
            KisNewsTitleResponse.NewsTitleRow row2 = row("1005", "20260615", "090000");
            KisNewsTitleResponse.NewsTitleRow row3 = row("1004", "20260615", "090000");
            // 총 3건 (count < PAGE_SIZE=40 → shouldStopPaging=true)
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(pageOf(List.of(row2, row3, poison)));

            Mockito.lenient()
                    .doThrow(new DataIntegrityViolationException("Data too long"))
                    .when(newsHeadlineRepository)
                    .insertIgnoreDuplicate(
                            org.mockito.ArgumentMatchers.argThat(
                                    e -> "1003".equals(e.getSerialNo())));

            // Act — 1페이지(3건, count<40)에서 종료
            NewsCollectionResult result = service.collect();

            // Assert — 2건 저장, 1건 skipped, 루프 1회
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(1);
            verify(guardedKisExecutor, times(1))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 멱등성
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("멱등 저장 — uk_domestic_news_headlines_serial")
    class Idempotency {

        @Test
        @DisplayName("10분 재실행 시 insertIgnoreDuplicate 재호출 (DB가 중복 무시)")
        void idempotentRerun() throws Exception {
            when(newsHeadlineRepository.findMaxSerialNo()).thenReturn(null);
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisNewsTitleResponse.class)))
                    .thenReturn(pageOf(List.of(row("1000000000000000001", "20260613", "090000"))));

            // Act — 2회 실행
            service.collect();
            service.collect();

            // Assert
            verify(newsHeadlineRepository, times(2)).insertIgnoreDuplicate(any());
        }
    }
}
