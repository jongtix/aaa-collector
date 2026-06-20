package com.aaa.collector.backfill;

import java.time.LocalDate;

/**
 * 그룹 B anchor 거부(rt_cd=2) 보정 결과 (SPEC-COLLECTOR-BACKFILL-001 T5, REQ-016).
 *
 * <p>비영업일/장중 anchor가 거부되면 anchor를 −1 달력일씩 보정해 영업일에 닿을 때까지 재시도한다. 한도 ({@code anchor-skip-max}) 초과 시
 * {@code exhausted=true} — 호출자는 당 회차 skip(진행점 무전진, IN_PROGRESS 유지)한다.
 *
 * @param correctedAnchor −1 달력일 보정된 다음 anchor
 * @param attempts 누적 보정 시도 횟수
 * @param exhausted 한도 초과 여부({@code true}=당 회차 skip)
 */
public record AnchorCorrectionResult(LocalDate correctedAnchor, int attempts, boolean exhausted) {}
