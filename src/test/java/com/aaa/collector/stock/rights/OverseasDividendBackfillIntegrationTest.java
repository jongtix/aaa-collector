package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.market.session.UsMarketSessionGate;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 해외 현금배당 백필 persist 트랜잭션 경계 통합 테스트 (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001 REQ-ODW-072).
 *
 * <p>fetch/persist의 매핑·rawRowCount 결정성·예외 전파는 {@link OverseasDividendBackfillTest}(mock 기반 단위 테스트)가
 * 이미 커버한다. 본 IT는 {@code @Transactional(propagation = MANDATORY)}가 실제 Spring AOP 프록시 경계에서 활성 트랜잭션 없이
 * 호출될 때 즉시 실패하는지만 검증한다({@code OverseasSplitIntegrationTest}의 동일 패턴, mock으로는 재현 불가 — 단위 테스트는 프록시가 없어
 * MANDATORY 위반을 관측할 수 없다).
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("해외 현금배당 백필 persist MANDATORY 트랜잭션 가드 IT")
@Tag("integration")
class OverseasDividendBackfillIntegrationTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @MockitoBean private GuardedKisExecutor guardedKisExecutor;
    @MockitoBean private KeyLeaseRegistry keyLeaseRegistry;

    // OverseasSplitIntegrationTest와 동일 사유 — ApplicationReadyEvent 기동 시
    // MarketSessionGateRefresher가 KisHolidayClient.fetchCalendar를 즉시 호출하므로 스텁 없이 두면
    // 컨텍스트 기동이 NPE로 깨진다(REQ-WM-007 MA-01).
    @MockitoBean(answers = org.mockito.Answers.RETURNS_MOCKS)
    private com.aaa.collector.kis.holiday.KisHolidayClient kisHolidayClient;

    @MockitoBean private UsMarketSessionGate usMarketOpenGate;

    @Autowired private OverseasDividendBackfillService service;

    @BeforeEach
    void setUp() {
        LeaseSession leaseSession = Mockito.mock(LeaseSession.class);
        when(usMarketOpenGate.isOpenDay(any())).thenReturn(true);
        when(keyLeaseRegistry.openSession()).thenReturn(leaseSession);
        when(leaseSession.isEmpty()).thenReturn(false);
    }

    @Test
    @DisplayName(
            "REQ-ODW-072: persistWindowForBackfill 트랜잭션 없이 호출 → IllegalTransactionStateException")
    void persistWithoutTransaction_throws() {
        OverseasDividendBackfillFetch fetch = new OverseasDividendBackfillFetch(List.of(), null, 0);

        assertThatThrownBy(() -> service.persistWindowForBackfill(fetch))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
