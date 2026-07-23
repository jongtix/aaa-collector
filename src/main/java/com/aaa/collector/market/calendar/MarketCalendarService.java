package com.aaa.collector.market.calendar;

import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code market_calendar} 우선순위 인지 upsert 단일 진입점 (SPEC-COLLECTOR-CALENDAR-001
 * REQ-CAL-003/-004/-005/-022).
 *
 * <p>기존 행이 없으면 INSERT, 있으면 신규 {@code source}의 우선순위({@link CalendarSource#ordinal()} 기반, 값이 작을수록
 * 우선순위 높음)가 기존 이상일 때만 UPDATE한다(JPA dirty-check 경로 — 조회 후 조건부 저장). 네이티브 {@code ON DUPLICATE KEY
 * UPDATE}는 사용하지 않는다(REQ-CAL-022, {@code backfill_status} 선례와 동일 근거 — Tier-1 회귀 가드 허용목록 확장 불필요).
 * DELETE 경로는 두지 않는다(REQ-CAL-005).
 */
@Service
@RequiredArgsConstructor
public class MarketCalendarService {

    private final MarketCalendarRepository marketCalendarRepository;

    /**
     * 지정 날짜의 개장 여부·출처를 우선순위 인지 방식으로 반영한다.
     *
     * <p>낮은 우선순위(ordinal 값이 더 큰) {@code source}의 upsert 시도는 기존 값을 변경하지 않는다(REQ-CAL-004) — 운영자 수동
     * 정정({@link CalendarSource#MANUAL})이 다음날 자동 갱신으로 되돌아가지 않는다.
     *
     * @param calendarCode 캘린더 도메인(KRX/NYSE)
     * @param calDate 대상 날짜
     * @param isOpen 개장 여부
     * @param source 값 출처(우선순위 판정에 사용)
     */
    @Transactional
    public void upsert(
            CalendarCode calendarCode, LocalDate calDate, boolean isOpen, CalendarSource source) {
        Optional<MarketCalendar> existing =
                marketCalendarRepository.findByCalendarCodeAndCalDate(calendarCode, calDate);
        if (existing.isEmpty()) {
            marketCalendarRepository.save(
                    MarketCalendar.builder()
                            .calendarCode(calendarCode)
                            .calDate(calDate)
                            .open(isOpen)
                            .source(source)
                            .build());
            return;
        }

        MarketCalendar current = existing.get();
        if (source.ordinal() > current.getSource().ordinal()) {
            // 신규 source가 기존보다 우선순위 낮음(ordinal 값이 큼) — 덮어쓰지 않는다(REQ-CAL-004).
            return;
        }
        // MANAGED 엔티티 dirty-check로 UPDATE 발화 — save() 호출 없음(ON DUPLICATE KEY UPDATE 미사용,
        // REQ-CAL-022).
        current.applyUpdate(isOpen, source);
    }
}
