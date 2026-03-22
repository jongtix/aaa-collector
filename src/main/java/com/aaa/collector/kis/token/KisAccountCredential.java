package com.aaa.collector.kis.token;

import com.aaa.collector.common.logging.LogMaskingUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * KIS Open API 계좌별 자격증명.
 *
 * @param alias 계좌별칭 (isa, gold, pension, stock, dc)
 * @param accountNumber 계좌번호
 * @param appKey 앱키
 * @param appSecret 앱시크릿
 */
public record KisAccountCredential(
        String alias, String accountNumber, String appKey, String appSecret) {

    public KisAccountCredential {
        List<String> errors = new ArrayList<>();

        if (alias == null || alias.isBlank()) {
            errors.add("alias must not be null or blank");
        }
        if (accountNumber == null || accountNumber.isBlank()) {
            errors.add("accountNumber must not be null or blank");
        }
        if (appKey == null || appKey.isBlank()) {
            errors.add("appKey must not be null or blank");
        }
        if (appSecret == null || appSecret.isBlank()) {
            errors.add("appSecret must not be null or blank");
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", errors));
        }
    }

    public String maskedAccountNumber() {
        return LogMaskingUtils.maskBackOnly(accountNumber);
    }

    public String maskedAppKey() {
        return LogMaskingUtils.maskFrontBack(appKey);
    }

    public String maskedAppSecret() {
        return LogMaskingUtils.maskFrontOnly(appSecret);
    }

    @Override
    public String toString() {
        return "KisAccountCredential{alias='%s', accountNumber='%s', appKey='%s', appSecret='%s'}"
                .formatted(alias, maskedAccountNumber(), maskedAppKey(), maskedAppSecret());
    }
}
