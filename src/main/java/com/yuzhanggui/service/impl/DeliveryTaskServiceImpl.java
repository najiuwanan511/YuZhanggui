package com.yuzhanggui.service.impl;

import com.yuzhanggui.entity.XianyuGoodsOrder;
import com.yuzhanggui.enums.DeliveryChannel;
import com.yuzhanggui.enums.DeliveryStatus;
import com.yuzhanggui.mapper.XianyuGoodsOrderMapper;
import com.yuzhanggui.service.DeliveryTaskService;
import com.yuzhanggui.service.AutomationExceptionNotificationService;
import com.yuzhanggui.service.AutomationRiskGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DeliveryTaskServiceImpl implements DeliveryTaskService {

    private final XianyuGoodsOrderMapper orderMapper;

    @Autowired(required = false)
    private AutomationExceptionNotificationService automationExceptionNotificationService;

    @Autowired(required = false)
    private AutomationRiskGuardService automationRiskGuardService;

    @Value("${app.delivery.lease-seconds:120}")
    private int leaseSeconds;

    @Value("${app.delivery.max-attempts:3}")
    private int maxAttempts;

    /** 连续核验失败后改为低频恢复检查；不永久丢弃尚未发送的订单。 */
    @Value("${app.delivery.max-verification-deferrals:3}")
    private int maxVerificationDeferrals = 3;

    /**
     * 任务连续"买家身份待核验"次数。进程内计数，重启后重新累计
     * （最坏情况只是多几次 5 分钟重试，不影响正确性）。
     */
    private final Map<Long, Integer> buyerVerificationDeferrals = new ConcurrentHashMap<>();

    public DeliveryTaskServiceImpl(XianyuGoodsOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional
    public XianyuGoodsOrder discover(XianyuGoodsOrder order, DeliveryChannel channel) {
        int quantity = order.getBuyNum() != null && order.getBuyNum() > 0 ? order.getBuyNum() : 1;
        order.setDeliveryStatus(DeliveryStatus.PENDING.name());
        order.setExpectedQuantity(quantity);
        order.setDeliveryChannel(channel.name());
        orderMapper.insert(order);

        XianyuGoodsOrder persisted = order.getId() != null ? orderMapper.selectById(order.getId()) : null;
        if (persisted == null && order.getOrderId() != null) {
            persisted = orderMapper.selectByAccountIdAndOrderId(order.getXianyuAccountId(), order.getOrderId());
        }
        if (persisted == null && order.getPnmId() != null) {
            persisted = orderMapper.selectByPnmId(order.getXianyuAccountId(), order.getPnmId());
        }
        if (persisted == null) {
            throw new IllegalStateException("订单任务持久化失败");
        }
        return persisted;
    }

    @Override
    @Transactional
    public List<XianyuGoodsOrder> claimDueTasks(String workerId, int limit) {
        int batchSize = Math.max(1, Math.min(limit, 100));
        orderMapper.markExpiredExternalAttemptsForReview();
        orderMapper.skipNonDeliverableTasks();
        List<XianyuGoodsOrder> tasks = orderMapper.lockDueTasks(batchSize);
        if (tasks.isEmpty()) {
            return tasks;
        }
        List<Long> taskIds = tasks.stream().map(XianyuGoodsOrder::getId).toList();
        if (orderMapper.claimTasks(taskIds, workerId, leaseSeconds) != taskIds.size()) {
            throw new IllegalStateException("订单任务租约领取冲突");
        }
        tasks.forEach(task -> {
            task.setDeliveryStatus(DeliveryStatus.PROCESSING.name());
            task.setAttemptCount((task.getAttemptCount() != null ? task.getAttemptCount() : 0) + 1);
        });
        return tasks;
    }

    @Override
    public void complete(Long taskId, String workerId) {
        buyerVerificationDeferrals.remove(taskId);
        XianyuGoodsOrder task = orderMapper.selectById(taskId);
        if (orderMapper.completeTask(taskId, workerId) == 0) {
            log.warn("任务租约已失效，忽略完成操作: taskId={}", taskId);
        } else if (task != null && automationRiskGuardService != null) {
            automationRiskGuardService.recordSuccess(task.getXianyuAccountId(), "自动发货");
        }
    }

    @Override
    public void retryOrFail(Long taskId, String workerId, String errorMessage) {
        buyerVerificationDeferrals.remove(taskId);
        XianyuGoodsOrder task = orderMapper.selectById(taskId);
        if (task == null || !workerId.equals(task.getLeaseOwner())) {
            log.warn("任务租约已失效，忽略重试操作: taskId={}", taskId);
            return;
        }
        int attempts = task.getAttemptCount() != null ? task.getAttemptCount() : 0;
        boolean exhausted = attempts >= maxAttempts;
        String status = exhausted ? DeliveryStatus.FAILED.name() : DeliveryStatus.RETRY_WAIT.name();
        LocalDateTime nextRetryTime = exhausted ? null : LocalDateTime.now().plusSeconds(Math.min(300L, 5L << attempts));
        String safeMessage = errorMessage == null ? "自动发货失败" : errorMessage.substring(0, Math.min(errorMessage.length(), 500));
        if (orderMapper.retryOrFailTask(taskId, status, nextRetryTime, safeMessage, workerId) == 0) {
            log.warn("任务租约已失效，忽略重试操作: taskId={}", taskId);
        } else if (exhausted) {
            notifyException(task, "自动发货", safeMessage);
            if (automationRiskGuardService != null) {
                automationRiskGuardService.recordFailure(task.getXianyuAccountId(), "自动发货", safeMessage);
            }
        }
    }

    @Override
    public void deferBuyerVerification(Long taskId, String workerId, String reason) {
        String safeReason = reason == null ? "订单买家身份暂时无法核验，卡密未发送" :
                reason.substring(0, Math.min(reason.length(), 500));
        int threshold = Math.max(1, maxVerificationDeferrals);
        int consecutive = Math.min(threshold + 1, buyerVerificationDeferrals.getOrDefault(taskId, 0) + 1);
        boolean slowRetry = consecutive >= threshold;
        if (slowRetry) {
            safeReason = "订单详情暂时无法核验，卡密未发送；30分钟后自动重试，更新账号Cookie后提前恢复。 " + safeReason;
            safeReason = safeReason.substring(0, Math.min(safeReason.length(), 500));
        }
        int updated = slowRetry
                ? orderMapper.deferBuyerVerificationRecoveryTask(taskId, workerId, safeReason)
                : orderMapper.deferBuyerVerificationTask(taskId, workerId, safeReason);
        if (updated == 0) {
            buyerVerificationDeferrals.remove(taskId);
            log.warn("任务租约已失效，忽略买家身份延迟核验: taskId={}", taskId);
            return;
        }
        buyerVerificationDeferrals.put(taskId, consecutive);
        log.warn("订单买家身份待核验，{}分钟后自动重试: taskId={}", slowRetry ? 30 : 5, taskId);
        if (consecutive == threshold) {
            XianyuGoodsOrder task = orderMapper.selectById(taskId);
            if (task != null) notifyException(task, "自动发货等待凭证或接口恢复", safeReason);
        }
    }

    @Override
    public void markReviewRequired(Long taskId, String workerId, String errorMessage) {
        buyerVerificationDeferrals.remove(taskId);
        String safeMessage = errorMessage == null ? "发送结果不确定，请人工核对" :
                errorMessage.substring(0, Math.min(errorMessage.length(), 500));
        if (orderMapper.markTaskReviewRequired(taskId, safeMessage, workerId) == 0) {
            log.warn("任务租约已失效，忽略人工复核操作: taskId={}", taskId);
        } else {
            XianyuGoodsOrder task = orderMapper.selectById(taskId);
            if (task != null) {
                notifyException(task, "自动发货需人工核对", safeMessage);
            }
        }
    }

    @Override
    public boolean renewLease(Long taskId, String workerId) {
        return orderMapper.renewTaskLease(taskId, workerId, leaseSeconds) > 0;
    }

    @Override
    public boolean isLeaseActive(Long taskId, String workerId) {
        return taskId != null && workerId != null && orderMapper.countActiveLease(taskId, workerId) > 0;
    }

    @Override
    public boolean beginExternalAttempt(Long taskId, String workerId) {
        return taskId != null && workerId != null
                && orderMapper.markExternalAttemptStarted(taskId, workerId) > 0;
    }

    @Override
    public boolean requeue(Long taskId) {
        buyerVerificationDeferrals.remove(taskId);
        return orderMapper.requeueTask(taskId) > 0;
    }

    @Override
    public void pauseAccountTasks(Long accountId) {
        if (accountId != null) {
            orderMapper.pauseTasksByAccount(accountId);
        }
    }

    @Override
    public void pauseClaimedTask(Long taskId, String workerId) {
        if (taskId != null && workerId != null) {
            orderMapper.pauseClaimedTask(taskId, workerId);
        }
    }

    private void notifyException(XianyuGoodsOrder task, String action, String reason) {
        if (automationExceptionNotificationService == null) {
            return;
        }
        Map<String, Object> details = new HashMap<>();
        details.put("orderId", task.getOrderId());
        details.put("goodsName", task.getGoodsTitle());
        details.put("buyerName", task.getBuyerUserName());
        automationExceptionNotificationService.notify(task.getXianyuAccountId(), action, reason, details);
    }
}
