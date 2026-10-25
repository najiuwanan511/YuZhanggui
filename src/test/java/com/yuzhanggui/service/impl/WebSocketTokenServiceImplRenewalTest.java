package com.yuzhanggui.service.impl;

import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuCookieMapper;
import com.yuzhanggui.service.CookieRefreshService;
import com.yuzhanggui.service.OperationLogService;
import com.yuzhanggui.service.WebSocketService;
import com.yuzhanggui.service.WebSocketTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketTokenServiceImplRenewalTest {

    @Test
    void sessionExpiryStartsPromptCoalescedRenewalAndReconnects() {
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        CookieRefreshService cookieRefreshService = mock(CookieRefreshService.class);
        WebSocketService webSocketService = mock(WebSocketService.class);
        XianyuCookieMapper cookieMapper = mock(XianyuCookieMapper.class);
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        account.setStatus(1);
        when(accountMapper.selectById(7L)).thenReturn(account);
        OperationLogService operationLogService = mock(OperationLogService.class);
        AtomicReference<Runnable> renewalAction = new AtomicReference<>();
        AtomicLong delaySeconds = new AtomicLong(-1);

        ReflectionTestUtils.setField(service, "webSocketScheduler", scheduler);
        ReflectionTestUtils.setField(service, "cookieRefreshService", cookieRefreshService);
        ReflectionTestUtils.setField(service, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(service, "xianyuCookieMapper", cookieMapper);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);

        doAnswer(invocation -> {
            renewalAction.set(invocation.getArgument(0));
            delaySeconds.set(invocation.getArgument(1));
            return future;
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS));
        when(cookieRefreshService.refreshCookie(7L)).thenReturn(true);
        when(webSocketService.startWebSocket(7L)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "scheduleSessionExpiryRenewal", 7L);

        assertTrue(delaySeconds.get() >= 3 && delaySeconds.get() <= 8);
        assertEquals("REFRESH_PENDING", service.getRenewalStatus(7L).state());

        renewalAction.get().run();

        WebSocketTokenService.RenewalStatus status = service.getRenewalStatus(7L);
        assertEquals("SUCCESS", status.state());
        verify(cookieRefreshService).refreshCookie(7L);
        verify(webSocketService).startWebSocket(7L);
    }
}