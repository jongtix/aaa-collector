package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillProperties;
import com.aaa.collector.backfill.BackfillTerminationPolicy;
import com.aaa.collector.backfill.BackfillWindowAdvancer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 백필 인프라 빈 구성 (SPEC-COLLECTOR-BACKFILL-001 T6/T7).
 *
 * <p>{@link BackfillTerminationPolicy}와 {@link BackfillWindowAdvancer}는 순수 로직 클래스(Spring 비의존)이며
 * {@link BackfillProperties}에서 임계값을 주입받아 빈으로 등록된다.
 *
 * <p>패키지 위치: {@code stock.backfill} — {@code stock} 피처 패키지가 {@code backfill} 피처 패키지에 의존하는 기존 방향을
 * 유지한다.
 */
@Configuration
class BackfillConfig {

    /**
     * 그룹별 백필 종료 판정기 빈 — staleWindowThreshold는 {@link BackfillProperties}에서 주입.
     *
     * @param props 백필 설정 프로퍼티
     * @return {@link BackfillTerminationPolicy} 인스턴스
     */
    @Bean
    BackfillTerminationPolicy backfillTerminationPolicy(BackfillProperties props) {
        return new BackfillTerminationPolicy(props.getStaleWindowThreshold());
    }

    /**
     * 백필 윈도우 anchor 전진기 빈 — floorDate·anchorSkipMax는 {@link BackfillProperties}에서 주입
     * (SPEC-COLLECTOR-BACKFILL-005).
     *
     * @param props 백필 설정 프로퍼티
     * @return {@link BackfillWindowAdvancer} 인스턴스
     */
    @Bean
    BackfillWindowAdvancer backfillWindowAdvancer(BackfillProperties props) {
        return new BackfillWindowAdvancer(props.getFloorDate(), props.getAnchorSkipMax());
    }
}
