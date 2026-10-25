package com.yuzhanggui.service.impl;

import com.yuzhanggui.config.PlaywrightManager;
import com.yuzhanggui.config.WebSocketConfig;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.entity.XianyuCookie;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuCookieMapper;
import com.yuzhanggui.service.CookieRefreshService;
import com.yuzhanggui.service.Mh5tkRefreshResult;
import com.yuzhanggui.service.OperationLogService;
import com.yuzhanggui.service.TokenRefreshService;
import com.yuzhanggui.service.WebSocketTokenService;
import com.yuzhanggui.utils.SessionCookieJar;
import com.yuzhanggui.utils.XianyuSignUtils;
import com.yuzhanggui.utils.XianyuApiUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.Cookie;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Token刷新服务实现
 *
 * <p>功能：</p>
 * <ul>
 *   <li>默认只在真实接口失败时刷新_m_h5_tk和Cookie</li>
 *   <li>可选的Cookie健康检查使用2-4小时随机间隔</li>
 *   <li>WebSocket Token由连接按数据库真实过期时间调度</li>
 *   <li>监控token过期时间</li>
 *   <li>自动重新获取过期的token</li>
 * </ul>
 *
 * <p>优化策略（参考Python实现）：</p>
 * <ul>
 *   <li>Python采用"按需刷新"策略：只在token获取失败时才调用hasLogin</li>
 *   <li>Java默认仅保留按需刷新，避免主动保活形成高频账号行为</li>
 *   <li>主要依赖token刷新失败时的自动重试机制来触发hasLogin</li>
 *   <li>WebSocket Token使用实际过期时间预约刷新，避免重复轮询</li>
 * </ul>
 */
@Slf4j
@Service
public class TokenRefreshServiceImpl implements TokenRefreshService {

    private static final long ONE_MINUTE_MS = 60 * 1000L;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuCookieMapper cookieMapper;

    @Autowired
    private WebSocketTokenService webSocketTokenService;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private CookieRefreshService cookieRefreshService;

    @Autowired
    private WebSocketConfig webSocketConfig;

    @Autowired
    private PlaywrightManager playwrightManager;

    @Autowired
    private CredentialUpdateCoordinator credentialUpdateCoordinator;

    @Autowired(required = false)
    private com.yuzhanggui.service.EmailNotifyService emailNotifyService;

    private volatile long nextCookieKeepAliveTime = 0;

    @PostConstruct
    public void initRefreshSchedules() {
        if (webSocketConfig.isCredentialKeepAliveEnabled()) {
            scheduleNextCookieKeepAlive();
        } else {
            nextCookieKeepAliveTime = Long.MAX_VALUE;
            log.info("主动Cookie保活已关闭；仅在平台接口真实返回凭证失效时刷新");
        }
    }

    private long randomRefreshDelayMinutes() {
        int minMinutes = Math.max(1, webSocketConfig.getCredentialRefreshMinMinutes());
        int maxMinutes = Math.max(minMinutes, webSocketConfig.getCredentialRefreshMaxMinutes());
        return ThreadLocalRandom.current().nextLong(minMinutes, maxMinutes + 1L);
    }

    private void scheduleNextCookieKeepAlive() {
        long delayMinutes = randomRefreshDelayMinutes();
        nextCookieKeepAliveTime = System.currentTimeMillis() + delayMinutes * ONE_MINUTE_MS;
        log.info("📅 下次Cookie保活检查将在 {} 分钟后执行", delayMinutes);
    }
    
    /**
     * 闲鱼API地址（用于刷新_m_h5_tk）
     */
    private static final String API_H5_TK = "https://h5api.m.goofish.com/h5/mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get/1.0/";
    
    private final String mh5tkEndpoint;

    public TokenRefreshServiceImpl() {
        this(API_H5_TK);
    }

    TokenRefreshServiceImpl(String mh5tkEndpoint) {
        this.mh5tkEndpoint = mh5tkEndpoint;
    }

