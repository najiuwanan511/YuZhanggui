package com.yuzhanggui.service.impl;

import com.yuzhanggui.mapper.*;
import com.yuzhanggui.service.OnlineUpdateMaintenanceService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ZeroBridgeMaintenanceTest {
    @Test
    void maintenanceStopsBothSubmissionAndBuyerReplyBeforeClaimingWork() {
        var bridge = mock(XianyuZeroBridgeOrderMapper.class);
        var service = mock(ZeroBridgeServiceImpl.class);
        var maintenance = mock(OnlineUpdateMaintenanceService.class);
        when(maintenance.isActive()).thenReturn(true);
        var scheduler = new ZeroBridgeScheduler(bridge, service,
                mock(XianyuGoodsAutoDeliveryConfigMapper.class), mock(XianyuGoodsOrderMapper.class), maintenance);
        scheduler.submitDueOrders();
        scheduler.sendDueResults();
        verifyNoInteractions(bridge, service);
    }
}
