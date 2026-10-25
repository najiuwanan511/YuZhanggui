package com.yuzhanggui.service.impl;

import com.yuzhanggui.config.WebSocketConfig;
import com.yuzhanggui.constants.OperationConstants;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.service.AccountService;
import com.yuzhanggui.service.OperationLogService;

import com.yuzhanggui.service.WebSocketService;
import com.yuzhanggui.service.WebSocketTokenService;
import com.yuzhanggui.service.NotificationChannelService;
import com.yuzhanggui.utils.XianyuSignUtils;
import com.yuzhanggui.websocket.WebSocketInitializer;
import com.yuzhanggui.websocket.WebSocketMessageHandler;
import com.yuzhanggui.websocket.XianyuWebSocketClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yuzhanggui.service.OnlineUpdateMaintenanceService;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket服务实现类
 * 参考Python代码的XianyuAutoAsync类
 * 增强功能：
 * 1. Token自动刷新机制
 * 2. 心跳超时检测
 * 3. 连接重连机制
 */
@Slf4j
@Service
public class WebSocketServiceImpl implements WebSocketService {

    @Autowired
    private AccountService accountService;
    
    @Autowired
    private WebSocketMessageHandler messageHandler;
    
    @Autowired
    private WebSocketTokenService tokenService;
    
    @Autowired
    private WebSocketInitializer initializer;
    
    @Autowired
    private WebSocketConfig config;
    
    @Autowired
    private com.yuzhanggui.utils.AccountDisplayNameUtils displayNameUtils;
    
    @Autowired
    private OperationLogService operationLogService;
    
    @Autowired(required = false)
    private com.yuzhanggui.service.EmailNotifyService emailNotifyService;

    @Autowired
    private com.yuzhanggui.mapper.XianyuAccountMapper xianyuAccountMapper;

    @Autowired
    private NotificationChannelService notificationChannelService;

    @Autowired
    private CredentialUpdateCoordinator credentialUpdateCoordinator;

    @Autowired(required = false)
    private OnlineUpdateMaintenanceService onlineUpdateMaintenanceService;

    private final AtomicBoolean notificationScanRunning = new AtomicBoolean(false);
    private volatile boolean shuttingDown;


    // 存储WebSocket客户端
    private final Map<Long, XianyuWebSocketClient> webSocketClients = new ConcurrentHashMap<>();
    
    // 心跳定时器
    @Autowired
    @Qualifier("webSocketScheduler")
    private ScheduledExecutorService webSocketScheduler;
    
    // 心跳任务
    private final Map<Long, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    
    // Token刷新任务
    private final Map<Long, Future<?>> tokenRefreshTasks = new ConcurrentHashMap<>();

    // Token刷新失败后的延迟重试任务
    private final Map<Long, Future<?>> tokenRetryTasks = new ConcurrentHashMap<>();

    // 同一账号同一时刻只执行一次Token刷新。
    private final Map<Long, AtomicBoolean> tokenRefreshInProgress = new ConcurrentHashMap<>();
    
    // 心跳响应时间记录
    private final Map<Long, Long> lastHeartbeatResponseTimes = new ConcurrentHashMap<>();
    
    // 心跳发送时间记录（参考Python的last_heartbeat_time）
    private final Map<Long, Long> lastHeartbeatSendTimes = new ConcurrentHashMap<>();
    
    // 连接重启标志
    private final Map<Long, Boolean> connectionRestartFlags = new ConcurrentHashMap<>();
    
    // 重连任务（防止重复重连）
    private final Map<Long, Future<?>> reconnectTasks = new ConcurrentHashMap<>();
    
    @Autowired
    @Qualifier("websocketMessageExecutor")
    private ExecutorService websocketMessageExecutor;
    
    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    private final Map<Long, Integer> heartbeatTimeoutCounts = new ConcurrentHashMap<>();
    private static final int HEARTBEAT_CONSECUTIVE_TIMEOUT_LIMIT = 3;

    // 重连次数记录（参考Python的无限重连但有退避）
    private final Map<Long, AtomicInteger> reconnectAttemptCounts = new ConcurrentHashMap<>();

    // 所有掉线通知共用防抖，独立于邮件渠道的开关。
    private final Map<Long, Long> lastDisconnectNotifyTimes = new ConcurrentHashMap<>();

    // 重连失败达到此次数后触发通知（另有异常断开和定期检查兜底）。
    private static final int RECONNECT_NOTIFY_THRESHOLD = 3;

    // 同一账号持续掉线时，每小时最多重复提醒一次。
    private static final long NOTIFY_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
    private static final long TOKEN_REFRESH_BASE_LEAD_MS = TimeUnit.MINUTES.toMillis(60);
    private static final long TOKEN_REFRESH_JITTER_MIN_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long TOKEN_REFRESH_JITTER_MAX_MS = TimeUnit.MINUTES.toMillis(20);


    /**
     * 闲鱼WebSocket URL
     * 参考Python代码：wss://wss-goofish.dingtalk.com/
     */
    private static final String WEBSOCKET_URL = "wss://wss-goofish.dingtalk.com/";

    /** 自动流程等待平台回执的默认超时（秒），保持原有行为不变 */
    private static final int DEFAULT_ACK_TIMEOUT_SECONDS = 10;

    @Override
    public boolean startWebSocket(Long accountId) {
        return startWebSocket(accountId, false);
    }

    /**
     * A freshly submitted Cookie may belong to an account still marked -2 from
     * the previous verification episode. Let that one credential recovery pass
     * through token validation; normal/manual starts remain blocked until the
     * token request itself succeeds.
     */
    private boolean startWebSocket(Long accountId, boolean allowCredentialRecovery) {
        try {
            log.info("启动WebSocket连接: accountId={}", accountId);
            if (!isAccountActive(accountId)
                    && !(allowCredentialRecovery && isVerificationRecoveryCandidate(accountId))) {
                log.info("跳过WebSocket连接：账号已禁用、不存在或仍待验证, accountId={}", accountId);
                return false;
            }


            // 检查是否已经连接
            if (webSocketClients.containsKey(accountId)) {
                XianyuWebSocketClient existingClient = webSocketClients.get(accountId);
                if (existingClient.isConnected()) {
                    log.info("WebSocket已连接: accountId={}", accountId);
                    return true;
                } else {
                    // 关闭旧连接
                    stopWebSocket(accountId);
                }
            }

            // 获取Cookie
            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("未找到账号Cookie: accountId={}", accountId);
                throw new com.yuzhanggui.exception.CookieNotFoundException("未找到账号Cookie，请先配置Cookie");
            }

            // 解析Cookie
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookieStr);
            
