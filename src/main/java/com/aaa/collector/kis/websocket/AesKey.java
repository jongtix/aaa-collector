package com.aaa.collector.kis.websocket;

/** AES-256-CBC 복호화에 필요한 IV + Key 쌍. */
public record AesKey(String iv, String key) {}
