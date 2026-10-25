package com.yuzhanggui.service.impl;

import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.entity.XianyuCookie;
import com.yuzhanggui.exception.CaptchaRequiredException;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuCookieMapper;
import com.yuzhanggui.service.AccountService;
import com.yuzhanggui.service.WebSocketTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketCredentialRecoveryTest {

    @Test
    void normalStartRemainsBlockedWhileAccountNeedsVerification() {
        WebSocketServiceImpl service = new WebSocketServiceImpl();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        AccountService accountService = mock(AccountService.class);
        WebSocketTokenService tokenService = mock(WebSocketTokenService.class);
        when(accountMapper.selectById(7L)).thenReturn(account(7L, -2));

        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "tokenService", tokenService);

        assertFalse(service.startWebSocket(7L));
        verify(accountService, never()).getCookieByAccountId(7L);
        verify(tokenService, never()).getAccessToken(7L);
    }

    @Test
    void newCredentialGetsOnePlatformValidationAttemptWithoutClearingAccountStatus() {
        WebSocketServiceImpl service = new WebSocketServiceImpl();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        AccountService accountService = mock(AccountService.class);
        WebSocketTokenService tokenService = mock(WebSocketTokenService.class);
        XianyuAccount account = account(7L, -2);
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(accountService.getCookieByAccountId(7L)).thenReturn("unb=123456; _m_h5_tk=fresh_1");
        when(accountService.getOrGenerateDeviceId(7L, "123456")).thenReturn("device-7");
        when(tokenService.getAccessToken(7L))
                .thenThrow(new CaptchaRequiredException("https://h5api.m.goofish.com/punish"));

        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "tokenService", tokenService);
        ReflectionTestUtils.setField(service, "credentialUpdateCoordinator", new CredentialUpdateCoordinator());

        assertFalse(service.restartAfterCredentialUpdate(7L));
        verify(tokenService).clearAccountRuntimeState(7L);
        verify(tokenService).clearToken(7L);
        verify(tokenService).getAccessToken(7L);
        verify(accountMapper, never()).updateById(account);
        assertEquals(-2, account.getStatus());
    }

    @Test
    void accountReturnsToNormalOnlyAfterSocketConnectionCompletes() {
        WebSocketServiceImpl service = new WebSocketServiceImpl();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuAccount account = account(7L, -2);
        when(accountMapper.selectById(7L)).thenReturn(account);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);

        ReflectionTestUtils.invokeMethod(service, "markAccountConnected", 7L);

        assertEquals(1, account.getStatus());
        verify(accountMapper).updateById(account);
    }

    @Test
    void savingFreshCookiePreservesVerificationStatusAndExistingAccountNote() {
        AccountServiceImpl service = new AccountServiceImpl();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        XianyuAccount account = account(7L, -2);
        account.setAccountNote("我的主账号");
        XianyuCookie cookie = new XianyuCookie();
        cookie.setId(11L);
        cookie.setXianyuAccountId(7L);
        when(accountMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(account);
        when(cookieMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(cookie);
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "cookieMapper", cookieMapper);
        var orders = mock(com.yuzhanggui.mapper.XianyuGoodsOrderMapper.class);
        ReflectionTestUtils.setField(service, "orderMapper", orders);

        service.saveAccountAndCookie("account-7", "123456",
                "unb=123456; _m_h5_tk=fresh_1", "fresh_1");

        assertEquals(-2, account.getStatus());
        assertEquals("我的主账号", account.getAccountNote());
        verify(accountMapper).updateById(account);
        verify(cookieMapper).updateById(cookie);
        verify(orders).resumeBuyerVerificationTasks(7L);
    }

    private static XianyuAccount account(Long id, int status) {
        XianyuAccount account = new XianyuAccount();
        account.setId(id);
        account.setStatus(status);
        return account;
    }
}
