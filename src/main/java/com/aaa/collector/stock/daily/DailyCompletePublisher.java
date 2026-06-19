package com.aaa.collector.stock.daily;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 일봉 수집 완료 이벤트 발행기.
 *
 * <p>수집 완료 시 Redis Streams {@value STREAM_KEY}에 {@code market}(국내 {@code domestic} / 해외 {@code
 * overseas}) + 완전성 메타(시도/성공/skip 종목 수)를 발행한다. analyzer가 {@code market} 필드로 시장별 모델 추론을 분기한다.
 *
 * <p>XADD 실패 시 예외를 흡수하고 로그만 남긴다 — 이미 저장된 일봉 결과를 무효화하지 않는다(REQ-BATCH-043 / REQ-OVOH-014).
 *
 * <p>MAXLEN 100 exact trimming(approximate=false) 적용 (REQ-BATCH-041 / TECHSPEC 5.1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyCompletePublisher {

    static final String STREAM_KEY = "stream:daily:complete";
    private static final long MAX_LEN = 100L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 일봉 수집 완료 이벤트를 지정 시장 식별자로 발행한다.
     *
     * <p>일부 종목 skip 여부와 무관하게 항상 발행한다(REQ-BATCH-040 / REQ-OVOH-040).
     *
     * @param result 수집 결과 집계 (시도/성공/skip 종목 수)
     * @param market 시장 식별자 (국내 {@code domestic} / 해외 {@code overseas})
     */
    public void publish(CollectionResult result, String market) {
        Map<String, String> fields =
                Map.of(
                        "market", market,
                        "attempted", String.valueOf(result.attempted()),
                        "succeeded", String.valueOf(result.succeeded()),
                        "skipped", String.valueOf(result.skipped()));

        MapRecord<String, String, String> record = MapRecord.create(STREAM_KEY, fields);
        XAddOptions options = XAddOptions.maxlen(MAX_LEN).approximateTrimming(false);

        try {
            redisTemplate.opsForStream().add(record, options);
            log.info(
                    "[{}-daily] 완료 이벤트 발행 — attempted={}, succeeded={}, skipped={}",
                    market,
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (DataAccessException e) {
            // REQ-BATCH-043 / REQ-OVOH-014: 발행 실패는 수집 결과를 무효화하지 않음
            // DataAccessException은 Redis 연결/타임아웃 오류이며 비밀정보를 포함하지 않음
            log.warn(
                    "[{}-daily] 완료 이벤트 발행 실패 — attempted={}, succeeded={}, skipped={}",
                    market,
                    result.attempted(),
                    result.succeeded(),
                    result.skipped(),
                    e);
        }
    }
}
