-- ROLLBACK_SAFE: true
-- 이유: 신규 테이블 생성(CREATE TABLE). 기존 테이블·엔티티에 영향 없음. 롤백(테이블 삭제) 시에도 다른
--       스키마와 독립적이므로 데이터 정합성 문제가 없다. analyzer는 이 테이블에 대응하는 collector JPA
--       엔티티를 갖지 않으므로 Hibernate ddl-auto=validate 검증 범위 밖이다(REQ-ASCH-025).
--
-- SPEC-ANALYZER-SCHEMA-001 (M1, REQ-ASCH-001~010):
-- aaa-analyzer(Phase 2 ML 추론 서비스)의 종목·거래일·horizon(D20/D60)별 앙상블 추론 결과를 저장한다.
-- ADR-033(2026-07-05 개정, 회귀 전환 + 가격 밴드 스윕)이 컬럼 확정의 최종 소스이며, 구 [D-8] 초안의
-- ENUM 타입 `lgbm_signal`/`xgb_signal` 등은 이 SPEC이 채택하지 않는다.
--
-- 이 레포 최초의 non-collector 접두사(`analyzer_`) 마이그레이션이다 — 기존 42개는 전부 `collector_`
-- 접두사이며, 이는 선례를 따르는 것이 아니라 선례를 만드는 것이다(REQ-ASCH-022).
CREATE TABLE trading_signals
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '대리 키 (REQ-ASCH-001)',
    stock_id           BIGINT       NOT NULL COMMENT 'stocks.id FK — 추론 대상 종목 (REQ-ASCH-001, -010)',
    trade_date         DATE         NOT NULL COMMENT '거래일 (REQ-ASCH-001)',
    horizon            VARCHAR(3)   NOT NULL COMMENT '추론 기간 — D20/D60. MySQL ENUM 미사용, 허용값은 COMMENT로만 명시 (REQ-ASCH-002)',
    score              DECIMAL(8,6) NOT NULL COMMENT '앙상블 기대수익률(부호 있는 소수, 예: 0.043000 = +4.3%) (REQ-ASCH-003)',
    p10                DECIMAL(8,6) NOT NULL COMMENT 'LightGBM 분위수 보조 모델 예측구간 하한(10%) (REQ-ASCH-003)',
    p90                DECIMAL(8,6) NOT NULL COMMENT 'LightGBM 분위수 보조 모델 예측구간 상한(90%) (REQ-ASCH-003)',
    lgbm_score         DECIMAL(8,6) NOT NULL COMMENT 'LightGBM 단일 모델 점수 (REQ-ASCH-001)',
    xgb_score          DECIMAL(8,6) NOT NULL COMMENT 'XGBoost 단일 모델 점수 (REQ-ASCH-001)',
    signal_class       VARCHAR(11)  NOT NULL COMMENT '삽입 시점 경계표 기준 5클래스 등급 스냅샷(STRONG_BUY/BUY/HOLD/SELL/STRONG_SELL). 경계표 개정에도 소급 재계산되지 않음 — score가 재해석 기준 (REQ-ASCH-004, -005)',
    confidence         DECIMAL(4,3) NOT NULL COMMENT '[0.500, 1.000] 범위 신뢰도 — Φ(|score|/σ), σ=(p90-p10)/2.563 (REQ-ASCH-006)',
    regime_suppressed  BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '체제 감지 발동 시 true. score/lgbm_score/xgb_score 원값은 억제 이전 값 그대로 저장 (REQ-ASCH-007)',
    model_version      VARCHAR(64)  NOT NULL COMMENT '{market}_{horizon}_{algo}_{trained_date} 형식 자유 텍스트. FK 아님 — 모델은 파일시스템 버전 관리 대상 (REQ-ASCH-008)',
    created_at         DATETIME     NOT NULL COMMENT '삽입 시각(애플리케이션 레벨 기록, DDL DEFAULT 없음)',
    updated_at         DATETIME     NOT NULL COMMENT '갱신 시각(애플리케이션 레벨 기록, DDL DEFAULT 없음) — 단 REQ-ASCH-009 INSERT-ONLY 시맨틱상 실질 갱신은 발생하지 않는다',
    UNIQUE KEY uk_trading_signals (stock_id, trade_date, horizon),
    CONSTRAINT fk_trading_signals_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    KEY idx_trading_signals_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT ='analyzer 앙상블 추론 신호 — 종목·거래일·horizon별 INSERT-ONLY 저장 (SPEC-ANALYZER-SCHEMA-001)';
