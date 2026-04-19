package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiResponse;
import java.util.List;

/**
 * KIS {@code intstock-grouplist} API 응답 DTO.
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 설정에 의해 JSON snake_case 키가
 * camelCase 필드에 자동 매핑된다.
 */
public record KisGroupListResponse(String rtCd, String msgCd, String msg1, List<Group> output2)
        implements KisApiResponse {

    public KisGroupListResponse {
        output2 = output2 != null ? List.copyOf(output2) : List.of();
    }

    /** 관심 그룹 항목. */
    public record Group(String interGrpCode, String interGrpName, String askCnt, String dataRank) {}
}
