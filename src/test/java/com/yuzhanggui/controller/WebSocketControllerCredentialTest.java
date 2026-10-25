package com.yuzhanggui.controller;

import com.yuzhanggui.controller.dto.UpdateCookieReqDTO;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketControllerCredentialTest {
    @Test
    void cookieSavedButCaptchaPendingReturnsActionableMessageForBothRemoteModes() {
        WebSocketController controller = new WebSocketController();
        ApplicationContext context = mock(ApplicationContext.class);
        XianyuAccountMapper mapper = mock(XianyuAccountMapper.class);
        AccountService accounts = mock(AccountService.class);
        WebSocketService sockets = mock(WebSocketService.class);
        WebSocketTokenService tokens = mock(WebSocketTokenService.class);
        when(context.getBean(XianyuAccountMapper.class)).thenReturn(mapper);
        when(context.getBean(AccountService.class)).thenReturn(accounts);
        XianyuAccount account = new XianyuAccount();
        account.setStatus(1);
        account.setUnb("123");
        when(mapper.selectById(7L)).thenReturn(account);
        when(accounts.updateAccountCookie(7L, "123", "unb=123; token=fresh")).thenReturn(true);
        when(tokens.isCaptchaPending(7L)).thenReturn(true);
        ReflectionTestUtils.setField(controller, "applicationContext", context);
        ReflectionTestUtils.setField(controller, "webSocketService", sockets);
        ReflectionTestUtils.setField(controller, "webSocketTokenService", tokens);
        ReflectionTestUtils.setField(controller, "operationLogService", mock(OperationLogService.class));
        UpdateCookieReqDTO request = new UpdateCookieReqDTO();
        request.setXianyuAccountId(7L);
        request.setCookieText("unb=123; token=fresh");

        var response = controller.updateCookie(request);
        assertEquals(200, response.getCode());
        assertTrue(response.getData().getMessage().contains("需要完成滑块验证"));
        assertTrue(response.getData().getMessage().contains("自动重连已暂停"));
        assertFalse(response.getData().getMessage().contains("正在重连"));

        ReflectionTestUtils.setField(controller, "captchaRemoteEnabled", true);
        ReflectionTestUtils.setField(controller, "captchaRemoteUrl", "https://nas.example/vnc.html");
        response = controller.updateCookie(request);
        assertTrue(response.getData().getMessage().contains("https://nas.example/vnc.html"));
        assertFalse(response.getData().getMessage().contains("正在重连"));
    }
}
