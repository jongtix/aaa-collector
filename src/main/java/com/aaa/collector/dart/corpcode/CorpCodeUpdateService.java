package com.aaa.collector.dart.corpcode;

import com.aaa.collector.common.config.InserterProperties;
import com.aaa.collector.dart.external.DartCorpCodeClient;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DART corp_code 매핑 갱신 서비스 (SPEC-COLLECTOR-DART-001 REQ-DART-002).
 *
 * <p>corpCode.zip을 다운로드하고 상장사 매핑을 INSERT IGNORE로 적재한다. 기존 행은 갱신하지 않는다(append-only, Tier-1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorpCodeUpdateService {

    private final DartCorpCodeClient dartCorpCodeClient;
    private final CorpCodeMappingRepository corpCodeMappingRepository;
    private final CorpCodeMappingInserter corpCodeMappingInserter;
    private final InserterProperties inserterProperties;

    /**
     * 상장사 corp_code 매핑을 전량 적재한다 (REQ-DART-002, AC-C3).
     *
     * <p>상장사(stock_code 비어 있지 않음)만 적재하며, 이미 존재하는 행은 INSERT IGNORE로 무시한다(append-only). 수용된 트레이드오프:
     * 사명변경·상장폐지 등 기존 행 변경 미반영.
     */
    public void update() {
        log.info("[dart-corpcode] 매핑 갱신 시작");
        List<CorpCodeEntry> entries = dartCorpCodeClient.fetchListedEntries();
        if (entries.isEmpty()) {
            log.warn("[dart-corpcode] 상장사 매핑 없음 — 갱신 스킵");
            return;
        }

        // REQ-INSERT-009: 전체 매핑 누적 후 청크 분할 배치 INSERT IGNORE (REQ-INSERT-010)
        List<CorpCodeMapping> batch = new ArrayList<>();
        for (CorpCodeEntry entry : entries) {
            batch.add(
                    CorpCodeMapping.builder()
                            .stockCode(entry.stockCode())
                            .corpCode(entry.corpCode())
                            .corpName(entry.corpName())
                            .modifyDate(entry.modifyDate())
                            .build());
        }

        int chunkSize = inserterProperties.getChunkSize();
        for (int i = 0; i < batch.size(); i += chunkSize) {
            List<CorpCodeMapping> chunk = batch.subList(i, Math.min(i + chunkSize, batch.size()));
            corpCodeMappingInserter.insertBatch(chunk);
        }
        log.info("[dart-corpcode] 매핑 갱신 완료 — 처리 건수={}", batch.size());
    }
}
