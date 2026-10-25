package com.yuzhanggui.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yuzhanggui.constants.OperationConstants;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.service.impl.CredentialUpdateCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional low-frequency recovery for disconnected accounts. Healthy connections are never touched. */
@Slf4j
@Component
public class AutomaticConnectionRepairScheduler {

    private final XianyuAccountMapper accountMapper;
    private final WebSocketTokenService webSocketTokenService;
    private final WebSocketService webSocketService;
    private final OperationLogService operationLogService;
    private final CredentialUpdateCoordinator credentialUpdateCoordinator;
    private final Executor taskExecutor;
    private final ConcurrentHashMap<Long, Long> nextRepairTimes = new ConcurrentHashMap<>();
    private final AtomicBoolean repairRunning = new AtomicBoolean(false);

    @Autowired(required = false)
    private OnlineUpdateMaintenanceService onlineUpdateMaintenanceService;

    @Value("${app.websocket.automatic-repair.enabled:true}")
    private boolean enabled;

    @Value("${app.websocket.automatic-repair.min-hours:2}")
    private int minHours;

    @Value("${app.websocket.automatic-repair.max-hours:4}")
    private int maxHours;

    @Value("${app.websocket.automatic-repair.account-spacing-minutes:15}")
    private int accountSpacingMinutes;

    private volatile long lastRepairStartedAt;

    public AutomaticConnectionRepairScheduler(XianyuAccountMapper accountMapper,
                                               WebSocketTokenService webSocketTokenService,
                                               WebSocketService webSocketService,
                                               OperationLogService operationLogService,
                                               CredentialUpdateCoordinator credentialUpdateCoordinator,
                                               @Qualifier("taskExecutor") Executor taskExecutor) {
        this.accountMapper = accountMapper;
        this.webSocketTokenService = webSocketTokenService;
        this.webSocketService = webSocketService;
        this.operationLogService = operationLogService;
        this.credentialUpdateCoordinator = credentialUpdateCoordinator;
        this.taskExecutor = taskExecutor;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void runDueRepairs() {
        runDueRepairsAt(System.currentTimeMillis());
    }

    void runDueRepairsAt(long now) {
        if (!enabled || repairRunning.get()
                || (onlineUpdateMaintenanceService != null && onlineUpdateMaintenanceService.isActive())) {
            return;
        }

        List<XianyuAccount> accounts = accountMapper.selectList(new QueryWrapper<XianyuAccount>()
                .eq("status", 1));
        Set<Long> activeAccountIds = new HashSet<>();
        for (XianyuAccount account : accounts) {
            if (account.getId() == null) continue;
            activeAccountIds.add(account.getId());
            nextRepairTimes.computeIfAbsent(account.getId(), ignored -> now + randomRepairDelayMillis());
        }
        nextRepairTimes.keySet().removeIf(accountId -> !activeAccountIds.contains(accountId));

        long spacingMillis = TimeUnit.MINUTES.toMillis(Math.max(1, accountSpacingMinutes));
        if (lastRepairStartedAt > 0 && now - lastRepairStartedAt < spacingMillis) {
            return;
        }

        XianyuAccount dueAccount = accounts.stream()
                .filter(account -> account.getId() != null)
                .filter(account -> !webSocketService.isConnected(account.getId()))
                .filter(account -> nextRepairTimes.getOrDefault(account.getId(), Long.MAX_VALUE) <= now)
                .min(Comparator
                        .comparingLong((XianyuAccount account) -> nextRepairTimes.get(account.getId()))
                        .thenComparing(XianyuAccount::getId))
                .orElse(null);
        if (dueAccount == null) {
            return;
        }

        Long accountId = dueAccount.getId();
        if (webSocketTokenService.isCaptchaPending(accountId)
                || webSocketTokenService.isSessionRenewalPending(accountId)) {
            nextRepairTimes.put(accountId, now + spacingMillis);
            log.info("【自动连接恢复】账号 {} 正在等待安全验证，本次跳过", accountId);
            return;
        }

        if (!repairRunning.compareAndSet(false, true)) {
            return;
        }
        nextRepairTimes.put(accountId, Long.MAX_VALUE);
        try {
            taskExecutor.execute(() -> executeAndReschedule(accountId, now));
        } catch (Exception exception) {
            log.error("【自动连接恢复】账号 {} 无法进入后台执行队列", accountId, exception);
            nextRepairTimes.put(accountId, now + TimeUnit.MINUTES.toMillis(5));
            repairRunning.set(false);
        }
    }

    private void executeAndReschedule(Long accountId, long scheduledAt) {
        lastRepairStartedAt = System.currentTimeMillis();
        try {
            if (onlineUpdateMaintenanceService != null && onlineUpdateMaintenanceService.isActive()) {
                log.info("【自动连接恢复】系统正在在线更新，账号 {} 本次延后", accountId);
                return;
            }
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account == null || !Integer.valueOf(1).equals(account.getStatus())
                    || webSocketTokenService.isCaptchaPending(accountId)
                    || webSocketTokenService.isSessionRenewalPending(accountId)) {
                log.info("【自动连接恢复】账号 {} 已禁用、不存在或正在等待安全验证，本次取消", accountId);
                return;
            }
            recoverDisconnectedAccount(accountId);
        } catch (Exception exception) {
            log.error("【自动连接恢复】账号 {} 执行异常", accountId, exception);
            recordResult(accountId, OperationConstants.Status.FAIL,
                    "自动连接恢复异常：" + safeMessage(exception), System.currentTimeMillis());
        } finally {
            long completedAt = Math.max(scheduledAt, System.currentTimeMillis());
            nextRepairTimes.put(accountId, completedAt + randomRepairDelayMillis());
            repairRunning.set(false);
        }
    }

