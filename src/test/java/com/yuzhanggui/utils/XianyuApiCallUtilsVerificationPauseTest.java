package com.yuzhanggui.utils;

import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.service.WebSocketTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XianyuApiCallUtilsVerificationPauseTest {

    @Test
    void blocksRequestWhileRuntimeVerificationIsPending() {
        XianyuApiCallUtils utils = new XianyuApiCallUtils();
        WebSocketTokenService tokenService = mock(WebSocketTokenService.class);
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        when(tokenService.isCaptchaPending(7L)).thenReturn(true);
        ReflectionTestUtils.setField(utils, "webSocketTokenService", tokenService);
        ReflectionTestUtils.setField(utils, "accountMapper", accountMapper);

        XianyuApiCallUtils.ApiCallResult result = utils.callApiWithRetry(
                7L, "mtop.test", Map.of(), "cookie=value");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("自动请求已暂停"));
    }

    @Test
    void blocksRequestAfterRestartWhenAccountIsPersistedAsVerificationRequired() {
        XianyuApiCallUtils utils = new XianyuApiCallUtils();
        WebSocketTokenService tokenService = mock(WebSocketTokenService.class);
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(8L);
        account.setStatus(-2);
        when(tokenService.isCaptchaPending(8L)).thenReturn(false);
        when(accountMapper.selectById(8L)).thenReturn(account);
        ReflectionTestUtils.setField(utils, "webSocketTokenService", tokenService);
        ReflectionTestUtils.setField(utils, "accountMapper", accountMapper);

        XianyuApiCallUtils.ApiCallResult result = utils.callApiWithRetry(
                8L, "mtop.test", Map.of(), "cookie=value");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("自动请求已暂停"));
    }
}
