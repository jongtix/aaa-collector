-- ROLLBACK_SAFE: true
-- 이유: 신규 테이블 생성(CREATE TABLE). 기존 테이블·엔티티에 영향 없음. 롤백(테이블 삭제) 시에도 다른
--       스키마와 독립적이므로 데이터 정합성 문제가 없다. collector는 이 테이블에 대응하는 JPA 엔티티를
--       갖지 않으므로 Hibernate ddl-auto=validate 검증 범위 밖이다(REQ-NOTIFIER-SCHEMA-012).
--
-- SPEC-NOTIFIER-SCHEMA-001 (M1, REQ-NOTIFIER-SCHEMA-001~007, 011~012):
-- aaa-notifier(Phase 3 알림 서비스)의 텔레그램 발송 사건을 이벤트 소싱식(INSERT-ONLY)으로 저장한다.
-- 1행 = 발송 사건 1건이며, 같은 알림의 이력은 trace_id로 연결한다. 갱신형 send_status 컬럼을 두지
-- 않는 것은 INSERT-ONLY 시맨틱과의 구조 정합이자 텔레그램 장애 구간을 감사 가능하게 만들기 위함이다
-- (REQ-NOTIFIER-SCHEMA-002).
--
-- 전환 전 유효 등급이나 교차 경계가 전용 컬럼은 의도적으로 두지 않는다 — trigger_price(틱 발동 가격),
-- prev_close(전일 종가), signal_class(발동 시점 유효 등급)가 이미 필터 오작동 검증에 필요한 증거를
-- 제공하며, 필요가 실증되면 후행 additive ALTER로 언제든 도입 가능하다(REQ-NOTIFIER-SCHEMA-006).
--
-- 이 레포 최초의 `notifier_` 접두사 마이그레이션이다 — 기존 45개(V1~V48, V15·V16·V30 결번)는 전부
-- `collector_`/`analyzer_` 접두사다. ADR-016 결정 2의 중앙집중 Flyway 소유 구조(collector가 전 서비스분
-- DDL을 단독 소유)에 따라 notifier는 자체 마이그레이션을 갖지 않는다(REQ-NOTIFIER-SCHEMA-011).
--
-- notifier 계정의 테이블 단위 쓰기 권한 부여는 이 파일의 관심사가 아니다(aaa-infra 소관). MySQL 8.4는
-- 존재하지 않는 테이블에 대한 테이블 단위 권한 부여를 ERROR 1146으로 거부하므로, 그 적용은 반드시 이
-- 마이그레이션 배포 이후여야 한다(REQ-NOTIFIER-SCHEMA-022).
CREATE TABLE notification_log
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '대리 키 (REQ-NOTIFIER-SCHEMA-001, -007)',
    event_type          VARCHAR(16)   NOT NULL COMMENT '발송 사건 종류 — SENT/SEND_FAILED/QUEUED/SUMMARY_SENT. MySQL ENUM 미사용, 허용값은 COMMENT로만 명시. 드라이런 도입 시 DRYRUN 확장 여지를 남긴다 (REQ-NOTIFIER-SCHEMA-002)',
    notification_type   VARCHAR(16)   NOT NULL COMMENT '알림 종류 — TIMING/AGGREGATE/DAILY_REPORT/QUEUE_SUMMARY. MySQL ENUM 미사용, 허용값은 COMMENT로만 명시. 시간외 알림용 EXTENDED_HOURS 확장은 후속 SPEC 시점 (REQ-NOTIFIER-SCHEMA-003)',
    stock_id            BIGINT        NULL COMMENT 'stocks.id FK — 알림 대상 종목. 일일 리포트·큐 요약 등 특정 종목에 결부되지 않는 알림은 NULL (REQ-NOTIFIER-SCHEMA-005)',
    horizon             VARCHAR(3)    NULL COMMENT '추론 기간 — D20/D60. MySQL ENUM 미사용, 허용값은 COMMENT로만 명시. 종목 무관 알림은 NULL (REQ-NOTIFIER-SCHEMA-004)',
    tier                TINYINT       NULL COMMENT '알림 우선순위 티어. 종목 무관 알림은 NULL (REQ-NOTIFIER-SCHEMA-001)',
    signal_class        VARCHAR(11)   NULL COMMENT '발동 시점 유효 등급 5클래스(STRONG_BUY/BUY/HOLD/SELL/STRONG_SELL). 컬럼명이 signal이 아닌 것은 단독 SIGNAL이 MySQL 8.4 예약어(SIGNAL 구문)이기 때문이며, trading_signals/signal_price_bands와 동일 명명을 유지한다. MySQL ENUM 미사용 (REQ-NOTIFIER-SCHEMA-004)',
    score               DECIMAL(8,6)  NULL COMMENT '발동 근거가 된 앙상블 기대수익률(부호 있는 소수, 예: 0.043000 = +4.3%). 종목 무관 알림은 NULL (REQ-NOTIFIER-SCHEMA-001)',
    confidence          DECIMAL(4,3)  NULL COMMENT '[0.500, 1.000] 범위 신뢰도 스냅샷. 종목 무관 알림은 NULL (REQ-NOTIFIER-SCHEMA-001)',
    trigger_price       DECIMAL(18,4) NULL COMMENT '틱 발동 가격 — 필터 오작동 수동 검증의 1차 증거 (REQ-NOTIFIER-SCHEMA-001, -006)',
    prev_close          DECIMAL(18,4) NULL COMMENT '전일 종가 — trigger_price와 대조해 발동 조건 성립 여부를 사후 검증한다 (REQ-NOTIFIER-SCHEMA-001, -006)',
    message_text        TEXT          NULL COMMENT '실제 발송된 텔레그램 메시지 본문 원문 (REQ-NOTIFIER-SCHEMA-001)',
    telegram_message_id BIGINT        NULL COMMENT '텔레그램 API가 반환한 message_id. 발송 실패(SEND_FAILED)·큐잉(QUEUED) 시 NULL (REQ-NOTIFIER-SCHEMA-001)',
    trace_id            VARCHAR(64)   NOT NULL COMMENT '같은 알림의 발송 사건 이력을 연결하는 추적 키 — 이벤트 소싱식 구조에서 갱신형 send_status를 대체한다 (REQ-NOTIFIER-SCHEMA-002)',
    trade_date          DATE          NULL COMMENT '거래일. 종목 무관 알림은 NULL (REQ-NOTIFIER-SCHEMA-001, -007)',
    created_at          DATETIME      NOT NULL COMMENT '삽입 시각(애플리케이션 레벨 기록, DDL DEFAULT 없음). INSERT-ONLY 시맨틱상 갱신 시각 컬럼은 두지 않는다 (REQ-NOTIFIER-SCHEMA-002)',
    CONSTRAINT fk_notification_log_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    KEY idx_notification_log_stock_trade_date (stock_id, trade_date),
    KEY idx_notification_log_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT ='notifier 텔레그램 발송 사건 이력 — 이벤트 소싱식 INSERT-ONLY 저장 (SPEC-NOTIFIER-SCHEMA-001)';
