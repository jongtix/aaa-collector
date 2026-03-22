package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class KisTokenConfigTest {

    private final KisTokenConfig kisTokenConfig = new KisTokenConfig();

    private static final List<KisAccountCredential> DUMMY_ACCOUNTS =
            List.of(new KisAccountCredential("test", "12345678", "appkey", "appsecret"));

    @Test
    @DisplayName("kisRestClient — KisProperties baseUrl로 RestClient가 생성된다 (non-null)")
    void kisRestClient_withValidProperties_returnsNonNullRestClient() {
        KisProperties props =
                new KisProperties("https://openapi.koreainvestment.com", "user", DUMMY_ACCOUNTS);

        RestClient restClient = kisTokenConfig.kisRestClient(RestClient.builder(), props);

        assertThat(restClient).isNotNull();
    }

    @Test
    @DisplayName("lockFactory — 서로 다른 키에 대해 독립적인 Lock 인스턴스를 반환한다")
    void lockFactory_withDifferentKeys_returnsDistinctLockInstances() {
        LockFactory lockFactory = kisTokenConfig.lockFactory();

        var lock1 = lockFactory.create("key-a");
        var lock2 = lockFactory.create("key-b");

        assertThat(lock1).isNotNull();
        assertThat(lock2).isNotNull();
        assertThat(lock1).isNotSameAs(lock2);
    }

    @Test
    @DisplayName("lockFactory — 같은 키로 호출할 때마다 새 Lock 인스턴스를 반환한다")
    void lockFactory_withSameKey_returnsNewLockInstanceEachTime() {
        LockFactory lockFactory = kisTokenConfig.lockFactory();

        var lock1 = lockFactory.create("same-key");
        var lock2 = lockFactory.create("same-key");

        assertThat(lock1).isNotSameAs(lock2);
    }
}
