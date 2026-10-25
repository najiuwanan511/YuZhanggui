package com.yuzhanggui.service;

/**
 * Token刷新服务接口
 * 用于定期刷新_m_h5_tk和websocket_token，保持Cookie有效性
 */
public interface TokenRefreshService {
    
    /**
     * 刷新指定账号的_m_h5_tk token（诚实三态版本）。
     *
     * <p>只有真正拿到新 Token 才返回 {@link Mh5tkRefreshResult#REFRESHED}；
     * Token 未变化返回 {@link Mh5tkRefreshResult#UNCHANGED}，调用方不得视为恢复成功。</p>
     *
     * @param accountId 账号ID
     * @return 刷新结果
     */
    Mh5tkRefreshResult refreshMh5tkTokenDetailed(Long accountId);

    /**
     * 刷新指定账号的_m_h5_tk token
     *
     * @param accountId 账号ID
     * @return 只有真正拿到新 Token 才返回 true；未变化或失败都返回 false
     */
    default boolean refreshMh5tkToken(Long accountId) {
        return refreshMh5tkTokenDetailed(accountId) == Mh5tkRefreshResult.REFRESHED;
    }
    
    /**
     * 刷新指定账号的WebSocket token
     * 
     * @param accountId 账号ID
     * @return 是否刷新成功
     */
    boolean refreshWebSocketToken(Long accountId);
    
    /**
     * 检查并刷新所有账号的token
     * 定时任务调用
     */
    void refreshAllAccountsTokens();
    
    /**
     * 检查token是否即将过期
     * 
     * @param accountId 账号ID
     * @return true-需要刷新，false-还有效
     */
    boolean needsRefresh(Long accountId);
}
