package com.aaa.collector.stock.shortsale.overseas.backfill;

import java.util.List;

/**
 * FINRA CDN 하루치 파일 취득 결과 (SPEC-COLLECTOR-BACKFILL-008 T2, REQ-BACKFILL-100~104/115/115a).
 *
 * <p>{@link Found}는 그날 HTTP 200으로 존재한 파일 본문 목록(CNMS 단독 1건 또는 시설 다건)을 담는다. {@link Absent}는 CNMS·모든
 * 시설 파일이 부재(403/404)일 때의 결과이며, {@link AbsenceReason}으로 403(floor 이전 결측)과 404(주말·휴장·미생성)를
 * 구분한다(AC-BF-16/-17) — 이 구분은 관측성 목적이며 종료 신호로 해석하지 않는다(REQ-BACKFILL-115a, floor 도달만 유일 종료).
 */
public sealed interface FinraCdnFetchResult {

    /** 그날 존재하는 파일 본문 목록. CNMS 200이면 단일 원소, 시설 폴백이면 200인 시설 파일들. */
    record Found(List<String> fileBodies) implements FinraCdnFetchResult {

        /** 방어적 불변 복사 — 외부 mutable 리스트 참조 노출 방지(SpotBugs EI_EXPOSE_REP/REP2). */
        public Found {
            fileBodies = List.copyOf(fileBodies);
        }
    }

    /** CNMS·전 시설 파일 부재. {@code reason}은 CNMS 응답 상태 기준(403/404) 관측성 분류다. */
    record Absent(AbsenceReason reason) implements FinraCdnFetchResult {}

    /** 파일 부재 사유 분류. */
    enum AbsenceReason {
        /** CNMS 403 — floor(2009-08-03) 이전 결측으로 추정. */
        FLOOR_BEFORE_403,
        /** CNMS 404 — 주말·휴장·미생성으로 추정. */
        NOT_GENERATED_404
    }
}