            // 生成设备ID（参考Python的generate_device_id）
            String unb = cookies.get("unb");
            if (unb == null || unb.isEmpty()) {
                log.error("Cookie中缺少unb字段: accountId={}", accountId);
                throw new com.yuzhanggui.exception.CookieExpiredException("Cookie中缺少unb字段，Cookie可能已过期或无效");
            }
            // 使用持久化的设备ID（如果数据库中已有则使用，否则生成新的并保存）
            String deviceId = accountService.getOrGenerateDeviceId(accountId, unb);
            if (deviceId == null || deviceId.isEmpty()) {
                log.error("获取或生成设备ID失败: accountId={}", accountId);
                throw new RuntimeException("无法获取或生成设备ID");
            }
            log.info("使用设备ID: accountId={}, deviceId={}", accountId, deviceId);
            
            // 获取accessToken（参考Python的refresh_token）
            log.info("正在获取accessToken: accountId={}", accountId);
            String accessToken = tokenService.getAccessToken(accountId);
            if (accessToken == null || accessToken.isEmpty()) {
                log.error("获取accessToken失败: accountId={}", accountId);
                log.error("无法继续WebSocket连接，请检查Cookie是否有效");
                throw new com.yuzhanggui.exception.TokenInvalidException("无法获取WebSocket Token，请检查Cookie是否有效");
            }
            log.info("accessToken获取成功: accountId={}, token长度={}", accountId, accessToken.length());
            
