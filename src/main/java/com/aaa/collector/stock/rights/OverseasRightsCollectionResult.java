package com.aaa.collector.stock.rights;

/**
 * 해외 현금배당 수집 집계 결과 (SPEC-COLLECTOR-OVERSEAS-ETC-001, SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001).
 *
 * @param attemptedStocks 조회를 시도한 종목 수
 * @param succeededRows {@code corporate_events}에 멱등 삽입을 시도한(저장 대상) 현금배당 행 수
 * @param skippedStocks 빈 응답·전 키 사망 등으로 종목 단위 skip된 수
 * @param skippedNonCashRows 비현금배당(증자·상폐 등)이라 저장하지 않고 skip한 행 수 (REQ-OVE-023a)
 * @param skippedValidationRows 필수 날짜 null·파싱 실패로 skip한 행 수
 * @param skippedToxicRows DB 저장 시 {@code DataAccessException}을 흡수하고 skip한 독성 행 수 (W1,
 *     42,460-failure 사건군)
 * @param skippedUnconfirmed CTRGT011R 금액 맵(03/75)에 확정 매칭이 없어(예정이거나 미반환) 행 생성을 defer한 수
 *     (REQ-ODA-022). 프리페치 유형 폐기로 인한 defer는 이중 집계하지 않는다(D5) — {@link #prefetchTruncated}/{@link
 *     #prefetchFailed}만으로 관측. 스크립배당(74) 확정 매칭으로 인한 defer는 {@link #skippedScripDividend}로 별도 집계되며 이
 *     카운터에는 섞이지 않는다(aaa-infra#64).
 * @param skippedScripDividend 권리유형 74(스크립/주식배당) 확정 매칭으로 defer된 행 수 (aaa-infra#64, REQ-ODA-072). 74는
 *     03/75와 달리 다음 배치에도 영원히 확정될 수 없는 미저장 유형이므로 {@link #skippedUnconfirmed}와 분리 집계한다 — "곧 확정될 것"
 *     카운터를 오염시키지 않기 위함.
 * @param prefetchTruncated CTRGT011R 프리페치가 MAX_PAGES 절단으로 fail-closed 폐기된 권리유형(03/75/74) 수
 *     (REQ-ODA-012, 0~3)
 * @param prefetchFailed CTRGT011R 프리페치 페이징 도중 재시도 소진으로 fail-closed 폐기된 권리유형(03/75/74) 수
 *     (REQ-ODA-062, 0~3)
 */
public record OverseasRightsCollectionResult(
        int attemptedStocks,
        int succeededRows,
        int skippedStocks,
        int skippedNonCashRows,
        int skippedValidationRows,
        int skippedToxicRows,
        int skippedUnconfirmed,
        int skippedScripDividend,
        int prefetchTruncated,
        int prefetchFailed) {}
