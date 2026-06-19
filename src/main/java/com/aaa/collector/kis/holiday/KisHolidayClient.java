package com.aaa.collector.kis.holiday;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * KIS 국내휴장일조회 API 클라이언트 (TR_ID=CTCA0903R).
 *
 * <p>엔드포인트: {@code GET /uapi/domestic-stock/v1/quotations/chk-holiday}. 단일 호출에 기준일부터 ~24일치를 반환한다 —
 * 페이징({@code ctx_area_nk})은 구현하지 않는다(SPEC-COLLECTOR-OBSV-001 §1.6 호출 정책, KisApiExecutor 확장 금지).
 *
 * <p>[★주의] KIS 원장서비스 연관으로 가급적 1일 1회 호출을 권고한다. 일 1회 갱신·캐시 후 메모리 판정으로 사용한다.
 *
 * <p>게이트 경유(SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-008): KIS REST는 {@link GuardedKisExecutor} 단일
 * 게이트를 경유해야 한다(ArchUnit 강제). 본 클라이언트는 단발 호출이므로 호출 시점에 {@link KeyLeaseRegistry#openSession()}으로
 * per-call 헬스 스냅샷을 1회 열고 게이트에 위임한다. 전 키 사망({@link NoHealthyKeyException}) 또는 인터럽트는 {@link
 * IllegalStateException}으로 변환해 상위 갱신기({@code MarketSessionGateRefresher})의 실패 격리 catch가 직전 캐시를 유지하게
 * 한다(REQ-OBSV-033).
 */
@Component
@RequiredArgsConstructor
public class KisHolidayClient {

    private static final String TR_ID = "CTCA0903R";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * 기준일부터의 국내 휴장/개장 일자 배열을 조회한다 (REQ-OBSV-031).
     *
     * <p>단일 호출로 기준일부터 ~24일치를 반환한다. 페이징은 구현하지 않는다. KIS REST는 게이트를 경유한다(REQ-KISGATE-008).
     *
     * @param baseDate 조회 기준일 (KST)
     * @return 일자별 개장/휴장 정보 목록 (응답 output 그대로, 빈 목록 방어 포함)
     * @throws IllegalStateException 전 키 사망 또는 인터럽트로 게이트 호출이 불가능할 때 (상위 갱신기가 직전 캐시 유지)
     */
    public List<KisHolidayResponse.HolidayRow> fetchCalendar(LocalDate baseDate) {
        String bassDt = baseDate.format(DATE_FORMAT);

        // REQ-KISGATE-006a: per-call 헬스 스냅샷 1회 고정 (단발 호출이므로 호출 단위 = batch 단위)
        LeaseSession session = keyLeaseRegistry.openSession();
        if (session.isEmpty()) {
            // REQ-KISGATE-024 보존: 전 키 사망 → 폴백 없음. 상위 갱신기 catch가 직전 캐시를 유지(REQ-OBSV-033)하도록 신호.
            throw new IllegalStateException("KIS 휴장일 조회 불가 — 건강 키 0개(전 키 사망)");
        }

        KisHolidayResponse response;
        try {
            response =
                    guardedKisExecutor.execute(
                            session,
                            uri ->
                                    uri.path("/uapi/domestic-stock/v1/quotations/chk-holiday")
                                            .queryParam("BASS_DT", bassDt)
                                            .queryParam("CTX_AREA_NK", "")
                                            .queryParam("CTX_AREA_FK", "")
                                            .build(),
                            TR_ID,
                            KisHolidayResponse.class);
        } catch (InterruptedException e) {
            // RETRY-001 REQ-RETRY-017 보존: 인터럽트 플래그 복원 후 갱신 불가 신호로 변환(직전 캐시 유지).
            Thread.currentThread().interrupt();
            throw new IllegalStateException("KIS 휴장일 조회 인터럽트", e);
        } catch (NoHealthyKeyException e) {
            // 방어적: openSession 비어있지 않아도 시도 중 전 키 소진 가능 — 직전 캐시 유지 신호로 변환.
            throw new IllegalStateException("KIS 휴장일 조회 불가 — 게이트 건강 키 소진", e);
        }

        List<KisHolidayResponse.HolidayRow> output = response.output();
        return output != null ? output : List.of();
    }
}
