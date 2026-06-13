package com.aaa.collector.common.startup;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * collector 기동 시 DB 권한 self-check를 수행하는 {@link ApplicationRunner}.
 *
 * <p>Spring Boot는 {@code ApplicationRunner}를 {@code ApplicationContext} 완전 초기화 후 실행한다. 따라서 Flyway
 * 마이그레이션과 DataSource 준비가 완료된 후 이 빈이 실행된다. {@code run()} 메서드에서 예외를 던지면 {@code
 * SpringApplication.run()}이 예외를 전파하여 JVM이 비정상 종료되므로 진정한 fail-fast다.
 *
 * <p>권한 누락 시 ERROR 로그에 누락 항목을 열거하고 {@link DbGrantMissingException}을 던진다. 예외가 전파되면 Spring Boot는
 * {@code ApplicationFailedEvent}를 발행하고 종료 코드 1로 JVM을 종료한다.
 *
 * <p>ADR-026 결정 4 — fail-fast(기동 실패) 선택 근거: 권한이 틀린 채 부팅해 기능 일부가 조용히 깨지는 것보다, 기동에서 멈춰 운영자가 즉시 인지하는
 * 편이 안전하다.
 *
 * @see DbGrantVerifier
 * @see DbGrantLoader
 */
@Slf4j
@RequiredArgsConstructor
public class DbGrantCheckRunner implements ApplicationRunner {

    // @MX:ANCHOR: [AUTO] collector 기동 시 DB 권한 검증 진입점
    // @MX:REASON: WatchlistWriter, StockGradePersistService 등 Tier-2 UPDATE 사용자들의 안전망.
    //             fan_in: DbGrantLoader(구현체) + DbGrantVerifier

    private final DbGrantLoader loader;
    private final DbGrantVerifier verifier;

    /**
     * DB 권한을 조회하고 기대 권한 충족 여부를 검증한다.
     *
     * @param args 사용하지 않음
     * @throws DbGrantMissingException 기대 권한 중 하나라도 누락된 경우
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("DB 권한 self-check 시작 (SPEC-INFRA-DBGRANT-001)");

        Set<String> schemaPrivs = loader.loadSchemaPrivileges();
        Set<String> tier2Tables = loader.loadTier2UpdateTables();

        try {
            verifier.verify(schemaPrivs, tier2Tables);
            log.info("DB 권한 self-check 통과 — 모든 기대 권한 확인됨");
        } catch (DbGrantMissingException e) {
            log.error("DB 권한 self-check 실패 — 기동을 중단합니다. {}", e.getMessage());
            throw e;
        }
    }
}
