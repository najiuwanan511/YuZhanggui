package com.yuzhanggui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhanggui.config.PlaywrightManager;
import com.yuzhanggui.constants.OperationConstants;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.entity.XianyuCookie;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuCookieMapper;
import com.yuzhanggui.service.CookieRefreshService;
import com.yuzhanggui.service.OperationLogService;
import com.yuzhanggui.utils.SessionCookieJar;
import com.yuzhanggui.utils.XianyuSignUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cookie刷新服务实现
 * 参考Python代码的Cookie刷新逻辑
 *
 * 核心改进（对齐Python的requests.Session行为）：
 * 1. 使用SessionCookieJar自动管理Cookie（模拟requests.Session）
 *    - OkHttp自动解析响应Set-Cookie并合并到jar
 *    - 后续请求自动携带最新Cookie，无需手动设置Cookie头
 * 2. 保留最新策略：新Cookie值覆盖旧Cookie
 * 3. 添加并发锁，防止多线程同时刷新同一账号的Cookie
 */
@Slf4j
@Service
public class CookieRefreshServiceImpl implements CookieRefreshService {
    private static final String HAS_LOGIN_URL = "https://passport.goofish.com/newlogin/hasLogin.do";
    private static final String GOOFISH_IM_URL = "https://www.goofish.com/im";
    private static final String GOOFISH_COOKIE_DOMAIN = ".goofish.com";
    private static final String TAOBAO_COOKIE_DOMAIN = ".taobao.com";

    @Autowired
    private XianyuCookieMapper cookieMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private CredentialUpdateCoordinator credentialUpdateCoordinator;

    @Autowired
    private PlaywrightManager playwrightManager;

    @Autowired
    private ManualVerificationScope manualVerificationScope;

    @Autowired(required = false)
    private com.yuzhanggui.service.EmailNotifyService emailNotifyService;

    private final ObjectMapper objectMapper = new ObjectMapper();


    private static final long BROWSER_REFRESH_COOLDOWN_MS = 30 * 60 * 1000L;
    private static final long CAPTCHA_POLL_INTERVAL_MS = 250L;
    private static final String CAPTCHA_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";
    private static final List<String> CAPTCHA_COOKIE_URLS = List.of(
            GOOFISH_IM_URL,
            "https://h5api.m.goofish.com",
            "https://passport.goofish.com",
            "https://www.taobao.com"
    );

    @Value("${app.captcha.enabled:true}")
    private boolean captchaBrowserEnabled = true;

    @Value("${app.captcha.browser-headless:true}")
    private boolean captchaBrowserHeadless = true;

    @Value("${app.captcha.timeout-seconds:120}")
    private int captchaTimeoutSeconds = 120;

    @Value("${app.captcha.max-concurrent:1}")
    private int captchaMaxConcurrent = 1;

    @Value("${app.captcha.remote-enabled:false}")
    private boolean captchaRemoteEnabled = false;

    @Value("${app.captcha.remote-url:}")
    private String captchaRemoteUrl = "";

    private final Map<Long, Long> lastBrowserRefreshTime = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> captchaLocks = new ConcurrentHashMap<>();
    private volatile Semaphore captchaSlots = new Semaphore(1);

    public CookieRefreshServiceImpl() {
    }

    @PostConstruct
    void initializeCaptchaSlots() {
        captchaSlots = new Semaphore(Math.max(1, captchaMaxConcurrent));
    }


    @Override
    public boolean checkLoginStatus(Long accountId) {
        return credentialUpdateCoordinator.withAccountLock(accountId,
                () -> doCheckLoginStatus(accountId, true));
    }

    @Override
    public boolean checkLoginStatusQuietly(Long accountId) {
        return credentialUpdateCoordinator.withAccountLock(accountId,
                () -> doCheckLoginStatus(accountId, false));
    }

