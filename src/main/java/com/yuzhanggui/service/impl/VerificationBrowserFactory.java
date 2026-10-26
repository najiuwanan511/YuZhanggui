package com.yuzhanggui.service.impl;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** A dedicated browser, accessed only by the verification worker. No automated CAPTCHA solver. */
@Component
public class VerificationBrowserFactory {
    public static final int WIDTH = 1080;
    public static final int HEIGHT = 720;
    private static final String TOKEN_ORIGIN = "https://h5api.m.goofish.com/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";
    @Value("${app.captcha.profile-dir:./data/browser-profiles}")
    private String profileDir;

    public record Point(double x, double y, long elapsedMs) {}
    public interface Session extends AutoCloseable {
        String screenshot();
        Map<String, String> cookies();
        void input(List<Point> points);
        @Override void close();
    }

    static boolean trustedUrl(String url, boolean resource) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            host = host.toLowerCase(Locale.ROOT);
            List<String> domains = resource
                    ? List.of("goofish.com", "taobao.com", "tmall.com", "alibaba.com", "alicdn.com", "mmstat.com", "aliyun.com")
                    : List.of("goofish.com", "taobao.com", "tmall.com", "alibaba.com");
            String target = host;
            return domains.stream().anyMatch(d -> target.equals(d) || target.endsWith("." + d));
        } catch (RuntimeException ignored) { return false; }
    }

    static void validatePoints(List<Point> points) {
        if (points == null || points.size() < 2 || points.size() > 160) throw new IllegalArgumentException("操作轨迹无效，请重新拖动");
        long previous = 0;
        for (Point p : points) {
            if (p == null || !Double.isFinite(p.x()) || !Double.isFinite(p.y()) || p.x() < 0 || p.x() > WIDTH
                    || p.y() < 0 || p.y() > HEIGHT || p.elapsedMs() < previous || p.elapsedMs() > 8000) {
                throw new IllegalArgumentException("操作轨迹超出验证画面或时间范围，请重新拖动");
            }
            previous = p.elapsedMs();
        }
        if (points.getFirst().elapsedMs() != 0) throw new IllegalArgumentException("操作轨迹起点无效");
    }

    public Session open(Long accountId, String url, Map<String, String> cookieMap) {
        if (!trustedUrl(url, false)) throw new IllegalArgumentException("平台未返回有效的验证地址，请重新加载验证");
        Playwright playwright = Playwright.create();
        Browser browser = null;
        try {
            RuntimeException last = null;
            for (String channel : new String[]{null, "chrome", "msedge"}) {
                try {
                    BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true).setTimeout(15000);
                    if (channel != null) options.setChannel(channel);
                    browser = playwright.chromium().launch(options);
                    break;
                } catch (RuntimeException failure) { last = failure; }
            }
            if (browser == null) throw new IllegalStateException("服务器验证浏览器不可用，请检查 Chromium 安装", last);
            Browser.NewContextOptions options = new Browser.NewContextOptions().setUserAgent(USER_AGENT)
                    .setLocale("zh-CN").setTimezoneId("Asia/Shanghai").setViewportSize(WIDTH, HEIGHT);
            Path profile = Path.of(profileDir).toAbsolutePath().normalize().resolve("account-" + accountId + ".json");
            if (Files.isRegularFile(profile)) options.setStorageStatePath(profile);
            BrowserContext context = browser.newContext(options);
            // Database credentials are authoritative; do not retain stale cookies from a previous login.
            context.clearCookies();
            List<Cookie> cookies = new ArrayList<>();
            cookieMap.forEach((name, value) -> {
                if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                    cookies.add(new Cookie(name, value).setDomain(".goofish.com").setPath("/"));
                    cookies.add(new Cookie(name, value).setDomain(".taobao.com").setPath("/"));
                }
            });
            context.addCookies(cookies);
            Page page = context.newPage();
            context.route("**/*", route -> {
                boolean mainNavigation = route.request().isNavigationRequest() && route.request().frame() == page.mainFrame();
                if (trustedUrl(route.request().url(), !mainNavigation)) route.resume(); else route.abort();
            });
            page.setDefaultTimeout(5000);
            try {
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(15000));
            } catch (PlaywrightException navigationFailure) {
                // A challenge can abort or delay navigation while rendering a usable page.
                if (!trustedUrl(page.url(), false)) throw navigationFailure;
            }
            Browser ownedBrowser = browser;
            return new Session() {
                public String screenshot() {
                    if (!trustedUrl(page.url(), false)) throw new IllegalStateException("验证页面跳转异常，请重新加载");
                    return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(page.screenshot(
                            new Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(75).setTimeout(5000)));
                }
                public Map<String, String> cookies() {
                    Map<String, String> result = new LinkedHashMap<>();
                    for (Cookie cookie : context.cookies(TOKEN_ORIGIN)) result.put(cookie.name, cookie.value);
                    return result;
                }
                public void input(List<Point> points) {
                    validatePoints(points);
                    Point first = points.getFirst();
                    page.mouse().move(first.x(), first.y());
                    page.mouse().down();
                    try {
                        long previous = 0;
                        for (Point point : points.subList(1, points.size())) {
                            long delay = point.elapsedMs() - previous;
                            if (delay > 0) Thread.sleep(delay);
                            page.mouse().move(point.x(), point.y());
                            previous = point.elapsedMs();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("验证操作已中断");
                    } finally { page.mouse().up(); }
                }
                public void close() {
                    try { context.close(); }
                    finally { try { ownedBrowser.close(); } finally { playwright.close(); } }
                }
            };
        } catch (RuntimeException failure) {
            try { if (browser != null) browser.close(); } finally { playwright.close(); }
            throw failure;
        }
    }
}
