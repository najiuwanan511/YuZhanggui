package com.yuzhanggui.service.impl;

import com.yuzhanggui.entity.XianyuCookie;
import com.yuzhanggui.mapper.XianyuCookieMapper;
import com.yuzhanggui.service.CookieRefreshService;
import com.yuzhanggui.service.Mh5tkRefreshResult;
import com.yuzhanggui.service.WebSocketTokenService;
import com.yuzhanggui.utils.XianyuSignUtils;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenRefreshRecoveryTest {
    private MockWebServer server;
    private TokenRefreshServiceImpl service;
    private XianyuCookieMapper mapper;
    private CookieRefreshService login;
    private WebSocketTokenService websocket;
    private XianyuCookie cookie;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        service = new TokenRefreshServiceImpl(server.url("/h5/bootstrap/1.0/").toString());
        mapper = mock(XianyuCookieMapper.class);
        login = mock(CookieRefreshService.class);
        websocket = mock(WebSocketTokenService.class);
        cookie = new XianyuCookie();
        cookie.setId(1L);
        cookie.setCookieText("unb=123; cookie2=session; _m_h5_tk=old_1; _m_h5_tk_enc=old-enc");
        when(mapper.selectOne(any())).thenReturn(cookie);
        when(mapper.updateById(any(XianyuCookie.class))).thenReturn(1);
        ReflectionTestUtils.setField(service, "cookieMapper", mapper);
        ReflectionTestUtils.setField(service, "cookieRefreshService", login);
        ReflectionTestUtils.setField(service, "webSocketTokenService", websocket);
        ReflectionTestUtils.setField(service, "credentialUpdateCoordinator", new CredentialUpdateCoordinator());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private MockResponse unchanged() {
        return new MockResponse().setBody("{\"ret\":[\"FAIL_SYS_TOKEN_EXOIRED::令牌过期\"]}");
    }

    private MockResponse rotated() {
        return unchanged().addHeader("Set-Cookie", "_m_h5_tk=new_9999999999999; Path=/")
                .addHeader("Set-Cookie", "_m_h5_tk_enc=new-enc; Path=/");
    }

    @Test
    void unchangedResponseTriggersSignedNegotiationWhilePreservingLoginCookies() throws Exception {
        server.enqueue(unchanged());
        server.enqueue(rotated());

        assertEquals(Mh5tkRefreshResult.REFRESHED, service.refreshMh5tkTokenDetailed(7L));
        var initial = server.takeRequest();
        var negotiation = server.takeRequest();
        assertTrue(initial.getHeader("Cookie").contains("_m_h5_tk=old_1"));
        assertFalse(negotiation.getHeader("Cookie").contains("_m_h5_tk"));
        assertTrue(negotiation.getHeader("Cookie").contains("cookie2=session"));
        var url = negotiation.getRequestUrl();
        assertEquals("34839810", url.queryParameter("appKey"));
        assertEquals("{}", url.queryParameter("data"));
        assertEquals(XianyuSignUtils.generateSign(url.queryParameter("t"), "", "{}"),
                url.queryParameter("sign"));
        assertEquals("new_9999999999999", cookie.getMH5Tk());
        assertTrue(cookie.getCookieText().contains("_m_h5_tk_enc=new-enc"));
        verify(mapper).updateById(cookie);
        verifyNoInteractions(login);
    }

    @Test
    void failedNegotiationLeavesStoredCredentialsIntactAndStopsAfterOneLoginFallback() {
        String original = cookie.getCookieText();
        server.enqueue(unchanged());
        server.enqueue(unchanged());
        server.enqueue(unchanged());
        when(login.checkLoginStatus(7L)).thenReturn(true);

        assertEquals(Mh5tkRefreshResult.UNCHANGED, service.refreshMh5tkTokenDetailed(7L));
        assertEquals(3, server.getRequestCount());
        assertEquals(original, cookie.getCookieText());
        verify(login, times(1)).checkLoginStatus(7L);
        verify(mapper, never()).updateById(any(XianyuCookie.class));
    }

    @Test
    void tokenRotatedByLoginIsRecognizedWithoutRequestingAnotherToken() {
        server.enqueue(unchanged());
        server.enqueue(unchanged());
        when(login.checkLoginStatus(7L)).thenAnswer(invocation -> {
            cookie.setCookieText("cookie2=session; _m_h5_tk=login_9999999999999; _m_h5_tk_enc=login-enc");
            return true;
        });
        assertEquals(Mh5tkRefreshResult.REFRESHED, service.refreshMh5tkTokenDetailed(7L));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void verificationChallengeStopsNegotiationEvenIfItSetsCookies() {
        server.enqueue(rotated().setBody("{\"ret\":[\"FAIL_SYS_USER_VALIDATE\"],"
                + "\"data\":{\"url\":\"https://www.goofish.com/verify\"}}"));
        assertEquals(Mh5tkRefreshResult.FAILED, service.refreshMh5tkTokenDetailed(7L));
        assertEquals(1, server.getRequestCount());
        verify(websocket).pauseForVerification(eq(7L), eq("https://www.goofish.com/verify"), anyString());
        verify(mapper, never()).updateById(any(XianyuCookie.class));
        verifyNoInteractions(login);
    }

    @Test
    void incompleteSigningPairDoesNotOverwriteStoredToken() {
        server.enqueue(unchanged().addHeader("Set-Cookie", "_m_h5_tk=new_9999999999999; Path=/"));
        server.enqueue(unchanged());
        assertNotEquals(Mh5tkRefreshResult.REFRESHED, service.refreshMh5tkTokenDetailed(7L));
        assertTrue(cookie.getCookieText().contains("_m_h5_tk=old_1"));
        verify(mapper, never()).updateById(any(XianyuCookie.class));
    }

    @Test
    void pendingVerificationMakesNoNetworkRequest() {
        when(websocket.isCaptchaPending(7L)).thenReturn(true);
        assertEquals(Mh5tkRefreshResult.FAILED, service.refreshMh5tkTokenDetailed(7L));
        assertEquals(0, server.getRequestCount());
        verifyNoInteractions(mapper, login);
    }
}
