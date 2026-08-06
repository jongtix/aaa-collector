package com.aaa.collector.stock.supply.correction;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * T0R 완료 마커 조회 전용 리포지토리 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-043).
 *
 * <p>앱은 이 테이블을 SELECT만 한다 — UPDATE 메서드를 두지 않는다(Tier 분류 대상 아님, {@code DbGrantVerifier.TIER2_TABLES}
 * 미등재). {@code findById({@link T0rCorrectionStatus#SINGLETON_ID})}로 단일 마커 행을 조회한다.
 */
public interface T0rCorrectionStatusRepository extends JpaRepository<T0rCorrectionStatus, Byte> {}
