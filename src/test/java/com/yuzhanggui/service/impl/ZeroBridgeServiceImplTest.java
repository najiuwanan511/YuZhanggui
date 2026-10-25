package com.yuzhanggui.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhanggui.entity.XianyuZeroBridgeOrder;
import com.yuzhanggui.entity.XianyuZeroCallbackEvent;
import com.yuzhanggui.entity.XianyuZeroSubmission;
import com.yuzhanggui.mapper.*;
import com.yuzhanggui.service.EnhancedMessageSendService;
import com.yuzhanggui.service.SysSettingService;
import com.yuzhanggui.service.ZeroBridgeService;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ZeroBridgeServiceImplTest {

    @Test
    void acceptsSignedTerminalCallbackAndQueuesOneResult() throws Exception {
        XianyuZeroBridgeOrderMapper bridgeMapper = mock(XianyuZeroBridgeOrderMapper.class);
        XianyuZeroSubmissionMapper submissionMapper = mock(XianyuZeroSubmissionMapper.class);
        XianyuZeroCallbackEventMapper eventMapper = mock(XianyuZeroCallbackEventMapper.class);
        XianyuGoodsOrderMapper orderMapper = mock(XianyuGoodsOrderMapper.class);
        SysSettingService settings = mock(SysSettingService.class);
        EnhancedMessageSendService messages = mock(EnhancedMessageSendService.class);
        when(settings.getSettingValue(ZeroBridgeServiceImpl.CALLBACK_SECRET_KEY)).thenReturn("secret-1234567890");

        XianyuZeroBridgeOrder bridge = new XianyuZeroBridgeOrder();
        bridge.setId(9L);
        bridge.setXianyuAccountId(7L);
        bridge.setExternalOrderId("XY100");
        bridge.setBuyerUserId("buyer-1");
        bridge.setSid("chat-1@goofish");
        bridge.setExpectedCount(1);
        when(bridgeMapper.selectByExternalOrder(7L, "XY100")).thenReturn(bridge);

        XianyuZeroSubmission submission = new XianyuZeroSubmission();
        submission.setLineId("line-1");
        when(submissionMapper.selectByBridgeId(9L)).thenReturn(List.of(submission));
        when(eventMapper.countTerminalLines(9L)).thenReturn(1);
        List<XianyuZeroCallbackEvent> stored = new ArrayList<>();
        doAnswer(invocation -> { stored.add(invocation.getArgument(0)); return 1; }).when(eventMapper).insert(any());
        when(eventMapper.selectTerminalEvents(9L)).thenAnswer(invocation -> stored);

        String body = "{\"eventId\":\"evt-1\",\"orderId\":\"XY100\",\"lineId\":\"line-1\","
                + "\"accountId\":\"7\",\"buyerId\":\"buyer-1\",\"chatId\":\"chat-1@goofish\","
                + "\"status\":\"完成\",\"projectName\":\"测试\",\"zeroOrderNo\":\"NO1\","
                + "\"content\":\"测试+456\",\"remark\":\"\",\"completedAt\":\"2026-08-31 07:46:02\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = "sha256=" + sign("secret-1234567890", timestamp + "." + body);

        ZeroBridgeService.CallbackResult result = service(bridgeMapper, submissionMapper, eventMapper,
                orderMapper, settings, messages).acceptCallback(body, "evt-1", timestamp, signature);

        assertEquals(200, result.httpStatus());
        verify(eventMapper).insert(any(XianyuZeroCallbackEvent.class));
        verify(bridgeMapper).markResultReady(eq(9L), contains("测试+456"));
    }

    @Test
    void rejectsInvalidSignatureWithoutLookingUpOrder() {
        XianyuZeroBridgeOrderMapper bridgeMapper = mock(XianyuZeroBridgeOrderMapper.class);
        SysSettingService settings = mock(SysSettingService.class);
        when(settings.getSettingValue(ZeroBridgeServiceImpl.CALLBACK_SECRET_KEY)).thenReturn("secret-1234567890");
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        ZeroBridgeService.CallbackResult result = service(bridgeMapper, mock(XianyuZeroSubmissionMapper.class),
                mock(XianyuZeroCallbackEventMapper.class), mock(XianyuGoodsOrderMapper.class), settings,
                mock(EnhancedMessageSendService.class)).acceptCallback("{}", "evt-1", timestamp, "sha256=bad");

        assertEquals(401, result.httpStatus());
        verifyNoInteractions(bridgeMapper);
    }

    @Test
    void rejectsSignedCallbackForDifferentBuyerBeforeStoringEvent() throws Exception {
        XianyuZeroBridgeOrderMapper bridgeMapper = mock(XianyuZeroBridgeOrderMapper.class);
        XianyuZeroSubmissionMapper submissionMapper = mock(XianyuZeroSubmissionMapper.class);
        XianyuZeroCallbackEventMapper eventMapper = mock(XianyuZeroCallbackEventMapper.class);
        SysSettingService settings = mock(SysSettingService.class);
        when(settings.getSettingValue(ZeroBridgeServiceImpl.CALLBACK_SECRET_KEY)).thenReturn("secret-1234567890");

        XianyuZeroBridgeOrder bridge = new XianyuZeroBridgeOrder();
        bridge.setId(9L);
        bridge.setBuyerUserId("buyer-1");
        bridge.setSid("chat-1@goofish");
        when(bridgeMapper.selectByExternalOrder(7L, "XY100")).thenReturn(bridge);

        String body = "{\"eventId\":\"evt-wrong-buyer\",\"orderId\":\"XY100\",\"lineId\":\"line-1\","
                + "\"accountId\":\"7\",\"buyerId\":\"buyer-2\",\"chatId\":\"chat-1@goofish\","
                + "\"status\":\"完成\"}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = "sha256=" + sign("secret-1234567890", timestamp + "." + body);

        ZeroBridgeService.CallbackResult result = service(bridgeMapper, submissionMapper, eventMapper,
                mock(XianyuGoodsOrderMapper.class), settings, mock(EnhancedMessageSendService.class))
                .acceptCallback(body, "evt-wrong-buyer", timestamp, signature);

        assertEquals(409, result.httpStatus());
        verify(eventMapper, never()).insert(any());
        verifyNoInteractions(submissionMapper);
    }

    private static ZeroBridgeServiceImpl service(XianyuZeroBridgeOrderMapper bridgeMapper,
                                                  XianyuZeroSubmissionMapper submissionMapper,
                                                  XianyuZeroCallbackEventMapper eventMapper,
                                                  XianyuGoodsOrderMapper orderMapper,
                                                  SysSettingService settings,
                                                  EnhancedMessageSendService messages) {
        return new ZeroBridgeServiceImpl(bridgeMapper, submissionMapper, eventMapper, orderMapper,
                settings, messages, new ObjectMapper());
    }

    private static String sign(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