    /** A bounded recovery: signed request, fresh token negotiation, then one login refresh. */
    @Override
    public Mh5tkRefreshResult refreshMh5tkTokenDetailed(Long accountId) {
        if (accountId == null) return Mh5tkRefreshResult.FAILED;
        return credentialUpdateCoordinator.withAccountLock(accountId, () -> {
            if (webSocketTokenService.isCaptchaPending(accountId)) return Mh5tkRefreshResult.FAILED;
            try {
                XianyuCookie initial = readCookie(accountId);
                if (initial == null || initial.getCookieText() == null) return Mh5tkRefreshResult.FAILED;
                String originalToken = XianyuSignUtils.parseCookies(initial.getCookieText()).get("_m_h5_tk");
                Mh5tkRefreshResult result = requestMh5tk(accountId, false);
                if (result == Mh5tkRefreshResult.REFRESHED) return result;
                result = requestMh5tk(accountId, true);
                if (result == Mh5tkRefreshResult.REFRESHED) return result;

                // hasLogin can itself rotate the signing token. Compare before making another request.
                if (cookieRefreshService.checkLoginStatus(accountId)) {
                    XianyuCookie refreshed = readCookie(accountId);
                    if (refreshed != null && resolveRefreshResult(originalToken,
                            XianyuSignUtils.parseCookies(refreshed.getCookieText()).get("_m_h5_tk"))
                            == Mh5tkRefreshResult.REFRESHED) {
                        return Mh5tkRefreshResult.REFRESHED;
                    }
                    return requestMh5tk(accountId, true);
                }
                return result;
            } catch (com.yuzhanggui.exception.CaptchaRequiredException exception) {
                webSocketTokenService.pauseForVerification(accountId, exception.getCaptchaUrl(),
                        "刷新订单签名Token需要完成平台验证");
                return Mh5tkRefreshResult.FAILED;
            } catch (Exception exception) {
                // A network error does not prove the login session expired.
                log.warn("【账号{}】签名Token恢复未完成，保留凭证等待后续重试: {}", accountId,
                        exception.getClass().getSimpleName());
                return Mh5tkRefreshResult.FAILED;
            }
        });
    }

