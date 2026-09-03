-- ROLLBACK_SAFE: false
-- 이유: NOT NULL → NULL 허용 완화(MODIFY COLUMN)는 컬럼 제약을 느슨하게 하는 방향이라 그 자체로는
--       기존 행의 데이터 손실이 없다. 다만 롤백(다시 NOT NULL로 되돌리는 것)은 이 마이그레이션 적용
--       이후 실제로 NULL 값이 INSERT된 행이 하나라도 있으면 실패한다(NULL 값이 남아있는 상태에서
--       NOT NULL 제약을 다시 걸 수 없음) — 애플리케이션 동작(analyzer INFER-001)이 이 완화를 전제로
--       NULL을 실제로 쓰기 시작하므로, 편도(one-way) 마이그레이션으로 취급한다.
--
-- SPEC-ANALYZER-INFER-001 (G3, REQ-AIF-040/041):
-- 단독 전략(xgboost 또는 lightgbm 하나만) 챔피언이 배포된 (market, horizon) 조합에서는 앙상블에
-- 참여하지 않은 알고리즘의 점수(lgbm_score) 및 그 알고리즘의 분위수 보조 모델 예측구간(p10/p90)을
-- 채울 값 자체가 존재하지 않는다. V43(SPEC-ANALYZER-SCHEMA-001)이 이 3개 컬럼을 NOT NULL로
-- 확정했을 때는 이 케이스가 식별되지 않았다 — 2026-08-19 캠페인 기준 라이브 챔피언 4개 조합 중 3개가
-- 이미 단독 전략(xgboost)이므로, 이 마이그레이션 없이는 INFER-001의 INSERT 경로 자체가 성립하지
-- 않는다(2026-09-03 사용자 결정 — sentinel 0 채움이나 score 복제 대신 데이터 의미상 정확한 NULL
-- 허용을 채택).
--
-- score/xgb_score/signal_class/confidence 등 다른 컬럼은 단독 전략 여부와 무관하게 항상 채워지므로
-- 변경 대상이 아니다.
ALTER TABLE trading_signals
    MODIFY COLUMN lgbm_score DECIMAL(8,6) NULL COMMENT 'LightGBM 단일 모델 점수. 단독 전략(xgboost만) 챔피언 배포 시 NULL(REQ-ASCH-001; NULL 허용은 SPEC-ANALYZER-INFER-001 G3)',
    MODIFY COLUMN p10 DECIMAL(8,6) NULL COMMENT 'LightGBM 분위수 보조 모델 예측구간 하한(10%). 단독 전략(xgboost만) 챔피언 배포 시 또는 분위수 모델 미배포 시 NULL(REQ-ASCH-003; NULL 허용은 SPEC-ANALYZER-INFER-001 G3)',
    MODIFY COLUMN p90 DECIMAL(8,6) NULL COMMENT 'LightGBM 분위수 보조 모델 예측구간 상한(90%). 단독 전략(xgboost만) 챔피언 배포 시 또는 분위수 모델 미배포 시 NULL(REQ-ASCH-003; NULL 허용은 SPEC-ANALYZER-INFER-001 G3)';
