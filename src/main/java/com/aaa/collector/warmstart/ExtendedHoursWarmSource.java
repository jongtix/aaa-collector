package com.aaa.collector.warmstart;

import com.aaa.collector.stock.exthours.ExtendedHoursRepository;
import com.aaa.collector.stock.exthours.Session;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code extended-hours-pre}/{@code extended-hours-after} last_load warm-start seed 해석기
 * (SPEC-COLLECTOR-EXPECTED-RUN-001 REQ-XR-018).
 *
 * <p>{@link BatchMetricsWarmStarter}에서 세션 분리 로직을 추출한 협력자다. {@code session} 컬럼 기준 최신 거래일 ({@link
 * ExtendedHoursRepository#findMaxTradeDateBySession}, SPEC-OBSV-WATERMARK-001 REQ-WM-003 자산 재사용)을
 * PRE/AFTER로 물리적으로 구분해 seed한다. 거래일을 그날 00:00 {@link LocalDateTime}으로 승격해 warm helper가 기대하는 {@code
 * Optional<LocalDateTime>}에 맞춘다 — 이는 거친 seed이며 첫 실제 실행이 {@code recordCompletion}으로 정밀 완료 시각으로
 * 덮어쓴다(실행-앵커 모델이라 seed 정밀도는 비임계, REQ-XR-019).
 *
 * <p>이 추출로 {@code Session}·시각 변환 참조를 warm-starter 밖으로 밀어내 warm-starter의 import 결합도
 * 상한(ExcessiveImports) 을 회피한다.
 */
@Component
@RequiredArgsConstructor
public class ExtendedHoursWarmSource {

    private final ExtendedHoursRepository extendedHoursRepository;

    /** PRE 세션 최신 거래일을 00:00 seed 시각으로 해석한다. 데이터 없으면 {@link Optional#empty()}. */
    public Optional<LocalDateTime> preLastLoad() {
        return extendedHoursRepository
                .findMaxTradeDateBySession(Session.PRE)
                .map(LocalDate::atStartOfDay);
    }

    /** AFTER 세션 최신 거래일을 00:00 seed 시각으로 해석한다. 데이터 없으면 {@link Optional#empty()}. */
    public Optional<LocalDateTime> afterLastLoad() {
        return extendedHoursRepository
                .findMaxTradeDateBySession(Session.AFTER)
                .map(LocalDate::atStartOfDay);
    }
}
