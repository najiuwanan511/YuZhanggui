package com.yuzhanggui.service.impl;

import com.google.gson.JsonObject;
import com.yuzhanggui.controller.dto.QRLoginSession;
import com.yuzhanggui.service.AccountService;
import com.yuzhanggui.service.WebSocketService;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QRLoginServiceImplTest {

    @Test
    void onlyReportsQrSuccessAfterPersistenceAndBeforeReconnect() {
        QRLoginServiceImpl service = new QRLoginServiceImpl();
        AccountService accounts = mock(AccountService.class);
        WebSocketService sockets = mock(WebSocketService.class);
        ReflectionTestUtils.setField(service, "accountService", accounts);
        ReflectionTestUtils.setField(service, "webSocketService", sockets);
        QRLoginSession session = new QRLoginSession("persistence-order");
        session.setStatus("scanned");
        session.setUnb("demo");
        session.getCookies().put("unb", "demo");
        session.getCookies().put("_m_h5_tk", "fresh_1");
        when(accounts.saveAccountAndCookie(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("demo"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("fresh_1"))).thenAnswer(call -> {
            assertEquals("scanned", session.getStatus());
            return 1L;
        });
        when(sockets.restartAfterCredentialUpdate(1L)).thenAnswer(call -> {
            assertEquals("success", session.getStatus());
            return false;
        });
        ReflectionTestUtils.invokeMethod(service, "saveCookieToDatabase", session);
        assertEquals("success", session.getStatus());
        verify(sockets).restartAfterCredentialUpdate(1L);
    }

    @Test
    void mergesPassportCookiesAcrossQrRequests() {
        QRLoginSession session = new QRLoginSession("session-1");

        QRLoginServiceImpl.mergeSetCookieHeaders(session, List.of(
                "loginKey=abc; Path=/; HttpOnly",
                "unb=22129504; Domain=.goofish.com; Path=/"));

        assertEquals("abc", session.getCookies().get("loginKey"));
        assertEquals("22129504", session.getUnb());
    }

    @Test
    void acceptsCurrentAndLegacyScannedStatusNames() {
        assertTrue(QRLoginServiceImpl.isScannedStatus("SCANED"));
        assertTrue(QRLoginServiceImpl.isScannedStatus("SCANNED"));
    }

    @Test
    void readsStatusFromCompatibleResponseFields() {
        JsonObject data = new JsonObject();
        data.addProperty("status", "confirmed");

        assertEquals("CONFIRMED", QRLoginServiceImpl.readQRCodeStatus(data));
    }

    @Test
    void treatsCancellationOnlyAsAnExplicitTerminalStatus() {
        assertTrue(QRLoginServiceImpl.isCancelledStatus("CANCELLED"));
        assertTrue(!QRLoginServiceImpl.isCancelledStatus("PROCESSING"));
    }

    @Test
    void verificationSessionCanBeExtendedForHumanCheck() {
        QRLoginSession session = new QRLoginSession("session-2");

        session.setExpireTime(900000);

        assertEquals(900000, session.getExpireTime());
        assertTrue(!session.isExpired());
        assertTrue(!QRLoginServiceImpl.isTerminalSessionStatus("verification_required"));
        assertTrue(QRLoginServiceImpl.isTerminalSessionStatus("success"));
    }

    @Test
    void keepsWaitingWhenConsumedQrBelongsToVerificationSession() {
        assertTrue(QRLoginServiceImpl.shouldKeepWaitingAfterQRCodeExpired("verification_required"));
        assertTrue(!QRLoginServiceImpl.shouldKeepWaitingAfterQRCodeExpired("waiting"));
        assertTrue(!QRLoginServiceImpl.shouldKeepWaitingAfterQRCodeExpired("scanned"));
    }

    @Test
    void verificationWindowCanRestartAfterOriginalQrLifetime() {
        QRLoginSession session = new QRLoginSession("session-3");
        session.setCreatedTime(System.currentTimeMillis() - 20 * 60 * 1000L);
        assertTrue(session.isExpired());

        session.setCreatedTime(System.currentTimeMillis());
        session.setExpireTime(15 * 60 * 1000L);

        assertTrue(!session.isExpired());
    }

    @Test
    void extractsFaceVerificationProtocolValues() {
        String normalHtml = """
                <script>
                window.location.href="https:\\/\\/passport.goofish.com\\/iv\\/mini\\/verify_modes.htm?htoken=token_1&amp;_umidfg=";
                </script>
                """;
        HttpUrl pageUrl = HttpUrl.get("https://passport.goofish.com/iv/mini/normal_validate.htm?htoken=token_1");

        assertEquals("token_1", QRLoginServiceImpl.extractFaceToken(normalHtml, pageUrl.toString()));
        assertEquals(
                "https://passport.goofish.com/iv/mini/verify_modes.htm?htoken=token_1&_umidfg=1",
                QRLoginServiceImpl.extractVerificationModesUrl(normalHtml, pageUrl));
        assertEquals(
                "https://passport.goofish.com/iv/face?foo=1&bar=2",
                QRLoginServiceImpl.extractFaceQRCodeContent(
                        "new Qrcode({ text: \"https:\\/\\/passport.goofish.com\\/iv\\/face?foo=1&amp;bar=2\" });"));
    }

    @Test
    void readsSuccessfulFaceVerificationCallback() {
        JsonObject content = new JsonObject();
        content.addProperty("code", 3);
        content.addProperty("url", "https://passport.goofish.com/iv/mini/ivCheckLogin.htm?token=done");
        JsonObject response = new JsonObject();
        response.add("content", content);

        QRLoginServiceImpl.FaceCheckResult result = QRLoginServiceImpl.readFaceCheckResult(response);

        assertEquals("3", result.code());
        assertEquals("https://passport.goofish.com/iv/mini/ivCheckLogin.htm?token=done", result.completionUrl());
    }

    @Test
    void onlyAllowsTrustedVerificationHosts() {
        assertTrue(QRLoginServiceImpl.isTrustedVerificationHost("passport.goofish.com"));
        assertTrue(QRLoginServiceImpl.isTrustedVerificationHost("login.taobao.com"));
        assertTrue(!QRLoginServiceImpl.isTrustedVerificationHost("goofish.com.attacker.example"));
    }

    @Test
    void qrLoginRestartsWebSocketAfterPersistingFreshCookie() {
        QRLoginServiceImpl service = new QRLoginServiceImpl();
        AccountService accountService = mock(AccountService.class);
        WebSocketService webSocketService = mock(WebSocketService.class);
        when(accountService.saveAccountAndCookie(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("22129504"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("fresh_1")))
                .thenReturn(7L);
        when(webSocketService.restartAfterCredentialUpdate(7L)).thenReturn(true);

        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "webSocketService", webSocketService);

        QRLoginSession session = new QRLoginSession("session-recovery");
        session.setUnb("22129504");
        LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
        cookies.put("unb", "22129504");
        cookies.put("_m_h5_tk", "fresh_1");
        session.getCookies().putAll(cookies);

        ReflectionTestUtils.invokeMethod(service, "saveCookieToDatabase", session);

        verify(webSocketService).restartAfterCredentialUpdate(7L);
    }
}
