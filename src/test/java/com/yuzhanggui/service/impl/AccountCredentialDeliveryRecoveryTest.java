package com.yuzhanggui.service.impl;

import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.entity.XianyuCookie;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuCookieMapper;
import com.yuzhanggui.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountCredentialDeliveryRecoveryTest {
    @Test
    void savingReplacementCredentialsWakesUnsentVerificationOrdersAfterCookieWrite() {
        var service = new AccountServiceImpl();
        var accounts = mock(XianyuAccountMapper.class);
        var cookies = mock(XianyuCookieMapper.class);
        var orders = mock(XianyuGoodsOrderMapper.class);
        var account = new XianyuAccount();
        account.setId(7L);
        when(accounts.selectById(7L)).thenReturn(account);
        var cookie = new XianyuCookie();
        when(cookies.selectOne(any())).thenReturn(cookie);
        ReflectionTestUtils.setField(service, "accountMapper", accounts);
        ReflectionTestUtils.setField(service, "cookieMapper", cookies);
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "credentialUpdateCoordinator", new CredentialUpdateCoordinator());

        assertTrue(service.updateAccountCookie(7L, "123", "unb=123; _m_h5_tk=new_9999999999999"));
        var sequence = inOrder(cookies, orders);
        sequence.verify(cookies).selectOne(any());
        sequence.verify(cookies).updateById(cookie);
        sequence.verify(orders).resumeBuyerVerificationTasks(7L);
        assertEquals("new_9999999999999", cookie.getMH5Tk());

        when(cookies.updateById(any(XianyuCookie.class))).thenThrow(new IllegalStateException("database unavailable"));
        clearInvocations(orders);
        assertFalse(service.updateAccountCookie(7L, "123", "unb=123; _m_h5_tk=other_9999999999999"));
        verifyNoInteractions(orders);
    }
}
