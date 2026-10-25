package com.yuzhanggui.controller;

import com.yuzhanggui.common.ResultObject;
import com.yuzhanggui.controller.dto.GetAccountListRespDTO;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuItemPolishConfigMapper;
import com.yuzhanggui.service.WebSocketService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountControllerListStatusTest {

    @Test
    void accountListIncludesLiveWebSocketConnectionStatus() {
        AccountController controller = new AccountController();
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuItemPolishConfigMapper polishConfigMapper = mock(XianyuItemPolishConfigMapper.class);
        WebSocketService webSocketService = mock(WebSocketService.class);

        XianyuAccount connectedAccount = account(1L);
        XianyuAccount disconnectedAccount = account(2L);
        when(accountMapper.selectList(null)).thenReturn(List.of(connectedAccount, disconnectedAccount));
        when(polishConfigMapper.selectList(null)).thenReturn(List.of());
        when(webSocketService.isConnected(1L)).thenReturn(true);
        when(webSocketService.isConnected(2L)).thenReturn(false);

        ReflectionTestUtils.setField(controller, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(controller, "itemPolishConfigMapper", polishConfigMapper);
        ReflectionTestUtils.setField(controller, "webSocketService", webSocketService);

        ResultObject<GetAccountListRespDTO> result = controller.getAccountList();

        assertEquals(200, result.getCode());
        assertTrue(result.getData().getAccounts().get(0).getWebsocketConnected());
        assertFalse(result.getData().getAccounts().get(1).getWebsocketConnected());
    }

    private static XianyuAccount account(Long id) {
        XianyuAccount account = new XianyuAccount();
        account.setId(id);
        account.setStatus(1);
        return account;
    }
}