    /**
     * 执行hasLogin检查（带重试机制）
     * 参考Python XianyuApis.hasLogin方法
     *
     * 核心改造：使用SessionCookieJar自动管理Cookie + 添加重试机制
     * - OkHttp自动从CookieJar加载Cookie，无需手动设置Cookie头
     * - OkHttp自动解析响应Set-Cookie并回调saveFromResponse
     * - hasLogin成功后从jar获取更新后的Cookie持久化到数据库
     * - 最多重试2次（retry_count < 2），与Python一致
     * 
     * @param accountId 账号ID
     * @param logOperation 是否记录操作日志（true=主动保活，false=被动检查）
     */
    private boolean doCheckLoginStatus(Long accountId, boolean logOperation) {
        return doCheckLoginStatusWithRetry(accountId, 0, logOperation);
    }

    /**
     * 执行hasLogin检查（带重试计数）
     * 参考Python: hasLogin(retry_count=0)
     * 
     * @param accountId 账号ID
     * @param retryCount 重试次数
     * @param logOperation 是否记录操作日志
     */
    private boolean doCheckLoginStatusWithRetry(Long accountId, int retryCount, boolean logOperation) {
        if (retryCount >= 2) {
            log.error("【账号{}】Login检查失败，重试次数过多", accountId);
            return false;
        }

        try {
            String logPrefix = logOperation ? "主动保活" : "被动检查";
            log.info("【账号{}】开始{}登录状态... (重试次数: {}/2)", accountId, logPrefix, retryCount);

            XianyuCookie cookie = cookieMapper.selectOne(
                    new LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .orderByDesc(XianyuCookie::getCreatedTime)
                            .last("LIMIT 1")
            );

            if (cookie == null || cookie.getCookieText() == null) {
                log.warn("【账号{}】未找到Cookie", accountId);
                return false;
            }

            String oldCookieStr = cookie.getCookieText();
            Map<String, String> cookies = XianyuSignUtils.parseCookies(oldCookieStr);

            SessionCookieJar cookieJar = new SessionCookieJar(oldCookieStr);

            FormBody.Builder formBuilder = new FormBody.Builder();
            formBuilder.add("appName", "xianyu");
            formBuilder.add("fromSite", "77");
            formBuilder.add("hid", cookies.getOrDefault("unb", ""));
            formBuilder.add("ltl", "true");
            formBuilder.add("appEntrance", "web");
            formBuilder.add("_csrf_token", cookies.getOrDefault("XSRF-TOKEN", ""));
            formBuilder.add("umidToken", "");
            formBuilder.add("hsiz", cookies.getOrDefault("cookie2", ""));
            formBuilder.add("bizParams", "taobaoBizLoginFrom=web");
            formBuilder.add("mainPage", "false");
            formBuilder.add("isMobile", "false");
            formBuilder.add("lang", "zh_CN");
            formBuilder.add("returnUrl", "");
            formBuilder.add("isIframe", "true");
            formBuilder.add("documentReferer", "https://www.goofish.com/");
            formBuilder.add("defaultView", "hasLogin");
            formBuilder.add("umidTag", "SERVER");
            formBuilder.add("deviceId", cookies.getOrDefault("cna", ""));

            Request request = new Request.Builder()
                    .url(HAS_LOGIN_URL)
                    .header("User-Agent", CAPTCHA_USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", "https://passport.goofish.com/")
                    .header("Origin", "https://passport.goofish.com")
                    .post(formBuilder.build())
                    .build();

            OkHttpClient httpClient = cookieJar.createHttpClient();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("【账号{}】检查登录状态失败: HTTP {}, 准备重试... (重试次数: {}/2)", 
                            accountId, response.code(), retryCount + 1);
                    Thread.sleep(500);
                    return doCheckLoginStatusWithRetry(accountId, retryCount + 1, logOperation);
                }

                String responseBody = response.body().string();
                log.debug("【账号{}】hasLogin接口已返回响应，长度: {}（内容不写入日志）",
                        accountId, responseBody != null ? responseBody.length() : 0);

                // 检测风控（参考Python实现）
                boolean isRiskControl = responseBody != null && (
                    responseBody.contains("RGV587_ERROR") ||
                    responseBody.contains("被挤爆啦") ||
                    responseBody.contains("FAIL_SYS_RGV587_ERROR") ||
                    responseBody.contains("FAIL_SYS_USER_VALIDATE"));

                if (isRiskControl) {
                    log.error("【账号{}】❌ hasLogin触发风控（响应内容已隐藏）", accountId);
                    log.error("【账号{}】系统目前无法自动解决，请进入闲鱼网页版-点击消息-过滑块-复制最新的Cookie", accountId);
                    
                    // 标记为失效（风控）
                    cookieMapper.update(null,
                            new LambdaUpdateWrapper<XianyuCookie>()
                                    .eq(XianyuCookie::getXianyuAccountId, accountId)
                                    .set(XianyuCookie::getCookieStatus, 3) // 3表示失效（风控）
                    );
                    markAccountAsCookieRefreshAbnormal(accountId, "hasLogin触发平台安全验证");

                    // 记录操作日志
                    operationLogService.log(accountId,
                            OperationConstants.Type.VERIFY,
                            OperationConstants.Module.COOKIE,
                            "hasLogin触发风控验证，需要人工处理滑块",
                            OperationConstants.Status.FAIL,
                            OperationConstants.TargetType.COOKIE,
                            String.valueOf(accountId),
                            null, null, "触发风控", null);

                    // 发送邮件通知
                    try {
                        XianyuAccount account = accountMapper.selectById(accountId);
                        String accountNote = account != null ? account.getAccountNote() : null;
                        if (emailNotifyService != null) {
                            emailNotifyService.sendCaptchaRequiredEmail(accountId, accountNote, "hasLogin时触发风控验证");
                        }
                    } catch (Exception e) {
                        log.error("【账号{}】发送风控验证邮件通知失败", accountId, e);
                    }

                    throw new com.yuzhanggui.exception.CaptchaRequiredException(
                        "触发风控，请进入闲鱼网页版过滑块后更新Cookie");
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);

                @SuppressWarnings("unchecked")
                Map<String, Object> content = (Map<String, Object>) responseMap.get("content");

                if (content != null && Boolean.TRUE.equals(content.get("success"))) {
                    log.info("【账号{}】✅ 登录状态有效", accountId);

                    String newCookieStr = cookieJar.getCookieString();
                    boolean cookieChanged = !newCookieStr.equals(oldCookieStr);

                    if (cookieChanged) {
                        Map<String, String> oldCookieMap = XianyuSignUtils.parseCookies(oldCookieStr);
                        Map<String, String> newCookieMap = XianyuSignUtils.parseCookies(newCookieStr);
                        String oldMh5tk = oldCookieMap.get("_m_h5_tk");
                        String newMh5tk = newCookieMap.get("_m_h5_tk");
                        boolean mh5tkUpdated = (newMh5tk != null && !newMh5tk.equals(oldMh5tk));

                        String updatedTime = java.time.LocalDateTime.now().format(
                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
                        cookieMapper.update(null,
                                new LambdaUpdateWrapper<XianyuCookie>()
                                        .eq(XianyuCookie::getXianyuAccountId, accountId)
                                        .set(XianyuCookie::getCookieText, newCookieStr)
                                        .set(XianyuCookie::getCookieStatus, 1)
                                        .set(XianyuCookie::getUpdatedTime, updatedTime)
                                        .set(mh5tkUpdated && newMh5tk != null, XianyuCookie::getMH5Tk, newMh5tk)
                        );

                        if (mh5tkUpdated) {
                            log.info("【账号{}】✅ _m_h5_tk已从hasLogin响应中更新（值已隐藏）", accountId);
                        }
                        log.info("【账号{}】✅ Cookie已通过SessionCookieJar自动更新到数据库", accountId);
                    } else {
                        if (cookie.getCookieStatus() == null || cookie.getCookieStatus() != 1) {
                            String updatedTime = java.time.LocalDateTime.now().format(
                                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
                            cookieMapper.update(null,
                                    new LambdaUpdateWrapper<XianyuCookie>()
                                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                                            .set(XianyuCookie::getCookieStatus, 1)
                                            .set(XianyuCookie::getUpdatedTime, updatedTime)
                            );
                            log.info("【账号{}】✅ Cookie状态已更新为有效", accountId);
                        }
                        log.info("【账号{}】Cookie无变化，登录态仍然有效", accountId);
                    }

                    // 只有在主动保活时才记录操作日志
                    if (logOperation) {
                        operationLogService.log(accountId,
                                OperationConstants.Type.UPDATE,
                                OperationConstants.Module.COOKIE,
                                "Cookie自动刷新成功",
                                OperationConstants.Status.SUCCESS,
                                OperationConstants.TargetType.COOKIE,
                                String.valueOf(accountId),
                                null, null, null, null);
                    }

                    return true;
                } else {
                    log.warn("【账号{}】⚠️ 登录状态无效，准备重试... (重试次数: {}/2)", accountId, retryCount + 1);

                    // 只有在主动保活时才记录操作日志
                    if (logOperation) {
                        operationLogService.log(accountId,
                                OperationConstants.Type.VERIFY,
                                OperationConstants.Module.COOKIE,
                                "登录状态检查失败，准备重试",
                                OperationConstants.Status.FAIL,
                                OperationConstants.TargetType.COOKIE,
                                String.valueOf(accountId),
                                null, null, "登录状态无效", null);
                    }

                    Thread.sleep(500);
                    return doCheckLoginStatusWithRetry(accountId, retryCount + 1, logOperation);
                }
            }

        } catch (Exception e) {
            log.error("【账号{}】检查登录状态异常，准备重试... (重试次数: {}/2)", accountId, retryCount + 1, e);

            // 只有在主动保活时才记录操作日志
            if (logOperation) {
                operationLogService.log(accountId,
                        OperationConstants.Type.VERIFY,
                        OperationConstants.Module.COOKIE,
                        "检查登录状态异常，准备重试: " + e.getMessage(),
                        OperationConstants.Status.FAIL,
                        OperationConstants.TargetType.COOKIE,
                        String.valueOf(accountId),
                        null, null, e.getMessage(), null);
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return doCheckLoginStatusWithRetry(accountId, retryCount + 1, logOperation);
        }
    }

    @Override
    public boolean refreshCookie(Long accountId) {
        return credentialUpdateCoordinator.withAccountLock(accountId, () -> {
            try {
                log.info("【账号{}】开始刷新Cookie...", accountId);

                // 通过hasLogin接口刷新Cookie
                boolean success = doCheckLoginStatus(accountId, true); // 主动刷新，记录日志
                if (!success) {
                    log.warn("【账号{}】hasLogin刷新失败，开始触发浏览器兜底刷新Cookie", accountId);
                    operationLogService.log(accountId,
                            OperationConstants.Type.REFRESH,
                            OperationConstants.Module.COOKIE,
                            "hasLogin刷新失败，开始触发浏览器兜底刷新Cookie",
                            OperationConstants.Status.PARTIAL,
                            OperationConstants.TargetType.COOKIE,
                            String.valueOf(accountId),
                            null, null, null, null);
                    success = refreshCookieWithBrowser(accountId);
                }

                if (success) {
                    log.info("【账号{}】✅ Cookie刷新成功", accountId);

                    // 记录操作日志
                    operationLogService.log(accountId,
                            OperationConstants.Type.REFRESH,
                            OperationConstants.Module.COOKIE,
                            "Cookie刷新成功",
                            OperationConstants.Status.SUCCESS,
                            OperationConstants.TargetType.COOKIE,
                            String.valueOf(accountId),
                            null, null, null, null);
                } else {
                    log.error("【账号{}】❌ Cookie刷新失败，需要手动更新", accountId);
                    markAccountAsCookieRefreshAbnormal(accountId, "hasLogin和浏览器兜底刷新均失败，需要手动处理Cookie");

                    // 记录操作日志
                    operationLogService.log(accountId,
                            OperationConstants.Type.REFRESH,
                            OperationConstants.Module.COOKIE,
                            "Cookie刷新失败，需要手动更新，账号已标记为异常待处理",
                            OperationConstants.Status.FAIL,
                            OperationConstants.TargetType.COOKIE,
                            String.valueOf(accountId),
                            null, null, "hasLogin和浏览器兜底刷新均失败", null);
                }

                return success;

            } catch (Exception e) {
                log.error("【账号{}】刷新Cookie失败", accountId, e);
                markAccountAsCookieRefreshAbnormal(accountId, "刷新Cookie异常: " + e.getMessage());

                // 记录操作日志
                operationLogService.log(accountId,
                        OperationConstants.Type.REFRESH,
                        OperationConstants.Module.COOKIE,
                        "刷新Cookie异常: " + e.getMessage(),
                        OperationConstants.Status.FAIL,
                        OperationConstants.TargetType.COOKIE,
                        String.valueOf(accountId),
                        null, null, e.getMessage(), null);

                return false;
            }
        });
    }

    @Override
    public boolean completeCaptcha(Long accountId, String verificationUrl) {
        if (manualVerificationScope != null && manualVerificationScope.owns(accountId)) return false;
        if (!captchaBrowserEnabled || (captchaBrowserHeadless && !captchaRemoteEnabled)
                || accountId == null || verificationUrl == null
                || verificationUrl.isBlank()) {
            if (captchaBrowserHeadless && !captchaRemoteEnabled && accountId != null) {
                log.info("【账号{}】当前为无头浏览器，跳过人工滑块并保留验证状态", accountId);
            }
            return false;
        }

        if (captchaRemoteEnabled) {
            log.warn("【账号{}】远程验证浏览器已打开，请访问 {} 完成滑块；验证 Cookie 将留在该账号独立 Context",
                    accountId, captchaRemoteUrl == null || captchaRemoteUrl.isBlank()
                            ? "飞牛主机的 7900 端口 (/vnc.html?autoconnect=true&resize=scale)" : captchaRemoteUrl);
        }

        ReentrantLock accountLock = captchaLocks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
        if (!accountLock.tryLock()) {
            log.info("【账号{}】已有安全验证任务运行，跳过重复验证", accountId);
            return false;
        }

        boolean slotAcquired = false;
        try {
            slotAcquired = captchaSlots.tryAcquire(1, TimeUnit.SECONDS);
            if (!slotAcquired) {
                log.info("【账号{}】安全验证浏览器达到并发上限，等待下次重试", accountId);
                return false;
            }

            XianyuCookie cookie = cookieMapper.selectOne(
                    new LambdaQueryWrapper<XianyuCookie>()
                            .eq(XianyuCookie::getXianyuAccountId, accountId)
                            .orderByDesc(XianyuCookie::getCreatedTime)
                            .last("LIMIT 1"));
            if (cookie == null || cookie.getCookieText() == null || cookie.getCookieText().isBlank()) {
                log.warn("【账号{}】安全验证失败：没有可用 Cookie", accountId);
                return false;
            }

            Map<String, String> originalCookies = XianyuSignUtils.parseCookies(cookie.getCookieText());
            String previousX5Sec = findCookieIgnoreCase(originalCookies, "x5sec");

            try (BrowserContext context = playwrightManager.createContext(accountId)) {
                try {
                    context.addCookies(buildBrowserCookies(originalCookies));
                    Page page = context.newPage();
                    try {
                        page.navigate(verificationUrl,
                                new Page.NavigateOptions()
                                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                        .setTimeout(TimeUnit.SECONDS.toMillis(Math.max(10, captchaTimeoutSeconds))));
                    } catch (Exception navigateError) {
                        // 验证页经常在滑块完成后主动终止导航；仍继续轮询浏览器 Cookie。
                        log.debug("【账号{}】安全验证页面导航未正常结束，继续等待 Cookie: {}",
                                accountId, navigateError.getMessage());
                    }

                    long deadline = System.currentTimeMillis()
                            + TimeUnit.SECONDS.toMillis(Math.max(10, captchaTimeoutSeconds));
                    while (System.currentTimeMillis() < deadline) {
                        Map<String, String> browserCookies = readBrowserCookies(context);
                        String currentX5Sec = findCookieIgnoreCase(browserCookies, "x5sec");
                        if (currentX5Sec != null && !currentX5Sec.isBlank()
                                && !currentX5Sec.equals(previousX5Sec)) {
                            Map<String, String> mergedCookies = new LinkedHashMap<>(originalCookies);
                            browserCookies.forEach((name, value) -> {
                                if (name.toLowerCase(Locale.ROOT).startsWith("x5")) {
                                    mergedCookies.put(name, value);
                                }
                            });
                            removeCaptchaChallengeCookies(mergedCookies);
                            persistCaptchaCookies(accountId, mergedCookies);
                            log.info("【账号{}】安全验证完成，已回收新的 x5sec Cookie", accountId);
                            return true;
                        }
                        Thread.sleep(CAPTCHA_POLL_INTERVAL_MS);
                    }
                } finally {
                    playwrightManager.saveContext(accountId, context);
                }
            }

            log.warn("【账号{}】安全验证超时，未检测到新的 x5sec Cookie", accountId);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("【账号{}】安全验证被中断", accountId);
            return false;
        } catch (Exception exception) {
            log.warn("【账号{}】安全验证浏览器执行失败: {}", accountId, exception.getMessage());
            return false;
        } finally {
            if (slotAcquired) {
                captchaSlots.release();
            }
            accountLock.unlock();
            captchaLocks.remove(accountId, accountLock);
        }
    }

    private Map<String, String> readBrowserCookies(BrowserContext context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Cookie browserCookie : context.cookies(CAPTCHA_COOKIE_URLS)) {
            if (browserCookie.name != null && browserCookie.value != null
                    && !browserCookie.name.isBlank() && !browserCookie.value.isBlank()) {
                result.put(browserCookie.name, browserCookie.value);
            }
        }
        return result;
    }

    private void persistCaptchaCookies(Long accountId, Map<String, String> cookies) {
        String cookieText = clearDuplicateCookies(XianyuSignUtils.formatCookies(cookies));
        String mh5tk = findCookieIgnoreCase(cookies, "_m_h5_tk");
        cookieMapper.update(null,
                new LambdaUpdateWrapper<XianyuCookie>()
                        .eq(XianyuCookie::getXianyuAccountId, accountId)
                        .set(XianyuCookie::getCookieText, cookieText)
                        .set(XianyuCookie::getCookieStatus, 1)
                        .set(mh5tk != null && !mh5tk.isBlank(), XianyuCookie::getMH5Tk, mh5tk));
    }

    static void removeCaptchaChallengeCookies(Map<String, String> cookies) {
        Set<String> staleNames = Set.of("x5secdata", "x5sectag", "x5step");
        cookies.keySet().removeIf(name -> staleNames.contains(name.toLowerCase(Locale.ROOT)));
    }

    static String findCookieIgnoreCase(Map<String, String> cookies, String name) {
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean refreshCookieWithBrowser(Long accountId) {
        Long lastTime = lastBrowserRefreshTime.get(accountId);
        if (lastTime != null && (System.currentTimeMillis() - lastTime) < BROWSER_REFRESH_COOLDOWN_MS) {
            long remainingMinutes = (BROWSER_REFRESH_COOLDOWN_MS - (System.currentTimeMillis() - lastTime)) / 60000;
            log.warn("【账号{}】浏览器兜底刷新冷却中，剩余{}分钟，跳过本次", accountId, remainingMinutes);
            return false;
        }

        XianyuCookie cookie = cookieMapper.selectOne(
                new LambdaQueryWrapper<XianyuCookie>()
                        .eq(XianyuCookie::getXianyuAccountId, accountId)
                        .orderByDesc(XianyuCookie::getCreatedTime)
                        .last("LIMIT 1")
        );
        if (cookie == null || cookie.getCookieText() == null || cookie.getCookieText().isBlank()) {
            log.warn("【账号{}】浏览器兜底刷新失败，未找到可用Cookie", accountId);
            markAccountAsCookieRefreshAbnormal(accountId, "浏览器兜底刷新失败：未找到可用Cookie");
            operationLogService.log(accountId,
                    OperationConstants.Type.REFRESH,
                    OperationConstants.Module.COOKIE,
                    "浏览器兜底刷新Cookie失败，账号已标记为异常待处理",
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.COOKIE,
                    String.valueOf(accountId),
                    null, null, "未找到可用Cookie", null);
            return false;
        }

        Map<String, String> existingCookies = XianyuSignUtils.parseCookies(cookie.getCookieText());
        if (existingCookies.isEmpty()) {
            log.warn("【账号{}】浏览器兜底刷新失败，Cookie内容为空", accountId);
            markAccountAsCookieRefreshAbnormal(accountId, "浏览器兜底刷新失败：Cookie内容为空");
            operationLogService.log(accountId,
                    OperationConstants.Type.REFRESH,
                    OperationConstants.Module.COOKIE,
                    "浏览器兜底刷新Cookie失败，账号已标记为异常待处理",
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.COOKIE,
                    String.valueOf(accountId),
                    null, null, "Cookie内容为空", null);
            return false;
        }

        operationLogService.log(accountId,
                OperationConstants.Type.REFRESH,
                OperationConstants.Module.COOKIE,
                "开始浏览器兜底刷新Cookie",
                OperationConstants.Status.SUCCESS,
                OperationConstants.TargetType.COOKIE,
                String.valueOf(accountId),
                null, null, null, null);

        lastBrowserRefreshTime.put(accountId, System.currentTimeMillis());

        try (BrowserContext context = playwrightManager.createContext(accountId)) {
            try {
                List<Cookie> browserCookies = buildBrowserCookies(existingCookies);
                context.addCookies(browserCookies);

                Page page = context.newPage();
                log.info("【账号{}】浏览器兜底刷新Cookie，开始访问 {}", accountId, GOOFISH_IM_URL);
                page.navigate(GOOFISH_IM_URL,
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                List<Cookie> refreshedCookies = context.cookies(List.of(
                        GOOFISH_IM_URL,
                        "https://passport.goofish.com",
                        "https://h5api.m.goofish.com",
                        "https://www.taobao.com"
                ));
                String refreshedCookieText = buildCookieText(refreshedCookies);
                if (refreshedCookieText.isBlank()) {
                    log.warn("【账号{}】浏览器兜底刷新未获取到新的Cookie", accountId);
                    markAccountAsCookieRefreshAbnormal(accountId, "浏览器兜底刷新失败：浏览器未返回Cookie");
                    operationLogService.log(accountId,
                            OperationConstants.Type.REFRESH,
                            OperationConstants.Module.COOKIE,
                            "浏览器兜底刷新Cookie失败，账号已标记为异常待处理",
                            OperationConstants.Status.FAIL,
                            OperationConstants.TargetType.COOKIE,
                            String.valueOf(accountId),
                            null, null, "浏览器未返回Cookie", null);
                    return false;
                }

                Map<String, String> refreshedCookieMap = XianyuSignUtils.parseCookies(refreshedCookieText);
                String newMh5Tk = refreshedCookieMap.get("_m_h5_tk");

                cookieMapper.update(null,
                        new LambdaUpdateWrapper<XianyuCookie>()
                                .eq(XianyuCookie::getXianyuAccountId, accountId)
                                .set(XianyuCookie::getCookieText, refreshedCookieText)
                                .set(XianyuCookie::getCookieStatus, 1)
                                .set(newMh5Tk != null && !newMh5Tk.isBlank(), XianyuCookie::getMH5Tk, newMh5Tk)
                );

                log.info("【账号{}】浏览器兜底刷新Cookie成功，Cookie长度: {}", accountId, refreshedCookieText.length());
                operationLogService.log(accountId,
                        OperationConstants.Type.REFRESH,
                        OperationConstants.Module.COOKIE,
                        "浏览器兜底刷新Cookie成功",
                        OperationConstants.Status.SUCCESS,
                        OperationConstants.TargetType.COOKIE,
                        String.valueOf(accountId),
                        null, null, null, null);
                return true;
            } finally {
                playwrightManager.saveContext(accountId, context);
            }
        } catch (Exception e) {
            log.error("【账号{}】浏览器兜底刷新Cookie失败", accountId, e);
            markAccountAsCookieRefreshAbnormal(accountId, "浏览器兜底刷新异常: " + e.getMessage());
            operationLogService.log(accountId,
                    OperationConstants.Type.REFRESH,
                    OperationConstants.Module.COOKIE,
                    "浏览器兜底刷新Cookie失败，账号已标记为异常待处理",
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.COOKIE,
                    String.valueOf(accountId),
                    null, null, e.getMessage(), null);
            return false;
        }
    }

    private void markAccountAsCookieRefreshAbnormal(Long accountId, String reason) {
        try {
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account == null) {
                operationLogService.log(accountId,
                        OperationConstants.Type.UPDATE,
                        OperationConstants.Module.ACCOUNT,
                        "Cookie刷新失败后更新账号状态失败",
                        OperationConstants.Status.FAIL,
                        OperationConstants.TargetType.ACCOUNT,
                        String.valueOf(accountId),
                        null, null, "账号不存在，原因: " + reason, null);
                return;
            }
            if (Objects.equals(account.getStatus(), -2)) {
                operationLogService.log(accountId,
                        OperationConstants.Type.UPDATE,
                        OperationConstants.Module.ACCOUNT,
                        "Cookie刷新失败，账号已处于异常待处理状态",
                        OperationConstants.Status.PARTIAL,
                        OperationConstants.TargetType.ACCOUNT,
                        String.valueOf(accountId),
                        null, null, reason, null);
                return;
            }

            account.setStatus(-2);
            accountMapper.updateById(account);
            log.warn("【账号{}】浏览器兜底刷新失败后，账号状态已更新为-2（异常待处理）", accountId);
            operationLogService.log(accountId,
                    OperationConstants.Type.UPDATE,
                    OperationConstants.Module.ACCOUNT,
                    "Cookie刷新失败，账号状态已标记为异常待处理(-2)",
                    OperationConstants.Status.SUCCESS,
                    OperationConstants.TargetType.ACCOUNT,
                    String.valueOf(accountId),
                    null, null, reason, null);
        } catch (Exception e) {
            log.error("【账号{}】Cookie刷新失败后更新账号状态异常", accountId, e);
            operationLogService.log(accountId,
                    OperationConstants.Type.UPDATE,
                    OperationConstants.Module.ACCOUNT,
                    "Cookie刷新失败后更新账号状态异常",
                    OperationConstants.Status.FAIL,
                    OperationConstants.TargetType.ACCOUNT,
                    String.valueOf(accountId),
                    null, null, e.getMessage(), null);
        }
    }

    private List<Cookie> buildBrowserCookies(Map<String, String> cookieMap) {
        List<Cookie> browserCookies = new ArrayList<>();
        for (Map.Entry<String, String> entry : cookieMap.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || name.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            browserCookies.add(new Cookie(name, value).setDomain(GOOFISH_COOKIE_DOMAIN).setPath("/"));
            browserCookies.add(new Cookie(name, value).setDomain(TAOBAO_COOKIE_DOMAIN).setPath("/"));
        }
        return browserCookies;
    }

    private String buildCookieText(List<Cookie> cookies) {
        Map<String, String> cookieMap = new LinkedHashMap<>();
        for (Cookie cookie : cookies) {
            if (cookie.name == null || cookie.name.isBlank() || cookie.value == null || cookie.value.isBlank()) {
                continue;
            }
            cookieMap.put(cookie.name, cookie.value);
        }
        return clearDuplicateCookies(XianyuSignUtils.formatCookies(cookieMap));
    }

    @Override
    public String clearDuplicateCookies(String cookieStr) {
        if (cookieStr == null || cookieStr.isEmpty()) {
            return cookieStr;
        }

        Map<String, String> cookies = new LinkedHashMap<>();
        String[] parts = cookieStr.split(";\\s*");

        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            int idx = part.indexOf('=');
            if (idx > 0) {
                String key = part.substring(0, idx);
                String value = part.substring(idx + 1);
                cookies.putIfAbsent(key, value);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }

        return sb.toString();
    }


}
