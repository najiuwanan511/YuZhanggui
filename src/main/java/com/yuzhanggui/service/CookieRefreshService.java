package com.yuzhanggui.service;

/**
 * Cookie刷新服务接口
 * 参考Python代码的Cookie刷新逻辑
 */
public interface CookieRefreshService {
    
    /**
     * 检查登录状态（记录操作日志）
     * 参考Python: hasLogin方法
     * 用于定时任务的主动Cookie保活
     * 
     * @param accountId 账号ID
     * @return 是否登录有效
     */
    boolean checkLoginStatus(Long accountId);
    
    /**
     * 检查登录状态（静默模式，不记录操作日志）
     * 参考Python: hasLogin方法
     * 用于Token刷新、WebSocket重连等场景的被动检查
     * 
     * @param accountId 账号ID
     * @return 是否登录有效
     */
    boolean checkLoginStatusQuietly(Long accountId);
    
    /**
     * 刷新Cookie
     * 参考Python: 通过hasLogin刷新Cookie
     * 
     * @param accountId 账号ID
     * @return 是否刷新成功
     */
    boolean refreshCookie(Long accountId);

    /**
     * 在该账号独立的浏览器 Context 中完成平台安全验证，并回写新的 x5sec Cookie。
     * 验证成功后由调用方清理 Token 缓存并重新获取 Token。
     *
     * @param accountId 账号 ID
     * @param verificationUrl 平台返回的最新验证地址
     * @return 是否拿到新的 x5sec 凭证
     */
    boolean completeCaptcha(Long accountId, String verificationUrl);
    
    /**
     * 清理重复Cookie
     * 参考Python: clear_duplicate_cookies
     * 
     * @param cookieStr Cookie字符串
     * @return 去重后的Cookie字符串
     */
    String clearDuplicateCookies(String cookieStr);
}
