package com.yuzhanggui.service;

import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AutomationRiskGuardServiceTest {

    @Test
    void accountWideProtectionIsDisabled() {
        XianyuAccountMapper accountMapper = mock(XianyuAccountMapper.class);
        XianyuGoodsOrderMapper orderMapper = mock(XianyuGoodsOrderMapper.class);
        NotificationChannelService notificationService = mock(NotificationChannelService.class);
        AutomationRiskGuardService service = new AutomationRiskGuardService(accountMapper, orderMapper, notificationService);

        assertFalse(service.isPaused(1L));
        assertFalse(service.recordFailure(1L, "自动评价", "接口失败"));
        service.recordSuccess(1L, "自动评价");
        assertEquals("自动化保护已移除", service.resume(1L));
        verifyNoInteractions(accountMapper, orderMapper, notificationService);
    }
}