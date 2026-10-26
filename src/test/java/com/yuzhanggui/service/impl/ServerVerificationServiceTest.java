package com.yuzhanggui.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.entity.XianyuCookie;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuCookieMapper;
import com.yuzhanggui.service.WebSocketService;
import com.yuzhanggui.service.WebSocketTokenService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServerVerificationServiceTest {
    private final XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
    private final XianyuCookieMapper cookies = mock(XianyuCookieMapper.class);
    private final WebSocketTokenService tokens = mock(WebSocketTokenService.class);
    private final WebSocketService sockets = mock(WebSocketService.class);
    private final VerificationBrowserFactory browsers = mock(VerificationBrowserFactory.class);
    private final VerificationBrowserFactory.Session browser = mock(VerificationBrowserFactory.Session.class);
    private final ManualVerificationScope scope = new ManualVerificationScope();
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final ExecutorService worker = mock(ExecutorService.class);
    private ServerVerificationService service;
    private XianyuAccount account;
    private XianyuCookie row;
    private String id;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), XianyuCookie.class);
        service = new ServerVerificationService(accounts, cookies, tokens, sockets,
                new CredentialUpdateCoordinator(), scope, browsers);
        ((ExecutorService) ReflectionTestUtils.getField(service, "worker")).shutdown();
        ReflectionTestUtils.setField(service, "worker", worker);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(worker).execute(any());
        account = new XianyuAccount(); account.setId(1L); account.setUnb("demo"); account.setStatus(-2);
        row = new XianyuCookie(); row.setId(9L); row.setXianyuAccountId(1L);
        row.setCookieText("unb=demo; cookie2=login; sgcookie=session; _m_h5_tk=old_1");
        when(accounts.selectById(1L)).thenReturn(account);
        when(cookies.selectOne(any(Wrapper.class))).thenReturn(row);
        when(tokens.isCaptchaPending(1L)).thenReturn(true);
        when(tokens.getCaptchaUrl(1L)).thenReturn("https://h5api.m.goofish.com/challenge");
        when(browsers.open(eq(1L), anyString(), anyMap())).thenReturn(browser);
        when(browser.cookies()).thenReturn(Map.of("unb", "demo"));
        when(browser.screenshot()).thenReturn("data:image/jpeg;base64,fixture");
        when(cookies.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    private void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    private void open() { id = service.start(1L).sessionId(); drain(); assertEquals("WAITING", service.state(1L, id).state()); }
    private void refresh() {
        Object active = ReflectionTestUtils.getField(service, "active");
        ReflectionTestUtils.setField(active, "capturedAt", 0L);
        service.state(1L, id); drain();
    }
    private void proof() { when(browser.cookies()).thenReturn(Map.of("unb", "demo", "x5sec", "verified", "x5secdata", "challenge")); }

    @Test void startIsAsyncAndDuplicateRequestsReuseOneBrowser() {
        var first = service.start(1L); id = first.sessionId();
        assertEquals("STARTING", first.state());
        assertEquals(id, service.start(1L).sessionId());
        verifyNoInteractions(browsers);
        drain();
        verify(browsers, times(1)).open(eq(1L), anyString(), anyMap());
    }

    @Test void bindsSessionToAccountAndRejectsAnotherActiveAccount() {
        open();
        when(accounts.selectById(2L)).thenReturn(account);
        assertThrows(IllegalStateException.class, () -> service.start(2L));
        assertThrows(IllegalArgumentException.class, () -> service.state(2L, id));
        assertThrows(IllegalArgumentException.class, () -> service.close(1L, "wrong"));
        verify(browser, never()).close();
    }

    @Test void rejectsMissingDisabledAndUnchallengedAccounts() {
        assertThrows(IllegalStateException.class, () -> service.start(2L));
        account.setStatus(0);
        assertThrows(IllegalStateException.class, () -> service.start(1L));
        account.setStatus(1); when(tokens.isCaptchaPending(1L)).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.start(1L));
        verifyNoInteractions(browsers);
    }

    @Test void rejectsUntrustedChallengeWithoutLaunchingBrowser() {
        when(tokens.getCaptchaUrl(1L)).thenReturn("http://127.0.0.1/admin");
        id = service.start(1L).sessionId(); drain();
        assertEquals("FAILED", service.state(1L, id).state());
        verifyNoInteractions(browsers);
    }

    @Test void unchangedProofDoesNotWriteOrRepeatedlyReconnect() {
        row.setCookieText(row.getCookieText() + "; x5sec=old-proof");
        when(browser.cookies()).thenReturn(Map.of("unb", "demo", "x5sec", "old-proof"));
        open(); refresh(); refresh();
        verify(cookies, never()).update(isNull(), any(Wrapper.class));
        verify(sockets, times(1)).restartAfterCredentialUpdate(1L);
    }

    @Test void savesProofBeforeReconnectAndPreservesNewH5Token() {
        open(); proof();
        row.setCookieText(row.getCookieText().replace("old_1", "new_2") + "; x5secdata=obsolete; x5step=2");
        doAnswer(call -> {
            LambdaUpdateWrapper<?> update = call.getArgument(1);
            update.getSqlSet(); update.getSqlSegment();
            Collection<?> values = update.getParamNameValuePairs().values();
            assertTrue(values.contains(row.getCookieText())); // compare-and-set guard
            assertTrue(values.stream().anyMatch(v -> v instanceof String s && s.contains("x5sec=verified")
                    && s.contains("_m_h5_tk=new_2") && !s.contains("x5secdata") && !s.contains("x5step")));
            return 1;
        }).when(cookies).update(isNull(), any(Wrapper.class));
        when(sockets.restartAfterCredentialUpdate(1L)).thenAnswer(call -> {
            verify(cookies).update(isNull(), any(Wrapper.class));
            assertTrue(scope.owns(1L));
            when(sockets.isConnected(1L)).thenReturn(true);
            return true;
        });
        refresh();
        assertEquals("CONNECTED", service.state(1L, id).state());
        assertFalse(scope.owns(1L));
        verify(browser).close();
    }

    @Test void changedLoginIsNotOverwritten() {
        open(); proof(); row.setCookieText("unb=demo; cookie2=another-login"); refresh();
        assertEquals("FAILED", service.state(1L, id).state());
        verify(cookies, never()).update(isNull(), any(Wrapper.class));
        verify(sockets, times(1)).restartAfterCredentialUpdate(1L);
        verify(browser).close();
    }

    @Test void wrongBrowserAccountIsNotSaved() {
        open(); when(browser.cookies()).thenReturn(Map.of("unb", "other", "x5sec", "verified")); refresh();
        assertEquals("FAILED", service.state(1L, id).state());
        verify(cookies, never()).update(isNull(), any(Wrapper.class));
    }

    @Test void concurrentWriteRejectionStopsRecovery() {
        open(); proof(); when(cookies.update(isNull(), any(Wrapper.class))).thenReturn(0); refresh();
        assertEquals("FAILED", service.state(1L, id).state());
        verify(sockets, times(1)).restartAfterCredentialUpdate(1L);
        verify(browser).close();
    }

    @Test void reconnectReturnValueCannotFakeConnectedState() {
        open(); proof(); when(sockets.restartAfterCredentialUpdate(1L)).thenReturn(true); refresh();
        assertEquals("VERIFICATION_REQUIRED", service.state(1L, id).state());
        verify(browser).close();
    }

    @Test void relaysInputOnceAndRefreshesScreenshotAfterwards() {
        open();
        var points = List.of(new VerificationBrowserFactory.Point(10, 20, 0), new VerificationBrowserFactory.Point(200, 20, 100));
        assertEquals("APPLYING", service.input(1L, id, points).state());
        assertThrows(IllegalStateException.class, () -> service.input(1L, id, points));
        drain();
        verify(browser).input(points);
        assertEquals("WAITING", service.state(1L, id).state());
    }

    @Test void closingThenReloadingReleasesPreviousBrowserBeforeOpeningNext() {
        open(); service.close(1L, id);
        String next = service.start(1L).sessionId(); assertNotEquals(id, next);
        drain();
        var order = inOrder(browser, browsers);
        order.verify(browsers).open(eq(1L), anyString(), anyMap());
        order.verify(browser).close();
        order.verify(browsers).open(eq(1L), anyString(), anyMap());
        assertEquals("WAITING", service.state(1L, next).state());
    }

    @Test void earlierScreenshotRefreshCannotUnlockQueuedInput() {
        open();
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(service, "active"), "capturedAt", 0L);
        service.state(1L, id);
        var points = List.of(new VerificationBrowserFactory.Point(10, 20, 0), new VerificationBrowserFactory.Point(100, 20, 200));
        service.input(1L, id, points);
        tasks.remove().run();
        assertEquals("APPLYING", service.state(1L, id).state());
        assertThrows(IllegalStateException.class, () -> service.input(1L, id, points));
        drain();
        assertEquals("WAITING", service.state(1L, id).state());
    }

    @Test void expiryClosesBrowserWithoutSavingProof() {
        open(); proof();
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(service, "active"), "expiresAt", 1L);
        service.cleanupExpired(); drain();
        assertEquals("EXPIRED", service.state(1L, id).state());
        verify(browser).close();
        verify(cookies, never()).update(isNull(), any(Wrapper.class));
    }

    @Test void fullQueueStillClosesCancelledBrowserWhenQueuedRefreshRuns() {
        open();
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(service, "active"), "capturedAt", 0L);
        service.state(1L, id); // queued refresh belongs to the browser worker
        doThrow(new RejectedExecutionException()).when(worker).execute(any());
        assertThrows(IllegalStateException.class, () -> service.close(1L, id));
        drain(); verify(browser).close();
    }

    @Test void browserFailureClosesResourcesAndHidesExceptionDetails() {
        open(); when(browser.screenshot()).thenThrow(new RuntimeException("secret challenge URL")); refresh();
        var state = service.state(1L, id);
        assertEquals("FAILED", state.state()); assertFalse(state.message().contains("secret"));
        verify(browser).close();
    }
}
