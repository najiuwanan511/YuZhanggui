package com.yuzhanggui.service.impl;

import com.yuzhanggui.entity.XianyuGoodsOrder;
import com.yuzhanggui.mapper.XianyuGoodsOrderMapper;
import com.yuzhanggui.service.AutomationRiskGuardService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class DeliveryTaskServiceImplTest {

    @Test
    void doesNotRetryATaskWhoseLeaseBelongsToAnotherWorker() {
        XianyuGoodsOrderMapper orderMapper = mock(XianyuGoodsOrderMapper.class);
        DeliveryTaskServiceImpl service = new DeliveryTaskServiceImpl(orderMapper);
        XianyuGoodsOrder task = new XianyuGoodsOrder();
        task.setLeaseOwner("worker-b");
        when(orderMapper.selectById(1L)).thenReturn(task);

        service.retryOrFail(1L, "worker-a", "send failed");

        verify(orderMapper, never()).retryOrFailTask(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void renewsOnlyTheCurrentWorkersLease() {
        XianyuGoodsOrderMapper orderMapper = mock(XianyuGoodsOrderMapper.class);
        DeliveryTaskServiceImpl service = new DeliveryTaskServiceImpl(orderMapper);
        ReflectionTestUtils.setField(service, "leaseSeconds", 120);
        when(orderMapper.renewTaskLease(2L, "worker-a", 120)).thenReturn(1);

        service.renewLease(2L, "worker-a");

        verify(orderMapper).renewTaskLease(2L, "worker-a", 120);
    }

    @Test
    void successfulDeliveryClearsOnlyTheDeliveryFailureSequence() {
        XianyuGoodsOrderMapper orderMapper = mock(XianyuGoodsOrderMapper.class);
        AutomationRiskGuardService riskGuardService = mock(AutomationRiskGuardService.class);
        DeliveryTaskServiceImpl service = new DeliveryTaskServiceImpl(orderMapper);
        ReflectionTestUtils.setField(service, "automationRiskGuardService", riskGuardService);
        XianyuGoodsOrder task = new XianyuGoodsOrder();
        task.setXianyuAccountId(7L);
        when(orderMapper.selectById(4L)).thenReturn(task);
        when(orderMapper.completeTask(4L, "worker-a")).thenReturn(1);

        service.complete(4L, "worker-a");

        verify(riskGuardService).recordSuccess(7L, "自动发货");
    }

    @Test
    void defersBuyerVerificationWithoutMarkingTheTaskFailed() {
        XianyuGoodsOrderMapper orderMapper = mock(XianyuGoodsOrderMapper.class);
        DeliveryTaskServiceImpl service = new DeliveryTaskServiceImpl(orderMapper);
        String reason = "BUYER_VERIFICATION_PENDING: recipient not verified";
        when(orderMapper.deferBuyerVerificationTask(3L, "worker-a", reason)).thenReturn(1);

        service.deferBuyerVerification(3L, "worker-a", reason);

        verify(orderMapper).deferBuyerVerificationTask(3L, "worker-a", reason);
        verify(orderMapper, never()).retryOrFailTask(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void keepsRetryingWithBackoffAfterMaxConsecutiveDeferrals() {
        XianyuGoodsOrderMapper orderMapper = mock(XianyuGoodsOrderMapper.class);
        DeliveryTaskServiceImpl service = new DeliveryTaskServiceImpl(orderMapper);
        ReflectionTestUtils.setField(service, "maxVerificationDeferrals", 2);
        String reason = "BUYER_VERIFICATION_PENDING: recipient not verified";
        when(orderMapper.deferBuyerVerificationTask(eq(3L), eq("worker-a"), anyString())).thenReturn(1);
        when(orderMapper.deferBuyerVerificationRecoveryTask(eq(3L), eq("worker-a"), anyString())).thenReturn(1);
        XianyuGoodsOrder task = new XianyuGoodsOrder();
        task.setXianyuAccountId(7L);
        task.setOrderId("order-1");
        when(orderMapper.selectById(3L)).thenReturn(task);

        service.deferBuyerVerification(3L, "worker-a", reason);
        service.deferBuyerVerification(3L, "worker-a", reason);

        service.deferBuyerVerification(3L, "worker-a", reason);

        // 初次五分钟重试；达到阈值后改为三十分钟恢复检查，不永久挂起
        verify(orderMapper, times(1)).deferBuyerVerificationTask(eq(3L), eq("worker-a"), anyString());
        verify(orderMapper, times(2)).deferBuyerVerificationRecoveryTask(eq(3L), eq("worker-a"), anyString());
    }
}
