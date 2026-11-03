-- ROLLBACK_SAFE: true
-- 이유: 신규 테이블 생성(CREATE TABLE). 기존 테이블·엔티티에 영향 없음. 롤백(테이블 삭제) 시에도 다른
--       스키마와 독립적이므로 데이터 정합성 문제가 없다. analyzer는 이 테이블에 대응하는 collector JPA
--       엔티티를 갖지 않으므로 Hibernate ddl-auto=validate 검증 범위 밖이다(signal_price_bands/
--       trading_signals와 동일 선례).
--
-- SPEC-SIGNAL-PRICECURVE-001 (M1, REQ-001~003, -016):
-- 밴드 스윕이 산출하는 그리드별 score(grid_scores)를 폐기하지 않고 전량 JSON 커브로 저장한다 —
-- notifier가 발동 체결가에서 표시 score를 선형 보간할 수 있게 한다(plan.md §B-1 결정: 그리드
-- 포인트당 1행이 아니라 (종목,거래일,horizon)당 1행의 JSON 커브 컬럼 — signal_price_bands 동형의
-- 포인트당-행 방식은 10~15배 저장량 증가로 기각됨).
--
-- boundary_set 컬럼을 두지 않는다(plan.md §B-2 결정): grid_scores는 evaluate(grid) 1회 평가로
-- PROMOTE/DEMOTE 양쪽에 공유되므로 (stock_id, trade_date, horizon) UNIQUE 키 하나로 충분하다 —
-- boundary_set별 중복 저장은 하지 않는다(REQ-002).
--
-- regime_suppressed 컬럼을 두지 않는다: 커브는 항상 억제 전 원값이며(REQ-005), signal_price_bands/
-- trading_signals와 달리 이 테이블에는 "억제된 버전"이 애초에 존재하지 않는다.
CREATE TABLE signal_price_curves
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '대리 키 (REQ-003)',
    stock_id       BIGINT        NOT NULL COMMENT 'stocks.id FK — 커브 대상 종목 (REQ-003)',
    trade_date     DATE          NOT NULL COMMENT '기준 일봉 날짜 (REQ-003)',
    horizon        VARCHAR(3)    NOT NULL COMMENT '추론 기간 — D20/D60. MySQL ENUM 미사용 (REQ-003)',
    prev_close     DECIMAL(18,4) NOT NULL COMMENT '전일 종가 — 그리드 중심(디버그/감사용) (REQ-003)',
    grid_step_pct  DECIMAL(6,5)  NOT NULL COMMENT '이 행이 쓰인 그리드 해상도 — 예: 0.00500 = 0.5% (REQ-003)',
    curve          JSON          NOT NULL COMMENT '[{"price":..,"score":..}, ...] 그리드 오름차순 배열 — 그리드 포인트당 1행이 아니라 (종목,거래일,horizon)당 1행 (REQ-001, -003)',
    model_version  VARCHAR(64)   NOT NULL COMMENT '{market}_{horizon}_{algo}_{trained_date} 형식 자유 텍스트. FK 아님 (signal_price_bands와 동일 관례)',
    created_at     DATETIME      NOT NULL COMMENT '삽입 시각(애플리케이션 레벨 기록, DDL DEFAULT 없음)',
    UNIQUE KEY uk_signal_price_curves (stock_id, trade_date, horizon),
    CONSTRAINT fk_signal_price_curves_stock FOREIGN KEY (stock_id) REFERENCES stocks (id),
    KEY idx_signal_price_curves_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT ='analyzer 밴드-스윕 그리드 score 커브 — notifier 표시 score 보간용 JSON 전체-그리드 저장 (SPEC-SIGNAL-PRICECURVE-001)';
