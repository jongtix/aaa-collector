package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.enums.AssetType;
import org.springframework.stereotype.Component;

/**
 * 종목 등급 분류 규칙 엔진. Stateless — thread-safe (REQ-013).
 *
 * <p>적용 순서:
 *
 * <ol>
 *   <li>F: nameKo에 "TDF" 또는 "액티브" 포함 (최우선)
 *   <li>C: ETF (대표 선정 서비스에서 A/B 재판정, 여기서는 비대표 ETF 처리)
 *   <li>A: holdingDays &ge; 750 AND ADTV &ge; HIGH (시장별 임계값)
 *   <li>B: holdingDays &ge; 250 AND LOW &le; ADTV &lt; HIGH
 *   <li>C: 나머지
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
    // @MX:NOTE: [AUTO] 평가 순서 — F > ETF-C > A(holdingDays≥750+ADTV≥HIGH) >
    // B(holdingDays≥250+LOW≤ADTV<HIGH) > C
    public Grade classify(GradeInput input) {
        // 1. F 우선 적용
        if (isFGrade(input)) {
            return Grade.F;
        }

        // 2. ETF 기본 C (EtfRepresentativeService에서 대표 ETF A/B 재판정)
        if (input.assetType() == AssetType.ETF) {
            return Grade.C;
        }

        double high = GradeConstants.getHighThreshold(input.market());
        double low = GradeConstants.getLowThreshold(input.market());

        // 3. A: holdingDays ≥ 750 AND ADTV ≥ HIGH
        if (input.holdingDays() >= GradeConstants.HOLDING_DAYS_A && input.adtv() >= high) {
            return Grade.A;
        }

        // 4. B: holdingDays ≥ 250 AND LOW ≤ ADTV < HIGH
        if (input.holdingDays() >= GradeConstants.HOLDING_DAYS_B
                && input.adtv() >= low
                && input.adtv() < high) {
            return Grade.B;
        }

        // 5. C: 나머지
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
