package com.aaa.collector.dart;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.config.InserterProperties;
import com.aaa.collector.dart.corpcode.CorpCodeEntry;
import com.aaa.collector.dart.corpcode.CorpCodeMappingInserter;
import com.aaa.collector.dart.corpcode.CorpCodeMappingRepository;
import com.aaa.collector.dart.corpcode.CorpCodeUpdateService;
import com.aaa.collector.dart.external.DartCorpCodeClient;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CorpCodeUpdateService 단위 테스트 (SPEC-COLLECTOR-DART-001 REQ-DART-002). */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorpCodeUpdateServiceTest")
class CorpCodeUpdateServiceTest {

    @Mock private DartCorpCodeClient dartCorpCodeClient;
    @Mock private CorpCodeMappingRepository corpCodeMappingRepository;
    @Mock private CorpCodeMappingInserter corpCodeMappingInserter;

    private CorpCodeUpdateService corpCodeUpdateService;

    @BeforeEach
    void setUp() {
        corpCodeUpdateService =
                new CorpCodeUpdateService(
                        dartCorpCodeClient,
                        corpCodeMappingRepository,
                        corpCodeMappingInserter,
                        new InserterProperties());
    }

    @Nested
    @DisplayName("상장사만 INSERT IGNORE")
    class ListedEntriesInsert {

        @Test
        @DisplayName("상장사 2건 반환 → insertBatch 1회 호출 (AC-4 배치 통합, REQ-INSERT-009)")
        void twoListedEntries_callsInsertBatchOnce() {
            // Arrange
            CorpCodeEntry e1 =
                    new CorpCodeEntry("00000001", "삼성전자", "005930", LocalDate.of(2026, 1, 1));
            CorpCodeEntry e2 =
                    new CorpCodeEntry("00000002", "SK하이닉스", "000660", LocalDate.of(2026, 1, 1));
            when(dartCorpCodeClient.fetchListedEntries()).thenReturn(List.of(e1, e2));

            // Act
            corpCodeUpdateService.update();

            // Assert — 2건 모두 단일 배치 (chunkSize=1000 > 2)
            verify(corpCodeMappingInserter, times(1)).insertBatch(any());
        }
    }

    @Nested
    @DisplayName("빈 목록 반환 시 로깅만")
    class EmptyList {

        @Test
        @DisplayName("fetchListedEntries 빈 목록 → insertIgnore 미호출")
        void emptyEntries_skipsInsert() {
            when(dartCorpCodeClient.fetchListedEntries()).thenReturn(List.of());

            corpCodeUpdateService.update();

            verify(corpCodeMappingInserter, never()).insertBatch(any());
        }
    }
}