            // 调用通用连接方法
            return connectWebSocket(accountId, cookieStr, deviceId, accessToken, unb);

        } catch (com.yuzhanggui.exception.CaptchaRequiredException e) {
            log.warn("启动WebSocket需要滑块验证: accountId={}, url={}", accountId, e.getCaptchaUrl());
            throw e; // 重新抛出，让Controller处理
        } catch (Exception e) {
            log.error("启动WebSocket失败: accountId={}", accountId, e);
            return false;
        }
    }

    @Override
    public boolean startWebSocketWithToken(Long accountId, String accessToken) {
        try {
            log.info("========== 使用手动Token启动WebSocket连接 ==========");
            log.info("【账号{}】accountId={}", accountId, accountId);
            if (!isAccountActive(accountId)) {
                log.info("跳过手动Token连接：账号已禁用、不存在或仍待验证, accountId={}", accountId);
                return false;
            }

            log.info("【账号{}】accessToken长度={}", accountId, accessToken != null ? accessToken.length() : 0);

            // 检查是否已经连接
            if (webSocketClients.containsKey(accountId)) {
                XianyuWebSocketClient existingClient = webSocketClients.get(accountId);
                if (existingClient.isConnected()) {
                    log.info("【账号{}】WebSocket已连接", accountId);
                    return true;
                } else {
                    // 关闭旧连接
                    log.info("【账号{}】关闭旧连接", accountId);
                    stopWebSocket(accountId);
                }
            }

            // 获取Cookie
            String cookieStr = accountService.getCookieByAccountId(accountId);
            if (cookieStr == null || cookieStr.isEmpty()) {
                log.error("【账号{}】未找到账号Cookie", accountId);
                throw new com.yuzhanggui.exception.CookieNotFoundException("未找到账号Cookie，请先配置Cookie");
            }
            log.info("【账号{}】Cookie长度={}", accountId, cookieStr.length());

            // 解析Cookie
            Map<String, String> cookies = XianyuSignUtils.parseCookies(cookieStr);
            log.info("【账号{}】解析到{}个Cookie字段", accountId, cookies.size());
            
            // 生成设备ID
            String unb = cookies.get("unb");
            if (unb == null || unb.isEmpty()) {
                log.error("【账号{}】Cookie中缺少unb字段", accountId);
                throw new com.yuzhanggui.exception.CookieExpiredException("Cookie中缺少unb字段，Cookie可能已过期或无效");
            }
            // 使用持久化的设备ID
            String deviceId = accountService.getOrGenerateDeviceId(accountId, unb);
            if (deviceId == null || deviceId.isEmpty()) {
                log.error("【账号{}】获取或生成设备ID失败", accountId);
                throw new RuntimeException("无法获取或生成设备ID");
            }
            log.info("【账号{}】设备ID={}", accountId, deviceId);
            
            log.info("【账号{}】准备调用通用连接方法（Token将在注册成功后保存）...", accountId);
            
            // 调用通用连接方法
            boolean result = connectWebSocket(accountId, cookieStr, deviceId, accessToken, unb);
            
            log.info("【账号{}】连接结果={}", accountId, result);
            log.info("========== 手动Token启动流程结束 ==========");
            
            return result;

        } catch (Exception e) {
            log.error("【账号{}】使用手动Token启动WebSocket失败", accountId, e);
            return false;
        }
    }

    /**
     * 通用WebSocket连接方法
     */
    private boolean connectWebSocket(Long accountId, String cookieStr, String deviceId, String accessToken, String unb) throws Exception {
        try {
            // 构建WebSocket请求头（参考Python的WEBSOCKET_HEADERS配置）
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", cookieStr);
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
            headers.put("Origin", "https://www.goofish.com");
            headers.put("Host", "wss-goofish.dingtalk.com");
            headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            headers.put("Cache-Control", "no-cache");
            headers.put("Pragma", "no-cache");
            headers.put("Connection", "Upgrade");
            headers.put("Upgrade", "websocket");

            // 创建WebSocket客户端（参考Python的_create_websocket_connection）
            URI serverUri = new URI(WEBSOCKET_URL);
            XianyuWebSocketClient client = new XianyuWebSocketClient(
                    serverUri, headers, String.valueOf(accountId), displayNameUtils, websocketMessageExecutor);
            
            // 设置当前用户ID（从Cookie的unb字段获取）
            client.setMyUserId(unb);
            
            // 设置消息处理器
            client.setMessageHandler(messageHandler);
            
            // 设置注册成功回调（保存Token）
            final String finalAccessToken = accessToken;
            client.setOnRegistrationSuccess(() -> {
                log.info("【账号{}】注册成功回调被触发，开始保存Token到数据库", accountId);
                tokenService.saveToken(accountId, finalAccessToken);
                log.info("【账号{}】✅ Token已成功保存到数据库", accountId);
            });
            
            // 设置Token失效回调（自动重连）
            // 参考Python: Token失效时设置connection_restart_flag=True，关闭WebSocket触发重连
            client.setOnTokenExpired(() -> {
                log.warn("【账号{}】Token失效(401)，触发自动重连流程...", accountId);
                try {
                    // 参考Python: 设置连接重启标志
                    connectionRestartFlags.put(accountId, true);
                    
                    // 停止当前连接
                    stopWebSocket(accountId);
                    
                    // 清除旧Token
                    tokenService.clearToken(accountId);
                    
                    // 重新启动连接（会自动刷新Token）
                    log.info("【账号{}】重新启动WebSocket连接（自动刷新Token）", accountId);
                    boolean success = startWebSocket(accountId);
                    
                    if (success) {
                        log.info("【账号{}】✅ Token失效后自动重连成功", accountId);
                    } else {
                        log.error("【账号{}】❌ Token失效后自动重连失败，将通过重连机制继续尝试", accountId);
                        // 参考Python: 失败后重连机制会继续尝试
                    }
                } catch (Exception e) {
                    log.error("【账号{}】Token失效自动重连异常", accountId, e);
                    // 参考Python: 异常后外层while True会继续重试
                    scheduleReconnect(accountId, config.getReconnectDelay(), false);
                }
            });
            
            // 设置心跳响应回调（更新心跳响应时间）
            client.setOnHeartbeatResponse(() -> {
                if (webSocketClients.get(accountId) == client) updateHeartbeatResponseTime(accountId);
            });
            
            // 设置连接关闭回调（参考Python的finally块中重连逻辑）
            client.setOnConnectionClosed(() -> handleUnexpectedConnectionClosed(accountId, client));

            // 连接WebSocket（参考Python的connect方法）
            log.info("正在连接WebSocket: {}", WEBSOCKET_URL);
            log.debug("WebSocket请求头已生成，字段数: {}（敏感值不写入日志）", headers.size());
            
            boolean connected = client.connectBlocking(10, TimeUnit.SECONDS);
            
            if (connected) {
                webSocketClients.put(accountId, client);
                
                // 执行WebSocket初始化流程（参考Python的init方法）
                log.info("开始WebSocket初始化流程: accountId={}", accountId);
                initializer.initialize(client, accessToken, deviceId, String.valueOf(accountId));
                
                // 握手和初始化均成功后才恢复账号状态；后续定时任务会读取该状态。
                markAccountConnected(accountId);

                // 启动心跳任务和下一次Token刷新
                startHeartbeat(accountId, client);
                reconnectAttemptCounts.remove(accountId);
                
                log.info("WebSocket连接成功: accountId={}", accountId);
                log.info("连接状态: isOpen={}, isClosed={}", 
                        client.isOpen(), client.isClosed());
                
                // 记录操作日志
                operationLogService.log(accountId, 
                    OperationConstants.Type.CONNECT, 
                    OperationConstants.Module.WEBSOCKET,
                    "WebSocket连接成功", 
                    OperationConstants.Status.SUCCESS,
                    OperationConstants.TargetType.WEBSOCKET, 
                    String.valueOf(accountId),
                    null, null, null, null);
                
                return true;
            } else {
                log.error("WebSocket连接失败: accountId={}", accountId);
                log.error("连接状态: isOpen={}, isClosed={}", 
                        client.isOpen(), client.isClosed());
                
                // 记录操作日志
                operationLogService.log(accountId, 
                    OperationConstants.Type.CONNECT, 
                    OperationConstants.Module.WEBSOCKET,
                    "WebSocket连接失败", 
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.WEBSOCKET, 
                    String.valueOf(accountId),
                    null, null, null, null);
                
                return false;
            }
        } catch (Exception e) {
            log.error("连接WebSocket异常: accountId={}", accountId, e);
            
            // 记录操作日志
            operationLogService.log(accountId, 
                OperationConstants.Type.CONNECT, 
                OperationConstants.Module.WEBSOCKET,
                "WebSocket连接异常: " + e.getMessage(), 
                OperationConstants.Status.FAIL,
                OperationConstants.TargetType.WEBSOCKET, 
                String.valueOf(accountId),
                null, null, null, null);
            
            throw e;
        }
    }

    @Override
    public boolean stopWebSocket(Long accountId) {
        return credentialUpdateCoordinator.withAccountLock(accountId, () -> stopWebSocket(accountId, true));
    }

    private boolean stopWebSocket(Long accountId, boolean cancelReconnect) {
        try {
            log.info("停止WebSocket连接: accountId={}", accountId);

            // 停止心跳任务
            stopHeartbeat(accountId, cancelReconnect);

            // 关闭WebSocket连接
            XianyuWebSocketClient client = webSocketClients.remove(accountId);
            if (client != null) {
                // 标记为主动关闭，防止onClose回调触发自动重连
                // 重连由调用方自行决定是否需要
                client.setIntentionalClose(true);
                client.close();
                log.info("WebSocket连接已关闭: accountId={}", accountId);
                
                // 记录操作日志
                operationLogService.log(accountId, 
                    OperationConstants.Type.DISCONNECT, 
                    OperationConstants.Module.WEBSOCKET,
                    "WebSocket连接已关闭", 
                    OperationConstants.Status.SUCCESS,
                    OperationConstants.TargetType.WEBSOCKET, 
                    String.valueOf(accountId),
                    null, null, null, null);
                
                return true;
            } else {
                log.warn("WebSocket连接不存在: accountId={}", accountId);
                return false;
            }

        } catch (Exception e) {
            log.error("停止WebSocket失败: accountId={}", accountId, e);
            return false;
        }
    }

    @Override
    public boolean restartAfterCredentialUpdate(Long accountId) {
        return credentialUpdateCoordinator.withAccountLock(accountId,
                () -> restartAfterCredentialUpdateLocked(accountId));
    }

    private boolean restartAfterCredentialUpdateLocked(Long accountId) {
        try {
            log.info("凭证已更新，开始重新校验并重建WebSocket连接: accountId={}", accountId);

            if (!isAccountConnectableAfterCredentialUpdate(accountId)) {
                log.info("凭证已更新但账号未启用，不执行重连: accountId={}", accountId);
                return false;
            }

            // 清除旧凭证状态，确保新Cookie立即参与Token获取和连接建立
            tokenService.clearAccountRuntimeState(accountId);
            stopWebSocket(accountId);
            tokenService.clearToken(accountId);

            // status=-2 is allowed only for this one recovery attempt. Account
            // status returns to normal only after WebSocket initialization.
            boolean success = startWebSocket(accountId, true);
            if (!success) {
                if (!tokenService.isCaptchaPending(accountId)) {
                    scheduleReconnect(accountId, config.getReconnectDelay(), false);
                }
            } else {
                AtomicInteger attemptCount = reconnectAttemptCounts.get(accountId);
                if (attemptCount != null) {
                    attemptCount.set(0);
                }
            }
            return success;
        } catch (com.yuzhanggui.exception.CaptchaRequiredException e) {
            log.warn("凭证更新后仍需人工安全验证，已暂停自动重连: accountId={}", accountId);
            return false;
        } catch (Exception e) {
            log.error("凭证更新后重建WebSocket连接失败: accountId={}", accountId, e);
            scheduleReconnect(accountId, config.getReconnectDelay(), false);
            return false;
        }
    }

    private boolean isVerificationRecoveryCandidate(Long accountId) {
        if (accountId == null) return false;
        try {
            XianyuAccount account = xianyuAccountMapper.selectById(accountId);
            return account != null && Integer.valueOf(-2).equals(account.getStatus());
        } catch (Exception exception) {
            log.warn("读取账号验证恢复状态失败: accountId={}, reason={}", accountId, exception.getMessage());
            return false;
        }
    }

    private boolean isAccountConnectableAfterCredentialUpdate(Long accountId) {
        if (accountId == null) return false;
        try {
            XianyuAccount account = xianyuAccountMapper.selectById(accountId);
            return account != null && !Integer.valueOf(0).equals(account.getStatus());
        } catch (Exception exception) {
            log.warn("读取账号凭证恢复状态失败: accountId={}, reason={}", accountId, exception.getMessage());
            return false;
        }
    }

    private void markAccountConnected(Long accountId) {
        // A recovered account starts a new incident if it later disconnects again.
        lastDisconnectNotifyTimes.remove(accountId);
        try {
            XianyuAccount account = xianyuAccountMapper.selectById(accountId);
            if (account != null && Integer.valueOf(-2).equals(account.getStatus())) {
                account.setStatus(1);
                xianyuAccountMapper.updateById(account);
                log.info("【账号{}】WebSocket已连接，账号状态恢复为正常", accountId);
            }
        } catch (Exception exception) {
            log.error("【账号{}】WebSocket连接后恢复账号状态失败", accountId, exception);
        }
    }

    @Override
    public boolean isConnected(Long accountId) {
        XianyuWebSocketClient client = webSocketClients.get(accountId);
        return client != null && client.isConnected();
    }

    @Override
    public void stopAllWebSockets() {
        log.info("停止所有WebSocket连接");
        
        for (Long accountId : webSocketClients.keySet()) {
            stopWebSocket(accountId);
        }
        
    }

    /**
     * 启动心跳任务
     * 增强功能：心跳超时检测（完全对齐Python逻辑）
     * 
     * Python心跳逻辑：
     * 1. 每隔heartbeat_interval秒发送一次心跳
     * 2. 检查上次心跳响应时间，如果超过(heartbeat_interval + heartbeat_timeout)则认为连接断开
     * 3. 心跳循环break后，外层while True循环会自动重连
     */
    private void startHeartbeat(Long accountId, XianyuWebSocketClient client) {
        // 初始化心跳响应时间（秒级时间戳，对齐Python）
        long currentTime = System.currentTimeMillis() / 1000;
        lastHeartbeatResponseTimes.put(accountId, currentTime);
        lastHeartbeatSendTimes.put(accountId, currentTime);
        heartbeatTimeoutCounts.remove(accountId);
        
        // 心跳发送任务（参考Python的heartbeat_loop）
        // 立即发送第一次心跳,防止连接空闲被关闭
        try {
            if (client.isConnected()) {
                client.sendHeartbeat();
                log.info("【账号{}】已发送初始心跳", accountId);
            }
        } catch (Exception e) {
            log.error("发送初始心跳失败: accountId={}", accountId, e);
        }
        
        // 参考Python: heartbeat_loop 中每1秒检查一次
        ScheduledFuture<?> heartbeatTask = webSocketScheduler.scheduleAtFixedRate(
            () -> {
                try {
                    if (webSocketClients.get(accountId) != client) return;
                    long now = System.currentTimeMillis() / 1000;
                    
                    // 参考Python: 每隔heartbeat_interval秒发送一次心跳
                    Long lastSendTime = lastHeartbeatSendTimes.get(accountId);
                    if (lastSendTime == null || now - lastSendTime >= config.getHeartbeatInterval()) {
                        if (client.isConnected()) {
                            client.sendHeartbeat();
                            lastHeartbeatSendTimes.put(accountId, now);
                            log.debug("【账号{}】心跳已发送", accountId);
                        }
                    }
                    
                    // 参考Python: 检查上次心跳响应时间
                    // Python: if (current_time - self.last_heartbeat_response) > (self.heartbeat_interval + self.heartbeat_timeout):
                    checkHeartbeatTimeout(accountId, now);
                } catch (Exception e) {
                    log.error("【账号{}】心跳任务异常", accountId, e);
                }
            },
            1, 1, TimeUnit.SECONDS  // 参考Python: 每秒检查一次
        );
        
        heartbeatTasks.put(accountId, heartbeatTask);
        log.info("心跳任务已启动: accountId={}, 心跳间隔{}秒, 超时阈值{}+{}秒", 
                accountId, config.getHeartbeatInterval(), config.getHeartbeatInterval(), config.getHeartbeatTimeout());
        
        // 启动Token自动刷新任务（参考Python的token_refresh_loop）
        scheduleTokenRefreshByExpiry(accountId);
    }
    
    private void checkHeartbeatTimeout(Long accountId, long now) {
        Long lastResponse = lastHeartbeatResponseTimes.get(accountId);
        if (lastResponse == null) return;
        long window = Math.max(1L, (long) config.getHeartbeatInterval() + config.getHeartbeatTimeout());
        // 每秒检查，但只按完整的无响应窗口计数；默认25秒一轮，75秒才判死。
        int missed = (int) Math.min(HEARTBEAT_CONSECUTIVE_TIMEOUT_LIMIT,
                Math.max(0L, now - lastResponse) / window);
        Integer previous = heartbeatTimeoutCounts.put(accountId, missed);
        if (missed > 0 && (previous == null || missed > previous)) {
            log.warn("【账号{}】心跳连续{}个窗口无响应（每窗口{}秒）", accountId, missed, window);
            if (missed >= HEARTBEAT_CONSECUTIVE_TIMEOUT_LIMIT) handleConnectionLost(accountId);
        }
    }

    /** 调度线程只投递；CallerRunsPolicy 满载时也不能在此执行网络/数据库操作。 */
    private Future<?> scheduleSocketWork(Runnable action, long delay, TimeUnit unit) {
        FutureTask<Void> work = new FutureTask<>(action, null);
        webSocketScheduler.schedule(() -> dispatchSocketWork(work), delay, unit);
        return work;
    }

    private void dispatchSocketWork(FutureTask<Void> work) {
        if (work.isCancelled()) return;
        Thread submittingThread = Thread.currentThread();
        try {
            taskExecutor.execute(() -> {
                if (Thread.currentThread() == submittingThread) {
                    throw new RejectedExecutionException("Socket work cannot run on its scheduling thread");
                }
                work.run();
            });
        } catch (RejectedExecutionException exception) {
            // 保留工作对象与取消能力，队列满时延后投递，不触发阻塞式建连。
            if (!work.isCancelled() && !webSocketScheduler.isShutdown()) {
                log.warn("WebSocket业务线程池繁忙，延后30秒投递");
                webSocketScheduler.schedule(() -> dispatchSocketWork(work), 30, TimeUnit.SECONDS);
            }
        }
    }

    private boolean isAccountActive(Long accountId) {
        if (accountId == null) return false;
        try {
            com.yuzhanggui.entity.XianyuAccount account = xianyuAccountMapper.selectById(accountId);
            return account != null && Integer.valueOf(1).equals(account.getStatus());
        } catch (Exception exception) {
            log.warn("读取账号状态失败，暂停连接或刷新: accountId={}, reason={}", accountId, exception.getMessage());
            return false;
        }
    }

    /**
     * 根据数据库中的真实到期时间安排一次刷新。每个账号随机提前65至80分钟，
     * 避免容器启动后所有账号在同一时刻集中请求Token。
     */
    private void scheduleTokenRefreshByExpiry(Long accountId) {
        Future<?> existingTask = tokenRefreshTasks.remove(accountId);
        if (existingTask != null) {
            existingTask.cancel(false);
        }
        if (!isAccountActive(accountId) || tokenService.isCaptchaPending(accountId)) {
            return;
        }

        long now = System.currentTimeMillis();
        Long expireTime = tokenService.getTokenExpireTime(accountId);
        long delayMillis;
        long randomLeadMillis = ThreadLocalRandom.current().nextLong(
                TOKEN_REFRESH_JITTER_MIN_MS, TOKEN_REFRESH_JITTER_MAX_MS + 1);

        if (expireTime != null && expireTime > now) {
            long refreshAt = expireTime - TOKEN_REFRESH_BASE_LEAD_MS - randomLeadMillis;
            if (refreshAt > now) {
                delayMillis = refreshAt - now;
            } else {
                long remainingMillis = expireTime - now;
                long emergencyMax = Math.min(TimeUnit.MINUTES.toMillis(5), Math.max(5_000L, remainingMillis / 2));
                delayMillis = emergencyMax <= 5_000L
                        ? 5_000L
                        : ThreadLocalRandom.current().nextLong(5_000L, emergencyMax + 1);
            }
        } else {
            delayMillis = ThreadLocalRandom.current().nextLong(5_000L, 30_001L);
        }

        long scheduledAt = now + delayMillis;
        Future<?> refreshTask = scheduleSocketWork(() -> {
            tokenRefreshTasks.remove(accountId);
            if (!isAccountActive(accountId) || tokenService.isCaptchaPending(accountId)
                    || tokenService.isSessionRenewalPending(accountId)) {
                log.info("【账号{}】Token刷新执行前账号状态已变化，本次任务取消", accountId);
                return;
            }

            AtomicBoolean guard = tokenRefreshInProgress.computeIfAbsent(accountId, ignored -> new AtomicBoolean(false));
            if (!guard.compareAndSet(false, true)) {
                log.debug("【账号{}】已有Token刷新正在执行，跳过重复任务", accountId);
                return;
            }
            try {
                connectionRestartFlags.put(accountId, true);
                refreshTokenAndReconnect(accountId);
            } finally {
                guard.set(false);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);

        tokenRefreshTasks.put(accountId, refreshTask);
        log.info("【账号{}】Token将按真实到期时间错峰刷新: expireAt={}, refreshAt={}, leadMinutes={}",
                accountId, expireTime, scheduledAt,
                expireTime == null ? null : Math.max(0L, (expireTime - scheduledAt) / 60_000L));
    }
    
    /**
     * 刷新Token并重连
     * 参考Python的refresh_token和重连逻辑
     * 
     * Python逻辑：
     * 1. 刷新Token
     * 2. 设置connection_restart_flag = True
     * 3. 关闭当前WebSocket连接（触发重连）
     * 4. Token刷新失败时，在token_retry_interval后重试
     */
    private void refreshTokenAndReconnect(Long accountId) {
        if (!isAccountActive(accountId)) {
            log.info("【账号{}】账号已禁用、不存在或仍待验证，跳过Token刷新", accountId);
            return;
        }

        if (tokenService.isCaptchaPending(accountId)) {
            log.info("【账号{}】正在等待安全验证，跳过Token刷新与重连", accountId);
            return;
        }
        if (tokenService.isSessionRenewalPending(accountId)) {
            log.info("【账号{}】Session过期自动续期等待中，暂停Token刷新与重连", accountId);
            return;
        }
        try {
            log.info("【账号{}】开始刷新Token并重连...", accountId);
            
            // 停止当前连接
            stopWebSocket(accountId);
            
            // 清除旧Token
            tokenService.clearToken(accountId);
            
            // 重新启动连接（会自动获取新Token）
            boolean success = startWebSocket(accountId);
            
            if (success) {
                // 重置重连计数
                AtomicInteger attemptCount = reconnectAttemptCounts.get(accountId);
                if (attemptCount != null) {
                    attemptCount.set(0);
                }
                log.info("【账号{}】✅ Token刷新并重连成功", accountId);
            } else {
                log.error("【账号{}】❌ Token刷新并重连失败，将在{}秒后重试", 
                        accountId, config.getTokenRetryInterval());
                
                // 参考Python: Token刷新失败后，在token_retry_interval后重试
                scheduleTokenRefreshRetry(accountId);
            }
        } catch (com.yuzhanggui.exception.CaptchaRequiredException e) {
            log.warn("【账号{}】平台要求安全验证，已停止Token刷新重试", accountId);
        } catch (Exception e) {
            log.error("【账号{}】Token刷新并重连异常，将在{}秒后重试", 
                    accountId, config.getTokenRetryInterval(), e);
            
            // 参考Python: 异常后也要重试
            scheduleTokenRefreshRetry(accountId);
        }
    }

    /**
     * 调度Token刷新重试
     */
    private void scheduleTokenRefreshRetry(Long accountId) {
        if (!isAccountActive(accountId)) {
            log.info("【账号{}】账号已禁用、不存在或仍待验证，不安排Token重试", accountId);
            return;
        }

        if (tokenService.isCaptchaPending(accountId)) {
            log.info("【账号{}】正在等待安全验证，不安排Token刷新重试", accountId);
            return;
        }
        if (tokenService.isSessionRenewalPending(accountId)) {
            log.info("【账号{}】Session过期自动续期等待中，不安排短间隔Token重试", accountId);
            return;
        }
        // 同一账号只保留一个任务，凭证更新或连接停止时可立即取消
        tokenRetryTasks.compute(accountId, (id, existingTask) -> {
            if (existingTask != null && !existingTask.isDone()) {
                return existingTask;
            }
            return scheduleSocketWork(() -> {
                tokenRetryTasks.remove(id);
                if (!isAccountActive(id)) {
                    log.info("【账号{}】Token重试执行前账号已停用，取消本次重试", id);
                    return;
                }

                log.info("【账号{}】Token刷新重试间隔已到，开始重试...", id);
                refreshTokenAndReconnect(id);
            }, config.getTokenRetryInterval(), TimeUnit.SECONDS);
        });
    }
    
    /**
     * 处理连接丢失
     * 参考Python的连接重连逻辑
     */
    private void handleConnectionLost(Long accountId) {
        log.warn("【账号{}】检测到连接丢失（心跳超时），准备重连...", accountId);
        // scheduleReconnect 会检查数据库账号状态，也放到业务池。
        scheduleSocketWork(() -> {
            Long response = lastHeartbeatResponseTimes.get(accountId);
            long window = Math.max(1L, (long) config.getHeartbeatInterval() + config.getHeartbeatTimeout());
            if (response != null && System.currentTimeMillis() / 1000 - response >= window * HEARTBEAT_CONSECUTIVE_TIMEOUT_LIMIT) {
                triggerWsDisconnectNotify(accountId);
                scheduleReconnect(accountId, config.getReconnectDelay(), false);
            }
        }, 0, TimeUnit.SECONDS);
    }

    private void handleUnexpectedConnectionClosed(Long accountId, XianyuWebSocketClient client) {
        // Ignore a late callback from a connection already replaced/stopped.
        if (webSocketClients.get(accountId) != client || shuttingDown) return;
        log.warn("【账号{}】WebSocket连接被关闭，触发自动重连...", accountId);
        boolean restarting = Boolean.TRUE.equals(connectionRestartFlags.get(accountId));
        if (!restarting) triggerWsDisconnectNotify(accountId);
        scheduleReconnect(accountId, restarting ? 0 : config.getReconnectDelay(), restarting);
    }
    
    /**
     * 调度重连任务
     * 参考Python的main()方法中while True无限重连循环
     * 
     * 关键改进（对齐Python逻辑）：
     * 1. 防止重复重连：同一账号同时只有一个重连任务
     * 2. 指数退避：重连失败后延迟逐渐增加
     * 3. 重连成功后重置计数
     * 
     * @param accountId 账号ID
     * @param delaySeconds 延迟秒数
     * @param isManualRestart 是否主动重启（Token刷新等）
     */
    private void scheduleReconnect(Long accountId, int delaySeconds, boolean isManualRestart) {
        if (tokenService.isSessionRenewalPending(accountId)) {
            log.info("【账号{}】Session过期自动续期等待中，不安排WebSocket重连", accountId);
            return;
        }
        if (!isAccountActive(accountId)) {
            log.info("【账号{}】账号已禁用、不存在或仍待验证，不安排WebSocket重连", accountId);
            return;
        }
        if (tokenService.isCaptchaPending(accountId)) {
            log.warn("【账号{}】正在等待人工安全验证，已暂停WebSocket自动重连", accountId);
            return;
        }
        // 令牌覆盖延迟、业务队列等待和整个建连过程，避免重复投递。
        CompletableFuture<Void> ticket = new CompletableFuture<>();
        if (reconnectTasks.putIfAbsent(accountId, ticket) != null) {
            log.debug("【账号{}】已有重连任务在执行，跳过", accountId);
            return;
        }
        
        // 重置重启标志
        if (isManualRestart) {
            connectionRestartFlags.put(accountId, false);
        }
        
        // 计数饱和后永久保持最大间隔，只有连接成功才重置。
        AtomicInteger attemptCount = reconnectAttemptCounts.computeIfAbsent(accountId, k -> new AtomicInteger(0));
        int maxAttempts = Math.max(1, config.getMaxReconnectAttempts());
        int currentAttempt = attemptCount.updateAndGet(value -> value > maxAttempts ? value : value + 1);
        if (!isManualRestart && currentAttempt > maxAttempts) {
            log.info("【账号{}】自动重连已达到{}次上限，转为每{}秒长间隔持续重试",
                    accountId, maxAttempts, config.getMaxReconnectDelay());
        }
        int maxDelay = Math.max(1, config.getMaxReconnectDelay());
        int actualDelay = isManualRestart ? Math.max(0, delaySeconds) : currentAttempt > maxAttempts
                ? maxDelay : (int) Math.min((long) Math.max(1, delaySeconds) << Math.min(currentAttempt - 1, 30), maxDelay);
        
        log.info("【账号{}】计划{}秒后执行重连（第{}次尝试）...", accountId, actualDelay, currentAttempt);
        
        try {
            Future<?> work = scheduleSocketWork(() -> credentialUpdateCoordinator.withAccountLock(accountId, () -> {
                boolean retry = false;
                try {
                    if (ticket.isCancelled() || reconnectTasks.get(accountId) != ticket) return;
                    if (tokenService.isCaptchaPending(accountId)) return;
                    if (tokenService.isSessionRenewalPending(accountId)) {
                        log.info("【账号{}】Session过期自动续期等待中，跳过本次WebSocket重连", accountId);
                        return;
                    }
                
                    if (!isAccountActive(accountId)) {
                        log.info("【账号{}】重连执行前账号已停用，取消本次重连", accountId);
                        return;
                    }
                    Long lastResponse = lastHeartbeatResponseTimes.get(accountId);
                    long window = Math.max(1L, (long) config.getHeartbeatInterval() + config.getHeartbeatTimeout());
                    if (!isManualRestart && isConnected(accountId) && lastResponse != null
                            && System.currentTimeMillis() / 1000 - lastResponse < window * HEARTBEAT_CONSECUTIVE_TIMEOUT_LIMIT) {
                        attemptCount.set(0);
                        return;
                    }
                    // 停止当前连接和心跳
                    stopWebSocket(accountId, false);
                
                    // 直接复用当前会话重新连接；只有Token接口真实失败时才进入凭证刷新逻辑。
                    boolean success = startWebSocket(accountId);
                
                    if (success) {
                        // 重连成功，重置计数
                        attemptCount.set(0);
                        log.info("【账号{}】✅ 重连成功", accountId);
                    
                        operationLogService.log(accountId,
                            OperationConstants.Type.RECONNECT,
                            OperationConstants.Module.WEBSOCKET,
                            isManualRestart ? "主动重启连接成功" : "异常断开后重连成功",
                            OperationConstants.Status.SUCCESS,
                            OperationConstants.TargetType.WEBSOCKET,
                            String.valueOf(accountId),
                            null, null, null, null);
                    } else {
                        log.error("【账号{}】❌ 重连失败（第{}次），将继续尝试...", accountId, currentAttempt);
                    
                        operationLogService.log(accountId,
                            OperationConstants.Type.RECONNECT,
                            OperationConstants.Module.WEBSOCKET,
                            "重连失败（第" + currentAttempt + "次）",
                            OperationConstants.Status.FAIL,
                            OperationConstants.TargetType.WEBSOCKET,
                            String.valueOf(accountId),
                            null, null, null, null);
                    
                        // 重连失败达到阈值时触发邮件通知
                        if (currentAttempt >= RECONNECT_NOTIFY_THRESHOLD) {
                            triggerWsDisconnectNotify(accountId);
                        }
                    
                        retry = true;
                    }
                } catch (com.yuzhanggui.exception.CaptchaRequiredException e) {
                    // Retrying here only replays the same risk-controlled token request
                    // and floods the operation log. The user must verify first.
                    log.warn("【账号{}】触发安全验证，WebSocket自动重连已暂停，等待用户完成验证后手动重连", accountId);
                } catch (Exception e) {
                    log.error("【账号{}】重连异常，将继续尝试...", accountId, e);
                    if (currentAttempt >= RECONNECT_NOTIFY_THRESHOLD) triggerWsDisconnectNotify(accountId);
                    retry = true;
                } finally {
                    boolean current = reconnectTasks.remove(accountId, ticket);
                    ticket.complete(null);
                    if (retry && current && !ticket.isCancelled()) {
                        scheduleReconnect(accountId, config.getReconnectDelay(), false);
                    }
                }
            }), actualDelay, TimeUnit.SECONDS);
            ticket.whenComplete((result, failure) -> {
                if (ticket.isCancelled()) work.cancel(false);
            });
        } catch (RejectedExecutionException exception) {
            reconnectTasks.remove(accountId, ticket);
            ticket.cancel(false);
            throw exception;
        }
    }
    
    /**
     * 更新心跳响应时间
     * 由消息处理器调用
     */
    public void updateHeartbeatResponseTime(Long accountId) {
        lastHeartbeatResponseTimes.put(accountId, System.currentTimeMillis() / 1000);
        heartbeatTimeoutCounts.remove(accountId);
    }

    /**
     * 停止心跳任务
     */
    private void stopHeartbeat(Long accountId, boolean cancelReconnect) {
        // 停止心跳任务
        ScheduledFuture<?> heartbeatTask = heartbeatTasks.remove(accountId);
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            log.info("心跳任务已停止: accountId={}", accountId);
        }
        
        // 停止Token刷新任务
        Future<?> tokenRefreshTask = tokenRefreshTasks.remove(accountId);
        if (tokenRefreshTask != null) {
            tokenRefreshTask.cancel(false);
            log.info("Token刷新任务已停止: accountId={}", accountId);
        }

        // 取消Token刷新失败后的延迟重试
        Future<?> tokenRetryTask = tokenRetryTasks.remove(accountId);
        if (tokenRetryTask != null) {
            tokenRetryTask.cancel(false);
            log.info("Token刷新重试任务已取消: accountId={}", accountId);
        }
        
        // 取消重连任务
        Future<?> reconnectTask = cancelReconnect ? reconnectTasks.remove(accountId) : null;
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            log.info("重连任务已取消: accountId={}", accountId);
        }
        
        // 清理状态
        lastHeartbeatResponseTimes.remove(accountId);
        lastHeartbeatSendTimes.remove(accountId);
        heartbeatTimeoutCounts.remove(accountId);
        tokenRefreshInProgress.remove(accountId);
        connectionRestartFlags.remove(accountId);
    }

    @Override
    public boolean sendMessage(Long accountId, String cid, String toId, String text) {
        // 仅写入本地 Socket 不代表送达，所有业务发送统一等待平台回执。
        return sendMessageWithResult(accountId, cid, toId, text);
    }

    @Override
    public boolean sendMessageWithResult(Long accountId, String cid, String toId, String text) {
        return sendMessageWithResult(accountId, cid, toId, text, DEFAULT_ACK_TIMEOUT_SECONDS);
    }

    @Override
    public boolean sendMessageWithResult(Long accountId, String cid, String toId, String text, int timeoutSeconds) {
        try {
            log.info("发送消息(等待结果): accountId={}, cid={}, toId={}, textLength={}, timeout={}s",
                    accountId, cid, toId, text == null ? 0 : text.length(), timeoutSeconds);
            
            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client == null) {
                log.error("WebSocket客户端不存在: accountId={}", accountId);
                return false;
            }
            
            if (!client.isConnected()) {
                log.error("WebSocket未连接: accountId={}", accountId);
                return false;
            }
            
            return client.sendMessageWithResult(cid, toId, text, timeoutSeconds);
            
        } catch (Exception e) {
            log.error("发送消息失败: accountId={}, cid={}, toId={}", accountId, cid, toId, e);
            return false;
        }
    }

    public void completePendingResponse(Long accountId, String mid, int code) {
        XianyuWebSocketClient client = webSocketClients.get(accountId);
        if (client != null) {
            client.completePendingResponse(mid, code);
        }
    }
    
    @Override
    public boolean sendImageMessage(Long accountId, String cid, String toId, String imageUrl, int width, int height) {
        return sendImageMessageWithResult(accountId, cid, toId, imageUrl, width, height);
    }

    @Override
    public boolean sendImageMessageWithResult(Long accountId, String cid, String toId, String imageUrl, int width, int height) {
        return sendImageMessageWithResult(accountId, cid, toId, imageUrl, width, height, DEFAULT_ACK_TIMEOUT_SECONDS);
    }

    @Override
    public boolean sendImageMessageWithResult(Long accountId, String cid, String toId, String imageUrl, int width, int height, int timeoutSeconds) {
        try {
            log.info("发送图片消息(等待结果): accountId={}, cid={}, toId={}, size={}x{}, timeout={}s",
                    accountId, cid, toId, width, height, timeoutSeconds);

            XianyuWebSocketClient client = webSocketClients.get(accountId);
            if (client == null) {
                log.error("WebSocket客户端不存在: accountId={}", accountId);
                return false;
            }

            if (!client.isConnected()) {
                log.error("WebSocket未连接: accountId={}", accountId);
                return false;
            }

            return client.sendImageMessageWithResult(cid, toId, imageUrl, width, height, timeoutSeconds);

        } catch (Exception e) {
            log.error("发送图片消息失败: accountId={}, cid={}, toId={}", accountId, cid, toId, e);
            return false;
        }
    }

    /**
     * 应用关闭时清理资源
     */
    @PreDestroy
    public void cleanup() {
        shuttingDown = true;
        log.info("应用关闭，清理WebSocket资源");
        stopAllWebSockets();
        
    }

    /** Catch pre-existing offline/verification states even when no close callback occurs. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void checkUnavailableAccountNotifications() {
        if (notificationsPaused() || !notificationScanRunning.compareAndSet(false, true)) return;
        try {
            scheduleSocketWork(() -> {
                try {
                    notifyUnavailableAccounts();
                } catch (Exception e) {
                    log.warn("检查账号掉线通知失败", e);
                } finally {
                    notificationScanRunning.set(false);
                }
            }, 0, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            notificationScanRunning.set(false);
            log.warn("账号掉线通知检查暂时无法调度", e);
        }
    }

    private void notifyUnavailableAccounts() {
        if (notificationsPaused()) return;
        for (XianyuAccount account : xianyuAccountMapper.selectList(
                new QueryWrapper<XianyuAccount>().in("status", 1, -2))) {
            if (account.getId() != null && (Integer.valueOf(1).equals(account.getStatus())
                    || Integer.valueOf(-2).equals(account.getStatus())) && !isConnected(account.getId())) {
                triggerWsDisconnectNotify(account.getId());
            }
        }
    }

    private boolean notificationsPaused() {
        return shuttingDown || (onlineUpdateMaintenanceService != null && onlineUpdateMaintenanceService.isActive());
    }

    private void triggerWsDisconnectNotify(Long accountId) {
        if (accountId == null || notificationsPaused()) return;
        XianyuAccount account;
        try {
            account = xianyuAccountMapper.selectById(accountId);
            if (account == null || Integer.valueOf(0).equals(account.getStatus())) return;
        } catch (Exception e) {
            log.warn("读取账号掉线通知状态失败: accountId={}", accountId, e);
            return;
        }
        long now = System.currentTimeMillis();
        AtomicBoolean acquired = new AtomicBoolean(false);
        lastDisconnectNotifyTimes.compute(accountId, (id, last) -> {
            if (last != null && now - last < NOTIFY_INTERVAL_MS) return last;
            acquired.set(true);
            return now;
        });
        if (!acquired.get()) return;

        boolean accepted = false;
        // Each channel owns its switch; SMTP settings must never suppress PushPlus/Bark/etc.
        try {
            if (notificationChannelService != null) {
                Map<String, Object> params = new HashMap<>();
                params.put("reason", Integer.valueOf(-2).equals(account.getStatus())
                        ? "账号需要完成平台安全验证，消息监听已暂停。请前往账号管理完成验证或更新凭证。"
                        : "账号消息监听连接已断开。请前往账号管理查看连接和自动恢复状态；如提示凭证失效或需要验证，请按页面指引处理。");
                notificationChannelService.dispatchMessage("ACCOUNT_OFFLINE", accountId, params);
                accepted = true;
            }
        } catch (Exception e) {
            log.warn("触发账号掉线多渠道通知失败: accountId={}", accountId, e);
        }
        try {
            if (emailNotifyService != null && emailNotifyService.isWsDisconnectNotifyEnabled()) {
                emailNotifyService.sendWsDisconnectNotifyEmail(accountId, account.getAccountNote());
                accepted = true;
            }
        } catch (Exception e) {
            log.warn("触发账号掉线邮件通知失败: accountId={}", accountId, e);
        }
        // Rejected async submissions must be eligible for the next periodic check.
        if (!accepted) lastDisconnectNotifyTimes.remove(accountId, now);
    }
}
