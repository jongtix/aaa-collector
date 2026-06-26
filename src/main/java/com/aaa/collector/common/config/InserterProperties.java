package com.aaa.collector.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Inserter 배치 설정 프로퍼티 (SPEC-COLLECTOR-INSERT-001 T-004, REQ-INSERT-009, REQ-INSERT-010).
 *
 * <p>{@code aaa.inserter.*} 프리픽스로 바인딩된다. 대용량 서비스(VIX/Fred/Ecos/CorpCode)가 {@code chunkSize}로 배치를
 * 분할하고, 소용량 서비스는 분할 없이 단일 배치로 INSERT IGNORE한다.
 */
// @MX:NOTE: [AUTO] 배치 청크 크기 설정 — 대용량 서비스(VIX/FRED/ECOS/CorpCode)가 공유
// @MX:REASON: REQ-INSERT-010 — HikariPool timeout 방지 (커넥션 획득 시간 < 5s 보장)
@ConfigurationProperties(prefix = "aaa.inserter")
public class InserterProperties {

    /** 배치 청크 크기 — 이 크기로 분할하여 청크마다 단일 커넥션 배치 INSERT IGNORE. */
    private int chunkSize = 1000;

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        if (chunkSize > 0) {
            this.chunkSize = chunkSize;
        }
    }
}
