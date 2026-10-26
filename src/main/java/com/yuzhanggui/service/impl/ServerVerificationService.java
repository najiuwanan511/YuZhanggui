package com.yuzhanggui.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuzhanggui.entity.XianyuAccount;
import com.yuzhanggui.entity.XianyuCookie;
import com.yuzhanggui.mapper.XianyuAccountMapper;
import com.yuzhanggui.mapper.XianyuCookieMapper;
import com.yuzhanggui.service.WebSocketService;
import com.yuzhanggui.service.WebSocketTokenService;
import com.yuzhanggui.utils.XianyuSignUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Relays the administrator's input to an isolated server browser and persists its verified cookies. */
@Slf4j
@Service
public class ServerVerificationService {
    private static final long TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final Set<String> TERMINAL = Set.of("CONNECTED", "VERIFICATION_REQUIRED", "FAILED", "CLOSED", "EXPIRED");
    private final XianyuAccountMapper accounts;
    private final XianyuCookieMapper cookies;
    private final WebSocketTokenService tokens;
    private final WebSocketService sockets;
    private final CredentialUpdateCoordinator coordinator;
    private final ManualVerificationScope scope;
    private final VerificationBrowserFactory browsers;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "server-human-verification");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile Session active;
    @Value("${app.captcha.enabled:true}")
    private boolean enabled = true;

    public record View(Long accountId, String sessionId, String state, String message, String image,
                       int width, int height, long revision, long expiresAt) {}

    private static class Session {
        final Long accountId;
        final String id = UUID.randomUUID().toString();
        final long expiresAt = System.currentTimeMillis() + TTL_MS;
        final AtomicBoolean refreshing = new AtomicBoolean();
        final AtomicBoolean inputPending = new AtomicBoolean();
        volatile View view;
        volatile String cancellation;
        long revision;
        volatile long capturedAt;
        Map<String, String> initialCookies;
        VerificationBrowserFactory.Session browser;
        Session(Long accountId) { this.accountId = accountId; }
        boolean terminal() { return view != null && TERMINAL.contains(view.state()); }
        synchronized void show(String state, String message, String image) {
            view = new View(accountId, id, state, message, image, VerificationBrowserFactory.WIDTH,
                    VerificationBrowserFactory.HEIGHT, ++revision, expiresAt);
        }
    }

    public ServerVerificationService(XianyuAccountMapper accounts, XianyuCookieMapper cookies,
            WebSocketTokenService tokens, WebSocketService sockets, CredentialUpdateCoordinator coordinator,
            ManualVerificationScope scope, VerificationBrowserFactory browsers) {
        this.accounts = accounts; this.cookies = cookies; this.tokens = tokens; this.sockets = sockets;
        this.coordinator = coordinator; this.scope = scope; this.browsers = browsers;
    }

    public synchronized View start(Long accountId) {
        requireAccount(accountId);
        if (!enabled) throw new IllegalStateException("服务器验证浏览器已禁用，请启用 CAPTCHA_BROWSER_ENABLED 或手动导入 Cookie");
        Session previous = active;
        if (previous != null && !previous.terminal() && previous.cancellation == null) {
            if (!previous.accountId.equals(accountId)) throw new IllegalStateException("另一账号正在验证，请先结束该验证");
            return state(accountId, previous.id);
        }
        if (!sockets.isConnected(accountId) && !tokens.isCaptchaPending(accountId)) {
            throw new IllegalStateException("当前没有待处理的平台验证，请刷新连接状态后重试");
        }
        Session session = new Session(accountId);
        session.show("STARTING", "正在获取当前账号的验证页面…", null);
        active = session;
        submit(session, () -> open(session));
        return session.view;
    }

    public View state(Long accountId, String sessionId) {
        Session session = requireSession(accountId, sessionId);
        if (!session.terminal() && System.currentTimeMillis() >= session.expiresAt) cancel(session, "EXPIRED");
        if ("WAITING".equals(session.view.state()) && System.currentTimeMillis() - session.capturedAt >= 700
                && session.refreshing.compareAndSet(false, true)) {
            submit(session, () -> {
                try { refresh(session, false); }
                finally { session.refreshing.set(false); }
            });
        }
        return session.view;
    }

    public View input(Long accountId, String sessionId, List<VerificationBrowserFactory.Point> points) {
        VerificationBrowserFactory.validatePoints(points);
        Session session = requireSession(accountId, sessionId);
        List<VerificationBrowserFactory.Point> actualInput = List.copyOf(points);
        synchronized (session) {
            if (!"WAITING".equals(session.view.state()) || !session.inputPending.compareAndSet(false, true)) {
                throw new IllegalStateException("验证页面正在处理，请等待画面更新后再操作");
            }
            session.show("APPLYING", "正在传递你的操作，请稍候…", session.view.image());
        }
        submit(session, () -> {
            try {
                requireAccount(accountId);
                session.browser.input(actualInput);
                refresh(session, true);
            } finally { session.inputPending.set(false); }
        });
        return session.view;
    }

    public void close(Long accountId, String sessionId) {
        Session session = requireSession(accountId, sessionId);
        if (!session.terminal()) cancel(session, "CLOSED");
    }

    private void open(Session session) {
        requireAccount(session.accountId);
        // Always obtain a fresh challenge. A saved URL may have expired or predate the latest QR login.
        boolean connected = sockets.isConnected(session.accountId)
                || scope.run(session.accountId, () -> sockets.restartAfterCredentialUpdate(session.accountId));
        if (connected && sockets.isConnected(session.accountId)) {
            finish(session, "CONNECTED", "消息连接已恢复");
            return;
        }
        if (session.cancellation != null) return;
        requireAccount(session.accountId);
        String url = tokens.getCaptchaUrl(session.accountId);
        if (!tokens.isCaptchaPending(session.accountId) || !VerificationBrowserFactory.trustedUrl(url, false)) {
            throw new IllegalStateException("未取得有效验证页面，请检查登录凭证后重试");
        }
        session.initialCookies = coordinator.withAccountLock(session.accountId, () -> {
            XianyuAccount account = requireAccount(session.accountId);
            Map<String, String> parsed = XianyuSignUtils.parseCookies(requireCookie(session.accountId).getCookieText());
            if (!Objects.equals(account.getUnb(), parsed.get("unb"))) throw new IllegalStateException("账号与登录凭证不一致，请重新登录");
            return parsed;
        });
        session.browser = browsers.open(session.accountId, url, session.initialCookies);
        refresh(session, true);
    }

    private void refresh(Session session, boolean fromInput) {
        requireAccount(session.accountId);
        Map<String, String> browserCookies = session.browser.cookies();
        String proof = CookieRefreshServiceImpl.findCookieIgnoreCase(browserCookies, "x5sec");
        if (proof != null && !proof.isBlank()
                && !Objects.equals(proof, CookieRefreshServiceImpl.findCookieIgnoreCase(session.initialCookies, "x5sec"))) {
            recover(session, browserCookies);
            return;
        }
        String image = session.browser.screenshot();
        session.capturedAt = System.currentTimeMillis();
        synchronized (session) {
            if (session.cancellation == null && (fromInput || !session.inputPending.get())) {
                session.show("WAITING", "请在下方服务器画面中手动完成验证。完成后自动保存凭证并检查连接。", image);
            }
        }
    }

    private void recover(Session session, Map<String, String> browserCookies) {
        session.show("RECONNECTING", "已取得验证凭证，正在保存并检查消息连接…", null);
        coordinator.withAccountLock(session.accountId, () -> {
            XianyuAccount account = requireAccount(session.accountId);
            if (session.cancellation != null || System.currentTimeMillis() >= session.expiresAt) return;
            XianyuCookie row = requireCookie(session.accountId);
            Map<String, String> current = XianyuSignUtils.parseCookies(row.getCookieText());
            if (!Objects.equals(account.getUnb(), browserCookies.get("unb"))
                    || !sameLogin(session.initialCookies, current)) {
                throw new IllegalStateException("验证期间登录凭证发生变化，请重新打开验证，避免覆盖新的登录状态");
            }
            Map<String, String> merged = new LinkedHashMap<>(current);
            browserCookies.forEach((name, value) -> {
                if (name.toLowerCase(Locale.ROOT).startsWith("x5") && value != null && !value.isBlank()) merged.put(name, value);
            });
            CookieRefreshServiceImpl.removeCaptchaChallengeCookies(merged);
            int updated = cookies.update(null, new LambdaUpdateWrapper<XianyuCookie>()
                    .eq(XianyuCookie::getId, row.getId())
                    .eq(XianyuCookie::getCookieText, row.getCookieText())
                    .set(XianyuCookie::getCookieText, XianyuSignUtils.formatCookies(merged))
                    .set(XianyuCookie::getCookieStatus, 1));
            if (updated != 1) throw new IllegalStateException("凭证已被其他操作更新，请重新打开验证");
            log.info("【账号{}】站内人工验证凭证已保存，开始检查消息连接", session.accountId);
            scope.run(session.accountId, () -> sockets.restartAfterCredentialUpdate(session.accountId));
        });
        if (session.cancellation != null) return;
        if (sockets.isConnected(session.accountId)) finish(session, "CONNECTED", "验证凭证已生效，消息连接已恢复");
        else finish(session, "VERIFICATION_REQUIRED", tokens.isCaptchaPending(session.accountId)
                ? "验证凭证已保存，但平台仍要求验证。请重新加载当前验证页面；无需再次扫码。"
                : "验证凭证已保存，消息连接尚未恢复。请刷新账号状态或检查应用日志。");
    }

    static boolean sameLogin(Map<String, String> before, Map<String, String> after) {
        return List.of("unb", "cookie2", "sgcookie", "x5sec").stream().allMatch(k -> Objects.equals(before.get(k), after.get(k)));
    }

    private XianyuAccount requireAccount(Long accountId) {
        if (accountId == null) throw new IllegalArgumentException("账号ID不能为空");
        XianyuAccount account = accounts.selectById(accountId);
        if (account == null || Integer.valueOf(0).equals(account.getStatus())) throw new IllegalStateException("账号已禁用或不存在，验证已停止");
        return account;
    }

    private XianyuCookie requireCookie(Long accountId) {
        XianyuCookie cookie = cookies.selectOne(new LambdaQueryWrapper<XianyuCookie>()
                .eq(XianyuCookie::getXianyuAccountId, accountId).orderByDesc(XianyuCookie::getCreatedTime).last("LIMIT 1"));
        if (cookie == null || cookie.getCookieText() == null || cookie.getCookieText().isBlank()) throw new IllegalStateException("未找到登录凭证，请先登录账号");
        return cookie;
    }

    private Session requireSession(Long accountId, String id) {
        Session session = active;
        if (session == null || !Objects.equals(accountId, session.accountId) || !session.id.equals(id)) {
            throw new IllegalArgumentException("验证会话已失效，请重新打开");
        }
        return session;
    }

    private void submit(Session session, Runnable task) {
        try {
            worker.execute(() -> {
                try {
                    if (session.terminal()) return;
                    if (session.cancellation == null && System.currentTimeMillis() < session.expiresAt) task.run();
                } catch (IllegalStateException | IllegalArgumentException expected) {
                    finish(session, "FAILED", expected.getMessage());
                } catch (RuntimeException failure) {
                    log.warn("【账号{}】站内验证浏览器异常: {}", session.accountId, failure.getClass().getSimpleName());
                    finish(session, "FAILED", "服务器验证画面不可用，请重新加载；若仍失败，请检查浏览器安装或手动导入 Cookie");
                } finally {
                    if ((!session.terminal() || session.browser != null)
                            && (session.cancellation != null || System.currentTimeMillis() >= session.expiresAt)) {
                        finish(session, session.cancellation == null ? "EXPIRED" : session.cancellation, "验证已结束，请重新打开验证");
                    }
                }
            });
        } catch (RejectedExecutionException busy) {
            session.cancellation = "CLOSED";
            session.show("FAILED", "验证服务繁忙，请稍后重试", null);
            throw new IllegalStateException("验证服务繁忙，请稍后重试");
        }
    }

    private void cancel(Session session, String reason) {
        if (session.cancellation != null) return;
        session.cancellation = reason;
        session.show("CLOSING", "正在结束验证…", null);
        submit(session, () -> {});
    }

    private void finish(Session session, String state, String message) {
        if (session.browser != null) {
            try { session.browser.close(); }
            catch (RuntimeException failure) { log.debug("关闭验证浏览器失败: {}", failure.getClass().getSimpleName()); }
            session.browser = null;
        }
        session.show(state, message, null);
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void cleanupExpired() {
        Session session = active;
        if (session != null && !session.terminal() && System.currentTimeMillis() >= session.expiresAt) cancel(session, "EXPIRED");
    }

    @PreDestroy
    public void shutdown() {
        Session session = active;
        if (session != null && !session.terminal()) cancel(session, "CLOSED");
        worker.shutdown();
        try { if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow(); }
        catch (InterruptedException interrupted) { worker.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
