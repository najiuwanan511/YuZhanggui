package com.yuzhanggui.service.impl;

import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.service.NotificationChannelService;
import com.yuzhanggui.service.OperationLogService;
import com.yuzhanggui.service.WebSocketService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketCaptchaNotificationTest {

    @Test
    void persistedVerificationWithoutUrlNotifiesAfterRestartAndRetriesRejectedSubmission() {
        var service = new WebSocketTokenServiceImpl();
        var accounts = mock(XianyuAccountMapper.class);
        var account = new XianyuAccount();
        account.setStatus(-2);
        when(accounts.selectById(7L)).thenReturn(account);
        var notifications = mock(NotificationChannelService.class);
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException()).doNothing()
                .when(notifications).dispatchMessage(eq("CREDENTIAL_UPDATE_REQUIRED"), eq(7L), any(Map.class));
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accounts);
        ReflectionTestUtils.setField(service, "notificationChannelService", notifications);
        ReflectionTestUtils.setField(service, "webSocketService", mock(WebSocketService.class));
        ReflectionTestUtils.setField(service, "operationLogService", mock(OperationLogService.class));
        for (int i = 0; i < 3; i++) {
            ReflectionTestUtils.invokeMethod(service, "rememberCaptchaRequirement", 7L, null, "需要验证");
        }
        verify(notifications, times(2)).dispatchMessage(eq("CREDENTIAL_UPDATE_REQUIRED"), eq(7L), any(Map.class));
        assertTrue(service.isCaptchaPending(7L));
    }

    @Test
    void captchaNotifiesOnNewUrlButNotOnRepeatedOrEmptyUrl() {
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        WebSocketService webSocketService = mock(WebSocketService.class);
        NotificationChannelService notificationChannelService = mock(NotificationChannelService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuAccount account = new XianyuAccount();
        account.setId(7L);
        account.setStatus(1);
        when(accountMapper.selectById(7L)).thenReturn(account);

        ReflectionTestUtils.setField(service, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(service, "notificationChannelService", notificationChannelService);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);

        ReflectionTestUtils.invokeMethod(service, "rememberCaptchaRequirement", 7L,
                "https://h5api.m.goofish.com/punish?first=1", "platform verification required");
        ReflectionTestUtils.invokeMethod(service, "rememberCaptchaRequirement", 7L,
                "https://h5api.m.goofish.com/punish?second=1", "platform verification required");
        ReflectionTestUtils.invokeMethod(service, "rememberCaptchaRequirement", 7L,
                "https://h5api.m.goofish.com/punish?second=1", "platform verification required");
        ReflectionTestUtils.invokeMethod(service, "rememberCaptchaRequirement", 7L,
                "", "platform verification required");

        assertTrue(service.isCaptchaPending(7L));
        assertEquals("https://h5api.m.goofish.com/punish?second=1", service.getCaptchaUrl(7L));
        verify(webSocketService, times(4)).stopWebSocket(7L);
        verify(notificationChannelService, times(2))
                .dispatchMessage(eq("CREDENTIAL_UPDATE_REQUIRED"), eq(7L), any(Map.class));
        service.clearCaptchaWait(7L);
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "captchaNotifiedUrls")).isEmpty());
    }

    @Test
    void newChallengeAfterRestartNotifiesEvenWhenAccountAlreadyNeedsVerification() {
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        WebSocketService webSocketService = mock(WebSocketService.class);
        NotificationChannelService notificationChannelService = mock(NotificationChannelService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuAccount account = new XianyuAccount();
        account.setStatus(-2);
        when(accountMapper.selectById(7L)).thenReturn(account);

        ReflectionTestUtils.setField(service, "webSocketService", webSocketService);
        ReflectionTestUtils.setField(service, "notificationChannelService", notificationChannelService);
        ReflectionTestUtils.setField(service, "operationLogService", operationLogService);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);

        ReflectionTestUtils.invokeMethod(service, "rememberCaptchaRequirement", 7L,
                "https://h5api.m.goofish.com/punish", "platform verification required");

        assertTrue(service.isCaptchaPending(7L));
        verify(notificationChannelService, times(1))
                .dispatchMessage(eq("CREDENTIAL_UPDATE_REQUIRED"), eq(7L), any(Map.class));
        service.clearAccountRuntimeState(7L);
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "captchaNotifiedUrls")).isEmpty());
    }

    @Test
    void remoteNotificationUsesConfiguredVncLocation() {
        WebSocketTokenServiceImpl service = new WebSocketTokenServiceImpl();
        NotificationChannelService notifications = mock(NotificationChannelService.class);
        ReflectionTestUtils.setField(service, "notificationChannelService", notifications);
        ReflectionTestUtils.setField(service, "captchaRemoteEnabled", true);
        ReflectionTestUtils.setField(service, "captchaRemotePort", 17900);
        ReflectionTestUtils.invokeMethod(service, "notifyCaptchaRequired", 7L, "验证链接已刷新");
        verify(notifications).dispatchMessage(eq("CREDENTIAL_UPDATE_REQUIRED"), eq(7L),
                org.mockito.ArgumentMatchers.argThat(params -> params.get("action").toString().contains("17900")
                        && params.get("action").toString().contains("发起远程验证")));
    }
}
