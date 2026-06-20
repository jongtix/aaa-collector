package com.aaa.collector.backfill;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link BackfillStatus} 영속성 리포지토리 (SPEC-COLLECTOR-BACKFILL-001).
 *
 * <p>T1 범위에서는 기본 CRUD만 제공한다. cron 진입부 lazy 시딩용 INSERT IGNORE 메서드는 T2에서 추가한다(REQ-BACKFILL-007/-008).
 */
public interface BackfillStatusRepository extends JpaRepository<BackfillStatus, Long> {}
