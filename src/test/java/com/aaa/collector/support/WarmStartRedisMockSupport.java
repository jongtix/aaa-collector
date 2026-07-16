package com.aaa.collector.support;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 풀컨텍스트 통합 테스트용 warm-start Redis 의존 일괄 모킹 베이스.
 *
 * <p>{@code @SpringBootTest} 컨텍스트가 기동되면 warm-start {@link
 * org.springframework.boot.ApplicationRunner} 구현체들(BatchMetricsWarmStarter,
 * MarketIndicatorMetricsWarmStarter, CoverageMetricsWarmStarter,
 * BackfillDensityMetricsWarmStarter)이 자동 실행되며 각자의 Redis 레포지토리 {@code find()}를 호출한다. mock {@link
 * StringRedisTemplate}는 {@code opsForValue()}가 null을 반환하므로 실 레포지토리가 NPE를 던져 컨텍스트 로드가 실패한다. 이를 막기 위해
 * warm-start Redis 레포지토리 전수를 mock 빈으로 대체한다 — mock은 기본적으로 {@code find*()}에서 {@code
 * Optional.empty()}를 반환해 게이지를 사전 등록값 그대로 두고 seed를 생략하는 정상 경로를 탄다({@link SmokeMockitoBase}의 {@code
 * types} 배열 모킹과 동일 목적·패턴).
 *
 * <p>각 테스트가 이 5개 mock을 개별 필드로 반복 선언하면 import·필드 수가 PMD 임계값을 넘어서므로(ExcessiveImports/TooManyFields),
 * 상속으로 공유한다. Spring bean override는 테스트 클래스 계층(상위 클래스 포함)을 스캔하므로 {@code @MockitoBean(types=...)}가
 * 서브클래스 컨텍스트에 그대로 적용된다.
 */
@MockitoBean(
        types = {
            StringRedisTemplate.class,
            BatchLastLoadRepository.class,
            MarketIndicatorLastSuccessRepository.class,
            CoverageRatioRepository.class,
            BackfillDensityRepository.class
        })
public class WarmStartRedisMockSupport {

    protected WarmStartRedisMockSupport() {}
}