    private void recoverDisconnectedAccount(Long accountId) {
        long startedAt = System.currentTimeMillis();
        credentialUpdateCoordinator.withAccountLock(accountId, () -> {
            if (webSocketService.isConnected(accountId)) {
                log.debug("【自动连接恢复】账号 {} 已在线，不刷新凭证也不重连", accountId);
                return;
            }
            if (webSocketTokenService.isCaptchaPending(accountId)
                    || webSocketTokenService.isSessionRenewalPending(accountId)) {
                log.info("【自动连接恢复】账号 {} 正在等待安全验证，本次取消", accountId);
                return;
            }
            log.info("【自动连接恢复】账号 {} 尝试复用现有Cookie与Token恢复连接", accountId);
            boolean connected = webSocketService.startWebSocket(accountId);
            int status = connected ? OperationConstants.Status.SUCCESS : OperationConstants.Status.FAIL;
            String description = connected ? "自动连接恢复成功（复用现有凭证）" : "自动连接恢复失败：未强制刷新凭证";
            recordResult(accountId, status, description, startedAt);
        });
    }

    private void recordResult(Long accountId, int status, String description, long startedAt) {
        int durationMs = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0, System.currentTimeMillis() - startedAt));
        operationLogService.log(accountId,
                OperationConstants.Type.REFRESH,
                OperationConstants.Module.WEBSOCKET,
                description,
                status,
                OperationConstants.TargetType.WEBSOCKET,
                String.valueOf(accountId),
                null, null,
                status == OperationConstants.Status.FAIL ? description : null,
                durationMs);
        if (status == OperationConstants.Status.SUCCESS) {
            log.info("【自动连接恢复】账号 {} 恢复成功", accountId);
        } else {
            log.warn("【自动连接恢复】账号 {} 处理结果：{}", accountId, description);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    long randomRepairDelayMillis() {
        int normalizedMin = Math.max(1, minHours);
        int normalizedMax = Math.max(normalizedMin, maxHours);
        long minMillis = TimeUnit.HOURS.toMillis(normalizedMin);
        long maxMillis = TimeUnit.HOURS.toMillis(normalizedMax);
        return ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1);
    }
}
