package com.aaa.collector.kis.websocket;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-CBC 복호화 유틸리티. */
public final class KisAesDecryptor {

    private KisAesDecryptor() {}

    /**
     * AES-256-CBC 복호화.
     *
     * @param base64Cipher Base64 인코딩된 암호문 (KIS Type A data 필드)
     * @param key 32바이트 AES 키 문자열 (output.key)
     * @param iv 16바이트 초기화 벡터 문자열 (output.iv)
     * @return 복호화된 평문 (UTF-8)
     * @throws IllegalArgumentException 복호화 실패 시
     */
    public static String decrypt(String base64Cipher, String key, String iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(base64Cipher));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("AES 복호화 실패", e);
        }
    }
}