    private XianyuCookie readCookie(Long accountId) {
        return cookieMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<XianyuCookie>()
                        .eq(XianyuCookie::getXianyuAccountId, accountId));
    }

    static Mh5tkRefreshResult resolveRefreshResult(String oldMh5tk, String newMh5tk) {
        if (newMh5tk != null && !newMh5tk.isBlank() && !newMh5tk.equals(oldMh5tk)) {
            return Mh5tkRefreshResult.REFRESHED;
        }
        return Mh5tkRefreshResult.UNCHANGED;
    }

    private Mh5tkRefreshResult requestMh5tk(Long accountId, boolean negotiateNewToken) throws Exception {
        XianyuCookie cookie = readCookie(accountId);
        if (cookie == null || cookie.getCookieText() == null) return Mh5tkRefreshResult.FAILED;
        Map<String, String> requestCookies = XianyuSignUtils.parseCookies(cookie.getCookieText());
        String oldToken = requestCookies.get("_m_h5_tk");
        if (negotiateNewToken) {
            // Only omit the short-lived signing pair from this request; retain the login session.
            // Never persist this temporary removal if negotiation fails.
            requestCookies.remove("_m_h5_tk");
            requestCookies.remove("_m_h5_tk_enc");
        }
        SessionCookieJar jar = new SessionCookieJar(requestCookies);
        String api = "mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get";
        String data = "{}";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String sign = XianyuSignUtils.generateSign(timestamp, jar.getMh5tkToken(), data);
        HttpUrl.Builder url = HttpUrl.get(mh5tkEndpoint).newBuilder();
        XianyuApiUtils.buildStandardParams(api, timestamp, sign).forEach(url::addQueryParameter);
        url.addQueryParameter("data", data);
        Request.Builder builder = new Request.Builder().url(url.build());
        XianyuApiUtils.buildStandardHeaders("").forEach((name, value) -> {
            if (!"Cookie".equalsIgnoreCase(name)) builder.header(name, value);
        });
        try (Response response = jar.createHttpClient().newCall(builder.get().build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!body.isBlank()) {
                // Stop before any further negotiation when the platform explicitly requires verification.
                com.fasterxml.jackson.databind.JsonNode root = new ObjectMapper().readTree(body);
                String ret = root.path("ret").toString();
                if (ret.contains("RGV587_ERROR") || ret.contains("FAIL_SYS_USER_VALIDATE")
                        || ret.contains("被挤爆啦")) {
                    throw new com.yuzhanggui.exception.CaptchaRequiredException(
                            root.path("data").path("url").asText(null));
                }
            }
            if (!response.isSuccessful()) return Mh5tkRefreshResult.FAILED;
            Map<String, String> received = new HashMap<>();
            for (Cookie value : Cookie.parseAll(response.request().url(), response.headers())) {
                received.put(value.name(), value.value());
            }
            String newToken = received.get("_m_h5_tk");
            String newEncryptedToken = received.get("_m_h5_tk_enc");
            if (resolveRefreshResult(oldToken, newToken) != Mh5tkRefreshResult.REFRESHED
                    || newEncryptedToken == null || newEncryptedToken.isBlank()) {
                return Mh5tkRefreshResult.UNCHANGED;
            }
            cookie.setCookieText(jar.getCookieString());
            cookie.setMH5Tk(newToken);
            if (cookieMapper.updateById(cookie) != 1) return Mh5tkRefreshResult.FAILED;
            log.info("【账号{}】订单签名Token已更新，准备恢复业务请求", accountId);
            return Mh5tkRefreshResult.REFRESHED;
        }
    }

    /**
     * 刷新WebSocket token
     */
    @Override
    public boolean refreshWebSocketToken(Long accountId) {
        try {
            log.info("【账号{}】开始刷新WebSocket token...", accountId);
            
            // 调用WebSocketTokenService重新获取token
            String newToken = webSocketTokenService.refreshToken(accountId);
            
            if (newToken != null && !newToken.isEmpty()) {
                log.info("【账号{}】✅ WebSocket token刷新成功", accountId);
                return true;
            } else {
                log.warn("【账号{}】⚠️ WebSocket token刷新失败", accountId);
                return false;
            }
            
        } catch (Exception e) {
            log.error("【账号{}】刷新WebSocket token失败", accountId, e);
            return false;
        }
    }
    
    /**
     * 检查token是否需要刷新
     */
    @Override
    public boolean needsRefresh(Long accountId) {
        try {
            XianyuCookie cookie = cookieMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
            );
            if (cookie == null) {
                return false;
            }
            
            // 检查WebSocket token是否即将过期（提前1小时刷新）
            if (cookie.getTokenExpireTime() != null) {
                long currentTime = System.currentTimeMillis();
                long expireTime = cookie.getTokenExpireTime();
                long oneHour = 60 * 60 * 1000;
                
                if (expireTime - currentTime < oneHour) {
                    log.info("【账号{}】WebSocket token即将过期，需要刷新", accountId);
                    return true;
                }
            }
            
            // _m_h5_tk没有明确的过期时间，建议每2小时刷新一次
            // 这里可以通过记录上次刷新时间来判断
            
            return false;
            
        } catch (Exception e) {
            log.error("【账号{}】检查token状态失败", accountId, e);
            return false;
        }
    }
    
    /**
     * 可选的低频Cookie健康检查。默认关闭；即使显式开启，也只检查登录态，
     * 不主动刷新Token、不启动浏览器兜底，避免健康账号被周期性改写凭证。
     */
    @Scheduled(fixedDelay = ONE_MINUTE_MS, initialDelay = ONE_MINUTE_MS)
    public void scheduledCookieKeepAlive() {
        if (!webSocketConfig.isCredentialKeepAliveEnabled()) {
            return;
        }
        if (System.currentTimeMillis() < nextCookieKeepAliveTime) {
            return;
        }
        scheduleNextCookieKeepAlive();
        try {
            log.info("开始低频Cookie登录态健康检查...");

            List<XianyuAccount> accounts = accountMapper.selectList(null);
            int keepAliveSuccessCount = 0;
            int failCount = 0;

            for (XianyuAccount account : accounts) {
                if (account.getStatus() == 1 && !webSocketTokenService.isCaptchaPending(account.getId())) {
                    try {
                        boolean loginOk = cookieRefreshService.checkLoginStatusQuietly(account.getId());
                        if (loginOk) {
                            keepAliveSuccessCount++;
                            log.debug("【账号{}】低频Cookie登录态检查通过", account.getId());
                        } else {
                            failCount++;
                            log.warn("【账号{}】低频Cookie检查未通过；不会主动启动浏览器或刷新Token", account.getId());
                            triggerCookieExpireNotify(account.getId());
                        }
                    } catch (Exception e) {
                        failCount++;
                        log.warn("【账号{}】Cookie健康检查异常: {}", account.getId(), e.getMessage());
                    }

                    // 随机间隔5-15秒，避免频繁请求和被识别为机器人
                    int randomInterval = 5000 + new java.util.Random().nextInt(10001);
                    Thread.sleep(randomInterval);
                }
            }

            log.info("Cookie健康检查完成: 正常{}个, 异常{}个", keepAliveSuccessCount, failCount);

        } catch (Exception e) {
            log.error("定期Cookie保活检查失败", e);
        }
    }

    /**
     * 定时任务：检查并刷新WebSocket token
     * 与Python完全一致：每分钟检查一次，1小时刷新一次
     *
     * 优化策略：
     * 1. 保持每分钟检查一次（与Python一致）
     * 2. 添加随机间隔（3-8秒），避免多账号同时请求
     */
    @Scheduled(fixedDelay = 60 * 1000, initialDelay = 60 * 1000)
    public void scheduledRefreshWebSocketToken() {
        if (!webSocketConfig.isTokenRefreshScanEnabled()) {
            return;
        }
        try {
            // 与Python完全一致：每分钟检查一次，判断是否需要刷新（1小时）
            log.debug("🔄 检查WebSocket token是否需要刷新...");

            List<XianyuAccount> accounts = accountMapper.selectList(null);

            for (XianyuAccount account : accounts) {
                if (account.getStatus() == 1) { // 只刷新正常状态的账号
                    // 检查是否需要刷新（提前1小时刷新，与Python一致）
                    if (needsRefresh(account.getId())) {
                        log.info("🔄 账号{}的WebSocket token即将过期，开始刷新...", account.getId());
                        boolean success = refreshWebSocketToken(account.getId());

                        if (success) {
                            log.info("✅ 账号{}的WebSocket token刷新成功", account.getId());
                        } else {
                            log.warn("⚠️ 账号{}的WebSocket token刷新失败，将在下次检查时重试", account.getId());
                        }

                        // 随机间隔3-8秒，避免频繁请求
                        int randomInterval = 3000 + new java.util.Random().nextInt(5001);
                        Thread.sleep(randomInterval);
                    }
                }
            }

        } catch (Exception e) {
            log.error("定时检查WebSocket token失败", e);
        }
    }
    
    /**
     * 刷新所有账号的token
     *
     * 优化策略：
     * 1. 添加随机间隔（5-10秒），避免多账号同时请求被识别为机器人
     */
    @Override
    public void refreshAllAccountsTokens() {
        try {
            List<XianyuAccount> accounts = accountMapper.selectList(null);

            int successCount = 0;
            int failCount = 0;

            for (XianyuAccount account : accounts) {
                if (account.getStatus() == 1) { // 只刷新正常状态的账号
                    // 批量场景下"未变化"视为正常（Token 仍然有效），只有 FAILED 计为失败
                    Mh5tkRefreshResult result = refreshMh5tkTokenDetailed(account.getId());
                    if (result == Mh5tkRefreshResult.FAILED) {
                        failCount++;
                    } else {
                        successCount++;
                    }

                    // 随机间隔5-10秒，避免频繁请求被检测
                    int randomInterval = 5000 + new java.util.Random().nextInt(5001);
                    Thread.sleep(randomInterval);
                }
            }

            log.info("✅ _m_h5_tk token刷新完成: 成功{}个, 失败{}个", successCount, failCount);

        } catch (Exception e) {
            log.error("刷新所有账号token失败", e);
        }
    }

    private final Map<Long, Long> lastCookieExpireNotifyTimes = new HashMap<>();
    private static final long COOKIE_NOTIFY_INTERVAL_MS = 10 * 60 * 1000L;

    /**
     * 主动 _m_h5_tk 刷新：不等订单接口报错，提前发现凭证问题。
     *
     * <p>默认关闭（与"按需刷新"策略保持一致），可通过 credentialKeepAliveEnabled 开启；
     * 即使开启，每个账号最多每 2 小时尝试一次，且"未变化"视为正常，不报错、不打扰。
     * 刷新失败只记日志，真正的恢复仍由业务接口调用时的按需刷新与任务挂起机制处理。</p>
     */
    private static final long PROACTIVE_MH5TK_REFRESH_INTERVAL_MS = 2 * 60 * 60 * 1000L;
    private final Map<Long, Long> lastProactiveMh5tkRefreshTimes = new HashMap<>();

    @Scheduled(fixedDelay = 30 * ONE_MINUTE_MS, initialDelay = 10 * ONE_MINUTE_MS)
    public void scheduledProactiveMh5tkRefresh() {
        if (!webSocketConfig.isCredentialKeepAliveEnabled()) {
            return;
        }
        try {
            List<XianyuAccount> accounts = accountMapper.selectList(null);
            long now = System.currentTimeMillis();

            for (XianyuAccount account : accounts) {
                if (account.getStatus() != 1 || webSocketTokenService.isCaptchaPending(account.getId())) {
                    continue;
                }
                Long lastTime = lastProactiveMh5tkRefreshTimes.get(account.getId());
                if (lastTime != null && now - lastTime < PROACTIVE_MH5TK_REFRESH_INTERVAL_MS) {
                    continue;
                }
                lastProactiveMh5tkRefreshTimes.put(account.getId(), now);

                try {
                    Mh5tkRefreshResult result = refreshMh5tkTokenDetailed(account.getId());
                    if (result == Mh5tkRefreshResult.FAILED) {
                        log.warn("【账号{}】主动_m_h5_tk刷新失败，将在业务接口调用时按需重试", account.getId());
                    } else {
                        log.debug("【账号{}】主动_m_h5_tk刷新完成: {}", account.getId(), result);
                    }
                } catch (Exception e) {
                    log.warn("【账号{}】主动_m_h5_tk刷新异常: {}", account.getId(), e.getMessage());
                }

                // 随机间隔5-15秒，避免多账号同时请求
                int randomInterval = 5000 + new java.util.Random().nextInt(10001);
                Thread.sleep(randomInterval);
            }
        } catch (Exception e) {
            log.error("主动_m_h5_tk刷新任务失败", e);
        }
    }

    private void triggerCookieExpireNotify(Long accountId) {
        try {
            if (emailNotifyService == null || !emailNotifyService.isCookieExpireNotifyEnabled()) {
                return;
            }
            long now = System.currentTimeMillis();
            Long lastTime = lastCookieExpireNotifyTimes.get(accountId);
            if (lastTime != null && (now - lastTime) < COOKIE_NOTIFY_INTERVAL_MS) {
                log.debug("【账号{}】Cookie过期邮件通知防抖中，跳过", accountId);
                return;
            }
            lastCookieExpireNotifyTimes.put(accountId, now);
            String accountNote = "";
            try {
                XianyuAccount account = accountMapper.selectById(accountId);
                if (account != null) {
                    accountNote = account.getAccountNote() != null ? account.getAccountNote() : "";
                }
            } catch (Exception e) {
                log.debug("获取账号备注失败: {}", e.getMessage());
            }
            emailNotifyService.sendCookieExpireNotifyEmail(accountId, accountNote);
        } catch (Exception e) {
            log.warn("触发Cookie过期邮件通知异常: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60 * ONE_MINUTE_MS, initialDelay = 5 * ONE_MINUTE_MS)
    public void scheduledCleanPlaywrightTempFiles() {
        try {
            playwrightManager.cleanTempFiles();
        } catch (Exception e) {
            log.warn("清理Playwright临时文件异常: {}", e.getMessage());
        }
    }
}
