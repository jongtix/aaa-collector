-- CR-001 (SPEC-COLLECTOR-GRADE-001): StockGrade.gradedAt 타입 변경 (LocalDateTime → ZonedDateTime)
-- NORMALIZE_UTC 전략과 호환되도록 DATETIME → TIMESTAMP로 변경.
-- 기존 DATETIME 값은 KST 기준으로 저장된 데이터가 없으므로 별도 데이터 보정 불필요.
ALTER TABLE stock_grades
    MODIFY COLUMN graded_at TIMESTAMP NOT NULL COMMENT '등급 산정 일시';
