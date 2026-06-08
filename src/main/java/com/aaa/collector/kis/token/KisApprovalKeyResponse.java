package com.aaa.collector.kis.token;

import com.fasterxml.jackson.annotation.JsonProperty;

/** KIS /oauth2/Approval 응답. */
public record KisApprovalKeyResponse(@JsonProperty("approval_key") String approvalKey) {}
