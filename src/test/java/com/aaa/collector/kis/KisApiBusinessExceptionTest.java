package com.aaa.collector.kis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KisApiBusinessException 단위 테스트")
class KisApiBusinessExceptionTest {

    @Test
    @DisplayName("getMsgCd 접근자는 생성자에 전달된 msg_cd를 그대로 반환한다")
    void getMsgCd_returnsGivenCode() {
        KisApiBusinessException ex = new KisApiBusinessException("1", "OPSQ0008", "후처리 오류");

        assertThat(ex.getMsgCd()).isEqualTo("OPSQ0008");
    }

    @Test
    @DisplayName("메시지 포맷은 기존과 동일하다")
    void message_formatUnchanged() {
        KisApiBusinessException ex = new KisApiBusinessException("1", "OPSQ0008", "후처리 오류");

        assertThat(ex).hasMessage("KIS API 비즈니스 오류 — rt_cd=1, msg_cd=OPSQ0008, msg1=후처리 오류");
    }
}
