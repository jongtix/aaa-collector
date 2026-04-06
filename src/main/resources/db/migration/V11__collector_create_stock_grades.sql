CREATE TABLE stock_grades (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    stock_id    BIGINT      NOT NULL,
    grade       VARCHAR(1)  NOT NULL COMMENT '종목 등급: A/B/C/F',
    graded_at   DATETIME    NOT NULL COMMENT '등급 산정 일시',
    created_at  DATETIME    NOT NULL,
    updated_at  DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_grades_stock (stock_id),
    CONSTRAINT fk_stock_grades_stock FOREIGN KEY (stock_id) REFERENCES stocks (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
