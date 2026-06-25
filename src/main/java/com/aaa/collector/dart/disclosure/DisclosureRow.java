package com.aaa.collector.dart.disclosure;

import java.time.LocalDate;

/**
 * DART 공시 단건 삽입 파라미터 객체 (SPEC-COLLECTOR-DART-001).
 *
 * <p>{@link DisclosureRepository#insertIgnore(DisclosureRow)}에 전달한다. 다수 String 파라미터의 의미를 명시적으로 전달하기
 * 위해 record로 표현한다.
 */
public record DisclosureRow(
        Long stockId,
        String corpCode,
        String stockCode,
        String corpCls,
        String reportNm,
        String rceptNo,
        String flrNm,
        LocalDate rceptDt,
        String rm,
        String pblntfTy) {}
