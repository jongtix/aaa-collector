package com.aaa.collector.kis.token;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * KIS 토큰 안전 모드의 진입·해제·조회를 담당하는 컴포넌트.
 *
 * <p>안전 모드는 토큰 발급 실패 등 비정상 상황에서 추가 API 호출을 차단하기 위해 사용된다. 상태는 {@link SafeModeRepository}를 통해 Redis에
 * 영구 저장된다.
 *
 * <p>TODO: WebSocket 모듈 구현 시 이 클래스와 {@link SafeModeRepository}를 {@code common.safemode} 패키지로 이동하여
 * 범용 안전 모드 관리자로 확장한다. (TECHSPEC 1.3절 격리 단위 참조)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SafeModeManager {

    private final SafeModeRepository safeModeRepository;

    /**
     * 안전 모드에 진입한다.
     *
     * @param alias 계정 식별자
     * @param cause 안전 모드 진입의 원인이 된 예외
     */
    public void enter(String alias, Throwable cause) {
        safeModeRepository.setSafeMode(alias, true);
        log.error("[{}] 토큰 안전 모드 진입", alias, cause);
        // TODO: stream:system:collector 이벤트 발행 (1-9)
    }

    /**
     * 안전 모드를 해제한다.
     *
     * @param alias 계정 식별자
     */
    public void exit(String alias) {
        safeModeRepository.setSafeMode(alias, false);
        log.info("[{}] 토큰 안전 모드 해제", alias);
        // TODO: stream:system:collector 이벤트 발행 (1-9)
    }

    /**
     * 안전 모드 활성화 여부를 반환한다.
     *
     * @param alias 계정 식별자
     * @return 안전 모드 활성화 시 {@code true}
     */
    public boolean isActive(String alias) {
        return safeModeRepository.isSafeMode(alias);
    }
}
