package com.aaa.collector.schedule.catchup;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * zone-aware 직전 발화 시각(expectedLastFire)을 계산한다 (SPEC-COLLECTOR-CATCHUP-001 T3).
 *
 * <p>8일 lookback으로 주간 cron(토/월)도 반드시 1회 이상 포함된다. Spring {@link CronExpression}을 재사용하므로 신규 의존성 없음.
 *
 * <p>알고리즘: {@code now - 8일}부터 {@code CronExpression.next(t)}로 다음 발화 시각으로 직접 점프하며(분 단위 순회 아님) {@code
 * <= now}인 마지막 히트를 수집한다. 반복 횟수는 8일 lookback 구간 내 실제 cron 발화 횟수에 비례한다 — 일간 배치는 ~8회, 10분-폴링 배치(news
 * 2종)도 최악 ~250~290회 수준으로, 24시간×60분 전수 순회(11,520회)와는 무관하다. 성능 문제 없음 — 부팅 시 1회 실행.
 *
 * <p><b>재용도 노트(SPEC-COLLECTOR-EXPECTED-RUN-001 REQ-XR-003)</b>: 위 부팅-1회 가정과 달리 {@link
 * com.aaa.collector.schedule.ExpectedRunGaugeBinder}가 이 계산기를 편입 배치 ~20종의 {@code expected_run} 게이지
 * 산출에 매 Prometheus scrape마다 호출한다. fire-jump 알고리즘 특성상 20배치 합산 scrape당 총 {@code
 * CronExpression.next()} 호출은 ~685회(실측, scrape 간격 30s 기준) 수준으로 sub-ms~low-ms 비용으로 추정되어 무시할 만하다(SPEC
 * §14). 유의미한 비용이 실측되면 scrape-interval 캐싱을 검토한다.
 */
@Component
public class ExpectedFireCalculator {

    /** 8일 lookback — 주간 cron을 1회 이상 포함하기에 충분. */
    private static final int LOOKBACK_DAYS = 8;

    /** 히트 재진입 방지용 커서 전진 폭(초) — 매 스텝 순회가 아니라 직전 히트 슬롯을 건너뛰는 오프셋. */
    private static final int STEP_SECONDS = 60;

    /**
     * 주어진 cron·zone 기준으로 {@code now} 이전 마지막 발화 시각을 반환한다.
     *
     * @param cron Spring cron 표현식 (6필드, 초 포함)
     * @param zoneId 타임존 문자열 (예: "Asia/Seoul", "America/New_York")
     * @param now 현재 시각 기준점
     * @return 마지막 발화 Instant. lookback 8일 이내에 발화 슬롯이 없으면 {@link Optional#empty()}
     */
    public Optional<Instant> calculate(String cron, String zoneId, Instant now) {
        CronExpression expression = CronExpression.parse(cron);
        ZoneId zone = ZoneId.of(zoneId);

        // lookback 시작점: now - 8일
        ZonedDateTime cursor = now.atZone(zone).minusDays(LOOKBACK_DAYS);
        ZonedDateTime nowZoned = now.atZone(zone);

        Instant lastHit = null;

        // cursor에서 next()를 반복 호출해 <= now 조건의 마지막 히트 수집
        while (true) {
            LocalDateTime cursorLocal = cursor.toLocalDateTime();
            LocalDateTime nextLocal = expression.next(cursorLocal);
            if (nextLocal == null) {
                break;
            }
            ZonedDateTime nextZoned = nextLocal.atZone(zone);
            if (nextZoned.isAfter(nowZoned)) {
                break;
            }
            lastHit = nextZoned.toInstant();
            // 다음 탐색: nextZoned + 1분 (같은 슬롯 재진입 방지)
            cursor = nextZoned.plusSeconds(STEP_SECONDS);
        }

        return Optional.ofNullable(lastHit);
    }
}
