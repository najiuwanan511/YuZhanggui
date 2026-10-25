package com.yuzhanggui.service.impl;

import com.yuzhanggui.config.WebSocketConfig;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.service.EmailNotifyService;
import com.yuzhanggui.service.NotificationChannelService;
import com.yuzhanggui.service.OnlineUpdateMaintenanceService;
import com.yuzhanggui.service.WebSocketTokenService;
import com.yuzhanggui.websocket.XianyuWebSocketClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketDisconnectNotificationTest {
    private WebSocketServiceImpl service;
    private XianyuAccountMapper accounts;
    private NotificationChannelService notifications;
    private EmailNotifyService email;
    private XianyuAccount account;

    @BeforeEach
    void setup() {
        service = new WebSocketServiceImpl();
        accounts = mock(XianyuAccountMapper.class);
        notifications = mock(NotificationChannelService.class);
        email = mock(EmailNotifyService.class);
        account = new XianyuAccount();
        account.setId(7L);
        account.setAccountNote("测试账号");
        account.setStatus(1);
        when(accounts.selectById(7L)).thenReturn(account);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accounts);
        ReflectionTestUtils.setField(service, "notificationChannelService", notifications);
        ReflectionTestUtils.setField(service, "emailNotifyService", email);
    }

    private void notifyOffline() {
        ReflectionTestUtils.invokeMethod(service, "triggerWsDisconnectNotify", 7L);
    }

    @Test
    void pushChannelsWorkWhenEmailToggleIsOff() {
        notifyOffline();
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
        verify(email, never()).sendWsDisconnectNotifyEmail(anyLong(), any());
    }

    @Test
    void pushChannelsWorkWithoutEmailService() {
        ReflectionTestUtils.setField(service, "emailNotifyService", null);
        notifyOffline();
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
    }

    @Test
    void emailFailureCannotSuppressPushAndPushFailureCannotSuppressEmail() {
        when(email.isWsDisconnectNotifyEnabled()).thenReturn(true);
        doThrow(new RejectedExecutionException()).when(email).sendWsDisconnectNotifyEmail(anyLong(), any());
        notifyOffline();
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
        clearInvocations(notifications, email);
        ReflectionTestUtils.invokeMethod(service, "markAccountConnected", 7L);
        doNothing().when(email).sendWsDisconnectNotifyEmail(anyLong(), any());
        doThrow(new RejectedExecutionException()).when(notifications).dispatchMessage(anyString(), anyLong(), anyMap());
        notifyOffline();
        verify(email).sendWsDisconnectNotifyEmail(7L, "测试账号");
    }

    @Test
    void rejectedDispatchCanRetryAndRecoveryAllowsANewIncident() {
        doThrow(new RejectedExecutionException()).doNothing().when(notifications)
                .dispatchMessage(anyString(), anyLong(), anyMap());
        notifyOffline();
        notifyOffline();
        notifyOffline();
        verify(notifications, times(2)).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
        ReflectionTestUtils.invokeMethod(service, "markAccountConnected", 7L);
        notifyOffline();
        verify(notifications, times(3)).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
    }

    @Test
    void concurrentCloseAndPollingOnlyDispatchOnce() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 30; i++) jobs.add(pool.submit(this::notifyOffline));
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
    }

    @SuppressWarnings("unchecked")
    @Test
    void continuedOutageWaitsOneHourBeforeNotifyingAgain() {
        notifyOffline();
        var times = (Map<Long, Long>) ReflectionTestUtils.getField(service, "lastDisconnectNotifyTimes");
        times.put(7L, System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10) - 1);
        notifyOffline();
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
        times.put(7L, System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(59));
        notifyOffline();
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
        times.put(7L, System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1) - 1);
        notifyOffline();
        verify(notifications, times(2)).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
    }

    @Test
    void periodicScanRunsOnBusinessWorkerAndDoesNotQueueDuplicates() throws Exception {
        var timers = new java.util.ArrayDeque<Runnable>();
        var workers = new java.util.ArrayDeque<Runnable>();
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
            timers.add(call.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        when(accounts.selectList(any())).thenReturn(List.of(account));
        ReflectionTestUtils.setField(service, "webSocketScheduler", scheduler);
        ReflectionTestUtils.setField(service, "taskExecutor", (Executor) workers::add);
        service.checkUnavailableAccountNotifications();
        service.checkUnavailableAccountNotifications();
        assertEquals(1, timers.size());
        timers.remove().run();
        verify(accounts, never()).selectList(any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            pool.submit(workers.remove()).get(5, TimeUnit.SECONDS);
        }
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
        service.checkUnavailableAccountNotifications();
        assertEquals(1, timers.size());
    }

    @Test
    void scanCatchesDisconnectedAndPersistedVerificationAccountsButSkipsConnectedAndDisabled() {
        var verification = new XianyuAccount();
        verification.setId(8L);
        verification.setStatus(-2);
        var connected = new XianyuAccount();
        connected.setId(9L);
        connected.setStatus(1);
        var disabled = new XianyuAccount();
        disabled.setId(10L);
        disabled.setStatus(0);
        when(accounts.selectList(any())).thenReturn(List.of(account, verification, connected, disabled));
        when(accounts.selectById(8L)).thenReturn(verification);
        var client = mock(XianyuWebSocketClient.class);
        when(client.isConnected()).thenReturn(true);
        clients().put(9L, client);
        ReflectionTestUtils.invokeMethod(service, "notifyUnavailableAccounts");
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(8L), argThat(params -> params.get("reason").toString().contains("验证")));
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void maintenanceAndDisabledAccountsDoNotNotify() {
        account.setStatus(0);
        notifyOffline();
        account.setStatus(1);
        var maintenance = mock(OnlineUpdateMaintenanceService.class);
        when(maintenance.isActive()).thenReturn(true);
        ReflectionTestUtils.setField(service, "onlineUpdateMaintenanceService", maintenance);
        notifyOffline();
        ReflectionTestUtils.invokeMethod(service, "notifyUnavailableAccounts");
        verifyNoInteractions(notifications, email);
    }

    @Test
    void realUnexpectedCloseNotifiesBeforeReconnectAndIgnoresReplacedClients() {
        var tokens = mock(WebSocketTokenService.class);
        // Renewal suppresses reconnect but must not suppress the offline notification.
        when(tokens.isSessionRenewalPending(7L)).thenReturn(true);
        ReflectionTestUtils.setField(service, "tokenService", tokens);
        ReflectionTestUtils.setField(service, "config", new WebSocketConfig());
        var oldClient = mock(XianyuWebSocketClient.class);
        var current = mock(XianyuWebSocketClient.class);
        clients().put(7L, current);
        ReflectionTestUtils.invokeMethod(service, "handleUnexpectedConnectionClosed", 7L, oldClient);
        verifyNoInteractions(notifications);
        ReflectionTestUtils.invokeMethod(service, "handleUnexpectedConnectionClosed", 7L, current);
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
    }

    @SuppressWarnings("unchecked")
    private Map<Long, XianyuWebSocketClient> clients() {
        return (Map<Long, XianyuWebSocketClient>) ReflectionTestUtils.getField(service, "webSocketClients");
    }
}
