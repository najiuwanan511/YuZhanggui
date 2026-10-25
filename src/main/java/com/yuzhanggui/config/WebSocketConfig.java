package com.yuzhanggui.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * WebSocket配置类
 * 参考Python代码的配置参数
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "websocket")
public class WebSocketConfig {
    
    /**
     * 心跳间隔（秒）
     * 参考Python: HEARTBEAT_INTERVAL = 15
     */
    private int heartbeatInterval = 15;
    
    /**
     * 心跳超时（秒）
     * 在心跳间隔之外额外等待的秒数
     */
    private int heartbeatTimeout = 10;
    
    /**
     * Token重试间隔（秒）
     * 参考Python: TOKEN_RETRY_INTERVAL = 300 (5分钟)
     */
    private int tokenRetryInterval = 300;

    /** 是否启用主动 Cookie 健康检查。默认关闭，凭证只在真实失败时刷新。 */
    private boolean credentialKeepAliveEnabled = false;

    /** 是否启用旧的每分钟 Token 到期扫描。默认关闭，由连接按真实到期时间调度。 */
    private boolean tokenRefreshScanEnabled = false;

    /**
     * 主动 Cookie 健康检查最小间隔（分钟）。仅在显式启用时使用。
     */
    private int credentialRefreshMinMinutes = 120;

    /**
     * 凭证刷新最大间隔（分钟）
     * 用于Cookie保活和_m_h5_tk兜底刷新
     */
    private int credentialRefreshMaxMinutes = 240;
    
    /**
     * 消息过期时间（毫秒）
     * 参考Python: MESSAGE_EXPIRE_TIME = 300000 (5分钟)
     */
    private long messageExpireTime = 300000L;
    
    /**
     * 人工接管超时（秒）
     * 参考Python: MANUAL_MODE_TIMEOUT = 3600 (1小时)
     */
    private int manualModeTimeout = 3600;
    
    /**
     * 人工接管切换关键词
     * 参考Python: TOGGLE_KEYWORDS = "。"
     */
    private String toggleKeywords = "。";
    
    /**
     * 模拟人工输入延迟
     * 参考Python: SIMULATE_HUMAN_TYPING = False
     */
    private boolean simulateHumanTyping = false;
    
    /**
     * 连接重连延迟（秒）
     * 参考Python: 5秒
     */
    private int reconnectDelay = 15;

    /** 自动重连最大退避秒数。 */
    private int maxReconnectDelay = 300;
    
    /**
     * 最大重连次数
     */
    private int maxReconnectAttempts = 5;
    
    /**
     * 消息发送重试次数
     */
    private int messageRetryAttempts = 3;
    
    /**
     * 消息发送重试延迟（毫秒）
     */
    private long messageRetryDelay = 1000L;
    
    /**
     * 打印原始消息开关（解密后的消息）
     * 用于调试，打印WebSocket接收到的原始消息内容
     */
    private boolean printRawMessage = false;
}
