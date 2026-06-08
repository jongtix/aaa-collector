package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("KisAesDecryptor")
class KisAesDecryptorTest {

    /** 테스트용 32바이트 AES-256 키 (임의의 고정값). */
    private static final String TEST_KEY = "12345678901234567890123456789012";

    /** 테스트용 16바이트 IV (임의의 고정값). */
    private static final String TEST_IV = "1234567890123456";

    /** 테스트 벡터 생성 헬퍼: AES-256-CBC로 평문을 암호화하여 Base64 문자열 반환. */
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    private static String encryptForTest(String plaintext, String key, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    @Nested
    @DisplayName("decrypt — 정상 복호화")
    class NormalDecryption {

        @Test
        @DisplayName("AES-256-CBC 암호화된 데이터를 정확히 복호화한다")
        void decryptsCorrectly() throws Exception {
            // Arrange
            String original = "테스트 데이터|100|200";
            String base64Cipher = encryptForTest(original, TEST_KEY, TEST_IV);

            // Act
            String result = KisAesDecryptor.decrypt(base64Cipher, TEST_KEY, TEST_IV);

            // Assert
            assertThat(result).isEqualTo(original);
        }

        @Test
        @DisplayName("파이프 구분 KIS 실시간 데이터 형식 복호화 — 라운드트립 성공")
        void decryptsKisStylePipeDelimitedData() throws Exception {
            // Arrange
            String kisStyleData = "005930^72500^72600^72400^1234567^8";
            String base64Cipher = encryptForTest(kisStyleData, TEST_KEY, TEST_IV);

            // Act
            String result = KisAesDecryptor.decrypt(base64Cipher, TEST_KEY, TEST_IV);

            // Assert
            assertThat(result).isEqualTo(kisStyleData);
        }

        @Test
        @DisplayName("ASCII 영문 데이터 복호화 — 라운드트립 성공")
        void decryptsAsciiData() throws Exception {
            // Arrange
            String original = "AAPL^150.25^150.50^149.80^5000000^2";
            String base64Cipher = encryptForTest(original, TEST_KEY, TEST_IV);

            // Act
            String result = KisAesDecryptor.decrypt(base64Cipher, TEST_KEY, TEST_IV);

            // Assert
            assertThat(result).isEqualTo(original);
        }
    }
}
