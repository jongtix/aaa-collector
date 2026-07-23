-- ROLLBACK_SAFE: true
-- 이유: 신규 테이블 생성(CREATE TABLE). 기존 테이블·엔티티에 영향 없음. 롤백(테이블 삭제) 시에도 다른
--       스키마와 독립적이므로 데이터 정합성 문제가 없다.
--
-- SPEC-COLLECTOR-CALENDAR-001 (TASK-001, REQ-CAL-001/-002/-049):
-- 시장 개장일/휴장일 캘린더를 영속화한다. "휴장일만 저장"하지 않고, 시딩·갱신된 범위 내 개장·휴장
-- 불문 모든 날짜에 정확히 1행을 유지한다(REQ-CAL-001) — DELETE 없이 UPDATE만으로 정정을 표현할 수
-- 있게 하기 위함이다(REQ-CAL-005, ADR-026 — collector 계정은 DELETE 권한이 없음).
--
-- source 우선순위(REQ-CAL-003/004): MANUAL > KIS_API > ALGORITHM > DERIVED. 자동 갱신 경로(일일 배치)는
-- 신규 값의 source 우선순위가 기존 행보다 낮으면 절대 덮어쓰지 않는다 — 운영자 수동 정정(MANUAL)이 다음날
-- 자동 갱신으로 되돌아가지 않는다.
--
-- calendar_code(REQ-CAL-001): KRX(코스피·코스닥 공유)/NYSE — stock.enums.Market과 의도적으로 분리된
-- 캘린더 도메인 코드다(조립 단위가 다름).
--
-- 스키마 변경과 데이터 산출(초기 시딩 SQL)은 별개 관심사다(REQ-CAL-049) — 이 DDL은 시딩 도구(TASK-012)
-- 코드와 별도 커밋으로 존재한다.
CREATE TABLE market_calendar
(
    calendar_code VARCHAR(8)  NOT NULL COMMENT '캘린더 도메인 코드 — KRX(코스피+코스닥 공유)/NYSE. stock.enums.Market과 별개 단위 (REQ-CAL-001)',
    cal_date      DATE        NOT NULL COMMENT '판정 대상 날짜. 시딩·갱신 범위 내 개장·휴장 불문 모든 날짜에 존재 (REQ-CAL-001)',
    is_open       BOOLEAN     NOT NULL COMMENT '개장 여부 — true=개장, false=휴장 (REQ-CAL-002)',
    source        VARCHAR(16) NOT NULL COMMENT '값 출처. 우선순위 MANUAL > KIS_API > ALGORITHM > DERIVED — 자동 갱신은 낮은 우선순위로 기존 값을 덮어쓰지 않는다 (REQ-CAL-003/004)',
    created_at    DATETIME    NOT NULL,
    updated_at    DATETIME    NOT NULL,
    PRIMARY KEY (calendar_code, cal_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT ='시장 개장일/휴장일 캘린더 — KRX/NYSE 전체 일자 영속화 (SPEC-COLLECTOR-CALENDAR-001)';
