package com.yuzhanggui.service.impl;

import com.yuzhanggui.entity.XianyuZeroBridgeOrder;
import com.yuzhanggui.mapper.XianyuZeroBridgeOrderMapper;
import com.yuzhanggui.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.yuzhanggui.mapper.XianyuGoodsOrderMapper;
import com.yuzhanggui.service.OnlineUpdateMaintenanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class ZeroBridgeScheduler {
    private final XianyuZeroBridgeOrderMapper bridgeMapper;
    private final ZeroBridgeServiceImpl zeroBridgeService;
    private final XianyuGoodsAutoDeliveryConfigMapper deliveryConfigMapper;
    private final XianyuGoodsOrderMapper orderMapper;
    private final OnlineUpdateMaintenanceService onlineUpdateMaintenanceService;

    public ZeroBridgeScheduler(XianyuZeroBridgeOrderMapper bridgeMapper,
                               ZeroBridgeServiceImpl zeroBridgeService,
                               XianyuGoodsAutoDeliveryConfigMapper deliveryConfigMapper,
                               XianyuGoodsOrderMapper orderMapper,
                               OnlineUpdateMaintenanceService onlineUpdateMaintenanceService) {
        this.bridgeMapper = bridgeMapper;
        this.zeroBridgeService = zeroBridgeService;
        this.deliveryConfigMapper = deliveryConfigMapper;
        this.orderMapper = orderMapper;
        this.onlineUpdateMaintenanceService = onlineUpdateMaintenanceService;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 8000)
    public void submitDueOrders() {
        if (onlineUpdateMaintenanceService.isActive()) return;
        if (!zeroBridgeService.isEnabled()) return;
        bridgeMapper.recoverInterruptedSubmissions();
        for (XianyuZeroBridgeOrder bridge : bridgeMapper.selectDueSubmissions(10)) {
            if (onlineUpdateMaintenanceService.isActive()) return;
            if (bridgeMapper.claimSubmission(bridge.getId()) == 0) continue;
            bridge = bridgeMapper.selectById(bridge.getId());
            try {
                String response = zeroBridgeService.submit(bridge);
                bridgeMapper.markSubmitted(bridge.getId(), response);
                zeroBridgeService.markOrderProgress(bridge.getGoodsOrderId(), "ZERO_PROCESSING", null, null);
                log.info("【账号{}】闲鱼订单已提交 Zero: orderId={}", bridge.getXianyuAccountId(), bridge.getExternalOrderId());
            } catch (Exception e) {
                int attempts = bridge.getSubmitAttempts() == null ? 1 : bridge.getSubmitAttempts();
                long delay = Math.min(300, 5L << Math.min(attempts, 6));
                bridgeMapper.markSubmitRetry(bridge.getId(), LocalDateTime.now().plusSeconds(delay), safe(e.getMessage()));
                zeroBridgeService.markOrderProgress(bridge.getGoodsOrderId(), "ZERO_SUBMIT_RETRY", "ZERO_SUBMIT_FAILED", safe(e.getMessage()));
                log.warn("【账号{}】提交 Zero 失败，{} 秒后重试: orderId={}, error={}",
                        bridge.getXianyuAccountId(), delay, bridge.getExternalOrderId(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 10000)
    public void sendDueResults() {
        if (onlineUpdateMaintenanceService.isActive()) return;
        bridgeMapper.recoverInterruptedReplies();
        for (XianyuZeroBridgeOrder bridge : bridgeMapper.selectDueReplies(10)) {
            if (onlineUpdateMaintenanceService.isActive()) return;
            if (bridgeMapper.claimReply(bridge.getId()) == 0) continue;
            bridge = bridgeMapper.selectById(bridge.getId());
            try {
                if (!zeroBridgeService.sendBuyerMessage(bridge, bridge.getResultSummary())) {
                    throw new IllegalStateException("闲鱼消息未取得送达确认");
                }
                boolean failed = bridge.getResultSummary() != null && bridge.getResultSummary().startsWith("❌");
                String finalStatus = failed ? "FAILED" : "COMPLETED";
                zeroBridgeService.finalizeReply(bridge, failed);
                if (!failed) {
                    var config = deliveryConfigMapper.findByAccountIdAndGoodsIdNoSku(
                            bridge.getXianyuAccountId(), bridge.getXyGoodsId());
                    if (config != null && Integer.valueOf(1).equals(config.getAutoConfirmShipment())) {
                        orderMapper.enqueueConfirmShipment(bridge.getXianyuAccountId(), bridge.getExternalOrderId());
                    }
                }
                log.info("【账号{}】Zero 最终结果已回复买家: orderId={}, status={}",
                        bridge.getXianyuAccountId(), bridge.getExternalOrderId(), finalStatus);
            } catch (Exception e) {
                int attempts = bridge.getReplyAttempts() == null ? 1 : bridge.getReplyAttempts();
                long delay = Math.min(300, 5L << Math.min(attempts, 6));
                bridgeMapper.markReplyRetry(bridge.getId(), LocalDateTime.now().plusSeconds(delay), safe(e.getMessage()));
                log.warn("【账号{}】Zero 结果回复失败，{} 秒后重试: orderId={}, error={}",
                        bridge.getXianyuAccountId(), delay, bridge.getExternalOrderId(), e.getMessage());
            }
        }
    }

    private static String safe(String value) {
        String text = value == null ? "未知错误" : value;
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
