package com.yuzhanggui.service.impl;

import com.yuzhanggui.websocket.XianyuWebSocketClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketServiceImplAcknowledgementTest {

    /** 与 WebSocketServiceImpl 中自动流程的默认回执超时保持一致（秒） */
    private static final int DEFAULT_ACK_TIMEOUT = 10;

    @Test
    @SuppressWarnings("unchecked")
    void textAndImageConvenienceMethodsRequireAPlatformAcknowledgement() {
        XianyuWebSocketClient client = mock(XianyuWebSocketClient.class);
        when(client.isConnected()).thenReturn(true);
        when(client.sendMessageWithResult(eq("cid"), eq("buyer"), eq("secret"), eq(DEFAULT_ACK_TIMEOUT)))
                .thenReturn(false);
        when(client.sendImageMessageWithResult(eq("cid"), eq("buyer"), eq("https://image.invalid/a"),
                eq(10), eq(20), eq(DEFAULT_ACK_TIMEOUT)))
                .thenReturn(false);

        WebSocketServiceImpl service = new WebSocketServiceImpl();
        Map<Long, XianyuWebSocketClient> clients =
                (Map<Long, XianyuWebSocketClient>) ReflectionTestUtils.getField(service, "webSocketClients");
        clients.put(7L, client);

        assertFalse(service.sendMessage(7L, "cid", "buyer", "secret"));
        assertFalse(service.sendImageMessage(7L, "cid", "buyer", "https://image.invalid/a", 10, 20));

        verify(client).sendMessageWithResult(eq("cid"), eq("buyer"), eq("secret"), eq(DEFAULT_ACK_TIMEOUT));
        verify(client).sendImageMessageWithResult(eq("cid"), eq("buyer"), eq("https://image.invalid/a"),
                eq(10), eq(20), eq(DEFAULT_ACK_TIMEOUT));
        verify(client, never()).sendMessage("cid", "buyer", "secret");
        verify(client, never()).sendImageMessage("cid", "buyer", "https://image.invalid/a", 10, 20);
    }

    @Test
    @SuppressWarnings("unchecked")
    void manualSendPassesShorterAcknowledgementTimeoutToClient() {
        XianyuWebSocketClient client = mock(XianyuWebSocketClient.class);
        when(client.isConnected()).thenReturn(true);
        when(client.sendMessageWithResult(eq("cid"), eq("buyer"), eq("hello"), eq(2))).thenReturn(true);

        WebSocketServiceImpl service = new WebSocketServiceImpl();
        Map<Long, XianyuWebSocketClient> clients =
                (Map<Long, XianyuWebSocketClient>) ReflectionTestUtils.getField(service, "webSocketClients");
        clients.put(8L, client);

        assertTrue(service.sendMessageWithResult(8L, "cid", "buyer", "hello", 2));

        // 在线客服手动发送使用 2 秒超时，不能退化成自动流程的 10 秒
        verify(client).sendMessageWithResult(eq("cid"), eq("buyer"), eq("hello"), eq(2));
        verify(client, never()).sendMessageWithResult(eq("cid"), eq("buyer"), eq("hello"), eq(DEFAULT_ACK_TIMEOUT));
    }
}
