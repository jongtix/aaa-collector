-- ROLLBACK_SAFE: true
-- 이유: 신규 테이블 생성(CREATE TABLE). 기존 테이블·엔티티에 영향 없음. 롤백(테이블 삭제) 시에도 다른
--       스키마와 독립적이므로 데이터 정합성 문제가 없다. analyzer는 이 테이블에 대응하는 collector JPA
--       엔티티를 갖지 않으므로 Hibernate ddl-auto=validate 검증 범위 밖이다(REQ-ASCH-025).
--
-- SPEC-ANALYZER-SCHEMA-001 (M1, REQ-ASCH-011~020, ADR-033 §6.6):
-- 마감 후 배치가 사전 계산한 가격 파티션을 저장한다 — 장중 등급 판정을 O(1) 가격 범위 비교로
-- 수행할 수 있게 한다. 이원 경계 히스테리시스 설계(PROMOTE/DEMOTE)에 따라 boundary_set당
-- band_seq 오름차순 가격 파티션을 갖는다.
--
-- signal_class 컬럼명(REQ-ASCH-013): trading_signals.signal_class와 동일 5클래스 등급 도메인이며, 두
-- 테이블 간 컬럼명·타입을 일치시키기 위해 의도적으로 `grade`가 아닌 `signal_class`로 명명한다(단독
-- `signal`은 MySQL 8.4 예약어(SIGNAL 구문)라 회피). TECHSPEC §6.6의
-- `grade` 표기는 이 SPEC의 문서 정정(REQ-ASCH-053, M4)이 별도로 바로잡는다.
--
-- DELETE/파티셔닝/TTL 미구현(REQ-ASCH-015): analyzer 계정에 DELETE 권한을 부여하지 않는다는
-- TECHSPEC §4 원칙과 정합 — 무기한 보관을 전제로 하며, 운영 부담이 실증되면 별도 아웃오브밴드
-- 절차로 후속 검토한다(이 SPEC의 범위 밖).
CREATE TABLE signal_price_bands
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '대리 키 (REQ-ASCH-011)',
    stock_id           BIGINT       NOT NULL COMMENT 'stocks.id FK — 밴드 대상 종목 (REQ-ASCH-011, -014)',
    trade_date         DATE         NOT NULL COMMENT '기준 일봉 날짜 — 밴드는 익거래일 장중에 적용 (REQ-ASCH-011)',
    horizon            VARCHAR(3)   NOT NULL COMMENT '추론 기간 — D20/D60. MySQL ENUM 미사용 (REQ-ASCH-011)',
    boundary_set       VARCHAR(7)   NOT NULL COMMENT '이원 경계 히스테리시스 세트 — PROMOTE(승급 경계)/DEMOTE(강등 경계) 두 값만 허용 (REQ-ASCH-012)',
    band_seq           INT          NOT NULL COMMENT '(stock_id, trade_date, horizon, boundary_set) 그룹 내 가격 오름차순 파티션 순번 (REQ-ASCH-012)',
    price_low          DECIMAL(18,4) NOT NULL COMMENT '파티션 하한가 (REQ-ASCH-011)',
    price_high         DECIMAL(18,4) NOT NULL COMMENT '파티션 상한가 (REQ-ASCH-011)',
    signal_class       VARCHAR(11)  NOT NULL COMMENT 'trading_signals.signal_class와 동일 5클래스 등급 도메인 — 컬럼명 정합을 위해 의도적으로 grade가 아닌 signal_class (REQ-ASCH-013)',
    regime_suppressed  BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '체제 감지 발동 시 true (REQ-ASCH-011, trading_signals.regime_suppressed와 동일 의미)',
    model_version      VARCHAR(64)  NOT NULL COMMENT '{market}_{horizon}_{algo}_{trained_date} 형식 자유 텍스트. FK 아님 (REQ-ASCH-011, -008 참조)',
    created_at         DATETIME     NOT NULL COMMENT '삽입 시각(애플리케이션 레벨 기록, DDL DEFAULT 없음)',
    UNIQUE KEY uk_signal_price_bands (stock_id, trade_date, horizon, boundary_set, band_seq),
    CONSTRAINT fk_signal_price_bands_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    KEY idx_signal_price_bands_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT ='analyzer 가격 밴드 스윕 — 장중 O(1) 등급 판정용 사전 계산 가격 파티션 (SPEC-ANALYZER-SCHEMA-001)';
