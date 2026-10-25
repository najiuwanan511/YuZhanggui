package com.yuzhanggui.service.impl;

import com.yuzhanggui.config.WebSocketConfig;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.service.OperationLogService;
import com.yuzhanggui.service.WebSocketTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketRecoverySchedulingTest {
    private WebSocketServiceImpl service;
    private WebSocketTokenService tokens;
    private XianyuAccount account;
    private ScheduledExecutorService scheduler;
    private final Queue<Runnable> timers = new ArrayDeque<>();
    private final Queue<Runnable> workers = new ArrayDeque<>();
    private final List<Long> delays = new ArrayList<>();

    @BeforeEach
    void setup() {
        service = spy(new WebSocketServiceImpl());
        tokens = mock(WebSocketTokenService.class);
        scheduler = mock(ScheduledExecutorService.class);
        XianyuAccountMapper mapper = mock(XianyuAccountMapper.class);
        account = new XianyuAccount();
        account.setId(7L);
        account.setStatus(1);
        when(mapper.selectById(7L)).thenReturn(account);
        ReflectionTestUtils.setField(service, "config", new WebSocketConfig());
        ReflectionTestUtils.setField(service, "tokenService", tokens);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", mapper);
        ReflectionTestUtils.setField(service, "webSocketScheduler", scheduler);
        ReflectionTestUtils.setField(service, "taskExecutor", (Executor) workers::add);
        ReflectionTestUtils.setField(service, "operationLogService", mock(OperationLogService.class));
        ReflectionTestUtils.setField(service, "credentialUpdateCoordinator", new CredentialUpdateCoordinator());
        doReturn(false).when(service).startWebSocket(7L);
        doAnswer(invocation -> {
            timers.add(invocation.getArgument(0));
            delays.add(invocation.getArgument(1));
            return mock(ScheduledFuture.class);
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    private void schedule() {
        ReflectionTestUtils.invokeMethod(service, "scheduleReconnect", 7L, 15, false);
    }

    private void runWorker() throws Exception {
        FutureTask<Void> task = new FutureTask<>(workers.remove(), null);
        Thread thread = new Thread(task, "test-business-worker");
        thread.start();
        task.get(5, TimeUnit.SECONDS);
        thread.join(5000);
    }

    @Test
    void persistentFailuresKeepFiveMinuteDelayAndSuccessResetsBackoff() throws Exception {
        schedule();
        for (int i = 0; i < 9; i++) {
            timers.remove().run();
            runWorker();
        }
        assertEquals(List.of(15L, 30L, 60L, 120L, 240L, 300L, 300L, 300L, 300L, 300L), delays);
        doReturn(true).when(service).startWebSocket(7L);
        timers.remove().run();
        runWorker();
        assertTrue(timers.isEmpty());
        schedule();
        assertEquals(15L, delays.getLast());
    }

    @Test
    void repeatedReconnectExceptionsAlsoNotifyWithoutEmailEnabled() throws Exception {
        var notifications = mock(com.yuzhanggui.service.NotificationChannelService.class);
        ReflectionTestUtils.setField(service, "notificationChannelService", notifications);
        doThrow(new IllegalStateException("reconnect failed")).when(service).startWebSocket(7L);
        schedule();
        for (int i = 0; i < 3; i++) {
            timers.remove().run();
            runWorker();
        }
        verify(notifications).dispatchMessage(eq("ACCOUNT_OFFLINE"), eq(7L), anyMap());
        assertFalse(timers.isEmpty());
    }

    @Test
    void timerOnlyQueuesWorkAndDuplicateReconnectCannotEnterQueue() throws Exception {
        schedule();
        timers.remove().run();
        verify(service, never()).startWebSocket(7L);
        schedule();
        assertTrue(timers.isEmpty());
        assertEquals(1, workers.size());
        runWorker();
        verify(service).startWebSocket(7L);
    }

    @Test
    void queuedReconnectIsCancelledWhenUserStopsAccount() throws Exception {
        schedule();
        timers.remove().run();
        service.stopWebSocket(7L);
        runWorker();
        verify(service, never()).startWebSocket(7L);
        assertTrue(timers.isEmpty());
    }

    @Test
    void queuedReconnectChecksCaptchaAndDisabledStateAgain() throws Exception {
        schedule();
        timers.remove().run();
        when(tokens.isCaptchaPending(7L)).thenReturn(true);
        runWorker();
        verify(service, never()).startWebSocket(7L);
        assertTrue(timers.isEmpty());
        when(tokens.isCaptchaPending(7L)).thenReturn(false);
        schedule();
        timers.remove().run();
        account.setStatus(0);
        runWorker();
        verify(service, never()).startWebSocket(7L);
        assertTrue(timers.isEmpty());
    }

    @Test
    void pendingSessionRenewalDoesNotScheduleReconnect() {
        when(tokens.isSessionRenewalPending(7L)).thenReturn(true);
        schedule();
        assertTrue(timers.isEmpty());
    }

    @Test
    void tokenRetryAlsoRunsOffSchedulerAndCanBeCancelledWhileQueued() throws Exception {
        ReflectionTestUtils.invokeMethod(service, "scheduleTokenRefreshRetry", 7L);
        timers.remove().run();
        verify(tokens, never()).clearToken(7L);
        service.stopWebSocket(7L);
        runWorker();
        verify(tokens, never()).clearToken(7L);
        assertTrue(timers.isEmpty());

        ReflectionTestUtils.invokeMethod(service, "scheduleTokenRefreshRetry", 7L);
        timers.remove().run();
        runWorker();
        verify(tokens).clearToken(7L);
        verify(service).startWebSocket(7L);
    }

    @Test
    void rejectedWorkCanBeCancelledBeforeDispatchRetry() {
        ReflectionTestUtils.setField(service, "taskExecutor", (Executor) task -> {
            throw new RejectedExecutionException();
        });
        schedule();
        timers.remove().run();
        service.stopWebSocket(7L);
        timers.remove().run();
        assertTrue(timers.isEmpty());
        verify(service, never()).startWebSocket(7L);
    }

    @Test
    void callerRunsPolicyCannotBlockSchedulerAndRetriesDispatch() throws Exception {
        ReflectionTestUtils.setField(service, "taskExecutor", (Executor) Runnable::run);
        schedule();
        timers.remove().run();
        verify(service, never()).startWebSocket(7L);
        assertEquals(List.of(15L, 30L), delays);
        ReflectionTestUtils.setField(service, "taskExecutor", (Executor) workers::add);
        timers.remove().run();
        runWorker();
        verify(service).startWebSocket(7L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void watchdogWaitsThreeFullWindowsAndHeartbeatResponseResetsThem() {
        Map<Long, Long> responses = (Map<Long, Long>) ReflectionTestUtils.getField(service, "lastHeartbeatResponseTimes");
        responses.put(7L, 100L);
        for (long now : new long[]{125, 126, 127, 150, 151, 174}) {
            ReflectionTestUtils.invokeMethod(service, "checkHeartbeatTimeout", 7L, now);
        }
        assertTrue(timers.isEmpty());
        ReflectionTestUtils.invokeMethod(service, "checkHeartbeatTimeout", 7L, 175L);
        ReflectionTestUtils.invokeMethod(service, "checkHeartbeatTimeout", 7L, 176L);
        assertEquals(1, timers.size());
        service.updateHeartbeatResponseTime(7L);
        long response = responses.get(7L);
        ReflectionTestUtils.invokeMethod(service, "checkHeartbeatTimeout", 7L, response + 74);
        assertEquals(1, timers.size());
        ReflectionTestUtils.invokeMethod(service, "checkHeartbeatTimeout", 7L, response + 75);
        assertEquals(2, timers.size());
        service.stopWebSocket(7L);
        assertFalse(responses.containsKey(7L));
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(service, "heartbeatTimeoutCounts")).isEmpty());
    }
}
