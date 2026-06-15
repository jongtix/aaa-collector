package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.enums.AssetType;
import org.springframework.stereotype.Component;

/**
 * 종목 등급 분류 규칙 엔진. Stateless — thread-safe (REQ-013).
 *
 * <p>적용 순서:
 *
 * <ol>
 *   <li>F: nameKo에 "TDF" 또는 "액티브" 포함
 *   <li>C: ETF (대표 선정 SPEC 적용 전, REQ-007)
 *   <li>A: 상장 7년 이상 AND 백분위 &le; 20 (상위 20%)
 *   <li>B: 상장 3년 이상 7년 미만 AND 백분위 &gt; 20 AND 백분위 &le; 60 (상위 20%~60%)
 *   <li>C: 나머지 (REQ-014 포함 — 7년 이상이지만 상위 20% 미충족 시 C)
 * </ol>
 */
@Component
public class GradeClassifier {

    /**
     * GradeInput을 받아 등급을 반환한다.
     *
     * @param input 등급 분류 입력 데이터
     * @return 분류된 등급
     */
    // @MX:NOTE: [AUTO] 평가 순서 — F > ETF-C > A(7년+상위20%) > B(3~7년+20~60%) > C(나머지). REQ-014: 7년 이상이라도
    // 상위 20% 미충족 시 B 후보 아님 → C
    public Grade classify(GradeInput input) {
        // 1. F 우선 적용
        if (isFGrade(input)) {
            return Grade.F;
        }

        // 2. ETF 기본 C (대표 선정 SPEC 미도입 전)
        if (input.assetType() == AssetType.ETF) {
            return Grade.C;
        }

        // 3. A: 상장 7년 이상 AND 상위 20%
        if (input.listedYears() >= GradeConstants.ESTABLISHED_YEARS_THRESHOLD
                && input.percentile() <= 20.0) {
            return Grade.A;
        }

        // 4. B: 상장 3년 이상 7년 미만 AND 상위 20%~60%
        if (input.listedYears() >= 3.0
                && input.listedYears() < GradeConstants.ESTABLISHED_YEARS_THRESHOLD
                && input.percentile() > 20.0
                && input.percentile() <= 60.0) {
            return Grade.B;
        }

        // 5. C: 나머지 (REQ-014: 7년 이상이지만 상위 20% 미충족 포함)
        return Grade.C;
    }

    private boolean isFGrade(GradeInput input) {
        String nameKo = input.nameKo();
        if (nameKo == null) {
            return false;
        }
        return nameKo.contains("TDF") || nameKo.contains("액티브");
    }
}
