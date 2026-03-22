package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SafeModeManagerTest {

    @Mock private SafeModeRepository safeModeRepository;

    @InjectMocks private SafeModeManager safeModeManager;

    @Test
    @DisplayName("enter — safeModeRepository.setSafeMode(alias, true) 호출")
    void enter_callsSetSafeModeWithTrue() {
        String alias = "test-alias";
        Throwable cause = new RuntimeException("token issue");

        safeModeManager.enter(alias, cause);

        verify(safeModeRepository).setSafeMode(alias, true);
    }

    @Test
    @DisplayName("exit — safeModeRepository.setSafeMode(alias, false) 호출")
    void exit_callsSetSafeModeWithFalse() {
        String alias = "test-alias";

        safeModeManager.exit(alias);

        verify(safeModeRepository).setSafeMode(alias, false);
    }

    @Test
    @DisplayName("isActive — safeModeRepository.isSafeMode(alias)가 true이면 true 반환")
    void isActive_returnsTrueWhenRepositoryReturnsTrue() {
        String alias = "test-alias";
        when(safeModeRepository.isSafeMode(alias)).thenReturn(true);

        boolean result = safeModeManager.isActive(alias);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isActive — safeModeRepository.isSafeMode(alias)가 false이면 false 반환")
    void isActive_returnsFalseWhenRepositoryReturnsFalse() {
        String alias = "test-alias";
        when(safeModeRepository.isSafeMode(alias)).thenReturn(false);

        boolean result = safeModeManager.isActive(alias);

        assertThat(result).isFalse();
    }
}
