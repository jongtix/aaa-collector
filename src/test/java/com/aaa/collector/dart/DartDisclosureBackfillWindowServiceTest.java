package com.aaa.collector.dart;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.dart.backfill.DartDisclosureBackfillWindowService;
import com.aaa.collector.dart.corpcode.CorpCodeMappingRepository;
import com.aaa.collector.dart.disclosure.DisclosureInserter;
import com.aaa.collector.dart.disclosure.DisclosureRepository;
import com.aaa.collector.dart.external.DartDisclosureClient;
import com.aaa.collector.dart.external.DartListResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** DART 공시 백필 윈도우 서비스 단위 테스트 (SPEC-COLLECTOR-DART-001 REQ-DART-020~023). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DartDisclosureBackfillWindowServiceTest")
class DartDisclosureBackfillWindowServiceTest {

    @Mock private DartDisclosureClient dartDisclosureClient;
    @Mock private DisclosureRepository disclosureRepository;
    @Mock private DisclosureInserter disclosureInserter;
    @Mock private CorpCodeMappingRepository corpCodeMappingRepository;
    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private DartDisclosureBackfillWindowService windowService;

    private static final String SYMBOL = "005930";
    private static final Long STOCK_ID = 10L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpTransactionTemplate() {
        // TransactionTemplate.executeWithoutResult 실제 Consumer 실행
        doAnswer(
                        inv -> {
                            Consumer<TransactionStatus> action = inv.getArgument(0);
                            action.accept(Mockito.mock(TransactionStatus.class));
                            return null;
                        })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    /** 픽스처: BackfillStatus mock 생성. protected 생성자 우회용. */
    private BackfillStatus mockStatus(Long id, LocalDate lastCollectedDate) {
        BackfillStatus status = Mockito.mock(BackfillStatus.class);
        when(status.getId()).thenReturn(id);
        when(status.getLastCollectedDate()).thenReturn(lastCollectedDate);
        return status;
    }

    private DartListResponse.DisclosureItem makeItem(String stockCode, String rceptDt) {
        return DartListResponse.DisclosureItem.of(
                "00000001", "Y", stockCode, "사업보고서", "20260601000001", "삼성전자", rceptDt, null);
    }

    @Nested
    @DisplayName("corp_code 매핑 없음")
    class CorpCodeNotFound {

        @Test
        @DisplayName("corp_code 없음 → findById 호출 후 fail() 1회 호출, insertIgnore 미호출")
        void missingCorpCode_callsUpdateErrorAndSkipsInsert() {
            // Arrange
            BackfillStatus status = mockStatus(1L, LocalDate.of(2026, 6, 20));
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(corpCodeMappingRepository.findCorpCodeByStockCode(SYMBOL))
                    .thenReturn(Optional.empty());
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));

            // Act
            windowService.executeWindow(status, STOCK_ID, SYMBOL);

            // Assert
            verify(backfillStatusRepository).findById(1L);
            verify(mockManaged)
                    .fail(eq(BackfillStatusType.IN_PROGRESS), eq("corp_code 매핑 없음: " + SYMBOL));
            verify(disclosureInserter, never()).insertBatchIsolated(any(), any());
        }
    }

    @Nested
    @DisplayName("stale-window COMPLETED 전이")
    class StaleWindowCompleted {

        @Test
        @DisplayName("API 0건 반환 → findById 후 advance(COMPLETED, bgnDe, 0, 0) 호출")
        void apiReturnsZeroItems_updatesStatusToCompleted() {
            // Arrange
            // lastCollectedDate=2026-06-20 → endDe=2026-06-19, bgnDe=2026-05-20
            BackfillStatus status = mockStatus(1L, LocalDate.of(2026, 6, 20));
            LocalDate expectedBgnDe = LocalDate.of(2026, 5, 20);
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(corpCodeMappingRepository.findCorpCodeByStockCode(SYMBOL))
                    .thenReturn(Optional.of("00000001"));
            when(dartDisclosureClient.fetchAllPages(any(), any(), any())).thenReturn(List.of());
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));

            // Act
            windowService.executeWindow(status, STOCK_ID, SYMBOL);

            // Assert
            verify(backfillStatusRepository).findById(1L);
            verify(mockManaged)
                    .advance(eq(BackfillStatusType.COMPLETED), eq(expectedBgnDe), eq(0), eq(0));
        }
    }

    @Nested
    @DisplayName("anchor 전진")
    class AnchorAdvance {

        @Test
        @DisplayName(
                "API 1건 반환(symbol 일치) → insertIgnore 1회 + findById 후 advance(IN_PROGRESS, bgnDe, 0, 1) 호출")
        void apiReturnsOneMatchingItem_insertsAndAdvancesAnchor() {
            // Arrange
            // lastCollectedDate=2026-06-20 → endDe=2026-06-19, bgnDe=2026-05-20
            BackfillStatus status = mockStatus(1L, LocalDate.of(2026, 6, 20));
            LocalDate expectedBgnDe = LocalDate.of(2026, 5, 20);
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(corpCodeMappingRepository.findCorpCodeByStockCode(SYMBOL))
                    .thenReturn(Optional.of("00000001"));
            when(dartDisclosureClient.fetchAllPages(any(), any(), any()))
                    .thenReturn(List.of(makeItem(SYMBOL, "20260601")));
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));

            // Act
            windowService.executeWindow(status, STOCK_ID, SYMBOL);

            // Assert
            verify(disclosureInserter).insertBatchIsolated(any(), any());
            verify(backfillStatusRepository).findById(1L);
            verify(mockManaged)
                    .advance(eq(BackfillStatusType.IN_PROGRESS), eq(expectedBgnDe), eq(0), eq(1));
        }
    }

    @Nested
    @DisplayName("stock_code 불일치 필터")
    class StockCodeMismatchFilter {

        @Test
        @DisplayName("API 2건(1건 일치·1건 불일치) → insertIgnore 1회만 호출")
        void apiReturnsTwoItems_onlyMatchingSymbolInserted() {
            // Arrange
            BackfillStatus status = mockStatus(1L, LocalDate.of(2026, 6, 20));
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(corpCodeMappingRepository.findCorpCodeByStockCode(SYMBOL))
                    .thenReturn(Optional.of("00000001"));
            DartListResponse.DisclosureItem matching = makeItem(SYMBOL, "20260601");
            DartListResponse.DisclosureItem mismatch = makeItem("000660", "20260601");
            when(dartDisclosureClient.fetchAllPages(any(), any(), any()))
                    .thenReturn(List.of(matching, mismatch));
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));

            // Act
            windowService.executeWindow(status, STOCK_ID, SYMBOL);

            // Assert — 일치하는 1건만 삽입
            verify(disclosureInserter).insertBatchIsolated(any(), any());
        }
    }
}
