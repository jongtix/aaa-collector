package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KisTokenConfigTest {

    private final KisTokenConfig kisTokenConfig = new KisTokenConfig();

    @Test
    @DisplayName("lockFactory — 서로 다른 키에 대해 독립적인 Lock 인스턴스를 반환한다")
    void lockFactory_withDifferentKeys_returnsDistinctLockInstances() {
        LockFactory lockFactory = kisTokenConfig.lockFactory();

        Lock lock1 = lockFactory.create("key-a");
        Lock lock2 = lockFactory.create("key-b");

        assertThat(lock1).isNotNull();
        assertThat(lock2).isNotNull();
        assertThat(lock1).isNotSameAs(lock2);
    }

    @Test
    @DisplayName("lockFactory — 같은 키로 호출할 때마다 새 Lock 인스턴스를 반환한다")
    void lockFactory_withSameKey_returnsNewLockInstanceEachTime() {
        LockFactory lockFactory = kisTokenConfig.lockFactory();

        Lock lock1 = lockFactory.create("same-key");
        Lock lock2 = lockFactory.create("same-key");

        assertThat(lock1).isNotSameAs(lock2);
    }
}
